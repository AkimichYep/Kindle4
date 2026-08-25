package com.yep.kindle.dron;

import com.yep.kindle.dron.display.KindleCanvas;
import com.yep.kindle.dron.display.KindleFormatUtils;
import com.yep.kindle.dron.display.KindleHttpClient;
import com.yep.kindle.dron.display.KindleJsonParser;
import com.yep.kindle.dron.display.KindleLayoutKit;
import com.yep.kindle.dron.display.WeatherIconFont;
import com.yep.kindle.dron.util.AppLog;
import com.yep.kindle.dron.util.KindleUtils;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Standalone Kindle e-ink moon-calendar image generator for Kharkiv, Ukraine.
 * <p>
 * Fetches sunrise/sunset from Open-Meteo (no key required) and computes moon
 * phases locally using a known new-moon anchor and the 29.53-day lunar cycle.
 * Renders a 600×800 grayscale PNG for the Kindle 4 e-ink screen.
 * </p>
 * <p>
 * All canvas setup, font loading, and layout chrome are delegated to the
 * shared {@code drone-display} module:
 * {@link KindleCanvas}, {@link WeatherIconFont}, {@link KindleLayoutKit},
 * {@link KindleHttpClient}, {@link KindleJsonParser}, {@link KindleFormatUtils}.
 * </p>
 */
public class KindleMoonCalendarNoKey {

    private static final String LAT = "49.9884";
    private static final String LON = "36.2328";

    private static final DateTimeFormatter API_DATE      = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter DAY_NAME      = DateTimeFormatter.ofPattern("EEE", Locale.ENGLISH);
    private static final DateTimeFormatter DAY_NUM       = DateTimeFormatter.ofPattern("dd MMM", Locale.ENGLISH);

    // Weather-icon glyphs for sunrise / sunset in the table
    private static final int GLYPH_SUNRISE = 0xF051;
    private static final int GLYPH_SUNSET  = 0xF052;
    // Full-moon glyph for the header
    private static final int GLYPH_FULL_MOON = 0xF0A3;

    public static void main(String[] args) {
        AppLog.init(System.getProperty("kindle.log.file", "moon-calendar.log"), 256 * 1024L);
        try {
            KindleUtils.syncSystemTimeZone();
            generateAndSave("kharkiv-moon-calendar.png");
            AppLog.info("Moon calendar image saved: "
                    + new File("kharkiv-moon-calendar.png").getAbsolutePath());
        } catch (Exception e) {
            AppLog.exception("Moon calendar image generation failed", e);
        }
    }

    /**
     * Fetch moon data, render image, and save to the given path.
     * Called by KindleDroneDetectorPro for in-process image generation.
     */
    public static void generateAndSave(String outputPath) throws Exception {
        KindleUtils.syncSystemTimeZone();
        String json = fetchMoonData();
        MoonCalendar calendar = parseMoonCalendar(json);
        BufferedImage image = renderMoonCalendar(calendar);
        ImageIO.write(image, "png", new File(outputPath));
    }

    // -------------------------------------------------------------------------
    // Data fetch
    // -------------------------------------------------------------------------

    private static String fetchMoonData() throws Exception {
        String urlString = "https://api.open-meteo.com/v1/forecast"
                + "?latitude=" + LAT + "&longitude=" + LON
                + "&daily=sunrise,sunset"
                + "&forecast_days=7"
                + "&timezone=auto";
        return KindleHttpClient.get(urlString, "KindleMoonCalendarNoKey/1.0");
    }

    // -------------------------------------------------------------------------
    // Moon phase calculation
    // -------------------------------------------------------------------------

    private static final double LUNAR_CYCLE    = 29.53058770576;
    private static final LocalDate KNOWN_NEW_MOON = LocalDate.of(2000, 1, 6);

    private static double calculateMoonPhase(LocalDate date) {
        long days = ChronoUnit.DAYS.between(KNOWN_NEW_MOON, date);
        double age = days % LUNAR_CYCLE;
        if (age < 0) age += LUNAR_CYCLE;
        return age / LUNAR_CYCLE; // 0.0 = new moon, 0.5 = full moon
    }

    /** Illumination fraction: 0 at new moon, 1 at full moon, back to 0. */
    private static double illumination(double phase) {
        return (1.0 - Math.cos(phase * 2 * Math.PI)) / 2.0;
    }

    // -------------------------------------------------------------------------
    // Parsing
    // -------------------------------------------------------------------------

