package com.yep.kindle.dron;

import com.yep.kindle.dron.display.KindleCanvas;
import com.yep.kindle.dron.display.KindleFormatUtils;
import com.yep.kindle.dron.display.KindleHttpClient;
import com.yep.kindle.dron.display.KindleJsonParser;
import com.yep.kindle.dron.display.KindleLayoutKit;
import com.yep.kindle.dron.display.WeatherIconFont;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Standalone Kindle e-ink space-weather dashboard image generator.
 * <p>
 * Fetches live data from three free NOAA SWPC endpoints (no key required)
 * and renders a 600×800 grayscale PNG for the Kindle 4 e-ink screen.
 * </p>
 * <p>
 * All canvas setup, font loading, and layout chrome are delegated to the
 * shared {@code drone-display} module:
 * {@link KindleCanvas}, {@link WeatherIconFont}, {@link KindleLayoutKit},
 * {@link KindleHttpClient}, {@link KindleJsonParser}, {@link KindleFormatUtils}.
 * </p>
 */
public class KindleSpaceWeatherNoKey {

    private static final String SOLAR_REGIONS_URL =
            "https://services.swpc.noaa.gov/json/solar_regions.json";
    private static final String XRAYS_URL =
            "https://services.swpc.noaa.gov/json/goes/primary/xrays-6-hour.json";
    private static final String KP_INDEX_URL =
            "https://services.swpc.noaa.gov/json/planetary_k_index_1m.json";

    // Weather-icon glyph codepoints used as section icons
    private static final int GLYPH_LIGHTNING     = 0xF016; // wi-lightning
    private static final int GLYPH_SUN_HIGH      = 0xF00D; // wi-day-sunny
    private static final int GLYPH_HOT           = 0xF072; // wi-hot
    private static final int GLYPH_SOLAR_ECLIPSE = 0xF06E; // wi-solar-eclipse

