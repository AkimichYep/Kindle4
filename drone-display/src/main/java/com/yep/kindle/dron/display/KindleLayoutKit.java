package com.yep.kindle.dron.display;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;

/**
 * Reusable layout primitives shared by all Kindle image generators.
 * <p>
 * Every method is stateless and side-effect-free beyond mutating the supplied
 * {@link Graphics2D} context.  Callers are free to change {@code g2d} state
 * after any call — each method restores color and stroke before returning
 * (via local variable save/restore rather than {@code Graphics2D.create()}).
 * </p>
 *
 * <h3>Coordinate contract</h3>
 * All methods work with the standard 600×800 canvas defined in
 * {@link KindleCanvas}.  Constants {@code WIDTH} and {@code HEIGHT} from
 * that class are used internally.
 */
public final class KindleLayoutKit {

    // -------------------------------------------------------------------------
    // Shared colors — kept as fields so all methods stay visually consistent
    // -------------------------------------------------------------------------

    /** Pure black. */
    public static final Color BLACK = Color.BLACK;

    /** Pure white. */
    public static final Color WHITE = Color.WHITE;

    /** Header / footer fill — solid black. */
    public static final Color HEADER_BG = Color.BLACK;

    /** Section header bar fill — light gray. */
    public static final Color SECTION_BG = new Color(185, 185, 185);

    /** Footer bar fill — near-black. */
    public static final Color FOOTER_BG = new Color(50, 50, 50);

    /** Even-row alternating shade. */
    public static final Color ROW_EVEN = new Color(240, 240, 240);

    /** Odd-row alternating shade (white). */
    public static final Color ROW_ODD  = Color.WHITE;

    /** Progress-bar background track. */
    public static final Color BAR_TRACK = new Color(210, 210, 210);

    /** Column header bar. */
    public static final Color COL_HEADER_BG = new Color(60, 60, 60);

    private KindleLayoutKit() {}

    // =========================================================================
    // CHROME — borders and dividers
    // =========================================================================

    /**
     * Draws the universal 3 px outer border rectangle around the full canvas.
     * Always call this <em>last</em>, just before {@code g2d.dispose()}.
     */
    public static void drawOuterBorder(Graphics2D g2d) {
        g2d.setColor(BLACK);
        g2d.setStroke(new BasicStroke(3));
        g2d.drawRect(2, 2, KindleCanvas.WIDTH - 4, KindleCanvas.HEIGHT - 4);
    }

    /**
     * Draws a full-width 2 px horizontal separator line inset 20 px from each
     * side.
     *
     * @param g2d target graphics context
     * @param y   vertical position of the line
     */
    public static void drawSeparator(Graphics2D g2d, int y) {
        g2d.setColor(BLACK);
        g2d.setStroke(new BasicStroke(2));
        g2d.drawLine(20, y, KindleCanvas.WIDTH - 20, y);
    }

    /**
     * Draws a 1 px horizontal divider spanning the full canvas width —
     * used for internal row dividers.
     *
     * @param g2d target graphics context
     * @param y   vertical position
     */
    public static void drawRowDivider(Graphics2D g2d, int y) {
        g2d.setColor(new Color(180, 180, 180));
        g2d.setStroke(new BasicStroke(1));
        g2d.drawLine(0, y, KindleCanvas.WIDTH, y);
    }

    // =========================================================================
    // HEADER BAR
    // =========================================================================

    /**
     * Fills a solid black header rectangle at the top of the canvas.
     *
     * @param g2d    target graphics context
     * @param height height of the header bar in pixels
     */
    public static void drawHeaderBar(Graphics2D g2d, int height) {
        g2d.setColor(HEADER_BG);
        g2d.fillRect(0, 0, KindleCanvas.WIDTH, height);
    }

    /**
     * Draws the standard header content: an optional weather-icon glyph on the
     * left, then a bold title and a smaller plain subtitle — all in white.
     *
     * @param g2d       target graphics context
     * @param iconBase  the base weather-icons font (may be {@code null})
     * @param iconSize  point size for the icon glyph
     * @param glyphCode Unicode code point from the weather-icons font
     * @param iconX     left edge of the icon
     * @param iconY     baseline of the icon
     * @param title     main header title string
     * @param titleSize font point size for the title
     * @param titleX    left edge of the title
     * @param titleY    baseline of the title
     * @param subtitle  secondary text (may be {@code null} or empty to skip)
     * @param subSize   font point size for the subtitle
     * @param subX      left edge of the subtitle
     * @param subY      baseline of the subtitle
     */
    public static void drawHeaderContent(Graphics2D g2d,
                                          Font iconBase, float iconSize, int glyphCode,
                                          int iconX, int iconY,
                                          String title, int titleSize, int titleX, int titleY,
                                          String subtitle, int subSize, int subX, int subY) {
        g2d.setColor(WHITE);

        // Icon glyph
        if (iconBase != null) {
            g2d.setFont(iconBase.deriveFont(Font.PLAIN, iconSize));
            g2d.drawString(WeatherIconFont.glyph(glyphCode), iconX, iconY);
        }

        // Title
        g2d.setFont(new Font("SansSerif", Font.BOLD, titleSize));
        g2d.drawString(title, titleX, titleY);

        // Subtitle
        if (subtitle != null && !subtitle.isEmpty()) {
            g2d.setFont(new Font("SansSerif", Font.PLAIN, subSize));
            g2d.drawString(subtitle, subX, subY);
        }
    }