    private static MoonCalendar parseMoonCalendar(String json) {
        MoonCalendar calendar = new MoonCalendar();

        String daily    = KindleJsonParser.extractSectionObject(json, "\"daily\":");
        String[] dates  = KindleJsonParser.parseStringArray(daily, "time");
        String[] rises  = KindleJsonParser.parseStringArray(daily, "sunrise");
        String[] sets   = KindleJsonParser.parseStringArray(daily, "sunset");

        int count = Math.min(7, dates.length);
        for (int i = 0; i < count; i++) {
            MoonDay day = new MoonDay();
            day.date          = parseDate(dates[i]);
            day.moonPhase     = calculateMoonPhase(day.date);
            day.illumination  = illumination(day.moonPhase);
            day.phaseName     = moonPhaseName(day.moonPhase);
            day.phaseGlyph    = moonPhaseGlyph(day.moonPhase);
            day.sunrise       = KindleFormatUtils.formatTimeOnly(i < rises.length ? rises[i] : "--:--");
            day.sunset        = KindleFormatUtils.formatTimeOnly(i < sets.length  ? sets[i]  : "--:--");
            calendar.days.add(day);
        }
        return calendar;
    }

    // -------------------------------------------------------------------------
    // Rendering — delegates all chrome to KindleLayoutKit
    // -------------------------------------------------------------------------

    private static BufferedImage renderMoonCalendar(MoonCalendar calendar) {
        BufferedImage image = KindleCanvas.newImage();
        Graphics2D g2d = KindleCanvas.createGraphics(image);

        Font iconBase = WeatherIconFont.get(KindleMoonCalendarNoKey.class);

        // =================================================================
        // HEADER — solid black banner with full-moon icon
        // =================================================================
        final int HEADER_H = 76;
        KindleLayoutKit.drawHeaderBar(g2d, HEADER_H);
        KindleLayoutKit.drawHeaderContent(g2d,
                iconBase, 50f, GLYPH_FULL_MOON,
                14, 65,
                "KHARKIV  MOON CALENDAR", 30, 72, 42,
                "7-day lunar forecast  \u2022  Kharkiv, Ukraine", 19, 72, 66);

        // =================================================================
        // TODAY — summary panel
        // =================================================================
        if (!calendar.days.isEmpty()) {
            drawTodayPanel(g2d, calendar.days.get(0), iconBase);
        }

        // =================================================================
        // COLUMN HEADERS
        // =================================================================
        final int TABLE_TOP = 176;
        KindleLayoutKit.drawColumnHeader(g2d, TABLE_TOP, 28,
                18,  "DAY",
                172, "PHASE",
                270, "ILLUMINATION",
                422, "SUNRISE / SUNSET");

        // =================================================================
        // 7-DAY TABLE
        // =================================================================
        final int ROW_TOP  = TABLE_TOP + 28;
        final int ROW_H    = (KindleCanvas.HEIGHT - ROW_TOP - 42) / 7;

        for (int i = 0; i < calendar.days.size(); i++) {
            MoonDay day = calendar.days.get(i);
            int y = ROW_TOP + i * ROW_H;

            // Row background
            KindleLayoutKit.drawRowBackground(g2d, i, 0, y, KindleCanvas.WIDTH, ROW_H);

            // Accent stripe on today's row
            if (i == 0) {
                KindleLayoutKit.drawRowAccentStripe(g2d, y, ROW_H, 6);
            }

            g2d.setColor(Color.BLACK);
            int baseline = y + ROW_H / 2 + 6;

            // Day name + number
            g2d.setFont(new Font("SansSerif", Font.BOLD, 20));
            g2d.drawString(day.date.format(DAY_NAME), 18, baseline - 10);
            g2d.setFont(new Font("SansSerif", Font.PLAIN, 16));
            g2d.drawString(day.date.format(DAY_NUM), 18, baseline + 10);

            // Moon icon
            if (iconBase != null) {
                float iconSize = Math.min(ROW_H - 6f, 44f);
                Font iconFont = iconBase.deriveFont(Font.PLAIN, iconSize);
                g2d.setFont(iconFont);
                FontMetrics fm = g2d.getFontMetrics(iconFont);
                int iconX = 120 + (48 - fm.stringWidth(day.phaseGlyph)) / 2;
                int iconY = y + (ROW_H + fm.getAscent() - fm.getDescent()) / 2;
                g2d.drawString(day.phaseGlyph, iconX, iconY);
            }

            // Phase name
            g2d.setFont(new Font("SansSerif", Font.BOLD, 16));
            g2d.drawString(day.phaseName, 172, baseline - 10);

            // Illumination progress bar
            KindleLayoutKit.drawProgressBar(g2d, day.illumination,
                    270, y + (ROW_H - 12) / 2, 130, 12,
                    false, false);
            // Percent label beside bar
            g2d.setColor(Color.BLACK);
            g2d.setFont(new Font("SansSerif", Font.PLAIN, 14));
            String illumStr = (int) Math.round(day.illumination * 100) + "%";
            g2d.drawString(illumStr, 406, y + (ROW_H - 12) / 2 + 12);

            // Sunrise / sunset with icons
            if (iconBase != null) {
                Font smallIcon = iconBase.deriveFont(Font.PLAIN, 18f);
                g2d.setFont(smallIcon);
                g2d.drawString(WeatherIconFont.glyph(GLYPH_SUNRISE), 422, baseline - 6);
                g2d.drawString(WeatherIconFont.glyph(GLYPH_SUNSET),  422, baseline + 16);
                g2d.setFont(new Font("SansSerif", Font.PLAIN, 16));
                g2d.drawString(day.sunrise, 446, baseline - 6);
                g2d.drawString(day.sunset,  446, baseline + 16);
            } else {
                g2d.setFont(new Font("SansSerif", Font.PLAIN, 15));
                g2d.drawString("\u2600 " + day.sunrise, 422, baseline - 6);
                g2d.drawString("\u263D " + day.sunset,  422, baseline + 16);
            }

            // Row divider
            KindleLayoutKit.drawRowDivider(g2d, y + ROW_H - 1);
        }

        // =================================================================
        // FOOTER
        // =================================================================
        final int FOOTER_TOP = KindleCanvas.HEIGHT - 40;
        KindleLayoutKit.drawFooterBar(g2d,
                "Weather Icons by Erik Flowers (SIL OFL)  \u2022  Open-Meteo API",
                FOOTER_TOP, 40);

        // Outer border — always last
        KindleLayoutKit.drawOuterBorder(g2d);
        g2d.dispose();
        return image;
    }

