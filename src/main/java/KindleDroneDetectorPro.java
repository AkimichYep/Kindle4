import java.io.BufferedReader;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.util.*;

public class KindleDroneDetectorPro {

    static final int ROWS = 40;
    static final int COLS = 50;
    static final int FAST_MS = 5000;
    static final int STATS_EVERY = 5;
    static final int PROBE_EVERY = 6;
    static final int IDLE_CRC_EVERY = 10;       // idle CRC measurement interval
    static final int MAXPERF_EVERY = 30;        // re-apply maxperf interval
    static final int IDLE_CRC_THRESHOLD = 6;    // min idle CRC to count as external RF (2s window)
    static final int IDLE_CRC_CONFIRM = 3;     // consecutive hits needed before !!RF!!
    static final int BASELINE_LOOPS = 30;
    static final String LOG_FILE = "/mnt/us/drone_log.txt";

    static final Map<String, String> DRONE_OUI = new HashMap<>();

    static {
        DRONE_OUI.put("60:60:1F", "DJI");
        DRONE_OUI.put("34:D2:62", "DJI");
        DRONE_OUI.put("0C:43:96", "DJI");
        DRONE_OUI.put("18:97:D0", "DJI");
        DRONE_OUI.put("48:1C:B9", "DJI");
        DRONE_OUI.put("E4:7A:2C", "DJI");
        DRONE_OUI.put("A4:77:61", "DJI");
        DRONE_OUI.put("FC:77:74", "DJI");
        DRONE_OUI.put("DC:54:75", "DJI");
        DRONE_OUI.put("C8:4D:44", "DJI");
        DRONE_OUI.put("00:26:19", "Parrot");
        DRONE_OUI.put("00:12:1C", "Parrot");
        DRONE_OUI.put("90:03:B7", "Parrot");
        DRONE_OUI.put("A0:14:3D", "Parrot");
        DRONE_OUI.put("94:E3:6D", "Autel");
        DRONE_OUI.put("38:1D:14", "Skydio");
        DRONE_OUI.put("E0:B6:F5", "Yuneec");
        DRONE_OUI.put("24:0A:C4", "ESP32");
        DRONE_OUI.put("30:AE:A4", "ESP32");
        DRONE_OUI.put("7C:9E:BD", "ESP32");
        DRONE_OUI.put("A4:CF:12", "ESP32");
    }

    static final String[] DRONE_KW = {
            "drone", "dji", "mavic", "tello", "phantom", "spark", "parrot", "anafi",
            "bebop", "fpv", "skydio", "autel", "yuneec", "avata", "inspire", "matrice"
    };

    static final String[] PROBE_SSIDS = {
            "TELLO-", "DJI-", "Spark-", "PHANTOM", "Mavic-", "ANAFI-",
            "Bebop2-", "FPV-", "AVATA-", "SkyController"
    };

    // === Data Structures ===

    static class AP {
        String mac = "", ssid = "", mode = "", encryption = "Open";
        int channel = 0, signalDbm = -999;
        boolean hidden = false;
        double dist = 0;
        int threat = 0;
        String flags = "";
    }

    static class History {
        static final int N = 40;
        String mac;
        String ssid = "";
        long firstSeen, lastSeen;
        int[] sigs = new int[N];
        int[] chs = new int[N];
        int idx = 0, count = 0;
        int chChanges = 0, gaps = 0;
        boolean seenNow = false, seenPrev = false;
        double stddev = 0;
        boolean isNew = false, isMoving = false, isHopping = false, isTransient = false;
        int peakSignal = -999;

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
            chs[idx] = ch;
            idx = (idx + 1) % N;
            count++;
            lastSeen = System.currentTimeMillis();
            if (sig > peakSignal) peakSignal = sig;
        }