    // =========================================================================
    // FOOTER BAR
    // =========================================================================

    /**
     * Draws a dark-gray footer bar and renders the attribution text in white.
     *
     * @param g2d    target graphics context
     * @param text   attribution / source line
     * @param yTop   top edge of the footer bar
     * @param height height of the footer bar in pixels
     */
    public static void drawFooterBar(Graphics2D g2d, String text, int yTop, int height) {
        g2d.setColor(FOOTER_BG);
        g2d.fillRect(0, yTop, KindleCanvas.WIDTH, height);
        g2d.setColor(WHITE);
        g2d.setFont(new Font("SansSerif", Font.PLAIN, 14));
        g2d.drawString(text, 18, yTop + height - 10);
    }

    // =========================================================================
    // SECTION HEADER
    // =========================================================================

    /**
     * Draws a gray section-header band with a 5 px black left-accent stripe,
     * an optional weather-icon glyph, and a bold title.
     * <p>
     * Height of the band is fixed at 28 px.
     * </p>
     *
     * @param g2d       target graphics context
     * @param iconBase  base weather-icons font (may be {@code null})
     * @param glyphCode codepoint for the section icon
     * @param title     section title text
     * @param y         top edge of the section header band
     */
    public static void drawSectionHeader(Graphics2D g2d,
                                          Font iconBase, int glyphCode,
                                          String title, int y) {
        // Gray band
        g2d.setColor(SECTION_BG);
        g2d.fillRect(0, y, KindleCanvas.WIDTH, 28);

        // Left accent stripe
        g2d.setColor(BLACK);
        g2d.fillRect(0, y, 5, 28);

        // Icon
        if (iconBase != null) {
            g2d.setFont(iconBase.deriveFont(Font.PLAIN, 18f));
            g2d.setColor(BLACK);
            g2d.drawString(WeatherIconFont.glyph(glyphCode), 10, y + 21);
        }

        // Title text
        g2d.setFont(new Font("SansSerif", Font.BOLD, 15));
        g2d.setColor(BLACK);
        g2d.drawString(title, 34, y + 20);
    }

    // =========================================================================
    // COLUMN TABLE HEADER
    // =========================================================================

    /**
     * Draws a dark column-header row used at the top of data tables.
     *
     * @param g2d     target graphics context
     * @param y       top edge of the header row
     * @param height  row height in pixels
     * @param columns pairs of (x-position, label) as varargs, e.g.
     *                {@code 20, "REGION", 140, "LOC", ...}
     */
    public static void drawColumnHeader(Graphics2D g2d, int y, int height, Object... columns) {
        g2d.setColor(COL_HEADER_BG);
        g2d.fillRect(14, y, KindleCanvas.WIDTH - 28, height);
        g2d.setColor(WHITE);
        g2d.setFont(new Font("SansSerif", Font.BOLD, 13));
        for (int i = 0; i + 1 < columns.length; i += 2) {
            int x   = ((Number) columns[i]).intValue();
            String lbl = String.valueOf(columns[i + 1]);
            g2d.drawString(lbl, x, y + height - 7);
        }
    }

    // =========================================================================
    // ROW SHADING
    // =========================================================================

    /**
     * Fills an alternating row background (even rows = light gray,
     * odd rows = white).
     *
     * @param g2d      target graphics context
     * @param rowIndex 0-based row index
     * @param x        left edge of the fill rectangle
     * @param y        top edge of the fill rectangle
     * @param w        width of the fill rectangle
     * @param h        height of the fill rectangle
     */
    public static void drawRowBackground(Graphics2D g2d,
                                          int rowIndex, int x, int y, int w, int h) {
        g2d.setColor(rowIndex % 2 == 0 ? ROW_EVEN : ROW_ODD);
        g2d.fillRect(x, y, w, h);
    }

    /**
     * Draws a thin left accent stripe to highlight a special row (e.g. today).
     *
     * @param g2d   target graphics context
     * @param y     top edge of the row
     * @param h     height of the row
     * @param width width of the stripe in pixels
     */
    public static void drawRowAccentStripe(Graphics2D g2d, int y, int h, int width) {
        g2d.setColor(new Color(120, 120, 120));
        g2d.fillRect(0, y, width, h);
    }

