package com.yep.kindle.dron.web;

import com.yep.kindle.dron.event.AppEvent;
import com.yep.kindle.dron.event.EventBus;

/**
 * Thread-safe shared state between the detector main loop and the web server.
 * Battery and temperature are NOT stored here — they are read fresh from hardware
 * by SensorReader at the time each web request is served.
 *
 * <p><b>Event-driven integration:</b> mutating methods that previously relied on
 * sequence-number polling now additionally post events to {@link EventBus#INSTANCE}
 * so the main detector loop wakes up immediately instead of waiting for the next
 * 5-second tick:</p>
 * <ul>
 *   <li>{@link #setMessage} → posts {@link AppEvent.Type#OVERLAY_MESSAGE}</li>
 *   <li>{@link #requestNextPage} → posts {@link AppEvent.Type#PAGE_ADVANCE}</li>
 * </ul>
 * <p>The sequence-number fields are kept for backward-compatibility with any
 * callers that have not yet been migrated to the event bus.</p>
 */
public class DeviceState {

    private volatile String lastMessage      = "";
    private volatile long   lastMessageTime  = System.currentTimeMillis();
    private volatile long   messageSeq       = 0;
    private volatile String statusMessage    = "Ready";
    private volatile long   nextPageSeq      = 0;
    private volatile long   nextPageAckedSeq = 0;
    private volatile String currentPage      = "weather";

    private AppDataManager dataManager = null;
    private volatile AppConfig config = new AppConfig();

    // ── Messages (trigger overlay on Kindle screen) ───────────────────────────

    public synchronized String getLastMessage()    { return lastMessage; }
    public synchronized long   getLastMessageTime(){ return lastMessageTime; }
    public synchronized long   getMessageSeq()     { return messageSeq; }

    public synchronized void setMessage(String message) {
        if (message != null && !message.trim().isEmpty()) {
            lastMessage     = message.trim();
            lastMessageTime = System.currentTimeMillis();
            messageSeq++;
            // Wake up the main loop immediately — no need to wait for the next tick.
            EventBus.INSTANCE.post(AppEvent.overlayMessage(lastMessage, "web"));
        }
    }

    // ── Status line (web dashboard only, no screen overlay) ──────────────────

    public synchronized String getStatusMessage() { return statusMessage; }

    public synchronized void setStatusMessage(String status) {
        if (status != null && !status.trim().isEmpty()) {
            statusMessage = status.trim();
        }
    }

    // ── Page navigation ───────────────────────────────────────────────────────

    public synchronized long getNextPageSeq() { return nextPageSeq; }

    public synchronized long requestNextPage() {
        nextPageSeq++;
        // Wake up the main loop immediately.
        EventBus.INSTANCE.post(AppEvent.pageAdvance("web"));
        return nextPageSeq;
    }

    public synchronized boolean hasPendingPageRequest() {
        return nextPageAckedSeq < nextPageSeq;
    }

    public synchronized void acknowledgePageRequest() {
        nextPageAckedSeq = nextPageSeq;
    }

    public String getCurrentPage() { return currentPage; }
    public void setCurrentPage(String page) { this.currentPage = page; }

    // ── AppDataManager reference ──────────────────────────────────────────────

    public AppDataManager getDataManager() { return dataManager; }
    public void setDataManager(AppDataManager manager) { this.dataManager = manager; }

    // ── App config (city etc.) ────────────────────────────────────────────────

    AppConfig getConfig() { return config; }
    void setConfig(AppConfig cfg) { this.config = cfg; }
    public String getConfigCity() { return config.city; }

    /** Loads config from the data manager's config file; safe to call if manager is null. */
    public void loadConfig() {
        if (dataManager != null) config = dataManager.loadConfig();
    }
}
