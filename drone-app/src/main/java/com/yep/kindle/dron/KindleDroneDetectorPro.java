package com.yep.kindle.dron;

import java.io.*;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.*;
import javax.imageio.ImageIO;

import com.yep.kindle.dron.detection.TemporalEngine;
import com.yep.kindle.dron.detection.ThreatScorer;
import com.yep.kindle.dron.display.DisplayManager;
import com.yep.kindle.dron.display.RadarImageManager;
import com.yep.kindle.dron.display.RadarRenderer;
import com.yep.kindle.dron.model.AP;
import com.yep.kindle.dron.model.DetectorContext;
import com.yep.kindle.dron.model.History;
import com.yep.kindle.dron.model.NetRecord;
import com.yep.kindle.dron.service.NetCsvStore;
import com.yep.kindle.dron.service.WeatherService;
import com.yep.kindle.dron.service.WifiScanner;
import com.yep.kindle.dron.tool.KindleTcpListener;
import com.yep.kindle.dron.util.KindleUtils;

/**
 * KindleDroneDetectorPro v2.5 — power-saving edition with fixed display rotation.
 *
 * Display rotation (each slot = 5 min):
 *   slot 0 — Weather PNG
 *   slot 1 — Moon Calendar PNG
 *   slot 2 — Space Weather PNG
 *   slot 3 — Radar (shown for 30 s, then returns to slot 0)
 *
 * Radar content:
 *   First run (no CSV)  → show ALL scanned APs (explore mode)
 *   Subsequent runs     → show only APs NOT in the loaded CSV baseline (new only)
 *
 * Wi-Fi scan: every 3 minutes, independent of display.
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

    // Display page indices
    static final int PAGE_WEATHER  = 0;
    static final int PAGE_MOON     = 1;
    static final int PAGE_SPACE    = 2;
    static final int PAGE_TEMP     = 3;
    static final int PAGE_RADAR    = 4;
    static final int PAGE_COUNT    = 5;

    static final String[] PAGE_FILES = {
        "/mnt/us/weather.png",
        "/mnt/us/moon.png",
        "/mnt/us/spaceweather.png",
        "/mnt/us/hometemp.png",
        RadarRenderer.IMAGE_FILE          // /mnt/us/radar.png
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
    static final String LOG_FILE = "/mnt/us/drone_log.txt";
    static final String CSV_FILE = "/mnt/us/drone_nets.csv";
    static final String CSV_TMP  = "/mnt/us/drone_nets.csv.tmp";
    static final String MESSAGE_FILE = "/mnt/us/message.png";

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
        System.setProperty("java.awt.headless", "true");
        System.out.println("=== KindleDroneDetectorPro v2.5 ===");

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

        DisplayManager    display  = new DisplayManager();
        RadarImageManager radarMgr = new RadarImageManager(KindleDroneDetectorPro::logFile);

        // ── Startup ───────────────────────────────────────────────────────────
        KindleUtils.exec("lipc-set-prop", "com.lab126.powerd", "preventScreenSaver", "1");
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            saveNetworksCsv(knownNets);
            KindleUtils.exec("lipc-set-prop", "com.lab126.powerd", "preventScreenSaver", "0");
            System.out.println("Shutdown: CSV saved.");
        }));

        openLogFile();
        int loadedFromCsv = loadNetworksCsv(knownNets, baseline);
        // firstRun = true means no prior CSV → radar will show all APs
        boolean firstRun = (loadedFromCsv < 3);

        if (!firstRun) {
            ctx.armed = true;
            logFile("ARMED CSV:" + loadedFromCsv);
        } else {
            System.out.println("No CSV – learning phase");
        }

        WifiScanner.initFirmware();
        int[] statsResult = new int[3];
        long prevCRC = WifiScanner.readStats(statsResult, true);
        ctx.noiseFloor = statsResult[1];
        ctx.csSnr      = statsResult[2];

        ctx.weather = WeatherService.fetchWeather(WEATHER_LOCATION);
        long lastWeatherFetchAt = System.currentTimeMillis();

        // ── Display page state ────────────────────────────────────────────────
        int  currentPage     = PAGE_WEATHER;
        long pageShownAt     = System.currentTimeMillis();

        // Generate and show the initial weather page
        generateAndShowPage(currentPage, ctx, display, firstRun, radarMgr);
        logFile("PAGE->weather");

        boolean listenerStarted = KindleTcpListener.startAsync();
        logFile(listenerStarted ? "TCP listener started on :5555" : "TCP listener already running");
        long handledRefreshAndNextSeq = KindleTcpListener.getRefreshAndNextPageSeq();

        // ── Scan / alert state ────────────────────────────────────────────────
        boolean emergencyActive = false;
        long shownOverlayId = -1;
        boolean overlayVisible = false;

        // =========================================================================
        // Main loop
        // =========================================================================
        while (true) {
            long t0 = System.currentTimeMillis();
            ctx.loop++;
            ctx.statTotalScans++;

            // ── Re-apply maxperf periodically ─────────────────────────────────
            if (ctx.loop % MAXPERF_EVERY == 0) {
                WifiScanner.reapplyMaxperf();
            }

            // ── Wi-Fi scan (every 3 min) ───────────────────────────────────────
            if (ctx.loop % SCAN_EVERY == 0 || ctx.loop == 1) {
                if (ctx.loop % PROBE_EVERY == 0) {
                    String probe = PROBE_SSIDS[(ctx.loop / PROBE_EVERY) % PROBE_SSIDS.length];
                    WifiScanner.sendProbe(probe);
                    logFile("PROBE:" + probe);
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
                        logFile("ARMED:" + baseline.size() + " MACs");
                        saveNetworksCsv(knownNets);
                        firstRun = false;
                    }
                }

                // Emergency blink ONLY on confirmed drone score (externalRF alone is
                // background RF noise — not enough for a drone alert).
                boolean droneConfirmed = maxThreat >= 60;
                if (droneConfirmed) {
                    ctx.statAlertEvents++;
                    if (!emergencyActive) {
                        emergencyActive = true;
                        logFile("!! DRONE CONFIRMED thr=" + maxThreat);
                        emergencyBlink(display);
                        // Redraw current page and reset the hold timer so the 5-min
                        // countdown restarts from now (blink cleared the screen).
                        pageShownAt = System.currentTimeMillis();
                        generateAndShowPage(currentPage, ctx, display, firstRun, radarMgr);
                    }
                } else {
                    emergencyActive = false;
                }
                // Log RF presence separately — informational only
                if (ctx.externalRF) {
                    logFile("RF active iCRC=" + ctx.idleCRC);
                }

                logLoopToFile(aps, maxThreat, ctx);
                log(aps, ctx);
            }

            // ── Weather refresh ────────────────────────────────────────────────
            long now = System.currentTimeMillis();
            if (now - lastWeatherFetchAt >= WEATHER_EVERY_MS) {
                lastWeatherFetchAt = now;
                WeatherService.WeatherData latest = WeatherService.fetchWeather(WEATHER_LOCATION);
                ctx.weather = latest;
                logFile(latest.error == null ? "WX:" + latest.temp + "C" : "WX ERR");
            }

            long refreshAndNextSeq = KindleTcpListener.getRefreshAndNextPageSeq();
            if (refreshAndNextSeq != handledRefreshAndNextSeq) {
                handledRefreshAndNextSeq = refreshAndNextSeq;
                lastWeatherFetchAt = runManualRefresh(ctx, firstRun);

                currentPage = (currentPage + 1) % PAGE_COUNT;
                pageShownAt = System.currentTimeMillis();
                generateAndShowPage(currentPage, ctx, display, firstRun, radarMgr);
                logFile("BTN PAGE->" + PAGE_NAMES[currentPage] + " (manual refresh)");

                if (currentPage == PAGE_RADAR) {
                    currentPage = PAGE_WEATHER;
                    pageShownAt = System.currentTimeMillis();
                    generateAndShowPage(currentPage, ctx, display, firstRun, radarMgr);
                    logFile("BTN PAGE->weather (post-radar)");
                }
            }

            // ── Firmware stats ─────────────────────────────────────────────────
            if (ctx.loop % STATS_EVERY == 0) {
                long newCRC = WifiScanner.readStats(statsResult, true);
                ctx.crcDelta = newCRC - prevCRC;
                prevCRC      = newCRC;
            }

            // ── Idle CRC ──────────────────────────────────────────────────────
            if (ctx.loop % IDLE_CRC_EVERY == 0) {
                ctx.idleCRC = WifiScanner.measureIdleCRC(statsResult);
                if (ctx.idleCRC >= IDLE_CRC_THRESHOLD) {
                    ctx.externalRFcount++;
                    if (ctx.externalRFcount >= IDLE_CRC_CONFIRM) ctx.externalRF = true;
                    logFile("RF iCRC=" + ctx.idleCRC + " cnt=" + ctx.externalRFcount);
                } else {
                    if (ctx.externalRFcount > 0) ctx.externalRFcount--;
                    if (ctx.externalRFcount == 0) ctx.externalRF = false;
                }
                prevCRC      = WifiScanner.readStats(statsResult, true);
                ctx.crcDelta = 0;
            }

            // ── Page rotation ─────────────────────────────────────────────────
            KindleTcpListener.OverlaySnapshot overlay = KindleTcpListener.getActiveOverlay();
            if (overlay != null) {
                if (!overlayVisible || shownOverlayId != overlay.id) {
                    showOverlayMessage(display, overlay.message);
                    shownOverlayId = overlay.id;
                    overlayVisible = true;
                    logFile("MSG shown id=" + overlay.id);
                }
            } else if (overlayVisible) {
                overlayVisible = false;
                shownOverlayId = -1;
                pageShownAt = System.currentTimeMillis();
                generateAndShowPage(currentPage, ctx, display, firstRun, radarMgr);
                logFile("MSG done -> PAGE->" + PAGE_NAMES[currentPage]);
            }

            // Advance to next page when the hold time has elapsed.
            now = System.currentTimeMillis();
            if (!overlayVisible && now - pageShownAt >= PAGE_HOLD_MS) {
                currentPage = (currentPage + 1) % PAGE_COUNT;
                pageShownAt = now;
                generateAndShowPage(currentPage, ctx, display, firstRun, radarMgr);
                logFile("PAGE->" + PAGE_NAMES[currentPage]);

                // After the radar slot finishes its 30-second hold, the screen is
                // cleared inside showRadarPage().  Immediately advance to Weather so
                // the display is never left blank for the remaining ~4:30 of the slot.
                if (currentPage == PAGE_RADAR) {
                    currentPage = PAGE_WEATHER;
                    pageShownAt = System.currentTimeMillis();
                    generateAndShowPage(currentPage, ctx, display, firstRun, radarMgr);
                    logFile("PAGE->weather (post-radar)");
                }
            }

            // ── Periodic CSV save ──────────────────────────────────────────────
            if (ctx.loop % SAVE_EVERY == 0 && ctx.armed) {
                saveNetworksCsv(knownNets);
            }

            if (ctx.loop % LOG_ROTATE_EVERY == 0) rotateLogIfNeeded();

            long wait = TICK_MS - (System.currentTimeMillis() - t0);
            if (wait > 0) KindleUtils.sleep(wait);
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
                generateInfoImage(PAGE_WEATHER, ctx);
                showPngOrFallback(PAGE_FILES[PAGE_WEATHER], ctx, display);
                break;

            case PAGE_MOON:
                generateInfoImage(PAGE_MOON, ctx);
                showPngOrFallback(PAGE_FILES[PAGE_MOON], ctx, display);
                break;

            case PAGE_SPACE:
                generateInfoImage(PAGE_SPACE, ctx);
                showPngOrFallback(PAGE_FILES[PAGE_SPACE], ctx, display);
                break;

            case PAGE_TEMP:
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

    static long runManualRefresh(DetectorContext ctx, boolean firstRun) {
        try {
            WeatherService.WeatherData latest = WeatherService.fetchWeather(WEATHER_LOCATION);
            ctx.weather = latest;
            logFile(latest.error == null ? "WX manual:" + latest.temp + "C" : "WX manual ERR");

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
            logFile("MANUAL scan aps=" + aps.size() + " thr=" + maxThreat + (firstRun ? " FIRST_RUN" : ""));
        } catch (Exception e) {
            logFile("MANUAL refresh err=" + e.getMessage());
        }

        return System.currentTimeMillis();
    }

    /**
     * Render the radar image and hold it on screen for RADAR_HOLD_MS.
     * <p>
     * First run (no CSV loaded): show ALL visible APs — the user gets a "full map"
     * of all Wi-Fi signals in range so they can see the device is working.
     * <p>
     * Subsequent runs: show only APs whose MAC is NOT in the CSV baseline, i.e.,
     * devices that appeared since the last run.
     */
    static void showRadarPage(DetectorContext ctx, DisplayManager display,
                               boolean firstRun, RadarImageManager radarMgr) {
        // Collect the most recent scan result from knownNets
        List<AP> radarAps = buildRadarAps(ctx, firstRun);

        if (radarAps.isEmpty()) {
            // Nothing to show — just put up a text placeholder for the hold period
            display.clearForPageSwitch("radar-empty");
            KindleUtils.exec("eips", "0", "10", "RADAR: no new MACs");
            KindleUtils.sleep(RADAR_HOLD_MS);
            display.clearForPageSwitch("");
            logFile("RADAR empty");
            return;
        }

        // Render + display (eips -g is called inside radarMgr.renderAndShow)
        boolean shown = radarMgr.renderAndShow(radarAps, ctx);
        logFile("RADAR shown=" + shown + " aps=" + radarAps.size()
                + (firstRun ? " FIRST_RUN" : " NEW_ONLY"));

        if (shown) {
            // Hold the radar image visible for 30 seconds
            KindleUtils.sleep(RADAR_HOLD_MS);
        }

        // Restore display cache so the next info page draws cleanly
        display.clearForPageSwitch("radar-done");
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
            logFile("INFO-ERR p=" + page + " " + e.getMessage());
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
            logFile("MSG render fallback: " + e.getMessage());
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
        int count = NetCsvStore.load(CSV_FILE, knownNets, baseline,
                KindleDroneDetectorPro::logFile);
        if (count > 0) System.out.println("CSV: " + count + " nets");
        return count;
    }

    static void saveNetworksCsv(Map<String, NetRecord> knownNets) {
        NetCsvStore.save(CSV_TMP, CSV_FILE, knownNets, KindleDroneDetectorPro::logFile);
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
                    logFile("NEW " + a.mac + " " + a.ssid + " " + (int) a.dist + "m");
                }
            }
            nr.update(a);
            a.distDeltaM = nr.lastDistDeltaM;

            if (!baseline.contains(a.mac)) {
                String nm = (a.ssid != null && !a.ssid.isEmpty()
                        && !"[HIDDEN]".equals(a.ssid)) ? a.ssid : a.mac;
                if (nr.surgeDetected) {
                    logFile("SURGE " + nm + " +" + nr.surgeRawDelta + "dBm " + nr.lastDistM + "m");
                }
                if (nr.consecutiveApproach == 3) {
                    logFile("APPROACH " + nm + " " + nr.lastDistM + "m");
                }
            }
        }
        if (knownNets.size() > 2000) {
            Iterator<String> it = knownNets.keySet().iterator();
            while (knownNets.size() > 1800 && it.hasNext()) { it.next(); it.remove(); }
        }
    }

    // =========================================================================
    // Logging
    // =========================================================================

    static PrintWriter logWriter = null;

    static void openLogFile() {
        try {
            logWriter = new PrintWriter(new BufferedWriter(new OutputStreamWriter(
                    new FileOutputStream(LOG_FILE, true),
                    java.nio.charset.StandardCharsets.UTF_8)), true);
            logWriter.println("--- START " + new Date() + " ---");
        } catch (IOException e) {
            System.err.println("log open: " + e.getMessage());
        }
    }

    static void rotateLogIfNeeded() {
        try {
            File f = new File(LOG_FILE);
            if (f.length() < LOG_MAX_BYTES) return;
            if (logWriter != null) logWriter.close();
            File bak = new File(LOG_FILE + ".1");
            if (bak.exists()) bak.delete();
            f.renameTo(bak);
            openLogFile();
        } catch (Exception e) {
            System.err.println("log rotate: " + e.getMessage());
        }
    }

    static void logFile(String msg) {
        if (logWriter != null) logWriter.printf("%tT %s%n", System.currentTimeMillis(), msg);
    }

    /** Compact console log — only notable APs. */
    static void log(List<AP> aps, DetectorContext ctx) {
        int notable = 0;
        for (AP a : aps) {
            History h = ctx.temporal.get(a.mac);
            if (a.threat > 0 || (h != null && (h.isNew || h.isMoving))) notable++;
        }
        System.out.printf("#%d APs:%d notable:%d CRC+%d NF:%d%s%n",
                ctx.loop, aps.size(), notable, ctx.crcDelta, ctx.noiseFloor,
                ctx.externalRF ? " !!RF" : "");
        for (AP a : aps) {
            History h = ctx.temporal.get(a.mac);
            if (a.threat < 30 && (h == null || (!h.isNew && !h.isMoving))) continue;
            System.out.printf("  %3d %-17s %-12s %4ddBm %4.0fm %s%n",
                    a.threat, a.mac, a.ssid, a.signalDbm, a.dist, a.flags);
        }
    }

    /** File log — compact summary every scan; full AP dump only on notable events. */
    static void logLoopToFile(List<AP> aps, int maxThreat, DetectorContext ctx) {
        if (logWriter == null) return;

        logWriter.printf("%tT #%d AP:%d THR:%d NF:%d%s%n",
                System.currentTimeMillis(), ctx.loop, aps.size(), maxThreat,
                ctx.noiseFloor, ctx.externalRF ? " RF" : "");

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
            logWriter.printf("  %s %-17s %-12s %3ddBm %4dm thr=%-3d %s%s%s [%s]%n",
                    ctx.baseline.contains(a.mac) ? "B" : "N",
                    a.mac, a.ssid, a.signalDbm, emaM, a.threat,
                    isNew ? "N" : "-", isMov ? "M" : "-",
                    a.ghost ? "G" : "-", a.flags);
        }
        if (skipped > 0) logWriter.printf("  +%d bg%n", skipped);
    }
}
