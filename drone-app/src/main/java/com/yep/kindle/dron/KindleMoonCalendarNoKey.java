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
//"2026-07-27T04:57
    private static final DateTimeFormatter API_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter API_DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");
    private static final DateTimeFormatter DAY_LABEL = DateTimeFormatter.ofPattern("EEE dd MMM", Locale.ENGLISH);
    private static final DateTimeFormatter TIME_LABEL = DateTimeFormatter.ofPattern("HH:mm");

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

    private static String fetchMoonData() throws Exception {
        String urlString = "https://api.open-meteo.com/v1/forecast"
                + "?latitude=" + LAT
                + "&longitude=" + LON
                + "&daily=sunrise,sunset" // <-- REMOVED moonrise, moonset, and moon_phase
                + "&forecast_days=7"
                + "&timezone=auto";

        URL url = new URL(urlString);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("User-Agent", "KindleMoonCalendarNoKey/1.0");

        int responseCode = conn.getResponseCode();
        if (responseCode != 200) {
            String errorBody = readResponseBody(conn.getErrorStream());
            throw new RuntimeException("Failed: HTTP " + responseCode + " | URL: " + urlString + " | Body: " + errorBody);
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
        if (inputStream == null) {
            return "<empty>";
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
            StringBuilder body = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                body.append(line);
            }
            return body.toString();
        } catch (Exception ignored) {
            return "<unreadable>";
        }
    }

    // The exact average length of a lunar cycle in days
    private static final double LUNAR_CYCLE = 29.53058770576;

    // A known anchor date of a New Moon
    private static final LocalDate KNOWN_NEW_MOON = LocalDate.of(2000, 1, 6);

    /**
     * Calculates the moon phase for a given date.
     * @return A double between 0.0 and 1.0 representing the phase.
     */
    private static double calculateManualMoonPhase(LocalDate targetDate) {
        // Calculate total days between our known new moon and the target date
        long daysBetween = java.time.temporal.ChronoUnit.DAYS.between(KNOWN_NEW_MOON, targetDate);

        // Find the remainder of days within the current cycle
        double moonAgeDays = daysBetween % LUNAR_CYCLE;

        // Handle negative differences if calculating a date before Jan 6, 2000
        if (moonAgeDays < 0) {
            moonAgeDays += LUNAR_CYCLE;
        }

        // Normalize to a 0.0 - 1.0 scale
        return moonAgeDays / LUNAR_CYCLE;
    }

    private static MoonCalendar parseMoonCalendar(String json) {
        MoonCalendar calendar = new MoonCalendar();

        String dailySection = extractSectionObject(json, "\"daily\":");
        String[] days = parseStringArray(dailySection, "time");
        String[] sunrises = parseStringArray(dailySection, "sunrise");
        String[] sunsets = parseStringArray(dailySection, "sunset");

        // NO MORE API MOON PARSING NEEDED:
        // double[] moonPhases = parseDoubleArray(dailySection, "moon_phase");

        int count = Math.min(7, days.length);
        for (int i = 0; i < count; i++) {
            MoonDay item = new MoonDay();
            item.date = parseDate(days[i]);

            // CALCULATE PHASE MANUALLY HERE:
            item.moonPhaseValue = calculateManualMoonPhase(item.date);

            item.phaseName = moonPhaseName(item.moonPhaseValue);
            item.phaseGlyph = moonPhaseGlyph(item.moonPhaseValue);

            item.sunrise = formatTimeOnly(i < sunrises.length ? sunrises[i] : "--:--");
            item.sunset = formatTimeOnly(i < sunsets.length ? sunsets[i] : "--:--");

            // Hardcode or omit moonrise/moonset since the API doesn't provide it here
            item.moonrise = "--:--";
            item.moonset = "--:--";

            calendar.days.add(item);
        }

        return calendar;
    }

    private static BufferedImage renderMoonCalendar(MoonCalendar calendar) {
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_BYTE_BINARY);
        Graphics2D g2d = image.createGraphics();

        g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        g2d.setColor(Color.WHITE);
        g2d.fillRect(0, 0, WIDTH, HEIGHT);
        g2d.setColor(Color.BLACK);
        g2d.setStroke(new BasicStroke(2));
        g2d.drawRect(12, 12, WIDTH - 24, HEIGHT - 24);

        g2d.setFont(new Font("SansSerif", Font.BOLD, 38));
        g2d.drawString("KHARKIV MOON CALENDAR", 34, 58);
        g2d.setFont(new Font("SansSerif", Font.PLAIN, 20));
        g2d.drawString("Next 7 days", 34, 84);
        g2d.drawLine(20, 96, WIDTH - 20, 96);

        Font iconBase = getWeatherIconBaseFont();

        int rowTop = 104;
        int rowHeight = 92;
        for (int i = 0; i < calendar.days.size(); i++) {
            MoonDay day = calendar.days.get(i);
            int y = rowTop + i * rowHeight;

            if (i > 0) {
                g2d.setStroke(new BasicStroke(1));
                g2d.drawLine(20, y, WIDTH - 20, y);
            }

            g2d.setFont(new Font("SansSerif", Font.BOLD, 24));
            g2d.drawString(day.date.format(DAY_LABEL), 28, y + 30);

            if (iconBase != null) {
                Font iconFont = iconBase.deriveFont(Font.PLAIN, 42f);
                g2d.setFont(iconFont);
                FontMetrics fm = g2d.getFontMetrics(iconFont);
                int iconX = 230 + (50 - fm.stringWidth(day.phaseGlyph)) / 2;
                int iconY = y + 49;
                g2d.drawString(day.phaseGlyph, iconX, iconY);
            }

            g2d.setFont(new Font("SansSerif", Font.PLAIN, 20));
            g2d.drawString(day.phaseName, 292, y + 30);
            g2d.drawString("Phase " + String.format(Locale.ENGLISH, "%.2f", day.moonPhaseValue), 292, y + 54);
            g2d.drawString("Moon " + day.moonrise + " / " + day.moonset, 28, y + 78);
            g2d.drawString("Sun " + day.sunrise + " / " + day.sunset, 332, y + 78);
        }

        g2d.drawLine(20, 750, WIDTH - 20, 750);
        g2d.setFont(new Font("SansSerif", Font.PLAIN, 18));
        g2d.drawString("Moon icons from Weather Icons (local font)", 28, 782);

        g2d.dispose();
        return image;
    }

    // Maps Open-Meteo moon_phase [0..1] to weather-icons moon glyphs.
    private static String moonPhaseGlyph(double moonPhaseValue) {
        int codePoint;
        if (moonPhaseValue == 0.0 || moonPhaseValue == 1.0) {
            codePoint = 0xF095; // wi-moon-new
        } else if (moonPhaseValue < 0.25) {
            codePoint = 0xF098; // wi-moon-waxing-crescent-3
        } else if (moonPhaseValue == 0.25) {
            codePoint = 0xF09C; // wi-moon-first-quarter
        } else if (moonPhaseValue < 0.5) {
            codePoint = 0xF09F; // wi-moon-waxing-gibbous-3
        } else if (moonPhaseValue == 0.5) {
            codePoint = 0xF0A3; // wi-moon-full
        } else if (moonPhaseValue < 0.75) {
            codePoint = 0xF0A6; // wi-moon-waning-gibbous-3
        } else if (moonPhaseValue == 0.75) {
            codePoint = 0xF0AA; // wi-moon-third-quarter
        } else {
            codePoint = 0xF0AD; // wi-moon-waning-crescent-3
        }
        return new String(Character.toChars(codePoint));
    }

    private static String moonPhaseName(double moonPhaseValue) {
        if (moonPhaseValue == 0.0 || moonPhaseValue == 1.0) {
            return "New Moon";
        } else if (moonPhaseValue < 0.25) {
            return "Waxing Crescent";
        } else if (moonPhaseValue == 0.25) {
            return "First Quarter";
        } else if (moonPhaseValue < 0.5) {
            return "Waxing Gibbous";
        } else if (moonPhaseValue == 0.5) {
            return "Full Moon";
        } else if (moonPhaseValue < 0.75) {
            return "Waning Gibbous";
        } else if (moonPhaseValue == 0.75) {
            return "Third Quarter";
        }
        return "Waning Crescent";
    }

    private static synchronized Font getWeatherIconBaseFont() {
        if (weatherIconBaseFont != null) {
            return weatherIconBaseFont;
        }

        try (InputStream fontStream = KindleMoonCalendarNoKey.class.getClassLoader()
                .getResourceAsStream("font/weathericons-regular-webfont.ttf")) {
            if (fontStream == null) {
                return null;
            }
            weatherIconBaseFont = Font.createFont(Font.TRUETYPE_FONT, fontStream);
            return weatherIconBaseFont;
        } catch (IOException e) {
            return null;
        } catch (FontFormatException e) {
            return null;
        }
    }

    private static String extractSectionObject(String json, String keyToken) {
        int key = json.indexOf(keyToken);
        if (key < 0) {
            return "{}";
        }
        int start = json.indexOf('{', key);
        if (start < 0) {
            return "{}";
        }
        int depth = 0;
        for (int i = start; i < json.length(); i++) {
            char ch = json.charAt(i);
            if (ch == '{') {
                depth++;
            } else if (ch == '}') {
                depth--;
                if (depth == 0) {
                    return json.substring(start, i + 1);
                }
            }
        }
        return "{}";
    }

    private static String[] parseStringArray(String section, String key) {
        String raw = extractArrayRaw(section, key);
        if (raw.isEmpty()) {
            return new String[0];
        }
        String[] parts = raw.split(",");
        List<String> values = new ArrayList<String>();
        for (String part : parts) {
            String cleaned = part.trim();
            if (cleaned.startsWith("\"") && cleaned.endsWith("\"") && cleaned.length() >= 2) {
                cleaned = cleaned.substring(1, cleaned.length() - 1);
            }
            if (!cleaned.isEmpty()) {
                values.add(cleaned);
            }
        }
        return values.toArray(new String[0]);
    }

    private static double[] parseDoubleArray(String section, String key) {
        String raw = extractArrayRaw(section, key);
        if (raw.isEmpty()) {
            return new double[0];
        }
        String[] parts = raw.split(",");
        double[] values = new double[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                values[i] = Double.parseDouble(parts[i].trim());
            } catch (Exception e) {
                values[i] = 0;
            }
        }
        return values;
    }

    private static String extractArrayRaw(String section, String key) {
        String needle = "\"" + key + "\":[";
        int keyIdx = section.indexOf(needle);
        if (keyIdx < 0) {
            return "";
        }
        int arrayStart = keyIdx + needle.length();
        int arrayEnd = section.indexOf(']', arrayStart);
        if (arrayEnd < 0) {
            return "";
        }
        return section.substring(arrayStart, arrayEnd);
    }

    private static LocalDate parseDate(String value) {
        try {
            return LocalDate.parse(value, API_DATE);
        } catch (DateTimeParseException ex) {
            return LocalDate.now();
        }
    }

//    private static String formatTimeOnly(String isoDateTime) {
//        try {
//            return LocalDateTime.parse(isoDateTime, API_DATE_TIME).toLocalTime().format(TIME_LABEL);
//        } catch (Exception ignored) {
//            return "--:--";
//        }
//    }

    //private static final DateTimeFormatter API_DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");

    private static String formatTimeOnly(String isoDateTime) {
        try {
            return LocalDateTime.parse(isoDateTime, API_DATE_TIME).toLocalTime().format(TIME_LABEL);
        } catch (Exception ignored) {
            return "--:--"; // <--- Triggered if parsing fails!
        }
    }

    private static class MoonCalendar {
        private final List<MoonDay> days = new ArrayList<MoonDay>();
    }

    private static class MoonDay {
        private LocalDate date = LocalDate.now();
        private String phaseName = "Unknown";
        private String phaseGlyph = "";
        private double moonPhaseValue;
        private String moonrise = "--:--";
        private String moonset = "--:--";
        private String sunrise = "--:--";
        private String sunset = "--:--";
    }
}

