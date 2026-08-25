package com.yep.kindle.dron.util;

import java.util.concurrent.TimeUnit;

public class KindleUtils {

    public static final int ROWS = 40;
    public static final int COLS = 50;
    private static final long TZ_SYNC_MIN_INTERVAL_MS = 60_000L;
    private static final long DEFAULT_COMMAND_TIMEOUT_MS = 15_000L;
    private static volatile long lastTzSyncAtMs = 0L;
    private static volatile Integer lastTzOffsetMinutes = null;

    /**
     * Executes a command and returns its trimmed stdout, or null on error.
     * Stderr is silently drained in a daemon thread to prevent deadlock.
     */
    public static String readCommand(String... cmd) {
        Process p = null;
        try {
            p = new ProcessBuilder(cmd).start();
            final Process process = p;
            Thread errDrain = new Thread(() -> drain(process.getErrorStream()), "cmd-err-drain");
            final StringBuilder output = new StringBuilder();
            Thread outReader = new Thread(() -> read(process.getInputStream(), output), "cmd-out-reader");
            errDrain.setDaemon(true);
            outReader.setDaemon(true);
            errDrain.start();
            outReader.start();
            if (!waitFor(p, cmd)) return null;
            outReader.join(250L);
            return output.toString().trim();
        } catch (Exception e) {
            AppLog.err("readCommand: " + e.getMessage());
            return null;
        } finally {
            if (p != null) p.destroy();
        }
    }

    /**
     * Synchronizes Java's default TimeZone with Linux `date` output.
     * Uses local HH:mm vs UTC HH:mm from the OS clock to avoid JVM tzdata quirks.
     */
    public static synchronized void syncSystemTimeZone() {
        long nowMs = System.currentTimeMillis();
        if (nowMs - lastTzSyncAtMs < TZ_SYNC_MIN_INTERVAL_MS) return;
        lastTzSyncAtMs = nowMs;

        try {
            String localHm = readCommand("date", "+%H:%M");
            String utcHm = readCommand("date", "-u", "+%H:%M");
            Integer localMin = parseHourMinuteToTotalMinutes(localHm);
            Integer utcMin = parseHourMinuteToTotalMinutes(utcHm);
            if (localMin == null || utcMin == null) {
                AppLog.err("syncSystemTimeZone: unable to parse local/utc time (local=" + localHm + ", utc=" + utcHm + ")");
                return;
            }

            int diffMin = localMin - utcMin;
            while (diffMin <= -720) diffMin += 1440;
            while (diffMin > 840) diffMin -= 1440;

            int abs = Math.abs(diffMin);
            int hh = abs / 60;
            int mm = abs % 60;
            String sign = diffMin >= 0 ? "+" : "-";
            String gmtTz = String.format("GMT%s%02d:%02d", sign, hh, mm);

            java.util.TimeZone tz = java.util.TimeZone.getTimeZone(gmtTz);
            java.util.TimeZone.setDefault(tz);
            System.setProperty("user.timezone", tz.getID());

            Integer prev = lastTzOffsetMinutes;
            lastTzOffsetMinutes = diffMin;
            if (prev == null || prev.intValue() != diffMin) {
                AppLog.info("Synced Java TimeZone (local=" + localHm + ", utc=" + utcHm + "): " + gmtTz);
            }
        } catch (Exception e) {
            AppLog.err("syncSystemTimeZone failed: " + e.getMessage());
        }
    }

    private static Integer parseHourMinuteToTotalMinutes(String hhmm) {
        if (hhmm == null || !hhmm.matches("\\d{2}:\\d{2}")) return null;
        try {
            int hh = Integer.parseInt(hhmm.substring(0, 2));
            int mm = Integer.parseInt(hhmm.substring(3, 5));
            if (hh < 0 || hh > 23 || mm < 0 || mm > 59) return null;
            return hh * 60 + mm;
        } catch (Exception ignored) {
            return null;
        }
    }

    public static void exec(String... cmd) {
        Process p = null;
        try {
            p = new ProcessBuilder(cmd).start();
            final Process process = p;
            // Drain stdout and stderr in background threads to prevent the
            // subprocess from blocking on a full OS pipe buffer, which would
            // cause waitFor() to deadlock (Java 8-compatible byte-array loop).
            Thread outDrain = new Thread(() -> drain(process.getInputStream()), "exec-out-drain");
            Thread errDrain = new Thread(() -> drain(process.getErrorStream()), "exec-err-drain");
            outDrain.setDaemon(true);
            errDrain.setDaemon(true);
            outDrain.start();
            errDrain.start();
            waitFor(p, cmd);
        } catch (Exception e) {
            AppLog.err("exec: " + e.getMessage());
        } finally {
            if (p != null) p.destroy();
        }
    }

    private static boolean waitFor(Process process, String[] command) throws InterruptedException {
        if (process.waitFor(commandTimeoutMs(), TimeUnit.MILLISECONDS)) return true;
        process.destroy();
        if (!process.waitFor(250, TimeUnit.MILLISECONDS)) process.destroyForcibly();
        AppLog.err("command timed out: " + command[0]);
        return false;
    }

    private static long commandTimeoutMs() {
        String configured = System.getProperty("kindle.command.timeout.ms");
        if (configured == null) return DEFAULT_COMMAND_TIMEOUT_MS;
        try {
            long timeout = Long.parseLong(configured);
            return timeout > 0 ? timeout : DEFAULT_COMMAND_TIMEOUT_MS;
        } catch (NumberFormatException ignored) {
            return DEFAULT_COMMAND_TIMEOUT_MS;
        }
    }

    public static void drain(java.io.InputStream is) {
        if (is == null) return;
        try (java.io.InputStream input = is) {
            byte[] buf = new byte[512];
            while (input.read(buf) != -1) { /* discard */ }
        } catch (Exception ignored) {}
    }

    private static void read(java.io.InputStream is, StringBuilder output) {
        if (is == null) return;
        try (java.io.InputStream input = is) {
            byte[] buffer = new byte[512];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.append(new String(buffer, 0, count, "UTF-8"));
            }
        } catch (Exception ignored) {
            // The process may be destroyed after a timeout, closing its stream.
        }
    }

    public static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (Exception ignored) {}
    }

    public static void renderToEInk(String text) {
        try {
            exec("eips", "-c");
            String[] lines = text.split("\n");
            for (int y = 0; y < lines.length && y < ROWS; y++) {
                String l = lines[y];
                if (l.isEmpty()) continue;
                if (l.length() > COLS) l = l.substring(0, COLS);
                exec("eips", "0", String.valueOf(y), l);
            }
        } catch (Exception e) {
            AppLog.err("eips: " + e.getMessage());
        }
    }
}
