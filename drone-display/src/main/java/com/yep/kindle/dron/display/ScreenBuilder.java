package com.yep.kindle.dron.display;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.yep.kindle.dron.detection.MovementMetrics;
import com.yep.kindle.dron.model.DetectorContext;
import com.yep.kindle.dron.model.History;
import com.yep.kindle.dron.model.NetRecord;
import com.yep.kindle.dron.service.WeatherService;
import com.yep.kindle.dron.util.KindleUtils;

/**
 * ScreenBuilder — assembles 40×50 character grids for each display page.
 *
 * Responsible for: weather page and history page.  All methods are pure in
 * the sense that they take their data as parameters and return a String[]
 * without touching the display hardware.
 */
public final class ScreenBuilder {

    private ScreenBuilder() {}

    // Kindle 4 eips supports only ASCII; safe ASCII box approximations
    public static final String LINE_H = "----------------------------------------";

    // =========================================================================
    // Weather page
    // =========================================================================

    /**
     * Build the main "DRONE WATCH" weather + known-threats page.
     *
     * @param ctx  all shared detector state needed for rendering
     */
    public static String[] buildWeatherScreen(DetectorContext ctx) {
        String[] sc = new String[KindleUtils.ROWS];
        fillEmpty(sc);
        int row = 0;
        long now = System.currentTimeMillis();

        String armedStr = ctx.armed
                ? "ARMED"
                : String.format("LEARN %d/%d", ctx.loop, ctx.baselineLoops);
        sc[row++] = pad(String.format("* DRONE WATCH  %tT  %-10s *", now, armedStr));
        sc[row++] = pad(LINE_H);

        // Weather block with enhanced formatting
        WeatherService.WeatherData wx = ctx.weather;
        if (wx == null || wx.error != null) {
            String errMsg = wx != null ? wx.error : "n/a";
            if (errMsg != null && errMsg.length() > 32) errMsg = errMsg.substring(0, 32);
            sc[row++] = pad("");
            sc[row++] = pad("  [!] WEATHER SERVICE OFFLINE");
            sc[row++] = pad("  Location: " + ctx.weatherLocation);
            sc[row++] = pad("  Error: " + errMsg);
            sc[row++] = pad("");
        } else {
            String icon = weatherIcon(KindleFormatUtils.nz(wx.description));
            String city = KindleFormatUtils.nz(wx.city);
            if (city.length() > 14) city = city.substring(0, 14);
            String country = KindleFormatUtils.nz(wx.country);
            if (country.length() > 10) country = country.substring(0, 10);
            String desc = KindleFormatUtils.nz(wx.description);
            if (desc.length() > 18) desc = desc.substring(0, 18);

            // Location line with icon
            sc[row++] = pad(String.format("  %s  %s, %s", icon, city, country));

            // Description line
            sc[row++] = pad(String.format("     %s", desc));

            // Parse temps
            int temp = 0;
            int feels = 0;
            try {
                temp = Integer.parseInt(KindleFormatUtils.nz(wx.temp).replaceAll("[^-0-9]", ""));
                feels = Integer.parseInt(KindleFormatUtils.nz(wx.feelsLike).replaceAll("[^-0-9]", ""));
            } catch (Exception ignored) {}

            // Temperature line
            String tempLine = String.format("  Temp: %d C", temp);
            if (feels != temp) {
                tempLine += String.format("  |  Feels: %d C", feels);
            }
            sc[row++] = pad(tempLine);

            // Humidity and Pressure
            String humidity = KindleFormatUtils.nz(wx.humidity);
            String pressure = KindleFormatUtils.nz(wx.pressure);
            sc[row++] = pad(String.format("  Humidity: %3s%%  |  Press: %4s hPa", humidity, pressure));

            // Wind information
            String windDir = KindleFormatUtils.nz(wx.windDir);
            String windSpeed = KindleFormatUtils.nz(wx.windSpeed);
            String windLine;
            if (windSpeed.equals("0") || windSpeed.equals("--")) {
                windLine = "  Wind: Calm";
            } else {
                windLine = String.format("  Wind: %s km/h %s", windSpeed, windDir);
            }
            sc[row++] = pad(windLine);

            // Update timestamp with quality indicator
            long ageSec = (now - wx.updatedAt) / 1000L;
            String freshness = ageSec < 300 ? "[FRESH]" : ageSec < 1800 ? "[OK]" : "[STALE]";
            String ageStr;
            if (ageSec < 60) ageStr = "just now";
            else if (ageSec < 3600) ageStr = (ageSec / 60) + "m";
            else if (ageSec < 86400) ageStr = (ageSec / 3600) + "h";
            else ageStr = (ageSec / 86400) + "d";
            sc[row++] = pad(String.format("  Updated: %s ago %s", ageStr, freshness));
            sc[row++] = pad("");
        }

        sc[row++] = pad(LINE_H);

        // Detector status
        long uptimeSec = (now - ctx.startTime) / 1000L;
        sc[row++] = pad(String.format("  Up %-8s  Scans %-5d  MACs %d",
                formatUptime(uptimeSec), ctx.statTotalScans, ctx.knownNets.size()));
        sc[row++] = pad(String.format("  NF %ddBm  SNR %d  LQ %d  CRC+%d",
                ctx.noiseFloor, ctx.csSnr, ctx.linkQuality, ctx.crcDelta));
        if (ctx.statNewArmed > 0 || ctx.statAlertEvents > 0) {
            sc[row++] = pad(String.format("  >> NEW detections: %-3d  Alerts: %d",
                    ctx.statNewArmed, ctx.statAlertEvents));
        }
        if (ctx.externalRF) {
            sc[row++] = pad(String.format("  !! EXT RF  iCRC=%d  count=%d/%d",
                    ctx.idleCRC, ctx.externalRFcount, ctx.idleCrcConfirm));
        }

        sc[row++] = pad(LINE_H);
        sc[row++] = pad("  KNOWN THREATS / DRONES");

        // Known-threat records sorted by lastSeen desc
        List<NetRecord> threats = new ArrayList<>();
        for (NetRecord nr : ctx.knownNets.values()) {
            if (nr.keyword || (nr.oui != null && !nr.oui.isEmpty())) threats.add(nr);
        }
        threats.sort((a, b) -> Long.compare(b.lastSeen, a.lastSeen));

        int shown = 0;
        for (NetRecord nr : threats) {
            if (row >= KindleUtils.ROWS - 2) break;
            long agoMs  = now - nr.lastSeen;
            String name = (nr.ssid != null && !nr.ssid.isEmpty()) ? nr.ssid : nr.mac;
            if (name.length() > 16) name = name.substring(0, 16);
            String ouiStr = (nr.oui != null && !nr.oui.isEmpty()) ? nr.oui : "?";
            if (ouiStr.length() > 6) ouiStr = ouiStr.substring(0, 6);
            String timeTag = (agoMs < 86_400_000L && nr.obsTime != null && !nr.obsTime.isEmpty())
                             ? "@" + nr.obsTime : "      ";
            sc[row++] = pad(String.format("  %-16s %-6s %s %s",
                    name, ouiStr, timeTag, formatLastSeen(agoMs)));
            shown++;
        }
        if (shown == 0 && row < KindleUtils.ROWS - 1) sc[row++] = pad("  (none detected yet)");

        if (row < KindleUtils.ROWS - 1) {
            sc[KindleUtils.ROWS - 1] = pad(String.format(
                    "loop#%d  scan:3min  radar:5min  info:5min", ctx.loop));
        }
        return sc;
    }

