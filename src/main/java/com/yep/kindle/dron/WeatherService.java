package com.yep.kindle.dron;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Weather fetch + formatting helpers extracted from KindleDroneDetectorPro.
 */
final class WeatherService {
    private WeatherService() {}

    static WeatherData unavailable(String reason) {
        WeatherData w = new WeatherData();
        w.error = reason;
        w.updatedAt = System.currentTimeMillis();
        return w;
    }

    static WeatherData fetchWeather(String location) {
        WeatherData wd = new WeatherData();
        wd.updatedAt = System.currentTimeMillis();
        try {
            String urlLocation = location.replace(" ", "%20");
            URL url = new URL("http://wttr.in/" + urlLocation + "?format=j1");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(12_000);
            conn.setReadTimeout(12_000);
            conn.setRequestProperty("User-Agent", "curl/7.0");

            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
            }

            String json = sb.toString();
            wd.temp        = field(json, "temp_C");
            wd.feelsLike   = field(json, "FeelsLikeC");
            wd.humidity    = field(json, "humidity");
            wd.windSpeed   = field(json, "windspeedKmph");
            wd.windDir     = field(json, "winddir16Point");
            wd.pressure    = field(json, "pressure");
            wd.description = arrayValue(json, "weatherDesc");
            wd.city        = arrayValue(json, "areaName");
            wd.country     = arrayValue(json, "country");
            wd.error       = null;
        } catch (Exception e) {
            wd.error = e.getClass().getSimpleName() + ":" + e.getMessage();
        }
        return wd;
    }

    static String weatherSummaryForHud(WeatherData weather) {
        if (weather == null) return "WX: n/a";
        if (weather.error != null) return "WX ERR " + weather.error;
        return String.format("WX %sC FL%s H%s%% W%s%s P%s",
                nz(weather.temp), nz(weather.feelsLike), nz(weather.humidity),
                nz(weather.windSpeed), nz(weather.windDir), nz(weather.pressure));
    }

    static String weatherSummaryForRadar(WeatherData weather) {
        if (weather == null || weather.error != null) return "WX: N/A";
        return String.format("WX %sC %s", nz(weather.temp), shortText(weather.description, 22));
    }

    private static String field(String json, String key) {
        String[] variants = {"\"" + key + "\":\"", "\"" + key + "\": \""};
        for (String search : variants) {
            int i = json.indexOf(search);
            if (i >= 0) {
                i += search.length();
                int e = json.indexOf('"', i);
                if (e > i) return json.substring(i, e);
            }
        }
        return "--";
    }

    private static String arrayValue(String json, String key) {
        int i = json.indexOf("\"" + key + "\"");
        if (i < 0) return "--";
        int open = json.indexOf('[', i);
        int close = json.indexOf(']', open);
        if (open < 0 || close < 0 || close <= open) return "--";
        return field(json.substring(open, close), "value");
    }

    private static String shortText(String s, int max) {
        if (s == null || s.isEmpty()) return "--";
        return s.length() > max ? s.substring(0, max) : s;
    }

    private static String nz(String s) {
        return (s == null || s.isEmpty()) ? "--" : s;
    }

    static class WeatherData {
        String temp = "--", feelsLike = "--", humidity = "--";
        String windSpeed = "--", windDir = "--", description = "--";
        String city = "--", country = "--", pressure = "--";
        String error = null;
        long updatedAt = 0;
    }
}

