package com.yep.kindle.dron.detection;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.yep.kindle.dron.model.AP;
import com.yep.kindle.dron.model.History;

/**
 * TemporalEngine — tracks AP appearance/disappearance across scan cycles.
 *
 * Maintains a per-MAC {@link History} ring-buffer, detects re-appearances (gaps),
 * computes movement flags, and prunes stale entries after TTL expiry.
 */
public class TemporalEngine {

    /** How long to keep a MAC in the tracker after its last observation. */
    private static final long TRACKER_TTL_MS = 180_000L; // 3 min

    private final Map<String, History> tracker = new HashMap<>();

    /**
     * Update the tracker with a fresh scan result.
     * Marks which MACs were seen in the previous cycle vs this one,
     * updates ring buffers, increments gap counters, and computes flags.
     *
     * @param aps     list of APs from the current scan
     * @param armed   arming state (used by History.compute)
     * @param baseline set of baseline MACs (used by History.compute)
     */
    public void update(List<AP> aps, boolean armed, Set<String> baseline) {
        // Reset seenNow for all tracked MACs from last cycle
        for (History h : tracker.values()) {
            h.seenPrev = h.seenNow;
            h.seenNow  = false;
        }

        // Update ring buffers for all currently visible APs
        for (AP a : aps) {
            if (a.mac == null || a.mac.isEmpty()) continue;
            History h = tracker.get(a.mac);
            if (h == null) {
                h = new History(a.mac);
                tracker.put(a.mac, h);
            }
            h.add(a.signalDbm, a.channel, a.dist);
            h.ssid    = a.ssid;
            h.seenNow = true;
        }

        // Compute flags and prune stale entries
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, History>> it = tracker.entrySet().iterator();
        while (it.hasNext()) {
            History h = it.next().getValue();
            // Re-appearance after a gap
            if (h.seenNow && !h.seenPrev && h.count > 3) h.gaps++;
            h.compute(armed, baseline);
            if (now - h.lastSeen > TRACKER_TTL_MS) it.remove();
        }
    }

    /**
     * Retrieve the history record for a given MAC, or {@code null} if not tracked.
     */
    public History get(String mac) {
        return tracker.get(mac);
    }

    /**
     * Returns the underlying tracker map (read-only usage intended).
     */
    public Map<String, History> all() {
        return tracker;
    }
}
