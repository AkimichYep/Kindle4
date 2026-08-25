package com.yep.kindle.dron;

import com.yep.kindle.dron.display.KindleCanvas;
import com.yep.kindle.dron.display.KindleLayoutKit;
import com.yep.kindle.dron.display.WeatherIconFont;
import com.yep.kindle.dron.util.AppLog;
import com.yep.kindle.dron.util.KindleUtils;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.WeekFields;
import java.util.Locale;

/**
 * Standalone Kindle e-ink Current Date & Time page generator.
 * <p>
 * Renders a 600×800 grayscale PNG displaying large digital clock,
 * full date, day of week, week number, time zone, and system info.
 * </p>
 */
public class KindleDateTime {

    private static final DateTimeFormatter TIME_LARGE = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter TIME_SEC   = DateTimeFormatter.ofPattern(":ss");
    private static final DateTimeFormatter DATE_FULL  = DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter DATE_SHORT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TIME_UTC   = DateTimeFormatter.ofPattern("HH:mm:ss 'UTC'");

    public static void main(String[] args) {
        AppLog.init(System.getProperty("kindle.log.file", "datetime.log"), 256 * 1024L);
        try {
            String out = args.length > 0 ? args[0] : "datetime.png";
            generateAndSave(out);
            AppLog.info("Date/time image saved: " + new File(out).getAbsolutePath());
        } catch (Exception e) {
            AppLog.exception("Date/time image generation failed", e);
        }
    }

    public static void generateAndSave(String outputPath) throws Exception {
        BufferedImage img = render();
        ImageIO.write(img, "png", new File(outputPath));
    }

    public static BufferedImage render() {
        KindleUtils.syncSystemTimeZone();
        BufferedImage img = KindleCanvas.newImage();
        Graphics2D g = KindleCanvas.createGraphics(img);

        ZoneId zone = ZoneId.systemDefault();
        ZonedDateTime now = ZonedDateTime.now(zone);
        ZonedDateTime utcNow = ZonedDateTime.now(ZoneId.of("UTC"));

        int W = KindleCanvas.WIDTH;

        // ── Header bar ────────────────────────────────────────────────────────
        final int HEADER_H = 110;
        KindleLayoutKit.drawHeaderBar(g, HEADER_H);
        Font iconFont = WeatherIconFont.get(KindleDateTime.class);

        // 0xF08B = time/clock glyph in WeatherIcons
        KindleLayoutKit.drawHeaderContent(g,
                iconFont, 50f, 0xF08B, 20, 75,
                "DATE & TIME", 34, 90, 58,
                "System Clock  ·  " + zone.getId(), 19, 90, 90);

        // ── Large Time Section ────────────────────────────────────────────────
        g.setColor(KindleLayoutKit.BLACK);
        String timeStr = now.format(TIME_LARGE);
        String secStr  = now.format(TIME_SEC);

        g.setFont(new Font("SansSerif", Font.BOLD, 130));
        FontMetrics fmTime = g.getFontMetrics();
        g.setFont(new Font("SansSerif", Font.PLAIN, 42));
        FontMetrics fmSec  = g.getFontMetrics();

        int totalWidth = fmTime.stringWidth(timeStr) + fmSec.stringWidth(secStr);
        int startX     = (W - totalWidth) / 2;

        g.setFont(new Font("SansSerif", Font.BOLD, 130));
        g.drawString(timeStr, startX, 260);

        g.setFont(new Font("SansSerif", Font.PLAIN, 42));
        g.drawString(secStr, startX + fmTime.stringWidth(timeStr), 230);

        KindleLayoutKit.drawSeparator(g, 290);

        // ── Full Date Section ─────────────────────────────────────────────────
        KindleLayoutKit.drawSectionHeader(g, iconFont, 0xF036, "CURRENT DATE", 305);

        String fullDate = now.format(DATE_FULL);
        g.setColor(KindleLayoutKit.BLACK);
        g.setFont(new Font("SansSerif", Font.BOLD, 28));
        FontMetrics fmDate = g.getFontMetrics();
        g.drawString(fullDate, (W - fmDate.stringWidth(fullDate)) / 2, 375);

        // ── Calendar Details Grid ─────────────────────────────────────────────
        int dayOfYear = now.getDayOfYear();
        int totalDays = now.toLocalDate().isLeapYear() ? 366 : 365;
        int weekNum   = now.get(WeekFields.of(Locale.getDefault()).weekOfWeekBasedYear());

        KindleLayoutKit.drawSeparator(g, 410);

        KindleLayoutKit.drawSectionHeader(g, iconFont, 0xF073, "CALENDAR DETAILS", 425);

        int y = 490;
        int rowH = 38;

        drawDetailRow(g, "ISO Date:", now.format(DATE_SHORT), y); y += rowH;
        drawDetailRow(g, "Day of Year:", dayOfYear + " of " + totalDays, y); y += rowH;
        drawDetailRow(g, "Week Number:", "Week " + weekNum, y); y += rowH;
        drawDetailRow(g, "UTC Time:", utcNow.format(TIME_UTC), y); y += rowH;

        // ── Timezone & System Info ────────────────────────────────────────────
        KindleLayoutKit.drawSeparator(g, y + 10);
        y += 25;

        KindleLayoutKit.drawSectionHeader(g, iconFont, 0xF08A, "TIMEZONE & LOCATION", y);
        y += 65;

        String offset = now.getOffset().getId();
        if (offset.equals("Z")) offset = "+00:00";
        drawDetailRow(g, "Time Zone:", zone.getId() + " (" + offset + ")", y); y += rowH;

        // ── Footer ────────────────────────────────────────────────────────────
        KindleLayoutKit.drawFooterBar(g, "Kindle Drone Detector  ·  Date & Time View", 752, 48);
        KindleLayoutKit.drawOuterBorder(g);

        g.dispose();
        return img;
    }

    private static void drawDetailRow(Graphics2D g, String label, String value, int y) {
        g.setColor(new Color(90, 90, 90));
        g.setFont(new Font("SansSerif", Font.PLAIN, 20));
        g.drawString(label, 40, y);

        g.setColor(KindleLayoutKit.BLACK);
        g.setFont(new Font("SansSerif", Font.BOLD, 21));
        FontMetrics fm = g.getFontMetrics();
        g.drawString(value, KindleCanvas.WIDTH - 40 - fm.stringWidth(value), y);
    }
}
