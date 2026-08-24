package com.yep.kindle.dron;

import com.yep.kindle.dron.detection.TemporalEngine;
import com.yep.kindle.dron.detection.ThreatScorer;
import com.yep.kindle.dron.display.DisplayManager;
import com.yep.kindle.dron.display.RadarImageManager;
import com.yep.kindle.dron.display.RadarRenderer;
import com.yep.kindle.dron.event.AppEvent;
import com.yep.kindle.dron.event.EventBus;
import com.yep.kindle.dron.event.ScanScheduler;
import com.yep.kindle.dron.model.AP;
import com.yep.kindle.dron.model.DetectorContext;
import com.yep.kindle.dron.model.History;
import com.yep.kindle.dron.model.NetRecord;
import com.yep.kindle.dron.service.NetCsvStore;
import com.yep.kindle.dron.service.WeatherService;
import com.yep.kindle.dron.service.WifiScanner;
import com.yep.kindle.dron.util.AppLog;
import com.yep.kindle.dron.util.KindleUtils;
import com.yep.kindle.dron.web.AppDataManager;
import com.yep.kindle.dron.web.DeviceState;
import com.yep.kindle.dron.web.KindleWebServer;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * KindleDroneDetectorPro v2.5 — event-driven edition.
 *
 * <h3>Architecture overview (after refactoring)</h3>
 * <pre>
 *  ┌──────────────────────────────────────────────────────────────┐
 *  │  ScanScheduler (ScheduledExecutorService, daemon)            │
 *  │  ├── every 180 s  → post WIFI_SCAN_DUE                      │
 *  │  ├── every 300 s  → post WEATHER_REFRESH                    │
 *  │  ├── every 180 s  → post MAXPERF_DUE                        │
 *  │  ├── every 180 s  → post SAVE_DUE                           │
 *  │  ├── every 6000 s → post PBANK_CHECK_DUE                    │
 *  │  └── every  50 s  → CrcWorker (background): measure 2 s     │
 *  │                      then post IDLE_CRC_RESULT               │
 *  └──────────────────────────────────────────────────────────────┘
 *               │ post()
 *               ▼
 *  ┌──────────────────────────────────────────────────────────────┐
 *  │  EventBus  (LinkedBlockingQueue, capacity 256)               │
 *  └──────────────────────────────────────────────────────────────┘
 *    ▲                               ▲
 *    │ DeviceState.requestNextPage() │ KindleTcpListener
 *    │ → post PAGE_ADVANCE           │ → post PAGE_ADVANCE /
 *    │                               │   OVERLAY_MESSAGE
 *    │
 *    ├── main thread: EventBus.poll(5 s) ──────────────────────────
 *    │   Sleeps up to 5 s; wakes immediately on any event.
 *    │   Handles each AppEvent.Type in a switch statement.
 *    │   No modulo-counter polling; no blocking sleep in the loop.
 *    └────────────────────────────────────────────────────────────
 * </pre>
 *
 * Display rotation (each slot = 5 min):
 *   slot 0 — Weather PNG
 *   slot 1 — Moon Calendar PNG
 *   slot 2 — Space Weather PNG
 *   slot 3 — Radar (shown for 30 s via non-blocking postDelayed, then returns to slot 0)
 *
 * Wi-Fi scan: every 3 minutes, driven by ScanScheduler.
 * Emergency blink: alternating white/black flashes when drone score >= 60.
 */
public class KindleDroneDetectorPro {

    // ── Poll tick (outer loop sleep) ──────────────────────────────────────────
    static final long TICK_MS           = 5_000L;   // 5 s idle tick

    // ── Scan timing ───────────────────────────────────────────────────────────
    /** Scan Wi-Fi every 3 min: 36 × 5 s = 180 s */
    static final int  SCAN_EVERY        = 36;
    static final int  STATS_EVERY       = 5;
    static final int  PROBE_EVERY       = 6;
    static final int  IDLE_CRC_EVERY    = 10;
    static final int  MAXPERF_EVERY     = 36;
    static final int  SAVE_EVERY        = 36;

    // ── Display page timing ───────────────────────────────────────────────────
    /**
     * How long each info page stays on screen (ms).
     * Sequence: Weather → Moon → Space → Radar, then repeat.
     */
    static final long PAGE_HOLD_MS      = 300_000L;  // 5 min per page
    /** How long the radar image stays visible (ms). */
    static final long RADAR_HOLD_MS     = 30_000L;   // 30 s
    static final long MESSAGE_HOLD_MS   = 20_000L;   // 20 s temporary message overlay
    /** Re-render an info page PNG only if it is older than this (ms). */
    static final long INFO_REGEN_MS     = 10 * 60_000L; // 10 min

    // Display page indices
    static final int PAGE_WEATHER  = 0;
    static final int PAGE_MOON     = 1;
    static final int PAGE_SPACE    = 2;
    static final int PAGE_TEMP     = 3;
    static final int PAGE_RADAR    = 4;
    static final int PAGE_COUNT    = 5;

    static final String[] PAGE_FILES = {
        "/mnt/us/drone-app/img/weather.png",
        "/mnt/us/drone-app/img/moon.png",
        "/mnt/us/drone-app/img/spaceweather.png",
        "/mnt/us/drone-app/img/hometemp.png",
        RadarRenderer.IMAGE_FILE          // /mnt/us/drone-app/img/radar.png
    };
    static final String[] PAGE_NAMES = { "weather", "moon", "space", "hometemp", "radar" };

