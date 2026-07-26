package com.yep.kindle.dron.display;

import java.util.Arrays;
import java.util.List;

import com.yep.kindle.dron.model.AP;
import com.yep.kindle.dron.model.DetectorContext;
import com.yep.kindle.dron.util.KindleUtils;

/**
 * DisplayManager — drives the Kindle e-ink display.
 *
 * Owns the row-level diff cache ({@code screenCache}) and the current page
 * tracking ({@code currentPage}).  All page-switching logic lives here so
 * the main loop just calls {@code showWeather()}, {@code showHistory()}, etc.
 *
 * The row-level diff avoids unnecessary e-ink refresh cycles: only rows whose
 * content changed since the last render are re-sent to {@code eips}, minimising
 * wear on the e-ink panel.
 */
public class DisplayManager {

    private final String[] screenCache = new String[KindleUtils.ROWS];
    private String currentPage = "";

    public DisplayManager() {
        Arrays.fill(screenCache, "");
    }

    // =========================================================================
    // Page accessors
    // =========================================================================

    public String getCurrentPage() { return currentPage; }

    public void resetCache() {
        Arrays.fill(screenCache, "");
        currentPage = "";
    }

    // =========================================================================
    // Named page renders
    // =========================================================================

    /** Render (or refresh) the weather page using row-level diff. */
    public void showWeatherPage(DetectorContext ctx) {
        String[] sc = ScreenBuilder.buildWeatherScreen(ctx);
        switchAndDiff("weather", sc);
    }

    /** Render the distance-history page. */
    public void showHistoryPage(DetectorContext ctx) {
        String[] sc = ScreenBuilder.buildHistoryScreen(ctx);
        switchAndDiff("history", sc);
    }

    /** Render the road-radar page. */
    public void showRoadRadarPage(DetectorContext ctx) {
        String[] sc = ScreenBuilder.buildRoadRadarScreen(ctx);
        switchAndDiff("road", sc);
    }

    /**
     * Legacy HUD render — full diff with optional alert flash.
     */
    public void render(List<AP> aps, boolean alert, DetectorContext ctx) {
        String[] screen = ScreenBuilder.buildScreen(aps, ctx);

        boolean anyChange = false;
        for (int i = 0; i < KindleUtils.ROWS; i++) {
            if (!screen[i].equals(screenCache[i])) { anyChange = true; break; }
        }
        if (!anyChange) return;

        if (alert) {
            KindleUtils.exec("eips", "-f");
            KindleUtils.sleep(300);
            KindleUtils.exec("eips", "-c");
            KindleUtils.sleep(50);
            for (int y = 0; y < KindleUtils.ROWS; y++) {
                String l = screen[y];
                if (!l.isEmpty()) KindleUtils.exec("eips", "0", String.valueOf(y), l);
                screenCache[y] = l;
            }
            return;
        }

        applyDiff(screen);
    }

    // =========================================================================
    // Radar image display helpers
    // =========================================================================

    /**
     * Show one animation frame from the radar ring buffer.
     * Falls back to the main radar.png if the frame file doesn't exist.
     */
    public void showRadarAnimFrame(int frameSlot) {
        String path = RadarRenderer.FRAME_FILES[frameSlot];
        java.io.File f = new java.io.File(path);
        if (!f.exists()) {
            KindleUtils.exec("eips", "-g", RadarRenderer.IMAGE_FILE);
        } else {
            KindleUtils.exec("eips", "-g", path);
        }
    }

    /**
     * Clear screen and mark display as needing a full re-draw (called after
     * the radar countdown expires so the weather page gets drawn fresh).
     */
    public void clearForPageSwitch(String logLabel) {
        KindleUtils.exec("eips", "-c");
        KindleUtils.sleep(100);
        resetCache();
        if (!logLabel.isEmpty()) System.out.println("DISPLAY -> " + logLabel);
    }

    // =========================================================================
    // Internal helpers
    // =========================================================================

    /**
     * If switching to a new page, issue a full clear first; then apply row-level diff.
     */
    private void switchAndDiff(String pageName, String[] sc) {
        if (!pageName.equals(currentPage)) {
            KindleUtils.exec("eips", "-c");
            KindleUtils.sleep(150);
            Arrays.fill(screenCache, "");
            currentPage = pageName;
        }
        applyDiff(sc);
    }

    /**
     * Write only the rows that changed (and handle the case where a row got
     * shorter, which requires a full clear to avoid bleed-through).
     */
    private void applyDiff(String[] sc) {
        boolean needClear = false;
        for (int y = 0; y < KindleUtils.ROWS; y++) {
            String cur = sc[y] != null ? sc[y] : "";
            if (cur.length() < screenCache[y].length()) { needClear = true; break; }
        }
        if (needClear) {
            KindleUtils.exec("eips", "-c");
            KindleUtils.sleep(100);
            Arrays.fill(screenCache, "");
        }
        for (int y = 0; y < KindleUtils.ROWS; y++) {
            String l = sc[y] != null ? sc[y] : "";
            if (!l.equals(screenCache[y])) {
                if (!l.isEmpty()) KindleUtils.exec("eips", "0", String.valueOf(y), l);
                screenCache[y] = l;
            }
        }
    }
}
