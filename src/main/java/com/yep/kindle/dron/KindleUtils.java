package com.yep.kindle.dron;

public class KindleUtils {

    static final int ROWS = 40;
    static final int COLS = 50;

    static void exec(String... cmd) {
        try {
            Runtime.getRuntime().exec(cmd).waitFor();
        } catch (Exception e) {
            System.err.println("exec: " + e.getMessage());
        }
    }

    static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (Exception ignored) {}
    }

    static void renderToEInk(String text) {
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
