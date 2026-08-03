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
import java.io.*;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Home Temperature page — reads the Papyrus eink PMIC sensor, persists daily
 * aggregates to CSV, and renders a 600×800 grayscale 30-day history chart.
 *
 * Sensor : /sys/bus/i2c/devices/1-0048/papyrus_temperature
 * CSV    : /mnt/us/hometemp.csv  (date, min, max, sum, count per day)
 *
 * Missing days are stored as absent rows and drawn as dashed stubs.
 * CSV is pruned to the last HISTORY_DAYS + 5 days on every save.
 */
public class KindleHomeTemp {

    static final String SENSOR_PATH  = "/sys/bus/i2c/devices/1-0048/papyrus_temperature";
    static String CSV_PATH = "/mnt/us/hometemp.csv"; // overrideable via setCsvPath()
    static final int    HISTORY_DAYS = 28; // 4 weeks

    private static final DateTimeFormatter TIME_FMT  = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DATE_DISP = DateTimeFormatter.ofPattern("EEE, dd MMM", Locale.ENGLISH);
    private static final DateTimeFormatter DATE_CSV  = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter DATE_AXIS = DateTimeFormatter.ofPattern("dd/MM");

    public static void setCsvPath(String path) { CSV_PATH = path; }

    // One record per calendar day; loaded once, then updated in-memory + saved after each reading
    private static final Map<LocalDate, DayRecord> dayData = new LinkedHashMap<>();
    private static boolean dataLoaded = false;

    // ── Data model ────────────────────────────────────────────────────────────

    static class DayRecord {
        int  min   = Integer.MAX_VALUE;
        int  max   = Integer.MIN_VALUE;
        long sum   = 0;
        int  count = 0;

        void add(int temp) {
            if (temp < min) min = temp;
            if (temp > max) max = temp;
            sum += temp;
            count++;
        }

        int avg() {
            return count > 0 ? (int) Math.round((double) sum / count) : 0;
        }
    }

    // ── Standalone preview (dev machine) ─────────────────────────────────────

    public static void main(String[] args) throws Exception {
        LocalDate today = LocalDate.now(ZoneId.systemDefault());
        // Each entry: avg temp. 0 = missing day. Spread of ±3 °C added for min/max.
        int[] fakeAvgs = {
            19, 20,  0, 22, 23, 23, 24,   // week 1  (day 3 missing)
            24, 25, 25, 26, 26,  0, 25,   // week 2  (day 13 missing)
            24, 23, 22, 24, 25, 26, 27,   // week 3
            27, 28, 26, 27, 27, 27,  0,   // week 4  (day 27 missing)
        };
        for (int i = 0; i < fakeAvgs.length; i++) {
            int avg = fakeAvgs[i];
            if (avg == 0) continue;
            LocalDate d = today.minusDays(fakeAvgs.length - 1 - i);
            DayRecord dr = dayData.computeIfAbsent(d, k -> new DayRecord());
            // Simulate multiple readings that spread min/max around the avg
            for (int j = 0; j < 12; j++) dr.add(avg + (j % 7) - 3);
        }
        String out = args.length > 0 ? args[0] : "hometemp_test.png";
        BufferedImage img = render(27);
        ImageIO.write(img, "png", new File(out));
        System.out.println("Saved: " + new File(out).getAbsolutePath());
    }

    // ── Public entry point ────────────────────────────────────────────────────

    public static void generateAndSave(String outputPath) throws Exception {
        if (!dataLoaded) {
            loadCsv();
            dataLoaded = true;
        }
        int temp = readTemperature();
        recordReading(temp);
        saveCsv();
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
        dayData.computeIfAbsent(LocalDate.now(ZoneId.systemDefault()), k -> new DayRecord()).add(temp);
    }

    // ── CSV persistence ───────────────────────────────────────────────────────