    // ── Misc thresholds ───────────────────────────────────────────────────────
    static final int  IDLE_CRC_THRESHOLD = 6;
    static final int  IDLE_CRC_CONFIRM   = 3;
    static final int  BASELINE_LOOPS     = 10;
    static final int  RADAR_DIST_DELTA_NEAR_M  = 8;
    static final int  RADAR_DIST_DELTA_MID_M   = 15;
    static final int  RADAR_DIST_DELTA_FAR_M   = 25;
    static final int  RADAR_DIST_DELTA_VFAR_M  = 40;
    static final String WEATHER_LOCATION = "Kharkiv";

    /** Weather data refresh interval. */
    static final long WEATHER_EVERY_MS  = 300_000L;

    // ── Emergency blink ───────────────────────────────────────────────────────
    static final int  BLINK_COUNT      = 6;
    static final int  BLINK_DELAY_MS   = 300;

    // ── File paths ────────────────────────────────────────────────────────────
    static final String APP_DIR      = "/mnt/us/drone-app";
    static final String LOG_FILE     = APP_DIR + "/logs/drone.log";
    static final String CSV_FILE     = APP_DIR + "/data/drone_nets.csv";
    static final String CSV_TMP      = APP_DIR + "/data/drone_nets.csv.tmp";
    static final String MESSAGE_FILE = APP_DIR + "/img/message.png";

    // ── Power bank keepalive ──────────────────────────────────────────────────
    /**
     * When battery is above this threshold and USB is charging, cap charge current
     * so the power bank always sees enough load to stay on.
     */
    static final int  PBANK_THROTTLE_ABOVE_PCT  = 85;
    /** Resume full charging once battery drops back below this threshold. */
    static final int  PBANK_RESTORE_BELOW_PCT   = 70;
    /** Value written to battery_suspend_current to cap charging (mA). */
    static final int  PBANK_LIMIT_MA            = 500;
    /** How often to check / re-apply the keepalive (every N loops = N × 5 s). */
    static final int  PBANK_CHECK_EVERY         = 12 * 100;  // 60 s * 100

    // ── Logging controls ──────────────────────────────────────────────────────
    static final long LOG_MAX_BYTES    = 256 * 1024;
    static final int  DUMP_THREAT_MIN  = 60;
    static final int  HEARTBEAT_EVERY  = 120;
    static final int  LOG_ROTATE_EVERY = 36;

    static final String[] PROBE_SSIDS = {
        "TELLO-", "DJI-", "Spark-", "PHANTOM", "Mavic-", "ANAFI-",
        "Bebop2-", "FPV-", "AVATA-", "SkyController"
    };

    // =========================================================================
    // Main
    // =========================================================================

