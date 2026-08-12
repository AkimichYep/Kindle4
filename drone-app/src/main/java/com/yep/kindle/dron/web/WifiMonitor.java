package com.yep.kindle.dron.web;

import com.yep.kindle.dron.util.AppLog;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Aggressive WiFi monitor: runs {@code iwlist wlan0 scan} every 2 seconds and
 * pushes parsed JSON lines into a bounded queue.  SSE stream handlers drain the
 * queue and forward events to connected browsers.
 *
 * <p>Design constraints for Kindle 4 (ARM, 256 MB RAM, BusyBox):
 * <ul>
 *   <li>Single daemon thread — no thread pool overhead.</li>
 *   <li>Bounded queue (512 entries) — drops oldest entry on overflow.</li>
 *   <li>Pure {@code iwlist} parsing — no external tools required.</li>
 * </ul>
 */
public class WifiMonitor {

    private static final int    SCAN_INTERVAL_MS = 2_000;
    private static final int    QUEUE_CAPACITY   = 512;
    private static final String IFACE            = "wlan0";

    private final AtomicBoolean  running   = new AtomicBoolean(false);
    private final AtomicInteger  scanCount = new AtomicInteger(0);
    private final BlockingQueue<String> queue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
    private volatile Thread scanThread;

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    public synchronized boolean start() {
        if (running.get()) return false;
        running.set(true);
        scanCount.set(0);
        queue.clear();
        scanThread = new Thread(this::runLoop, "wifi-monitor");
        scanThread.setDaemon(true);
        scanThread.start();
        AppLog.info("[MONITOR] start iface=" + IFACE);
        return true;
    }

    public synchronized boolean stop() {
        if (!running.get()) return false;
        running.set(false);
        Thread t = scanThread;
        if (t != null) t.interrupt();
        push(statusEvent("Monitor stopped after " + scanCount.get() + " scans"));
        AppLog.info("[MONITOR] stop scans=" + scanCount.get());
        return true;
    }

    public boolean isRunning() { return running.get(); }

    /**
     * Blocking poll — waits up to {@code timeoutMs} for the next JSON event.
     * Returns {@code null} on timeout or interrupt.
     */
    public String pollLine(long timeoutMs) throws InterruptedException {
        return queue.poll(timeoutMs, TimeUnit.MILLISECONDS);
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private void push(String json) {
        if (!queue.offer(json)) {
            queue.poll();   // drop oldest to make room
            queue.offer(json);
        }
    }

    private void runLoop() {
        push(statusEvent("Monitor started on " + IFACE + ", scan interval " + SCAN_INTERVAL_MS + "ms"));
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            int n = scanCount.incrementAndGet();
            long t0 = System.currentTimeMillis();
            try {
                runScan(n, t0);
            } catch (Exception e) {
                push(logEvent("ERR", "Scan #" + n + " exception: " + escape(e.getMessage())));
                AppLog.err("[MONITOR] scan #" + n + ": " + e.getMessage());
            }
            long elapsed = System.currentTimeMillis() - t0;
            long sleep   = SCAN_INTERVAL_MS - elapsed;
            if (sleep > 0) {
                try { Thread.sleep(sleep); } catch (InterruptedException e) { break; }
            }
        }
        running.set(false);
    }

