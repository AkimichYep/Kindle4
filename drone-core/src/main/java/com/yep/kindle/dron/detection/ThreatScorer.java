package com.yep.kindle.dron.detection;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.yep.kindle.dron.model.AP;
import com.yep.kindle.dron.model.History;
import com.yep.kindle.dron.model.NetRecord;

/**
 * ThreatScorer — scores each AP for drone likelihood and filters APs for radar display.
 *
 * Scoring is multi-factor (additive, capped at 100):
 *   OUI match (+50), SSID keyword (+40), hidden+close (+15), new+armed (+25),
 *   moving (+20), channel-hopping (+20), transient (+15), non-Master mode (+20),
 *   strong signal (+20), randomised MAC bit (+10), RSSI surge (+20),
 *   consecutive approach streak (+20).
 */
public class ThreatScorer {

    // Distance-change thresholds by band (metres)
    private final int nearM, midM, farM, vfarM;
    private final TemporalEngine temporal;

    public ThreatScorer(TemporalEngine temporal,
                        int nearM, int midM, int farM, int vfarM) {
        this.temporal = temporal;
        this.nearM = nearM;
        this.midM  = midM;
        this.farM  = farM;
        this.vfarM = vfarM;
    }

    /**
     * Score every AP in the list.
     * Results are written back into {@code a.threat} and {@code a.flags}.
     * Also updates {@code nr.lastFlags} for persistence.
     *
     * @param aps      live AP list (sorted later by caller)
     * @param armed    arming state
     * @param baseline set of known-baseline MACs
     * @param knownNets persistent records map
     */
    public void score(List<AP> aps, boolean armed, Set<String> baseline,
                      Map<String, NetRecord> knownNets) {
        for (AP a : aps) {
            int s = 0;
            StringBuilder f = new StringBuilder();

            String v = DroneSignatures.lookupOUI(a.mac);
            if (v != null) { s += 50; f.append(v).append(' '); }

            if (DroneSignatures.matchesKeyword(a.ssid)) { s += 40; f.append("SSID "); }

            if (a.hidden && a.dist < 80) { s += 15; f.append("HID "); }

            History h = temporal.get(a.mac);
            if (h != null) {
                if (h.isNew && armed && !baseline.contains(a.mac) && a.signalDbm > -80)
                    { s += 25; f.append("NEW "); }
                if (h.isMoving)    { s += 20; f.append("MOV "); }
                if (h.isHopping)   { s += 20; f.append("HOP "); }
                if (h.isTransient) { s += 15; f.append("TRN "); }
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

            // Fast-approach signals (non-baseline only)
            NetRecord nr = knownNets.get(a.mac);
            if (nr != null && !baseline.contains(a.mac)) {
                if (nr.surgeDetected)            { s += 20; f.append("SRG "); }
                if (nr.consecutiveApproach >= 3) { s += 20; f.append("APR "); }
            }

            a.threat = Math.min(s, 100);
            a.flags  = f.toString().trim();
            if (nr != null) nr.lastFlags = a.flags;
        }
    }

    /**
     * Build the filtered AP list for radar rendering.
     * Includes only: NEW APs, APs whose distance changed meaningfully, and
     * APs that disappeared and have now returned.
     * Excludes static/unchanged APs, ghost placeholders, and threat-only APs without events.
     *
     * @param liveAps live AP list (already scored)
     * @param armed   arming state
     * @param baseline set of baseline MACs
     * @param logger  callback for event log lines (may be null)
     */
    public List<AP> buildRadarAps(List<AP> liveAps, boolean armed, Set<String> baseline,
                                  java.util.function.Consumer<String> logger) {
        List<AP> result = new ArrayList<>();
        for (AP a : liveAps) {
            if (baseline.contains(a.mac) && a.threat == 0) continue;

            History h = temporal.get(a.mac);
            boolean isNew     = armed && !baseline.contains(a.mac) && h != null && h.isNew;
            int     distThr   = dynamicDistThresholdM(a.dist);
            boolean distChanged = Math.abs(a.distDeltaM) >= distThr;
            boolean returned  = h != null && h.seenNow && !h.seenPrev && h.count > 3;

            if (isNew || distChanged || returned) {
                if (logger != null) {
                    String reason;
                    if (isNew) {
                        long ageS = (h != null)
                                ? (System.currentTimeMillis() - h.firstSeen) / 1000L : -1;
                        reason = "NEW " + ageS + "s";
                    } else if (returned) {
                        reason = "RET online-again";
                    } else {
                        reason = "DIST d=" + a.distDeltaM + "m thr=" + distThr;
                    }
                    logger.accept(String.format("RADAR-INC %s %-14s thr=%-3d dist=%.0fm [%s]",
                            a.mac, a.ssid, a.threat, a.dist, reason));
                }
                result.add(a);
            }
        }
        return result;
    }

    /**
     * Returns true if any AP in the list represents an event worth showing on radar:
     * new AP, meaningful distance change, or AP returned from offline.
     */
    public boolean hasInterestingActivity(List<AP> aps, boolean armed, Set<String> baseline) {
        for (AP a : aps) {
            if (a.ghost) continue;
            if (baseline.contains(a.mac) && a.threat == 0) continue;
            History h = temporal.get(a.mac);
            boolean isNew      = !baseline.contains(a.mac) && armed && h != null && h.isNew;
            boolean distChanged = Math.abs(a.distDeltaM) >= dynamicDistThresholdM(a.dist);
            boolean returned   = h != null && h.seenNow && !h.seenPrev && h.count > 3;
            if (isNew || distChanged || returned) return true;
        }
        return false;
    }

    /** Distance-change threshold by distance band (metres). */
    public int dynamicDistThresholdM(double distM) {
        return MovementMetrics.dynamicDistThresholdM(distM, nearM, midM, farM, vfarM);
    }
}
