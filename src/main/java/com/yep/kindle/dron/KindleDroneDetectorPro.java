package com.yep.kindle.dron;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * KindleDroneDetectorPro v2.3
 *
 * Changes vs v2.1:
 *  - CSV persistence: known networks saved/loaded from /mnt/us/drone_nets.csv
 *  - Fast arming from CSV (>=3 entries → arm immediately)
 *  - Reliable I/O: atomic writes, null-safe, process stderr drain
 *  - Display diff: row-level redraw cache, minimises e-ink wear
 *  - Statistics panel: uptime, scans, unique MACs, peak threat, alerts
 *  - Radar image: grayscale PNG with range rings + AP dots,
 *    written to /mnt/us/radar.png and displayed via `eips -g` once/minute.
 *  - Weather: wttr.in fetch every 5 minutes, shown in text HUD and radar footer.
 *    The normal text HUD is restored automatically after the radar timeout.
 */
public class KindleDroneDetectorPro {

    // ── Timing constants ──────────────────────────────────────────────────────
    static final int  FAST_MS         = 5000;
    static final int  STATS_EVERY     = 5;
    static final int  PROBE_EVERY     = 6;
    static final int  IDLE_CRC_EVERY  = 10;
    static final int  MAXPERF_EVERY   = 30;
    static final int  SAVE_EVERY      = 12;   // persist CSV every ~60 s
    static final int  RADAR_EVERY     = 12;   // regenerate radar image every ~60 s
    static final int  RADAR_SHOW_LOOPS = 10;  // display radar for this many loops (~50 s) then restore text
    static final int  IDLE_CRC_THRESHOLD = 6;
    static final int  IDLE_CRC_CONFIRM   = 3;
    static final int  BASELINE_LOOPS     = 30; // only used when no CSV
    static final long TRACKER_TTL_MS     = 180_000L; // 3 min
    static final long WEATHER_EVERY_MS   = 300_000L; // 5 min
    static final long ABSENT_GHOST_MS    = 600_000L; // 10 min — show known-threat as ghost on radar
    static final int  RADAR_SNAPSHOT_MAX = 3;        // keep only this many timestamped radar PNGs
    static final int  HISTORY_EVERY_LOOPS = 36;      // show history page every ~3 min
    static final int  HISTORY_SHOW_LOOPS  = 6;       // keep history page for ~30 s
    static final int  ROAD_VIEW_EVERY_LOOPS = 18;    // show road-radar every ~90 s
    static final int  ROAD_VIEW_SHOW_LOOPS  = 8;     // keep road-radar for ~40 s
    static final int  RADAR_DIST_DELTA_NEAR_M = 4;   // <= 60m
    static final int  RADAR_DIST_DELTA_MID_M  = 6;   // 61..140m
    static final int  RADAR_DIST_DELTA_FAR_M  = 8;   // 141..240m
    static final int  RADAR_DIST_DELTA_VFAR_M = 10;  // > 240m
    static final String WEATHER_LOCATION = "Kharkiv";

    // ── File paths ────────────────────────────────────────────────────────────
    static final String LOG_FILE = "/mnt/us/drone_log.txt";
    static final String CSV_FILE = "/mnt/us/drone_nets.csv";
    static final String CSV_TMP  = "/mnt/us/drone_nets.csv.tmp";

    static final String[] PROBE_SSIDS = {
        "TELLO-", "DJI-", "Spark-", "PHANTOM", "Mavic-", "ANAFI-",
        "Bebop2-", "FPV-", "AVATA-", "SkyController"
    };

    // =========================================================================
    // Data structures
    // =========================================================================

    /** Live scan result for one access point. */
    static class AP {
        String mac        = "";
        String ssid       = "";
        String mode       = "";
        String encryption = "Open";
        int    channel    = 0;
        int    signalDbm  = -999;
        boolean hidden    = false;
        double  dist      = 0;
        int     distDeltaM = 0; // + = moving away, - = getting closer
        int     threat    = 0;
        String  flags     = "";
        boolean ghost     = false; // true = known-threat not currently visible
    }

    /** Per-MAC temporal ring buffer (40 observations). */
    static class History {
        static final int N = 40;
        String mac;
        String ssid       = "";
        long   firstSeen, lastSeen;
        int[]  sigs       = new int[N];
        int[]  chs        = new int[N];
        int[]  dists      = new int[N];
        int    idx        = 0, count = 0;
        int    chChanges  = 0, gaps   = 0;
        boolean seenNow = false, seenPrev = false;
        double  stddev    = 0;
        boolean isNew = false, isMoving = false, isHopping = false, isTransient = false;
        int     peakSignal = -999;

        History(String m) {
            mac = m;
            firstSeen = lastSeen = System.currentTimeMillis();
        }

        void add(int sig, int ch, double distM) {
            if (count > 0) {
                int pi = (idx - 1 + N) % N;
                if (chs[pi] != 0 && chs[pi] != ch) chChanges++;
            }
            sigs[idx] = sig;
            chs[idx]  = ch;
            dists[idx] = (int) Math.round(distM);
            idx = (idx + 1) % N;
            count++;
            lastSeen = System.currentTimeMillis();
            if (sig > peakSignal) peakSignal = sig;
        }

        void compute(boolean armed, Set<String> baseline) {
            long now = System.currentTimeMillis();
            int n = Math.min(count, N);
            if (n >= 3) {
                double sum = 0;
                for (int i = 0; i < n; i++) sum += sigs[(idx - 1 - i + N * 2) % N];
                double mean = sum / n;
                double var  = 0;
                for (int i = 0; i < n; i++) {
                    double d = sigs[(idx - 1 - i + N * 2) % N] - mean;
                    var += d * d;
                }
                stddev = Math.sqrt(var / n);
            } else {
                stddev = 0;
            }
            isNew       = (now - firstSeen) < 50_000L;
            isMoving    = stddev > 10.0;
            isHopping   = chChanges >= 2;
            isTransient = (gaps >= 2) && (peakSignal > -80);
        }
    }

    /** Persistent record loaded from / saved to CSV. */
    static class NetRecord {
        static final int DIST_KEEP = 12;
        String mac;
        String ssid;
        long   firstSeen;
        long   lastSeen;
        int    seenCount;
        int    peakSignal;
        String oui;
        boolean keyword;
        String obsTime = ""; // HH:mm wall-clock time of last observation
        int    lastDistM = -1;
        int    lastDistDeltaM = 0;
        Deque<Integer> distHistory = new ArrayDeque<>();

        NetRecord(String mac) {
            this.mac       = mac;
            this.firstSeen = System.currentTimeMillis();
            this.lastSeen  = this.firstSeen;
        }

        /** Merge live observation into this persistent record. */
        void update(AP a) {
            lastSeen = System.currentTimeMillis();
            seenCount++;
            // Record wall-clock time of this observation (HH:mm)
            obsTime = String.format("%tR", lastSeen);
            if (a.signalDbm > peakSignal) peakSignal = a.signalDbm;
            if (a.ssid != null && !a.ssid.isEmpty() && !a.ssid.equals("[HIDDEN]")) ssid = a.ssid;
            if (oui == null || oui.isEmpty()) oui = DroneSignatures.lookupOUI(mac);
            if (!keyword) keyword = DroneSignatures.matchesKeyword(ssid);

            int dm = (int) Math.round(a.dist);
            if (lastDistM >= 0) lastDistDeltaM = dm - lastDistM;
            lastDistM = dm;
            pushDist(dm);
            a.distDeltaM = lastDistDeltaM;
        }

        void pushDist(int distM) {
            distHistory.addLast(distM);
            while (distHistory.size() > DIST_KEEP) distHistory.removeFirst();
        }

        String encodeDistHistory() {
            return NetCsvStore.encodeDistHistory(distHistory);
        }

        void decodeDistHistory(String raw) {
            NetCsvStore.decodeDistHistory(this, raw);
        }
    }

