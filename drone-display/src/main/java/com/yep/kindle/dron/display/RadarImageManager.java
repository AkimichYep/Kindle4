package com.yep.kindle.dron.display;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

import com.yep.kindle.dron.detection.TemporalEngine;
import com.yep.kindle.dron.model.AP;
import com.yep.kindle.dron.model.DetectorContext;
import com.yep.kindle.dron.model.History;
import com.yep.kindle.dron.service.WeatherService;
import com.yep.kindle.dron.util.AppLog;
import com.yep.kindle.dron.util.KindleUtils;

/**
 * RadarImageManager — orchestrates radar PNG generation, snapshot persistence,
 * and animation frame display.
 *
 * Delegates pixel rendering to {@link RadarRenderer}; handles file lifecycle
 * (timestamped snapshots, ring-buffer frame management, snapshot pruning) and
 * issues {@code eips -g} commands to show the result on the e-ink display.
 */
public class RadarImageManager {

    private static final int SNAPSHOT_MAX = 3;
    private static final String SNAPSHOT_DIR = "/mnt/us";

    private final Consumer<String> logger;

    public RadarImageManager(Consumer<String> logger) {
        this.logger = logger;
    }

    /**
     * Render the radar image from a pre-filtered AP list, write it to the
     * 3-frame ring buffer, and show the latest frame via {@code eips -g}.
     *
     * @param radarAps  event-filtered AP list (NEW / DIST / RET entries only)
     * @param ctx       shared detector state
     * @return true if the image was successfully shown (radarCountdown should be set by caller)
     */
    public boolean renderAndShow(List<AP> radarAps, DetectorContext ctx) {
        try {
            boolean newDetection = detectNewEvent(radarAps, ctx.temporal);

            RadarRenderer.render(radarAps, ctx.armed, ctx.loop, ctx.externalRF,
                    ctx.noiseFloor, ctx.csSnr,
                    WeatherService.weatherSummaryForRadar(ctx.weather));

            if (newDetection) saveSnapshot();

            // Clear any text ghosting, then display the image
            KindleUtils.exec("eips", "-c");
            KindleUtils.sleep(300);
            KindleUtils.exec("eips", "-g", RadarRenderer.IMAGE_FILE);
            KindleUtils.sleep(500);

            int frameIdx = (RadarRenderer.nextFrame + RadarRenderer.FRAME_COUNT - 1)
                    % RadarRenderer.FRAME_COUNT;
            log(String.format("RADAR shown: %d items (%d live, %d ghost) newDet=%b frame=%d",
                    radarAps.size(),
                    (int) radarAps.stream().filter(a -> !a.ghost).count(),
                    (int) radarAps.stream().filter(a -> a.ghost).count(),
                    newDetection, frameIdx));
            return true;
        } catch (Exception e) {
            AppLog.err("RadarImageManager.renderAndShow: " + e.getMessage());
            log("RADAR ERROR: " + e.getMessage());
            return false;
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private boolean detectNewEvent(List<AP> radarAps, TemporalEngine temporal) {
        for (AP a : radarAps) {
            if (a.ghost) continue;
            History h = temporal.get(a.mac);
            if (h != null && (h.isNew || (h.seenNow && !h.seenPrev && h.count > 3))) {
                return true;
            }
        }
        return false;
    }

    private void saveSnapshot() {
        long ts = System.currentTimeMillis();
        String snapPath = String.format("%s/radar_%tY%tm%td_%tH%tM%tS.png",
                SNAPSHOT_DIR, ts, ts, ts, ts, ts, ts);
        File src = new File(RadarRenderer.IMAGE_FILE);
        if (!src.exists()) return;
        try {
            Files.copy(src.toPath(), new File(snapPath).toPath(),
                       StandardCopyOption.REPLACE_EXISTING);
            log("RADAR snapshot: " + snapPath);
            pruneSnapshots();
        } catch (IOException e) {
            AppLog.err("radar snapshot: " + e.getMessage());
        }
    }

    /**
     * Keep only the SNAPSHOT_MAX most recent timestamped radar PNGs on disk.
     */
    public void pruneSnapshots() {
        File dir = new File(SNAPSHOT_DIR);
        File[] snaps = dir.listFiles((d, name) ->
                name.startsWith("radar_") && name.endsWith(".png")
                && name.length() > "radar_.png".length());
        if (snaps == null || snaps.length <= SNAPSHOT_MAX) return;
        Arrays.sort(snaps, (a, b) -> a.getName().compareTo(b.getName()));
        int toDelete = snaps.length - SNAPSHOT_MAX;
        for (int i = 0; i < toDelete; i++) {
            boolean deleted = snaps[i].delete();
            log("RADAR prune " + (deleted ? "deleted" : "FAILED") + ": " + snaps[i].getName());
        }
    }

    private void log(String msg) {
        if (logger != null) logger.accept(msg);
    }
}
