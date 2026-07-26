package com.yep.kindle.dron.model;

import java.util.Map;
import java.util.Set;

import com.yep.kindle.dron.detection.TemporalEngine;
import com.yep.kindle.dron.detection.ThreatScorer;
import com.yep.kindle.dron.service.WeatherService;

/**
 * DetectorContext — carries all shared mutable state of KindleDroneDetectorPro
 * to the display-layer classes (ScreenBuilder, DisplayManager) without making
 * them depend on static fields in the main class.
 *
 * All fields are public for direct read/write by the main loop; this is
 * intentional — it replaces a large set of scattered statics with a single,
 * explicitly-passed object.
 */
public class DetectorContext {

    // ── Timing constants (read-only, set at construction) ─────────────────────
    public final int  baselineLoops;
    public final int  idleCrcThreshold;
    public final int  idleCrcConfirm;
    public final String weatherLocation;

    // ── Sub-components ────────────────────────────────────────────────────────
    public final TemporalEngine temporal;
    public final ThreatScorer   scorer;

    // ── Loop state ────────────────────────────────────────────────────────────
    public int     loop         = 0;
    public long    startTime    = System.currentTimeMillis();
    public boolean armed        = false;

    // ── RF metrics ────────────────────────────────────────────────────────────
    public int     noiseFloor   = -96;
    public int     csSnr        = 0;
    public int     linkQuality  = 0;
    public long    crcDelta     = 0;
    public long    idleCRC      = 0;
    public boolean externalRF   = false;
    public int     externalRFcount = 0;

    // ── Statistics counters ───────────────────────────────────────────────────
    public int  statTotalScans   = 0;
    public int  statPeakThreat   = 0;
    public int  statAlertEvents  = 0;
    public int  statNewArmed     = 0;

    // ── Network maps ─────────────────────────────────────────────────────────
    public final Map<String, NetRecord> knownNets;
    public final Set<String>            baseline;

    // ── Weather ───────────────────────────────────────────────────────────────
    public WeatherService.WeatherData weather = WeatherService.unavailable("boot");

    public DetectorContext(int baselineLoops, int idleCrcThreshold, int idleCrcConfirm,
                           String weatherLocation,
                           TemporalEngine temporal, ThreatScorer scorer,
                           Map<String, NetRecord> knownNets, Set<String> baseline) {
        this.baselineLoops    = baselineLoops;
        this.idleCrcThreshold = idleCrcThreshold;
        this.idleCrcConfirm   = idleCrcConfirm;
        this.weatherLocation  = weatherLocation;
        this.temporal         = temporal;
        this.scorer           = scorer;
        this.knownNets        = knownNets;
        this.baseline         = baseline;
    }
}
