package com.yep.kindle.dron;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
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
    static final String WEATHER_LOCATION = "Kharkiv";

    // ── File paths ────────────────────────────────────────────────────────────
    static final String LOG_FILE = "/mnt/us/drone_log.txt";
    static final String CSV_FILE = "/mnt/us/drone_nets.csv";
    static final String CSV_TMP  = "/mnt/us/drone_nets.csv.tmp";

    // ── CSV column indices ────────────────────────────────────────────────────
    static final int CSV_MAC        = 0;
    static final int CSV_SSID       = 1;
    static final int CSV_FIRST_SEEN = 2;
    static final int CSV_LAST_SEEN  = 3;
    static final int CSV_COUNT      = 4;
    static final int CSV_PEAK_SIG   = 5;
    static final int CSV_OUI        = 6;
    static final int CSV_KEYWORD    = 7;
    static final int CSV_COLS       = 8;

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
        int     threat    = 0;
        String  flags     = "";
    }

    /** Per-MAC temporal ring buffer (40 observations). */
    static class History {
        static final int N = 40;
        String mac;
        String ssid       = "";
        long   firstSeen, lastSeen;
        int[]  sigs       = new int[N];
        int[]  chs        = new int[N];
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

        void add(int sig, int ch) {
            if (count > 0) {
                int pi = (idx - 1 + N) % N;
                if (chs[pi] != 0 && chs[pi] != ch) chChanges++;
            }
            sigs[idx] = sig;
            chs[idx]  = ch;
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
        String mac;
        String ssid;
        long   firstSeen;
        long   lastSeen;
        int    seenCount;
        int    peakSignal;
        String oui;
        boolean keyword;

        NetRecord(String mac) {
            this.mac       = mac;
            this.firstSeen = System.currentTimeMillis();
            this.lastSeen  = this.firstSeen;
        }

        /** Merge live observation into this persistent record. */
        void update(AP a) {
            lastSeen = System.currentTimeMillis();
            seenCount++;
            if (a.signalDbm > peakSignal) peakSignal = a.signalDbm;
            if (a.ssid != null && !a.ssid.isEmpty() && !a.ssid.equals("[HIDDEN]")) ssid = a.ssid;
            if (oui == null || oui.isEmpty()) oui = DroneSignatures.lookupOUI(mac);
            if (!keyword) keyword = DroneSignatures.matchesKeyword(ssid);
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
    static WeatherData weather = WeatherData.unavailable("boot");
    static long lastWeatherFetchAt = 0;

    // ── Radar state ───────────────────────────────────────────────────────────
    // When > 0, the radar image is currently shown; countdown decrements each
    // loop and when it hits 0 the text HUD is restored (full screen clear).
    static int radarCountdown = 0;

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

            // ── Radar image (once per minute) ─────────────────────────────────
            if (loop % RADAR_EVERY == 0) {
                renderRadarImage(aps);
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
                    // Force full text HUD repaint on next render()
                    Arrays.fill(screenCache, "");
                    KindleUtils.exec("eips", "-c");
                    KindleUtils.sleep(100);
                }
            }

            // ── Render / log ─────────────────────────────────────────────────
            // Skip text HUD while radar is displayed
            if (radarCountdown == 0) render(aps, alert);
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
        File f = new File(CSV_FILE);
        if (!f.exists()) return 0;
        int count = 0;
        try (BufferedReader br = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] col = line.split(",", -1);
                if (col.length < CSV_COLS) continue;
                String mac = col[CSV_MAC].trim().toUpperCase();
                if (mac.length() < 11) continue; // sanity-check MAC format
                NetRecord nr  = new NetRecord(mac);
                nr.ssid       = unescape(col[CSV_SSID]);
                nr.firstSeen  = parseLong(col[CSV_FIRST_SEEN], System.currentTimeMillis());
                nr.lastSeen   = parseLong(col[CSV_LAST_SEEN],  nr.firstSeen);
                nr.seenCount  = parseInt(col[CSV_COUNT], 0);
                nr.peakSignal = parseInt(col[CSV_PEAK_SIG], -99);
                nr.oui        = unescape(col[CSV_OUI]);
                nr.keyword    = "1".equals(col[CSV_KEYWORD].trim());
                knownNets.put(mac, nr);
                baseline.add(mac);
                count++;
            }
            System.out.println("CSV loaded: " + count + " networks from " + CSV_FILE);
            logFile("CSV loaded: " + count + " networks");
        } catch (IOException e) {
            System.err.println("loadNetworksCsv: " + e.getMessage());
        }
        return count;
    }

    /**
     * Atomically save knownNets to CSV (write temp → rename).
     */
    static void saveNetworksCsv() {
        File tmp  = new File(CSV_TMP);
        File dest = new File(CSV_FILE);
        try (PrintWriter pw = new PrintWriter(new BufferedWriter(new FileWriter(tmp)))) {
            pw.println("# mac,ssid,firstSeen,lastSeen,count,peakSignal,oui,keyword");
            for (NetRecord nr : knownNets.values()) {
                pw.printf("%s,%s,%d,%d,%d,%d,%s,%s%n",
                    nr.mac,
                    escape(nr.ssid),
                    nr.firstSeen,
                    nr.lastSeen,
                    nr.seenCount,
                    nr.peakSignal,
                    escape(nr.oui != null ? nr.oui : ""),
                    nr.keyword ? "1" : "0");
            }
        } catch (IOException e) {
            System.err.println("saveNetworksCsv write: " + e.getMessage());
            return;
        }
        // Atomic rename: replaces dest only if write succeeded
        if (!tmp.renameTo(dest)) {
            // renameTo can fail across mount points on some kernels; fall back to copy+delete
            try {
                Files.copy(tmp.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
                tmp.delete();
            } catch (IOException e) {
                System.err.println("saveNetworksCsv rename: " + e.getMessage());
            }
        }
        logFile("CSV saved: " + knownNets.size() + " networks");
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

    // ── CSV string helpers ────────────────────────────────────────────────────

    static String escape(String s) {
        if (s == null || s.isEmpty()) return "";
        // replace commas and newlines to keep CSV well-formed
        return s.replace(",", ";").replace("\n", " ").replace("\r", "");
    }

    static String unescape(String s) {
        return s == null ? "" : s.trim();
    }

    static long parseLong(String s, long def) {
        try { return Long.parseLong(s.trim()); } catch (Exception e) { return def; }
    }

    static int parseInt(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return def; }
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
        if (maxThreat > 0 || externalRF || loop % 10 == 0) {
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("%tT #%d AP:%d CRC+%d iCRC:%d NF:%d SNR:%d THR:%d%s",
                    System.currentTimeMillis(), loop, aps.size(), crcDelta, idleCRC,
                    noiseFloor, csSnr, maxThreat, externalRF ? " !!RF!!" : ""));
            int c = 0;
            for (AP a : aps) {
                if (a.threat > 0 && c < 5) {
                    String ms = a.mac.length() > 9 ? a.mac.substring(9) : a.mac;
                    sb.append(String.format(" | %d:%s(%s)%ddB", a.threat, ms, a.flags, a.signalDbm));
                    c++;
                }
            }
            logWriter.println(sb.toString());
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
            h.add(a.signalDbm, a.channel);
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
    // =========================================================================
    // Radar image rendering
    // =========================================================================

    /**
     * Build the radar PNG, store it in the 3-frame ring buffer, and show the
     * latest frame via {@code eips -g}.
     *
     * Display sequence:
     *   1. eips -c  — clears text-mode ghosting from the previous HUD.
     *   2. eips -g <file> — loads the PNG image and triggers the panel refresh.
     *
     * The radar stays on screen for RADAR_SHOW_LOOPS loops cycling through the
     * last 3 frames; the text HUD is restored automatically afterwards.
     */
    static void renderRadarImage(List<AP> aps) {
        try {
            RadarRenderer.render(aps, armed, loop, externalRF);
            // Clear leftover text ghosting, then display the radar image.
            KindleUtils.exec("eips", "-c");
            KindleUtils.sleep(300);
            KindleUtils.exec("eips", "-g", RadarRenderer.IMAGE_FILE);
            KindleUtils.sleep(500); // allow E-ink panel to complete the refresh
            radarCountdown = RADAR_SHOW_LOOPS;
            logFile("RADAR rendered: " + aps.size() + " APs, frame " + ((RadarRenderer.nextFrame + RadarRenderer.FRAME_COUNT - 1) % RadarRenderer.FRAME_COUNT));
        } catch (Exception e) {
            System.err.println("renderRadarImage: " + e.getMessage());
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
     */
    static String[] buildScreen(List<AP> aps) {
        String[] sc = new String[KindleUtils.ROWS];
        Arrays.fill(sc, "");
        int row = 0;

        // ── Header row 0: time, AP count, threat count, loop ─────────────────
        int thr = 0;
        for (AP a : aps) if (a.threat >= 30) thr++;
        sc[row++] = pad(String.format("DRONE %tT AP:%d THR:%d #%d",
                System.currentTimeMillis(), aps.size(), thr, loop));

        // ── Row 1: RF metrics + armed state ───────────────────────────────────
        sc[row++] = pad(String.format("CRC+%d NF:%d SNR:%d LQ:%d %s",
                crcDelta, noiseFloor, csSnr, linkQuality,
                armed ? "ARMED" : String.format("LEARN %d/%d", loop, BASELINE_LOOPS)));

        // ── Row 2: uptime + cumulative stats ──────────────────────────────────
        long uptimeSec = (System.currentTimeMillis() - startTime) / 1000L;
        sc[row++] = pad(String.format("UP:%s SC:%d MAC:%d NEW:%d PK:%d AL:%d",
                formatUptime(uptimeSec), statTotalScans,
                knownNets.size(), statNewArmed, statPeakThreat, statAlertEvents));

        // ── Row 3: weather (updated every 5 minutes) ─────────────────────────
        sc[row++] = pad(weatherSummaryForHud());

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
        for (AP a : aps) {
            if (row >= KindleUtils.ROWS - 2) break;
            // After row 20 skip zero-threat APs to keep the screen readable
            if (a.threat == 0 && row > 20) break;
            History h = tracker.get(a.mac);
            char m1 = a.threat >= 60 ? '!' : (a.threat >= 30 ? '+' : ' ');
            char m2 = (h != null && h.isMoving) ? '~' : ' ';
            char m3 = (armed && !baseline.contains(a.mac) && h != null && h.isNew) ? '*' : ' ';

            String ln = String.format("%c%c%c%3.0fm %3ddB C%-2d %-16s",
                    m1, m2, m3, a.dist, a.signalDbm, a.channel,
                    a.ssid.length() > 16 ? a.ssid.substring(0, 16) : a.ssid);

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

    // =========================================================================
    // Weather
    // =========================================================================

    static void maybeRefreshWeather() {
        long now = System.currentTimeMillis();
        if (lastWeatherFetchAt != 0 && now - lastWeatherFetchAt < WEATHER_EVERY_MS) return;
        lastWeatherFetchAt = now;

        WeatherData latest = fetchWeather(WEATHER_LOCATION);
        weather = latest;
        if (latest.error == null) {
            logFile("WEATHER ok: " + latest.temp + "C " + latest.description);
        } else {
            logFile("WEATHER err: " + latest.error);
        }
    }

    static WeatherData fetchWeather(String location) {
        WeatherData wd = new WeatherData();
        wd.updatedAt = System.currentTimeMillis();
        try {
            String urlLocation = location.replace(" ", "%20");
            URL url = new URL("http://wttr.in/" + urlLocation + "?format=j1");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(12_000);
            conn.setReadTimeout(12_000);
            conn.setRequestProperty("User-Agent", "curl/7.0");

            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
            }

            String json = sb.toString();
            wd.temp        = field(json, "temp_C");
            wd.feelsLike   = field(json, "FeelsLikeC");
            wd.humidity    = field(json, "humidity");
            wd.windSpeed   = field(json, "windspeedKmph");
            wd.windDir     = field(json, "winddir16Point");
            wd.pressure    = field(json, "pressure");
            wd.description = arrayValue(json, "weatherDesc");
            wd.city        = arrayValue(json, "areaName");
            wd.country     = arrayValue(json, "country");
            wd.error       = null;
        } catch (Exception e) {
            wd.error = e.getClass().getSimpleName() + ":" + e.getMessage();
        }
        return wd;
    }

    static String field(String json, String key) {
        String[] variants = {"\"" + key + "\":\"", "\"" + key + "\": \""};
        for (String search : variants) {
            int i = json.indexOf(search);
            if (i >= 0) {
                i += search.length();
                int e = json.indexOf('"', i);
                if (e > i) return json.substring(i, e);
            }
        }
        return "--";
    }

    static String arrayValue(String json, String key) {
        int i = json.indexOf("\"" + key + "\"");
        if (i < 0) return "--";
        int open = json.indexOf('[', i);
        int close = json.indexOf(']', open);
        if (open < 0 || close < 0 || close <= open) return "--";
        return field(json.substring(open, close), "value");
    }

    static String weatherSummaryForHud() {
        if (weather == null) return "WX: n/a";
        if (weather.error != null) {
            return "WX ERR " + weather.error;
        }
        return String.format("WX %sC FL%s H%s%% W%s%s P%s",
                nz(weather.temp), nz(weather.feelsLike), nz(weather.humidity),
                nz(weather.windSpeed), nz(weather.windDir), nz(weather.pressure));
    }

    static String weatherSummaryForRadar() {
        if (weather == null || weather.error != null) return "WX: N/A";
        return String.format("WX %sC %s", nz(weather.temp), shortText(weather.description, 22));
    }

    static String shortText(String s, int max) {
        if (s == null || s.isEmpty()) return "--";
        return s.length() > max ? s.substring(0, max) : s;
    }

    static String nz(String s) {
        return (s == null || s.isEmpty()) ? "--" : s;
    }

    static class WeatherData {
        String temp = "--", feelsLike = "--", humidity = "--";
        String windSpeed = "--", windDir = "--", description = "--";
        String city = "--", country = "--", pressure = "--";
        String error = null;
        long updatedAt = 0;

        static WeatherData unavailable(String reason) {
            WeatherData w = new WeatherData();
            w.error = reason;
            w.updatedAt = System.currentTimeMillis();
            return w;
        }
    }

    // =========================================================================
    // Console log
    // =========================================================================

    static void log(List<AP> aps) {
        System.out.printf("%n== #%d %tT APs:%d CRC+%d iCRC:%d NF:%d SNR:%d LQ:%d%s ==%n",
                loop, System.currentTimeMillis(), aps.size(), crcDelta, idleCRC,
                noiseFloor, csSnr, linkQuality, externalRF ? " !!RF!!" : "");
        int i = 0;
        for (AP a : aps) {
            i++;
            if (a.threat > 0 || i <= 3) {
                History h = tracker.get(a.mac);
                System.out.printf("%2d)%3d %-17s %-14s C%-2d %4ddB %4.0fm sd=%.1f hop=%d %s%n",
                        i, a.threat, a.mac, a.ssid, a.channel, a.signalDbm, a.dist,
                        h != null ? h.stddev : 0, h != null ? h.chChanges : 0, a.flags);
            }
        }
    }
}
