package com.yep.kindle.dron.web;

import com.yep.kindle.dron.util.AppLog;
import com.yep.kindle.dron.util.KindleUtils;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Passive RF monitor using the AR6003 PHY-layer CRC error counter.
 *
 * <p>Runs {@code wmiconfig -i wlan0 --getTargetStats --clearStats} every 2 seconds.
 * The first call after start() is discarded — it returns the cumulative total since
 * boot or the last clear.  All subsequent calls return only the CRC errors that
 * accumulated in the 2-second window since the previous clear.
 *
 * <p><b>Baseline:</b> rolling mean of the last 10 valid samples.
 * <b>Alert threshold:</b> current delta > max(baseline × 4, baseline + 50).
 * A drone control link or strong foreign 802.11 transmitter on the home channel
 * would spike rx_crcerr well above the normal neighbourhood-traffic baseline
 * (~10–20 per 2-second window in a typical residential environment).
 *
 * <p><b>Interference note:</b> while {@code iwlist wlan0 scan} is running,
 * the AR6003 hops off the home channel and the CRC count for that window will
 * be artificially suppressed.  Running both monitors simultaneously reduces
 * sensitivity during scan windows.
 */
public class ChipStatsMonitor {

    private static final int      POLL_INTERVAL_MS = 2_000;
    private static final int      QUEUE_CAPACITY   = 256;
    private static final int      WINDOW_SIZE      = 10;
    private static final String[] CMD = {
        "wmiconfig", "-i", "wlan0", "--getTargetStats", "--clearStats"
    };

    private final AtomicBoolean running   = new AtomicBoolean(false);
    private final AtomicInteger pollCount = new AtomicInteger(0);
    private final BlockingQueue<String> queue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
    private volatile Thread pollThread;

    private final int[] window     = new int[WINDOW_SIZE];
    private int          windowPos  = 0;
    private int          windowFill = 0;

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    public synchronized boolean start() {
        if (running.get()) return false;
        running.set(true);
        pollCount.set(0);
        windowPos  = 0;
        windowFill = 0;
        queue.clear();
        pollThread = new Thread(this::runLoop, "rf-monitor");
        pollThread.setDaemon(true);
        pollThread.start();
        AppLog.info("[RF] start");
        return true;
    }

    public synchronized boolean stop() {
        if (!running.get()) return false;
        running.set(false);
        Thread t = pollThread;
        if (t != null) t.interrupt();
        push(statusEvent("RF monitor stopped after " + pollCount.get() + " polls"));
        AppLog.info("[RF] stop polls=" + pollCount.get());
        return true;
    }

    public boolean isRunning() { return running.get(); }

    public String pollLine(long timeoutMs) throws InterruptedException {
        return queue.poll(timeoutMs, TimeUnit.MILLISECONDS);
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private void push(String json) {
        if (!queue.offer(json)) {
            queue.poll();
            queue.offer(json);
        }
    }

    private void runLoop() {
        push(statusEvent("RF monitor started — discarding first sample (cumulative)"));
        boolean first = true;
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            long t0 = System.currentTimeMillis();
            try {
                runPoll(first);
                if (first) first = false;
                else       pollCount.incrementAndGet();
            } catch (Exception e) {
                push(logEvent("ERR", "RF poll: " + escape(e.getMessage())));
                AppLog.err("[RF] " + e.getMessage());
            }
            long sleep = POLL_INTERVAL_MS - (System.currentTimeMillis() - t0);
            if (sleep > 0) {
                try { Thread.sleep(sleep); } catch (InterruptedException e) { break; }
            }
        }
        running.set(false);
    }

    private void runPoll(boolean discard) throws Exception {
        String out = KindleUtils.readCommand(CMD);
        if (out == null || out.trim().isEmpty()) {
            throw new Exception("wmiconfig returned no stats output");
        }

        // First call: cumulative since boot — discard, used only to reset the counter.
        if (discard) return;

        int crc    = parseField(out, "rx_crcerr");
        int rssi   = parseField(out, "cs_rssi");
        int snr    = parseField(out, "cs_snr");
        int noise  = parseField(out, "noise_floor_calibation");
        int txfail = parseField(out, "tx_failed_cnt");

        int baseline = computeBaseline();

        // Update window AFTER reading baseline so this sample doesn't skew its own threshold.
        window[windowPos] = crc;
        windowPos = (windowPos + 1) % WINDOW_SIZE;
        if (windowFill < WINDOW_SIZE) windowFill++;

        boolean alert = baseline > 0 && crc > Math.max(baseline * 4, baseline + 50);

        StringBuilder ev = new StringBuilder();
        ev.append("{\"type\":\"rf\"");
        ev.append(",\"crc\":").append(crc);
        ev.append(",\"rssi\":").append(rssi);
        ev.append(",\"snr\":").append(snr);
        ev.append(",\"noise\":").append(noise);
        ev.append(",\"txfail\":").append(txfail);
        ev.append(",\"baseline\":").append(baseline);
        ev.append(",\"alert\":").append(alert);
        ev.append('}');
        push(ev.toString());

        if (alert) {
            int mult = baseline > 0 ? (crc / baseline) : 0;
            push(logEvent("ALERT",
                "RF spike: crc=" + crc + " baseline=" + baseline + " (" + mult + "x)"));
            AppLog.info("[RF] ALERT crc=" + crc + " base=" + baseline + " " + mult + "x");
        }
    }

    private int computeBaseline() {
        if (windowFill == 0) return 0;
        int sum = 0;
        for (int i = 0; i < windowFill; i++) {
            sum += window[(windowPos - 1 - i + WINDOW_SIZE) % WINDOW_SIZE];
        }
        return sum / windowFill;
    }

    /** Parses "  fieldName = 42" lines from wmiconfig output. */
    private static int parseField(String text, String field) {
        int idx = text.indexOf(field);
        if (idx < 0) return 0;
        int eq = text.indexOf('=', idx);
        if (eq < 0) return 0;
        int nl = text.indexOf('\n', eq);
        String val = (nl > eq ? text.substring(eq + 1, nl) : text.substring(eq + 1)).trim();
        try { return Integer.parseInt(val); } catch (NumberFormatException e) { return 0; }
    }

    // ── JSON helpers ──────────────────────────────────────────────────────────

    private static String statusEvent(String msg) {
        return "{\"type\":\"rf-status\",\"msg\":\"" + escape(msg) + "\"}";
    }

    private static String logEvent(String level, String msg) {
        return "{\"type\":\"rf-log\",\"level\":\"" + level + "\",\"msg\":\"" + escape(msg) + "\"}";
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "");
    }
}
