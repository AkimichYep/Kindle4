package com.yep.kindle.dron.display;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
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
 * Responsible for: weather page, history page, and time/day transition page.
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
            if (!windDir.isEmpty() && !windSpeed.isEmpty()) {
                windLine = String.format("  Wind: %s @ %s", windDir, windSpeed);
            } else if (!windSpeed.isEmpty()) {
                windLine = String.format("  Wind: %s", windSpeed);
            } else {
                windLine = "  Wind: n/a";
            }
            sc[row++] = pad(windLine);
        }

        sc[row++] = pad(LINE_H);
        sc[row++] = pad(String.format("  RF Noise: %d dBm  |  CRC Delta: +%d", ctx.noiseFloor, ctx.crcDelta));
        sc[row++] = pad(LINE_H);

        Map<String, NetRecord> knownNets = ctx.knownNets;
        Set<String> baseline = ctx.baseline;
        List<NetRecord> threats = new ArrayList<>();
        int activeCount = 0;
        for (NetRecord nr : knownNets.values()) {
            if (nr.lastSeen >= now - 60_000L) activeCount++;
            if (!baseline.contains(nr.mac) || nr.surgeDetected || nr.consecutiveApproach >= 3) {
                threats.add(nr);
            }
        }
        threats.sort((a, b) -> Integer.compare(b.peakSignal, a.peakSignal));

        sc[row++] = pad(String.format("  ACTIVE APs: %d  |  SUSPECTS/NEW: %d", activeCount, threats.size()));
        sc[row++] = pad(LINE_H);

        int maxThreatRows = KindleUtils.ROWS - row - 3;
        if (threats.isEmpty()) {
            sc[row++] = pad("  (no active threats or new networks)");
        } else {
            sc[row++] = pad("  MAC / SSID        RSSI   DIST   STATUS");
            for (int i = 0; i < threats.size() && i < maxThreatRows; i++) {
                NetRecord nr = threats.get(i);
                String name = (nr.ssid != null && !nr.ssid.isEmpty() && !"[HIDDEN]".equals(nr.ssid))
                        ? nr.ssid : nr.mac;
                if (name.length() > 14) name = name.substring(0, 14);

                String status = nr.surgeDetected ? "SURGE!"
                        : nr.consecutiveApproach >= 3 ? "APPROACH"
                        : !baseline.contains(nr.mac) ? "NEW" : "ACTIVE";

                int dist = nr.lastDistM >= 0 ? nr.lastDistM
                        : (!nr.distHistory.isEmpty() ? nr.distHistory.peekLast() : -1);
                String distStr = dist >= 0 ? String.format("%3dm", dist) : " n/a";

                sc[row++] = pad(String.format("  %-16s %4ddBm %5s  %s", name, nr.peakSignal, distStr, status));
            }
        }

        while (row < KindleUtils.ROWS - 1) {
            sc[row++] = pad("");
        }
        sc[KindleUtils.ROWS - 1] = pad(String.format("  Total MACs tracked: %d", knownNets.size()));
        return sc;
    }

    // =========================================================================
    // History page
    // =========================================================================

    public static String[] buildHistoryScreen(DetectorContext ctx) {
        String[] sc = new String[KindleUtils.ROWS];
        fillEmpty(sc);
        int row = 0;

        sc[row++] = pad("* DETECTOR HISTORY & METRICS *");
        sc[row++] = pad(LINE_H);

        Map<String, NetRecord> knownNets = ctx.knownNets;
        for (NetRecord nr : knownNets.values()) {
            if (row >= KindleUtils.ROWS - 2) break;
            String name = (nr.ssid != null && !nr.ssid.isEmpty()) ? nr.ssid : nr.mac;
            if (name.length() > 20) name = name.substring(0, 20);
            sc[row++] = pad(String.format(" %-20s  peak:%4ddBm  seen:%dx", name, nr.peakSignal, nr.seenCount));
        }

        while (row < KindleUtils.ROWS) {
            sc[row++] = pad("");
        }
        return sc;
    }

    // =========================================================================
    // Time & Day of Week Transition Page (Huge Font & Width Optimized)
    // =========================================================================

    public static String[] buildTimeScreen(DetectorContext ctx) {
        Calendar cal = Calendar.getInstance();
        return buildTimeScreenForCalendar(ctx, cal);
    }

    public static String[] buildTimeScreenForDay(DetectorContext ctx, int dayOfWeek) {
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.DAY_OF_WEEK, dayOfWeek);
        return buildTimeScreenForCalendar(ctx, cal);
    }

    public static String[] buildTimeScreenForCalendar(DetectorContext ctx, Calendar cal) {
        String[] sc = new String[KindleUtils.ROWS];
        for (int i = 0; i < KindleUtils.ROWS; i++) {
            sc[i] = center("");
        }
        int row = 0;

        int dayOfWeek = cal.get(Calendar.DAY_OF_WEEK);
        String dayName = cal.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.LONG, Locale.US);
        if (dayName == null) dayName = "TODAY";
        dayName = dayName.toUpperCase();

        int hour = cal.get(Calendar.HOUR_OF_DAY);
        int min = cal.get(Calendar.MINUTE);
        String timeDigitsStr = String.format("%02d%02d", hour, min);

        String dateStr = String.format("%tB %td, %tY", cal, cal, cal);

        String quote1 = "";
        String quote2 = "";
        switch (dayOfWeek) {
            case Calendar.MONDAY:
                quote1 = "Ugh, MONDAY. Even the drones are moving slow.";
                quote2 = "Warning: Coffee level critical in cockpit.";
                break;
            case Calendar.TUESDAY:
                quote1 = "TUESDAY is just Monday's sneaky twin.";
                quote2 = "Scanning skies for unauthorized motivation.";
                break;
            case Calendar.WEDNESDAY:
                quote1 = "HUMP DAY! Halfway to the weekend.";
                quote2 = "Drones report: Weekend is within visual range.";
                break;
            case Calendar.THURSDAY:
                quote1 = "THURSDAY. Friday is within radar range!";
                quote2 = "Thrusters primed. Overheating anticipation.";
                break;
            case Calendar.FRIDAY:
                quote1 = "TGIF! Arming weekend mode.";
                quote2 = "Alert: High probability of happy hours!";
                break;
            case Calendar.SATURDAY:
                quote1 = "SATURDAY! Drones are on standby.";
                quote2 = "Perfect day for a direct visual scan.";
                break;
            case Calendar.SUNDAY:
            default:
                quote1 = "SUNDAY. Recharging batteries.";
                quote2 = "Humans and drones alike. Airspace quiet.";
                break;
        }

        sc[row++] = center("==================================================");
        sc[row++] = center("*               TIME FOR A BREAK                 *");
        sc[row++] = center("==================================================");
        sc[row++] = center("");

        // Render BIG DAY OF WEEK (5 rows high, auto-scaled to fill up to 45 cols)
        String[] bigDayRows = renderBigDay(dayName);
        for (String rLine : bigDayRows) {
            sc[row++] = center(rLine);
        }
        sc[row++] = center("");

        // Render GIANT TIME HH:MM (5 rows high)
        String[] bigTimeRows = renderBigTime(timeDigitsStr);
        for (String rLine : bigTimeRows) {
            sc[row++] = center(rLine);
        }

        sc[row++] = center(dateStr);
        sc[row++] = center("");
        sc[row++] = center("--------------------------------------------------");
        sc[row++] = center(quote1);
        sc[row++] = center(quote2);
        sc[row++] = center("--------------------------------------------------");
        sc[row++] = center("");

        // Insert ASCII art
        String[] ascii = getDayAsciiArt(dayOfWeek);
        for (String line : ascii) {
            if (row < KindleUtils.ROWS - 2) {
                sc[row++] = center(line.trim());
            }
        }

        while (row < KindleUtils.ROWS - 1) {
            sc[row++] = center("");
        }

        sc[KindleUtils.ROWS - 1] = center("Transitioning... Back to scanning in 10s");
        return sc;
    }

    // =========================================================================
    // 6x5 HUGE TIME DIGITS (HH:MM) - Uses dense '@' ASCII blocks for Kindle eips
    // =========================================================================

    private static final String[][] DIGITS_6x5 = {
        // '0'
        { "@@@@@@", "@    @", "@    @", "@    @", "@@@@@@" },
        // '1'
        { "  @@  ", " @@@  ", "  @@  ", "  @@  ", "@@@@@@" },
        // '2'
        { "@@@@@@", "     @", "@@@@@@", "@     ", "@@@@@@" },
        // '3'
        { "@@@@@@", "     @", "@@@@@@", "     @", "@@@@@@" },
        // '4'
        { "@    @", "@    @", "@@@@@@", "     @", "     @" },
        // '5'
        { "@@@@@@", "@     ", "@@@@@@", "     @", "@@@@@@" },
        // '6'
        { "@@@@@@", "@     ", "@@@@@@", "@    @", "@@@@@@" },
        // '7'
        { "@@@@@@", "     @", "    @@", "   @@ ", "  @@  " },
        // '8'
        { "@@@@@@", "@    @", "@@@@@@", "@    @", "@@@@@@" },
        // '9'
        { "@@@@@@", "@    @", "@@@@@@", "     @", "@@@@@@" }
    };

    private static final String[] COLON_6x5 = {
        "   ",
        " @ ",
        "   ",
        " @ ",
        "   "
    };

    private static String[] renderBigTime(String hhmm) {
        String[] result = new String[5];
        java.util.Arrays.fill(result, "");

        int d0 = hhmm.charAt(0) - '0';
        int d1 = hhmm.charAt(1) - '0';
        int d2 = hhmm.charAt(2) - '0';
        int d3 = hhmm.charAt(3) - '0';

        for (int r = 0; r < 5; r++) {
            result[r] = DIGITS_6x5[d0][r] + "  " +
                        DIGITS_6x5[d1][r] +
                        COLON_6x5[r] +
                        DIGITS_6x5[d2][r] + "  " +
                        DIGITS_6x5[d3][r];
        }
        return result;
    }

    // =========================================================================
    // DYNAMIC WIDE DAY LETTERS - Scales to use all available 50 columns!
    // =========================================================================

    private static String[] renderBigDay(String dayName) {
        String[] result = new String[5];
        java.util.Arrays.fill(result, "");

        int len = dayName.length();

        for (int i = 0; i < len; i++) {
            char c = dayName.charAt(i);
            String[] letter = getBigLetterScaled(c, len);
            for (int r = 0; r < 5; r++) {
                result[r] += letter[r] + (i < len - 1 ? (len <= 6 ? "  " : " ") : "");
            }
        }
        return result;
    }

    private static String[] getBigLetterScaled(char c, int wordLen) {
        if (wordLen <= 6) {
            switch (c) {
                case 'A': return new String[]{" @@@@ ", "@    @", "@@@@@@", "@    @", "@    @"};
                case 'B': return new String[]{"@@@@@ ", "@    @", "@@@@@ ", "@    @", "@@@@@ "};
                case 'C': return new String[]{" @@@@@", "@     ", "@     ", "@     ", " @@@@@"};
                case 'D': return new String[]{"@@@@@ ", "@    @", "@    @", "@    @", "@@@@@ "};
                case 'E': return new String[]{"@@@@@@", "@     ", "@@@@@ ", "@     ", "@@@@@@"};
                case 'F': return new String[]{"@@@@@@", "@     ", "@@@@@ ", "@     ", "@     "};
                case 'G': return new String[]{" @@@@@", "@     ", "@  @@@", "@    @", " @@@@@"};
                case 'H': return new String[]{"@    @", "@    @", "@@@@@@", "@    @", "@    @"};
                case 'I': return new String[]{"@@@@@@", "  @@  ", "  @@  ", "  @@  ", "@@@@@@"};
                case 'J': return new String[]{"   @@@", "    @@", "    @@", "@   @@", " @@@@ "};
                case 'K': return new String[]{"@   @@", "@  @@ ", "@@@@  ", "@  @@ ", "@   @@"};
                case 'L': return new String[]{"@     ", "@     ", "@     ", "@     ", "@@@@@@"};
                case 'M': return new String[]{"@    @", "@@  @@", "@ @@ @", "@    @", "@    @"};
                case 'N': return new String[]{"@    @", "@@   @", "@ @  @", "@  @ @", "@    @"};
                case 'O': return new String[]{" @@@@ ", "@    @", "@    @", "@    @", " @@@@ "};
                case 'P': return new String[]{"@@@@@ ", "@    @", "@@@@@ ", "@     ", "@     "};
                case 'R': return new String[]{"@@@@@ ", "@    @", "@@@@@ ", "@  @@ ", "@   @@"};
                case 'S': return new String[]{" @@@@@", "@     ", " @@@@ ", "     @", "@@@@@ "};
                case 'T': return new String[]{"@@@@@@", "  @@  ", "  @@  ", "  @@  ", "  @@  "};
                case 'U': return new String[]{"@    @", "@    @", "@    @", "@    @", " @@@@ "};
                case 'V': return new String[]{"@    @", "@    @", "@    @", " @@@@ ", "  @@  "};
                case 'W': return new String[]{"@    @", "@    @", "@ @@ @", "@@  @@", "@    @"};
                case 'Y': return new String[]{"@    @", " @  @ ", "  @@  ", "  @@  ", "  @@  "};
                default:  return new String[]{"      ", "      ", "      ", "      ", "      "};
            }
        } else {
            switch (c) {
                case 'A': return new String[]{"@@@ ","@  @","@@@@","@  @","@  @"};
                case 'B': return new String[]{"@@@ ","@  @","@@@ ","@  @","@@@ "};
                case 'C': return new String[]{" @@@","@   ","@   ","@   "," @@@"};
                case 'D': return new String[]{"@@@ ","@  @","@  @","@  @","@@@ "};
                case 'E': return new String[]{"@@@@","@   ","@@@ ","@   ","@@@@"};
                case 'F': return new String[]{"@@@@","@   ","@@@ ","@   ","@   "};
                case 'G': return new String[]{" @@@","@   ","@ @@","@  @"," @@@"};
                case 'H': return new String[]{"@  @","@  @","@@@@","@  @","@  @"};
                case 'I': return new String[]{"@@@@"," @@ "," @@ "," @@ ","@@@@"};
                case 'J': return new String[]{"  @@","  @@","  @@","@ @@"," @@ "};
                case 'K': return new String[]{"@  @","@ @@","@@  ","@ @@","@  @"};
                case 'L': return new String[]{"@   ","@   ","@   ","@   ","@@@@"};
                case 'M': return new String[]{"@@@@","@  @","@  @","@  @","@  @"};
                case 'N': return new String[]{"@  @","@@ @","@ @@","@  @","@  @"};
                case 'O': return new String[]{" @@ ","@  @","@  @","@  @"," @@ "};
                case 'P': return new String[]{"@@@ ","@  @","@@@ ","@   ","@   "};
                case 'R': return new String[]{"@@@ ","@  @","@@@ ","@  @","@  @"};
                case 'S': return new String[]{" @@@","@   "," @@ ","   @","@@@ "};
                case 'T': return new String[]{"@@@@"," @@ "," @@ "," @@ "," @@ "};
                case 'U': return new String[]{"@  @","@  @","@  @","@  @"," @@ "};
                case 'V': return new String[]{"@  @","@  @","@  @"," @@ "," @@ "};
                case 'W': return new String[]{"@  @","@  @","@  @","@@@@","@  @"};
                case 'Y': return new String[]{"@  @"," @  "," @@ "," @@ "," @@ "};
                default:  return new String[]{"    ","    ","    ","    ","    "};
            }
        }
    }

    private static String[] getDayAsciiArt(int dayOfWeek) {
        switch (dayOfWeek) {
            case Calendar.MONDAY:
                return new String[] {
                    "          _|_          ",
                    "       --/ _ \\--       ",
                    "     .---+---+---.     ",
                    "    /  T_T   T_T  \\    ",
                    "   |               |   ",
                    "    \\    _____    /    ",
                    "     '._       _.'     ",
                    "        '._ _.'        ",
                    "      ( NEED COFFEE )  "
                };
            case Calendar.TUESDAY:
                return new String[] {
                    "         _/\\_/\\_       ",
                    "        ( o   o )      ",
                    "        /   _   \\      ",
                    "       (   \\_/   )     ",
                    "        \\_______/      ",
                    "         /     \\       ",
                    "        /_______\\      ",
                    "       /    |    \\     ",
                    "    ( SCANNING INTENSELY )"
                };
            case Calendar.WEDNESDAY:
                return new String[] {
                    "          _     _      ",
                    "         / \\_ _/ \\     ",
                    "        (  o   o  )    ",
                    "         \\   _   /     ",
                    "          \\_/ \\_/      ",
                    "         _|_   _|_     ",
                    "        (___) (___)    ",
                    "                       ",
                    "     ( HAPPY HUMP DAY! )"
                };
            case Calendar.THURSDAY:
                return new String[] {
                    "         .-''''-.      ",
                    "       .'  _  _  '.    ",
                    "      /   (O)(O)   \\   ",
                    "      |     __     |   ",
                    "      \\    \\__/    /   ",
                    "       '.        .'    ",
                    "         '-....-'      ",
                    "          /    \\       ",
                    "    ( WEEKEND IN SIGHT! )"
                };
            case Calendar.FRIDAY:
                return new String[] {
                    "           /\\          ",
                    "          /  \\         ",
                    "         /____\\        ",
                    "        (  @   @ )     ",
                    "         \\   o  /      ",
                    "          \\____/       ",
                    "         _/__|__\\_     ",
                    "        /         \\    ",
                    "    ( TGIF: PARTY MODE )"
                };
            case Calendar.SATURDAY:
                return new String[] {
                    "         .-------.     ",
                    "       .'  _   _  '.   ",
                    "      /   (X) (X)   \\  ",
                    "      |      _      |  ",
                    "      \\     (_)     /  ",
                    "       '.         .'   ",
                    "         '-.....-'     ",
                    "        / /     \\ \\    ",
                    "    ( SHHH! SLEEPING... )"
                };
            case Calendar.SUNDAY:
            default:
                return new String[] {
                    "       _.-'''''''-._   ",
                    "     .'   __   __   '. ",
                    "    /    (O)   (O)    \\",
                    "    |                 |",
                    "    \\      \\___/      /",
                    "     '.             .' ",
                    "       '.________._'   ",
                    "          \\_____/      ",
                    "     ( RELAXING RADAR )"
                };
        }
    }

    private static String pad(String s) {
        if (s == null) s = "";
        if (s.length() >= KindleUtils.COLS) return s.substring(0, KindleUtils.COLS);
        StringBuilder sb = new StringBuilder(s);
        while (sb.length() < KindleUtils.COLS) sb.append(' ');
        return sb.toString();
    }

    private static String center(String s) {
        if (s == null) s = "";
        if (s.length() > KindleUtils.COLS) {
            return s.substring(0, KindleUtils.COLS);
        }
        int totalSpaces = KindleUtils.COLS - s.length();
        int left = totalSpaces / 2;
        int right = totalSpaces - left;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < left; i++) sb.append(' ');
        sb.append(s);
        for (int i = 0; i < right; i++) sb.append(' ');
        return sb.toString();
    }

    private static String weatherIcon(String desc) {
        if (desc == null) return "[?]";
        String d = desc.toLowerCase();
        if (d.contains("thunder") || d.contains("storm"))      return "[!]";
        if (d.contains("rain") || d.contains("drizzle"))       return "///";
        if (d.contains("snow") || d.contains("sleet"))         return "* *";
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
