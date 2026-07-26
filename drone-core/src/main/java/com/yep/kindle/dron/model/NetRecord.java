package com.yep.kindle.dron.model;

import java.util.ArrayDeque;
import java.util.Deque;

import com.yep.kindle.dron.detection.DroneSignatures;
import com.yep.kindle.dron.service.NetCsvStore;
import com.yep.kindle.dron.util.WifiUtils;

/**
 * Persistent record loaded from / saved to CSV.
 * Tracks EMA-smoothed RSSI, distance history, consecutive approach streaks,
 * and surge detection for each known MAC address.
 */
public class NetRecord {
    public static final int DIST_KEEP = 16;

    public String mac;
    public String ssid;
    public long   firstSeen;
    public long   lastSeen;
    public int    seenCount;
    public int    peakSignal = -999; // must start below any real RSSI value
    public String oui;
    public boolean keyword;
    public String obsTime = ""; // HH:mm wall-clock time of last observation
    public int    lastDistM = -1;
    public int    lastDistDeltaM = 0;
    public int    smoothedRssi = Integer.MIN_VALUE; // EMA-smoothed signal; resets each session
    public int    emaWarmup   = 0;  // scans since EMA init; consecutiveApproach blocked for first 3
    public int    consecutiveApproach    = 0;  // EMA-dist decreasing in a row; reset on reversal
    public int    maxConsecutiveApproach = 0;  // lifetime peak, persisted to CSV
    public boolean surgeDetected         = false; // raw RSSI jumped >12 dBm vs smoothed this scan
    public int    surgeRawDelta          = 0;  // magnitude of the surge for logging
    public int    surgeCount             = 0;  // lifetime surge events, persisted to CSV
    public int    lastChannel            = 0;  // last seen channel, persisted to CSV
    public String lastFlags              = ""; // last scoring flags, persisted to CSV
    public Deque<Integer> distHistory = new ArrayDeque<>();

    public NetRecord(String mac) {
        this.mac       = mac;
        this.firstSeen = System.currentTimeMillis();
        this.lastSeen  = this.firstSeen;
    }

    /**
     * Merge live observation into this persistent record.
     * Updates EMA-smoothed RSSI, detects surges, tracks consecutive approach streaks.
     */
    public void update(AP a) {
        lastSeen = System.currentTimeMillis();
        seenCount++;
        obsTime = String.format("%tR", lastSeen);
        if (a.signalDbm > peakSignal) peakSignal = a.signalDbm;
        if (a.ssid != null && !a.ssid.isEmpty() && !a.ssid.equals("[HIDDEN]")) ssid = a.ssid;
        if (oui == null || oui.isEmpty()) oui = DroneSignatures.lookupOUI(mac);
        if (!keyword) keyword = DroneSignatures.matchesKeyword(ssid);

        if (a.channel > 0) lastChannel = a.channel;

        // EMA on RSSI (α=0.25) reduces per-scan noise (±5 dBm raw) to ~1.9 dBm.
        // Fast-path surge: if raw signal jumped >12 dBm vs smoothed baseline in one
        // 5-second scan, a rapid physical approach is likely. Flag it before EMA damps
        // the signal — at 50 km/h a drone closes ~70 m per scan, which maps to 12+ dBm.
        if (smoothedRssi == Integer.MIN_VALUE) {
            smoothedRssi  = a.signalDbm;
            surgeDetected = false;
            surgeRawDelta = 0;
            emaWarmup     = 0;
        } else {
            if (emaWarmup < 3) emaWarmup++;
            int rawDelta  = a.signalDbm - smoothedRssi; // positive = getting stronger
            surgeDetected = (rawDelta > 12);
            surgeRawDelta = surgeDetected ? rawDelta : 0;
            if (surgeDetected) surgeCount++;
            smoothedRssi  = (int) Math.round(0.25 * a.signalDbm + 0.75 * smoothedRssi);
        }
        int dm = (int) Math.round(WifiUtils.calculateDistance(smoothedRssi));

        // Suppress consecutiveApproach during EMA warm-up (first 3 scans after init).
        // Without this, the EMA settling drift falsely inflates maxConsecutiveApproach
        // for static routers that were loaded from CSV with a reset smoothedRssi.
        if (lastDistM >= 0 && emaWarmup >= 3) {
            lastDistDeltaM = dm - lastDistM;
            if (dm < lastDistM) {
                consecutiveApproach++;
                if (consecutiveApproach > maxConsecutiveApproach)
                    maxConsecutiveApproach = consecutiveApproach;
            } else {
                consecutiveApproach = 0;
            }
        } else {
            if (lastDistM >= 0) lastDistDeltaM = dm - lastDistM;
            consecutiveApproach = 0;
        }
        lastDistM = dm;
        pushDist(dm);
        a.distDeltaM = lastDistDeltaM;
    }

    public void pushDist(int distM) {
        distHistory.addLast(distM);
        while (distHistory.size() > DIST_KEEP) distHistory.removeFirst();
    }

    public String encodeDistHistory() {
        return NetCsvStore.encodeDistHistory(distHistory);
    }

    public void decodeDistHistory(String raw) {
        NetCsvStore.decodeDistHistory(this, raw);
    }
}
