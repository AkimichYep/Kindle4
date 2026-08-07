package com.yep.kindle.dron.event;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Replaces the monolithic {@code while(true) + KindleUtils.sleep()} loop in
 * {@code KindleDroneDetectorPro} with a proper {@link ScheduledExecutorService}.
 *
 * <h3>What changed — before vs after</h3>
 * <table border="1" cellpadding="4">
 * <tr><th>Before</th><th>After</th></tr>
 * <tr><td>Main thread loops every 5 s polling modulo counters</td>
 *     <td>Main thread blocks on {@link EventBus#poll} with a 5-second timeout</td></tr>
 * <tr><td>Each scheduled activity checks {@code ctx.loop % N == 0}</td>
 *     <td>Each activity is fired by a dedicated ScheduledFuture; CPU wakes up only when needed</td></tr>
 * <tr><td>measureIdleCRC() blocks the main thread for 2 s every 50 s</td>
 *     <td>CRC measurement runs in a background daemon thread; result is posted as {@link AppEvent#idleCrcResult}</td></tr>
 * <tr><td>Radar hold: {@code KindleUtils.sleep(30_000)} on the main thread</td>
 *     <td>Radar page is shown; a one-shot schedule posts {@link AppEvent.Type#WIFI_SCAN_DUE} 30 s later to resume the loop</td></tr>
 * </table>
 *
 * <h3>Timing parameters (match original constants in KindleDroneDetectorPro)</h3>
 * <pre>
 *  WIFI_SCAN_INTERVAL_S  = 180   (was SCAN_EVERY=36 ticks × 5 s)
 *  STATS_INTERVAL_S      =  25   (was STATS_EVERY=5 × 5 s)
 *  IDLE_CRC_INTERVAL_S   =  50   (was IDLE_CRC_EVERY=10 × 5 s)
 *  MAXPERF_INTERVAL_S    = 180   (was MAXPERF_EVERY=36 × 5 s)
 *  SAVE_INTERVAL_S       = 180   (was SAVE_EVERY=36 × 5 s)
 *  WEATHER_INTERVAL_S    = 300   (was WEATHER_EVERY_MS=300_000)
 *  PBANK_INTERVAL_S      = 6000  (was PBANK_CHECK_EVERY=1200 × 5 s)
 * </pre>
 */
public final class ScanScheduler {

    // ── Intervals (seconds) ───────────────────────────────────────────────────
    public static final int WIFI_SCAN_INTERVAL_S = 180;
    public static final int STATS_INTERVAL_S     =  25;
    public static final int IDLE_CRC_INTERVAL_S  =  50;
    public static final int MAXPERF_INTERVAL_S   = 180;
    public static final int SAVE_INTERVAL_S      = 180;
    public static final int WEATHER_INTERVAL_S   = 300;
    public static final int PBANK_INTERVAL_S     = 6000;

    private final EventBus bus;
    private final ScheduledExecutorService scheduler;
    private final ExecutorService crcWorker;   // dedicated thread for blocking CRC measurement

    private volatile boolean running = false;

    /**
     * Creates a scheduler that posts events to the supplied bus.
     *
     * @param bus the application event bus (typically {@link EventBus#INSTANCE})
     */
    public ScanScheduler(EventBus bus) {
        this.bus = bus;
        // 1 daemon scheduler thread — lightweight on 128 MB Kindle 4
        this.scheduler = Executors.newSingleThreadScheduledExecutor(
                namedDaemon("scan-scheduler"));
        // Separate thread for the 2-second blocking CRC measurement so it
        // never holds up the scheduler's single thread.
        this.crcWorker = Executors.newSingleThreadExecutor(
                namedDaemon("crc-worker"));
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /**
     * Start all periodic tasks.  Safe to call only once; subsequent calls are no-ops.
     *
     * @param crcMeasureFn  callback that performs the 2-second idle CRC measurement
     *                      and returns the raw CRC count; executed in a background thread
     */
    public synchronized void start(CrcMeasureCallback crcMeasureFn) {
        if (running) return;
        running = true;

        // Wi-Fi scan — fire immediately on first tick then every 3 minutes
        schedule(WIFI_SCAN_INTERVAL_S,  WIFI_SCAN_INTERVAL_S,  AppEvent.wifiScanDue());

        // Firmware stats (noise floor, SNR) — every 25 s
        schedule(STATS_INTERVAL_S,      STATS_INTERVAL_S,      null /* stats event not needed; main loop reads inline after WIFI_SCAN_DUE */);

        // Weather data refresh — every 5 minutes
        schedule(WEATHER_INTERVAL_S,    WEATHER_INTERVAL_S,    AppEvent.weatherRefresh());

        // maxperf re-apply — every 3 minutes (same cadence as scan)
        schedule(MAXPERF_INTERVAL_S,    MAXPERF_INTERVAL_S,    AppEvent.maxperfDue());

        // CSV / device-state save — every 3 minutes
        schedule(SAVE_INTERVAL_S,       SAVE_INTERVAL_S,       AppEvent.saveDue());

        // Power-bank keepalive check — every 100 minutes
        schedule(PBANK_INTERVAL_S,      PBANK_INTERVAL_S,      AppEvent.pbankCheckDue());

        // Idle CRC measurement — every 50 s, runs in background thread
        scheduler.scheduleAtFixedRate(() -> {
            crcWorker.submit(() -> {
                long count = crcMeasureFn.measure();
                bus.post(AppEvent.idleCrcResult(count));
            });
        }, IDLE_CRC_INTERVAL_S, IDLE_CRC_INTERVAL_S, TimeUnit.SECONDS);
    }

    /**
     * Schedule a one-shot event to be posted after {@code delayMs} milliseconds.
     * Used by the main loop to resume after the radar hold without blocking.
     *
     * @param delayMs delay in milliseconds
     * @param event   event to post when the delay expires
     * @return a handle that can be cancelled (ignored if not needed)
     */
    public ScheduledFuture<?> postDelayed(long delayMs, AppEvent event) {
        return scheduler.schedule(() -> bus.post(event),
                delayMs, TimeUnit.MILLISECONDS);
    }

    /** Stop all scheduled tasks and shut down the executor threads gracefully. */
    public synchronized void stop() {
        if (!running) return;
        running = false;
        scheduler.shutdownNow();
        crcWorker.shutdownNow();
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    /** Schedule a fixed-rate task that posts {@code event} each period.
     *  Passing {@code null} as event skips the post (useful for pure side-effect tasks). */
    private void schedule(long initialDelay, long period, AppEvent event) {
        if (event == null) return; // no-op slot reserved for future use
        scheduler.scheduleAtFixedRate(
                () -> bus.post(event),
                initialDelay, period, TimeUnit.SECONDS);
    }

    // ── Thread factory ────────────────────────────────────────────────────────

    private static ThreadFactory namedDaemon(String name) {
        return new ThreadFactory() {
            private final AtomicInteger idx = new AtomicInteger(0);
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, name + "-" + idx.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        };
    }

    // ── Callback interface ────────────────────────────────────────────────────

    /**
     * Functional interface for the blocking idle-CRC measurement.
     * Implemented by {@code WifiScanner.measureIdleCRC()} in the main module.
     */
    @FunctionalInterface
    public interface CrcMeasureCallback {
        /**
         * Perform the 2-second blocking idle CRC window.
         *
         * @return raw CRC error count observed in the measurement window
         */
        long measure();
    }
}