    private void runScan(int n, long t0) throws Exception {
        Process p = Runtime.getRuntime().exec(new String[]{"iwlist", IFACE, "scan"});

        // Drain stderr to prevent subprocess blocking on a full pipe buffer.
        final Process fp = p;
        Thread errDrain = new Thread(() -> {
            try {
                byte[] buf = new byte[256];
                while (fp.getErrorStream().read(buf) != -1) { /* discard */ }
            } catch (Exception ignored) {}
        }, "monitor-err-drain");
        errDrain.setDaemon(true);
        errDrain.start();

        StringBuilder raw = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = br.readLine()) != null) {
                raw.append(line).append('\n');
            }
        }
        p.waitFor();

        long elapsed = System.currentTimeMillis() - t0;
        String output = raw.toString();
        if (output.trim().isEmpty()) {
            push(logEvent("WARN", "Scan #" + n + " empty — interface down or scanning blocked"));
            return;
        }
        int apCount = parseAndPush(output, n);
        push(statusEvent("Scan #" + n + ": " + apCount + " AP" + (apCount != 1 ? "s" : "") + " in " + elapsed + " ms"));
        AppLog.info("[MONITOR] scan #" + n + " aps=" + apCount + " ms=" + elapsed);
    }

    /**
     * Parse {@code iwlist scan} output into individual AP JSON events.
     * Returns the number of APs found.
     */
    private int parseAndPush(String raw, int n) {
        // iwlist output groups each AP under "Cell XX -- Address: ..."
        String[] cells = raw.split("(?=\\s*Cell \\d+)");
        int count = 0;
        for (String cell : cells) {
            if (!cell.contains("Address:")) continue;
            String mac      = extractBetween(cell, "Address: ", "\n");
            // Stop at the newline so that Extra:wmm_ie=...hex" can't contaminate the SSID.
            String ssidLine = extractBetween(cell, "ESSID:\"", "\n");
            String ssid     = ssidLine.endsWith("\"") ? ssidLine.substring(0, ssidLine.length() - 1) : ssidLine;
            int    rssi     = parseRssi(cell);
            // iwlist format: "Frequency:2.437 GHz (Channel 6)"
            String ch       = extractBetween(cell, "(Channel ", ")");
            String enc  = cell.contains("Encryption key:on") ? "Y" : "N";
            String wpa  = cell.contains("WPA2") ? "WPA2"
                        : cell.contains("WPA")  ? "WPA"
                        : enc.equals("Y")        ? "WEP" : "Open";
            if (mac.isEmpty()) continue;
            count++;

            StringBuilder ev = new StringBuilder();
            ev.append("{\"type\":\"ap\"");
            ev.append(",\"scan\":").append(n);
            ev.append(",\"mac\":\"").append(escape(mac)).append('"');
            ev.append(",\"ssid\":\"").append(escape(ssid.isEmpty() ? "[HIDDEN]" : ssid)).append('"');
            ev.append(",\"rssi\":").append(rssi);
            ev.append(",\"ch\":\"").append(escape(ch)).append('"');
            ev.append(",\"enc\":\"").append(enc).append('"');
            ev.append(",\"wpa\":\"").append(wpa).append('"');
            ev.append(",\"drone\":").append(isDroneLike(mac, ssid) ? "true" : "false");
            ev.append('}');
            push(ev.toString());
        }
        return count;
    }

    // ── Drone heuristic ───────────────────────────────────────────────────────

    private static final String[] DRONE_OUI = {
        "60:60:1F", "34:D2:62", "48:1C:B9", "A0:14:3D", "90:03:B7", "AC:CF:85"
    };
    private static final String[] DRONE_SSID_PREFIX = {
        "MAVIC", "PHANTOM", "SPARK-", "TELLO-", "DJI", "AVATA", "ANAFI", "BEBOP", "FPV", "PARROT"
    };

    private static boolean isDroneLike(String mac, String ssid) {
        String macUp  = mac.toUpperCase();
        String ssidUp = ssid.toUpperCase();
        for (String oui : DRONE_OUI) {
            if (macUp.startsWith(oui.toUpperCase())) return true;
        }
        for (String prefix : DRONE_SSID_PREFIX) {
            if (ssidUp.contains(prefix)) return true;
        }
        return false;
    }

    // ── Parsing helpers ───────────────────────────────────────────────────────

    private static String extractBetween(String src, String after, String before) {
        int a = src.indexOf(after);
        if (a < 0) return "";
        a += after.length();
        int b = src.indexOf(before, a);
        return (b > a ? src.substring(a, b) : src.substring(a)).trim();
    }

    private static int parseRssi(String cell) {
        // "Signal level=-65 dBm" OR "Signal level=45/100"
        int idx = cell.indexOf("Signal level=");
        if (idx < 0) return 0;
        String s = cell.substring(idx + 13).trim();
        try {
            if (s.startsWith("-")) {
                return Integer.parseInt(s.split("[ /\n]")[0]);
            }
            // Percentage form: convert 0–100 to approximate dBm
            int pct = Integer.parseInt(s.split("[ /\n]")[0]);
            return -100 + pct / 2;
        } catch (Exception ignored) { return 0; }
    }

    // ── JSON helpers ──────────────────────────────────────────────────────────

    private static String statusEvent(String msg) {
        return "{\"type\":\"status\",\"msg\":\"" + escape(msg) + "\"}";
    }

    private static String logEvent(String level, String msg) {
        return "{\"type\":\"log\",\"level\":\"" + level + "\",\"msg\":\"" + escape(msg) + "\"}";
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "");
    }
}
