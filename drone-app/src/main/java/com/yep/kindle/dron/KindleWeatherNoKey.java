package com.yep.kindle.dron;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.io.IOException;
import java.awt.FontFormatException;

public class KindleWeatherNoKey {

    // Coordinates for Kharkiv, Ukraine
    private static final String LAT = "49.9884";
    private static final String LON = "36.2328";

    // Kindle 4 native resolution (Portrait: 600 width x 800 height)
    private static final int WIDTH = 600;
    private static final int HEIGHT = 800;
    private static final DateTimeFormatter API_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");
    private static final DateTimeFormatter API_TIME_LABEL = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DATE_LABEL = DateTimeFormatter.ofPattern("MM-dd");
    private static Font weatherIconBaseFont;

    public static void main(String[] args) {
        try {
            System.out.println("Fetching weather for Kharkiv from Open-Meteo (No API key required)...");
            String weatherJson = fetchWeatherData();
            WeatherSnapshot snapshot = parseWeatherSnapshot(weatherJson);

            System.out.println("Rendering e-ink layout...");
            BufferedImage img = generateEInkImage(snapshot);

            // Output image to push to Kindle
            File output = new File("kharkiv-weather.png");
            ImageIO.write(img, "png", output);
            System.out.println("Successfully generated image: " + output.getAbsolutePath());

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static String fetchWeatherData() throws Exception {
        // Open-Meteo endpoint requires zero authentication parameters
        String urlString = "https://api.open-meteo.com/v1/forecast"
                + "?latitude=" + LAT
                + "&longitude=" + LON
                // `time` is returned automatically and is not a selectable variable.
                + "&current=temperature_2m,weather_code"
                + "&hourly=temperature_2m,weather_code"
                + "&daily=sunrise,sunset"
                + "&forecast_days=2"
                + "&timezone=auto";

        URL url = new URL(urlString);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("User-Agent", "KindleWeatherNoKey/1.0");

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

    private static BufferedImage generateEInkImage(WeatherSnapshot snapshot) {
        // Create a 1-bit binary image palette optimized strictly for sharp e-ink screens
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_BYTE_BINARY);
        Graphics2D g2d = image.createGraphics();

        // Turn off text anti-aliasing for razor-sharp pixel representation on e-ink
        g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        // Fill background white
        g2d.setColor(Color.WHITE);
        g2d.fillRect(0, 0, WIDTH, HEIGHT);

        // Set drawing elements to black
        g2d.setColor(Color.BLACK);
        g2d.setStroke(new BasicStroke(2));
        g2d.drawRect(12, 12, WIDTH - 24, HEIGHT - 24);

        // Header block
        int headerTop = 34;
        boolean currentDay = isDaytime(snapshot.currentTime, snapshot.sunriseTime, snapshot.sunsetTime);
        drawWeatherIcon(g2d, snapshot.currentCode, currentDay, 34, headerTop + 4, 120, 100);
        g2d.setFont(new Font("SansSerif", Font.BOLD, 42));
        g2d.drawString(shortCondition(snapshot.currentCode), 172, 74);
        g2d.setFont(new Font("SansSerif", Font.PLAIN, 48));
        g2d.drawString("Kharkiv", 364, 80);
        g2d.setStroke(new BasicStroke(3));
        g2d.drawLine(20, 122, WIDTH - 20, 122);

        // Top metrics line
        g2d.setFont(new Font("SansSerif", Font.BOLD, 84));
        g2d.drawString(formatTemp(snapshot.currentTemp), 26, 218);
        g2d.setFont(new Font("Monospaced", Font.BOLD, 80));
        g2d.drawString(snapshot.currentDate.format(DATE_LABEL), 304, 214);

        g2d.drawLine(20, 240, WIDTH - 20, 240);

        // Main clock block
        g2d.setFont(new Font("Monospaced", Font.BOLD, 188));
        g2d.drawString(snapshot.currentTime.format(API_TIME_LABEL), 24, 470);
        g2d.drawLine(20, 510, WIDTH - 20, 510);

        // 4-slot hourly strip
        int slotY = 532;
        for (int i = 0; i < snapshot.slots.size(); i++) {
            HourSlot slot = snapshot.slots.get(i);
            int x = 20 + i * 140;
            if (i > 0) {
                g2d.setStroke(new BasicStroke(1));
                g2d.drawLine(x, 520, x, 748);
            }
            g2d.setFont(new Font("SansSerif", Font.PLAIN, 30));
            g2d.drawString(slot.timeLabel, x + 38, slotY + 26);
            boolean slotDay = isDaytime(slot.slotTime, snapshot.sunriseTime, snapshot.sunsetTime);
            drawWeatherIcon(g2d, slot.weatherCode, slotDay, x + 24, slotY + 36, 92, 74);
            g2d.setFont(new Font("SansSerif", Font.BOLD, 44));
            g2d.drawString(formatTemp(slot.temperature), x + 28, slotY + 180);
            g2d.setFont(new Font("SansSerif", Font.PLAIN, 20));
            g2d.drawString(shortCondition(slot.weatherCode), x + 10, slotY + 210);
        }

        // Footer details
        g2d.setStroke(new BasicStroke(2));
        g2d.drawLine(20, 748, WIDTH - 20, 748);
        Font iconBase = getWeatherIconBaseFont();
        if (iconBase != null) {
            Font footerIconFont = iconBase.deriveFont(Font.PLAIN, 24f);
            g2d.setFont(footerIconFont);
            g2d.drawString(new String(Character.toChars(0xF051)), 28, 782);
            g2d.drawString(new String(Character.toChars(0xF052)), 286, 782);
            g2d.setFont(new Font("SansSerif", Font.PLAIN, 24));
            g2d.drawString(snapshot.sunrise, 58, 782);
            g2d.drawString(snapshot.sunset, 316, 782);
        } else {
            g2d.setFont(new Font("SansSerif", Font.PLAIN, 24));
            g2d.drawString("Sunrise " + snapshot.sunrise + "  Sunset " + snapshot.sunset, 172, 782);
        }

        g2d.dispose();
        return image;
    }

    private static WeatherSnapshot parseWeatherSnapshot(String json) {
        WeatherSnapshot snapshot = new WeatherSnapshot();
        snapshot.currentTemp = extractDouble(json, "\"current\":\\{", "\"temperature_2m\":", 0.0);
        snapshot.currentCode = (int) extractDouble(json, "\"current\":\\{", "\"weather_code\":", 3);
        String currentIsoTime = extractString(json, "\"current\":\\{", "\"time\":", LocalDateTime.now().format(API_TIME));
        LocalDateTime currentDateTime = parseApiDateTime(currentIsoTime);
        snapshot.currentDate = currentDateTime.toLocalDate();
        snapshot.currentTime = currentDateTime.toLocalTime();

        String hourlySection = extractSectionObject(json, "\"hourly\":");
        String[] hourlyTimes = parseStringArray(hourlySection, "time");
        double[] hourlyTemps = parseDoubleArray(hourlySection, "temperature_2m");
        int[] hourlyCodes = parseIntArray(hourlySection, "weather_code");

        boolean hasHourlyTimes = hourlyTimes.length > 0;
        int currentIndex = hasHourlyTimes ? findTimeIndex(hourlyTimes, currentDateTime) : -1;
        int start = hasHourlyTimes ? Math.min(currentIndex + 1, hourlyTimes.length - 1) : -1;

        for (int i = 0; i < 4; i++) {
            HourSlot slot = new HourSlot();

            int idx = -1;
            if (hasHourlyTimes) {
                idx = Math.min(start + (i * 3), hourlyTimes.length - 1);
                slot.timeLabel = formatSlotLabel(hourlyTimes, idx);
                slot.slotTime = parseLabelTime(slot.timeLabel);
            } else {
                // Keep UI deterministic even if API omits hourly times.
                LocalTime fallbackTime = currentDateTime.plusHours((i + 1) * 3L).toLocalTime();
                slot.timeLabel = fallbackTime.format(API_TIME_LABEL);
                slot.slotTime = fallbackTime;
            }

            slot.temperature = idx >= 0 && idx < hourlyTemps.length ? hourlyTemps[idx] : snapshot.currentTemp;
            slot.weatherCode = idx >= 0 && idx < hourlyCodes.length ? hourlyCodes[idx] : snapshot.currentCode;
            snapshot.slots.add(slot);
        }

        String dailySection = extractSectionObject(json, "\"daily\":");
        String[] sunrises = parseStringArray(dailySection, "sunrise");
        String[] sunsets = parseStringArray(dailySection, "sunset");
        snapshot.sunrise = formatTimeOnly(sunrises.length > 0 ? sunrises[0] : "--:--");
        snapshot.sunset = formatTimeOnly(sunsets.length > 0 ? sunsets[0] : "--:--");
        snapshot.sunriseTime = parseLabelTime(snapshot.sunrise);
        snapshot.sunsetTime = parseLabelTime(snapshot.sunset);
        return snapshot;
    }

    private static void drawWeatherIcon(Graphics2D g2d, int code, boolean isDaytime, int x, int y, int w, int h) {
        Font iconBase = getWeatherIconBaseFont();
        if (iconBase != null) {
            String glyph = weatherIconGlyph(code, isDaytime);
            float fontSize = Math.max(32f, Math.min(w, h) * 0.9f);
            Font iconFont = iconBase.deriveFont(Font.PLAIN, fontSize);
            g2d.setFont(iconFont);
            FontMetrics fm = g2d.getFontMetrics(iconFont);
            int textX = x + (w - fm.stringWidth(glyph)) / 2;
            int textY = y + ((h - fm.getHeight()) / 2) + fm.getAscent();
            g2d.drawString(glyph, textX, textY);
            return;
        }

        // Fallback to geometric icons when font resources are unavailable.
        if (code == 0) {
            drawSun(g2d, x + w / 2 - 22, y + h / 2 - 22, 44);
        } else if (code == 1 || code == 2 || code == 3) {
            drawCloud(g2d, x + 8, y + 18, w - 16, h - 24, false);
        } else if (code == 45 || code == 48) {
            drawCloud(g2d, x + 8, y + 20, w - 16, h - 30, true);
        } else if ((code >= 51 && code <= 67) || (code >= 80 && code <= 82)) {
            drawCloud(g2d, x + 8, y + 12, w - 16, h - 28, false);
            drawRain(g2d, x + 20, y + h - 20, w - 40, 18);
        } else if (code >= 71 && code <= 77) {
            drawCloud(g2d, x + 8, y + 12, w - 16, h - 28, false);
            drawSnow(g2d, x + 22, y + h - 22, w - 44, 18);
        } else if (code >= 95) {
            drawCloud(g2d, x + 8, y + 12, w - 16, h - 28, false);
            drawLightning(g2d, x + (w / 2) - 9, y + h - 28);
        } else {
            drawCloud(g2d, x + 8, y + 18, w - 16, h - 24, false);
        }
    }

    private static synchronized Font getWeatherIconBaseFont() {
        if (weatherIconBaseFont != null) {
            return weatherIconBaseFont;
        }

        try (InputStream fontStream = KindleWeatherNoKey.class.getClassLoader()
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

    private static String weatherIconGlyph(int code, boolean isDaytime) {
        int codePoint;
        switch (code) {
            case 0:
                codePoint = isDaytime ? 0xF00D : 0xF02E;
                break;
            case 1:
            case 2:
            case 3:
                codePoint = isDaytime ? 0xF002 : 0xF031;
                break;
            case 45:
            case 48:
                codePoint = isDaytime ? 0xF003 : 0xF04A;
                break;
            case 51:
            case 53:
            case 55:
                codePoint = isDaytime ? 0xF00B : 0xF02B;
                break;
            case 56:
            case 57:
                codePoint = isDaytime ? 0xF0B2 : 0xF0B4;
                break;
            case 61:
            case 63:
            case 65:
                codePoint = isDaytime ? 0xF008 : 0xF028;
                break;
            case 66:
            case 67:
                codePoint = isDaytime ? 0xF006 : 0xF026;
                break;
            case 71:
            case 73:
            case 75:
            case 77:
            case 85:
            case 86:
                codePoint = isDaytime ? 0xF00A : 0xF02A;
                break;
            case 80:
            case 81:
            case 82:
                codePoint = isDaytime ? 0xF009 : 0xF029;
                break;
            case 95:
            case 96:
            case 99:
                codePoint = isDaytime ? 0xF010 : 0xF02D;
                break;
            default:
                codePoint = isDaytime ? 0xF002 : 0xF031;
                break;
        }
        return new String(Character.toChars(codePoint));
    }

    private static void drawCloud(Graphics2D g2d, int x, int y, int w, int h, boolean fog) {
        g2d.setStroke(new BasicStroke(3));
        int bubbleY = y + h / 2;
        g2d.drawOval(x + 4, bubbleY - 10, 34, 30);
        g2d.drawOval(x + 30, bubbleY - 24, 40, 36);
        g2d.drawOval(x + 58, bubbleY - 10, 34, 30);
        g2d.drawRoundRect(x + 8, bubbleY, w - 16, 24, 14, 14);
        if (fog) {
            g2d.setStroke(new BasicStroke(2));
            g2d.drawLine(x + 14, y + h - 8, x + w - 14, y + h - 8);
            g2d.drawLine(x + 18, y + h - 2, x + w - 18, y + h - 2);
        }
    }

    private static void drawRain(Graphics2D g2d, int x, int y, int w, int h) {
        g2d.setStroke(new BasicStroke(3));
        int step = Math.max(8, w / 4);
        for (int i = 0; i < 4; i++) {
            int dx = x + i * step;
            g2d.drawLine(dx, y, dx - 5, y + h);
        }
    }

    private static void drawSnow(Graphics2D g2d, int x, int y, int w, int h) {
        g2d.setStroke(new BasicStroke(2));
        int step = Math.max(10, w / 3);
        for (int i = 0; i < 3; i++) {
            int cx = x + i * step;
            int cy = y + h / 2;
            g2d.drawLine(cx - 4, cy, cx + 4, cy);
            g2d.drawLine(cx, cy - 4, cx, cy + 4);
            g2d.drawLine(cx - 3, cy - 3, cx + 3, cy + 3);
            g2d.drawLine(cx - 3, cy + 3, cx + 3, cy - 3);
        }
    }

    private static void drawLightning(Graphics2D g2d, int x, int y) {
        g2d.setStroke(new BasicStroke(3));
        int[] xs = {x, x + 10, x + 4, x + 16, x + 7};
        int[] ys = {y, y, y + 14, y + 14, y + 30};
        g2d.drawPolyline(xs, ys, xs.length);
    }

    private static void drawSun(Graphics2D g2d, int x, int y, int size) {
        g2d.setStroke(new BasicStroke(3));
        g2d.drawOval(x, y, size, size);
        int cx = x + size / 2;
        int cy = y + size / 2;
        for (int i = 0; i < 8; i++) {
            double a = Math.toRadians(i * 45);
            int x1 = cx + (int) (Math.cos(a) * (size / 2 + 3));
            int y1 = cy + (int) (Math.sin(a) * (size / 2 + 3));
            int x2 = cx + (int) (Math.cos(a) * (size / 2 + 12));
            int y2 = cy + (int) (Math.sin(a) * (size / 2 + 12));
            g2d.drawLine(x1, y1, x2, y2);
        }
    }

    private static String shortCondition(int code) {
        switch (code) {
            case 0: return "Clear";
            case 1:
            case 2:
            case 3: return "Cloudy";
            case 45:
            case 48: return "Fog";
            case 51:
            case 53:
            case 55: return "Drizzle";
            case 61:
            case 63:
            case 65:
            case 80:
            case 81:
            case 82: return "Rain";
            case 71:
            case 73:
            case 75:
            case 77: return "Snow";
            case 95:
            case 96:
            case 99: return "Storm";
            default: return "Overcast";
        }
    }

    private static String formatTemp(double temp) {
        return String.format(Locale.ENGLISH, "%d°C", (int) Math.round(temp));
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

    private static double extractDouble(String json, String objectKeyRegexLike, String valueKeyRegexLike, double fallback) {
        String objectKey = objectKeyRegexLike.replace("\\", "");
        String valueKey = valueKeyRegexLike.replace("\\", "");
        String section = extractSectionObject(json, objectKey);
        int key = section.indexOf(valueKey);
        if (key < 0) {
            return fallback;
        }
        int valueStart = key + valueKey.length();
        int valueEnd = valueStart;
        while (valueEnd < section.length()) {
            char c = section.charAt(valueEnd);
            if (!(Character.isDigit(c) || c == '-' || c == '.')) {
                break;
            }
            valueEnd++;
        }
        try {
            return Double.parseDouble(section.substring(valueStart, valueEnd));
        } catch (Exception e) {
            return fallback;
        }
    }

    private static String extractString(String json, String objectKeyRegexLike, String valueKeyRegexLike, String fallback) {
        String objectKey = objectKeyRegexLike.replace("\\", "");
        String valueKey = valueKeyRegexLike.replace("\\", "");
        String section = extractSectionObject(json, objectKey);
        int key = section.indexOf(valueKey);
        if (key < 0) {
            return fallback;
        }
        int firstQuote = section.indexOf('"', key + valueKey.length());
        if (firstQuote < 0) {
            return fallback;
        }
        int secondQuote = section.indexOf('"', firstQuote + 1);
        if (secondQuote < 0) {
            return fallback;
        }
        return section.substring(firstQuote + 1, secondQuote);
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

    private static int[] parseIntArray(String section, String key) {
        double[] doubles = parseDoubleArray(section, key);
        int[] ints = new int[doubles.length];
        for (int i = 0; i < doubles.length; i++) {
            ints[i] = (int) Math.round(doubles[i]);
        }
        return ints;
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

    private static LocalDateTime parseApiDateTime(String value) {
        try {
            return LocalDateTime.parse(value, API_TIME);
        } catch (DateTimeParseException ex) {
            return LocalDateTime.now();
        }
    }

    private static int findTimeIndex(String[] hourlyTimes, LocalDateTime current) {
        String expected = current.format(API_TIME);
        for (int i = 0; i < hourlyTimes.length; i++) {
            if (expected.equals(hourlyTimes[i])) {
                return i;
            }
        }

        // When current contains minutes (e.g. 11:45), pick the latest hourly bucket <= now.
        int bestIndex = -1;
        LocalDateTime bestTime = null;
        for (int i = 0; i < hourlyTimes.length; i++) {
            try {
                LocalDateTime slotTime = LocalDateTime.parse(hourlyTimes[i], API_TIME);
                if (!slotTime.isAfter(current) && (bestTime == null || slotTime.isAfter(bestTime))) {
                    bestTime = slotTime;
                    bestIndex = i;
                }
            } catch (Exception ignored) {
                // Keep scanning; malformed entries are skipped.
            }
        }
        if (bestIndex >= 0) {
            return bestIndex;
        }

        for (int i = 0; i < hourlyTimes.length; i++) {
            if (hourlyTimes[i].endsWith(current.toLocalTime().format(API_TIME_LABEL))) {
                return i;
            }
        }
        return 0;
    }

    private static String formatSlotLabel(String[] hourlyTimes, int idx) {
        if (idx < 0 || idx >= hourlyTimes.length) {
            return "--:--";
        }
        return formatTimeOnly(hourlyTimes[idx]);
    }

    private static String formatTimeOnly(String isoDateTime) {
        try {
            return LocalDateTime.parse(isoDateTime, API_TIME).toLocalTime().format(API_TIME_LABEL);
        } catch (Exception ignored) {
            return isoDateTime.length() >= 5 ? isoDateTime.substring(Math.max(0, isoDateTime.length() - 5)) : "--:--";
        }
    }

    // WMO Weather interpretation codes mapping for Open-Meteo
    private static String decodeWeatherCode(int code) {
        switch (code) {
            case 0: return "Clear Sky";
            case 1: case 2: case 3: return "Partly Cloudy";
            case 45: case 48: return "Foggy";
            case 51: case 53: case 55: return "Drizzle";
            case 61: case 63: case 65: return "Rain";
            case 71: case 73: case 75: return "Snow";
            case 95: case 96: case 99: return "Thunderstorm";
            default: return "Overcast";
        }
    }

    private static LocalTime parseLabelTime(String value) {
        try {
            return LocalTime.parse(value, API_TIME_LABEL);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean isDaytime(LocalTime time, LocalTime sunrise, LocalTime sunset) {
        if (time == null || sunrise == null || sunset == null) {
            return true;
        }
        if (sunrise.isBefore(sunset)) {
            return !time.isBefore(sunrise) && time.isBefore(sunset);
        }
        return !time.isBefore(sunrise) || time.isBefore(sunset);
    }

    private static class WeatherSnapshot {
        private double currentTemp;
        private int currentCode;
        private LocalDate currentDate = LocalDate.now();
        private LocalTime currentTime = LocalTime.now();
        private final List<HourSlot> slots = new ArrayList<HourSlot>();
        private String sunrise = "--:--";
        private String sunset = "--:--";
        private LocalTime sunriseTime;
        private LocalTime sunsetTime;
    }

    private static class HourSlot {
        private String timeLabel = "--:--";
        private double temperature;
        private int weatherCode;
        private LocalTime slotTime;
    }
}