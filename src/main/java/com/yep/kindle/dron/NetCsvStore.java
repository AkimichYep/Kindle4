package com.yep.kindle.dron;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * CSV persistence for known networks and distance-history codec.
 */
final class NetCsvStore {
    private NetCsvStore() {}

    private static final int CSV_MAC = 0;
    private static final int CSV_SSID = 1;
    private static final int CSV_FIRST_SEEN = 2;
    private static final int CSV_LAST_SEEN = 3;
    private static final int CSV_COUNT = 4;
    private static final int CSV_PEAK_SIG = 5;
    private static final int CSV_OUI = 6;
    private static final int CSV_KEYWORD = 7;
    private static final int CSV_OBS_TIME = 8;
    private static final int CSV_DIST_HIST = 9;

    static int load(String csvFile,
                    Map<String, KindleDroneDetectorPro.NetRecord> knownNets,
                    Set<String> baseline,
                    Consumer<String> log) {
        File f = new File(csvFile);
        if (!f.exists()) return 0;
        int count = 0;

        try (BufferedReader br = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;

                String[] col = line.split(",", -1);
                if (col.length < 8) continue; // legacy minimum

                String mac = col[CSV_MAC].trim().toUpperCase();
                if (mac.length() < 11) continue;

                KindleDroneDetectorPro.NetRecord nr = new KindleDroneDetectorPro.NetRecord(mac);
                nr.ssid = unescape(col[CSV_SSID]);
                nr.firstSeen = parseLong(col[CSV_FIRST_SEEN], System.currentTimeMillis());
                nr.lastSeen = parseLong(col[CSV_LAST_SEEN], nr.firstSeen);
                nr.seenCount = parseInt(col[CSV_COUNT], 0);
                nr.peakSignal = parseInt(col[CSV_PEAK_SIG], -99);
                nr.oui = unescape(col[CSV_OUI]);
                nr.keyword = "1".equals(col[CSV_KEYWORD].trim());

                if (col.length > CSV_OBS_TIME) nr.obsTime = unescape(col[CSV_OBS_TIME]);
                if (col.length > CSV_DIST_HIST) decodeDistHistory(nr, unescape(col[CSV_DIST_HIST]));

                knownNets.put(mac, nr);
                baseline.add(mac);
                count++;
            }
            log(log, "CSV loaded: " + count + " networks");
        } catch (IOException e) {
            System.err.println("loadNetworksCsv: " + e.getMessage());
        }
        return count;
    }

    static void save(String csvTmp,
                     String csvFile,
                     Map<String, KindleDroneDetectorPro.NetRecord> knownNets,
                     Consumer<String> log) {
        File tmp = new File(csvTmp);
        File dest = new File(csvFile);

        try (PrintWriter pw = new PrintWriter(new BufferedWriter(new FileWriter(tmp)))) {
            pw.println("# mac,ssid,firstSeen,lastSeen,count,peakSignal,oui,keyword,obsTime,distHist");
            for (KindleDroneDetectorPro.NetRecord nr : knownNets.values()) {
                pw.printf("%s,%s,%d,%d,%d,%d,%s,%s,%s,%s%n",
                        nr.mac,
                        escape(nr.ssid),
                        nr.firstSeen,
                        nr.lastSeen,
                        nr.seenCount,
                        nr.peakSignal,
                        escape(nr.oui != null ? nr.oui : ""),
                        nr.keyword ? "1" : "0",
                        escape(nr.obsTime != null ? nr.obsTime : ""),
                        escape(encodeDistHistory(nr.distHistory)));
            }
        } catch (IOException e) {
            System.err.println("saveNetworksCsv write: " + e.getMessage());
            return;
        }

        if (!tmp.renameTo(dest)) {
            try {
                Files.copy(tmp.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
                tmp.delete();
            } catch (IOException e) {
                System.err.println("saveNetworksCsv rename: " + e.getMessage());
            }
        }
        log(log, "CSV saved: " + knownNets.size() + " networks");
    }

    static String encodeDistHistory(java.util.Deque<Integer> distHistory) {
        if (distHistory == null || distHistory.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (int d : distHistory) {
            if (!first) sb.append(';');
            sb.append(d);
            first = false;
        }
        return sb.toString();
    }

    static void decodeDistHistory(KindleDroneDetectorPro.NetRecord nr, String raw) {
        nr.distHistory.clear();
        if (raw == null || raw.trim().isEmpty()) return;
        String[] parts = raw.split(";");
        for (String p : parts) {
            try {
                int d = Integer.parseInt(p.trim());
                nr.pushDist(d);
            } catch (Exception ignored) {
            }
        }
        if (!nr.distHistory.isEmpty()) {
            nr.lastDistM = nr.distHistory.peekLast();
            if (nr.distHistory.size() >= 2) {
                java.util.Iterator<Integer> it = nr.distHistory.descendingIterator();
                int last = it.next();
                int prev = it.next();
                nr.lastDistDeltaM = last - prev;
            }
        }
    }

    private static void log(Consumer<String> sink, String msg) {
        if (sink != null) sink.accept(msg);
    }

    private static String escape(String s) {
        if (s == null || s.isEmpty()) return "";
        return s.replace(",", ";").replace("\n", " ").replace("\r", "");
    }

    private static String unescape(String s) {
        return s == null ? "" : s.trim();
    }

    private static long parseLong(String s, long def) {
        try {
            return Long.parseLong(s.trim());
        } catch (Exception e) {
            return def;
        }
    }

    private static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return def;
        }
    }
}

