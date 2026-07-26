package com.yep.kindle.dron.model;

import java.util.Set;

/**
 * Per-MAC temporal ring buffer (40 observations).
 * Tracks signal history, channel changes, and gap events.
 * Used by TemporalEngine to detect moving, hopping, and transient APs.
 */
public class History {
    public static final int N = 40;

    public String mac;
    public String ssid       = "";
    public long   firstSeen, lastSeen;
    public int[]  sigs       = new int[N];
    public int[]  chs        = new int[N];
    public int[]  dists      = new int[N];
    public int    idx        = 0, count = 0;
    public int    chChanges  = 0, gaps   = 0;
    public boolean seenNow = false, seenPrev = false;
    public double  stddev    = 0;
    public boolean isNew = false, isMoving = false, isHopping = false, isTransient = false;
    public int     peakSignal = -999;

    public History(String m) {
        mac = m;
        firstSeen = lastSeen = System.currentTimeMillis();
    }

    public void add(int sig, int ch, double distM) {
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

    /**
     * Recompute derived flags: stddev, isNew, isMoving, isHopping, isTransient.
     */
    public void compute(boolean armed, Set<String> baseline) {
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