        void compute() {
            long now = System.currentTimeMillis();
            int n = Math.min(count, N);
            if (n < 3) {
                stddev = 0;
                return;
            }
            double sum = 0;
            for (int i = 0; i < n; i++) sum += sigs[(idx - 1 - i + N * 2) % N];
            double mean = sum / n;
            double var = 0;
            for (int i = 0; i < n; i++) {
                double d = sigs[(idx - 1 - i + N * 2) % N] - mean;
                var += d * d;
            }
            stddev = Math.sqrt(var / n);
            isNew = (now - firstSeen) < 50000;
            isMoving = stddev > 10.0;
            isHopping = chChanges >= 2;
            isTransient = (gaps >= 2) && (peakSignal > -80);
        }
    }

    // === Global State ===
    static Map<String, History> tracker = new HashMap<>();
    static Set<String> baseline = new HashSet<>();
    static boolean armed = false;
    static int loop = 0;
    static long prevCRC = 0, crcDelta = 0;
    static int noiseFloor = -96, csSnr = 0, linkQuality = 0;
    static boolean probeActive = false;
    static String lastRendered = "";
    static String lastRenderSignature = "";
    static PrintWriter logWriter = null;

    // Idle CRC detection state
    static long idleCRC = 0;
    static boolean externalRF = false;
    static int externalRFcount = 0;     // consecutive idle CRC detections

    // === Main ===
    public static void main(String[] args) {
        System.out.println("=== KindleDroneDetectorPro v2.1 ===");

        // Prevent Kindle sleep for the duration of the detector run
        exec("lipc-set-prop", "com.lab126.powerd", "preventScreenSaver", "1");
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            exec("lipc-set-prop", "com.lab126.powerd", "preventScreenSaver", "0");
            System.out.println("Sleep restored.");
        }));

        openLogFile();
        initFirmware();
        prevCRC = readStats(true);

        while (true) {
            long t0 = System.currentTimeMillis();
            loop++;

            // Re-apply maxperf periodically (Kindle resets to "rec")
            if (loop % MAXPERF_EVERY == 0) {
                exec("wmiconfig", "-i", "wlan0", "--power", "maxperf");
            }

            // Pre-scan: probe setting
            if (loop % PROBE_EVERY == 0) {
                String probe = PROBE_SSIDS[(loop / PROBE_EVERY) % PROBE_SSIDS.length];
                exec("wmiconfig", "-i", "wlan0", "--scanprobedssid", probe);
                probeActive = true;
                logFile("PROBE: " + probe);
            } else if (probeActive) {
                exec("wmiconfig", "-i", "wlan0", "--scanprobedssid", "any");
                probeActive = false;
            }

            // Scan
            List<AP> aps = scan();
            temporal(aps);

            // Fast noise/signal from /proc/net/wireless (every loop, cheap)
            readProcWireless();

            // Firmware stats via wmiconfig (expensive, less frequent)
            if (loop % STATS_EVERY == 0) {
                long newCRC = readStats(true);
                crcDelta = newCRC - prevCRC;
                prevCRC = newCRC;
            }

            // Idle CRC measurement: detect external RF on home channel
            if (loop % IDLE_CRC_EVERY == 0) {
                measureIdleCRC();
            }

            // Score
            score(aps);
            aps.sort((a, b) -> b.threat != a.threat ? b.threat - a.threat : Double.compare(a.dist, b.dist));

            // Learning
            if (loop <= BASELINE_LOOPS) {
                for (AP a : aps) baseline.add(a.mac);
                if (loop == BASELINE_LOOPS) {
                    armed = true;
                    System.out.println("ARMED: " + baseline.size() + " MACs baselined");
                    logFile("ARMED: " + baseline.size() + " MACs baselined");
                }
            }

            // Alert level
            int maxThreat = aps.isEmpty() ? 0 : aps.get(0).threat;

            // Render
            render(aps, maxThreat >= 60 || externalRF);

            // Console log
            log(aps);

            // File log
            logLoopToFile(aps, maxThreat);

            // Wait
            long wait = FAST_MS - (System.currentTimeMillis() - t0);
            if (wait > 0) sleep(wait);
        }
    }

    // === Idle CRC Measurement ===
    // Measures CRC errors during a brief period of NO scanning.
    // Any CRC errors during idle = external RF on home channel.
    static void measureIdleCRC() {
        // Clear stats
        exec("wmiconfig", "-i", "wlan0", "--getTargetStats", "--clearStats");
        // Idle listen (no scanning, radio stays on home channel)
        sleep(2000);
        // Read CRC accumulated during idle
        idleCRC = readStats(false);

        if (idleCRC >= IDLE_CRC_THRESHOLD) {
            externalRFcount++;
            if (externalRFcount >= IDLE_CRC_CONFIRM) {
                externalRF = true;
            }
            String msg = "!! IDLE-CRC=" + idleCRC + " external RF (count=" + externalRFcount + "/" + IDLE_CRC_CONFIRM + ")";
            System.out.println(msg);
            logFile(msg);
        } else {
            // Decay: require consecutive non-detections to clear
            if (externalRFcount > 0) externalRFcount--;
            if (externalRFcount == 0) externalRF = false;
        }

        // Re-clear for normal operation delta tracking
        long fresh = readStats(true);
        prevCRC = fresh;
        crcDelta = 0;
    }

    // === Fast /proc/net/wireless reader ===
    // Returns live signal/noise without spawning wmiconfig.
    // Format: wlan0: SSSS  LL  LVL  NNN  ...
    //   level and noise are unsigned; subtract 256 if > 127 for dBm.
    static void readProcWireless() {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"cat", "/proc/net/wireless"});
            p.waitFor();
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            while ((line = r.readLine()) != null) {
                if (line.trim().startsWith("wlan0")) {
                    String[] parts = line.trim().split("\\s+");
                    // parts: [wlan0:, status, link, level, noise, ...]
                    if (parts.length >= 5) {
                        try {
                            linkQuality = Integer.parseInt(parts[2].replace(".", "").trim());
                            int level = Integer.parseInt(parts[3].replace(".", "").trim());
                            int noise = Integer.parseInt(parts[4].replace(".", "").trim());
                            if (level > 127) level -= 256;
                            if (noise > 127) noise -= 256;
                            noiseFloor = noise;
                            // csSnr can be derived: level - noise
                            csSnr = level - noise;
                        } catch (Exception ignored) {
                        }
                    }
                    break;
                }
            }
            r.close();
        } catch (Exception ignored) {
        }
    }

    // === Logging ===
    static void openLogFile() {
        try {
            logWriter = new PrintWriter(new FileWriter(LOG_FILE, true), true);
            logWriter.println("--- START " + new Date() + " ---");
        } catch (Exception e) {
            System.err.println("Cannot open log file: " + e.getMessage());
        }
    }

    static void logFile(String msg) {
        if (logWriter != null) {
            logWriter.printf("%tT %s%n", System.currentTimeMillis(), msg);
        }
    }

    static void logLoopToFile(List<AP> aps, int maxThreat) {
        if (logWriter == null) return;
        if (maxThreat > 0 || externalRF || loop % 10 == 0) {
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("%tT #%d AP:%d CRC+%d iCRC:%d NF:%d SNR:%d THR:%d%s",
                    System.currentTimeMillis(), loop, aps.size(), crcDelta, idleCRC,
                    noiseFloor, csSnr, maxThreat, externalRF ? " !!RF!!" : ""));
            int count = 0;
            for (AP a : aps) {
                if (a.threat > 0 && count < 5) {
                    String macShort = a.mac.length() > 9 ? a.mac.substring(9) : a.mac;
                    sb.append(String.format(" | %d:%s(%s)%ddB",
                            a.threat, macShort, a.flags, a.signalDbm));
                    count++;
                }
            }
            logWriter.println(sb.toString());
        }
    }

    // === Firmware Init ===
    static void initFirmware() {
        exec("wmiconfig", "-i", "wlan0", "--power", "maxperf");
        sleep(200);
        exec("wmiconfig", "-i", "wlan0", "--scan",
                "--fgstart=1", "--fgend=1", "--bg=3",
                "--minact=30", "--maxact=150", "--pas=200",
                "--scanctrlflags", "1", "1", "1", "1", "1", "1");
        sleep(200);
        exec("wmiconfig", "-i", "wlan0", "--getTargetStats", "--clearStats");
        sleep(200);
        System.out.println("FW: maxperf, 200ms dwell, BSS reporting ON");
    }

    // === Scan ===
    static List<AP> scan() {
        List<AP> list = new ArrayList<>();
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"iwlist", "wlan0", "scan"});
            p.waitFor();
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            AP cur = null;
            while ((line = r.readLine()) != null) {
                String t = line.trim();
                if (t.contains("Address:")) {
                    if (cur != null && cur.signalDbm != -999) {
                        fin(cur);
                        list.add(cur);
                    }
                    cur = new AP();
                    cur.mac = t.substring(t.indexOf("Address:") + 9).trim();
                } else if (cur == null) continue;
                else if (t.startsWith("ESSID:")) {
                    String v = t.substring(6).trim();
                    if (v.startsWith("\"") && v.endsWith("\"") && v.length() >= 2) v = v.substring(1, v.length() - 1);
                    cur.ssid = v.isEmpty() ? "[HIDDEN]" : v;
                    cur.hidden = v.isEmpty();
                } else if (t.startsWith("Mode:")) cur.mode = t.substring(5).trim();
                else if (t.startsWith("Frequency:")) {
                    int ci = t.indexOf("Channel");
                    if (ci > 0) try {
                        cur.channel = Integer.parseInt(t.substring(ci + 8).replace(")", "").trim());
                    } catch (Exception e) {
                    }
                } else if (t.contains("Signal level=")) {
                    try {
                        int si = t.indexOf("Signal level=") + 13;
                        int se = t.indexOf(" dBm", si);
                        if (se > si) cur.signalDbm = Integer.parseInt(t.substring(si, se).trim());
                    } catch (Exception e) {
                    }
                } else if (t.contains("WPA2") || t.contains("802.11i")) cur.encryption = "WPA2";
                else if (t.contains("WPA Version")) {
                    if (!cur.encryption.equals("WPA2")) cur.encryption = "WPA";
                } else if (t.startsWith("Encryption key:on")) {
                    if (cur.encryption.equals("Open")) cur.encryption = "WEP";
                }
            }
            if (cur != null && cur.signalDbm != -999) {
                fin(cur);
                list.add(cur);
            }
            r.close();
        } catch (Exception e) {
            System.err.println("scan err: " + e.getMessage());
        }
        return list;
    }

    static void fin(AP a) {
        a.dist = Math.max(0.5, Math.pow(10.0, (-30.0 - a.signalDbm) / 27.0));
        if (a.ssid == null || a.ssid.isEmpty()) {
            a.ssid = "[HIDDEN]";
            a.hidden = true;
        }
    }

    // === Temporal Engine ===
    static void temporal(List<AP> aps) {
        for (History h : tracker.values()) {
            h.seenPrev = h.seenNow;
            h.seenNow = false;
        }
        for (AP a : aps) {
            History h = tracker.get(a.mac);
            if (h == null) {
                h = new History(a.mac);
                tracker.put(a.mac, h);
            }
            h.add(a.signalDbm, a.channel);
            h.ssid = a.ssid;
            h.seenNow = true;
        }
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, History>> it = tracker.entrySet().iterator();
        while (it.hasNext()) {
            History h = it.next().getValue();
            if (h.seenNow && !h.seenPrev && h.count > 3) h.gaps++;
            h.compute();
            if (now - h.lastSeen > 180000) it.remove();
        }
    }

    // === Firmware Stats (single call) ===
    static long readStats(boolean updateNoise) {
        long crc = 0;
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"wmiconfig", "-i", "wlan0", "--getTargetStats"});
            p.waitFor();
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.startsWith("rx_crcerr")) {
                    crc = Long.parseLong(line.split("=")[1].trim());
                } else if (updateNoise && line.startsWith("noise_floor")) {
                    try {
                        noiseFloor = Integer.parseInt(line.split("=")[1].trim());
                    } catch (Exception ignored) {
                    }
                } else if (updateNoise && line.startsWith("cs_snr")) {
                    try {
                        csSnr = Integer.parseInt(line.split("=")[1].trim().split("\\s")[0]);
                    } catch (Exception ignored) {
                    }
                }
            }
            r.close();
        } catch (Exception e) {
        }
        return crc;
    }

    // === Scoring ===
    static void score(List<AP> aps) {
        for (AP a : aps) {
            int s = 0;
            StringBuilder f = new StringBuilder();

            // OUI match
            String v = getOUI(a.mac);
            if (v != null) {
                s += 50;
                f.append(v).append(" ");
            }

            // SSID keyword
            if (matchKW(a.ssid)) {
                s += 40;
                f.append("SSID ");
            }

            // Hidden within 80m
            if (a.hidden && a.dist < 80) {
                s += 15;
                f.append("HID ");
            }

            // Temporal
            History h = tracker.get(a.mac);
            if (h != null) {
                if (h.isNew && armed && !baseline.contains(a.mac) && a.signalDbm > -80) {
                    s += 25;
                    f.append("NEW ");
                }
                if (h.isMoving) {
                    s += 20;
                    f.append("MOV ");
                }
                if (h.isHopping) {
                    s += 20;
                    f.append("HOP ");
                }
                if (h.isTransient) {
                    s += 15;
                    f.append("TRN ");
                }
            }

            // Non-master mode
            if (a.mode != null && !a.mode.isEmpty() && !a.mode.equals("Master")) {
                s += 20;
                f.append("ADH ");
            }

            // Strong new device
            if (armed && !baseline.contains(a.mac) && a.signalDbm > -65) {
                s += 20;
                f.append("STR ");
            }

            // Random MAC
            if (a.mac.length() >= 2) {
                try {
                    int firstByte = Integer.parseInt(a.mac.substring(0, 2), 16);
                    if ((firstByte & 0x02) != 0) {
                        s += 10;
                        f.append("RMAC ");
                    }
                } catch (Exception ignored) {
                }
            }

            a.threat = Math.min(s, 100);
            a.flags = f.toString().trim();
        }
    }

    static String getOUI(String mac) {
        if (mac == null || mac.length() < 8) return null;
        return DRONE_OUI.get(mac.substring(0, 8).toUpperCase());
    }

    static boolean matchKW(String ssid) {
        if (ssid == null) return false;
        String l = ssid.toLowerCase();
        for (String k : DRONE_KW) if (l.contains(k)) return true;
        return false;
    }

    // === E-Ink Render ===
    static void render(List<AP> aps, boolean alert) {
        // Only redraw when visible AP/alert content changed; ignore loop/time churn.
        String signature = buildRenderSignature(aps);
        if (signature.equals(lastRenderSignature)) return;

        StringBuilder hud = new StringBuilder();
        int row = 0;

        // Header
        int thr = 0;
        for (AP a : aps) if (a.threat >= 30) thr++;
        hud.append(String.format("DRONE %tT AP:%d THR:%d #%d",
                System.currentTimeMillis(), aps.size(), thr, loop)).append("\n");
        row++;
        hud.append(String.format("CRC+%d NF:%d SNR:%d LQ:%d %s",
                crcDelta, noiseFloor, csSnr, linkQuality,
                armed ? "ARMED" : "LEARN")).append("\n");
        row++;

        // External RF alert
        if (externalRF) {
            hud.append(String.format("** EXT RF: iCRC=%d thr=%d cnt=%d/%d **\n",
                    idleCRC, IDLE_CRC_THRESHOLD, externalRFcount, IDLE_CRC_CONFIRM));
            row++;
        }

        // CRC burst alert
        if (crcDelta > 200) {
            hud.append("** RF BURST: CRC+").append(crcDelta).append(" **\n");
            row++;
        }

        hud.append("--------------------------------------\n");
        row++;

        // AP list
        for (AP a : aps) {
            if (row >= ROWS - 1) break;
            if (a.threat == 0 && row > 20) break;
            History h = tracker.get(a.mac);
            char m1 = a.threat >= 60 ? '!' : (a.threat >= 30 ? '+' : ' ');
            char m2 = (h != null && h.isMoving) ? '~' : ' ';
            char m3 = (armed && !baseline.contains(a.mac) && h != null && h.isNew) ? '*' : ' ';

            String ln = String.format("%c%c%c%3.0fm %3ddB C%-2d %-16s",
                    m1, m2, m3, a.dist, a.signalDbm, a.channel,
                    a.ssid.length() > 16 ? a.ssid.substring(0, 16) : a.ssid);

            if (a.flags.length() > 0) {
                int space = COLS - ln.length() - 2;
                if (space > 3) ln += " " + a.flags.substring(0, Math.min(a.flags.length(), space));
            }
            if (ln.length() > COLS) ln = ln.substring(0, COLS);
            hud.append(ln).append("\n");
            row++;
        }

        if (aps.size() > ROWS - 5) hud.append("...+").append(aps.size() - (ROWS - 5)).append(" more\n");

        String content = hud.toString();
        if (alert) {
            try {
                Runtime.getRuntime().exec(new String[]{"eips", "-f"}).waitFor();
            } catch (Exception e) {
            }
            sleep(300);
        }
        writeEink(content);
        lastRendered = content;
        lastRenderSignature = signature;
    }

    static String buildRenderSignature(List<AP> aps) {
        StringBuilder sb = new StringBuilder(256);
        sb.append("rf=").append(externalRF)
                .append("|i=").append(idleCRC)
                .append("|b=").append(crcDelta > 200)
                .append("|n=").append(noiseFloor)
                .append("|s=").append(csSnr)
                .append("|l=").append(linkQuality)
                .append("|a=").append(armed);

        for (AP a : aps) {
            History h = tracker.get(a.mac);
            sb.append('|').append(a.mac)
                    .append(',').append(a.ssid)
                    .append(',').append(a.channel)
                    .append(',').append(a.signalDbm)
                    .append(',').append(a.threat)
                    .append(',').append(a.flags);
            if (h != null) {
                sb.append(',').append(h.isNew)
                        .append(',').append(h.isMoving)
                        .append(',').append(h.isHopping)
                        .append(',').append(h.isTransient);
            }
        }
        return sb.toString();
    }

    static void writeEink(String text) {
        try {
            Runtime.getRuntime().exec(new String[]{"eips", "-c"}).waitFor();
            sleep(50);
            String[] lines = text.split("\n");
            for (int y = 0; y < lines.length && y < ROWS; y++) {
                String l = lines[y];
                if (l.isEmpty()) continue;
                if (l.length() > COLS) l = l.substring(0, COLS);
                Runtime.getRuntime().exec(new String[]{"eips", "0", String.valueOf(y), l}).waitFor();
            }
        } catch (Exception e) {
            System.err.println("eips: " + e.getMessage());
        }
    }

    // === Console Log ===
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

    // === Util ===
    static void exec(String... cmd) {
        try {
            Runtime.getRuntime().exec(cmd).waitFor();
        } catch (Exception e) {
            System.err.println("exec: " + e.getMessage());
        }
    }

    static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (Exception ignored) {
        }
    }
}