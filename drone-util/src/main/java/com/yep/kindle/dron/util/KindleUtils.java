package com.yep.kindle.dron.util;

public class KindleUtils {

    public static final int ROWS = 40;
    public static final int COLS = 50;

    public static void exec(String... cmd) {
        try {
            Process p = Runtime.getRuntime().exec(cmd);
            // Drain stdout and stderr in background threads to prevent the
            // subprocess from blocking on a full OS pipe buffer, which would
            // cause waitFor() to deadlock (Java 8-compatible byte-array loop).
            Thread outDrain = new Thread(() -> drain(p.getInputStream()), "exec-out-drain");
            Thread errDrain = new Thread(() -> drain(p.getErrorStream()), "exec-err-drain");
            outDrain.setDaemon(true);
            errDrain.setDaemon(true);
            outDrain.start();
            errDrain.start();
            p.waitFor();
        } catch (Exception e) {
            System.err.println("exec: " + e.getMessage());
        }
    }

    public static void drain(java.io.InputStream is) {
        if (is == null) return;
        try {
            byte[] buf = new byte[512];
            while (is.read(buf) != -1) { /* discard */ }
        } catch (Exception ignored) {}
    }

    public static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (Exception ignored) {}
    }

    public static void renderToEInk(String text) {
        try {
            Runtime.getRuntime().exec(new String[]{"eips", "-c"}).waitFor();
            String[] lines = text.split("\n");
            for (int y = 0; y < lines.length && y < ROWS; y++) {
                String l = lines[y];
                if (l.isEmpty()) continue;
                if (l.length() > COLS) l = l.substring(0, COLS);
                Runtime.getRuntime().exec(new String[]{"eips", "0", String.valueOf(y), l}).waitFor();
            }
        } catch (Exception e) {
            System.err.println("eips: " + e.getMessage());
        }
    }
}
