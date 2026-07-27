package com.yep.kindle.dron;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.File;
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

    // ------------------------------------------------------------------------
    // Image Renderer (Kindle 600x800 B&W Layout)
    // ------------------------------------------------------------------------

    private static BufferedImage renderSpaceWeatherImage(
            List<SolarRegion> regions, List<XRayFlux> xrays, List<KpIndex> kpList) {

        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_BYTE_BINARY);
        Graphics2D g2d = image.createGraphics();

        g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        // Background & Outer Border
        g2d.setColor(Color.WHITE);
        g2d.fillRect(0, 0, WIDTH, HEIGHT);
        g2d.setColor(Color.BLACK);
        g2d.setStroke(new BasicStroke(2));
        g2d.drawRect(12, 12, WIDTH - 24, HEIGHT - 24);

        // Header
        g2d.setFont(new Font("SansSerif", Font.BOLD, 32));
        g2d.drawString("SPACE WEATHER DASHBOARD", 30, 52);

        g2d.setFont(new Font("SansSerif", Font.PLAIN, 18));
        String nowStr = LocalDateTime.now().format(DateTimeFormatter.ofPattern("EEE dd MMM yyyy HH:mm", Locale.ENGLISH));
        g2d.drawString("Updated: " + nowStr + " | NOAA SWPC", 30, 78);
        g2d.drawLine(20, 90, WIDTH - 20, 90);

        // SECTION 1: GEOMAGNETIC ACTIVITY (Kp INDEX)
        g2d.setFont(new Font("SansSerif", Font.BOLD, 22));
        g2d.drawString("1. GEOMAGNETIC ACTIVITY (Kp INDEX)", 30, 120);

        double latestKp = 0.0;
        String kpTime = "--:--";
        if (!kpList.isEmpty()) {
            KpIndex last = kpList.get(kpList.size() - 1);
            latestKp = last.getKpIndex();
            kpTime = formatTimeOnly(last.getTimeTag());
        }

        g2d.setFont(new Font("SansSerif", Font.BOLD, 48));
        g2d.drawString(String.format(Locale.ENGLISH, "Kp %.1f", latestKp), 30, 175);

        g2d.setFont(new Font("SansSerif", Font.PLAIN, 20));
        g2d.drawString(getKpStatusText(latestKp), 220, 155);
        g2d.drawString("Time: " + kpTime + " UTC", 220, 180);

        // Visual Kp Bar Indicator (0 to 9 scale)
        g2d.drawRect(30, 195, 540, 24);
        int fillWidth = (int) Math.min(540, (latestKp / 9.0) * 540);
        g2d.fillRect(30, 195, fillWidth, 24);

        g2d.drawLine(20, 235, WIDTH - 20, 235);

        // SECTION 2: SOLAR FLARE X-RAY FLUX (GOES)
        g2d.setFont(new Font("SansSerif", Font.BOLD, 22));
        g2d.drawString("2. X-RAY FLUX (SOLAR FLARES)", 30, 265);

        double latestFlux = 0.0;
        String xrayTime = "--:--";
        if (!xrays.isEmpty()) {
            XRayFlux last = xrays.get(xrays.size() - 1);
            latestFlux = last.getFlux();
            xrayTime = formatTimeOnly(last.getTimeTag());
        }

        String flareClass = calculateFlareClass(latestFlux);

        g2d.setFont(new Font("SansSerif", Font.BOLD, 48));
        g2d.drawString("Class " + flareClass, 30, 320);

        g2d.setFont(new Font("SansSerif", Font.PLAIN, 20));
        g2d.drawString(String.format(Locale.ENGLISH, "Flux: %.2e W/m²", latestFlux), 280, 300);
        g2d.drawString("Time: " + xrayTime + " UTC", 280, 325);

        g2d.drawLine(20, 345, WIDTH - 20, 345);

        // SECTION 3: ACTIVE SUNSPOT REGIONS
        g2d.setFont(new Font("SansSerif", Font.BOLD, 22));
        g2d.drawString("3. ACTIVE SUNSPOT REGIONS (" + regions.size() + ")", 30, 375);

        g2d.setFont(new Font("SansSerif", Font.BOLD, 18));
        g2d.drawString("Region", 30, 405);
        g2d.drawString("Location", 140, 405);
        g2d.drawString("Spots", 260, 405);
        g2d.drawString("Area", 360, 405);
        g2d.drawString("Class", 480, 405);
        g2d.drawLine(30, 412, WIDTH - 30, 412);

        g2d.setFont(new Font("SansSerif", Font.PLAIN, 18));
        int rowY = 438;
        int maxDisplay = Math.min(8, regions.size());

        if (regions.isEmpty()) {
            g2d.drawString("No active sunspot regions currently reported.", 30, rowY);
        } else {
            for (int i = 0; i < maxDisplay; i++) {
                SolarRegion r = regions.get(i);
                g2d.drawString("#" + r.getRegion(), 30, rowY);
                g2d.drawString(r.getLocation().isEmpty() ? "--" : r.getLocation(), 140, rowY);
                g2d.drawString(String.valueOf(r.getNumberSpots()), 260, rowY);
                g2d.drawString(String.valueOf(r.getArea()), 360, rowY);
                g2d.drawString(r.getSpotClass().isEmpty() ? "--" : r.getSpotClass(), 480, rowY);
                rowY += 32;
            }
        }

        // Footer
        g2d.drawLine(20, 745, WIDTH - 20, 745);
        g2d.setFont(new Font("SansSerif", Font.PLAIN, 16));
        g2d.drawString("Source: NOAA Space Weather Prediction Center (services.swpc.noaa.gov)", 28, 772);

        g2d.dispose();
        return image;
    }

    // Helper functions for classifications
    private static String getKpStatusText(double kp) {
        if (kp < 3.0) return "Status: Quiet / Normal";
        if (kp < 4.0) return "Status: Unsettled";
        if (kp < 5.0) return "Status: Active";
        if (kp < 6.0) return "Status: G1 Minor Storm";
        if (kp < 7.0) return "Status: G2 Moderate Storm";
        if (kp < 8.0) return "Status: G3 Strong Storm";
        if (kp < 9.0) return "Status: G4 Severe Storm";
        return "Status: G5 Extreme Storm";
    }

    private static String calculateFlareClass(double flux) {
        if (flux <= 0) return "A";
        if (flux < 1e-7) return "A";
        if (flux < 1e-6) return String.format(Locale.ENGLISH, "B%.1f", flux / 1e-7);
        if (flux < 1e-5) return String.format(Locale.ENGLISH, "C%.1f", flux / 1e-6);
        if (flux < 1e-4) return String.format(Locale.ENGLISH, "M%.1f", flux / 1e-5);
        return String.format(Locale.ENGLISH, "X%.1f", flux / 1e-4);
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

    // ------------------------------------------------------------------------
    // API Fetchers
    // ------------------------------------------------------------------------

    private static List<SolarRegion> fetchSolarRegions() throws Exception {
        String json = httpGet(SOLAR_REGIONS_URL);
        List<SolarRegion> list = new ArrayList<>();
        List<String> objects = splitJsonObjects(json);

        for (String obj : objects) {
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
        List<String> objects = splitJsonObjects(json);

        for (String obj : objects) {
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
        List<String> objects = splitJsonObjects(json);

        for (String obj : objects) {
            KpIndex kp = new KpIndex();
            kp.setTimeTag(getStringField(obj, "time_tag"));
            double val = getDoubleField(obj, "kp_index");
            if (val == 0.0) {
                val = getDoubleField(obj, "estimated_kp");
            }
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
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            return sb.toString();
        }
    }

    // JSON Parser Utilities
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
        Pattern p = Pattern.compile("\"" + Pattern.quote(fieldName) + "\"\\s*:\\s*(-?\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?)");
        Matcher m = p.matcher(jsonObject);
        return m.find() ? Double.parseDouble(m.group(1)) : 0.0;
    }

    // DTO Classes
    public static class SolarRegion {
        private String observedDate;
        private int region;
        private int area;
        private int numberSpots;
        private String spotClass;
        private String location;

        public String getObservedDate() { return observedDate; }
        public void setObservedDate(String observedDate) { this.observedDate = observedDate; }
        public int getRegion() { return region; }
        public void setRegion(int region) { this.region = region; }
        public int getArea() { return area; }
        public void setArea(int area) { this.area = area; }
        public int getNumberSpots() { return numberSpots; }
        public void setNumberSpots(int numberSpots) { this.numberSpots = numberSpots; }
        public String getSpotClass() { return spotClass; }
        public void setSpotClass(String spotClass) { this.spotClass = spotClass; }
        public String getLocation() { return location; }
        public void setLocation(String location) { this.location = location; }
    }

    public static class XRayFlux {
        private String timeTag;
        private String energy;
        private double flux;

        public String getTimeTag() { return timeTag; }
        public void setTimeTag(String timeTag) { this.timeTag = timeTag; }
        public String getEnergy() { return energy; }
        public void setEnergy(String energy) { this.energy = energy; }
        public double getFlux() { return flux; }
        public void setFlux(double flux) { this.flux = flux; }
    }

    public static class KpIndex {
        private String timeTag;
        private double kpIndex;

        public String getTimeTag() { return timeTag; }
        public void setTimeTag(String timeTag) { this.timeTag = timeTag; }
        public double getKpIndex() { return kpIndex; }
        public void setKpIndex(double kpIndex) { this.kpIndex = kpIndex; }
    }
}
