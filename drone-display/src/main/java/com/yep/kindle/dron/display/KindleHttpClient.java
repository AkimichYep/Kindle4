package com.yep.kindle.dron.display;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Minimal HTTP GET client shared by all Kindle data-fetching classes.
 * <p>
 * The three Kindle app classes (Weather, MoonCalendar, SpaceWeather) each
 * contained their own identical HTTP read loop and error-body reader.
 * This class extracts that logic once.
 * </p>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * String json = KindleHttpClient.get(
 *         "https://api.open-meteo.com/...",
 *         "KindleWeather/1.0");
 * }</pre>
 */
public final class KindleHttpClient {

    /** Default connect + read timeout in milliseconds. */
    private static final int DEFAULT_TIMEOUT_MS = 8_000;

    private KindleHttpClient() {}

    /**
     * Performs an HTTP GET and returns the entire response body as a
     * {@link String}.  Throws a {@link RuntimeException} if the server
     * returns a non-200 status.
     *
     * @param urlString  fully-qualified URL to fetch
     * @param userAgent  value for the {@code User-Agent} request header
     * @return response body
     * @throws Exception on network error or non-200 HTTP response
     */
    public static String get(String urlString, String userAgent) throws Exception {
        URL url = new URL(urlString);
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", userAgent);
            conn.setConnectTimeout(DEFAULT_TIMEOUT_MS);
            conn.setReadTimeout(DEFAULT_TIMEOUT_MS);

            int responseCode = conn.getResponseCode();
            if (responseCode != 200) {
                String errorBody = readStream(conn.getErrorStream());
                throw new RuntimeException(
                        "HTTP GET failed: " + responseCode
                                + " | URL: " + urlString
                                + " | Body: " + errorBody);
            }

            return readStream(conn.getInputStream());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * Reads an {@link InputStream} fully into a {@link String}.
     * Returns {@code "<empty>"} if the stream is {@code null} and
     * {@code "<unreadable>"} if an {@link IOException} occurs while reading.
     *
     * @param stream the stream to drain (may be {@code null})
     * @return stream contents as a string
     */
    public static String readStream(InputStream stream) {
        if (stream == null) {
            return "<empty>";
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            return sb.toString();
        } catch (IOException e) {
            return "<unreadable>";
        }
    }
}