    public static void main(String[] args) {
        System.setProperty("file.encoding", "UTF-8");
        System.setProperty("sun.jnu.encoding", "UTF-8");
        System.setProperty("java.awt.headless", "true");
        AppLog.info("=== KindleDroneDetectorPro v2.5-event ===");

        // ── Shared state ─────────────────────────────────────────────────────
        Map<String, NetRecord> knownNets = new LinkedHashMap<>();
        Set<String>            baseline  = new HashSet<>();
        TemporalEngine temporal = new TemporalEngine();
        ThreatScorer   scorer   = new ThreatScorer(temporal,
                RADAR_DIST_DELTA_NEAR_M, RADAR_DIST_DELTA_MID_M,
                RADAR_DIST_DELTA_FAR_M,  RADAR_DIST_DELTA_VFAR_M);
        DetectorContext ctx = new DetectorContext(
                BASELINE_LOOPS, IDLE_CRC_THRESHOLD, IDLE_CRC_CONFIRM, WEATHER_LOCATION,
                temporal, scorer, knownNets, baseline);
        ctx.startTime = System.currentTimeMillis();

        DisplayManager    display   = new DisplayManager();
        RadarImageManager radarMgr  = new RadarImageManager(AppLog::info);

        // ── Web server / shared state ─────────────────────────────────────────
        DeviceState    webState   = new DeviceState();
        KindleWebServer webServer = null;
        AppDataManager dataManager;

        dataManager = new AppDataManager(APP_DIR);
        webState.setDataManager(dataManager);
        webState.setWifiMonitor(new com.yep.kindle.dron.web.WifiMonitor());
        webState.setChipStatsMonitor(new com.yep.kindle.dron.web.ChipStatsMonitor());
        webState.loadConfig();
        KindleHomeTemp.setCsvPath(dataManager.getHomeTempCsvFile().getAbsolutePath());

        try {
            webServer = new KindleWebServer(webState);
            webServer.start();
        } catch (Exception e) {
            AppLog.err("Web server failed to start: " + e.getMessage());
            webServer = null;
        }

        final KindleWebServer webServerFinal  = webServer;
        final DeviceState     webStateFinal   = webState;
        final AppDataManager  dataManagerFinal = dataManager;

        // ── Startup ───────────────────────────────────────────────────────────
        KindleUtils.exec("lipc-set-prop", "com.lab126.powerd", "preventScreenSaver", "1");
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (webServerFinal != null) webServerFinal.stop();
            saveNetworksCsv(knownNets, dataManagerFinal);
            try { dataManagerFinal.saveDeviceState(webStateFinal); } catch (Exception ignored) { }
            KindleUtils.exec("lipc-set-prop", "com.lab126.powerd", "preventScreenSaver", "0");
            AppLog.info("Shutdown complete.");
        }));

        AppLog.init(LOG_FILE, LOG_MAX_BYTES);
        int loadedFromCsv = loadNetworksCsv(knownNets, baseline);
        boolean firstRun  = (loadedFromCsv < 3);

        if (!firstRun) {
            ctx.armed = true;
            AppLog.info("ARMED CSV:" + loadedFromCsv);
        } else {
            AppLog.info("No CSV – learning phase");
        }

        WifiScanner.initFirmware();
        int[] statsResult = new int[3];
        long  prevCRC     = WifiScanner.readStats(statsResult, true);
        ctx.noiseFloor    = statsResult[1];
        ctx.csSnr         = statsResult[2];

        KindleWelcomePage.generateAndShow(
                new java.io.File(dataManager.getImgDir(), "welcome.png").getAbsolutePath());
        KindleUtils.sleep(20000);
        try {
            String weatherPath = new java.io.File(dataManager.getImgDir(), "weather.png").getAbsolutePath();
            KindleWeatherNoKey.generateAndSaveForCity(weatherPath, webState.getConfigCity());
            KindleUtils.exec("eips", "-c");
            KindleUtils.sleep(200);
            KindleUtils.exec("eips", "-g", weatherPath);
        } catch (Exception e) {
            AppLog.err("Startup weather: " + e.getMessage());
        }

        ctx.weather = WeatherService.fetchWeather(WEATHER_LOCATION);

        // ── Display page state ────────────────────────────────────────────────
        int  currentPage = PAGE_WEATHER;
        long pageShownAt = System.currentTimeMillis();

        generateAndShowPage(currentPage, ctx, display, firstRun, radarMgr);
        AppLog.info("PAGE->weather");
        webState.setCurrentPage(PAGE_NAMES[currentPage]);

        try {
            java.net.InetAddress ia = java.net.InetAddress.getLocalHost();
            webState.setStatusMessage("Ready — http://" + ia.getHostAddress() + ":8080");
        } catch (Exception e) {
            webState.setStatusMessage("Ready — port 8080");
        }

        // ── Event bus + scheduler ─────────────────────────────────────────────
        // The CRC callback runs in the ScanScheduler's background crc-worker thread.
        // It calls the same WifiScanner.measureIdleCRC() but no longer blocks main.
        EventBus      bus       = EventBus.INSTANCE;
        ScanScheduler scheduler = new ScanScheduler(bus);
        scheduler.start(() -> {
            int[] r = new int[3];
            return WifiScanner.measureIdleCRC(r);
        });

        // Force the first Wi-Fi scan immediately (scheduler fires after initial delay).
        bus.post(AppEvent.wifiScanDue());

        // ── Mutable loop state ────────────────────────────────────────────────
        boolean emergencyActive     = false;
        boolean overlayVisible      = false;
        long    msgShownAt          = 0;
        long    shownOverlayId      = -1;
        boolean pbankThrottleActive = false;
        // Timestamp of the last radar show — used to schedule the 30-second hold
        // without blocking the main thread.
        boolean radarHoldActive     = false;
        long    radarHoldUntil      = 0;

        // =========================================================================
        // Event-driven main loop
        // =========================================================================
        while (true) {

            // Block up to TICK_MS waiting for the next event.
            // Returns immediately when a producer posts.
            AppEvent ev = bus.poll(TICK_MS, TimeUnit.MILLISECONDS);

            // ── Radar hold: non-blocking 30-second wait ───────────────────────
            // During the radar hold we skip normal event dispatch and just wait
            // for the hold timer to expire — new events queue up and are handled
            // once we return to normal operation.
            if (radarHoldActive) {
                if (System.currentTimeMillis() >= radarHoldUntil) {
                    radarHoldActive = false;
                    display.clearForPageSwitch("radar-done");
                    currentPage  = PAGE_WEATHER;
                    pageShownAt  = System.currentTimeMillis();
                    generateAndShowPage(currentPage, ctx, display, firstRun, radarMgr);
                    AppLog.info("PAGE->weather (post-radar)");
                    webState.setCurrentPage(PAGE_NAMES[currentPage]);
                    // Drain any stale events that accumulated during the hold.
                    bus.drainAll();
                }
                continue; // don't handle events during radar hold
            }

            // ── Handle incoming event (may be null on timeout) ────────────────
            if (ev != null) {
                switch (ev.type) {

                    // ── Scheduled Wi-Fi scan ──────────────────────────────────
                    case WIFI_SCAN_DUE: {
                        ctx.loop++;
                        ctx.statTotalScans++;

                        // Directed probe — rotate through known drone SSIDs
                        if (ctx.loop % PROBE_EVERY == 0) {
                            String probe = PROBE_SSIDS[(ctx.loop / PROBE_EVERY) % PROBE_SSIDS.length];
                            WifiScanner.sendProbe(probe);
                            AppLog.info("PROBE:" + probe);
                        } else {
                            WifiScanner.sendProbe("any");
                        }

                        List<AP> aps = WifiScanner.scan();
                        temporal.update(aps, ctx.armed, baseline);
                        updateKnownNets(aps, ctx);

                        int[] procResult = WifiScanner.readProcWireless();
                        if (procResult != null) {
                            ctx.linkQuality = procResult[0];
                            ctx.noiseFloor  = procResult[2];
                            ctx.csSnr       = procResult[1] - procResult[2];
                        }

                        // Read firmware stats inline after each scan (replaces STATS_EVERY tick)
                        long newCRC  = WifiScanner.readStats(statsResult, true);
                        ctx.crcDelta = newCRC - prevCRC;
                        prevCRC      = newCRC;
                        ctx.noiseFloor = statsResult[1];
                        ctx.csSnr      = statsResult[2];

                        scorer.score(aps, ctx.armed, baseline, knownNets);
                        aps.sort((a, b) -> b.threat != a.threat
                                ? b.threat - a.threat : Double.compare(a.dist, b.dist));

                        int maxThreat = aps.isEmpty() ? 0 : aps.get(0).threat;
                        if (maxThreat > ctx.statPeakThreat) ctx.statPeakThreat = maxThreat;

                        // Learning-phase arming
                        if (!ctx.armed) {
                            for (AP a : aps) {
                                baseline.add(a.mac);
                                NetRecord nr = knownNets.get(a.mac);
                                if (nr == null) { nr = new NetRecord(a.mac); knownNets.put(a.mac, nr); }
                                nr.update(a);
                            }
                            if (ctx.loop >= BASELINE_LOOPS) {
                                ctx.armed = true;
                                AppLog.info("ARMED:" + baseline.size() + " MACs");
                                saveNetworksCsv(knownNets);
                                firstRun = false;
                            }
                        }

                        boolean droneConfirmed = maxThreat >= 60;
                        if (droneConfirmed) {
                            ctx.statAlertEvents++;
                            if (!emergencyActive) {
                                emergencyActive = true;
                                AppLog.info("!! DRONE CONFIRMED thr=" + maxThreat);
                                emergencyBlink(display);
                                pageShownAt = System.currentTimeMillis();
                                generateAndShowPage(currentPage, ctx, display, firstRun, radarMgr);
                                // Also post a DRONE_ALERT event for any future consumers
                                bus.post(AppEvent.droneAlert(maxThreat, "thr=" + maxThreat));
                            }
                        } else {
                            emergencyActive = false;
                        }
                        if (ctx.externalRF) AppLog.info("RF active iCRC=" + ctx.idleCRC);

                        logLoopToFile(aps, maxThreat, ctx);
                        log(aps, ctx);

                        updateWebState(webState, ctx);
                        break;
                    }

                    // ── Background idle CRC result ────────────────────────────
                    case IDLE_CRC_RESULT: {
                        ctx.idleCRC = (int) ev.value;
                        if (ctx.idleCRC >= IDLE_CRC_THRESHOLD) {
                            ctx.externalRFcount++;
                            if (ctx.externalRFcount >= IDLE_CRC_CONFIRM) ctx.externalRF = true;
                            AppLog.info("RF iCRC=" + ctx.idleCRC + " cnt=" + ctx.externalRFcount);
                        } else {
                            if (ctx.externalRFcount > 0) ctx.externalRFcount--;
                            if (ctx.externalRFcount == 0) ctx.externalRF = false;
                        }
                        // Reset CRC delta — a fresh baseline was taken by measureIdleCRC
                        prevCRC      = WifiScanner.readStats(statsResult, true);
                        ctx.crcDelta = 0;
                        break;
                    }

                    // ── Weather data refresh ──────────────────────────────────
                    case WEATHER_REFRESH: {
                        WeatherService.WeatherData latest = WeatherService.fetchWeather(WEATHER_LOCATION);
                        ctx.weather = latest;
                        AppLog.info(latest.error == null ? "WX:" + latest.temp + "C" : "WX ERR");
                        break;
                    }

                    // ── Page advance (web UI or physical button) ──────────────
                    case PAGE_ADVANCE: {
                        // Run a manual refresh before switching page
                        WeatherService.WeatherData latest = WeatherService.fetchWeather(WEATHER_LOCATION);
                        ctx.weather = latest;
                        WifiScanner.sendProbe("manual");
                        List<AP> aps = WifiScanner.scan();
                        temporal.update(aps, ctx.armed, ctx.baseline);
                        updateKnownNets(aps, ctx);
                        ctx.scorer.score(aps, ctx.armed, ctx.baseline, ctx.knownNets);

                        webState.acknowledgePageRequest();
                        currentPage = advancePage(currentPage, ctx, display, firstRun, radarMgr,
                                ev.source != null ? ev.source.toUpperCase() : "EV");
                        pageShownAt = System.currentTimeMillis();
                        webState.setCurrentPage(PAGE_NAMES[currentPage]);

                        // If we just showed the radar, start the non-blocking hold
                        if (currentPage == PAGE_RADAR) {
                            radarHoldActive = true;
                            radarHoldUntil  = System.currentTimeMillis() + RADAR_HOLD_MS;
                        }
                        break;
                    }

                    // ── Overlay message ───────────────────────────────────────
                    case OVERLAY_MESSAGE: {
                        String msg = ev.payload != null ? ev.payload : webState.getLastMessage();
                        showOverlayMessage(display, msg);
                        msgShownAt      = System.currentTimeMillis();
                        overlayVisible  = true;
                        shownOverlayId  = System.currentTimeMillis(); // use timestamp as id
                        AppLog.info("MSG src=" + ev.source + " seq=ev");
                        // Schedule auto-dismiss
                        scheduler.postDelayed(MESSAGE_HOLD_MS, AppEvent.overlayClear("timeout"));
                        break;
                    }

                    // ── Overlay dismiss ───────────────────────────────────────
                    case OVERLAY_CLEAR: {
                        if (overlayVisible) {
                            overlayVisible = false;
                            shownOverlayId = -1;
                            pageShownAt    = System.currentTimeMillis();
                            generateAndShowPage(currentPage, ctx, display, firstRun, radarMgr);
                            AppLog.info("MSG-TIMEOUT -> PAGE->" + PAGE_NAMES[currentPage]);
                        }
                        break;
                    }

                    // ── Periodic save ─────────────────────────────────────────
                    case SAVE_DUE: {
                        saveNetworksCsv(knownNets, dataManager);
                        try { dataManager.saveDeviceState(webState); }
                        catch (Exception e) { AppLog.info("state-save: " + e.getMessage()); }
                        break;
                    }

                    // ── Firmware maxperf re-apply ─────────────────────────────
                    case MAXPERF_DUE: {
                        WifiScanner.reapplyMaxperf();
                        break;
                    }

                    // ── Power-bank keepalive check ────────────────────────────
                    case PBANK_CHECK_DUE: {
                        int batt       = com.yep.kindle.dron.util.SensorReader.readBatteryPercent();
                        int isCharging = com.yep.kindle.dron.util.SensorReader.readIsCharging();
                        if (isCharging == 1) {
                            if (!pbankThrottleActive && batt >= PBANK_THROTTLE_ABOVE_PCT) {
                                boolean ok = com.yep.kindle.dron.util.SensorReader
                                        .writeBatterySuspendCurrent(PBANK_LIMIT_MA);
                                pbankThrottleActive = ok;
                                AppLog.info("PBANK throttle ON batt=" + batt + "% ok=" + ok);
                            } else if (pbankThrottleActive && batt < PBANK_RESTORE_BELOW_PCT) {
                                boolean ok = com.yep.kindle.dron.util.SensorReader
                                        .writeBatterySuspendCurrent(0);
                                if (ok) pbankThrottleActive = false;
                                AppLog.info("PBANK throttle OFF batt=" + batt + "% ok=" + ok);
                            }
                        } else if (isCharging == 0 && pbankThrottleActive) {
                            com.yep.kindle.dron.util.SensorReader.writeBatterySuspendCurrent(0);
                            pbankThrottleActive = false;
                            AppLog.info("PBANK throttle OFF (USB removed)");
                        }
                        break;
                    }

                    // ── Rotation toggle from web UI ───────────────────────────
                    case ROTATION_TOGGLE: {
                        boolean on = (ev.value == 1);
                        AppLog.info("ROTATION " + (on ? "ON" : "OFF"));
                        if (on) {
                            // Reset timer so the current page gets its full hold after re-enable
                            pageShownAt = System.currentTimeMillis();
                        }
                        break;
                    }

                    default:
                        break;
                }
            }

            // ── Page auto-advance timer (checked on every wakeup) ─────────────
            long now = System.currentTimeMillis();
            if (!overlayVisible && !radarHoldActive && webState.isRotationEnabled()
                    && now - pageShownAt >= PAGE_HOLD_MS) {
                currentPage = (currentPage + 1) % PAGE_COUNT;
                pageShownAt = now;
                generateAndShowPage(currentPage, ctx, display, firstRun, radarMgr);
                AppLog.info("PAGE->" + PAGE_NAMES[currentPage]);
                webState.setCurrentPage(PAGE_NAMES[currentPage]);

                if (currentPage == PAGE_RADAR) {
                    // Start non-blocking 30-second hold instead of Thread.sleep
                    radarHoldActive = true;
                    radarHoldUntil  = System.currentTimeMillis() + RADAR_HOLD_MS;
                }
            }

            // ── Overlay auto-dismiss (fallback for expiry without OVERLAY_CLEAR) ─
            if (overlayVisible && now - msgShownAt >= MESSAGE_HOLD_MS) {
                overlayVisible = false;
                shownOverlayId = -1;
                pageShownAt    = System.currentTimeMillis();
                generateAndShowPage(currentPage, ctx, display, firstRun, radarMgr);
                AppLog.info("MSG-TIMEOUT-FALLBACK -> PAGE->" + PAGE_NAMES[currentPage]);
            }
        }
    }

    // =========================================================================
    // Display page generation + show
    // =========================================================================

    /**
     * Generate (if needed) and display the given page.
     * For PAGE_RADAR: render the radar image then block for RADAR_HOLD_MS so it
     * stays visible for 30 seconds before the caller returns.
     */
    static void generateAndShowPage(int page, DetectorContext ctx, DisplayManager display,
                                     boolean firstRun, RadarImageManager radarMgr) {
        switch (page) {
            case PAGE_WEATHER:
            case PAGE_MOON:
            case PAGE_SPACE:
                // These pages fetch external data — only regenerate when the cached PNG is stale.
                java.io.File f = new java.io.File(PAGE_FILES[page]);
                long ageMs = f.exists() ? System.currentTimeMillis() - f.lastModified() : Long.MAX_VALUE;
                if (ageMs > INFO_REGEN_MS) {
                    generateInfoImage(page, ctx);
                } else {
                    AppLog.info("PAGE-CACHED p=" + page + " age=" + (ageMs / 1000) + "s");
                }
                showPngOrFallback(PAGE_FILES[page], ctx, display);
                break;

            case PAGE_TEMP:
                // Home Temperature reads a local sensor — always regenerate so the display
                // shows the current reading, not a cached morning value.
                generateInfoImage(PAGE_TEMP, ctx);
                showPngOrFallback(PAGE_FILES[PAGE_TEMP], ctx, display);
                break;

            case PAGE_RADAR:
                showRadarPage(ctx, display, firstRun, radarMgr);
                break;

            default:
                break;
        }
    }

    // runManualRefresh() has been inlined into the PAGE_ADVANCE event handler
    // in the main event loop. Kept here as a no-op stub for backward compatibility
    // with any tooling that may reference it.
    static long runManualRefresh(DetectorContext ctx, boolean firstRun) {
        try {
            WeatherService.WeatherData latest = WeatherService.fetchWeather(WEATHER_LOCATION);
            ctx.weather = latest;
            AppLog.info(latest.error == null ? "WX manual:" + latest.temp + "C" : "WX manual ERR");

            WifiScanner.sendProbe("manual");
            List<AP> aps = WifiScanner.scan();
            TemporalEngine temporal = ctx.temporal;
            temporal.update(aps, ctx.armed, ctx.baseline);
            updateKnownNets(aps, ctx);

            int[] procResult = WifiScanner.readProcWireless();
            if (procResult != null) {
                ctx.linkQuality = procResult[0];
                ctx.noiseFloor  = procResult[2];
                ctx.csSnr       = procResult[1] - procResult[2];
            }

            ctx.scorer.score(aps, ctx.armed, ctx.baseline, ctx.knownNets);
            int maxThreat = 0;
            for (AP ap : aps) {
                if (ap.threat > maxThreat) {
                    maxThreat = ap.threat;
                }
            }
            if (maxThreat > ctx.statPeakThreat) {
                ctx.statPeakThreat = maxThreat;
            }
            AppLog.info("MANUAL scan aps=" + aps.size() + " thr=" + maxThreat + (firstRun ? " FIRST_RUN" : ""));
        } catch (Exception e) {
            AppLog.info("MANUAL refresh err=" + e.getMessage());
        }

        return System.currentTimeMillis();
    }

    /**
     * Render the radar image and display it on the e-ink screen.
     *
     * <p><b>Non-blocking:</b> this method no longer calls
     * {@code KindleUtils.sleep(RADAR_HOLD_MS)}.  The 30-second hold is
     * implemented in the main event loop via {@code radarHoldActive /
     * radarHoldUntil} timestamps so the main thread is not blocked and
     * can still react to urgent events (e.g. emergency blink).</p>
     */
    static void showRadarPage(DetectorContext ctx, DisplayManager display,
                               boolean firstRun, RadarImageManager radarMgr) {
        List<AP> radarAps = buildRadarAps(ctx, firstRun);

        if (radarAps.isEmpty()) {
            AppLog.info("RADAR empty, skip");
            return;
        }

        boolean shown = radarMgr.renderAndShow(radarAps, ctx);
        AppLog.info("RADAR shown=" + shown + " aps=" + radarAps.size()
                + (firstRun ? " FIRST_RUN" : " NEW_ONLY"));
        // Caller is responsible for tracking the 30-second hold (radarHoldActive flag).
        // display.clearForPageSwitch is called by the main loop once hold expires.
    }

    /**
     * Build the AP list for radar rendering.
     *
     * First run: all APs that have been seen (from knownNets, present in current scan).
     * Subsequent runs: only APs whose MAC was NOT in the baseline loaded from CSV.
     *
     * We reconstruct AP objects from NetRecord so this works even between scans.
     */
    static List<AP> buildRadarAps(DetectorContext ctx, boolean firstRun) {
        List<AP> result = new ArrayList<>();
        long now = System.currentTimeMillis();
        // Only include APs seen in the last 10 minutes
        long cutoff = now - 600_000L;

        for (NetRecord nr : ctx.knownNets.values()) {
            if (nr.lastSeen < cutoff) continue;
            // On subsequent runs, skip MACs that were in the CSV baseline
            if (!firstRun && ctx.baseline.contains(nr.mac)) continue;

            // Reconstruct a minimal AP from the NetRecord
            AP a = new AP();
            a.mac       = nr.mac;
            a.ssid      = nr.ssid != null ? nr.ssid : "";
            a.signalDbm = nr.peakSignal;
            a.channel   = nr.lastChannel;
            a.dist      = nr.lastDistM >= 0 ? nr.lastDistM
                        : (!nr.distHistory.isEmpty() ? nr.distHistory.peekLast() : 100);
            a.distDeltaM = nr.lastDistDeltaM;
            a.flags     = nr.lastFlags != null ? nr.lastFlags : "";
            a.threat    = 0; // scorer not re-run here, displayed as neutral
            result.add(a);
        }
        // Sort by distance ascending (nearest first)
        result.sort((a, b) -> Double.compare(a.dist, b.dist));
        return result;
    }

    // =========================================================================
    // Info image generation (Weather / Moon / Space)
    // =========================================================================

    static void generateInfoImage(int page, DetectorContext ctx) {
        try {
            switch (page) {
                case PAGE_WEATHER:
                    KindleWeatherNoKey.generateAndSave(PAGE_FILES[PAGE_WEATHER]);
                    break;
                case PAGE_MOON:
                    KindleMoonCalendarNoKey.generateAndSave(PAGE_FILES[PAGE_MOON]);
                    break;
                case PAGE_SPACE:
                    KindleSpaceWeatherNoKey.generateAndSave(PAGE_FILES[PAGE_SPACE]);
                    break;
                case PAGE_TEMP:
                    KindleHomeTemp.generateAndSave(PAGE_FILES[PAGE_TEMP]);
                    break;
                default:
                    break;
            }
        } catch (Exception e) {
            AppLog.info("INFO-ERR p=" + page + " " + e.getMessage());
        }
    }

    /** Show PNG via eips -g, falling back to text weather if the file is missing. */
    static void showPngOrFallback(String file, DetectorContext ctx, DisplayManager display) {
        java.io.File f = new java.io.File(file);
        if (f.exists()) {
            KindleUtils.exec("eips", "-c");
            KindleUtils.sleep(200);
            KindleUtils.exec("eips", "-g", file);
            KindleUtils.sleep(500);
        } else {
            display.showWeatherPage(ctx);
        }
    }

    static void showOverlayMessage(DisplayManager display, String message) {
        String text = (message == null || message.trim().isEmpty()) ? "(empty)" : message.trim();
        try {
            renderMessageImage(text, MESSAGE_FILE);
            KindleUtils.exec("eips", "-c");
            KindleUtils.sleep(120);
            KindleUtils.exec("eips", "-g", MESSAGE_FILE);
            KindleUtils.sleep(250);
        } catch (Exception e) {
            display.clearForPageSwitch("msg-fallback");
            KindleUtils.exec("eips", "0", "8", "MESSAGE");
            KindleUtils.exec("eips", "0", "10", text);
            AppLog.info("MSG render fallback: " + e.getMessage());
        }
    }

    static void renderMessageImage(String text, String targetFile) throws IOException {
        final int width = 600;
        final int height = 800;

        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, width, height);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        g.setColor(Color.BLACK);
        g.setFont(new Font("Dialog", Font.BOLD, 34));
        g.drawString("Incoming Message", 90, 110);

        Font funny = new Font("Comic Sans MS", Font.BOLD, 52);
        if (!"Comic Sans MS".equalsIgnoreCase(funny.getFamily())) {
            funny = new Font("Dialog", Font.BOLD, 52);
        }
        g.setFont(funny);

        List<String> lines = wrapForWidth(g, text, width - 80);
        int y = 250;
        FontMetrics fm = g.getFontMetrics();
        int lineHeight = fm.getHeight() + 12;
        for (String line : lines) {
            int lineW = fm.stringWidth(line);
            int x = Math.max(20, (width - lineW) / 2);
            g.drawString(line, x, y);
            y += lineHeight;
            if (y > height - 120) {
                break;
            }
        }

        g.setFont(new Font("Dialog", Font.PLAIN, 20));
        g.drawString("Visible for 20 seconds", 185, height - 60);
        g.dispose();

        ImageIO.write(img, "png", new File(targetFile));
    }

    static List<String> wrapForWidth(Graphics2D g, String text, int maxWidth) {
        List<String> lines = new ArrayList<>();
        if (text == null || text.trim().isEmpty()) {
            lines.add("(empty)");
            return lines;
        }

        FontMetrics fm = g.getFontMetrics();
        String[] words = text.trim().split("\\s+");
        StringBuilder line = new StringBuilder();
        for (String word : words) {
            String candidate = line.length() == 0 ? word : line + " " + word;
            if (fm.stringWidth(candidate) <= maxWidth) {
                line.setLength(0);
                line.append(candidate);
                continue;
            }
            if (line.length() > 0) {
                lines.add(line.toString());
                line.setLength(0);
                line.append(word);
            } else {
                lines.add(word);
            }
        }
        if (line.length() > 0) {
            lines.add(line.toString());
        }
        return lines;
    }

    // =========================================================================
    // Emergency blink — alternating white / black full-panel flashes
    // =========================================================================

    static void emergencyBlink(DisplayManager display) {
        display.clearForPageSwitch("");
        for (int i = 0; i < BLINK_COUNT; i++) {
            KindleUtils.exec("eips", "-f");   // white flash
            KindleUtils.sleep(BLINK_DELAY_MS);
            KindleUtils.exec("eips", "-c");   // black clear
            KindleUtils.sleep(BLINK_DELAY_MS);
        }
        KindleUtils.exec("eips", "0", "10", "!! DRONE DETECTED !!");
        KindleUtils.sleep(2000);
        display.clearForPageSwitch("");
    }

    // =========================================================================
    // CSV persistence
    // =========================================================================

    static int loadNetworksCsv(Map<String, NetRecord> knownNets, Set<String> baseline) {
        int count = NetCsvStore.load(CSV_FILE, knownNets, baseline, AppLog::info);
        if (count > 0) AppLog.info("CSV: " + count + " nets");
        return count;
    }

    static void saveNetworksCsv(Map<String, NetRecord> knownNets) {
        NetCsvStore.save(CSV_TMP, CSV_FILE, knownNets, AppLog::info);
    }

    static void saveNetworksCsv(Map<String, NetRecord> knownNets, AppDataManager dm) {
        if (dm != null) {
            NetCsvStore.save(dm.getCsvTempFile().getAbsolutePath(),
                             dm.getCsvFile().getAbsolutePath(),
                             knownNets, AppLog::info);
        } else {
            saveNetworksCsv(knownNets);
        }
    }

    /**
     * Advance to the next display page, skip radar directly back to weather.
     * Returns the new currentPage value.
     */
    static int advancePage(int current, DetectorContext ctx, DisplayManager display,
                           boolean firstRun, RadarImageManager radarMgr, String src) {
        current = (current + 1) % PAGE_COUNT;
        generateAndShowPage(current, ctx, display, firstRun, radarMgr);
        AppLog.info(src + " PAGE->" + PAGE_NAMES[current]);
        if (current == PAGE_RADAR) {
            current = PAGE_WEATHER;
            generateAndShowPage(current, ctx, display, firstRun, radarMgr);
            AppLog.info(src + " PAGE->weather (post-radar)");
        }
        return current;
    }

    /**
     * Push current detector state into DeviceState so the web UI sees fresh values.
     * Reads real battery from sysfs when available; falls back to runtime estimation.
     */
    static void updateWebState(DeviceState ws, DetectorContext ctx) {
        if (ws == null || ctx == null) return;
        // Battery and temperature are read fresh by SensorReader on each web request.
        // Only push detector status here.
        int apCount = ctx.knownNets.size();
        String threat = ctx.statPeakThreat >= 60 ? "DRONE DETECTED!" :
                        ctx.statPeakThreat >= 30 ? "Suspicious"      : "Clear";
        ws.setStatusMessage(String.format("APs:%d  Threat:%s(%d)  Loop:%d",
                apCount, threat, ctx.statPeakThreat, ctx.loop));
    }

    // =========================================================================
    // Known-nets updater
    // =========================================================================

    static void updateKnownNets(List<AP> aps, DetectorContext ctx) {
        Map<String, NetRecord> knownNets = ctx.knownNets;
        Set<String>            baseline  = ctx.baseline;

        for (AP a : aps) {
            if (a.mac == null || a.mac.isEmpty()) continue;
            NetRecord nr = knownNets.get(a.mac);
            if (nr == null) {
                nr = new NetRecord(a.mac);
                knownNets.put(a.mac, nr);
                if (ctx.armed && !baseline.contains(a.mac)) {
                    ctx.statNewArmed++;
                    AppLog.info("NEW " + a.mac + " " + a.ssid + " " + (int) a.dist + "m");
                }
            }
            nr.update(a);
            a.distDeltaM = nr.lastDistDeltaM;

            if (!baseline.contains(a.mac)) {
                String nm = (a.ssid != null && !a.ssid.isEmpty()
                        && !"[HIDDEN]".equals(a.ssid)) ? a.ssid : a.mac;
                if (nr.surgeDetected) {
                    AppLog.info("SURGE " + nm + " +" + nr.surgeRawDelta + "dBm " + nr.lastDistM + "m");
                }
                if (nr.consecutiveApproach == 3) {
                    AppLog.info("APPROACH " + nm + " " + nr.lastDistM + "m");
                }
            }
        }
        if (knownNets.size() > 2000) {
            Iterator<String> it = knownNets.keySet().iterator();
            while (knownNets.size() > 1800 && it.hasNext()) { it.next(); it.remove(); }
        }
    }

    // =========================================================================
    // Logging  (file I/O delegated to AppLog; only formatting lives here)
    // =========================================================================

    /** Compact console+file log — only notable APs, written on every scan. */
    static void log(List<AP> aps, DetectorContext ctx) {
        int notable = 0;
        for (AP a : aps) {
            History h = ctx.temporal.get(a.mac);
            if (a.threat > 0 || (h != null && (h.isNew || h.isMoving))) notable++;
        }
        AppLog.info(String.format("#%d APs:%d notable:%d CRC+%d NF:%d%s",
                ctx.loop, aps.size(), notable, ctx.crcDelta, ctx.noiseFloor,
                ctx.externalRF ? " !!RF" : ""));
        for (AP a : aps) {
            History h = ctx.temporal.get(a.mac);
            if (a.threat < 30 && (h == null || (!h.isNew && !h.isMoving))) continue;
            AppLog.info(String.format("  %3d %-17s %-12s %4ddBm %4.0fm %s",
                    a.threat, a.mac, a.ssid, a.signalDbm, a.dist, a.flags));
        }
    }

    /** Detailed scan log — compact summary every scan; full AP dump on notable events. */
    static void logLoopToFile(List<AP> aps, int maxThreat, DetectorContext ctx) {
        AppLog.info(String.format("#%d AP:%d THR:%d NF:%d%s",
                ctx.loop, aps.size(), maxThreat,
                ctx.noiseFloor, ctx.externalRF ? " RF" : ""));

        boolean heartbeat = (ctx.loop % HEARTBEAT_EVERY == 0);
        if (maxThreat < DUMP_THREAT_MIN && !ctx.externalRF && !heartbeat) return;

        int idx = 0, skipped = 0;
        for (AP a : aps) {
            idx++;
            History   h  = ctx.temporal.get(a.mac);
            NetRecord nr = ctx.knownNets.get(a.mac);
            boolean isNew = h != null && h.isNew;
            boolean isMov = h != null && h.isMoving;
            boolean notable = a.threat >= 10 || isNew || isMov
                    || (nr != null && (nr.surgeDetected || nr.consecutiveApproach >= 3));
            if (!notable && !(heartbeat && idx <= 3)) { skipped++; continue; }
            int emaM = nr != null && nr.lastDistM >= 0 ? nr.lastDistM : (int) a.dist;
            AppLog.info(String.format("  %s %-17s %-12s %3ddBm %4dm thr=%-3d %s%s%s [%s]",
                    ctx.baseline.contains(a.mac) ? "B" : "N",
                    a.mac, a.ssid, a.signalDbm, emaM, a.threat,
                    isNew ? "N" : "-", isMov ? "M" : "-",
                    a.ghost ? "G" : "-", a.flags));
        }
        if (skipped > 0) AppLog.info(String.format("  +%d bg", skipped));
    }
}