    // =========================================================================
    // History page
    // =========================================================================

    /**
     * Build the movement-focused distance-history page.
     */
    public static String[] buildHistoryScreen(DetectorContext ctx) {
        String[] sc = new String[KindleUtils.ROWS];
        fillEmpty(sc);
        int row = 0;
        long now = System.currentTimeMillis();

        sc[row++] = pad(String.format("* DIST HISTORY  %tT  loop#%d *", now, ctx.loop));
        sc[row++] = pad(LINE_H);
        sc[row++] = pad("SSID/MAC     DIST dM/th lastSeen trend");
        sc[row++] = pad(LINE_H);

        List<NetRecord> recs = new ArrayList<>(ctx.knownNets.values());
        recs.sort((a, b) -> {
            int da = movementScore(a);
            int db = movementScore(b);
            if (db != da) return db - da;
            return Long.compare(b.lastSeen, a.lastSeen);
        });

        int shown = 0;
        for (NetRecord nr : recs) {
            if (row >= KindleUtils.ROWS - 2) break;
            if (nr.distHistory.isEmpty()) continue;

            int    dist = nr.lastDistM >= 0 ? nr.lastDistM : nr.distHistory.peekLast();
            int    dm   = nr.lastDistDeltaM;
            int    thr  = ctx.scorer.dynamicDistThresholdM(dist);
            if (shown >= 12 && Math.abs(dm) < 3) continue;

            String name = (nr.ssid != null && !nr.ssid.isEmpty()) ? nr.ssid : nr.mac;
            if (name.length() > 13) name = name.substring(0, 13);
            String when  = formatLastSeen(now - nr.lastSeen);
            String spark = MovementMetrics.sparkline(nr.distHistory);

            sc[row++] = pad(String.format("%-11s %3dm%s/%02d %-7s %s%s",
                    name, dist, MovementMetrics.formatDistDeltaTag(dm), thr,
                    shortText(when, 7), spark, MovementMetrics.trendArrow(nr.distHistory)));
            shown++;
        }

        if (shown == 0 && row < KindleUtils.ROWS - 1) sc[row++] = pad("(not enough history yet)");
        if (row < KindleUtils.ROWS) {
            sc[KindleUtils.ROWS - 1] = pad("legend: dM/th  +away -closer  trend .oO#");
        }
        return sc;
    }