    public static void main(String[] args) {
        try {
            generateAndSave("kindle-space-weather.png");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Fetch NOAA data, render image, and save to the given path.
     * Called by KindleDroneDetectorPro for in-process image generation.
     */
    public static void generateAndSave(String outputPath) throws Exception {
        List<SolarRegion> regions = fetchSolarRegions();
        List<XRayFlux>    xrays   = fetchXRayFlux();
        List<KpIndex>     kpList  = fetchKpIndex();
        BufferedImage image = renderSpaceWeatherImage(regions, xrays, kpList);
        ImageIO.write(image, "png", new File(outputPath));
    }

    // -------------------------------------------------------------------------
    // Image rendering — delegates all chrome to KindleLayoutKit
    // -------------------------------------------------------------------------

    private static BufferedImage renderSpaceWeatherImage(
            List<SolarRegion> regions, List<XRayFlux> xrays, List<KpIndex> kpList) {

        BufferedImage image = KindleCanvas.newImage();
        Graphics2D g2d = KindleCanvas.createGraphics(image);

        Font iconBase = WeatherIconFont.get(KindleSpaceWeatherNoKey.class);

        // =================================================================
        // HEADER — solid black banner with solar icon
        // =================================================================
        final int HEADER_H = 80;
        KindleLayoutKit.drawHeaderBar(g2d, HEADER_H);

        String nowStr = LocalDateTime.now().format(
                DateTimeFormatter.ofPattern("EEE dd MMM yyyy  HH:mm", Locale.ENGLISH));
        KindleLayoutKit.drawHeaderContent(g2d,
                iconBase, 50f, GLYPH_HOT,
                14, 65,
                "SPACE WEATHER DASHBOARD", 28, 76, 42,
                nowStr + "  \u2022  NOAA SWPC", 16, 76, 66);

        // =================================================================
        // SECTION 1 — Kp INDEX
        // =================================================================
        double latestKp = 0.0;
        String kpTime   = "--:--";
        if (!kpList.isEmpty()) {
            KpIndex last = kpList.get(kpList.size() - 1);
            latestKp = last.kpIndex;
            kpTime   = KindleFormatUtils.formatNoaaTime(last.timeTag);
        }

        final int S1_Y = HEADER_H + 2;
        KindleLayoutKit.drawSectionHeader(g2d, iconBase, GLYPH_LIGHTNING,
                "1.  GEOMAGNETIC ACTIVITY  (Kp INDEX)", S1_Y);

        g2d.setColor(Color.BLACK);
        g2d.setFont(new Font("SansSerif", Font.BOLD, 58));
        g2d.drawString(String.format(Locale.ENGLISH, "Kp %.1f", latestKp), 24, S1_Y + 80);

        g2d.setFont(new Font("SansSerif", Font.BOLD, 18));
        g2d.drawString(getKpStatusText(latestKp), 230, S1_Y + 52);
        g2d.setFont(new Font("SansSerif", Font.PLAIN, 16));
        g2d.drawString("Time: " + kpTime + " UTC", 230, S1_Y + 74);

        // Alert badge
        drawKpAlertBadge(g2d, latestKp, 490, S1_Y + 44);

        // Kp bar with ticks and G-level labels
        drawKpBar(g2d, latestKp, 24, S1_Y + 92, 552, 26);

        g2d.setColor(new Color(80, 80, 80));
        KindleLayoutKit.drawSeparator(g2d, S1_Y + 130);

        // =================================================================
        // SECTION 2 — X-RAY FLUX
        // =================================================================
        double latestFlux = 0.0;
        String xrayTime   = "--:--";
        if (!xrays.isEmpty()) {
            XRayFlux last = xrays.get(xrays.size() - 1);
            latestFlux = last.flux;
            xrayTime   = KindleFormatUtils.formatNoaaTime(last.timeTag);
        }
        String flareClass = calculateFlareClass(latestFlux);

        final int S2_Y = S1_Y + 132;
        KindleLayoutKit.drawSectionHeader(g2d, iconBase, GLYPH_SUN_HIGH,
                "2.  X-RAY FLUX  (SOLAR FLARES)", S2_Y);

        g2d.setColor(Color.BLACK);
        g2d.setFont(new Font("SansSerif", Font.BOLD, 58));
        g2d.drawString("Class " + flareClass, 24, S2_Y + 80);

        g2d.setFont(new Font("SansSerif", Font.PLAIN, 16));
        g2d.drawString(String.format(Locale.ENGLISH, "Flux: %.2e W/m\u00B2", latestFlux), 24, S2_Y + 100);
        g2d.drawString("Time: " + xrayTime + " UTC", 310, S2_Y + 100);

        // Flare badge
        drawFlareBadge(g2d, flareClass, 490, S2_Y + 44);

        g2d.setColor(new Color(80, 80, 80));
        KindleLayoutKit.drawSeparator(g2d, S2_Y + 116);

        // =================================================================
        // SECTION 3 — SUNSPOT REGIONS
        // =================================================================
        final int S3_Y = S2_Y + 118;
        KindleLayoutKit.drawSectionHeader(g2d, iconBase, GLYPH_SOLAR_ECLIPSE,
                "3.  ACTIVE SUNSPOT REGIONS  (" + regions.size() + ")", S3_Y);

        KindleLayoutKit.drawColumnHeader(g2d, S3_Y + 32, 22,
                20,  "REGION",
                120, "LOC",
                200, "SPOTS",
                290, "AREA",
                390, "CLASS",
                480, "DATE");

        final int ROW_H = 28;
        int rowY = S3_Y + 54;
        int maxDisplay = Math.min(8, regions.size());

        g2d.setFont(new Font("SansSerif", Font.PLAIN, 15));
        if (regions.isEmpty()) {
            g2d.setColor(Color.BLACK);
            g2d.setFont(new Font("SansSerif", Font.PLAIN, 16));
            g2d.drawString("No active sunspot regions currently reported.", 20, rowY + 20);
        } else {
            for (int i = 0; i < maxDisplay; i++) {
                SolarRegion r = regions.get(i);
                KindleLayoutKit.drawRowBackground(g2d, i, 14, rowY, KindleCanvas.WIDTH - 28, ROW_H);

                g2d.setColor(Color.BLACK);
                g2d.setFont(new Font("SansSerif", Font.BOLD, 15));
                g2d.drawString("#" + r.region, 20, rowY + 19);

                g2d.setFont(new Font("SansSerif", Font.PLAIN, 15));
                g2d.drawString(KindleFormatUtils.nz(r.location),   120, rowY + 19);
                g2d.drawString(String.valueOf(r.numberSpots),      200, rowY + 19);
                g2d.drawString(String.valueOf(r.area),             290, rowY + 19);

                // Complex spot classes in bold
                String sc = KindleFormatUtils.nz(r.spotClass);
                boolean complex = sc.contains("Gamma") || sc.startsWith("Delta")
                               || sc.startsWith("Beta-Gamma");
                if (complex) g2d.setFont(new Font("SansSerif", Font.BOLD, 15));
                g2d.drawString(sc, 390, rowY + 19);

                g2d.setFont(new Font("SansSerif", Font.PLAIN, 13));
                String shortDate = r.observedDate.length() >= 10
                        ? r.observedDate.substring(5, 10) : r.observedDate;
                g2d.drawString(shortDate, 480, rowY + 19);

                rowY += ROW_H;
            }
        }

        // =================================================================
        // FOOTER
        // =================================================================
        final int FOOTER_TOP = KindleCanvas.HEIGHT - 36;
        KindleLayoutKit.drawFooterBar(g2d,
                "Source: NOAA Space Weather Prediction Center  (services.swpc.noaa.gov)",
                FOOTER_TOP, 36);

        // Outer border — always last
        KindleLayoutKit.drawOuterBorder(g2d);
        g2d.dispose();
        return image;
    }

    // =========================================================================
    // Section-specific widgets (Kp bar and badges)
    // =========================================================================

    /**
     * Draws the Kp bar: gradient fill, per-integer tick lines, G-level labels
     * above, and numeric tick labels below.
     */
    private static void drawKpBar(Graphics2D g2d, double kp,
                                   int x, int y, int w, int h) {
        // Track
        g2d.setColor(new Color(220, 220, 220));
        g2d.fillRect(x, y, w, h);

        // Fill — darker shade as kp rises toward 9
        int shade = Math.max(0, 180 - (int) (kp / 9.0 * 180));
        g2d.setColor(new Color(shade, shade, shade));
        int fillW = (int) Math.min(w, (kp / 9.0) * w);
        if (fillW > 0) g2d.fillRect(x, y, fillW, h);

        // Tick lines at each integer 1–8
        g2d.setColor(new Color(100, 100, 100));
        for (int level = 1; level <= 8; level++) {
            int tx = x + (int) ((level / 9.0) * w);
            g2d.drawLine(tx, y, tx, y + h);
        }

        // Border
        g2d.setColor(Color.BLACK);
        g2d.drawRect(x, y, w, h);

        // Numeric tick labels below bar
        g2d.setFont(new Font("SansSerif", Font.PLAIN, 12));
        for (int t : new int[]{0, 3, 5, 7, 9}) {
            int tx = x + (int) ((t / 9.0) * w);
            g2d.drawString(String.valueOf(t), tx - 4, y + h + 14);
        }

        // G-level labels above bar
        g2d.setFont(new Font("SansSerif", Font.BOLD, 11));
        String[] gLabels = {"G1", "G2", "G3", "G4", "G5"};
        int[]    gKp     = {5, 6, 7, 8, 9};
        for (int i = 0; i < gLabels.length; i++) {
            int gx = x + (int) ((gKp[i] / 9.0) * w) - 10;
            g2d.drawString(gLabels[i], gx, y - 2);
        }
    }

    /** Circular Kp-level alert badge. */
    private static void drawKpAlertBadge(Graphics2D g2d, double kp, int cx, int cy) {
        Color fill;
        Color text;
        String label;
        if (kp >= 5.0) {
            fill  = Color.BLACK;
            text  = Color.WHITE;
            label = "G" + (int) (kp - 4);
        } else if (kp >= 3.0) {
            fill  = new Color(100, 100, 100);
            text  = Color.WHITE;
            label = "OK";
        } else {
            fill  = new Color(180, 180, 180);
            text  = Color.BLACK;
            label = "OK";
        }
        KindleLayoutKit.drawBadge(g2d, fill, text, label, cx, cy, 28);
    }

    /** Circular flare-class severity badge. */
    private static void drawFlareBadge(Graphics2D g2d, String flareClass, int cx, int cy) {
        char fc = flareClass.isEmpty() ? 'A' : flareClass.charAt(0);
        Color fill;
        Color text;
        if      (fc == 'X') { fill = Color.BLACK;              text = Color.WHITE; }
        else if (fc == 'M') { fill = new Color(80, 80, 80);    text = Color.WHITE; }
        else if (fc == 'C') { fill = new Color(140, 140, 140); text = Color.WHITE; }
        else                { fill = new Color(210, 210, 210); text = Color.BLACK; }
        KindleLayoutKit.drawBadge(g2d, fill, text, String.valueOf(fc), cx, cy, 28);
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
        if (flux <= 0)   return "A";
        if (flux < 1e-7) return "A";
        if (flux < 1e-6) return String.format(Locale.ENGLISH, "B%.1f", flux / 1e-7);
        if (flux < 1e-5) return String.format(Locale.ENGLISH, "C%.1f", flux / 1e-6);
        if (flux < 1e-4) return String.format(Locale.ENGLISH, "M%.1f", flux / 1e-5);
        return                  String.format(Locale.ENGLISH, "X%.1f", flux / 1e-4);
    }

    // -------------------------------------------------------------------------
    // API fetchers — delegate HTTP and JSON to shared utilities
    // -------------------------------------------------------------------------

    private static List<SolarRegion> fetchSolarRegions() throws Exception {
        String json = KindleHttpClient.get(SOLAR_REGIONS_URL, "KindleSpaceWeather/1.0");
        List<SolarRegion> list = new ArrayList<SolarRegion>();
        for (String obj : KindleJsonParser.splitJsonObjects(json)) {
            SolarRegion r = new SolarRegion();
            r.observedDate = KindleJsonParser.getStringField(obj, "observed_date");
            r.region       = KindleJsonParser.getIntField(obj, "region");
            r.area         = KindleJsonParser.getIntField(obj, "area");
            r.numberSpots  = KindleJsonParser.getIntField(obj, "number_spots");
            r.spotClass    = KindleJsonParser.getStringField(obj, "class");
            r.location     = KindleJsonParser.getStringField(obj, "location");
            list.add(r);
        }
        return list;
    }

    private static List<XRayFlux> fetchXRayFlux() throws Exception {
        String json = KindleHttpClient.get(XRAYS_URL, "KindleSpaceWeather/1.0");
        List<XRayFlux> list = new ArrayList<XRayFlux>();
        for (String obj : KindleJsonParser.splitJsonObjects(json)) {
            XRayFlux x = new XRayFlux();
            x.timeTag = KindleJsonParser.getStringField(obj, "time_tag");
            x.energy  = KindleJsonParser.getStringField(obj, "energy");
            x.flux    = KindleJsonParser.getDoubleField(obj, "flux");
            list.add(x);
        }
        return list;
    }

    private static List<KpIndex> fetchKpIndex() throws Exception {
        String json = KindleHttpClient.get(KP_INDEX_URL, "KindleSpaceWeather/1.0");
        List<KpIndex> list = new ArrayList<KpIndex>();
        for (String obj : KindleJsonParser.splitJsonObjects(json)) {
            KpIndex kp = new KpIndex();
            kp.timeTag = KindleJsonParser.getStringField(obj, "time_tag");
            double val = KindleJsonParser.getDoubleField(obj, "kp_index");
            if (val == 0.0) val = KindleJsonParser.getDoubleField(obj, "estimated_kp");
            kp.kpIndex = val;
            list.add(kp);
        }
        return list;
    }

    // -------------------------------------------------------------------------
    // Data models (plain data holders — no getters/setters needed internally)
    // -------------------------------------------------------------------------

    public static class SolarRegion {
        public String observedDate = "";
        public int    region;
        public int    area;
        public int    numberSpots;
        public String spotClass = "";
        public String location  = "";

        // Keep public getters for any external callers
        public String getObservedDate() { return observedDate; }
        public void   setObservedDate(String v) { observedDate = v; }
        public int    getRegion()       { return region; }
        public void   setRegion(int v)  { region = v; }
        public int    getArea()         { return area; }
        public void   setArea(int v)    { area = v; }
        public int    getNumberSpots()  { return numberSpots; }
        public void   setNumberSpots(int v) { numberSpots = v; }
        public String getSpotClass()    { return spotClass; }
        public void   setSpotClass(String v) { spotClass = v; }
        public String getLocation()     { return location; }
        public void   setLocation(String v)  { location = v; }
    }

    public static class XRayFlux {
        public String timeTag = "";
        public String energy  = "";
        public double flux;

        public String getTimeTag() { return timeTag; }
        public void   setTimeTag(String v) { timeTag = v; }
        public String getEnergy()  { return energy; }
        public void   setEnergy(String v)  { energy = v; }
        public double getFlux()    { return flux; }
        public void   setFlux(double v)    { flux = v; }
    }

    public static class KpIndex {
        public String timeTag = "";
        public double kpIndex;

        public String getTimeTag()  { return timeTag; }
        public void   setTimeTag(String v) { timeTag = v; }
        public double getKpIndex()  { return kpIndex; }
        public void   setKpIndex(double v) { kpIndex = v; }
    }
}
