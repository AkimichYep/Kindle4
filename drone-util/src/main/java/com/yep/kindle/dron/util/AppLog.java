package com.yep.kindle.dron.util;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal static logger shared across all modules.
 *
 * Call {@link #init(String, long)} once at app startup; after that any
 * module can call {@link #info(String)} / {@link #err(String)} without
 * referencing the file path again.
 *
 * Every line is written to:
 *   1. System.out / System.err (always — safe to call before init)
 *   2. An in-memory ring buffer ({@link #getRecentLines}) — used by /api/logs
 *   3. The configured log file (only after {@link #init} is called)
 *
 * File rotation: once the file exceeds {@code maxBytes}, it is renamed to
 * {@code <path>.1} (overwriting any previous backup) and a fresh file is opened.
 */
public final class AppLog {

    private static final int RING_SIZE = 600;
    private static final String[] RING = new String[RING_SIZE];

    private static int  head     = 0;   // next write slot
    private static int  size     = 0;   // filled slots (capped at RING_SIZE)
    private static int  errCount = 0;
    private static int  writes   = 0;   // total writes since init (for rotation checks)

    private static PrintWriter fileWriter   = null;
    private static long        maxFileBytes = 256 * 1024L;
    private static String      filePath     = null;

    private AppLog() {}

    // ── Initialisation ────────────────────────────────────────────────────────

    public static synchronized void init(String path, long maxBytes) {
        KindleUtils.syncSystemTimeZone();
        filePath     = path;
        maxFileBytes = maxBytes;
        openFile();
    }

    // ── Public log methods ────────────────────────────────────────────────────

    public static void info(String msg) { write("INFO", msg, false); }
    public static void err(String msg)  { write("ERR ", msg, true);  }

    // ── Ring-buffer access (for /api/logs) ────────────────────────────────────

    public static synchronized List<String> getRecentLines(int n) {
        int count = Math.min(n, size);
        List<String> out = new ArrayList<>(count);
        int start = (head - count + RING_SIZE) % RING_SIZE;
        for (int i = 0; i < count; i++) {
            String s = RING[(start + i) % RING_SIZE];
            if (s != null) out.add(s);
        }
        return out;
    }

    public static synchronized int getErrCount() { return errCount; }
    public static synchronized int getTotalLines() { return writes; }

    /** Returns the configured log file path, or null if not initialised. */
    public static String getFilePath() { return filePath; }

    // ── Internal ──────────────────────────────────────────────────────────────

    private static synchronized void write(String level, String msg, boolean isErr) {
        String line = String.format("%tT %s %s", System.currentTimeMillis(), level, msg);
        if (isErr) System.err.println(line); else System.out.println(line);
        if (isErr) errCount++;
        writes++;

        RING[head] = line;
        head = (head + 1) % RING_SIZE;
        if (size < RING_SIZE) size++;

        if (fileWriter != null) {
            fileWriter.println(line);
            // Check rotation every 50 writes to avoid a stat() call on every line
            if (writes % 50 == 0 && filePath != null) {
                File f = new File(filePath);
                if (f.length() > maxFileBytes) rotate();
            }
        }
    }

    private static void openFile() {
        if (filePath == null) return;
        try {
            new File(filePath).getParentFile().mkdirs();
            fileWriter = new PrintWriter(new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(filePath, true), StandardCharsets.UTF_8)), true);
        } catch (IOException e) {
            System.err.println("AppLog.openFile: " + e.getMessage());
        }
    }

    private static void rotate() {
        try {
            if (fileWriter != null) { fileWriter.close(); fileWriter = null; }
            File f   = new File(filePath);
            File bak = new File(filePath + ".1");
            if (bak.exists()) bak.delete();
            f.renameTo(bak);
            openFile();
        } catch (Exception e) {
            System.err.println("AppLog.rotate: " + e.getMessage());
        }
    }
}
