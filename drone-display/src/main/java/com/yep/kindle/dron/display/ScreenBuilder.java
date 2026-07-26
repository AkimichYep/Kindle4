package com.yep.kindle.dron.display;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.yep.kindle.dron.detection.MovementMetrics;
import com.yep.kindle.dron.model.AP;
import com.yep.kindle.dron.model.DetectorContext;
import com.yep.kindle.dron.model.History;
import com.yep.kindle.dron.model.NetRecord;
import com.yep.kindle.dron.service.WeatherService;
import com.yep.kindle.dron.util.KindleUtils;

/**
 * ScreenBuilder — assembles 40×50 character grids for each display page.
 *
 * Responsible for: weather page, history page, road-radar page, and the
 * legacy HUD screen (buildScreen).  All methods are pure in the sense that
 * they take their data as parameters and return a String[] without touching
 * the display hardware.
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

        // Weather block
        WeatherService.WeatherData wx = ctx.weather;
        if (wx == null || wx.error != null) {
            String errMsg = wx != null ? wx.error : "n/a";
            if (errMsg != null && errMsg.length() > 34) errMsg = errMsg.substring(0, 34);
            sc[row++] = pad("  WEATHER: " + ctx.weatherLocation + "  [OFFLINE]");
            sc[row++] = pad("  ERR: " + errMsg);
        } else {
            String icon = weatherIcon(nz(wx.description));
            String city = nz(wx.city);
            if (city.length() > 12) city = city.substring(0, 12);
            String desc = nz(wx.description);
            if (desc.length() > 20) desc = desc.substring(0, 20);
            sc[row++] = pad(String.format("  %s %-20s  %s", icon, desc, city));
            sc[row++] = pad(String.format("  Temp:%sC  Feels:%sC  Hum:%s%%",
                    nz(wx.temp), nz(wx.feelsLike), nz(wx.humidity)));
            sc[row++] = pad(String.format("  Wind:%-3s %3skm/h  Pres:%4shPa",
                    nz(wx.windDir), nz(wx.windSpeed), nz(wx.pressure)));
            long ageSec = (now - wx.updatedAt) / 1000L;
            String ageStr = ageSec < 60 ? ageSec + "s ago"
                          : ageSec < 3600 ? (ageSec / 60) + "m ago"
                          : (ageSec / 3600) + "h ago";
            sc[row++] = pad("  upd: " + ageStr);
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
                    "loop#%d  radar:activity  history:~3min", ctx.loop));
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
    // Road-radar page
    // =========================================================================

    /**
     * Build the movement-centric road-radar page (distance + speed estimates).
     */
    public static String[] buildRoadRadarScreen(DetectorContext ctx) {
        String[] sc = new String[KindleUtils.ROWS];
        fillEmpty(sc);
        int row = 0;
        long now = System.currentTimeMillis();

        String armedStr = ctx.armed
                ? "ARMED"
                : String.format("LEARN %d/%d", ctx.loop, ctx.baselineLoops);
        sc[row++] = pad(String.format("* ROAD RADAR  %tT  %-10s *", now, armedStr));
        sc[row++] = pad(LINE_H);

        List<NetRecord> recs = new ArrayList<>(ctx.knownNets.values());
        recs.sort((a, b) -> {
            int da = Math.abs(a.lastDistDeltaM);
            int db = Math.abs(b.lastDistDeltaM);
            if (db != da) return db - da;
            return Double.compare(Math.abs(speedKmh(b)), Math.abs(speedKmh(a)));
        });

        int totalActive = 0, totalMoving = 0;
        for (NetRecord nr : recs) {
            if (nr.distHistory.size() < 2) continue;
            if (now - nr.lastSeen > 240_000L) continue;
            totalActive++;
            if (Math.abs(nr.lastDistDeltaM) > 2 || Math.abs(speedKmh(nr)) > 0.5) totalMoving++;
        }
        sc[row++] = pad(String.format("  Movers:%-2d  All:%-2d  NF:%ddBm  SNR:%d",
                totalMoving, totalActive, ctx.noiseFloor, ctx.csSnr));
        sc[row++] = pad(LINE_H);
        sc[row++] = pad(" AP           dist   dM  km/h  dir  trend");
        sc[row++] = pad(LINE_H);

        int shown = 0;
        for (NetRecord nr : recs) {
            if (row >= KindleUtils.ROWS - 2) break;
            if (nr.distHistory.size() < 2) continue;
            if (now - nr.lastSeen > 240_000L) continue;

            double kmh = speedKmh(nr);
            if (shown >= 14 && Math.abs(nr.lastDistDeltaM) < 2 && Math.abs(kmh) < 0.5) continue;

            String name = (nr.ssid != null && !nr.ssid.isEmpty()) ? nr.ssid : nr.mac;
            if (name.length() > 12) name = name.substring(0, 12);

            int    dist  = nr.lastDistM >= 0 ? nr.lastDistM : nr.distHistory.peekLast();
            String dmTag = MovementMetrics.formatDistDeltaTag(nr.lastDistDeltaM);
            String dir   = nr.lastDistDeltaM < -2 ? "NEAR"
                         : nr.lastDistDeltaM >  2 ? "AWAY" : "----";
            char prefix  = nr.lastDistDeltaM < -2 ? '<'
                         : nr.lastDistDeltaM >  2 ? '>' : ' ';
            String spark = shortText(MovementMetrics.sparkline(nr.distHistory), 6);

            sc[row++] = pad(String.format("%c%-12s %4dm %4s %4.1f  %-4s %s",
                    prefix, name, dist, dmTag, Math.abs(kmh), dir, spark));
            shown++;
        }

        if (shown == 0 && row < KindleUtils.ROWS - 2) sc[row++] = pad("  no active APs in range");

        sc[KindleUtils.ROWS - 2] = pad(LINE_H);
        sc[KindleUtils.ROWS - 1] = pad(WeatherService.weatherSummaryForHud(ctx.weather)
                + "  <NEAR >AWAY");
        return sc;
    }

    // =========================================================================
    // Legacy HUD screen
    // =========================================================================

    /**
     * Build the simple AP-list HUD screen (used by the legacy render() path).
     */
    public static String[] buildScreen(List<AP> aps, DetectorContext ctx) {
        String[] sc = new String[KindleUtils.ROWS];
        fillEmpty(sc);
        int row = 0;

        boolean anythingNew = ctx.statNewArmed > 0 || ctx.statAlertEvents > 0
                || ctx.scorer.hasInterestingActivity(aps, ctx.armed, ctx.baseline);

        int thr = 0;
        for (AP a : aps) if (a.threat >= 30) thr++;
        sc[row++] = pad(String.format("DRONE %tT AP:%d THR:%d #%d",
                System.currentTimeMillis(), aps.size(), thr, ctx.loop));

        if (anythingNew) {
            sc[row++] = pad(String.format("CRC+%d NF:%d SNR:%d LQ:%d %s",
                    ctx.crcDelta, ctx.noiseFloor, ctx.csSnr, ctx.linkQuality,
                    ctx.armed ? "ARMED" : String.format("LEARN %d/%d", ctx.loop, ctx.baselineLoops)));
            long uptimeSec = (System.currentTimeMillis() - ctx.startTime) / 1000L;
            sc[row++] = pad(String.format("UP:%s SC:%d MAC:%d NEW:%d PK:%d AL:%d",
                    formatUptime(uptimeSec), ctx.statTotalScans,
                    ctx.knownNets.size(), ctx.statNewArmed, ctx.statPeakThreat,
                    ctx.statAlertEvents));
            sc[row++] = pad(WeatherService.weatherSummaryForHud(ctx.weather));
        } else {
            sc[row++] = pad(WeatherService.weatherSummaryForHud(ctx.weather));
        }

        if (ctx.externalRF) {
            sc[row++] = pad(String.format("** EXT RF: iCRC=%d thr=%d cnt=%d/%d **",
                    ctx.idleCRC, ctx.idleCrcThreshold, ctx.externalRFcount, ctx.idleCrcConfirm));
        }
        if (ctx.crcDelta > 200) {
            sc[row++] = pad("** RF BURST: CRC+" + ctx.crcDelta + " **");
        }
        sc[row++] = pad("--------------------------------------");

        long now = System.currentTimeMillis();
        for (AP a : aps) {
            if (row >= KindleUtils.ROWS - 2) break;
            if (a.threat == 0 && row > 20) break;
            History h = ctx.temporal.get(a.mac);
            char m1 = a.threat >= 60 ? '!' : (a.threat >= 30 ? '+' : ' ');
            char m2 = (h != null && h.isMoving) ? '~' : ' ';
            char m3 = (ctx.armed && !ctx.baseline.contains(a.mac) && h != null && h.isNew) ? '*' : ' ';

            String dTag = MovementMetrics.formatDistDeltaTag(a.distDeltaM);
            String ln = String.format("%c%c%c%3.0fm%s %3ddB C%-2d %-16s",
                    m1, m2, m3, a.dist, dTag, a.signalDbm, a.channel,
                    a.ssid.length() > 16 ? a.ssid.substring(0, 16) : a.ssid);

            NetRecord nr = ctx.knownNets.get(a.mac);
            if (nr != null && !nr.obsTime.isEmpty()) {
                long agoMs = now - nr.lastSeen;
                if (agoMs > 300_000L) {
                    String lastSeen = " [" + formatLastSeen(agoMs) + "]";
                    int space = KindleUtils.COLS - ln.length();
                    if (space > lastSeen.length()) ln += lastSeen;
                }
            }
            if (!a.flags.isEmpty()) {
                int space = KindleUtils.COLS - ln.length() - 1;
                if (space > 3) ln += " " + a.flags.substring(0, Math.min(a.flags.length(), space));
            }
            sc[row++] = pad(ln);
        }

        int visibleAPs = row - 5;
        if (visibleAPs > 0 && aps.size() > visibleAPs) {
            int hidden = aps.size() - visibleAPs;
            if (row < KindleUtils.ROWS) sc[row++] = pad("...+" + hidden + " more");
        }
        return sc;
    }

    // =========================================================================
    // Speed / movement helpers
    // =========================================================================

    /** Estimate speed in km/h from the distance history of a NetRecord. */
    public static double speedKmh(NetRecord nr) {
        if (nr == null || nr.distHistory == null || nr.distHistory.size() < 4) return 0.0;
        List<Integer> hist = new ArrayList<>(nr.distHistory);
        int n = hist.size();
        int w = Math.max(1, n / 3);
        double sumOld = 0, sumNew = 0;
        for (int i = 0;     i < w; i++) sumOld += hist.get(i);
        for (int i = n - w; i < n; i++) sumNew += hist.get(i);
        double deltaM  = (sumNew - sumOld) / w;
        // FAST_MS = 5000 ms per scan cycle
        double seconds = (n - 1) * 5.0;
        return (deltaM / seconds) * 3.6;
    }

    /** Returns true if any recent NetRecord shows speed >= 1 km/h. */
    public static boolean hasRoadMovement(Collection<NetRecord> records) {
        long now = System.currentTimeMillis();
        for (NetRecord nr : records) {
            if (nr.distHistory.size() < 3) continue;
            if (now - nr.lastSeen > 240_000L) continue;
            if (Math.abs(speedKmh(nr)) >= 1.0) return true;
        }
        return false;
    }

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

    /** Return s or "--" if null/empty. */
    public static String nz(String s) {
        return (s == null || s.isEmpty()) ? "--" : s;
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

    /** Map a weather description to a 4-character ASCII icon. */
    public static String weatherIcon(String desc) {
        if (desc == null || desc.equals("--")) return "[??]";
        String d = desc.toLowerCase();
        if (d.contains("thunder") || d.contains("storm"))                   return "[!!]";
        if (d.contains("blizzard") || d.contains("sleet"))                  return "[**]";
        if (d.contains("snow") || d.contains("flurr"))                      return "[**]";
        if (d.contains("drizzle"))                                           return "[.~]";
        if (d.contains("rain") || d.contains("shower"))                     return "[~~]";
        if (d.contains("fog") || d.contains("mist") || d.contains("haze")) return "[..]";
        if (d.contains("overcast"))                                          return "[CC]";
        if (d.contains("cloud"))                                             return "[Cc]";
        if (d.contains("clear") || d.contains("sunny") || d.contains("sun")) return "[<>]";
        return "[ -]";
    }

    private static void fillEmpty(String[] sc) {
        java.util.Arrays.fill(sc, "");
    }
}