    // =========================================================================
    // PROGRESS / ILLUMINATION BAR
    // =========================================================================

    /**
     * Draws a horizontal progress bar with an optional centred percent label.
     *
     * @param g2d          target graphics context
     * @param fraction     fill level in range [0.0, 1.0]
     * @param x            left edge
     * @param y            top edge
     * @param w            total bar width
     * @param h            bar height
     * @param showLabel    if {@code true}, renders "N%" centred inside the bar
     * @param invertLabel  when {@code true} prints the label in white
     *                     (use when the filled area is dark and covers the label)
     */
    public static void drawProgressBar(Graphics2D g2d,
                                        double fraction,
                                        int x, int y, int w, int h,
                                        boolean showLabel, boolean invertLabel) {
        // Background track
        g2d.setColor(BAR_TRACK);
        g2d.fillRect(x, y, w, h);

        // Filled portion
        g2d.setColor(BLACK);
        int fillW = (int) Math.min(w, Math.max(0, fraction * w));
        if (fillW > 0) g2d.fillRect(x, y, fillW, h);

        // Border
        g2d.setColor(new Color(100, 100, 100));
        g2d.setStroke(new BasicStroke(1));
        g2d.drawRect(x, y, w, h);

        // Percent label
        if (showLabel) {
            String label = (int) Math.round(fraction * 100) + "%";
            g2d.setFont(new Font("SansSerif", Font.BOLD, Math.max(10, h - 2)));
            g2d.setColor(invertLabel ? WHITE : BLACK);
            FontMetrics fm = g2d.getFontMetrics();
            int lx = x + (w - fm.stringWidth(label)) / 2;
            int ly = y + (h + fm.getAscent() - fm.getDescent()) / 2;
            g2d.drawString(label, lx, ly);
        }
    }

    /**
     * Draws a vertical progress bar (used in the moon calendar today-panel
     * as a "fill from bottom" illumination indicator).
     *
     * @param g2d       target graphics context
     * @param fraction  fill level in range [0.0, 1.0]; 1.0 = completely filled
     * @param x         left edge
     * @param y         top edge
     * @param w         bar width
     * @param h         bar height
     * @param showLabel if {@code true}, renders "N%" centred inside
     */
    public static void drawVerticalBar(Graphics2D g2d,
                                        double fraction,
                                        int x, int y, int w, int h,
                                        boolean showLabel) {
        // Background track
        g2d.setColor(BAR_TRACK);
        g2d.fillRect(x, y, w, h);

        // Fill from bottom
        g2d.setColor(BLACK);
        int fillH = (int) Math.min(h, Math.max(0, fraction * h));
        if (fillH > 0) g2d.fillRect(x, y + h - fillH, w, fillH);

        // Border
        g2d.setColor(BLACK);
        g2d.setStroke(new BasicStroke(2));
        g2d.drawRect(x, y, w, h);

        // Percent label
        if (showLabel) {
            String label = (int) Math.round(fraction * 100) + "%";
            g2d.setFont(new Font("SansSerif", Font.BOLD, 20));
            boolean light = fraction > 0.5;
            g2d.setColor(light ? WHITE : BLACK);
            FontMetrics fm = g2d.getFontMetrics();
            int lx = x + (w - fm.stringWidth(label)) / 2;
            int ly = y + h / 2 + 7;
            g2d.drawString(label, lx, ly);
        }
    }

    // =========================================================================
    // CIRCULAR BADGE
    // =========================================================================

    /**
     * Draws a filled circle "badge" (e.g. Kp-level or flare-class indicator)
     * with a centred label.
     *
     * @param g2d       target graphics context
     * @param fillColor fill color of the circle
     * @param textColor text color for the label (set to {@code null} to skip label)
     * @param label     the short label drawn inside (1–3 characters)
     * @param cx        centre x
     * @param cy        centre y
     * @param r         radius
     */
    public static void drawBadge(Graphics2D g2d,
                                  Color fillColor, Color textColor, String label,
                                  int cx, int cy, int r) {
        g2d.setColor(fillColor);
        g2d.fillOval(cx - r, cy - r, r * 2, r * 2);
        g2d.setColor(new Color(50, 50, 50));
        g2d.setStroke(new BasicStroke(2));
        g2d.drawOval(cx - r, cy - r, r * 2, r * 2);

        if (textColor != null && label != null && !label.isEmpty()) {
            g2d.setColor(textColor);
            g2d.setFont(new Font("SansSerif", Font.BOLD, Math.max(10, r - 6)));
            FontMetrics fm = g2d.getFontMetrics();
            g2d.drawString(label,
                           cx - fm.stringWidth(label) / 2,
                           cy + fm.getAscent() / 2 - 1);
        }
    }
}
