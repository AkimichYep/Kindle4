package com.yep.kindle.dron.event;

/**
 * Typed application events that flow through {@link EventBus}.
 *
 * <p>All events are immutable value objects. Producers post events; the
 * main detector loop consumes them via {@code EventBus.poll()}, eliminating
 * the tight 5-second busy-wait loop that previously polled sequence numbers
 * on every tick.</p>
 *
 * <h3>Event taxonomy</h3>
 * <pre>
 *  PAGE_ADVANCE      — user triggered "next page" (web UI or physical button)
 *  OVERLAY_MESSAGE   — new overlay text to render on e-ink
 *  OVERLAY_CLEAR     — dismiss active overlay (timeout or explicit clear)
 *  WIFI_SCAN_DUE     — scheduled Wi-Fi scan tick fired by ScanScheduler
 *  WEATHER_REFRESH   — scheduled weather data refresh tick
 *  IDLE_CRC_RESULT   — background CRC measurement completed (non-blocking)
 *  DRONE_ALERT       — threat score crossed the alarm threshold
 *  SAVE_DUE          — periodic CSV + state persistence tick
 *  MAXPERF_DUE       — periodic wmiconfig maxperf re-apply tick
 *  PBANK_CHECK_DUE   — periodic power-bank keepalive check tick
 * </pre>
 */
public final class AppEvent {

    /** Discriminates the event without requiring instanceof checks. */
    public enum Type {
        PAGE_ADVANCE,
        OVERLAY_MESSAGE,
        OVERLAY_CLEAR,
        WIFI_SCAN_DUE,
        WEATHER_REFRESH,
        IDLE_CRC_RESULT,
        DRONE_ALERT,
        SAVE_DUE,
        MAXPERF_DUE,
        PBANK_CHECK_DUE,
        ROTATION_TOGGLE
    }

    // ── Fields ────────────────────────────────────────────────────────────────

    /** Discriminator — never null. */
    public final Type   type;

    /**
     * Optional text payload:
     * <ul>
     *   <li>{@code OVERLAY_MESSAGE} — the message string to display.</li>
     *   <li>{@code DRONE_ALERT}     — human-readable alert summary.</li>
     *   <li>All other types        — {@code null}.</li>
     * </ul>
     */
    public final String payload;

    /**
     * Optional numeric payload:
     * <ul>
     *   <li>{@code IDLE_CRC_RESULT} — measured idle CRC count.</li>
     *   <li>{@code DRONE_ALERT}     — peak threat score (0–100).</li>
     *   <li>All other types        — {@code 0}.</li>
     * </ul>
     */
    public final long   value;

    /** Source that produced this event (for logging). */
    public final String source;

    /** Wall-clock creation time in epoch milliseconds. */
    public final long   createdAt;

    // ── Constructors ──────────────────────────────────────────────────────────

    private AppEvent(Type type, String payload, long value, String source) {
        this.type      = type;
        this.payload   = payload;
        this.value     = value;
        this.source    = source;
        this.createdAt = System.currentTimeMillis();
    }

    // ── Static factories ──────────────────────────────────────────────────────

    /** "Next page" requested from the web UI. */
    public static AppEvent pageAdvance(String source) {
        return new AppEvent(Type.PAGE_ADVANCE, null, 0, source);
    }

    /** New overlay message to render on-screen. */
    public static AppEvent overlayMessage(String message, String source) {
        return new AppEvent(Type.OVERLAY_MESSAGE, message, 0, source);
    }

    /** Dismiss the active overlay (timeout or explicit clear). */
    public static AppEvent overlayClear(String source) {
        return new AppEvent(Type.OVERLAY_CLEAR, null, 0, source);
    }

    /** Wi-Fi scan tick produced by {@link com.yep.kindle.dron.event.ScanScheduler}. */
    public static AppEvent wifiScanDue() {
        return new AppEvent(Type.WIFI_SCAN_DUE, null, 0, "ScanScheduler");
    }

    /** Weather data refresh tick. */
    public static AppEvent weatherRefresh() {
        return new AppEvent(Type.WEATHER_REFRESH, null, 0, "ScanScheduler");
    }

    /**
     * Background idle-CRC measurement completed.
     *
     * @param crcCount raw CRC error count observed during the 2-second window
     */
    public static AppEvent idleCrcResult(long crcCount) {
        return new AppEvent(Type.IDLE_CRC_RESULT, null, crcCount, "CrcWorker");
    }

    /**
     * Drone threat confirmed.
     *
     * @param score   peak threat score
     * @param summary human-readable summary for the log
     */
    public static AppEvent droneAlert(int score, String summary) {
        return new AppEvent(Type.DRONE_ALERT, summary, score, "ThreatScorer");
    }

    /** Periodic CSV / device-state persistence tick. */
    public static AppEvent saveDue() {
        return new AppEvent(Type.SAVE_DUE, null, 0, "ScanScheduler");
    }

    /** Periodic {@code wmiconfig --power maxperf} re-apply tick. */
    public static AppEvent maxperfDue() {
        return new AppEvent(Type.MAXPERF_DUE, null, 0, "ScanScheduler");
    }

    /** Periodic power-bank keepalive check tick. */
    public static AppEvent pbankCheckDue() {
        return new AppEvent(Type.PBANK_CHECK_DUE, null, 0, "ScanScheduler");
    }

    /** Display rotation enabled/disabled from web UI. value=1 means enabled. */
    public static AppEvent rotationToggle(boolean enabled, String source) {
        return new AppEvent(Type.ROTATION_TOGGLE, null, enabled ? 1 : 0, source);
    }

    // ── Object ────────────────────────────────────────────────────────────────

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("AppEvent{type=").append(type);
        if (source  != null) sb.append(", src=").append(source);
        if (payload != null) sb.append(", payload='").append(payload).append('\'');
        if (value   != 0)    sb.append(", value=").append(value);
        return sb.append('}').toString();
    }
}