    // =========================================================================
    // Global state
    // =========================================================================

    static Map<String, History>   tracker    = new HashMap<>();
    static Map<String, NetRecord> knownNets  = new LinkedHashMap<>(); // preserves insertion order
    static Set<String>            baseline   = new HashSet<>();
    static boolean armed        = false;
    static int     loop         = 0;
    static long    startTime    = System.currentTimeMillis();
    static long    prevCRC      = 0, crcDelta = 0;
    static int     noiseFloor   = -96, csSnr = 0, linkQuality = 0;
    static boolean probeActive  = false;
    static long    idleCRC      = 0;
    static boolean externalRF   = false;
    static int     externalRFcount = 0;

    // ── Statistics counters ───────────────────────────────────────────────────
    static int  statTotalScans   = 0;
    static int  statUniqueMacs   = 0; // ever seen this session
    static int  statPeakThreat   = 0;
    static int  statAlertEvents  = 0;
    static int  statNewArmed     = 0; // new MACs detected after arming

    // ── Weather state ─────────────────────────────────────────────────────────
    static WeatherService.WeatherData weather = WeatherService.unavailable("boot");
    static long lastWeatherFetchAt = 0;

    // ── Radar state ───────────────────────────────────────────────────────────
    // When > 0, the radar image is currently shown; countdown decrements each
    // loop and when it hits 0 the weather page is restored (full screen clear).
    static int radarCountdown = 0;

    // Track whether any interesting activity (new/moving/threat AP) was seen
    // since the last radar render so we only show radar when relevant.
    static boolean activitySinceLastRadar = false;

    // ── Display-mode tracking ─────────────────────────────────────────────────
    // Which page is currently on screen: "weather", "history", "radar", "hud"
    static String currentPage = "";
    static int historyCountdown = 0;
    static int roadCountdown = 0;

    // ── Row-level display diff ────────────────────────────────────────────────
    // Kindle screen is ROWS×COLS; we cache what was last written to each row.
    static String[] screenCache = new String[KindleUtils.ROWS];

    static PrintWriter logWriter = null;

    // =========================================================================
    // Main
    // =========================================================================

