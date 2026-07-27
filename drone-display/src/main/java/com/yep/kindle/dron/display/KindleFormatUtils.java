package com.yep.kindle.dron.display;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Formatting utilities shared by all Kindle image generators.
 * <p>
 * Each of the three apps (Weather, MoonCalendar, SpaceWeather) had its own
 * copy of {@code formatTemp()}, {@code formatTimeOnly()}, and similar helpers.
 * This class is the single authoritative implementation.
 * </p>
 */
public final class KindleFormatUtils {

    private static final DateTimeFormatter API_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");
    private static final DateTimeFormatter TIME_ONLY =
            DateTimeFormatter.ofPattern("HH:mm");

    private KindleFormatUtils() {}

    /**
     * Formats a temperature value as an integer with a degree-Celsius suffix.
     * Example: {@code 22.6} → {@code "23°C"}.
     *
     * @param celsius temperature in degrees Celsius
     * @return formatted string, e.g. {@code "23°C"}
     */
    public static String formatTemp(double celsius) {
        return String.format(Locale.ENGLISH, "%d\u00B0C", (int) Math.round(celsius));
    }

    /**
     * Extracts the {@code HH:mm} portion from an ISO-8601 date-time string
     * of the form {@code "yyyy-MM-dd'T'HH:mm"} or a bare time string.
     * Falls back to {@code "--:--"} on any parse failure.
     *
     * @param isoDateTime ISO-8601 date-time string or a plain {@code HH:mm} string
     * @return time as {@code "HH:mm"}, or {@code "--:--"}
     */
    public static String formatTimeOnly(String isoDateTime) {
        if (isoDateTime == null || isoDateTime.isEmpty()) return "--:--";
        try {
            return LocalDateTime.parse(isoDateTime, API_TIME)
                                .toLocalTime()
                                .format(TIME_ONLY);
        } catch (Exception ignored) {}
        // Try already-formatted "HH:mm" or last 5 chars
        if (isoDateTime.length() >= 5) {
            String tail = isoDateTime.substring(isoDateTime.length() - 5);
            if (tail.matches("\\d{2}:\\d{2}")) return tail;
        }
        return "--:--";
    }

    /**
     * Extracts just the {@code HH:mm} portion from a raw time-tag string that
     * may contain either an ISO-8601 {@code T} separator or a space separator,
     * as returned by NOAA SWPC (e.g. {@code "2026-07-27T14:18:00Z"} or
     * {@code "2026-07-27 14:18:00"}).
     *
     * @param rawTime raw time-tag string (may be {@code null})
     * @return {@code "HH:mm"}, or {@code "--:--"}
     */
    public static String formatNoaaTime(String rawTime) {
        if (rawTime == null || rawTime.isEmpty()) return "--:--";
        try {
            String sep = rawTime.contains("T") ? "T" : " ";
            String[] parts = rawTime.split(sep);
            if (parts.length >= 2) {
                String timePart = parts[1];
                return timePart.substring(0, Math.min(5, timePart.length()));
            }
        } catch (Exception ignored) {}
        return rawTime.length() >= 5 ? rawTime.substring(0, 5) : "--:--";
    }

    /**
     * Returns {@code "--"} when the supplied string is {@code null} or empty,
     * otherwise returns the string unchanged.
     *
     * @param s any string
     * @return {@code s}, or {@code "--"} if blank
     */
    public static String nz(String s) {
        return (s == null || s.isEmpty()) ? "--" : s;
    }
}
