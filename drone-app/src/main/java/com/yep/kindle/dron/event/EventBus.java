package com.yep.kindle.dron.event;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import com.yep.kindle.dron.util.AppLog;

/**
 * Minimal single-consumer event bus backed by a {@link LinkedBlockingQueue}.
 *
 * <h3>Design contract</h3>
 * <ul>
 *   <li><b>Multiple producers</b> — any thread may call {@link #post(AppEvent)}
 *       without external synchronisation; {@code LinkedBlockingQueue} is
 *       thread-safe.</li>
 *   <li><b>Single consumer</b> — only the main detector thread calls
 *       {@link #poll(long, TimeUnit)}.  No locking is required on the read
 *       side.</li>
 *   <li><b>Bounded capacity (256)</b> — prevents unbounded memory growth on a
 *       128 MB Kindle 4.  If the queue is full, {@link #post} silently drops
 *       the event and logs a warning.  In practice the consumer always drains
 *       faster than any realistic burst rate.</li>
 * </ul>
 *
 * <h3>Replaces</h3>
 * <ul>
 *   <li>The 5-second {@code while(true) + KindleUtils.sleep()} busy-wait in
 *       {@code KindleDroneDetectorPro}.</li>
 *   <li>Sequence-number polling for page-advance and overlay-message signals
 *       from {@code DeviceState} and {@code KindleTcpListener}.</li>
 * </ul>
 *
 * <h3>Usage pattern</h3>
 * <pre>{@code
 * // Producer (any thread):
 * EventBus.INSTANCE.post(AppEvent.pageAdvance("web"));
 *
 * // Consumer (main detector thread):
 * AppEvent ev = EventBus.INSTANCE.poll(TICK_MS, TimeUnit.MILLISECONDS);
 * if (ev != null) { handleEvent(ev); }
 * }</pre>
 */
public final class EventBus {

    /** Global singleton — the whole application shares one bus. */
    public static final EventBus INSTANCE = new EventBus(256);

    private final LinkedBlockingQueue<AppEvent> queue;

    /** Creates a bus with the given maximum capacity. */
    public EventBus(int capacity) {
        this.queue = new LinkedBlockingQueue<>(capacity);
    }

    // ── Producers ─────────────────────────────────────────────────────────────

    /**
     * Post an event.  Never blocks; if the queue is full the event is dropped
     * and {@code false} is returned (capacity overflow should never happen in
     * normal operation).
     *
     * @param event the event to enqueue; must not be {@code null}
     * @return {@code true} if the event was accepted, {@code false} if dropped
     */
    public boolean post(AppEvent event) {
        if (event == null) throw new NullPointerException("event");
        boolean accepted = queue.offer(event);
        if (!accepted) {
            AppLog.warn("Event queue full; dropped " + event.type
                    + " event (capacity=" + queue.size() + ")");
        }
        return accepted;
    }

    // ── Consumer ──────────────────────────────────────────────────────────────

    /**
     * Retrieve the next event, waiting up to {@code timeout} time units.
     *
     * <p>This is the <em>only</em> method the main loop needs.  When an event
     * arrives before the timeout expires the method returns immediately,
     * allowing the loop to react without waiting for the next 5-second tick.</p>
     *
     * @param timeout how long to wait
     * @param unit    time unit for {@code timeout}
     * @return the next event, or {@code null} if the timeout elapsed
     */
    public AppEvent poll(long timeout, TimeUnit unit) {
        try {
            return queue.poll(timeout, unit);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /**
     * Drain all events currently in the queue without waiting.
     * Useful for flushing stale events after a long blocking operation
     * (e.g., after the 30-second radar hold).
     */
    public void drainAll() {
        queue.clear();
    }

    /** Returns the number of events currently waiting to be consumed. */
    public int size() {
        return queue.size();
    }
}
