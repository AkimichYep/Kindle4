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
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class KindleSpaceWeatherNoKey {

    private static final String SOLAR_REGIONS_URL = "https://services.swpc.noaa.gov/json/solar_regions.json";
    private static final String XRAYS_URL = "https://services.swpc.noaa.gov/json/goes/primary/xrays-6-hour.json";
    private static final String KP_INDEX_URL = "https://services.swpc.noaa.gov/json/planetary_k_index_1m.json";

    private static final int WIDTH = 600;
    private static final int HEIGHT = 800;

    // Weather-icon glyphs that work for space weather context
    private static final int GLYPH_LIGHTNING  = 0xF016; // wi-lightning
    private static final int GLYPH_SUN_HIGH   = 0xF00D; // wi-day-sunny
    private static final int GLYPH_HOT        = 0xF072; // wi-hot
    private static final int GLYPH_SOLAR_ECLIPSE = 0xF06E; // wi-solar-eclipse

    private static Font weatherIconBaseFont;

    public static void main(String[] args) {
        try {
            System.out.println("Fetching NOAA space weather data...");
            List<SolarRegion> regions = fetchSolarRegions();
            List<XRayFlux> xrays = fetchXRayFlux();
            List<KpIndex> kpList = fetchKpIndex();

            System.out.println("Rendering space weather image for Kindle...");
            BufferedImage image = renderSpaceWeatherImage(regions, xrays, kpList);

            File output = new File("kindle-space-weather.png");
            ImageIO.write(image, "png", output);
            System.out.println("Successfully generated image: " + output.getAbsolutePath());

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // -------------------------------------------------------------------------
    // Font loader
    // -------------------------------------------------------------------------

    private static synchronized Font getWeatherIconBaseFont() {
        if (weatherIconBaseFont != null) return weatherIconBaseFont;
        try (InputStream fontStream = KindleSpaceWeatherNoKey.class.getClassLoader()
                .getResourceAsStream("font/weathericons-regular-webfont.ttf")) {
            if (fontStream == null) return null;
            weatherIconBaseFont = Font.createFont(Font.TRUETYPE_FONT, fontStream);
            return weatherIconBaseFont;
        } catch (IOException | FontFormatException e) {
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // Image Renderer (Kindle 600x800 B&W Layout)
    // -------------------------------------------------------------------------

    private static BufferedImage renderSpaceWeatherImage(
            List<SolarRegion> regions, List<XRayFlux> xrays, List<KpIndex> kpList) {

        // Grayscale for richer shading
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g2d = image.createGraphics();

        g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

        // White background
        g2d.setColor(Color.WHITE);
        g2d.fillRect(0, 0, WIDTH, HEIGHT);

        Font iconBase = getWeatherIconBaseFont();

        // =================================================================
        // HEADER — filled black bar with solar icon
        // =================================================================
        g2d.setColor(Color.BLACK);
        g2d.fillRect(0, 0, WIDTH, 80);

        // Solar icon in header
        if (iconBase != null) {
            Font headerIcon = iconBase.deriveFont(Font.PLAIN, 50f);
            g2d.setColor(Color.WHITE);
            g2d.setFont(headerIcon);
            g2d.drawString(new String(Character.toChars(GLYPH_HOT)), 14, 65);
        }

        g2d.setColor(Color.WHITE);
        g2d.setFont(new Font("SansSerif", Font.BOLD, 28));
        g2d.drawString("SPACE WEATHER DASHBOARD", 76, 42);

        g2d.setFont(new Font("SansSerif", Font.PLAIN, 16));
        String nowStr = LocalDateTime.now().format(
                DateTimeFormatter.ofPattern("EEE dd MMM yyyy  HH:mm", Locale.ENGLISH));
        g2d.drawString(nowStr + "  \u2022  NOAA SWPC", 76, 66);

        // =================================================================
        // SECTION 1 — GEOMAGNETIC ACTIVITY (Kp INDEX)
        // =================================================================
        double latestKp = 0.0;
        String kpTime = "--:--";
        if (!kpList.isEmpty()) {
            KpIndex last = kpList.get(kpList.size() - 1);
            latestKp = last.getKpIndex();
            kpTime = formatTimeOnly(last.getTimeTag());
        }

        int sec1Y = 82;
        drawSectionHeader(g2d, iconBase, GLYPH_LIGHTNING,
                "1.  GEOMAGNETIC ACTIVITY  (Kp INDEX)", sec1Y);

        // Kp value — large
        g2d.setColor(Color.BLACK);
        g2d.setFont(new Font("SansSerif", Font.BOLD, 58));
        g2d.drawString(String.format(Locale.ENGLISH, "Kp %.1f", latestKp), 24, sec1Y + 80);

        // Status text + time (right side)
        g2d.setFont(new Font("SansSerif", Font.BOLD, 18));
        String status = getKpStatusText(latestKp);
        g2d.drawString(status, 230, sec1Y + 52);
        g2d.setFont(new Font("SansSerif", Font.PLAIN, 16));
        g2d.drawString("Time: " + kpTime + " UTC", 230, sec1Y + 74);

        // Kp alert indicator (filled circle with level)
        drawKpAlertBadge(g2d, latestKp, 490, sec1Y + 44);

        // Kp bar with tick marks (0–9)
        drawKpBar(g2d, latestKp, 24, sec1Y + 92, 552, 26);

        // Section divider
        g2d.setColor(new Color(80, 80, 80));
        g2d.setStroke(new BasicStroke(1.5f));
        g2d.drawLine(14, sec1Y + 130, WIDTH - 14, sec1Y + 130);

        // =================================================================
        // SECTION 2 — X-RAY FLUX (SOLAR FLARES)
        // =================================================================
        double latestFlux = 0.0;
        String xrayTime = "--:--";
        if (!xrays.isEmpty()) {
            XRayFlux last = xrays.get(xrays.size() - 1);
            latestFlux = last.getFlux();
            xrayTime = formatTimeOnly(last.getTimeTag());
        }
        String flareClass = calculateFlareClass(latestFlux);

        int sec2Y = sec1Y + 132;
        drawSectionHeader(g2d, iconBase, GLYPH_SUN_HIGH,
                "2.  X-RAY FLUX  (SOLAR FLARES)", sec2Y);

        // Flare class — large
        g2d.setColor(Color.BLACK);
        g2d.setFont(new Font("SansSerif", Font.BOLD, 58));
        g2d.drawString("Class " + flareClass, 24, sec2Y + 80);

        // Flux value + time on the right
        g2d.setFont(new Font("SansSerif", Font.PLAIN, 16));
        g2d.drawString(String.format(Locale.ENGLISH, "Flux: %.2e W/m\u00B2", latestFlux), 24, sec2Y + 100);
        g2d.drawString("Time: " + xrayTime + " UTC", 310, sec2Y + 100);

        // Flare severity badge
        drawFlareBadge(g2d, flareClass, 490, sec2Y + 44);

        // Section divider
        g2d.setColor(new Color(80, 80, 80));
        g2d.setStroke(new BasicStroke(1.5f));
        g2d.drawLine(14, sec2Y + 116, WIDTH - 14, sec2Y + 116);

        // =================================================================
        // SECTION 3 — ACTIVE SUNSPOT REGIONS
        // =================================================================
        int sec3Y = sec2Y + 118;
        drawSectionHeader(g2d, iconBase, GLYPH_SOLAR_ECLIPSE,
                "3.  ACTIVE SUNSPOT REGIONS  (" + regions.size() + ")", sec3Y);

        // Table header row
        int tblTop = sec3Y + 32;
        g2d.setColor(new Color(60, 60, 60));
        g2d.fillRect(14, tblTop, WIDTH - 28, 22);
        g2d.setColor(Color.WHITE);
        g2d.setFont(new Font("SansSerif", Font.BOLD, 13));
        g2d.drawString("REGION",  20, tblTop + 15);
        g2d.drawString("LOC",    120, tblTop + 15);
        g2d.drawString("SPOTS",  200, tblTop + 15);
        g2d.drawString("AREA",   290, tblTop + 15);
        g2d.drawString("CLASS",  390, tblTop + 15);
        g2d.drawString("DATE",   480, tblTop + 15);

        // Table rows
        g2d.setFont(new Font("SansSerif", Font.PLAIN, 15));
        int rowY = tblTop + 22;
        int rowH = 28;
        int maxDisplay = Math.min(8, regions.size());

        if (regions.isEmpty()) {
            g2d.setColor(Color.BLACK);
            g2d.setFont(new Font("SansSerif", Font.PLAIN, 16));
            g2d.drawString("No active sunspot regions currently reported.", 20, rowY + 20);
        } else {
            for (int i = 0; i < maxDisplay; i++) {
                SolarRegion r = regions.get(i);

                // Alternating row background
                if (i % 2 == 0) {
                    g2d.setColor(new Color(240, 240, 240));
                    g2d.fillRect(14, rowY, WIDTH - 28, rowH);
                }

                g2d.setColor(Color.BLACK);
                g2d.setFont(new Font("SansSerif", Font.BOLD, 15));
                g2d.drawString("#" + r.getRegion(), 20, rowY + 19);

                g2d.setFont(new Font("SansSerif", Font.PLAIN, 15));
                g2d.drawString(r.getLocation().isEmpty() ? "--" : r.getLocation(), 120, rowY + 19);
                g2d.drawString(String.valueOf(r.getNumberSpots()), 200, rowY + 19);
                g2d.drawString(String.valueOf(r.getArea()),        290, rowY + 19);

                // Spot class — bold if complex
                String sc = r.getSpotClass().isEmpty() ? "--" : r.getSpotClass();
                boolean complex = sc.startsWith("Beta-Gamma") || sc.startsWith("Delta") || sc.contains("Gamma");
                if (complex) {
                    g2d.setFont(new Font("SansSerif", Font.BOLD, 15));
                }
                g2d.drawString(sc, 390, rowY + 19);

                g2d.setFont(new Font("SansSerif", Font.PLAIN, 13));
                String shortDate = r.getObservedDate().length() >= 10
                        ? r.getObservedDate().substring(5, 10) : r.getObservedDate();
                g2d.drawString(shortDate, 480, rowY + 19);

                rowY += rowH;
            }
        }

        // =================================================================
        // FOOTER
        // =================================================================
        int footerY = HEIGHT - 36;
        g2d.setColor(new Color(50, 50, 50));
        g2d.fillRect(0, footerY, WIDTH, 36);
        g2d.setColor(Color.WHITE);
        g2d.setFont(new Font("SansSerif", Font.PLAIN, 14));
        g2d.drawString("Source: NOAA Space Weather Prediction Center  (services.swpc.noaa.gov)", 14, footerY + 24);

        // Outer border
        g2d.setColor(Color.BLACK);
        g2d.setStroke(new BasicStroke(3));
        g2d.drawRect(2, 2, WIDTH - 4, HEIGHT - 4);

        g2d.dispose();
        return image;
    }

    /** Draws a filled section header bar with optional weather icon glyph. */
    private static void drawSectionHeader(Graphics2D g2d, Font iconBase,
                                           int glyphCode, String title, int y) {
        // Light gray section header
        g2d.setColor(new Color(185, 185, 185));
        g2d.fillRect(0, y, WIDTH, 28);

        // Left accent stripe
        g2d.setColor(Color.BLACK);
        g2d.fillRect(0, y, 5, 28);

        // Icon
        if (iconBase != null) {
            Font sectionIcon = iconBase.deriveFont(Font.PLAIN, 18f);
            g2d.setFont(sectionIcon);
            g2d.setColor(Color.BLACK);
            g2d.drawString(new String(Character.toChars(glyphCode)), 10, y + 21);
        }

        // Title text
        g2d.setFont(new Font("SansSerif", Font.BOLD, 15));
        g2d.setColor(Color.BLACK);
        g2d.drawString(title, 34, y + 20);
    }

    /**
     * Draws the Kp index bar (0–9) with tick marks, threshold labels,
     * and a storm-level color gradient via shading.
     */
    private static void drawKpBar(Graphics2D g2d, double kp,
                                   int x, int y, int w, int h) {
        // Background track
        g2d.setColor(new Color(220, 220, 220));
        g2d.fillRect(x, y, w, h);

        // Filled portion — darker as kp rises
        int fillW = (int) Math.min(w, (kp / 9.0) * w);
        // Gradient effect: mild kp = medium gray, high kp = black
        int shade = Math.max(0, 180 - (int)(kp / 9.0 * 180));
        g2d.setColor(new Color(shade, shade, shade));
        g2d.fillRect(x, y, fillW, h);

        // G-storm threshold lines (G1=Kp5, G2=Kp6, G3=Kp7, G4=Kp8, G5=Kp9)
        g2d.setColor(new Color(100, 100, 100));
        g2d.setStroke(new BasicStroke(1f));
        for (int level = 1; level <= 8; level++) {
            int tx = x + (int)((level / 9.0) * w);
            g2d.drawLine(tx, y, tx, y + h);
        }

        // Border
        g2d.setColor(Color.BLACK);
        g2d.setStroke(new BasicStroke(1.5f));
        g2d.drawRect(x, y, w, h);

        // Tick labels below bar (0, 3, 5, 7, 9)
        g2d.setFont(new Font("SansSerif", Font.PLAIN, 12));
        int[] ticks = {0, 3, 5, 7, 9};
        for (int t : ticks) {
            int tx = x + (int)((t / 9.0) * w);
            g2d.drawString(String.valueOf(t), tx - 4, y + h + 14);
        }

        // G-level labels
        g2d.setFont(new Font("SansSerif", Font.BOLD, 11));
        String[] gLabels = {"G1", "G2", "G3", "G4", "G5"};
        int[] gKp = {5, 6, 7, 8, 9};
        for (int i = 0; i < gLabels.length; i++) {
            int gx = x + (int)((gKp[i] / 9.0) * w) - 10;
            g2d.drawString(gLabels[i], gx, y - 2);
        }
    }

    /** Draws a small circular badge showing Kp storm level. */
    private static void drawKpAlertBadge(Graphics2D g2d, double kp, int cx, int cy) {
        int r = 28;
        // Fill based on severity
        if (kp >= 5.0) {
            g2d.setColor(Color.BLACK);
        } else if (kp >= 3.0) {
            g2d.setColor(new Color(100, 100, 100));
        } else {
            g2d.setColor(new Color(180, 180, 180));
        }
        g2d.fillOval(cx - r, cy - r, r * 2, r * 2);
        g2d.setColor(new Color(50, 50, 50));
        g2d.setStroke(new BasicStroke(2));
        g2d.drawOval(cx - r, cy - r, r * 2, r * 2);

        // Text inside
        Color textColor = kp >= 3.0 ? Color.WHITE : Color.BLACK;
        g2d.setColor(textColor);
        String label = kp >= 5.0 ? "G" + (int)(kp - 4) : "OK";
        g2d.setFont(new Font("SansSerif", Font.BOLD, 17));
        FontMetrics fm = g2d.getFontMetrics();
        g2d.drawString(label, cx - fm.stringWidth(label) / 2, cy + 6);
    }

    /** Draws a flare-class severity badge. */
    private static void drawFlareBadge(Graphics2D g2d, String flareClass, int cx, int cy) {
        char fc = flareClass.isEmpty() ? 'A' : flareClass.charAt(0);
        int r = 28;

        // Color by class
        if (fc == 'X') {
            g2d.setColor(Color.BLACK);
        } else if (fc == 'M') {
            g2d.setColor(new Color(80, 80, 80));
        } else if (fc == 'C') {
            g2d.setColor(new Color(140, 140, 140));
        } else {
            g2d.setColor(new Color(210, 210, 210));
        }
        g2d.fillOval(cx - r, cy - r, r * 2, r * 2);
        g2d.setColor(new Color(50, 50, 50));
        g2d.setStroke(new BasicStroke(2));
        g2d.drawOval(cx - r, cy - r, r * 2, r * 2);

        Color textColor = (fc == 'X' || fc == 'M') ? Color.WHITE : Color.BLACK;
        g2d.setColor(textColor);
        String label = String.valueOf(fc);
        g2d.setFont(new Font("SansSerif", Font.BOLD, 22));
        FontMetrics fm = g2d.getFontMetrics();
        g2d.drawString(label, cx - fm.stringWidth(label) / 2, cy + 8);
    }

    // -------------------------------------------------------------------------
    // Classification helpers
    // -------------------------------------------------------------------------

    private static String getKpStatusText(double kp) {
        if (kp < 3.0) return "Quiet / Normal";
        if (kp < 4.0) return "Unsettled";
        if (kp < 5.0) return "Active";
        if (kp < 6.0) return "G1 Minor Storm";
        if (kp < 7.0) return "G2 Moderate Storm";
        if (kp < 8.0) return "G3 Strong Storm";
        if (kp < 9.0) return "G4 Severe Storm";
        return "G5 Extreme Storm";
    }

    private static String calculateFlareClass(double flux) {
        if (flux <= 0)    return "A";
        if (flux < 1e-7)  return "A";
        if (flux < 1e-6)  return String.format(Locale.ENGLISH, "B%.1f", flux / 1e-7);
        if (flux < 1e-5)  return String.format(Locale.ENGLISH, "C%.1f", flux / 1e-6);
        if (flux < 1e-4)  return String.format(Locale.ENGLISH, "M%.1f", flux / 1e-5);
        return                   String.format(Locale.ENGLISH, "X%.1f", flux / 1e-4);
    }

    private static String formatTimeOnly(String rawTime) {
        if (rawTime == null || rawTime.isEmpty()) return "--:--";
        try {
            if (rawTime.contains("T")) {
                String timePart = rawTime.split("T")[1];
                return timePart.substring(0, Math.min(5, timePart.length()));
            } else if (rawTime.contains(" ")) {
                String timePart = rawTime.split(" ")[1];
                return timePart.substring(0, Math.min(5, timePart.length()));
            }
        } catch (Exception ignored) {}
        return rawTime;
    }

    // -------------------------------------------------------------------------
    // API Fetchers
    // -------------------------------------------------------------------------

    private static List<SolarRegion> fetchSolarRegions() throws Exception {
        String json = httpGet(SOLAR_REGIONS_URL);
        List<SolarRegion> list = new ArrayList<>();
        for (String obj : splitJsonObjects(json)) {
            SolarRegion r = new SolarRegion();
            r.setObservedDate(getStringField(obj, "observed_date"));
            r.setRegion(getIntField(obj, "region"));
            r.setArea(getIntField(obj, "area"));
            r.setNumberSpots(getIntField(obj, "number_spots"));
            r.setSpotClass(getStringField(obj, "class"));
            r.setLocation(getStringField(obj, "location"));
            list.add(r);
        }
        return list;
    }

    private static List<XRayFlux> fetchXRayFlux() throws Exception {
        String json = httpGet(XRAYS_URL);
        List<XRayFlux> list = new ArrayList<>();
        for (String obj : splitJsonObjects(json)) {
            XRayFlux x = new XRayFlux();
            x.setTimeTag(getStringField(obj, "time_tag"));
            x.setEnergy(getStringField(obj, "energy"));
            x.setFlux(getDoubleField(obj, "flux"));
            list.add(x);
        }
        return list;
    }

    private static List<KpIndex> fetchKpIndex() throws Exception {
        String json = httpGet(KP_INDEX_URL);
        List<KpIndex> list = new ArrayList<>();
        for (String obj : splitJsonObjects(json)) {
            KpIndex kp = new KpIndex();
            kp.setTimeTag(getStringField(obj, "time_tag"));
            double val = getDoubleField(obj, "kp_index");
            if (val == 0.0) val = getDoubleField(obj, "estimated_kp");
            kp.setKpIndex(val);
            list.add(kp);
        }
        return list;
    }

    private static String httpGet(String urlString) throws Exception {
        URL url = new URL(urlString);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("User-Agent", "KindleSpaceWeather/1.0");
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);

        int responseCode = conn.getResponseCode();
        if (responseCode != 200) {
            throw new RuntimeException("HTTP GET failed with code " + responseCode + " for " + urlString);
        }

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            return sb.toString();
        }
    }

    // -------------------------------------------------------------------------
    // JSON Parser Utilities
    // -------------------------------------------------------------------------

    private static List<String> splitJsonObjects(String jsonArray) {
        List<String> objects = new ArrayList<>();
        int depth = 0;
        int start = -1;
        for (int i = 0; i < jsonArray.length(); i++) {
            char c = jsonArray.charAt(i);
            if (c == '{') {
                if (depth == 0) start = i;
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0 && start != -1) {
                    objects.add(jsonArray.substring(start, i + 1));
                    start = -1;
                }
            }
        }
        return objects;
    }

    private static String getStringField(String jsonObject, String fieldName) {
        Pattern p = Pattern.compile("\"" + Pattern.quote(fieldName) + "\"\\s*:\\s*\"([^\"]*)\"");
        Matcher m = p.matcher(jsonObject);
        return m.find() ? m.group(1) : "";
    }

    private static int getIntField(String jsonObject, String fieldName) {
        Pattern p = Pattern.compile("\"" + Pattern.quote(fieldName) + "\"\\s*:\\s*(-?\\d+)");
        Matcher m = p.matcher(jsonObject);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    private static double getDoubleField(String jsonObject, String fieldName) {
        Pattern p = Pattern.compile("\"" + Pattern.quote(fieldName)
                + "\"\\s*:\\s*(-?\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?)");
        Matcher m = p.matcher(jsonObject);
        return m.find() ? Double.parseDouble(m.group(1)) : 0.0;
    }

    // -------------------------------------------------------------------------
    // DTO Classes
    // -------------------------------------------------------------------------

    public static class SolarRegion {
        private String observedDate;
        private int region;
        private int area;
        private int numberSpots;
        private String spotClass;
        private String location;

        public String getObservedDate() { return observedDate; }
        public void setObservedDate(String v) { observedDate = v; }
        public int getRegion() { return region; }
        public void setRegion(int v) { region = v; }
        public int getArea() { return area; }
        public void setArea(int v) { area = v; }
        public int getNumberSpots() { return numberSpots; }
        public void setNumberSpots(int v) { numberSpots = v; }
        public String getSpotClass() { return spotClass; }
        public void setSpotClass(String v) { spotClass = v; }
        public String getLocation() { return location; }
        public void setLocation(String v) { location = v; }
    }

    public static class XRayFlux {
        private String timeTag;
        private String energy;
        private double flux;

        public String getTimeTag() { return timeTag; }
        public void setTimeTag(String v) { timeTag = v; }
        public String getEnergy() { return energy; }
        public void setEnergy(String v) { energy = v; }
        public double getFlux() { return flux; }
        public void setFlux(double v) { flux = v; }
    }

    public static class KpIndex {
        private String timeTag;
        private double kpIndex;

        public String getTimeTag() { return timeTag; }
        public void setTimeTag(String v) { timeTag = v; }
        public double getKpIndex() { return kpIndex; }
        public void setKpIndex(double v) { kpIndex = v; }
    }
}
