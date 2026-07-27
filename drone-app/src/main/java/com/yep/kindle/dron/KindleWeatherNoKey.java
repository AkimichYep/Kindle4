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
    private static final DateTimeFormatter DATE_LABEL = DateTimeFormatter.ofPattern("EEE, dd MMM", Locale.ENGLISH);
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
                + "&current=temperature_2m,weather_code,apparent_temperature,relative_humidity_2m,wind_speed_10m"
                + "&hourly=temperature_2m,weather_code"
                + "&daily=sunrise,sunset,temperature_2m_max,temperature_2m_min"
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
        // Grayscale image for richer visual shading on e-ink
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g2d = image.createGraphics();

        // Enable antialiasing for smooth shapes, but crisp text
        g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

        // --- BACKGROUND: white ---
        g2d.setColor(Color.WHITE);
        g2d.fillRect(0, 0, WIDTH, HEIGHT);

        // =====================================================================
        // HEADER BAR — filled black banner with white text
        // =====================================================================
        g2d.setColor(Color.BLACK);
        g2d.fillRect(0, 0, WIDTH, 110);

        // Large weather icon (white) on the black header
        boolean currentDay = isDaytime(snapshot.currentTime, snapshot.sunriseTime, snapshot.sunsetTime);
        Font iconBase = getWeatherIconBaseFont();
        if (iconBase != null) {
            Font bigIcon = iconBase.deriveFont(Font.PLAIN, 80f);
            g2d.setFont(bigIcon);
            g2d.setColor(Color.WHITE);
            String glyph = weatherIconGlyph(snapshot.currentCode, currentDay);
            FontMetrics fm = g2d.getFontMetrics(bigIcon);
            int iconY = 88;
            g2d.drawString(glyph, 18, iconY);

            // Condition label next to icon
            g2d.setFont(new Font("SansSerif", Font.BOLD, 34));
            g2d.drawString(shortCondition(snapshot.currentCode), 112, 56);

            // City name — right-aligned
            g2d.setFont(new Font("SansSerif", Font.PLAIN, 26));
            String city = "Kharkiv, UA";
            FontMetrics fmCity = g2d.getFontMetrics();
            g2d.drawString(city, WIDTH - fmCity.stringWidth(city) - 18, 56);

            // Date below condition in header
            g2d.setFont(new Font("SansSerif", Font.PLAIN, 22));
            g2d.drawString(snapshot.currentDate.format(DATE_LABEL), 112, 88);
        } else {
            g2d.setColor(Color.WHITE);
            g2d.setFont(new Font("SansSerif", Font.BOLD, 34));
            g2d.drawString(shortCondition(snapshot.currentCode), 20, 56);
            g2d.setFont(new Font("SansSerif", Font.PLAIN, 22));
            g2d.drawString("Kharkiv  " + snapshot.currentDate.format(DATE_LABEL), 20, 88);
        }

        // =====================================================================
        // TEMPERATURE BLOCK — huge centered temperature
        // =====================================================================
        g2d.setColor(Color.BLACK);
        // Main temperature — very large, centered
        String tempStr = formatTemp(snapshot.currentTemp);
        g2d.setFont(new Font("SansSerif", Font.BOLD, 148));
        FontMetrics fmTemp = g2d.getFontMetrics();
        int tempX = (WIDTH - fmTemp.stringWidth(tempStr)) / 2;
        g2d.drawString(tempStr, tempX, 278);

        // Feels-like and humidity on the same row, below main temp
        g2d.setFont(new Font("SansSerif", Font.PLAIN, 22));
        String feelsLike = "Feels " + formatTemp(snapshot.feelsLike);
        String humidity  = "Hum " + snapshot.humidity + "%";
        String wind      = "Wind " + snapshot.windSpeed + " km/h";
        g2d.drawString(feelsLike, 30, 310);
        g2d.drawString(humidity,  210, 310);
        g2d.drawString(wind,      380, 310);

        // Daily hi/lo strip
        g2d.setFont(new Font("SansSerif", Font.BOLD, 22));
        String hiLo = "\u25B2 " + formatTemp(snapshot.tempMax) + "   \u25BC " + formatTemp(snapshot.tempMin);
        FontMetrics fmHiLo = g2d.getFontMetrics();
        g2d.drawString(hiLo, (WIDTH - fmHiLo.stringWidth(hiLo)) / 2, 342);

        // Separator
        g2d.setStroke(new BasicStroke(2));
        g2d.drawLine(20, 360, WIDTH - 20, 360);

        // =====================================================================
        // CLOCK ROW
        // =====================================================================
        g2d.setFont(new Font("Monospaced", Font.BOLD, 130));
        String clockStr = snapshot.currentTime.format(API_TIME_LABEL);
        FontMetrics fmClock = g2d.getFontMetrics();
        int clockX = (WIDTH - fmClock.stringWidth(clockStr)) / 2;
        g2d.drawString(clockStr, clockX, 488);

        // Separator
        g2d.setStroke(new BasicStroke(2));
        g2d.drawLine(20, 502, WIDTH - 20, 502);

        // =====================================================================
        // HOURLY FORECAST STRIP — 4 slots
        // =====================================================================
        int slotW = (WIDTH - 40) / 4;  // 140px each
        int stripTop = 508;
        int stripBottom = 738;

        for (int i = 0; i < snapshot.slots.size(); i++) {
            HourSlot slot = snapshot.slots.get(i);
            int slotX = 20 + i * slotW;

            // Alternating shaded background for even slots
            if (i % 2 == 1) {
                g2d.setColor(new Color(220, 220, 220));
                g2d.fillRect(slotX, stripTop, slotW, stripBottom - stripTop);
                g2d.setColor(Color.BLACK);
            }

            // Vertical dividers
            if (i > 0) {
                g2d.setColor(Color.BLACK);
                g2d.setStroke(new BasicStroke(1));
                g2d.drawLine(slotX, stripTop, slotX, stripBottom);
            }

            // Time label — centered
            g2d.setColor(Color.BLACK);
            g2d.setFont(new Font("SansSerif", Font.BOLD, 22));
            FontMetrics fmSlot = g2d.getFontMetrics();
            int timeW = fmSlot.stringWidth(slot.timeLabel);
            g2d.drawString(slot.timeLabel, slotX + (slotW - timeW) / 2, stripTop + 26);

            // Weather icon — centered in slot
            boolean slotDay = isDaytime(slot.slotTime, snapshot.sunriseTime, snapshot.sunsetTime);
            if (iconBase != null) {
                Font slotIcon = iconBase.deriveFont(Font.PLAIN, 58f);
                g2d.setFont(slotIcon);
                FontMetrics fmIcon = g2d.getFontMetrics(slotIcon);
                String glyph = weatherIconGlyph(slot.weatherCode, slotDay);
                int glyphW = fmIcon.stringWidth(glyph);
                g2d.drawString(glyph, slotX + (slotW - glyphW) / 2, stripTop + 100);
            } else {
                drawWeatherIconGeometric(g2d, slot.weatherCode, slotDay,
                        slotX + 10, stripTop + 36, slotW - 20, 68);
            }

            // Temperature — large, centered
            g2d.setFont(new Font("SansSerif", Font.BOLD, 36));
            String t = formatTemp(slot.temperature);
            FontMetrics fmT = g2d.getFontMetrics();
            g2d.drawString(t, slotX + (slotW - fmT.stringWidth(t)) / 2, stripTop + 152);

            // Condition label — small, centered
            g2d.setFont(new Font("SansSerif", Font.PLAIN, 16));
            String cond = shortCondition(slot.weatherCode);
            FontMetrics fmCond = g2d.getFontMetrics();
            g2d.drawString(cond, slotX + (slotW - fmCond.stringWidth(cond)) / 2, stripTop + 178);
        }

        // =====================================================================
        // FOOTER — sunrise / sunset with icons
        // =====================================================================
        g2d.setColor(Color.BLACK);
        g2d.setStroke(new BasicStroke(2));
        g2d.drawLine(20, stripBottom + 2, WIDTH - 20, stripBottom + 2);

        int footerY = 786;
        if (iconBase != null) {
            Font footerIcon = iconBase.deriveFont(Font.PLAIN, 30f);
            g2d.setFont(footerIcon);
            // Sunrise icon + time
            g2d.drawString(new String(Character.toChars(0xF051)), 28, footerY);
            g2d.setFont(new Font("SansSerif", Font.BOLD, 26));
            g2d.drawString(snapshot.sunrise, 68, footerY);

            // Vertical separator
            g2d.setStroke(new BasicStroke(1));
            g2d.drawLine(WIDTH / 2, 748, WIDTH / 2, 800);

            // Sunset icon + time
            g2d.setFont(footerIcon);
            g2d.drawString(new String(Character.toChars(0xF052)), 315, footerY);
            g2d.setFont(new Font("SansSerif", Font.BOLD, 26));
            g2d.drawString(snapshot.sunset, 355, footerY);
        } else {
            g2d.setFont(new Font("SansSerif", Font.PLAIN, 24));
            g2d.drawString("Sunrise " + snapshot.sunrise + "   Sunset " + snapshot.sunset, 60, footerY);
        }

        // Outer border
        g2d.setStroke(new BasicStroke(3));
        g2d.drawRect(2, 2, WIDTH - 4, HEIGHT - 4);

        g2d.dispose();
        return image;
    }

    private static WeatherSnapshot parseWeatherSnapshot(String json) {
        WeatherSnapshot snapshot = new WeatherSnapshot();
        snapshot.currentTemp = extractDouble(json, "\"current\":\\{", "\"temperature_2m\":", 0.0);
        snapshot.currentCode = (int) extractDouble(json, "\"current\":\\{", "\"weather_code\":", 3);
        snapshot.feelsLike   = extractDouble(json, "\"current\":\\{", "\"apparent_temperature\":", snapshot.currentTemp);
        snapshot.humidity    = (int) extractDouble(json, "\"current\":\\{", "\"relative_humidity_2m\":", 0);
        snapshot.windSpeed   = (int) Math.round(extractDouble(json, "\"current\":\\{", "\"wind_speed_10m\":", 0));

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
        double[] maxTemps = parseDoubleArray(dailySection, "temperature_2m_max");
        double[] minTemps = parseDoubleArray(dailySection, "temperature_2m_min");

        snapshot.sunrise = formatTimeOnly(sunrises.length > 0 ? sunrises[0] : "--:--");
        snapshot.sunset = formatTimeOnly(sunsets.length > 0 ? sunsets[0] : "--:--");
        snapshot.sunriseTime = parseLabelTime(snapshot.sunrise);
        snapshot.sunsetTime = parseLabelTime(snapshot.sunset);
        snapshot.tempMax = maxTemps.length > 0 ? maxTemps[0] : snapshot.currentTemp;
        snapshot.tempMin = minTemps.length > 0 ? minTemps[0] : snapshot.currentTemp;
        return snapshot;
    }

    // -------------------------------------------------------------------------
    // Icon drawing
    // -------------------------------------------------------------------------

    private static void drawWeatherIconGeometric(Graphics2D g2d, int code, boolean isDaytime,
                                                  int x, int y, int w, int h) {
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

    // -------------------------------------------------------------------------
    // Geometric icon fallbacks
    // -------------------------------------------------------------------------

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

    // -------------------------------------------------------------------------
    // Utility helpers
    // -------------------------------------------------------------------------

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
        return String.format(Locale.ENGLISH, "%d\u00B0C", (int) Math.round(temp));
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

    // -------------------------------------------------------------------------
    // Data models
    // -------------------------------------------------------------------------

    private static class WeatherSnapshot {
        private double currentTemp;
        private int currentCode;
        private double feelsLike;
        private int humidity;
        private int windSpeed;
        private double tempMax;
        private double tempMin;
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
