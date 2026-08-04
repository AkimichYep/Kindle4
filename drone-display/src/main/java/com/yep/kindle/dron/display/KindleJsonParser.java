package com.yep.kindle.dron.display;

import java.util.ArrayList;
import java.util.List;

/**
 * Minimal JSON extraction helpers shared by all Kindle data-parsing classes.
 * <p>
 * This is intentionally a hand-rolled, zero-dependency parser tailored to the
 * flat, well-structured JSON responses returned by Open-Meteo and NOAA SWPC.
 * It is <strong>not</strong> a general-purpose JSON library.
 * </p>
 *
 * <h3>Two families of methods</h3>
 * <ol>
 *   <li><b>Section/Array family</b> — used by Weather and MoonCalendar to pull
 *       named sections and typed arrays from Open-Meteo responses.</li>
 *   <li><b>Object-field family</b> — used by SpaceWeather to extract scalar
 *       fields from a flat JSON array of objects returned by NOAA SWPC.</li>
 * </ol>
 */
public final class KindleJsonParser {

    private KindleJsonParser() {}

    // =========================================================================
    // SECTION / ARRAY family  (Open-Meteo style)
    // =========================================================================

    /**
     * Extracts the JSON object that immediately follows the given key token
     * inside a larger JSON string.
     *
     * <p>Example: {@code extractSectionObject(json, "\"hourly\":")} returns the
     * complete {@code {...}} block for the {@code "hourly"} key.</p>
     *
     * @param json     full JSON string
     * @param keyToken literal token to search for (e.g. {@code "\"daily\":"})
     * @return the matched object string including braces, or {@code "{}"}
     */
    public static String extractSectionObject(String json, String keyToken) {
        int key = json.indexOf(keyToken);
        if (key < 0) return "{}";
        int start = json.indexOf('{', key);
        if (start < 0) return "{}";
        int depth = 0;
        for (int i = start; i < json.length(); i++) {
            char ch = json.charAt(i);
            if (ch == '{') {
                depth++;
            } else if (ch == '}') {
                depth--;
                if (depth == 0) return json.substring(start, i + 1);
            }
        }
        return "{}";
    }

    /**
     * Extracts a named JSON array's raw content (the part between {@code [} and
     * {@code ]}) from a section object.
     *
     * @param section JSON object string (already extracted via
     *                {@link #extractSectionObject})
     * @param key     array key name without quotes
     * @return raw array content, or an empty string if not found
     */
    public static String extractArrayRaw(String section, String key) {
        String needle = "\"" + key + "\":[";
        int keyIdx = section.indexOf(needle);
        if (keyIdx < 0) return "";
        int arrayStart = keyIdx + needle.length();
        int arrayEnd = section.indexOf(']', arrayStart);
        if (arrayEnd < 0) return "";
        return section.substring(arrayStart, arrayEnd);
    }

    /**
     * Parses a named string array from a JSON section object.
     *
     * @param section JSON section object string
     * @param key     array key name
     * @return array of unquoted string values (never {@code null})
     */
    public static String[] parseStringArray(String section, String key) {
        String raw = extractArrayRaw(section, key);
        if (raw.isEmpty()) return new String[0];
        String[] parts = raw.split(",");
        List<String> values = new ArrayList<String>();
        for (String part : parts) {
            String cleaned = part.trim();
            if (cleaned.startsWith("\"") && cleaned.endsWith("\"") && cleaned.length() >= 2) {
                cleaned = cleaned.substring(1, cleaned.length() - 1);
            }
            if (!cleaned.isEmpty()) values.add(cleaned);
        }
        return values.toArray(new String[0]);
    }

