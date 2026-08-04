package com.yep.kindle.dron;

import com.yep.kindle.dron.display.KindleCanvas;
import com.yep.kindle.dron.display.KindleFormatUtils;
import com.yep.kindle.dron.display.KindleHttpClient;
import com.yep.kindle.dron.display.KindleJsonParser;
import com.yep.kindle.dron.display.KindleLayoutKit;
import com.yep.kindle.dron.display.WeatherIconFont;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Standalone Kindle e-ink weather image generator for Kharkiv, Ukraine.
 * <p>
 * Fetches live data from the Open-Meteo free API (no key required) and
 * renders a 600×800 grayscale PNG suitable for the Kindle 4 e-ink screen.
 * </p>
 * <p>
 * All canvas setup, font loading, and layout primitives are delegated to
 * the shared {@code drone-display} module classes:
 * {@link KindleCanvas}, {@link WeatherIconFont}, {@link KindleLayoutKit},
 * {@link KindleHttpClient}, {@link KindleJsonParser}, {@link KindleFormatUtils}.
 * </p>
 */
public class KindleWeatherNoKey {

    // Default coordinates: Kharkiv, Ukraine
    private static final String LAT = "49.9884";
    private static final String LON = "36.2328";
    private static final String DEFAULT_CITY_LABEL = "Kharkiv, UA";

    private static final DateTimeFormatter API_TIME       = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");
    private static final DateTimeFormatter API_TIME_LABEL = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DATE_LABEL     = DateTimeFormatter.ofPattern("EEE, dd MMM", Locale.ENGLISH);

