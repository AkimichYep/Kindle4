package com.yep.kindle.dron.service;

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

import com.yep.kindle.dron.model.NetRecord;
import com.yep.kindle.dron.util.AppLog;

/**
 * CSV persistence for known networks and distance-history codec.
 */
public final class NetCsvStore {
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
    private static final int CSV_LAST_CHANNEL = 10;  // last seen channel
    private static final int CSV_SURGE_COUNT = 11;   // lifetime surge events
    private static final int CSV_MAX_CONSEC_APP = 12; // max consecutive approach streak
    private static final int CSV_LAST_FLAGS = 13;    // last scoring flags (SRG, APR, MOV, …)

    public static int load(String csvFile,
                    Map<String, NetRecord> knownNets,
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

                NetRecord nr = new NetRecord(mac);
                nr.ssid = unescape(col[CSV_SSID]);
                nr.firstSeen = parseLong(col[CSV_FIRST_SEEN], System.currentTimeMillis());
                nr.lastSeen = parseLong(col[CSV_LAST_SEEN], nr.firstSeen);
                nr.seenCount = parseInt(col[CSV_COUNT], 0);
                int ps = parseInt(col[CSV_PEAK_SIG], -999);
                nr.peakSignal = (ps == 0) ? -999 : ps; // 0 = legacy buggy value, treat as unknown
                nr.oui = unescape(col[CSV_OUI]);
                nr.keyword = "1".equals(col[CSV_KEYWORD].trim());

                if (col.length > CSV_OBS_TIME)       nr.obsTime                = unescape(col[CSV_OBS_TIME]);
                if (col.length > CSV_DIST_HIST)      decodeDistHistory(nr, unescape(col[CSV_DIST_HIST]));
                if (col.length > CSV_LAST_CHANNEL)   nr.lastChannel            = parseInt(col[CSV_LAST_CHANNEL], 0);
                if (col.length > CSV_SURGE_COUNT)    nr.surgeCount             = parseInt(col[CSV_SURGE_COUNT], 0);
                if (col.length > CSV_MAX_CONSEC_APP) nr.maxConsecutiveApproach = parseInt(col[CSV_MAX_CONSEC_APP], 0);
                if (col.length > CSV_LAST_FLAGS)     nr.lastFlags              = unescape(col[CSV_LAST_FLAGS]);

                knownNets.put(mac, nr);
                baseline.add(mac);
                count++;
            }
            log(log, "CSV loaded: " + count + " networks");
        } catch (IOException e) {
            AppLog.err("loadNetworksCsv: " + e.getMessage());
        }
        return count;
    }

    public static void save(String csvTmp,
                     String csvFile,
                     Map<String, NetRecord> knownNets,
                     Consumer<String> log) {
        File tmp = new File(csvTmp);
        File dest = new File(csvFile);

        long now = System.currentTimeMillis();
        int written = 0, pruned = 0;
        try (PrintWriter pw = new PrintWriter(new BufferedWriter(new FileWriter(tmp)))) {
            pw.println("# mac,ssid,firstSeen,lastSeen,count,peakSignal,oui,keyword,obsTime,distHist,lastCh,surgeCnt,maxApp,lastFlags");
            for (NetRecord nr : knownNets.values()) {
                // Skip transient junk: rarely-seen, long-gone MACs with no signal of
                // interest (no OUI/keyword hit, no surge, no approach streak). Keeps the
                // persisted baseline meaningful and bounded on long runs. Non-destructive:
                // the in-memory record is retained; only the on-disk file is trimmed.
                if (isTransientJunk(nr, now)) { pruned++; continue; }
                pw.printf("%s,%s,%d,%d,%d,%d,%s,%s,%s,%s,%d,%d,%d,%s%n",
                        nr.mac,
                        escape(nr.ssid),
                        nr.firstSeen,
                        nr.lastSeen,
                        nr.seenCount,
                        nr.peakSignal,
                        escape(nr.oui != null ? nr.oui : ""),
                        nr.keyword ? "1" : "0",
                        escape(nr.obsTime != null ? nr.obsTime : ""),
                        escape(encodeDistHistory(nr.distHistory)),
                        nr.lastChannel,
                        nr.surgeCount,
                        nr.maxConsecutiveApproach,
                        escape(nr.lastFlags != null ? nr.lastFlags : ""));
                written++;
            }
        } catch (IOException e) {
            AppLog.err("saveNetworksCsv write: " + e.getMessage());
            return;
        }

        if (!tmp.renameTo(dest)) {
            try {
                Files.copy(tmp.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
                tmp.delete();
            } catch (IOException e) {
                AppLog.err("saveNetworksCsv rename: " + e.getMessage());
            }
        }
        log(log, "CSV saved: " + written + " networks" + (pruned > 0 ? " (" + pruned + " transient pruned)" : ""));
    }

    /** Stale, low-value MAC with no drone-relevant signal — safe to drop from the CSV. */
    private static boolean isTransientJunk(NetRecord nr, long now) {
        boolean interesting = (nr.oui != null && !nr.oui.isEmpty())
                || nr.keyword
                || nr.surgeCount > 0
                || nr.maxConsecutiveApproach >= 3;
        boolean stale = (now - nr.lastSeen) > 3_600_000L; // not seen for > 1 h
        return !interesting && stale && nr.seenCount < 5;
    }

    public static String encodeDistHistory(java.util.Deque<Integer> distHistory) {
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

    public static void decodeDistHistory(NetRecord nr, String raw) {
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

