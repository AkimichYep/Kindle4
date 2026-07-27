package com.yep.kindle.dron.display;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/**
 * Factory for the standard 600×800 Kindle e-ink canvas.
 * <p>
 * All three Kindle image generators (Weather, MoonCalendar, SpaceWeather)
 * use exactly the same canvas configuration.  Creating it in one place
 * guarantees consistency and eliminates the copy-paste setup block.
 * </p>
 */
public final class KindleCanvas {

    /** Kindle 4 / Kindle Touch portrait width in pixels. */
    public static final int WIDTH  = 600;

    /** Kindle 4 / Kindle Touch portrait height in pixels. */
    public static final int HEIGHT = 800;

    private KindleCanvas() {}

    /**
     * Creates a new 600×800 grayscale {@link BufferedImage} and returns its
     * {@link Graphics2D} context already configured with:
     * <ul>
     *   <li>Full antialiasing (shapes + text)</li>
     *   <li>Quality rendering hint</li>
     *   <li>White background pre-filled</li>
     * </ul>
     *
     * @param image the image that was created with {@link #newImage()}
     * @return a ready-to-draw {@code Graphics2D} context
     */
    public static Graphics2D createGraphics(BufferedImage image) {
        Graphics2D g2d = image.createGraphics();
        g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                             RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                             RenderingHints.VALUE_ANTIALIAS_ON);
        g2d.setRenderingHint(RenderingHints.KEY_RENDERING,
                             RenderingHints.VALUE_RENDER_QUALITY);
        // White background
        g2d.setColor(Color.WHITE);
        g2d.fillRect(0, 0, WIDTH, HEIGHT);
        return g2d;
    }

    /**
     * Creates a blank 600×800 8-bit grayscale image.
     * Grayscale (not binary) is used so shaded fills render correctly.
     *
     * @return a new {@link BufferedImage}
     */
    public static BufferedImage newImage() {
        return new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_BYTE_GRAY);
    }
}