    /**
     * Parses a named double array from a JSON section object.
     *
     * @param section JSON section object string
     * @param key     array key name
     * @return array of parsed doubles (never {@code null}; failed entries = 0.0)
     */
    public static double[] parseDoubleArray(String section, String key) {
        String raw = extractArrayRaw(section, key);
        if (raw.isEmpty()) return new double[0];
        String[] parts = raw.split(",");
        double[] values = new double[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                values[i] = Double.parseDouble(parts[i].trim());
            } catch (NumberFormatException e) {
                values[i] = 0.0;
            }
        }
        return values;
    }

    /**
     * Parses a named int array from a JSON section object (rounds doubles).
     *
     * @param section JSON section object string
     * @param key     array key name
     * @return array of parsed integers (never {@code null}; failed entries = 0)
     */
    public static int[] parseIntArray(String section, String key) {
        double[] d = parseDoubleArray(section, key);
        int[] ints = new int[d.length];
        for (int i = 0; i < d.length; i++) ints[i] = (int) Math.round(d[i]);
        return ints;
    }

    /**
     * Extracts a single double value from the named section of a JSON string.
     *
     * @param json       full JSON string
     * @param objectKey  key token for the enclosing object (e.g.
     *                   {@code "\"current\":"})
     * @param valueKey   key token for the value (e.g. {@code "\"temperature_2m\":"})
     * @param fallback   value to return when not found or unparseable
     * @return extracted double, or {@code fallback}
     */
    public static double extractDouble(String json,
                                        String objectKey, String valueKey,
                                        double fallback) {
        String section = extractSectionObject(json, objectKey);
        int key = section.indexOf(valueKey);
        if (key < 0) return fallback;
        int vs = key + valueKey.length();
        int ve = vs;
        while (ve < section.length()) {
            char c = section.charAt(ve);
            if (!(Character.isDigit(c) || c == '-' || c == '.')) break;
            ve++;
        }
        try {
            return Double.parseDouble(section.substring(vs, ve));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /**
     * Extracts a single quoted string value from the named section of a JSON string.
     *
     * @param json       full JSON string
     * @param objectKey  key token for the enclosing object
     * @param valueKey   key token for the value
     * @param fallback   value to return when not found
     * @return extracted string (without quotes), or {@code fallback}
     */
    public static String extractString(String json,
                                        String objectKey, String valueKey,
                                        String fallback) {
        String section = extractSectionObject(json, objectKey);
        int key = section.indexOf(valueKey);
        if (key < 0) return fallback;
        int firstQuote  = section.indexOf('"', key + valueKey.length());
        if (firstQuote  < 0) return fallback;
        int secondQuote = section.indexOf('"', firstQuote + 1);
        if (secondQuote < 0) return fallback;
        return section.substring(firstQuote + 1, secondQuote);
    }

    // =========================================================================
    // OBJECT-FIELD family  (NOAA SWPC flat array style)
    // =========================================================================

    /**
     * Splits a JSON array string into individual object strings.
     * Each returned element is a complete {@code {...}} block.
     *
     * @param jsonArray JSON array string (may start with {@code [} or bare)
     * @return list of JSON object strings (never {@code null})
     */
    public static List<String> splitJsonObjects(String jsonArray) {
        List<String> objects = new ArrayList<String>();
        int depth = 0, start = -1;
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

    /**
     * Extracts a quoted string field value from a flat JSON object string.
     */
    public static String getStringField(String jsonObject, String fieldName) {
        int ki = jsonObject.indexOf("\"" + fieldName + "\"");
        if (ki < 0) return "";
        int colon = jsonObject.indexOf(':', ki + fieldName.length() + 2);
        if (colon < 0) return "";
        int q1 = jsonObject.indexOf('"', colon + 1);
        if (q1 < 0) return "";
        int q2 = jsonObject.indexOf('"', q1 + 1);
        if (q2 < 0) return "";
        return jsonObject.substring(q1 + 1, q2);
    }

    /**
     * Extracts an integer field value from a flat JSON object string.
     */
    public static int getIntField(String jsonObject, String fieldName) {
        int ki = jsonObject.indexOf("\"" + fieldName + "\"");
        if (ki < 0) return 0;
        int colon = jsonObject.indexOf(':', ki + fieldName.length() + 2);
        if (colon < 0) return 0;
        int vs = colon + 1;
        while (vs < jsonObject.length() && (jsonObject.charAt(vs) == ' ' || jsonObject.charAt(vs) == '\t')) vs++;
        int ve = vs;
        while (ve < jsonObject.length()) {
            char c = jsonObject.charAt(ve);
            if (!(Character.isDigit(c) || c == '-')) break;
            ve++;
        }
        try { return Integer.parseInt(jsonObject.substring(vs, ve)); }
        catch (NumberFormatException e) { return 0; }
    }

    /**
     * Extracts a double field value (including scientific notation) from a flat
     * JSON object string.
     */
    public static double getDoubleField(String jsonObject, String fieldName) {
        int ki = jsonObject.indexOf("\"" + fieldName + "\"");
        if (ki < 0) return 0.0;
        int colon = jsonObject.indexOf(':', ki + fieldName.length() + 2);
        if (colon < 0) return 0.0;
        int vs = colon + 1;
        while (vs < jsonObject.length() && (jsonObject.charAt(vs) == ' ' || jsonObject.charAt(vs) == '\t')) vs++;
        int ve = vs;
        while (ve < jsonObject.length()) {
            char c = jsonObject.charAt(ve);
            if (!(Character.isDigit(c) || c == '-' || c == '.' || c == 'e' || c == 'E' || c == '+')) break;
            ve++;
        }
        try { return Double.parseDouble(jsonObject.substring(vs, ve)); }
        catch (NumberFormatException e) { return 0.0; }
    }
}