    static void loadCsv() {
        File f = new File(CSV_PATH);
        if (!f.exists()) return;
        try (BufferedReader br = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] p = line.split(",");
                if (p.length < 5) continue;
                try {
                    LocalDate  date = LocalDate.parse(p[0].trim(), DATE_CSV);
                    DayRecord  dr   = new DayRecord();
                    dr.min   = Integer.parseInt(p[1].trim());
                    dr.max   = Integer.parseInt(p[2].trim());
                    dr.sum   = Long.parseLong(p[3].trim());
                    dr.count = Integer.parseInt(p[4].trim());
                    dayData.put(date, dr);
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
    }

    static void saveCsv() {
        // Prune old entries before writing
        LocalDate cutoff = LocalDate.now(ZoneId.systemDefault()).minusDays(HISTORY_DAYS + 5);
        dayData.entrySet().removeIf(e -> e.getKey().isBefore(cutoff));

        File tmp = new File(CSV_PATH + ".tmp");
        try (PrintWriter pw = new PrintWriter(new BufferedWriter(new FileWriter(tmp)))) {
            pw.println("# date,min,max,sum,count");
            dayData.entrySet().stream()
                   .sorted(Map.Entry.comparingByKey())
                   .forEach(e -> {
                       DayRecord dr = e.getValue();
                       pw.printf("%s,%d,%d,%d,%d%n",
                               e.getKey().format(DATE_CSV),
                               dr.min, dr.max, dr.sum, dr.count);
                   });
        } catch (Exception ignored) {
            tmp.delete();
            return;
        }
        File dest = new File(CSV_PATH);
        if (dest.exists()) dest.delete();
        tmp.renameTo(dest);
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
        g.setFont(new Font("SansSerif", Font.BOLD, 148));
        FontMetrics fm = g.getFontMetrics();
        g.drawString(tempStr, (KindleCanvas.WIDTH - fm.stringWidth(tempStr)) / 2, 272);

        // ── Timestamp + today's min/max ───────────────────────────────────────
        ZonedDateTime now = ZonedDateTime.now(ZoneId.systemDefault());
        String timeStr = "Updated: " + now.format(TIME_FMT) + "  " + now.format(DATE_DISP);
        g.setFont(new Font("SansSerif", Font.PLAIN, 19));
        fm = g.getFontMetrics();
        g.drawString(timeStr, (KindleCanvas.WIDTH - fm.stringWidth(timeStr)) / 2, 304);

        DayRecord todayRec = dayData.get(LocalDate.now(ZoneId.systemDefault()));
        if (todayRec != null && todayRec.count > 0) {
            String todayStr = "Today  ▼ " + todayRec.min + "°C  ▲ " + todayRec.max + "°C";
            g.setFont(new Font("SansSerif", Font.BOLD, 19));
            fm = g.getFontMetrics();
            g.drawString(todayStr, (KindleCanvas.WIDTH - fm.stringWidth(todayStr)) / 2, 328);
        }

        KindleLayoutKit.drawSeparator(g, 346);

        // ── Two 2-week history charts ─────────────────────────────────────────
        drawWeeksChart(g, 354, 14, 14); // weeks 1–2 (older)
        KindleLayoutKit.drawSeparator(g, 543);
        drawWeeksChart(g, 548,  0, 14); // weeks 3–4 (recent)

        // ── Footer ───────────────────────────────────────────────────────────
        KindleLayoutKit.drawFooterBar(g, "Sensor: Papyrus PMIC  ·  " + CSV_PATH, 752, 48);

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
        int bulbR  = Math.max(9, w / 3);
        int bulbCX = x + w / 2;
        int bulbCY = y + tubeH + bulbR / 2;
        g.drawOval(bulbCX - bulbR, bulbCY - bulbR, bulbR * 2, bulbR * 2);
        g.fillOval(bulbCX - bulbR + 3, bulbCY - bulbR + 3, bulbR * 2 - 6, bulbR * 2 - 6);
        if (temp > 0 && temp != -999) {
            double fillFrac = Math.min(1.0, temp / 50.0);
            int fillH = (int) ((tubeH - 4) * fillFrac);
            if (fillH > 2) g.fillRoundRect(tubeX + 3, y + tubeH - fillH, tubeW - 6, fillH, 3, 3);
        }
    }

    // ── 2-week bar chart ─────────────────────────────────────────────────────
    // fromDaysAgo: how many days ago the newest bar in this window is (0 = today)
    // numDays    : width of window in days (14 for two-week view)

    static void drawWeeksChart(Graphics2D g, int yTop, int fromDaysAgo, int numDays) {
        final int LEFT      = 44;
        final int RIGHT     = 556;
        final int GRAPH_W   = RIGHT - LEFT;
        final int CHART_H   = 155;
        final int CHART_TOP = yTop + 22;
        final int CHART_BTM = CHART_TOP + CHART_H;

        LocalDate today    = LocalDate.now(ZoneId.systemDefault());
        LocalDate winStart = today.minusDays(fromDaysAgo + numDays - 1);
        LocalDate winEnd   = today.minusDays(fromDaysAgo);
        boolean   isRecent = (fromDaysAgo == 0);

        // Section title
        g.setColor(Color.BLACK);
        g.setFont(new Font("SansSerif", Font.BOLD, 14));
        String title = (isRecent ? "WEEKS 3–4  " : "WEEKS 1–2  (bar=min–max · tick=avg)  ")
                + winStart.format(DATE_AXIS) + " – " + winEnd.format(DATE_AXIS);
        g.drawString(title, LEFT, yTop + 16);

        // Chart border
        g.setColor(new Color(180, 180, 180));
        g.setStroke(new BasicStroke(1));
        g.drawRect(LEFT, CHART_TOP, GRAPH_W, CHART_H);

        // Build slot arrays oldest→newest
        int[] mins = new int[numDays];
        int[] maxs = new int[numDays];
        int[] avgs = new int[numDays];
        for (int i = 0; i < numDays; i++) {
            LocalDate d  = today.minusDays(fromDaysAgo + numDays - 1 - i);
            DayRecord dr = dayData.get(d);
            if (dr != null && dr.count > 0) {
                mins[i] = dr.min;
                maxs[i] = dr.max;
                avgs[i] = dr.avg();
            }
            // 0 = missing day
        }

        // Y-axis range
        int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
        for (int i = 0; i < numDays; i++) {
            if (avgs[i] == 0) continue;
            if (mins[i] < lo) lo = mins[i];
            if (maxs[i] > hi) hi = maxs[i];
        }
        if (lo == Integer.MAX_VALUE) { lo = 15; hi = 35; }
        lo = Math.max(0, lo - 2);
        hi = hi + 2;
        int range = Math.max(1, hi - lo);

        int slot = GRAPH_W / numDays;
        int barW = Math.max(3, slot - 2);

        // Older window uses lighter shading to visually distinguish from recent
        int grayBase  = isRecent ? 80  : 130;
        int grayRange = isRecent ? 100 : 60;

        for (int i = 0; i < numDays; i++) {
            int bx = LEFT + i * slot + (slot - barW) / 2;

            if (avgs[i] == 0) {
                g.setColor(new Color(200, 200, 200));
                g.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER,
                        1f, new float[]{3f, 3f}, 0f));
                g.drawLine(bx + barW / 2, CHART_BTM - 8, bx + barW / 2, CHART_BTM - 1);
                g.setStroke(new BasicStroke(1));
                continue;
            }

            int minY = CHART_BTM - (int)((double)(mins[i] - lo) / range * CHART_H);
            int maxY = CHART_BTM - (int)((double)(maxs[i] - lo) / range * CHART_H);
            int barH = Math.max(4, minY - maxY);

            boolean isNewest = (i == numDays - 1) && isRecent;
            int gray = isNewest ? 20 : (int)(grayBase + (double) grayRange * (numDays - 1 - i) / (numDays - 1));
            g.setColor(new Color(gray, gray, gray));
            g.setStroke(new BasicStroke(1));
            g.fillRect(bx, maxY, barW, barH);

            // White tick at avg
            int avgY = CHART_BTM - (int)((double)(avgs[i] - lo) / range * CHART_H);
            if (avgY >= maxY && avgY <= minY) {
                g.setColor(Color.WHITE);
                g.setStroke(new BasicStroke(2f));
                g.drawLine(bx, avgY, bx + barW, avgY);
                g.setStroke(new BasicStroke(1));
            }

            // Min/max labels inside bar when tall enough
            if (barH > 28) {
                g.setFont(new Font("SansSerif", Font.BOLD, 11));
                FontMetrics fmLbl = g.getFontMetrics();
                g.setColor(Color.WHITE);
                String maxLbl = String.valueOf(maxs[i]);
                g.drawString(maxLbl, bx + (barW - fmLbl.stringWidth(maxLbl)) / 2, maxY + 12);
                String minLbl = String.valueOf(mins[i]);
                g.drawString(minLbl, bx + (barW - fmLbl.stringWidth(minLbl)) / 2, minY - 3);
            }
        }

        // Y-axis labels
        g.setColor(Color.BLACK);
        g.setFont(new Font("SansSerif", Font.PLAIN, 13));
        g.drawString(hi + "°", LEFT - 34, CHART_TOP + 12);
        g.drawString(lo + "°", LEFT - 34, CHART_BTM);

        // X-axis: every 7 days + last bar
        g.setFont(new Font("SansSerif", Font.PLAIN, 12));
        for (int i = 0; i < numDays - 1; i += 7) {
            LocalDate d = today.minusDays(fromDaysAgo + numDays - 1 - i);
            g.drawString(d.format(DATE_AXIS), LEFT + i * slot, CHART_BTM + 16);
        }
        g.setFont(new Font("SansSerif", Font.BOLD, 12));
        String lastLbl = winEnd.format(DATE_AXIS);
        FontMetrics fmLast = g.getFontMetrics();
        int lastX = LEFT + (numDays - 1) * slot + (slot - fmLast.stringWidth(lastLbl)) / 2;
        g.drawString(lastLbl, lastX, CHART_BTM + 16);
    }
}