    public static void main(String[] args) {
        System.setProperty("java.awt.headless", "true");
        System.out.println("=== KindleDroneDetectorPro v2.2 ===");
        Arrays.fill(screenCache, "");

        KindleUtils.exec("lipc-set-prop", "com.lab126.powerd", "preventScreenSaver", "1");
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            saveNetworksCsv();
            KindleUtils.exec("lipc-set-prop", "com.lab126.powerd", "preventScreenSaver", "0");
            System.out.println("Sleep restored. CSV saved.");
        }));

        openLogFile();
        int loaded = loadNetworksCsv();

        // Arm immediately if we have enough persistent knowledge
        if (loaded >= 3) {
            armed = true;
            System.out.println("ARMED immediately: " + loaded + " nets from CSV");
            logFile("ARMED from CSV: " + loaded + " known MACs");
        } else {
            System.out.println("No CSV or too few entries (" + loaded + ") – entering learning phase");
        }

        initFirmware();
        prevCRC = readStats(true);

        // Fetch weather eagerly and display the weather page as the startup screen.
        weather = WeatherService.fetchWeather(WEATHER_LOCATION);
        lastWeatherFetchAt = System.currentTimeMillis();
        renderWeatherPage();

        while (true) {
            long t0 = System.currentTimeMillis();
            loop++;
            statTotalScans++;

            // ── Re-apply maxperf periodically ────────────────────────────────
            if (loop % MAXPERF_EVERY == 0) {
                KindleUtils.exec("wmiconfig", "-i", "wlan0", "--power", "maxperf");
            }

            // ── Directed probe ───────────────────────────────────────────────
            if (loop % PROBE_EVERY == 0) {
                String probe = PROBE_SSIDS[(loop / PROBE_EVERY) % PROBE_SSIDS.length];
                KindleUtils.exec("wmiconfig", "-i", "wlan0", "--scanprobedssid", probe);
                probeActive = true;
                logFile("PROBE: " + probe);
            } else if (probeActive) {
                KindleUtils.exec("wmiconfig", "-i", "wlan0", "--scanprobedssid", "any");
                probeActive = false;
            }

            // ── Scan + temporal engine ───────────────────────────────────────
            List<AP> aps = scan();
            temporal(aps);
            updateKnownNets(aps);
            readProcWireless();
            maybeRefreshWeather();

            // ── Firmware stats ───────────────────────────────────────────────
            if (loop % STATS_EVERY == 0) {
                long newCRC = readStats(true);
                crcDelta = newCRC - prevCRC;
                prevCRC  = newCRC;
            }

            // ── Idle CRC ─────────────────────────────────────────────────────
            if (loop % IDLE_CRC_EVERY == 0) {
                measureIdleCRC();
            }

            // ── Score + sort ─────────────────────────────────────────────────
            score(aps);
            aps.sort((a, b) -> b.threat != a.threat
                    ? b.threat - a.threat : Double.compare(a.dist, b.dist));

            int maxThreat = aps.isEmpty() ? 0 : aps.get(0).threat;
            if (maxThreat > statPeakThreat) statPeakThreat = maxThreat;

            // ── Learning-phase arming (fallback when no CSV) ─────────────────
            if (!armed) {
                for (AP a : aps) {
                    baseline.add(a.mac);
                    NetRecord nr = knownNets.get(a.mac);
                    if (nr == null) { nr = new NetRecord(a.mac); knownNets.put(a.mac, nr); }
                    nr.update(a);
                }
                if (loop == BASELINE_LOOPS) {
                    armed = true;
                    System.out.println("ARMED: " + baseline.size() + " MACs baselined");
                    logFile("ARMED: " + baseline.size() + " MACs baselined");
                    saveNetworksCsv();
                }
            }

            // ── Alert tracking ───────────────────────────────────────────────
            boolean alert = maxThreat >= 60 || externalRF;
            if (alert) statAlertEvents++;

            // Track whether anything interesting appeared this loop
            boolean hasActivity = hasInterestingActivity(aps);
            if (hasActivity) activitySinceLastRadar = true;

            // ── Radar image (once per minute, only when there is activity) ────
            if (loop % RADAR_EVERY == 0) {
                if (activitySinceLastRadar) {
                    // Build the filtered AP list: only items worth showing on radar
                    List<AP> radarAps = buildRadarAps(aps);
                    logFile(String.format("RADAR trigger: live=%d filtered=%d ghosts=%d",
                            aps.size(), radarAps.size(),
                            (int) radarAps.stream().filter(a -> a.ghost).count()));
                    renderRadarImage(radarAps);
                } else {
                    logFile("RADAR skip: no activity since last render");
                }
                activitySinceLastRadar = false;
            }

            // Rotate to history page periodically when radar is not active.
            if (radarCountdown == 0 && loop % HISTORY_EVERY_LOOPS == 0) {
                historyCountdown = HISTORY_SHOW_LOOPS;
            }

            // Road-radar page for moving APs (distance + estimated speed).
            if (radarCountdown == 0 && loop % ROAD_VIEW_EVERY_LOOPS == 0 && hasRoadMovement()) {
                roadCountdown = ROAD_VIEW_SHOW_LOOPS;
            }

            // While radar is on screen: cycle through the last 3 saved frames
            // (Kindle-like animation — one frame per loop tick)
            if (radarCountdown > 0) {
                // Determine which slot to show this tick.
                // The most-recently-written slot index is (nextFrame - 1 + FRAME_COUNT) % FRAME_COUNT.
                // We step backwards through the ring on each tick so the animation
                // replays from oldest→newest, giving a temporal "sweep" feel.
                int framesAvailable = Math.min(radarCountdown, RadarRenderer.FRAME_COUNT);
                // Step through available frames in order oldest→newest
                int tickInCycle = (RADAR_SHOW_LOOPS - radarCountdown) % framesAvailable;
                // oldest slot = nextFrame (the slot about to be overwritten next render)
                int slotToShow = (RadarRenderer.nextFrame + tickInCycle) % RadarRenderer.FRAME_COUNT;
                // Only refresh display when moving to a new frame (skip on first tick —
                // renderRadarImage already showed the latest frame)
                if (radarCountdown < RADAR_SHOW_LOOPS && tickInCycle == 0) {
                    // New animation cycle — use eips to load the first frame of each pass.
                    KindleUtils.exec("eips", "-g", RadarRenderer.FRAME_FILES[slotToShow]);
                    KindleUtils.sleep(300);
                } else if (radarCountdown < RADAR_SHOW_LOOPS) {
                    showRadarAnimFrame(slotToShow);
                }
                radarCountdown--;
                if (radarCountdown == 0) {
                    // Radar done — force weather page on next render cycle
                    Arrays.fill(screenCache, "");
                    currentPage = ""; // force re-draw of weather page
                    KindleUtils.exec("eips", "-c");
                    KindleUtils.sleep(100);
                    logFile("DISPLAY → radar ended, returning to weather page");
                }
            }

            // ── Render / log ─────────────────────────────────────────────────
            // Default view is weather; periodically rotate in distance history.
            if (radarCountdown == 0) {
                if (roadCountdown > 0) {
                    renderRoadRadarPage();
                    roadCountdown--;
                } else if (historyCountdown > 0) {
                    renderHistoryPage();
                    historyCountdown--;
                } else {
                    renderWeatherPage();
                }
            }
            log(aps);
            logLoopToFile(aps, maxThreat);

            // ── Periodic CSV save ─────────────────────────────────────────────
            if (loop % SAVE_EVERY == 0 && armed) {
                saveNetworksCsv();
            }

            long wait = FAST_MS - (System.currentTimeMillis() - t0);
            if (wait > 0) KindleUtils.sleep(wait);
        }
    }

    // =========================================================================
    // CSV persistence
    // =========================================================================

    /**
     * Load networks from CSV into knownNets + baseline.
     * @return number of records loaded
     */
    static int loadNetworksCsv() {
        int count = NetCsvStore.load(CSV_FILE, knownNets, baseline, KindleDroneDetectorPro::logFile);
        if (count > 0) System.out.println("CSV loaded: " + count + " networks from " + CSV_FILE);
        return count;
    }

    /**
     * Atomically save knownNets to CSV (write temp → rename).
     */
    static void saveNetworksCsv() {
        NetCsvStore.save(CSV_TMP, CSV_FILE, knownNets, KindleDroneDetectorPro::logFile);
    }

    /** Update persistent knownNets map from current scan. */
    static void updateKnownNets(List<AP> aps) {
        int before = knownNets.size();
        for (AP a : aps) {
            if (a.mac == null || a.mac.isEmpty()) continue;
            NetRecord nr = knownNets.get(a.mac);
            if (nr == null) {
                nr = new NetRecord(a.mac);
                knownNets.put(a.mac, nr);
                statUniqueMacs++;
                if (armed && !baseline.contains(a.mac)) statNewArmed++;
            }
            nr.update(a);
            a.distDeltaM = nr.lastDistDeltaM;
        }
        // Keep knownNets bounded: drop oldest entries if it grows very large
        if (knownNets.size() > 2000) {
            Iterator<String> it = knownNets.keySet().iterator();
            while (knownNets.size() > 1800 && it.hasNext()) {
                it.next(); it.remove();
            }
            logFile("knownNets trimmed to " + knownNets.size());
        }
        int after = knownNets.size();
        if (after > before) logFile("knownNets: +" + (after - before) + " new MACs");
    }


    // =========================================================================
    // Idle CRC measurement
    // =========================================================================

    static void measureIdleCRC() {
        KindleUtils.exec("wmiconfig", "-i", "wlan0", "--getTargetStats", "--clearStats");
        KindleUtils.sleep(2000);
        idleCRC = readStats(false);

        if (idleCRC >= IDLE_CRC_THRESHOLD) {
            externalRFcount++;
            if (externalRFcount >= IDLE_CRC_CONFIRM) externalRF = true;
            String msg = "!! IDLE-CRC=" + idleCRC + " external RF (count="
                    + externalRFcount + "/" + IDLE_CRC_CONFIRM + ")";
            System.out.println(msg);
            logFile(msg);
        } else {
            if (externalRFcount > 0) externalRFcount--;
            if (externalRFcount == 0) externalRF = false;
        }
        prevCRC  = readStats(true);
        crcDelta = 0;
    }

    // =========================================================================
    // /proc/net/wireless reader
    // =========================================================================

    static void readProcWireless() {
        int[] r = WifiUtils.readProcWireless();
        if (r != null) {
            linkQuality = r[0];
            noiseFloor  = r[2];
            csSnr       = r[1] - r[2];
        }
    }

    // =========================================================================
    // Logging
    // =========================================================================

    static void openLogFile() {
        try {
            logWriter = new PrintWriter(new BufferedWriter(new FileWriter(LOG_FILE, true)), true);
            logWriter.println("--- START " + new Date() + " ---");
        } catch (IOException e) {
            System.err.println("Cannot open log file: " + e.getMessage());
        }
    }

    static void logFile(String msg) {
        if (logWriter != null) logWriter.printf("%tT %s%n", System.currentTimeMillis(), msg);
    }

    static void logLoopToFile(List<AP> aps, int maxThreat) {
        if (logWriter == null) return;

        // Always write a compact loop summary line
        logWriter.printf("%tT #%d AP:%d CRC+%d iCRC:%d NF:%d SNR:%d THR:%d page:%s%s%n",
                System.currentTimeMillis(), loop, aps.size(), crcDelta, idleCRC,
                noiseFloor, csSnr, maxThreat, currentPage,
                externalRF ? " !!RF!!" : "");

        // Write full AP detail every 10 loops, and always when there are threats
        boolean fullDump = (maxThreat > 0 || externalRF || loop % 10 == 0);
        if (fullDump) {
            int idx = 0;
            for (AP a : aps) {
                idx++;
                History h = tracker.get(a.mac);
                double stddev  = h != null ? h.stddev    : 0;
                int    hops    = h != null ? h.chChanges : 0;
                int    gaps    = h != null ? h.gaps      : 0;
                boolean isNew  = h != null && h.isNew;
                boolean isMov  = h != null && h.isMoving;
                boolean isHop  = h != null && h.isHopping;
                String inBase  = baseline.contains(a.mac) ? "BASE" : "NEW ";
                logWriter.printf("  [%2d] %s %-17s %-14s C%-2d %4ddBm %5.0fm"
                        + " thr=%-3d sd=%.1f hop=%d gap=%d %s%s%s%s flags=[%s]%n",
                        idx, inBase, a.mac, a.ssid, a.channel, a.signalDbm, a.dist,
                        a.threat, stddev, hops, gaps,
                        isNew ? "N" : "-", isMov ? "M" : "-", isHop ? "H" : "-",
                        a.ghost ? "G" : "-",
                        a.flags);
            }
        }
    }

    // =========================================================================
    // Firmware init
    // =========================================================================

    static void initFirmware() {
        KindleUtils.exec("wmiconfig", "-i", "wlan0", "--power", "maxperf");
        KindleUtils.sleep(200);
        KindleUtils.exec("wmiconfig", "-i", "wlan0", "--scan",
                "--fgstart=1", "--fgend=1", "--bg=3",
                "--minact=30", "--maxact=150", "--pas=200",
                "--scanctrlflags", "1", "1", "1", "1", "1", "1");
        KindleUtils.sleep(200);
        KindleUtils.exec("wmiconfig", "-i", "wlan0", "--getTargetStats", "--clearStats");
        KindleUtils.sleep(200);
        System.out.println("FW: maxperf, 200ms dwell, BSS reporting ON");
    }

    // =========================================================================
    // Scan
    // =========================================================================

    static List<AP> scan() {
        List<AP> list = new ArrayList<>();
        Process p = null;
        try {
            p = Runtime.getRuntime().exec(new String[]{"iwlist", "wlan0", "scan"});
            // Drain stderr to prevent process blocking on full pipe
            final Process fp = p;
            Thread errDrain = new Thread(() -> drainStream(fp.getErrorStream()), "err-drain");
            errDrain.setDaemon(true);
            errDrain.start();

            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            AP cur = null;
            while ((line = r.readLine()) != null) {
                String t = line.trim();
                if (t.contains("Address:")) {
                    if (cur != null && cur.signalDbm != -999) { finAP(cur); list.add(cur); }
                    cur = new AP();
                    cur.mac = t.substring(t.indexOf("Address:") + 9).trim().toUpperCase();
                } else if (cur == null) {
                    continue;
                } else if (t.startsWith("ESSID:")) {
                    String v = t.substring(6).trim();
                    if (v.startsWith("\"") && v.endsWith("\"") && v.length() >= 2)
                        v = v.substring(1, v.length() - 1);
                    cur.ssid   = v.isEmpty() ? "[HIDDEN]" : v;
                    cur.hidden = v.isEmpty();
                } else if (t.startsWith("Mode:")) {
                    cur.mode = t.substring(5).trim();
                } else if (t.startsWith("Frequency:")) {
                    int ci = t.indexOf("Channel");
                    if (ci > 0) {
                        try {
                            cur.channel = Integer.parseInt(t.substring(ci + 8).replace(")", "").trim());
                        } catch (NumberFormatException ignored) {}
                    }
                } else if (t.contains("Signal level=")) {
                    try {
                        int si = t.indexOf("Signal level=") + 13;
                        int se = t.indexOf(" dBm", si);
                        if (se > si) cur.signalDbm = Integer.parseInt(t.substring(si, se).trim());
                    } catch (NumberFormatException ignored) {}
                } else if (t.contains("WPA2") || t.contains("802.11i")) {
                    cur.encryption = "WPA2";
                } else if (t.contains("WPA Version")) {
                    if (!cur.encryption.equals("WPA2")) cur.encryption = "WPA";
                } else if (t.startsWith("Encryption key:on")) {
                    if (cur.encryption.equals("Open")) cur.encryption = "WEP";
                }
            }
            if (cur != null && cur.signalDbm != -999) { finAP(cur); list.add(cur); }
            r.close();
            p.waitFor();
        } catch (Exception e) {
            System.err.println("scan err: " + e.getMessage());
        } finally {
            if (p != null) p.destroy();
        }
        return list;
    }

    /** Drain an input stream silently (prevent subprocess pipe stalls). */
    static void drainStream(InputStream is) {
        if (is == null) return;
        try {
            byte[] buf = new byte[512];
            while (is.read(buf) != -1) { /* discard */ }
        } catch (IOException ignored) {}
    }

    static void finAP(AP a) {
        a.dist = WifiUtils.calculateDistance(a.signalDbm);
        if (a.ssid == null || a.ssid.isEmpty()) { a.ssid = "[HIDDEN]"; a.hidden = true; }
    }

    // =========================================================================
    // Temporal engine
    // =========================================================================

    static void temporal(List<AP> aps) {
        for (History h : tracker.values()) { h.seenPrev = h.seenNow; h.seenNow = false; }
        for (AP a : aps) {
            if (a.mac == null || a.mac.isEmpty()) continue;
            History h = tracker.get(a.mac);
            if (h == null) { h = new History(a.mac); tracker.put(a.mac, h); }
            h.add(a.signalDbm, a.channel, a.dist);
            h.ssid   = a.ssid;
            h.seenNow = true;
        }
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, History>> it = tracker.entrySet().iterator();
        while (it.hasNext()) {
            History h = it.next().getValue();
            if (h.seenNow && !h.seenPrev && h.count > 3) h.gaps++;
            h.compute(armed, baseline);
            if (now - h.lastSeen > TRACKER_TTL_MS) it.remove();
        }
    }

    // =========================================================================
    // Firmware stats
    // =========================================================================

    static long readStats(boolean updateNoise) {
        long crc = 0;
        Process p = null;
        try {
            p = Runtime.getRuntime().exec(new String[]{"wmiconfig", "-i", "wlan0", "--getTargetStats"});
            final Process fp = p;
            Thread errDrain = new Thread(() -> drainStream(fp.getErrorStream()), "stat-err-drain");
            errDrain.setDaemon(true);
            errDrain.start();

            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.startsWith("rx_crcerr")) {
                    try { crc = Long.parseLong(line.split("=")[1].trim()); }
                    catch (Exception ignored) {}
                } else if (updateNoise && line.startsWith("noise_floor")) {
                    try { noiseFloor = Integer.parseInt(line.split("=")[1].trim()); }
                    catch (Exception ignored) {}
                } else if (updateNoise && line.startsWith("cs_snr")) {
                    try { csSnr = Integer.parseInt(line.split("=")[1].trim().split("\\s")[0]); }
                    catch (Exception ignored) {}
                }
            }
            r.close();
            p.waitFor();
        } catch (Exception ignored) {
        } finally {
            if (p != null) p.destroy();
        }
        return crc;
    }

    // =========================================================================
    // Scoring
    // =========================================================================

    static void score(List<AP> aps) {
        for (AP a : aps) {
            int s = 0;
            StringBuilder f = new StringBuilder();

            String v = DroneSignatures.lookupOUI(a.mac);
            if (v != null) { s += 50; f.append(v).append(' '); }

            if (DroneSignatures.matchesKeyword(a.ssid)) { s += 40; f.append("SSID "); }

            if (a.hidden && a.dist < 80) { s += 15; f.append("HID "); }

            History h = tracker.get(a.mac);
            if (h != null) {
                if (h.isNew && armed && !baseline.contains(a.mac) && a.signalDbm > -80)
                    { s += 25; f.append("NEW "); }
                if (h.isMoving)   { s += 20; f.append("MOV "); }
                if (h.isHopping)  { s += 20; f.append("HOP "); }
                if (h.isTransient){ s += 15; f.append("TRN "); }
            }

            if (a.mode != null && !a.mode.isEmpty() && !a.mode.equals("Master"))
                { s += 20; f.append("ADH "); }

            if (armed && !baseline.contains(a.mac) && a.signalDbm > -65)
                { s += 20; f.append("STR "); }

            if (a.mac.length() >= 2) {
                try {
                    int firstByte = Integer.parseInt(a.mac.substring(0, 2), 16);
                    if ((firstByte & 0x02) != 0) { s += 10; f.append("RMAC "); }
                } catch (NumberFormatException ignored) {}
            }

            a.threat = Math.min(s, 100);
            a.flags  = f.toString().trim();
        }
    }

    // =========================================================================
    // Radar AP filtering
    // =========================================================================

    /**
     * Build the filtered AP list for radar rendering.
     *
     * Included items only:
     *   1) NEW — live AP not in baseline and in "new" window
     *   2) DIST — live AP whose distance changed meaningfully (|delta| >= threshold)
     *   3) RET — live AP that disappeared earlier and has now returned online
     *
     * Excluded by design:
     *   - static/unchanged APs
     *   - ghost/offline AP placeholders
     *   - threat-only APs without new/delta/return events
     */
    static List<AP> buildRadarAps(List<AP> liveAps) {
        List<AP> result = new ArrayList<>();

        // Keep radar focused on change events only.
        for (AP a : liveAps) {
            History h = tracker.get(a.mac);
            boolean isNew = armed && !baseline.contains(a.mac) && h != null && h.isNew;
            int distThr = dynamicDistThresholdM(a.dist);
            boolean distChanged = Math.abs(a.distDeltaM) >= distThr;
            boolean returned = h != null && h.seenNow && !h.seenPrev && h.count > 3;

            if (isNew || distChanged || returned) {
                String reason;
                if (isNew) {
                    long ageS = (h != null) ? (System.currentTimeMillis() - h.firstSeen) / 1000L : -1;
                    reason = "NEW " + ageS + "s";
                } else if (returned) {
                    reason = "RET online-again";
                } else {
                    reason = "DIST d=" + a.distDeltaM + "m thr=" + distThr;
                }
                logFile(String.format("RADAR-INC %s %-14s thr=%-3d dist=%.0fm [%s]",
                        a.mac, a.ssid, a.threat, a.dist, reason));
                result.add(a);
            }
        }

        return result;
    }

    // =========================================================================
    // Radar image rendering
    // =========================================================================

    /**
     * Build the radar PNG from a pre-filtered AP list, store it in the 3-frame
     * ring buffer, and show the latest frame via {@code eips -g}.
     *
     * The AP list should be the result of {@link #buildRadarAps} — only
     * event-like APs: NEW, DIST(change), RET(returned online).
     *
     * When new threats are detected a timestamped copy is saved alongside the
     * main radar.png so evidence is preserved across overwrites.
     */
    static void renderRadarImage(List<AP> radarAps) {
        try {
            // Determine whether this render contains a fresh event (new or returned).
            boolean newDetection = false;
            for (AP a : radarAps) {
                if (a.ghost) continue;
                History h = tracker.get(a.mac);
                if (h != null && (h.isNew || (h.seenNow && !h.seenPrev && h.count > 3))) {
                    newDetection = true;
                    break;
                }
            }

            RadarRenderer.render(radarAps, armed, loop, externalRF);

            // Save a timestamped snapshot when something new is detected,
            // keeping only the RADAR_SNAPSHOT_MAX most recent files on disk.
            if (newDetection) {
                long ts = System.currentTimeMillis();
                String snapPath = String.format("/mnt/us/radar_%tY%tm%td_%tH%tM%tS.png",
                        ts, ts, ts, ts, ts, ts);
                File src = new File(RadarRenderer.IMAGE_FILE);
                if (src.exists()) {
                    try {
                        Files.copy(src.toPath(), new File(snapPath).toPath(),
                                   StandardCopyOption.REPLACE_EXISTING);
                        logFile("RADAR snapshot: " + snapPath);
                        pruneRadarSnapshots();
                    } catch (IOException e) {
                        System.err.println("radar snapshot: " + e.getMessage());
                    }
                }
            }

            // Clear leftover text ghosting, then display the radar image.
            KindleUtils.exec("eips", "-c");
            KindleUtils.sleep(300);
            KindleUtils.exec("eips", "-g", RadarRenderer.IMAGE_FILE);
            KindleUtils.sleep(500); // allow E-ink panel to complete the refresh
            radarCountdown = RADAR_SHOW_LOOPS;
            currentPage    = "radar";
            int frameIdx = (RadarRenderer.nextFrame + RadarRenderer.FRAME_COUNT - 1) % RadarRenderer.FRAME_COUNT;
            logFile(String.format("RADAR shown: %d items (%d live, %d ghost) newDet=%b frame=%d",
                    radarAps.size(),
                    (int) radarAps.stream().filter(a -> !a.ghost).count(),
                    (int) radarAps.stream().filter(a -> a.ghost).count(),
                    newDetection, frameIdx));
        } catch (Exception e) {
            System.err.println("renderRadarImage: " + e.getMessage());
            logFile("RADAR ERROR: " + e.getMessage());
        }
    }

    /**
     * Show one animation frame from the ring buffer.
     */
    static void showRadarAnimFrame(int frameSlot) {
        String path = RadarRenderer.FRAME_FILES[frameSlot];
        java.io.File f = new java.io.File(path);
        if (!f.exists()) {
            KindleUtils.exec("eips", "-g", RadarRenderer.IMAGE_FILE);
        } else {
            KindleUtils.exec("eips", "-g", path);
        }
    }

    /**
     * Keep only the RADAR_SNAPSHOT_MAX most recent radar_YYYYMMDD_HHmmSS.png
     * files in /mnt/us/.  Deletes the oldest ones when the count exceeds the cap.
     */
    static void pruneRadarSnapshots() {
        File dir = new File("/mnt/us");
        File[] snaps = dir.listFiles((d, name) ->
                name.startsWith("radar_") && name.endsWith(".png")
                && name.length() > "radar_.png".length());
        if (snaps == null || snaps.length <= RADAR_SNAPSHOT_MAX) return;
        // Sort by name (which encodes timestamp lexicographically)
        Arrays.sort(snaps, (a, b) -> a.getName().compareTo(b.getName()));
        int toDelete = snaps.length - RADAR_SNAPSHOT_MAX;
        for (int i = 0; i < toDelete; i++) {
            boolean deleted = snaps[i].delete();
            logFile("RADAR prune " + (deleted ? "deleted" : "FAILED") + ": " + snaps[i].getName());
        }
    }

    // =========================================================================
    // Weather page
    // =========================================================================

    /**
     * Render the weather page using row-level diff so it refreshes live
     * without a full screen clear every loop (reduces e-ink wear).
     * Called both on startup and whenever no radar/HUD is needed.
     */
    static void renderWeatherPage() {
        String[] sc = buildWeatherScreen();

        // If we're switching from a different page, do a full clear first
        if (!"weather".equals(currentPage)) {
            KindleUtils.exec("eips", "-c");
            KindleUtils.sleep(150);
            Arrays.fill(screenCache, "");
            currentPage = "weather";
            logFile("DISPLAY → weather page");
        }

        // Row-level diff: only update rows that changed
        boolean needClear = false;
        for (int y = 0; y < KindleUtils.ROWS; y++) {
            String cur = sc[y] != null ? sc[y] : "";
            if (cur.length() < screenCache[y].length()) { needClear = true; break; }
        }
        if (needClear) {
            KindleUtils.exec("eips", "-c");
            KindleUtils.sleep(100);
            Arrays.fill(screenCache, "");
        }
        for (int y = 0; y < KindleUtils.ROWS; y++) {
            String l = sc[y] != null ? sc[y] : "";
            if (!l.equals(screenCache[y])) {
                if (!l.isEmpty()) KindleUtils.exec("eips", "0", String.valueOf(y), l);
                screenCache[y] = l;
            }
        }
    }

    /**
     * Render movement-focused history page (distance trend over recent scans).
     */
    static void renderHistoryPage() {
        String[] sc = buildHistoryScreen();
        if (!"history".equals(currentPage)) {
            KindleUtils.exec("eips", "-c");
            KindleUtils.sleep(150);
            Arrays.fill(screenCache, "");
            currentPage = "history";
            logFile("DISPLAY -> history page");
        }

        boolean needClear = false;
        for (int y = 0; y < KindleUtils.ROWS; y++) {
            String cur = sc[y] != null ? sc[y] : "";
            if (cur.length() < screenCache[y].length()) { needClear = true; break; }
        }
        if (needClear) {
            KindleUtils.exec("eips", "-c");
            KindleUtils.sleep(100);
            Arrays.fill(screenCache, "");
        }
        for (int y = 0; y < KindleUtils.ROWS; y++) {
            String l = sc[y] != null ? sc[y] : "";
            if (!l.equals(screenCache[y])) {
                if (!l.isEmpty()) KindleUtils.exec("eips", "0", String.valueOf(y), l);
                screenCache[y] = l;
            }
        }
    }

    /**
     * Render movement-centric road radar page: distance + speed estimate.
     */
    static void renderRoadRadarPage() {
        String[] sc = buildRoadRadarScreen();
        if (!"road".equals(currentPage)) {
            KindleUtils.exec("eips", "-c");
            KindleUtils.sleep(150);
            Arrays.fill(screenCache, "");
            currentPage = "road";
            logFile("DISPLAY -> road radar page");
        }

        boolean needClear = false;
        for (int y = 0; y < KindleUtils.ROWS; y++) {
            String cur = sc[y] != null ? sc[y] : "";
            if (cur.length() < screenCache[y].length()) { needClear = true; break; }
        }
        if (needClear) {
            KindleUtils.exec("eips", "-c");
            KindleUtils.sleep(100);
            Arrays.fill(screenCache, "");
        }
        for (int y = 0; y < KindleUtils.ROWS; y++) {
            String l = sc[y] != null ? sc[y] : "";
            if (!l.equals(screenCache[y])) {
                if (!l.isEmpty()) KindleUtils.exec("eips", "0", String.valueOf(y), l);
                screenCache[y] = l;
            }
        }
    }

    static String[] buildRoadRadarScreen() {
        String[] sc = new String[KindleUtils.ROWS];
        Arrays.fill(sc, "");
        int row = 0;
        long now = System.currentTimeMillis();

        sc[row++] = pad(String.format("* ROAD RADAR  %tT  loop#%d *", now, loop));
        sc[row++] = pad(LINE_H);
        sc[row++] = pad("AP           dist   dM  km/h dir age");
        sc[row++] = pad(LINE_H);

        List<NetRecord> recs = new ArrayList<>(knownNets.values());
        recs.sort((a, b) -> Double.compare(Math.abs(speedKmh(b)), Math.abs(speedKmh(a))));

        int shown = 0;
        for (NetRecord nr : recs) {
            if (row >= KindleUtils.ROWS - 2) break;
            if (nr.distHistory.size() < 3) continue;

            long ageMs = now - nr.lastSeen;
            if (ageMs > 240_000L) continue; // keep view focused on recent roadside movement

            double kmh = speedKmh(nr);
            if (shown >= 14 && Math.abs(kmh) < 1.5) continue;

            String name = (nr.ssid != null && !nr.ssid.isEmpty()) ? nr.ssid : nr.mac;
            if (name.length() > 12) name = name.substring(0, 12);

            int dist = nr.lastDistM >= 0 ? nr.lastDistM : nr.distHistory.peekLast();
            String dmTag = formatDistDeltaTag(nr.lastDistDeltaM).trim();
            String dir = kmh > 0.8 ? "AWAY" : (kmh < -0.8 ? "NEAR" : "----");
            String age = shortText(formatLastSeen(ageMs), 6);

            String ln = String.format("%-12s %4dm %4s %4.1f %-4s %s",
                    name, dist, dmTag, Math.abs(kmh), dir, age);
            sc[row++] = pad(ln);
            shown++;
        }

        if (shown == 0 && row < KindleUtils.ROWS - 1) {
            sc[row++] = pad("no recent moving APs yet");
        }
        if (row < KindleUtils.ROWS) sc[KindleUtils.ROWS - 1] = pad("dir: NEAR=towards you  AWAY=from you");

        return sc;
    }

    static boolean hasRoadMovement() {
        long now = System.currentTimeMillis();
        for (NetRecord nr : knownNets.values()) {
            if (nr.distHistory.size() < 3) continue;
            if (now - nr.lastSeen > 240_000L) continue;
            if (Math.abs(speedKmh(nr)) >= 1.0) return true;
        }
        return false;
    }

    static double speedKmh(NetRecord nr) {
        if (nr == null || nr.distHistory == null || nr.distHistory.size() < 2) return 0.0;
        int first = nr.distHistory.peekFirst();
        int last = nr.distHistory.peekLast();
        int samples = nr.distHistory.size() - 1;
        if (samples <= 0) return 0.0;
        double seconds = samples * (FAST_MS / 1000.0);
        if (seconds <= 0.0) return 0.0;
        double mps = (last - first) / seconds;
        return mps * 3.6;
    }

    static String[] buildHistoryScreen() {
        String[] sc = new String[KindleUtils.ROWS];
        Arrays.fill(sc, "");
        int row = 0;
        long now = System.currentTimeMillis();

        sc[row++] = pad(String.format("* DIST HISTORY  %tT  loop#%d *", now, loop));
        sc[row++] = pad(LINE_H);
        sc[row++] = pad("SSID/MAC     DIST dM/th lastSeen trend");
        sc[row++] = pad(LINE_H);

        List<NetRecord> recs = new ArrayList<>(knownNets.values());
        recs.sort((a, b) -> {
            int da = movementScore(a);
            int db = movementScore(b);
            if (db != da) return db - da;
            return Long.compare(b.lastSeen, a.lastSeen);
        });

        int shown = 0;
        for (NetRecord nr : recs) {
            if (row >= KindleUtils.ROWS - 2) break;
            if (nr.distHistory.isEmpty()) continue;

            int dist = nr.lastDistM >= 0 ? nr.lastDistM : nr.distHistory.peekLast();
            int dm   = nr.lastDistDeltaM;
            int thr  = dynamicDistThresholdM(dist);
            if (shown >= 12 && Math.abs(dm) < 3) continue; // keep page focused on movers

            String name = (nr.ssid != null && !nr.ssid.isEmpty()) ? nr.ssid : nr.mac;
            if (name.length() > 13) name = name.substring(0, 13);
            String when = formatLastSeen(now - nr.lastSeen);
            String spark = MovementMetrics.sparkline(nr.distHistory);

            String ln = String.format("%-11s %3dm%s/%02d %-7s %s%s",
                    name, dist, formatDistDeltaTag(dm), thr, shortText(when, 7),
                    spark, MovementMetrics.trendArrow(nr.distHistory));
            sc[row++] = pad(ln);
            shown++;
        }

        if (shown == 0 && row < KindleUtils.ROWS - 1) {
            sc[row++] = pad("(not enough history yet)");
        }

        if (row < KindleUtils.ROWS) sc[KindleUtils.ROWS - 1] = pad("legend: dM/th  +away -closer  trend .oO#");
        return sc;
    }

    static int movementScore(NetRecord nr) {
        int score = Math.abs(nr.lastDistDeltaM) * 3;
        if (nr.distHistory.size() >= 2) {
            int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
            for (int d : nr.distHistory) {
                if (d < min) min = d;
                if (d > max) max = d;
            }
            score += (max - min);
        }
        long ageMs = System.currentTimeMillis() - nr.lastSeen;
        if (ageMs < 120_000L) score += 10;
        return score;
    }

    // Movement sparkline + trend arrow moved to MovementMetrics.

    // Kindle 4 eips supports only ASCII; these are safe ASCII box approximations
    static final String LINE_H  = "----------------------------------------"; // horizontal rule
    static final String LINE_TL = "+"; static final String LINE_TR = "+";
    static final String LINE_BL = "+"; static final String LINE_BR = "+";

    static String[] buildWeatherScreen() {
        String[] sc = new String[KindleUtils.ROWS];
        Arrays.fill(sc, "");
        int row = 0;
        long now = System.currentTimeMillis();

        // ── Title bar ────────────────────────────────────────────────────────
        String armed_s = armed ? "ARMED" : String.format("LEARN %d/%d", loop, BASELINE_LOOPS);
        sc[row++] = pad(String.format("* DRONE WATCH  %tT  %-10s *", now, armed_s));
        sc[row++] = pad(LINE_H);

        // ── Weather block ────────────────────────────────────────────────────
        if (weather == null || weather.error != null) {
            String errMsg = weather != null ? weather.error : "n/a";
            if (errMsg != null && errMsg.length() > 34) errMsg = errMsg.substring(0, 34);
            sc[row++] = pad("  WEATHER: " + WEATHER_LOCATION + "  [OFFLINE]");
            sc[row++] = pad("  ERR: " + errMsg);
        } else {
            String icon = weatherIcon(nz(weather.description));
            String city = nz(weather.city);
            if (city.length() > 12) city = city.substring(0, 12);
            String desc = nz(weather.description);
            if (desc.length() > 20) desc = desc.substring(0, 20);
            // [icon] condition          city
            sc[row++] = pad(String.format("  %s %-20s  %s", icon, desc, city));
            // Temp + feels + humidity on one line
            sc[row++] = pad(String.format("  Temp:%sC  Feels:%sC  Hum:%s%%",
                    nz(weather.temp), nz(weather.feelsLike), nz(weather.humidity)));
            // Wind direction + speed + pressure
            sc[row++] = pad(String.format("  Wind:%-3s %3skm/h  Pres:%4shPa",
                    nz(weather.windDir), nz(weather.windSpeed), nz(weather.pressure)));
            // Update age
            long ageSec = (now - weather.updatedAt) / 1000L;
            String ageStr = ageSec < 60 ? ageSec + "s ago"
                          : ageSec < 3600 ? (ageSec/60) + "m ago"
                          : (ageSec/3600) + "h ago";
            sc[row++] = pad("  upd: " + ageStr);
        }

        sc[row++] = pad(LINE_H);

        // ── Detector status block ─────────────────────────────────────────────
        long uptimeSec = (now - startTime) / 1000L;
        sc[row++] = pad(String.format("  Up %-8s  Scans %-5d  MACs %d",
                formatUptime(uptimeSec), statTotalScans, knownNets.size()));
        sc[row++] = pad(String.format("  NF %ddBm  SNR %d  LQ %d  CRC+%d",
                noiseFloor, csSnr, linkQuality, crcDelta));
        if (statNewArmed > 0 || statAlertEvents > 0) {
            sc[row++] = pad(String.format("  >> NEW detections: %-3d  Alerts: %d",
                    statNewArmed, statAlertEvents));
        }
        if (externalRF) {
            sc[row++] = pad(String.format("  !! EXT RF  iCRC=%d  count=%d/%d",
                    idleCRC, externalRFcount, IDLE_CRC_CONFIRM));
        }

        sc[row++] = pad(LINE_H);

        // ── Known threats section ─────────────────────────────────────────────
        sc[row++] = pad("  KNOWN THREATS / DRONES");

        int shown = 0;
        // Collect known-threat records sorted by lastSeen desc
        List<NetRecord> threats = new ArrayList<>();
        for (NetRecord nr : knownNets.values()) {
            if (nr.keyword || (nr.oui != null && !nr.oui.isEmpty())) threats.add(nr);
        }
        // Sort: most recently seen first
        threats.sort((a, b) -> Long.compare(b.lastSeen, a.lastSeen));

        for (NetRecord nr : threats) {
            if (row >= KindleUtils.ROWS - 2) break;
            long agoMs = now - nr.lastSeen;
            String lastSeen = formatLastSeen(agoMs);
            String name = (nr.ssid != null && !nr.ssid.isEmpty()) ? nr.ssid : nr.mac;
            if (name.length() > 16) name = name.substring(0, 16);
            String ouiStr = (nr.oui != null && !nr.oui.isEmpty()) ? nr.oui : "?";
            if (ouiStr.length() > 6) ouiStr = ouiStr.substring(0, 6);
            // @HH:mm if recent (< 24h), else blank
            String timeTag = (agoMs < 86_400_000L && nr.obsTime != null && !nr.obsTime.isEmpty())
                             ? "@" + nr.obsTime : "      ";
            sc[row++] = pad(String.format("  %-16s %-6s %s %s",
                    name, ouiStr, timeTag, lastSeen));
            shown++;
        }
        if (shown == 0 && row < KindleUtils.ROWS - 1) sc[row++] = pad("  (none detected yet)");

        // ── Footer ───────────────────────────────────────────────────────────
        if (row < KindleUtils.ROWS - 1) {
            sc[KindleUtils.ROWS - 1] = pad(String.format(
                "loop#%d  radar:activity  history:~3min", loop));
        }

        return sc;
    }

    /**
     * Returns true when radar should be shown for change events:
     *   - new AP,
     *   - meaningful distance change,
     *   - AP returned from offline to online.
     */
    static boolean hasInterestingActivity(List<AP> aps) {
        for (AP a : aps) {
            if (a.ghost) continue;
            History h = tracker.get(a.mac);
            boolean isNew = !baseline.contains(a.mac) && armed && h != null && h.isNew;
            boolean distChanged = Math.abs(a.distDeltaM) >= dynamicDistThresholdM(a.dist);
            boolean returned = h != null && h.seenNow && !h.seenPrev && h.count > 3;
            if (isNew || distChanged || returned) return true;
        }
        return false;
    }

    /** Distance-change threshold by distance band (meters). */
    static int dynamicDistThresholdM(double distM) {
        return MovementMetrics.dynamicDistThresholdM(
                distM,
                RADAR_DIST_DELTA_NEAR_M,
                RADAR_DIST_DELTA_MID_M,
                RADAR_DIST_DELTA_FAR_M,
                RADAR_DIST_DELTA_VFAR_M);
    }

    /**
     * Format a duration in milliseconds as a human-readable "last seen" string.
     * e.g.  "just now", "5 min ago", "2 h ago", "3 d ago"
     */
    static String formatLastSeen(long agoMs) {
        if (agoMs < 0) agoMs = 0;
        long secs  = agoMs / 1000L;
        if (secs < 60)    return "just now";
        long mins  = secs / 60L;
        if (mins < 60)    return mins + " min ago";
        long hours = mins / 60L;
        if (hours < 48)   return hours + " h ago";
        long days  = hours / 24L;
        return days + " d ago";
    }

    // =========================================================================
    // E-Ink render  –  row-level diff
    // =========================================================================

    /**
     * Build the full screen as a String[] of ROWS lines, then write only the
     * rows that changed since last render.  This prevents unnecessary e-ink
     * refresh cycles (each eips call causes a partial refresh / wear).
     *
     * A full-screen flash (eips -f) is still used for high-threat alerts to
     * ensure the operator sees new critical information immediately.
     */
    static void render(List<AP> aps, boolean alert) {
        String[] screen = buildScreen(aps);

        // Check whether anything changed
        boolean anyChange = false;
        for (int i = 0; i < KindleUtils.ROWS; i++) {
            if (!screen[i].equals(screenCache[i])) { anyChange = true; break; }
        }
        if (!anyChange) return;

        // Alert: full flash then redraw everything
        if (alert) {
            KindleUtils.exec("eips", "-f");
            KindleUtils.sleep(300);
            KindleUtils.exec("eips", "-c");
            KindleUtils.sleep(50);
            for (int y = 0; y < KindleUtils.ROWS; y++) {
                String l = screen[y];
                if (!l.isEmpty()) KindleUtils.exec("eips", "0", String.valueOf(y), l);
                screenCache[y] = l;
            }
            return;
        }

        // Normal mode: only rewrite changed rows (row-level diff)
        boolean needClear = false;
        // We need a clear if any row got shorter (old content would bleed through)
        for (int y = 0; y < KindleUtils.ROWS; y++) {
            if (screen[y].length() < screenCache[y].length()) { needClear = true; break; }
        }
        if (needClear) {
            KindleUtils.exec("eips", "-c");
            KindleUtils.sleep(50);
            for (int y = 0; y < KindleUtils.ROWS; y++) {
                String l = screen[y];
                if (!l.isEmpty()) KindleUtils.exec("eips", "0", String.valueOf(y), l);
                screenCache[y] = l;
            }
        } else {
            for (int y = 0; y < KindleUtils.ROWS; y++) {
                if (!screen[y].equals(screenCache[y])) {
                    String l = screen[y];
                    if (!l.isEmpty()) KindleUtils.exec("eips", "0", String.valueOf(y), l);
                    else              KindleUtils.exec("eips", "-c"); // blank row via clear (rare)
                    screenCache[y] = l;
                }
            }
        }
    }

    /**
     * Assemble ROWS lines representing the complete current display state.
     * Lines are padded/truncated to COLS characters.
     * Statistics header is omitted when nothing new has been detected.
     */
    static String[] buildScreen(List<AP> aps) {
        String[] sc = new String[KindleUtils.ROWS];
        Arrays.fill(sc, "");
        int row = 0;

        boolean anythingNew = statNewArmed > 0 || statAlertEvents > 0 || hasInterestingActivity(aps);

        // ── Header row 0: time, AP count, threat count, loop ─────────────────
        int thr = 0;
        for (AP a : aps) if (a.threat >= 30) thr++;
        sc[row++] = pad(String.format("DRONE %tT AP:%d THR:%d #%d",
                System.currentTimeMillis(), aps.size(), thr, loop));

        // ── Statistics rows (rows 1-3) only shown when something new found ────
        if (anythingNew) {
            // ── Row 1: RF metrics + armed state ──────────────────────────────
            sc[row++] = pad(String.format("CRC+%d NF:%d SNR:%d LQ:%d %s",
                    crcDelta, noiseFloor, csSnr, linkQuality,
                    armed ? "ARMED" : String.format("LEARN %d/%d", loop, BASELINE_LOOPS)));

            // ── Row 2: uptime + cumulative stats ─────────────────────────────
            long uptimeSec = (System.currentTimeMillis() - startTime) / 1000L;
            sc[row++] = pad(String.format("UP:%s SC:%d MAC:%d NEW:%d PK:%d AL:%d",
                    formatUptime(uptimeSec), statTotalScans,
                    knownNets.size(), statNewArmed, statPeakThreat, statAlertEvents));

            // ── Row 3: weather (updated every 5 minutes) ─────────────────────
            sc[row++] = pad(weatherSummaryForHud());
        } else {
            // No new activity – show just weather summary instead of full stats
            sc[row++] = pad(weatherSummaryForHud());
        }

        // ── Optional: external RF warning ─────────────────────────────────────
        if (externalRF) {
            sc[row++] = pad(String.format("** EXT RF: iCRC=%d thr=%d cnt=%d/%d **",
                    idleCRC, IDLE_CRC_THRESHOLD, externalRFcount, IDLE_CRC_CONFIRM));
        }

        // ── Optional: CRC burst warning ───────────────────────────────────────
        if (crcDelta > 200) {
            sc[row++] = pad("** RF BURST: CRC+" + crcDelta + " **");
        }

        // ── Separator ─────────────────────────────────────────────────────────
        sc[row++] = pad("--------------------------------------");

        // ── AP rows ───────────────────────────────────────────────────────────
        long now = System.currentTimeMillis();
        for (AP a : aps) {
            if (row >= KindleUtils.ROWS - 2) break;
            // After row 20 skip zero-threat APs to keep the screen readable
            if (a.threat == 0 && row > 20) break;
            History h = tracker.get(a.mac);
            char m1 = a.threat >= 60 ? '!' : (a.threat >= 30 ? '+' : ' ');
            char m2 = (h != null && h.isMoving) ? '~' : ' ';
            char m3 = (armed && !baseline.contains(a.mac) && h != null && h.isNew) ? '*' : ' ';

            String dTag = formatDistDeltaTag(a.distDeltaM);
            String ln = String.format("%c%c%c%3.0fm%s %3ddB C%-2d %-16s",
                    m1, m2, m3, a.dist, dTag, a.signalDbm, a.channel,
                    a.ssid.length() > 16 ? a.ssid.substring(0, 16) : a.ssid);

            // Append "last seen" note for known records that haven't been seen recently
            NetRecord nr = knownNets.get(a.mac);
            if (nr != null && !nr.obsTime.isEmpty()) {
                long agoMs = now - nr.lastSeen;
                if (agoMs > 300_000L) { // older than 5 min — worth annotating
                    String lastSeen = " [" + formatLastSeen(agoMs) + "]";
                    int space = KindleUtils.COLS - ln.length();
                    if (space > lastSeen.length()) ln += lastSeen;
                }
            }

            if (!a.flags.isEmpty()) {
                int space = KindleUtils.COLS - ln.length() - 1;
                if (space > 3) ln += " " + a.flags.substring(0, Math.min(a.flags.length(), space));
            }
            sc[row++] = pad(ln);
        }

        // ── Overflow indicator ────────────────────────────────────────────────
        int visibleAPs = row - 5; // approximate header rows
        if (visibleAPs > 0 && aps.size() > visibleAPs) {
            int hidden = aps.size() - visibleAPs;
            if (row < KindleUtils.ROWS) sc[row++] = pad("...+" + hidden + " more");
        }

        return sc;
    }

    /** Truncate/pad a line to exactly COLS characters. */
    static String pad(String s) {
        if (s == null) return "";
        if (s.length() > KindleUtils.COLS) return s.substring(0, KindleUtils.COLS);
        return s;
    }

    /** Format seconds as H:MM:SS. */
    static String formatUptime(long secs) {
        long h = secs / 3600, m = (secs % 3600) / 60, s = secs % 60;
        return String.format("%d:%02d:%02d", h, m, s);
    }

    static String formatDistDeltaTag(int deltaM) {
        return MovementMetrics.formatDistDeltaTag(deltaM);
    }

    // =========================================================================
    // Weather
    // =========================================================================

    static void maybeRefreshWeather() {
        long now = System.currentTimeMillis();
        if (lastWeatherFetchAt != 0 && now - lastWeatherFetchAt < WEATHER_EVERY_MS) return;
        lastWeatherFetchAt = now;

        WeatherService.WeatherData latest = WeatherService.fetchWeather(WEATHER_LOCATION);
        weather = latest;
        if (latest.error == null) {
            logFile("WEATHER ok: " + latest.temp + "C " + latest.description);
        } else {
            logFile("WEATHER err: " + latest.error);
        }
    }

    static String weatherSummaryForHud() {
        return WeatherService.weatherSummaryForHud(weather);
    }

    static String weatherSummaryForRadar() {
        return WeatherService.weatherSummaryForRadar(weather);
    }

    static String shortText(String s, int max) {
        if (s == null || s.isEmpty()) return "--";
        return s.length() > max ? s.substring(0, max) : s;
    }

    static String nz(String s) {
        return (s == null || s.isEmpty()) ? "--" : s;
    }

    static String weatherIcon(String desc) {
        if (desc == null || desc.equals("--")) return "[??]";
        String d = desc.toLowerCase();
        if (d.contains("thunder") || d.contains("storm"))                   return "[!!]";
        if (d.contains("blizzard") || d.contains("sleet"))                  return "[**]";
        if (d.contains("snow") || d.contains("flurr"))                      return "[**]";
        if (d.contains("drizzle"))                                           return "[.~]";
        if (d.contains("rain") || d.contains("shower"))                     return "[~~]";
        if (d.contains("fog") || d.contains("mist") || d.contains("haze")) return "[..]";
        if (d.contains("overcast"))                                          return "[CC]";
        if (d.contains("cloud"))                                             return "[Cc]";
        if (d.contains("clear") || d.contains("sunny") || d.contains("sun")) return "[<>]";
        return "[ -]";
    }

    // Weather data model + HTTP parser moved to WeatherService.

    // =========================================================================
    // Console log
    // =========================================================================

    static void log(List<AP> aps) {
        System.out.printf("%n== #%d %tT APs:%d CRC+%d iCRC:%d NF:%d SNR:%d LQ:%d page:%s%s ==%n",
                loop, System.currentTimeMillis(), aps.size(), crcDelta, idleCRC,
                noiseFloor, csSnr, linkQuality, currentPage, externalRF ? " !!RF!!" : "");
        int i = 0;
        for (AP a : aps) {
            i++;
            // Always print threats and new/moving; print first 5 for context; skip boring tail
            History h = tracker.get(a.mac);
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
                    a.ghost   ? " [GHOST]"  : "",
                    baseline.contains(a.mac) ? "" : " [NEW]");
        }
    }
}