    /**
     * Renders the "today" phase summary panel that sits between the header and
     * the 7-day table.
     */
    private static void drawTodayPanel(Graphics2D g2d, MoonDay today, Font iconBase) {
        final int PANEL_Y = 76;
        final int PANEL_H = 100;

        // Light gray panel
        g2d.setColor(new Color(230, 230, 230));
        g2d.fillRect(0, PANEL_Y, KindleCanvas.WIDTH, PANEL_H);

        // Left accent bar
        g2d.setColor(Color.BLACK);
        g2d.fillRect(0, PANEL_Y, 6, PANEL_H);

        // Large moon icon
        if (iconBase != null) {
            g2d.setFont(iconBase.deriveFont(Font.PLAIN, 72f));
            g2d.setColor(Color.BLACK);
            g2d.drawString(today.phaseGlyph, 20, PANEL_Y + 86);
        }

        // TODAY label + phase name + illumination text
        g2d.setColor(Color.BLACK);
        g2d.setFont(new Font("SansSerif", Font.BOLD, 13));
        g2d.drawString("TODAY", 108, PANEL_Y + 20);

        g2d.setFont(new Font("SansSerif", Font.BOLD, 28));
        g2d.drawString(today.phaseName, 108, PANEL_Y + 52);

        g2d.setFont(new Font("SansSerif", Font.PLAIN, 20));
        g2d.drawString(String.format(Locale.ENGLISH,
                "Illumination: %d%%", (int) Math.round(today.illumination * 100)),
                108, PANEL_Y + 78);

        // Vertical illumination bar on the right
        KindleLayoutKit.drawVerticalBar(g2d, today.illumination,
                400, PANEL_Y + 16, 180, 68, true);
    }

    // -------------------------------------------------------------------------
    // Moon phase glyph / name mapping
    // -------------------------------------------------------------------------

    private static String moonPhaseGlyph(double phase) {
        int cp;
        if      (phase < 0.063)               cp = 0xF095; // new moon
        else if (phase < 0.188)               cp = 0xF097; // waxing crescent 2
        else if (phase < 0.313)               cp = 0xF09C; // first quarter
        else if (phase < 0.438)               cp = 0xF09F; // waxing gibbous 3
        else if (phase < 0.563)               cp = 0xF0A3; // full moon
        else if (phase < 0.688)               cp = 0xF0A6; // waning gibbous 3
        else if (phase < 0.813)               cp = 0xF0AA; // third quarter
        else if (phase < 0.938)               cp = 0xF0AD; // waning crescent 3
        else                                  cp = 0xF095; // new moon (cycle end)
        return WeatherIconFont.glyph(cp);
    }

    private static String moonPhaseName(double phase) {
        if      (phase < 0.063 || phase >= 0.938)  return "New Moon";
        else if (phase < 0.188)                     return "Waxing Crescent";
        else if (phase < 0.313)                     return "First Quarter";
        else if (phase < 0.438)                     return "Waxing Gibbous";
        else if (phase < 0.563)                     return "Full Moon";
        else if (phase < 0.688)                     return "Waning Gibbous";
        else if (phase < 0.813)                     return "Third Quarter";
        else                                        return "Waning Crescent";
    }

    // -------------------------------------------------------------------------
    // Date helper
    // -------------------------------------------------------------------------

    private static LocalDate parseDate(String value) {
        try {
            return LocalDate.parse(value, API_DATE);
        } catch (DateTimeParseException ex) {
            return LocalDate.now();
        }
    }

    // -------------------------------------------------------------------------
    // Data models
    // -------------------------------------------------------------------------

    private static class MoonCalendar {
        final List<MoonDay> days = new ArrayList<MoonDay>();
    }

    private static class MoonDay {
        LocalDate date         = LocalDate.now();
        String    phaseName    = "Unknown";
        String    phaseGlyph   = "";
        double    moonPhase;
        double    illumination;
        String    sunrise      = "--:--";
        String    sunset       = "--:--";
    }
}