    public static void main(String[] args) {
        try {
            generateAndSave("kharkiv-weather.png");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Fetch weather, render image, and save to the given path.
     * Called by KindleDroneDetectorPro for in-process image generation.
     */
    public static void generateAndSave(String outputPath) throws Exception {
        generateAndSave(outputPath, DEFAULT_CITY_LABEL, LAT, LON);
    }

    /** Geocode city by name, then generate and save the weather image. */
    public static void generateAndSaveForCity(String outputPath, String cityName) throws Exception {
        double[] coords = lookupCoords(cityName);
        String lat = String.valueOf(coords[0]);
        String lon = String.valueOf(coords[1]);
        generateAndSave(outputPath, cityName, lat, lon);
    }

    /** Generate weather image for explicit coordinates and city label. */
    public static void generateAndSave(String outputPath, String cityLabel, String lat, String lon)
            throws Exception {
        String weatherJson = fetchWeatherData(lat, lon);
        WeatherSnapshot snapshot = parseWeatherSnapshot(weatherJson);
        BufferedImage img = generateEInkImage(snapshot, cityLabel);
        ImageIO.write(img, "png", new File(outputPath));
    }

    // -------------------------------------------------------------------------
    // Data fetch
    // -------------------------------------------------------------------------

    private static String fetchWeatherData(String lat, String lon) throws Exception {
        String urlString = "https://api.open-meteo.com/v1/forecast"
                + "?latitude=" + lat + "&longitude=" + lon
                + "&current=temperature_2m,weather_code,apparent_temperature"
                +          ",relative_humidity_2m,wind_speed_10m"
                + "&hourly=temperature_2m,weather_code"
                + "&daily=sunrise,sunset,temperature_2m_max,temperature_2m_min"
                + "&forecast_days=2"
                + "&timezone=auto";
        return KindleHttpClient.get(urlString, "KindleWeatherNoKey/1.0");
    }

    /**
     * Geocode a city name to lat/lon using the Open-Meteo geocoding API (no key required).
     * Falls back to Kharkiv defaults on failure.
     */
    private static double[] lookupCoords(String cityName) throws Exception {
        String encoded = cityName.replace(" ", "%20");
        String url = "https://geocoding-api.open-meteo.com/v1/search?name="
                + encoded + "&count=1&language=en&format=json";
        String json = KindleHttpClient.get(url, "KindleWeatherNoKey/1.0");
        int resultsIdx = json.indexOf("\"results\"");
        if (resultsIdx < 0) throw new Exception("City not found: " + cityName);
        int arrOpen = json.indexOf('[', resultsIdx);
        if (arrOpen < 0) throw new Exception("City not found: " + cityName);
        // Find end of the results array to limit the search scope
        int firstObjOpen = json.indexOf('{', arrOpen);
        if (firstObjOpen < 0) throw new Exception("City not found: " + cityName);
        // Extract the first result object using brace depth tracking
        int depth = 0, objEnd = -1;
        for (int i = firstObjOpen; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') { depth--; if (depth == 0) { objEnd = i; break; } }
        }
        if (objEnd < 0) throw new Exception("City not found: " + cityName);
        String obj = json.substring(firstObjOpen, objEnd + 1);
        double lat = KindleJsonParser.getDoubleField(obj, "latitude");
        double lon = KindleJsonParser.getDoubleField(obj, "longitude");
        if (lat == 0.0 && lon == 0.0) throw new Exception("No coordinates for: " + cityName);
        return new double[]{lat, lon};
    }

    // -------------------------------------------------------------------------
    // Image rendering — delegates all chrome to KindleLayoutKit
    // -------------------------------------------------------------------------

    private static BufferedImage generateEInkImage(WeatherSnapshot snapshot, String cityLabel) {
        BufferedImage image = KindleCanvas.newImage();
        Graphics2D g2d = KindleCanvas.createGraphics(image);

        Font iconBase = WeatherIconFont.get(KindleWeatherNoKey.class);

        // =================================================================
        // HEADER — solid black banner, white icon + text
        // =================================================================
        final int HEADER_H = 110;
        KindleLayoutKit.drawHeaderBar(g2d, HEADER_H);

        boolean currentDay = isDaytime(snapshot.currentTime, snapshot.sunriseTime, snapshot.sunsetTime);
        int headerGlyph = weatherIconGlyph(snapshot.currentCode, currentDay);

        KindleLayoutKit.drawHeaderContent(g2d,
                iconBase, 80f, headerGlyph,
                18, 92,                                         // icon position
                shortCondition(snapshot.currentCode), 34, 112, 56,   // title
                cityLabel, 26, 400, 56);                        // subtitle (city, right area)

        // Date below condition (still in header)
        g2d.setColor(Color.WHITE);
        g2d.setFont(new Font("SansSerif", Font.PLAIN, 22));
        g2d.drawString(snapshot.currentDate.format(DATE_LABEL), 112, 90);

        // =================================================================
        // TEMPERATURE BLOCK
        // =================================================================
        g2d.setColor(Color.BLACK);
        String tempStr = KindleFormatUtils.formatTemp(snapshot.currentTemp);
        g2d.setFont(new Font("SansSerif", Font.BOLD, 148));
        FontMetrics fmTemp = g2d.getFontMetrics();
        g2d.drawString(tempStr, (KindleCanvas.WIDTH - fmTemp.stringWidth(tempStr)) / 2, 278);

        g2d.setFont(new Font("SansSerif", Font.PLAIN, 22));
        g2d.drawString("Feels " + KindleFormatUtils.formatTemp(snapshot.feelsLike), 30, 310);
        g2d.drawString("Hum " + snapshot.humidity + "%", 210, 310);
        g2d.drawString("Wind " + snapshot.windSpeed + " km/h", 380, 310);

        g2d.setFont(new Font("SansSerif", Font.BOLD, 22));
        String hiLo = "\u25B2 " + KindleFormatUtils.formatTemp(snapshot.tempMax)
                    + "   \u25BC " + KindleFormatUtils.formatTemp(snapshot.tempMin);
        FontMetrics fmHiLo = g2d.getFontMetrics();
        g2d.drawString(hiLo, (KindleCanvas.WIDTH - fmHiLo.stringWidth(hiLo)) / 2, 342);

        KindleLayoutKit.drawSeparator(g2d, 360);

        // =================================================================
        // CLOCK ROW
        // =================================================================
        g2d.setFont(new Font("Monospaced", Font.BOLD, 130));
        String clockStr = snapshot.currentTime.format(API_TIME_LABEL);
        FontMetrics fmClock = g2d.getFontMetrics();
        g2d.drawString(clockStr, (KindleCanvas.WIDTH - fmClock.stringWidth(clockStr)) / 2, 488);

        KindleLayoutKit.drawSeparator(g2d, 502);

        // =================================================================
        // HOURLY FORECAST STRIP — 4 slots
        // =================================================================
        final int SLOT_W     = (KindleCanvas.WIDTH - 40) / 4;
        final int STRIP_TOP  = 508;
        final int STRIP_BTM  = 738;

        for (int i = 0; i < snapshot.slots.size(); i++) {
            HourSlot slot = snapshot.slots.get(i);
            int slotX = 20 + i * SLOT_W;

            // Alternating shade
            KindleLayoutKit.drawRowBackground(g2d, i, slotX, STRIP_TOP, SLOT_W, STRIP_BTM - STRIP_TOP);
            g2d.setColor(Color.BLACK);

            // Vertical divider
            if (i > 0) {
                g2d.setStroke(new BasicStroke(1));
                g2d.drawLine(slotX, STRIP_TOP, slotX, STRIP_BTM);
            }

            // Time label
            g2d.setFont(new Font("SansSerif", Font.BOLD, 22));
            FontMetrics fmSlot = g2d.getFontMetrics();
            int timeW = fmSlot.stringWidth(slot.timeLabel);
            g2d.drawString(slot.timeLabel, slotX + (SLOT_W - timeW) / 2, STRIP_TOP + 26);

            // Weather icon
            boolean slotDay = isDaytime(slot.slotTime, snapshot.sunriseTime, snapshot.sunsetTime);
            if (iconBase != null) {
                Font slotIcon = iconBase.deriveFont(Font.PLAIN, 58f);
                g2d.setFont(slotIcon);
                FontMetrics fmIcon = g2d.getFontMetrics(slotIcon);
                String glyph = WeatherIconFont.glyph(weatherIconGlyph(slot.weatherCode, slotDay));
                int glyphW = fmIcon.stringWidth(glyph);
                g2d.drawString(glyph, slotX + (SLOT_W - glyphW) / 2, STRIP_TOP + 100);
            } else {
                drawWeatherIconGeometric(g2d, slot.weatherCode, slotDay,
                        slotX + 10, STRIP_TOP + 36, SLOT_W - 20, 68);
            }

            // Temperature
            g2d.setFont(new Font("SansSerif", Font.BOLD, 36));
            String t = KindleFormatUtils.formatTemp(slot.temperature);
            FontMetrics fmT = g2d.getFontMetrics();
            g2d.drawString(t, slotX + (SLOT_W - fmT.stringWidth(t)) / 2, STRIP_TOP + 152);

            // Condition label
            g2d.setFont(new Font("SansSerif", Font.PLAIN, 16));
            String cond = shortCondition(slot.weatherCode);
            FontMetrics fmCond = g2d.getFontMetrics();
            g2d.drawString(cond, slotX + (SLOT_W - fmCond.stringWidth(cond)) / 2, STRIP_TOP + 178);
        }

        // =================================================================
        // FOOTER — sunrise / sunset
        // =================================================================
        g2d.setColor(Color.BLACK);
        KindleLayoutKit.drawSeparator(g2d, STRIP_BTM + 2);

        final int FOOTER_Y = 786;
        if (iconBase != null) {
            Font footerIcon = iconBase.deriveFont(Font.PLAIN, 30f);
            g2d.setFont(footerIcon);
            g2d.setColor(Color.BLACK);
            g2d.drawString(WeatherIconFont.glyph(0xF051), 28, FOOTER_Y);
            g2d.setFont(new Font("SansSerif", Font.BOLD, 26));
            g2d.drawString(snapshot.sunrise, 68, FOOTER_Y);

            // Vertical mid-separator
            g2d.setStroke(new BasicStroke(1));
            g2d.drawLine(KindleCanvas.WIDTH / 2, STRIP_BTM + 2, KindleCanvas.WIDTH / 2, KindleCanvas.HEIGHT);

            g2d.setFont(footerIcon);
            g2d.drawString(WeatherIconFont.glyph(0xF052), 315, FOOTER_Y);
            g2d.setFont(new Font("SansSerif", Font.BOLD, 26));
            g2d.drawString(snapshot.sunset, 355, FOOTER_Y);
        } else {
            g2d.setFont(new Font("SansSerif", Font.PLAIN, 24));
            g2d.drawString("Sunrise " + snapshot.sunrise + "   Sunset " + snapshot.sunset,
                           60, FOOTER_Y);
        }

        // Outer border — always last
        KindleLayoutKit.drawOuterBorder(g2d);
        g2d.dispose();
        return image;
    }

    // -------------------------------------------------------------------------
    // Data parsing — delegates JSON work to KindleJsonParser
    // -------------------------------------------------------------------------

    private static WeatherSnapshot parseWeatherSnapshot(String json) {
        WeatherSnapshot s = new WeatherSnapshot();

        s.currentTemp = KindleJsonParser.extractDouble(json, "\"current\":", "\"temperature_2m\":", 0.0);
        s.currentCode = (int) KindleJsonParser.extractDouble(json, "\"current\":", "\"weather_code\":", 3);
        s.feelsLike   = KindleJsonParser.extractDouble(json, "\"current\":", "\"apparent_temperature\":", s.currentTemp);
        s.humidity    = (int) KindleJsonParser.extractDouble(json, "\"current\":", "\"relative_humidity_2m\":", 0);
        s.windSpeed   = (int) Math.round(
                KindleJsonParser.extractDouble(json, "\"current\":", "\"wind_speed_10m\":", 0));

        String currentIsoTime = KindleJsonParser.extractString(
                json, "\"current\":", "\"time\":", LocalDateTime.now().format(API_TIME));
        LocalDateTime currentDateTime = parseApiDateTime(currentIsoTime);
        s.currentDate = currentDateTime.toLocalDate();
        s.currentTime = currentDateTime.toLocalTime();

        String hourlySection = KindleJsonParser.extractSectionObject(json, "\"hourly\":");
        String[] hourlyTimes = KindleJsonParser.parseStringArray(hourlySection, "time");
        double[] hourlyTemps = KindleJsonParser.parseDoubleArray(hourlySection, "temperature_2m");
        int[]    hourlyCodes = KindleJsonParser.parseIntArray(hourlySection, "weather_code");

        boolean hasHourly = hourlyTimes.length > 0;
        int currentIdx = hasHourly ? findTimeIndex(hourlyTimes, currentDateTime) : -1;
        int start = hasHourly ? Math.min(currentIdx + 1, hourlyTimes.length - 1) : -1;

        for (int i = 0; i < 4; i++) {
            HourSlot slot = new HourSlot();
            int idx = -1;
            if (hasHourly) {
                idx = Math.min(start + (i * 3), hourlyTimes.length - 1);
                slot.timeLabel = KindleFormatUtils.formatTimeOnly(hourlyTimes[idx]);
                slot.slotTime  = parseLabelTime(slot.timeLabel);
            } else {
                LocalTime fb = currentDateTime.plusHours((i + 1) * 3L).toLocalTime();
                slot.timeLabel = fb.format(API_TIME_LABEL);
                slot.slotTime  = fb;
            }
            slot.temperature = (idx >= 0 && idx < hourlyTemps.length) ? hourlyTemps[idx] : s.currentTemp;
            slot.weatherCode = (idx >= 0 && idx < hourlyCodes.length) ? hourlyCodes[idx] : s.currentCode;
            s.slots.add(slot);
        }

        String dailySection = KindleJsonParser.extractSectionObject(json, "\"daily\":");
        String[] sunrises = KindleJsonParser.parseStringArray(dailySection, "sunrise");
        String[] sunsets  = KindleJsonParser.parseStringArray(dailySection, "sunset");
        double[] maxTemps = KindleJsonParser.parseDoubleArray(dailySection, "temperature_2m_max");
        double[] minTemps = KindleJsonParser.parseDoubleArray(dailySection, "temperature_2m_min");

        s.sunrise     = KindleFormatUtils.formatTimeOnly(sunrises.length > 0 ? sunrises[0] : "--:--");
        s.sunset      = KindleFormatUtils.formatTimeOnly(sunsets.length  > 0 ? sunsets[0]  : "--:--");
        s.sunriseTime = parseLabelTime(s.sunrise);
        s.sunsetTime  = parseLabelTime(s.sunset);
        s.tempMax     = maxTemps.length > 0 ? maxTemps[0] : s.currentTemp;
        s.tempMin     = minTemps.length > 0 ? minTemps[0] : s.currentTemp;
        return s;
    }

    // -------------------------------------------------------------------------
    // Icon glyph mapping
    // -------------------------------------------------------------------------

    private static int weatherIconGlyph(int code, boolean isDaytime) {
        switch (code) {
            case 0:           return isDaytime ? 0xF00D : 0xF02E;
            case 1: case 2:
            case 3:           return isDaytime ? 0xF002 : 0xF031;
            case 45: case 48: return isDaytime ? 0xF003 : 0xF04A;
            case 51: case 53:
            case 55:          return isDaytime ? 0xF00B : 0xF02B;
            case 56: case 57: return isDaytime ? 0xF0B2 : 0xF0B4;
            case 61: case 63:
            case 65:          return isDaytime ? 0xF008 : 0xF028;
            case 66: case 67: return isDaytime ? 0xF006 : 0xF026;
            case 71: case 73:
            case 75: case 77:
            case 85: case 86: return isDaytime ? 0xF00A : 0xF02A;
            case 80: case 81:
            case 82:          return isDaytime ? 0xF009 : 0xF029;
            case 95: case 96:
            case 99:          return isDaytime ? 0xF010 : 0xF02D;
            default:          return isDaytime ? 0xF002 : 0xF031;
        }
    }

    private static String shortCondition(int code) {
        switch (code) {
            case 0:                         return "Clear";
            case 1: case 2: case 3:         return "Cloudy";
            case 45: case 48:               return "Fog";
            case 51: case 53: case 55:      return "Drizzle";
            case 61: case 63: case 65:
            case 80: case 81: case 82:      return "Rain";
            case 71: case 73: case 75:
            case 77:                        return "Snow";
            case 95: case 96: case 99:      return "Storm";
            default:                        return "Overcast";
        }
    }

    // -------------------------------------------------------------------------
    // Geometric icon fallbacks (used when font resource is unavailable)
    // -------------------------------------------------------------------------

    private static void drawWeatherIconGeometric(Graphics2D g2d, int code, boolean isDaytime,
                                                  int x, int y, int w, int h) {
        if (code == 0) {
            drawSun(g2d, x + w / 2 - 22, y + h / 2 - 22, 44);
        } else if (code >= 95) {
            drawCloud(g2d, x + 8, y + 12, w - 16, h - 28, false);
            drawLightning(g2d, x + (w / 2) - 9, y + h - 28);
        } else if ((code >= 51 && code <= 67) || (code >= 80 && code <= 82)) {
            drawCloud(g2d, x + 8, y + 12, w - 16, h - 28, false);
            drawRain(g2d, x + 20, y + h - 20, w - 40, 18);
        } else if (code >= 71 && code <= 77) {
            drawCloud(g2d, x + 8, y + 12, w - 16, h - 28, false);
            drawSnow(g2d, x + 22, y + h - 22, w - 44, 18);
        } else if (code == 45 || code == 48) {
            drawCloud(g2d, x + 8, y + 20, w - 16, h - 30, true);
        } else {
            drawCloud(g2d, x + 8, y + 18, w - 16, h - 24, false);
        }
    }

    private static void drawCloud(Graphics2D g2d, int x, int y, int w, int h, boolean fog) {
        g2d.setStroke(new BasicStroke(3));
        int by = y + h / 2;
        g2d.drawOval(x + 4,  by - 10, 34, 30);
        g2d.drawOval(x + 30, by - 24, 40, 36);
        g2d.drawOval(x + 58, by - 10, 34, 30);
        g2d.drawRoundRect(x + 8, by, w - 16, 24, 14, 14);
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
            int cx = x + i * step, cy = y + h / 2;
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
        int cx = x + size / 2, cy = y + size / 2;
        for (int i = 0; i < 8; i++) {
            double a = Math.toRadians(i * 45);
            int x1 = cx + (int)(Math.cos(a) * (size / 2 + 3));
            int y1 = cy + (int)(Math.sin(a) * (size / 2 + 3));
            int x2 = cx + (int)(Math.cos(a) * (size / 2 + 12));
            int y2 = cy + (int)(Math.sin(a) * (size / 2 + 12));
            g2d.drawLine(x1, y1, x2, y2);
        }
    }

    // -------------------------------------------------------------------------
    // Time helpers
    // -------------------------------------------------------------------------

    private static LocalDateTime parseApiDateTime(String value) {
        try {
            return LocalDateTime.parse(value, API_TIME);
        } catch (DateTimeParseException ex) {
            return LocalDateTime.now();
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
        if (time == null || sunrise == null || sunset == null) return true;
        if (sunrise.isBefore(sunset)) return !time.isBefore(sunrise) && time.isBefore(sunset);
        return !time.isBefore(sunrise) || time.isBefore(sunset);
    }

    private static int findTimeIndex(String[] hourlyTimes, LocalDateTime current) {
        String expected = current.format(API_TIME);
        for (int i = 0; i < hourlyTimes.length; i++) {
            if (expected.equals(hourlyTimes[i])) return i;
        }
        int bestIndex = -1;
        LocalDateTime bestTime = null;
        for (int i = 0; i < hourlyTimes.length; i++) {
            try {
                LocalDateTime t = LocalDateTime.parse(hourlyTimes[i], API_TIME);
                if (!t.isAfter(current) && (bestTime == null || t.isAfter(bestTime))) {
                    bestTime = t;
                    bestIndex = i;
                }
            } catch (Exception ignored) {}
        }
        return bestIndex >= 0 ? bestIndex : 0;
    }

    // -------------------------------------------------------------------------
    // Data models
    // -------------------------------------------------------------------------

    private static class WeatherSnapshot {
        double currentTemp;
        int    currentCode;
        double feelsLike;
        int    humidity;
        int    windSpeed;
        double tempMax;
        double tempMin;
        LocalDate currentDate = LocalDate.now();
        LocalTime currentTime = LocalTime.now();
        final List<HourSlot> slots = new ArrayList<HourSlot>();
        String sunrise = "--:--";
        String sunset  = "--:--";
        LocalTime sunriseTime;
        LocalTime sunsetTime;
    }

    private static class HourSlot {
        String    timeLabel   = "--:--";
        double    temperature;
        int       weatherCode;
        LocalTime slotTime;
    }
}