    // =========================================================================
    // Movement helpers
    // =========================================================================

    static int movementScore(NetRecord nr) {
        int score = Math.abs(nr.lastDistDeltaM) * 3;
        if (nr.distHistory.size() >= 2) {
            int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
            for (int d : nr.distHistory) {
                if (d < min) min = d;
                if (d > max) max = d;
            }
            score += (max - min);
        }
        long ageMs = System.currentTimeMillis() - nr.lastSeen;
        if (ageMs < 120_000L) score += 10;
        return score;
    }

    // =========================================================================
    // String utilities
    // =========================================================================

    /** Truncate/pad a line to exactly COLS characters. */
    public static String pad(String s) {
        if (s == null) return "";
        if (s.length() > KindleUtils.COLS) return s.substring(0, KindleUtils.COLS);
        return s;
    }

    /** Truncate string to max characters, or return "--" if null/empty. */
    public static String shortText(String s, int max) {
        if (s == null || s.isEmpty()) return "--";
        return s.length() > max ? s.substring(0, max) : s;
    }

    /** Format seconds as H:MM:SS. */
    public static String formatUptime(long secs) {
        long h = secs / 3600, m = (secs % 3600) / 60, s = secs % 60;
        return String.format("%d:%02d:%02d", h, m, s);
    }

    /**
     * Format a duration in milliseconds as a human-readable "last seen" string.
     * Examples: "just now", "5 min ago", "2 h ago", "3 d ago"
     */
    public static String formatLastSeen(long agoMs) {
        if (agoMs < 0) agoMs = 0;
        long secs = agoMs / 1000L;
        if (secs < 60)  return "just now";
        long mins  = secs / 60L;
        if (mins < 60)  return mins + " min ago";
        long hours = mins / 60L;
        if (hours < 48) return hours + " h ago";
        long days  = hours / 24L;
        return days + " d ago";
    }

    /** Map weather description to a compact weather icon. */
    public static String weatherIcon(String desc) {
        if (desc == null || desc.equals("--")) return "[?]";
        String d = desc.toLowerCase();
        if (d.contains("thunder") || d.contains("storm"))      return "[!]";
        if (d.contains("blizzard"))                             return "[#]";
        if (d.contains("sleet") || d.contains("snow"))         return "[*]";
        if (d.contains("drizzle") || d.contains("rain"))       return "[~]";
        if (d.contains("shower"))                               return "[:]";
        if (d.contains("fog") || d.contains("mist"))           return "[=]";
        if (d.contains("overcast") || d.contains("cloud"))     return "[o]";
        if (d.contains("clear") || d.contains("sunny"))        return "[.]";
        return "[?]";
    }

    private static void fillEmpty(String[] sc) {
        java.util.Arrays.fill(sc, "");
    }
}
