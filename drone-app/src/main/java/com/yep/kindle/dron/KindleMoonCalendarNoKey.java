package com.yep.kindle.dron;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontFormatException;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class KindleMoonCalendarNoKey {

    private static final String LAT = "49.9884";
    private static final String LON = "36.2328";

    private static final int WIDTH = 600;
    private static final int HEIGHT = 800;

    private static final DateTimeFormatter API_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter API_DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");
    private static final DateTimeFormatter DAY_NAME = DateTimeFormatter.ofPattern("EEE", Locale.ENGLISH);
    private static final DateTimeFormatter DAY_NUM  = DateTimeFormatter.ofPattern("dd MMM", Locale.ENGLISH);
    private static final DateTimeFormatter TIME_LABEL = DateTimeFormatter.ofPattern("HH:mm");

    // Weather-icon codepoints for sun/moon in footer
    private static final int GLYPH_SUNRISE = 0xF051;
    private static final int GLYPH_SUNSET  = 0xF052;

    private static Font weatherIconBaseFont;

    public static void main(String[] args) {
        try {
            System.out.println("Fetching moon calendar for Kharkiv from Open-Meteo...");
            String json = fetchMoonData();
            MoonCalendar calendar = parseMoonCalendar(json);

            System.out.println("Rendering moon calendar...");
            BufferedImage image = renderMoonCalendar(calendar);

            File output = new File("kharkiv-moon-calendar.png");
            ImageIO.write(image, "png", output);
            System.out.println("Successfully generated image: " + output.getAbsolutePath());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // -------------------------------------------------------------------------
    // API fetch
    // -------------------------------------------------------------------------

    private static String fetchMoonData() throws Exception {
        String urlString = "https://api.open-meteo.com/v1/forecast"
                + "?latitude=" + LAT
                + "&longitude=" + LON
                + "&daily=sunrise,sunset"
                + "&forecast_days=7"
                + "&timezone=auto";

        URL url = new URL(urlString);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("User-Agent", "KindleMoonCalendarNoKey/1.0");

        int responseCode = conn.getResponseCode();
        if (responseCode != 200) {
            String errorBody = readResponseBody(conn.getErrorStream());
            throw new RuntimeException("Failed: HTTP " + responseCode + " | Body: " + errorBody);
        }

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }
            return response.toString();
        }
    }

    private static String readResponseBody(InputStream inputStream) {
        if (inputStream == null) return "<empty>";
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
            StringBuilder body = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) body.append(line);
            return body.toString();
        } catch (Exception ignored) {
            return "<unreadable>";
        }
    }

    // -------------------------------------------------------------------------
    // Moon phase calculation
    // -------------------------------------------------------------------------

    private static final double LUNAR_CYCLE = 29.53058770576;
    private static final LocalDate KNOWN_NEW_MOON = LocalDate.of(2000, 1, 6);

    private static double calculateManualMoonPhase(LocalDate targetDate) {
        long daysBetween = java.time.temporal.ChronoUnit.DAYS.between(KNOWN_NEW_MOON, targetDate);
        double moonAgeDays = daysBetween % LUNAR_CYCLE;
        if (moonAgeDays < 0) moonAgeDays += LUNAR_CYCLE;
        return moonAgeDays / LUNAR_CYCLE; // 0.0 = New Moon, 0.5 = Full Moon
    }

    /** Illumination fraction: 0 at new moon, 1 at full moon, back to 0. */
    private static double illuminationFraction(double phase) {
        return (1.0 - Math.cos(phase * 2 * Math.PI)) / 2.0;
    }

    // -------------------------------------------------------------------------
    // Parsing
    // -------------------------------------------------------------------------

    private static MoonCalendar parseMoonCalendar(String json) {
        MoonCalendar calendar = new MoonCalendar();

        String dailySection = extractSectionObject(json, "\"daily\":");
        String[] days = parseStringArray(dailySection, "time");
        String[] sunrises = parseStringArray(dailySection, "sunrise");
        String[] sunsets = parseStringArray(dailySection, "sunset");

        int count = Math.min(7, days.length);
        for (int i = 0; i < count; i++) {
            MoonDay item = new MoonDay();
            item.date = parseDate(days[i]);
            item.moonPhaseValue = calculateManualMoonPhase(item.date);
            item.illumination = illuminationFraction(item.moonPhaseValue);
            item.phaseName = moonPhaseName(item.moonPhaseValue);
            item.phaseGlyph = moonPhaseGlyph(item.moonPhaseValue);
            item.sunrise = formatTimeOnly(i < sunrises.length ? sunrises[i] : "--:--");
            item.sunset = formatTimeOnly(i < sunsets.length ? sunsets[i] : "--:--");
            item.moonrise = "--:--";
            item.moonset = "--:--";
            calendar.days.add(item);
        }
        return calendar;
    }

    // -------------------------------------------------------------------------
    // Rendering
    // -------------------------------------------------------------------------

    private static BufferedImage renderMoonCalendar(MoonCalendar calendar) {
        // Grayscale for richer shading on e-ink
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g2d = image.createGraphics();

        g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

        // White background
        g2d.setColor(Color.WHITE);
        g2d.fillRect(0, 0, WIDTH, HEIGHT);

        // =================================================================
        // HEADER — filled black bar
        // =================================================================
        g2d.setColor(Color.BLACK);
        g2d.fillRect(0, 0, WIDTH, 76);

        Font iconBase = getWeatherIconBaseFont();

        // Moon icon in header (full-moon glyph)
        if (iconBase != null) {
            Font headerIcon = iconBase.deriveFont(Font.PLAIN, 50f);
            g2d.setColor(Color.WHITE);
            g2d.setFont(headerIcon);
            g2d.drawString(new String(Character.toChars(0xF0A3)), 14, 65);
        }

        g2d.setColor(Color.WHITE);
        g2d.setFont(new Font("SansSerif", Font.BOLD, 30));
        g2d.drawString("KHARKIV  MOON CALENDAR", 72, 42);
        g2d.setFont(new Font("SansSerif", Font.PLAIN, 19));
        g2d.drawString("7-day lunar forecast  \u2022  Kharkiv, Ukraine", 72, 66);

        // Today's phase summary bar (just below header)
        if (!calendar.days.isEmpty()) {
            MoonDay today = calendar.days.get(0);
            drawTodayPhaseBar(g2d, today, iconBase);
        }

        // Column headers
        int tableTop = 176;
        g2d.setColor(new Color(50, 50, 50));
        g2d.fillRect(0, tableTop, WIDTH, 28);
        g2d.setColor(Color.WHITE);
        g2d.setFont(new Font("SansSerif", Font.BOLD, 14));
        g2d.drawString("DAY",        18, tableTop + 19);
        g2d.drawString("PHASE",     172, tableTop + 19);
        g2d.drawString("ILLUMINATION", 270, tableTop + 19);
        g2d.drawString("SUNRISE / SUNSET", 422, tableTop + 19);

        // =================================================================
        // ROWS — 7 days
        // =================================================================
        int rowTop = tableTop + 28;
        int rowH = (HEIGHT - rowTop - 42) / 7; // fit 7 rows + footer

        for (int i = 0; i < calendar.days.size(); i++) {
            MoonDay day = calendar.days.get(i);
            int y = rowTop + i * rowH;

            // Alternating row shading
            if (i % 2 == 0) {
                g2d.setColor(new Color(240, 240, 240));
                g2d.fillRect(0, y, WIDTH, rowH);
            } else {
                g2d.setColor(Color.WHITE);
                g2d.fillRect(0, y, WIDTH, rowH);
            }

            // Highlight today (row 0) with a slightly darker band
            if (i == 0) {
                g2d.setColor(new Color(200, 200, 200));
                g2d.fillRect(0, y, 6, rowH);  // accent left stripe
            }

            // Day name + number (left column)
            g2d.setColor(Color.BLACK);
            int textBaseline = y + rowH / 2 + 6;

            g2d.setFont(new Font("SansSerif", Font.BOLD, 20));
            g2d.drawString(day.date.format(DAY_NAME), 18, textBaseline - 10);
            g2d.setFont(new Font("SansSerif", Font.PLAIN, 16));
            g2d.drawString(day.date.format(DAY_NUM), 18, textBaseline + 10);

            // Moon icon (weather-icons font)
            if (iconBase != null) {
                float iconSize = Math.min(rowH - 6f, 44f);
                Font iconFont = iconBase.deriveFont(Font.PLAIN, iconSize);
                g2d.setFont(iconFont);
                FontMetrics fm = g2d.getFontMetrics(iconFont);
                String glyph = day.phaseGlyph;
                int iconX = 120 + (48 - fm.stringWidth(glyph)) / 2;
                int iconY = y + (rowH + fm.getAscent() - fm.getDescent()) / 2;
                g2d.drawString(glyph, iconX, iconY);
            }

            // Phase name
            g2d.setFont(new Font("SansSerif", Font.BOLD, 16));
            g2d.drawString(day.phaseName, 172, textBaseline - 10);

            // Illumination bar
            int barX = 270;
            int barW = 130;
            int barH = 12;
            int barY = y + (rowH - barH) / 2;
            g2d.setColor(new Color(200, 200, 200));
            g2d.fillRect(barX, barY, barW, barH);
            g2d.setColor(Color.BLACK);
            int fillW = (int) (day.illumination * barW);
            g2d.fillRect(barX, barY, fillW, barH);
            g2d.setColor(new Color(100, 100, 100));
            g2d.setStroke(new BasicStroke(1));
            g2d.drawRect(barX, barY, barW, barH);

            // Illumination percent
            g2d.setColor(Color.BLACK);
            g2d.setFont(new Font("SansSerif", Font.PLAIN, 14));
            String illumStr = String.format(Locale.ENGLISH, "%d%%", (int) Math.round(day.illumination * 100));
            g2d.drawString(illumStr, barX + barW + 6, barY + barH);

            // Sunrise / sunset (right column) — with icons if available
            if (iconBase != null) {
                Font smallIcon = iconBase.deriveFont(Font.PLAIN, 18f);
                g2d.setFont(smallIcon);
                g2d.drawString(new String(Character.toChars(GLYPH_SUNRISE)), 422, textBaseline - 6);
                g2d.drawString(new String(Character.toChars(GLYPH_SUNSET)),  422, textBaseline + 16);
                g2d.setFont(new Font("SansSerif", Font.PLAIN, 16));
                g2d.drawString(day.sunrise, 446, textBaseline - 6);
                g2d.drawString(day.sunset,  446, textBaseline + 16);
            } else {
                g2d.setFont(new Font("SansSerif", Font.PLAIN, 15));
                g2d.drawString("\u2600 " + day.sunrise, 422, textBaseline - 6);
                g2d.drawString("\u263D " + day.sunset,  422, textBaseline + 16);
            }

            // Row bottom divider
            g2d.setColor(new Color(180, 180, 180));
            g2d.setStroke(new BasicStroke(1));
            g2d.drawLine(0, y + rowH - 1, WIDTH, y + rowH - 1);
        }

        // =================================================================
        // FOOTER
        // =================================================================
        int footerY = HEIGHT - 40;
        g2d.setColor(new Color(50, 50, 50));
        g2d.fillRect(0, footerY, WIDTH, 40);
        g2d.setColor(Color.WHITE);
        g2d.setFont(new Font("SansSerif", Font.PLAIN, 15));
        g2d.drawString("Weather Icons by Erik Flowers (SIL OFL)  \u2022  Open-Meteo API", 18, footerY + 26);

        // Outer border
        g2d.setColor(Color.BLACK);
        g2d.setStroke(new BasicStroke(3));
        g2d.drawRect(2, 2, WIDTH - 4, HEIGHT - 4);

        g2d.dispose();
        return image;
    }

    /** Draws a "today's phase" summary panel between header and table. */
    private static void drawTodayPhaseBar(Graphics2D g2d, MoonDay today, Font iconBase) {
        int panelY = 76;
        int panelH = 100;

        // Light gray panel
        g2d.setColor(new Color(230, 230, 230));
        g2d.fillRect(0, panelY, WIDTH, panelH);

        // Decorative left accent bar
        g2d.setColor(Color.BLACK);
        g2d.fillRect(0, panelY, 6, panelH);

        // Big moon icon
        if (iconBase != null) {
            Font bigIcon = iconBase.deriveFont(Font.PLAIN, 72f);
            g2d.setFont(bigIcon);
            g2d.setColor(Color.BLACK);
            g2d.drawString(today.phaseGlyph, 20, panelY + 86);
        }

        // TODAY label
        g2d.setColor(Color.BLACK);
        g2d.setFont(new Font("SansSerif", Font.BOLD, 13));
        g2d.drawString("TODAY", 108, panelY + 20);

        // Phase name — large
        g2d.setFont(new Font("SansSerif", Font.BOLD, 28));
        g2d.drawString(today.phaseName, 108, panelY + 52);

        // Illumination
        g2d.setFont(new Font("SansSerif", Font.PLAIN, 20));
        String illumStr = String.format(Locale.ENGLISH, "Illumination: %d%%",
                (int) Math.round(today.illumination * 100));
        g2d.drawString(illumStr, 108, panelY + 78);

        // Big illumination bar on the right
        int barX = 400;
        int barY = panelY + 16;
        int barW = 180;
        int barH = 68;
        g2d.setColor(new Color(200, 200, 200));
        g2d.fillRect(barX, barY, barW, barH);
        g2d.setColor(Color.BLACK);
        int fillH = (int) (today.illumination * barH);
        g2d.fillRect(barX, barY + barH - fillH, barW, fillH);
        g2d.setColor(Color.BLACK);
        g2d.setStroke(new BasicStroke(2));
        g2d.drawRect(barX, barY, barW, barH);

        // Percent inside bar
        g2d.setColor(today.illumination > 0.5 ? Color.WHITE : Color.BLACK);
        g2d.setFont(new Font("SansSerif", Font.BOLD, 20));
        String pct = (int) Math.round(today.illumination * 100) + "%";
        FontMetrics fm = g2d.getFontMetrics();
        g2d.drawString(pct, barX + (barW - fm.stringWidth(pct)) / 2, barY + barH / 2 + 7);
    }

    // -------------------------------------------------------------------------
    // Moon phase glyphs and names
    // -------------------------------------------------------------------------

    private static String moonPhaseGlyph(double phase) {
        int cp;
        if (phase < 0.063) {
            cp = 0xF095; // new moon
        } else if (phase < 0.188) {
            cp = 0xF097; // waxing crescent 2
        } else if (phase < 0.313) {
            cp = 0xF09C; // first quarter
        } else if (phase < 0.438) {
            cp = 0xF09F; // waxing gibbous 3
        } else if (phase < 0.563) {
            cp = 0xF0A3; // full moon
        } else if (phase < 0.688) {
            cp = 0xF0A6; // waning gibbous 3
        } else if (phase < 0.813) {
            cp = 0xF0AA; // third quarter
        } else if (phase < 0.938) {
            cp = 0xF0AD; // waning crescent 3
        } else {
            cp = 0xF095; // new moon (cycle end)
        }
        return new String(Character.toChars(cp));
    }

    private static String moonPhaseName(double phase) {
        if (phase < 0.063 || phase >= 0.938) return "New Moon";
        if (phase < 0.188) return "Waxing Crescent";
        if (phase < 0.313) return "First Quarter";
        if (phase < 0.438) return "Waxing Gibbous";
        if (phase < 0.563) return "Full Moon";
        if (phase < 0.688) return "Waning Gibbous";
        if (phase < 0.813) return "Third Quarter";
        return "Waning Crescent";
    }

    // -------------------------------------------------------------------------
    // Font loader
    // -------------------------------------------------------------------------

    private static synchronized Font getWeatherIconBaseFont() {
        if (weatherIconBaseFont != null) return weatherIconBaseFont;
        try (InputStream fontStream = KindleMoonCalendarNoKey.class.getClassLoader()
                .getResourceAsStream("font/weathericons-regular-webfont.ttf")) {
            if (fontStream == null) return null;
            weatherIconBaseFont = Font.createFont(Font.TRUETYPE_FONT, fontStream);
            return weatherIconBaseFont;
        } catch (IOException | FontFormatException e) {
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // JSON parsing helpers
    // -------------------------------------------------------------------------

    private static String extractSectionObject(String json, String keyToken) {
        int key = json.indexOf(keyToken);
        if (key < 0) return "{}";
        int start = json.indexOf('{', key);
        if (start < 0) return "{}";
        int depth = 0;
        for (int i = start; i < json.length(); i++) {
            char ch = json.charAt(i);
            if (ch == '{') depth++;
            else if (ch == '}') {
                depth--;
                if (depth == 0) return json.substring(start, i + 1);
            }
        }
        return "{}";
    }

    private static String[] parseStringArray(String section, String key) {
        String raw = extractArrayRaw(section, key);
        if (raw.isEmpty()) return new String[0];
        String[] parts = raw.split(",");
        List<String> values = new ArrayList<String>();
        for (String part : parts) {
            String cleaned = part.trim();
            if (cleaned.startsWith("\"") && cleaned.endsWith("\"") && cleaned.length() >= 2)
                cleaned = cleaned.substring(1, cleaned.length() - 1);
            if (!cleaned.isEmpty()) values.add(cleaned);
        }
        return values.toArray(new String[0]);
    }

    private static String extractArrayRaw(String section, String key) {
        String needle = "\"" + key + "\":[";
        int keyIdx = section.indexOf(needle);
        if (keyIdx < 0) return "";
        int arrayStart = keyIdx + needle.length();
        int arrayEnd = section.indexOf(']', arrayStart);
        if (arrayEnd < 0) return "";
        return section.substring(arrayStart, arrayEnd);
    }

    private static LocalDate parseDate(String value) {
        try {
            return LocalDate.parse(value, API_DATE);
        } catch (DateTimeParseException ex) {
            return LocalDate.now();
        }
    }

    private static String formatTimeOnly(String isoDateTime) {
        try {
            return LocalDateTime.parse(isoDateTime, API_DATE_TIME).toLocalTime().format(TIME_LABEL);
        } catch (Exception ignored) {
            return "--:--";
        }
    }

    // -------------------------------------------------------------------------
    // Data models
    // -------------------------------------------------------------------------

    private static class MoonCalendar {
        private final List<MoonDay> days = new ArrayList<MoonDay>();
    }

    private static class MoonDay {
        private LocalDate date = LocalDate.now();
        private String phaseName = "Unknown";
        private String phaseGlyph = "";
        private double moonPhaseValue;
        private double illumination;
        private String moonrise = "--:--";
        private String moonset = "--:--";
        private String sunrise = "--:--";
        private String sunset = "--:--";
    }
}
