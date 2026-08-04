package com.yep.kindle.dron.web;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

class AppConfig {

    static final String DEFAULT_CITY = "Kharkiv";

    String city = DEFAULT_CITY;

    static AppConfig load(File file) {
        AppConfig cfg = new AppConfig();
        if (!file.exists()) return cfg;
        try {
            String json = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8).trim();
            int ki = json.indexOf("\"city\"");
            if (ki >= 0) {
                int colon = json.indexOf(':', ki + 6);
                if (colon >= 0) {
                    int q1 = json.indexOf('"', colon + 1);
                    if (q1 >= 0) {
                        int q2 = json.indexOf('"', q1 + 1);
                        if (q2 > q1) cfg.city = json.substring(q1 + 1, q2);
                    }
                }
            }
        } catch (Exception ignored) {}
        return cfg;
    }

    void save(File file) throws IOException {
        String json = "{\"city\":\"" + HttpUtils.escapeJson(city) + "\"}";
        Files.write(file.toPath(), json.getBytes(StandardCharsets.UTF_8));
    }

    /** Takes the first city from a comma/semicolon-separated list; falls back to default. */
    static String normalizeCity(String input) {
        if (input == null || input.trim().isEmpty()) return DEFAULT_CITY;
        String[] parts = input.split("[,;]");
        String first = parts[0].trim();
        return first.isEmpty() ? DEFAULT_CITY : first;
    }

    /**
     * Returns null if the city name is acceptable, or a human-readable error string otherwise.
     * Rules: at least 2 characters, at least 2 Unicode letters (rejects "1234", "kk", "!!").
     */
    static String validate(String city) {
        if (city == null || city.trim().length() < 2) return "City name is too short (min 2 characters)";
        int letters = 0;
        for (int i = 0; i < city.length(); i++) {
            if (Character.isLetter(city.charAt(i))) letters++;
        }
        if (letters < 2) return "City name must contain at least 2 letters";
        return null;
    }
}
