package com.yep.kindle.dron;

import com.yep.kindle.dron.display.KindleCanvas;
import com.yep.kindle.dron.display.KindleLayoutKit;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;

/**
 * Home Temperature page — reads the Papyrus eink PMIC temperature sensor and
 * renders a 600×800 grayscale PNG for the Kindle 4 e-ink display.
 *
 * Sensor: /sys/bus/i2c/devices/1-0048/papyrus_temperature
 *
 * Each call to generateAndSave() appends the current reading to an in-memory
 * ring buffer (up to MAX_HISTORY entries) used for the sparkline graph.
 * min/max are tracked across the whole session.
 */
public class KindleHomeTemp {

    static final String SENSOR_PATH = "/sys/bus/i2c/devices/1-0048/papyrus_temperature";
    static final int    MAX_HISTORY = 24; // ~2 h at 5-min refresh

    private static final Deque<Integer> history = new ArrayDeque<>();
    private static int sessionMin = Integer.MAX_VALUE;
    private static int sessionMax = Integer.MIN_VALUE;

    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("EEE, dd MMM", Locale.ENGLISH);

    // ── Standalone preview (dev machine) ─────────────────────────────────────

    public static void main(String[] args) throws Exception {
        // Seed plausible history so the sparkline renders properly
        int[] fake = { 22, 22, 23, 23, 24, 24, 25, 25, 26, 26, 27, 27 };
        for (int t : fake) recordReading(t);
        String out = args.length > 0 ? args[0] : "hometemp_test.png";
        BufferedImage img = render(27);
        ImageIO.write(img, "png", new File(out));
        System.out.println("Saved: " + new File(out).getAbsolutePath());
    }

    // ── Public entry point ────────────────────────────────────────────────────

    public static void generateAndSave(String outputPath) throws Exception {
        int temp = readTemperature();
        recordReading(temp);
        BufferedImage img = render(temp);
        ImageIO.write(img, "png", new File(outputPath));
    }

    // ── Sensor read ───────────────────────────────────────────────────────────

    static int readTemperature() {
        try (BufferedReader br = new BufferedReader(new FileReader(SENSOR_PATH))) {
            String line = br.readLine();
            if (line != null) return Integer.parseInt(line.trim());
        } catch (Exception ignored) {}
        return -999;
    }

    static void recordReading(int temp) {
        if (temp == -999) return;
        history.addLast(temp);
        if (history.size() > MAX_HISTORY) history.pollFirst();
        if (temp < sessionMin) sessionMin = temp;
        if (temp > sessionMax) sessionMax = temp;
    }

    // ── Rendering ────────────────────────────────────────────────────────────

    static BufferedImage render(int temp) {
        BufferedImage img = KindleCanvas.newImage();
        Graphics2D g = KindleCanvas.createGraphics(img);

        // ── Header bar ───────────────────────────────────────────────────────
        final int HEADER_H = 110;
        KindleLayoutKit.drawHeaderBar(g, HEADER_H);
        drawThermometerIcon(g, 18, 10, 72, 88, temp);
        g.setColor(Color.WHITE);
        g.setFont(new Font("SansSerif", Font.BOLD, 34));
        g.drawString("HOME TEMPERATURE", 106, 58);
        g.setFont(new Font("SansSerif", Font.PLAIN, 20));
        g.drawString("Kindle 4  ·  eink PMIC sensor", 106, 90);

        // ── Big temperature number ────────────────────────────────────────────
        g.setColor(Color.BLACK);
        String tempStr = (temp == -999) ? "ERR" : temp + "°C";
        g.setFont(new Font("SansSerif", Font.BOLD, 160));
        FontMetrics fm = g.getFontMetrics();
        g.drawString(tempStr, (KindleCanvas.WIDTH - fm.stringWidth(tempStr)) / 2, 292);

        // ── Timestamp ────────────────────────────────────────────────────────
        LocalDateTime now = LocalDateTime.now();
        String timeStr = "Updated: " + now.format(TIME_FMT) + "  " + now.format(DATE_FMT);
        g.setFont(new Font("SansSerif", Font.PLAIN, 20));
        fm = g.getFontMetrics();
        g.drawString(timeStr, (KindleCanvas.WIDTH - fm.stringWidth(timeStr)) / 2, 334);

        KindleLayoutKit.drawSeparator(g, 352);

        // ── Temperature bar (0–50 °C scale) ──────────────────────────────────
        if (temp != -999) {
            final int BAR_X = 50, BAR_Y = 372, BAR_W = 500, BAR_H = 36;
            double fraction = Math.max(0.0, Math.min(1.0, temp / 50.0));
            KindleLayoutKit.drawProgressBar(g, fraction, BAR_X, BAR_Y, BAR_W, BAR_H, false, false);
            g.setColor(Color.BLACK);
            g.setFont(new Font("SansSerif", Font.PLAIN, 16));
            g.drawString("0°C",   BAR_X,                    BAR_Y + BAR_H + 18);
            g.drawString("25°C",  BAR_X + BAR_W / 2 - 16,   BAR_Y + BAR_H + 18);
            g.drawString("50°C",  BAR_X + BAR_W - 30,        BAR_Y + BAR_H + 18);
        }

        KindleLayoutKit.drawSeparator(g, 444);

        // ── Session min / max ─────────────────────────────────────────────────
        g.setColor(Color.BLACK);
        g.setFont(new Font("SansSerif", Font.BOLD, 28));
        String minStr = sessionMin == Integer.MAX_VALUE ? "MIN: --" : "MIN: " + sessionMin + "°C";
        String maxStr = sessionMax == Integer.MIN_VALUE ? "MAX: --" : "MAX: " + sessionMax + "°C";
        g.drawString(minStr, 60,  476);
        g.drawString(maxStr, 320, 476);
        g.setFont(new Font("SansSerif", Font.PLAIN, 15));
        g.setColor(new Color(110, 110, 110));
        g.drawString("since startup", 60,  496);
        g.drawString("since startup", 320, 496);

        KindleLayoutKit.drawSeparator(g, 514);

        // ── Sparkline trend graph ─────────────────────────────────────────────
        drawTrendGraph(g, 522);

        // ── Footer ───────────────────────────────────────────────────────────
        KindleLayoutKit.drawFooterBar(g, "Source: Papyrus PMIC  ·  " + SENSOR_PATH, 752, 48);

        KindleLayoutKit.drawOuterBorder(g);
        g.dispose();
        return img;
    }

