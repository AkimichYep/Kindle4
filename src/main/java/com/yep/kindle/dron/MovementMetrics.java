package com.yep.kindle.dron;

import java.util.Deque;

/**
 * Movement-related helper methods extracted from KindleDroneDetectorPro.
 */
final class MovementMetrics {
    private MovementMetrics() {}

    static int dynamicDistThresholdM(double distM,
                                     int nearM,
                                     int midM,
                                     int farM,
                                     int veryFarM) {
        if (distM <= 60)  return nearM;
        if (distM <= 140) return midM;
        if (distM <= 240) return farM;
        return veryFarM;
    }

    static String formatDistDeltaTag(int deltaM) {
        if (Math.abs(deltaM) < 2) return "  =0";
        if (deltaM > 0) return String.format(" +%d", Math.min(deltaM, 99));
        return String.format(" -%d", Math.min(Math.abs(deltaM), 99));
    }

    static String sparkline(Deque<Integer> hist) {
        if (hist == null || hist.isEmpty()) return "";
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for (int d : hist) {
            if (d < min) min = d;
            if (d > max) max = d;
        }
        int span = Math.max(1, max - min);
        StringBuilder sb = new StringBuilder();
        int kept = 0;
        int skip = Math.max(1, hist.size() / 12);
        int idx = 0;
        for (int d : hist) {
            if (idx % skip != 0) {
                idx++;
                continue;
            }
            int lvl = (d - min) * 3 / span;
            char c = (lvl <= 0) ? '.' : (lvl == 1 ? 'o' : (lvl == 2 ? 'O' : '#'));
            sb.append(c);
            kept++;
            idx++;
            if (kept >= 12) break;
        }
        return sb.toString();
    }

    static String trendArrow(Deque<Integer> hist) {
        if (hist == null || hist.size() < 2) return "";
        int first = hist.peekFirst();
        int last = hist.peekLast();
        int d = last - first;
        if (Math.abs(d) < 3) return "=";
        return d > 0 ? ">" : "<";
    }
}

