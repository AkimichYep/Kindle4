package com.yep.kindle.dron;

import java.io.*;
import java.util.*;
import java.util.function.Consumer;

import com.yep.kindle.dron.detection.MovementMetrics;
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
import com.yep.kindle.dron.util.KindleUtils;

/**
 * KindleDroneDetectorPro v2.3
 *
 * Main entry point and orchestration loop for the Kindle drone detector.
 * All heavy logic has been extracted into dedicated classes:
 *
 *   WifiScanner      — hardware scanning, firmware management
 *   TemporalEngine   — per-MAC ring-buffer tracking across scans
 *   ThreatScorer     — multi-factor AP threat scoring
 *   ScreenBuilder    — assembles 40×50 text grids for each display page
 *   DisplayManager   — row-diff e-ink rendering
 *   RadarImageManager — radar PNG generation and snapshot lifecycle
 *   RadarRenderer    — low-level pixel renderer
 *   NetCsvStore      — CSV persistence
 *   WeatherService   — weather fetch / format
 *   MovementMetrics  — distance-delta utilities
 *   DroneSignatures  — OUI + keyword database
 */
public class KindleDroneDetectorPro {

    // ── Timing constants ──────────────────────────────────────────────────────
    static final int  FAST_MS            = 5000;
    static final int  STATS_EVERY        = 5;
    static final int  PROBE_EVERY        = 6;
    static final int  IDLE_CRC_EVERY     = 10;
    static final int  MAXPERF_EVERY      = 30;
    static final int  SAVE_EVERY         = 12;    // persist CSV every ~60 s
    static final int  RADAR_EVERY        = 12;    // regenerate radar image every ~60 s
    static final int  RADAR_SHOW_LOOPS   = 10;    // display radar for this many loops (~50 s)
    static final int  IDLE_CRC_THRESHOLD = 6;
    static final int  IDLE_CRC_CONFIRM   = 3;
    static final int  BASELINE_LOOPS     = 30;    // only used when no CSV
    static final long WEATHER_EVERY_MS   = 300_000L; // 5 min
    static final long ABSENT_GHOST_MS    = 600_000L; // 10 min
    static final int  RADAR_SNAPSHOT_MAX = 3;
    static final int  HISTORY_SHOW_LOOPS = 6;     // keep history page for ~30 s
    static final int  ROAD_VIEW_SHOW_LOOPS = 8;   // keep road-radar for ~40 s
    static final long HISTORY_VIEW_INTERVAL_MS = 180_000L; // show history every ~3 min
    static final long ROAD_VIEW_INTERVAL_MS    =  90_000L; // show road-radar every ~90 s
    static final int  RADAR_DIST_DELTA_NEAR_M  = 8;
    static final int  RADAR_DIST_DELTA_MID_M   = 15;
    static final int  RADAR_DIST_DELTA_FAR_M   = 25;
    static final int  RADAR_DIST_DELTA_VFAR_M  = 40;
    static final String WEATHER_LOCATION = "Kharkiv";

    // ── File paths ────────────────────────────────────────────────────────────
    static final String LOG_FILE = "/mnt/us/drone_log.txt";
    static final String CSV_FILE = "/mnt/us/drone_nets.csv";
    static final String CSV_TMP  = "/mnt/us/drone_nets.csv.tmp";

    // ── Logging controls ──────────────────────────────────────────────────────
    static final long LOG_MAX_BYTES   = 512 * 1024; // rotate at 512 KB (keep one .1 backup)
    static final int  DUMP_THREAT_MIN = 30;         // full AP dump only when something is "suspicious"
    static final int  HEARTBEAT_EVERY = 60;         // force a context dump every ~5 min
    static final int  LOG_ROTATE_EVERY = 6;         // check log size every ~30 s

    static final String[] PROBE_SSIDS = {
        "TELLO-", "DJI-", "Spark-", "PHANTOM", "Mavic-", "ANAFI-",
        "Bebop2-", "FPV-", "AVATA-", "SkyController"
    };