    // ── Thermometer icon (geometric, white on black header) ───────────────────

    static void drawThermometerIcon(Graphics2D g, int x, int y, int w, int h, int temp) {
        g.setColor(Color.WHITE);
        g.setStroke(new BasicStroke(3f));

        int tubeW = Math.max(8, w / 4);
        int tubeX = x + (w - tubeW) / 2;
        int tubeH = (int) (h * 0.60);
        g.drawRoundRect(tubeX, y, tubeW, tubeH, tubeW, tubeW);

        int bulbR = Math.max(9, w / 3);
        int bulbCX = x + w / 2;
        int bulbCY = y + tubeH + bulbR / 2;
        g.drawOval(bulbCX - bulbR, bulbCY - bulbR, bulbR * 2, bulbR * 2);
        g.fillOval(bulbCX - bulbR + 3, bulbCY - bulbR + 3, bulbR * 2 - 6, bulbR * 2 - 6);

        // Fill tube proportional to 0–50 °C
        if (temp > 0 && temp != -999) {
            double fillFrac = Math.min(1.0, temp / 50.0);
            int fillH = (int) ((tubeH - 4) * fillFrac);
            if (fillH > 2) {
                g.fillRoundRect(tubeX + 3, y + tubeH - fillH, tubeW - 6, fillH, 3, 3);
            }
        }
    }

    // ── Sparkline bar chart ───────────────────────────────────────────────────

    static void drawTrendGraph(Graphics2D g, int yTop) {
        final int LEFT     = 50;
        final int RIGHT    = 550;
        final int GRAPH_W  = RIGHT - LEFT;
        final int GRAPH_H  = 186;
        final int CHART_TOP = yTop + 26;
        final int CHART_BTM = CHART_TOP + GRAPH_H;

        // Section title
        g.setColor(Color.BLACK);
        g.setFont(new Font("SansSerif", Font.BOLD, 16));
        int count = history.size();
        g.drawString("HISTORY  (" + count + " / " + MAX_HISTORY + " readings,  5 min each)", LEFT, yTop + 18);

        // Chart border
        g.setColor(new Color(180, 180, 180));
        g.setStroke(new BasicStroke(1));
        g.drawRect(LEFT, CHART_TOP, GRAPH_W, GRAPH_H);

        if (history.isEmpty()) {
            g.setColor(new Color(140, 140, 140));
            g.setFont(new Font("SansSerif", Font.ITALIC, 20));
            g.drawString("Collecting data...", LEFT + GRAPH_W / 2 - 80, CHART_TOP + GRAPH_H / 2 + 6);
            return;
        }

        // Y-axis range with 2 °C padding
        Integer[] readings = history.toArray(new Integer[0]);
        int lo = readings[0], hi = readings[0];
        for (int v : readings) { if (v < lo) lo = v; if (v > hi) hi = v; }
        lo = Math.max(0, lo - 2);
        hi = hi + 2;
        int range = Math.max(1, hi - lo);

        // Bars — right-aligned in the chart so the newest reading is on the right edge
        int n      = readings.length;
        int slot   = Math.max(3, (GRAPH_W - 2) / MAX_HISTORY);
        int barW   = Math.max(2, slot - 1);
        int startX = LEFT + 1 + (MAX_HISTORY - n) * slot;

        for (int i = 0; i < n; i++) {
            int v    = readings[i];
            int barH = Math.max(2, (int) ((double)(v - lo) / range * GRAPH_H));
            int bx   = startX + i * slot;
            int by   = CHART_BTM - barH;
            // Oldest bar = light gray (200), newest = solid black (40)
            int gray = (int) (200 - 160.0 * i / Math.max(1, n - 1));
            g.setColor(new Color(gray, gray, gray));
            g.fillRect(bx, by, barW, barH);
        }

        // Y-axis labels
        g.setColor(Color.BLACK);
        g.setFont(new Font("SansSerif", Font.PLAIN, 14));
        g.drawString(hi + "°", LEFT - 36, CHART_TOP + 12);
        g.drawString(lo + "°", LEFT - 36, CHART_BTM);

        // X-axis labels
        g.setFont(new Font("SansSerif", Font.PLAIN, 13));
        g.drawString("older", LEFT + 2,   CHART_BTM + 16);
        g.drawString("now →", RIGHT - 42, CHART_BTM + 16);
    }
}