    // =========================================================================
    // Main
    // =========================================================================

    public static void main(String[] args) {
        System.setProperty("java.awt.headless", "true");
        System.out.println("=== KindleDroneDetectorPro v2.3 ===");

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

        DisplayManager   display = new DisplayManager();
        RadarImageManager radarMgr = new RadarImageManager(KindleDroneDetectorPro::logFile);

        // ── Startup ───────────────────────────────────────────────────────────
        KindleUtils.exec("lipc-set-prop", "com.lab126.powerd", "preventScreenSaver", "1");
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            saveNetworksCsv(knownNets);
            KindleUtils.exec("lipc-set-prop", "com.lab126.powerd", "preventScreenSaver", "0");
            System.out.println("Sleep restored. CSV saved.");
        }));

        openLogFile();
        int loaded = loadNetworksCsv(knownNets, baseline);

        if (loaded >= 3) {
            ctx.armed = true;
            System.out.println("ARMED immediately: " + loaded + " nets from CSV");
            logFile("ARMED from CSV: " + loaded + " known MACs");
        } else {
            System.out.println("No CSV or too few entries (" + loaded + ") – entering learning phase");
        }

        WifiScanner.initFirmware();
        int[] statsResult = new int[3]; // [0]=crc, [1]=noiseFloor, [2]=csSnr
        long prevCRC = WifiScanner.readStats(statsResult, true);
        ctx.noiseFloor = statsResult[1];
        ctx.csSnr      = statsResult[2];

        ctx.weather = WeatherService.fetchWeather(WEATHER_LOCATION);
        long lastWeatherFetchAt = System.currentTimeMillis();
        long lastHistoryViewAt  = lastWeatherFetchAt;
        long lastRoadViewAt     = lastWeatherFetchAt;
        display.showWeatherPage(ctx);

        // ── Display-page tracking ─────────────────────────────────────────────
        int  radarCountdown      = 0;
        int  historyCountdown    = 0;
        int  roadCountdown       = 0;
        boolean activitySinceLastRadar = false;

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

            // ── Directed probe ────────────────────────────────────────────────
            boolean probeActive = false;
            if (ctx.loop % PROBE_EVERY == 0) {
                String probe = PROBE_SSIDS[(ctx.loop / PROBE_EVERY) % PROBE_SSIDS.length];
                WifiScanner.sendProbe(probe);
                probeActive = true;
                logFile("PROBE: " + probe);
            } else if (probeActive) {
                WifiScanner.sendProbe("any");
                probeActive = false;
            }

            // ── Scan + temporal engine ─────────────────────────────────────────
            List<AP> aps = WifiScanner.scan();
            temporal.update(aps, ctx.armed, baseline);
            updateKnownNets(aps, ctx);

            int[] procResult = WifiScanner.readProcWireless();
            if (procResult != null) {
                ctx.linkQuality = procResult[0];
                ctx.noiseFloor  = procResult[2];
                ctx.csSnr       = procResult[1] - procResult[2];
            }

            // ── Weather refresh ────────────────────────────────────────────────
            long now = System.currentTimeMillis();
            if (now - lastWeatherFetchAt >= WEATHER_EVERY_MS) {
                lastWeatherFetchAt = now;
                WeatherService.WeatherData latest = WeatherService.fetchWeather(WEATHER_LOCATION);
                ctx.weather = latest;
                if (latest.error == null) logFile("WEATHER ok: " + latest.temp + "C " + latest.description);
                else                      logFile("WEATHER err: " + latest.error);
            }

            // ── Firmware stats ─────────────────────────────────────────────────
            if (ctx.loop % STATS_EVERY == 0) {
                long newCRC = WifiScanner.readStats(statsResult, true);
                ctx.crcDelta   = newCRC - prevCRC;
                prevCRC        = newCRC;
                // NF / SNR are sourced only from /proc/net/wireless (every loop, above)
                // so they stay mutually consistent (SNR = level − NF from one source).
                // The firmware cs_snr/noise_floor are a different measurement (SNR to the
                // associated AP) and must not be mixed into the same fields.
            }

            // ── Idle CRC ──────────────────────────────────────────────────────
            if (ctx.loop % IDLE_CRC_EVERY == 0) {
                ctx.idleCRC = WifiScanner.measureIdleCRC(statsResult);
                if (ctx.idleCRC >= IDLE_CRC_THRESHOLD) {
                    ctx.externalRFcount++;
                    if (ctx.externalRFcount >= IDLE_CRC_CONFIRM) ctx.externalRF = true;
                    String msg = "!! IDLE-CRC=" + ctx.idleCRC + " external RF (count="
                            + ctx.externalRFcount + "/" + IDLE_CRC_CONFIRM + ")";
                    System.out.println(msg);
                    logFile(msg);
                } else {
                    if (ctx.externalRFcount > 0) ctx.externalRFcount--;
                    if (ctx.externalRFcount == 0) ctx.externalRF = false;
                }
                prevCRC      = WifiScanner.readStats(statsResult, true);
                ctx.crcDelta = 0;
            }

            // ── Score + sort ───────────────────────────────────────────────────
            scorer.score(aps, ctx.armed, baseline, knownNets);
            aps.sort((a, b) -> b.threat != a.threat
                    ? b.threat - a.threat : Double.compare(a.dist, b.dist));

            int maxThreat = aps.isEmpty() ? 0 : aps.get(0).threat;
            if (maxThreat > ctx.statPeakThreat) ctx.statPeakThreat = maxThreat;

            // ── Learning-phase arming (fallback when no CSV) ───────────────────
            if (!ctx.armed) {
                for (AP a : aps) {
                    baseline.add(a.mac);
                    NetRecord nr = knownNets.get(a.mac);
                    if (nr == null) { nr = new NetRecord(a.mac); knownNets.put(a.mac, nr); }
                    nr.update(a);
                }
                if (ctx.loop == BASELINE_LOOPS) {
                    ctx.armed = true;
                    System.out.println("ARMED: " + baseline.size() + " MACs baselined");
                    logFile("ARMED: " + baseline.size() + " MACs baselined");
                    saveNetworksCsv(knownNets);
                }
            }

            // ── Alert tracking ─────────────────────────────────────────────────
            boolean alert = maxThreat >= 60 || ctx.externalRF;
            if (alert) ctx.statAlertEvents++;

            boolean hasActivity = scorer.hasInterestingActivity(aps, ctx.armed, baseline);
            if (hasActivity) activitySinceLastRadar = true;

            // ── Radar image (once per minute, only when there is activity) ─────
            if (ctx.loop % RADAR_EVERY == 0) {
                if (activitySinceLastRadar) {
                    List<AP> radarAps = scorer.buildRadarAps(aps, ctx.armed, baseline,
                            KindleDroneDetectorPro::logFile);
                    if (!radarAps.isEmpty()) {
                        logFile(String.format("RADAR trigger: live=%d filtered=%d ghosts=%d",
                                aps.size(), radarAps.size(),
                                (int) radarAps.stream().filter(a -> a.ghost).count()));
                        if (radarMgr.renderAndShow(radarAps, ctx)) {
                            radarCountdown = RADAR_SHOW_LOOPS;
                            display.getCurrentPage(); // page is now "radar" externally
                        }
                    } else {
                        logFile("RADAR skip: nothing to show after filter");
                    }
                } else {
                    logFile("RADAR skip: no activity since last render");
                }
                activitySinceLastRadar = false;
            }

            // ── Page-rotation triggers (wall-time based) ───────────────────────
            if (radarCountdown == 0 && roadCountdown == 0 && historyCountdown == 0) {
                long roadElapsed = t0 - lastRoadViewAt;
                if (roadElapsed >= ROAD_VIEW_INTERVAL_MS) {
                    roadCountdown  = ROAD_VIEW_SHOW_LOOPS;
                    lastRoadViewAt = t0;
                    logFile(String.format("ROAD trigger: loop#%d elapsed=%ds",
                            ctx.loop, roadElapsed / 1000));
                } else {
                    long histElapsed = t0 - lastHistoryViewAt;
                    if (histElapsed >= HISTORY_VIEW_INTERVAL_MS) {
                        historyCountdown  = HISTORY_SHOW_LOOPS;
                        lastHistoryViewAt = t0;
                        logFile(String.format("HISTORY trigger: loop#%d elapsed=%ds",
                                ctx.loop, histElapsed / 1000));
                    }
                }
            }

            // ── Radar animation cycle ──────────────────────────────────────────
            if (radarCountdown > 0) {
                int framesAvailable = Math.min(radarCountdown, RadarRenderer.FRAME_COUNT);
                int tickInCycle = (RADAR_SHOW_LOOPS - radarCountdown) % framesAvailable;
                int slotToShow  = (RadarRenderer.nextFrame + tickInCycle) % RadarRenderer.FRAME_COUNT;
                if (radarCountdown < RADAR_SHOW_LOOPS && tickInCycle == 0) {
                    KindleUtils.exec("eips", "-g", RadarRenderer.FRAME_FILES[slotToShow]);
                    KindleUtils.sleep(300);
                } else if (radarCountdown < RADAR_SHOW_LOOPS) {
                    display.showRadarAnimFrame(slotToShow);
                }
                radarCountdown--;
                if (radarCountdown == 0) {
                    roadCountdown    = 0;
                    historyCountdown = 0;
                    display.clearForPageSwitch("radar ended, returning to weather page");
                    logFile("DISPLAY -> radar ended, returning to weather page");
                }
            }

            // ── Text page render ───────────────────────────────────────────────
            if (radarCountdown == 0) {
                if (roadCountdown > 0) {
                    display.showRoadRadarPage(ctx);
                    roadCountdown--;
                } else if (historyCountdown > 0) {
                    display.showHistoryPage(ctx);
                    historyCountdown--;
                } else {
                    display.showWeatherPage(ctx);
                }
            }

            log(aps, ctx);
            logLoopToFile(aps, maxThreat, ctx);
            if (ctx.loop % LOG_ROTATE_EVERY == 0) rotateLogIfNeeded();

            // ── Periodic CSV save ──────────────────────────────────────────────
            if (ctx.loop % SAVE_EVERY == 0 && ctx.armed) {
                saveNetworksCsv(knownNets);
            }

            long wait = FAST_MS - (System.currentTimeMillis() - t0);
            if (wait > 0) KindleUtils.sleep(wait);
        }
    }

    // =========================================================================
    // CSV persistence
    // =========================================================================

    static int loadNetworksCsv(Map<String, NetRecord> knownNets, Set<String> baseline) {
        int count = NetCsvStore.load(CSV_FILE, knownNets, baseline,
                KindleDroneDetectorPro::logFile);
        if (count > 0) System.out.println("CSV loaded: " + count + " networks from " + CSV_FILE);
        return count;
    }

    static void saveNetworksCsv(Map<String, NetRecord> knownNets) {
        NetCsvStore.save(CSV_TMP, CSV_FILE, knownNets, KindleDroneDetectorPro::logFile);
    }

    // =========================================================================
    // Known-nets updater
    // =========================================================================

    /** Merge the current scan into the persistent knownNets map. */
    static void updateKnownNets(List<AP> aps, DetectorContext ctx) {
        Map<String, NetRecord> knownNets = ctx.knownNets;
        Set<String>            baseline  = ctx.baseline;

        int before = knownNets.size();
        for (AP a : aps) {
            if (a.mac == null || a.mac.isEmpty()) continue;
            NetRecord nr = knownNets.get(a.mac);
            if (nr == null) {
                nr = new NetRecord(a.mac);
                knownNets.put(a.mac, nr);
                // NOTE: do NOT touch statTotalScans here — it counts scan loops
                // (incremented once per main-loop iteration). Unique-MAC count is
                // reported separately via knownNets.size().
                if (ctx.armed && !baseline.contains(a.mac)) ctx.statNewArmed++;
            }
            nr.update(a);
            a.distDeltaM = nr.lastDistDeltaM;

            if (!baseline.contains(a.mac)) {
                String nm = (a.ssid != null && !a.ssid.isEmpty()
                        && !"[HIDDEN]".equals(a.ssid)) ? a.ssid : a.mac;
                if (nr.surgeDetected) {
                    logFile(String.format("!! SURGE  %-14s +%ddBm sig=%d ch=%-2d ema=%dm #%d",
                            nm, nr.surgeRawDelta, a.signalDbm,
                            a.channel, nr.lastDistM, nr.surgeCount));
                }
                if (nr.consecutiveApproach == 3) {
                    logFile(String.format("!! APPROACH %-14s streak=3 dist=%dm trend=%s",
                            nm, nr.lastDistM,
                            MovementMetrics.sparkline(nr.distHistory)));
                }
            }
        }
        // Bound the map size to prevent unbounded growth
        if (knownNets.size() > 2000) {
            Iterator<String> it = knownNets.keySet().iterator();
            while (knownNets.size() > 1800 && it.hasNext()) { it.next(); it.remove(); }
            logFile("knownNets trimmed to " + knownNets.size());
        }
        int after = knownNets.size();
        if (after > before) logFile("knownNets: +" + (after - before) + " new MACs");
    }

    // =========================================================================
    // Logging
    // =========================================================================

    static PrintWriter logWriter = null;

    static void openLogFile() {
        try {
            // Force UTF-8 so log text is charset-independent of the Kindle JVM default
            // (which may be US-ASCII and would corrupt non-ASCII characters).
            logWriter = new PrintWriter(new BufferedWriter(new OutputStreamWriter(
                    new FileOutputStream(LOG_FILE, true),
                    java.nio.charset.StandardCharsets.UTF_8)), true);
            logWriter.println("--- START " + new Date() + " ---");
        } catch (IOException e) {
            System.err.println("Cannot open log file: " + e.getMessage());
        }
    }

    /** Size-based log rotation: keep one backup (drone_log.txt.1) and start fresh. */
    static void rotateLogIfNeeded() {
        try {
            File f = new File(LOG_FILE);
            if (f.length() < LOG_MAX_BYTES) return;
            if (logWriter != null) logWriter.close();
            File bak = new File(LOG_FILE + ".1");
            if (bak.exists()) bak.delete();
            f.renameTo(bak);
            openLogFile();
            logFile("log rotated (previous kept as drone_log.txt.1)");
        } catch (Exception e) {
            System.err.println("log rotate failed: " + e.getMessage());
        }
    }

    static void logFile(String msg) {
        if (logWriter != null) logWriter.printf("%tT %s%n", System.currentTimeMillis(), msg);
    }

    static void log(List<AP> aps, DetectorContext ctx) {
        System.out.printf("%n== #%d %tT APs:%d CRC+%d iCRC:%d NF:%d SNR:%d LQ:%d %s ==%n",
                ctx.loop, System.currentTimeMillis(), aps.size(), ctx.crcDelta, ctx.idleCRC,
                ctx.noiseFloor, ctx.csSnr, ctx.linkQuality,
                ctx.externalRF ? " !!RF!!" : "");
        int i = 0;
        for (AP a : aps) {
            i++;
            History h = ctx.temporal.get(a.mac);
            boolean notable = a.threat > 0 || (h != null && (h.isNew || h.isMoving)) || i <= 5;
            if (!notable) {
                if (i == 6) System.out.printf("  ... (%d more background APs)%n", aps.size() - 5);
                continue;
            }
            System.out.printf("%2d)%3d %-17s %-14s C%-2d %4ddBm %5.0fm"
                    + " sd=%.1f hop=%d %s%s%s%n",
                    i, a.threat, a.mac, a.ssid, a.channel, a.signalDbm, a.dist,
                    h != null ? h.stddev : 0, h != null ? h.chChanges : 0,
                    a.flags,
                    a.ghost ? " [GHOST]" : "",
                    ctx.baseline.contains(a.mac) ? "" : " [NEW]");
        }
    }

    static void logLoopToFile(List<AP> aps, int maxThreat, DetectorContext ctx) {
        if (logWriter == null) return;

        logWriter.printf("%tT #%d AP:%d CRC+%d iCRC:%d NF:%d SNR:%d THR:%d%s%n",
                System.currentTimeMillis(), ctx.loop, aps.size(), ctx.crcDelta, ctx.idleCRC,
                ctx.noiseFloor, ctx.csSnr, maxThreat,
                ctx.externalRF ? " !!RF!!" : "");

        boolean hasFastApproach = false;
        for (AP a : aps) {
            NetRecord nr = ctx.knownNets.get(a.mac);
            if (nr != null && !ctx.baseline.contains(a.mac)
                    && (nr.surgeDetected || nr.consecutiveApproach >= 3)) {
                hasFastApproach = true;
                break;
            }
        }
        boolean fullDump = (maxThreat >= DUMP_THREAT_MIN || ctx.externalRF
                || ctx.loop % HEARTBEAT_EVERY == 0 || hasFastApproach);
        if (!fullDump) return;
        boolean heartbeat = (ctx.loop % HEARTBEAT_EVERY == 0);

        int idx = 0;
        int skipped = 0;
        for (AP a : aps) {
            idx++;
            History   h  = ctx.temporal.get(a.mac);
            NetRecord nr = ctx.knownNets.get(a.mac);
            double stddev = h  != null ? h.stddev    : 0;
            int    hops   = h  != null ? h.chChanges : 0;
            int    gaps   = h  != null ? h.gaps      : 0;
            boolean isNew = h  != null && h.isNew;
            boolean isMov = h  != null && h.isMoving;
            boolean isHop = h  != null && h.isHopping;

            // Only log APs that carry information; skip silent background routers.
            // On the periodic heartbeat keep the top 5 (already sorted by threat/dist) for context.
            boolean notable = a.threat >= 10 || isNew || isMov || isHop
                    || (nr != null && (nr.surgeDetected || nr.consecutiveApproach >= 3));
            if (!notable && !(heartbeat && idx <= 5)) { skipped++; continue; }

            int    emaM   = nr != null && nr.lastDistM >= 0 ? nr.lastDistM : (int) a.dist;
            int    smaSig = nr != null && nr.smoothedRssi != Integer.MIN_VALUE
                            ? nr.smoothedRssi : a.signalDbm;
            int    ca     = nr != null ? nr.consecutiveApproach : 0;
            String srg    = nr != null && nr.surgeDetected ? "!" : " ";
            String inBase = ctx.baseline.contains(a.mac) ? "BASE" : "NEW ";
            logWriter.printf("  [%2d] %s %-17s %-14s C%-2d %4ddBm%s(%3ddBm) %4dm"
                    + " thr=%-3d sd=%.1f hop=%d gap=%d ca=%-2d %s%s%s%s flags=[%s]%n",
                    idx, inBase, a.mac, a.ssid, a.channel, a.signalDbm, srg, smaSig, emaM,
                    a.threat, stddev, hops, gaps, ca,
                    isNew ? "N" : "-", isMov ? "M" : "-", isHop ? "H" : "-",
                    a.ghost ? "G" : "-",
                    a.flags);
        }
        if (skipped > 0) logWriter.printf("       ... (%d background APs omitted)%n", skipped);
    }
}
