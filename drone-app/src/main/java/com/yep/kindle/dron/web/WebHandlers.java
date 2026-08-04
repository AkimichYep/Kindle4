package com.yep.kindle.dron.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.yep.kindle.dron.KindleWeatherNoKey;
import com.yep.kindle.dron.util.KindleUtils;
import com.yep.kindle.dron.util.SensorReader;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

final class WebHandlers {

    private WebHandlers() {}

    static final class RootHandler implements HttpHandler {
        public void handle(HttpExchange ex) throws IOException {
            if (!HttpUtils.requireMethod(ex, "GET")) return;
            ex.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
            ex.getResponseHeaders().set("Cache-Control", "no-cache");
            ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            HttpUtils.writeResponse(ex, 200, DashboardView.BYTES);
        }
    }

    static final class GalleryHandler implements HttpHandler {
        private final DeviceState deviceState;
        GalleryHandler(DeviceState deviceState) { this.deviceState = deviceState; }
        public void handle(HttpExchange ex) throws IOException {
            if (!HttpUtils.requireMethod(ex, "GET")) return;
            HttpUtils.send(ex, 200, "text/html; charset=UTF-8", GalleryView.build(deviceState));
        }
    }

    static final class StatusHandler implements HttpHandler {
        private final DeviceState deviceState;
        StatusHandler(DeviceState deviceState) { this.deviceState = deviceState; }
        public void handle(HttpExchange ex) throws IOException {
            if (!HttpUtils.requireMethod(ex, "GET")) return;
            int    battery  = SensorReader.readBatteryPercent();
            int    charging = SensorReader.readIsCharging();
            double battTemp = SensorReader.readBatteryTemperatureCelsius();
            int    roomTemp = SensorReader.readRoomTemperatureCelsius();
            String json = String.format(
                "{\"battery\":%d,\"charging\":%d,\"batteryTemp\":%.1f,\"temperature\":%d," +
                "\"status\":\"%s\",\"userMessage\":\"%s\",\"ts\":%d}",
                battery, charging, battTemp, roomTemp,
                HttpUtils.escapeJson(deviceState.getStatusMessage()),
                HttpUtils.escapeJson(deviceState.getLastMessage()),
                System.currentTimeMillis());
            HttpUtils.send(ex, 200, "application/json", json);
        }
    }

    static final class MessageHandler implements HttpHandler {
        private final DeviceState deviceState;
        MessageHandler(DeviceState deviceState) { this.deviceState = deviceState; }
        public void handle(HttpExchange ex) throws IOException {
            if (!HttpUtils.requireMethod(ex, "POST")) return;
            String text = HttpUtils.readTextParam(ex);
            if (text == null) { HttpUtils.send(ex, 400, "text/plain", "Empty message"); return; }
            deviceState.setMessage(text);
            HttpUtils.send(ex, 200, "application/json",
                String.format("{\"ok\":true,\"message\":\"%s\"}", HttpUtils.escapeJson(text)));
        }
    }

    static final class NextPageHandler implements HttpHandler {
        private final DeviceState deviceState;
        NextPageHandler(DeviceState deviceState) { this.deviceState = deviceState; }
        public void handle(HttpExchange ex) throws IOException {
            if (!HttpUtils.requireMethod(ex, "POST")) return;
            long seq = deviceState.requestNextPage();
            HttpUtils.send(ex, 200, "application/json", String.format("{\"ok\":true,\"seq\":%d}", seq));
        }
    }

    static final class ImageServeHandler implements HttpHandler {
        private final DeviceState deviceState;
        ImageServeHandler(DeviceState deviceState) { this.deviceState = deviceState; }
        public void handle(HttpExchange ex) throws IOException {
            if (!HttpUtils.requireMethod(ex, "GET")) return;
            String filename = ex.getRequestURI().getPath().substring("/api/img/".length());
            AppDataManager dm = deviceState.getDataManager();
            if (dm == null) { HttpUtils.send(ex, 503, "text/plain", "Not ready"); return; }
            File imgFile = dm.getImageFile(filename);
            if (imgFile == null) { HttpUtils.send(ex, 404, "text/plain", "Not found"); return; }
            try {
                byte[] data = Files.readAllBytes(imgFile.toPath());
                HttpUtils.sendBinary(ex, 200, HttpUtils.mimeForFilename(filename), data);
            } catch (IOException e) {
                HttpUtils.send(ex, 500, "text/plain", "Read error");
            }
        }
    }

    static final class HealthHandler implements HttpHandler {
        public void handle(HttpExchange ex) throws IOException {
            if (!HttpUtils.requireMethod(ex, "GET")) return;
            HttpUtils.send(ex, 200, "text/plain", "ok");
        }
    }

    static final class ConfigHandler implements HttpHandler {
        private final DeviceState deviceState;
        ConfigHandler(DeviceState deviceState) { this.deviceState = deviceState; }

        public void handle(HttpExchange ex) throws IOException {
            String method = ex.getRequestMethod();
            if ("GET".equals(method)) {
                String city = deviceState.getConfig().city;
                HttpUtils.send(ex, 200, "application/json",
                    String.format("{\"city\":\"%s\"}", HttpUtils.escapeJson(city)));
            } else if ("POST".equals(method)) {
                String body = HttpUtils.readTextParam(ex);
                String city = AppConfig.normalizeCity(body);
                String validErr = AppConfig.validate(city);
                if (validErr != null) {
                    HttpUtils.send(ex, 400, "application/json",
                        String.format("{\"ok\":false,\"error\":\"%s\"}", HttpUtils.escapeJson(validErr)));
                    return;
                }
                deviceState.getConfig().city = city;
                AppDataManager dm = deviceState.getDataManager();
                if (dm != null) {
                    try { dm.saveConfig(deviceState.getConfig()); }
                    catch (IOException ignored) {}
                }
                HttpUtils.send(ex, 200, "application/json",
                    String.format("{\"ok\":true,\"city\":\"%s\"}", HttpUtils.escapeJson(city)));
            } else {
                HttpUtils.send(ex, 405, "text/plain", "Method Not Allowed");
            }
        }
    }

    interface ImageGenerator {
        void generate(String outputPath) throws Exception;
    }

    static final class KindleViewHandler implements HttpHandler {
        private final DeviceState    deviceState;
        private final String         filename;
        private final String         fallbackPath;
        private final ImageGenerator generator;

        KindleViewHandler(DeviceState ds, String filename, String fallbackPath, ImageGenerator gen) {
            this.deviceState  = ds;
            this.filename     = filename;
            this.fallbackPath = fallbackPath;
            this.generator    = gen;
        }

        public void handle(HttpExchange ex) throws IOException {
            if (!HttpUtils.requireMethod(ex, "POST")) return;
            AppDataManager dm = deviceState.getDataManager();
            String imgPath = dm != null
                ? new File(dm.getImgDir(), filename).getAbsolutePath()
                : fallbackPath;
            try {
                generator.generate(imgPath);
                KindleUtils.exec("eips", "-c");
                KindleUtils.sleep(200);
                KindleUtils.exec("eips", "-g", imgPath);
                HttpUtils.send(ex, 200, "application/json",
                    String.format("{\"ok\":true,\"imageUrl\":\"/api/img/%s\"}", filename));
            } catch (Exception e) {
                HttpUtils.send(ex, 500, "application/json",
                    String.format("{\"ok\":false,\"error\":\"%s\"}",
                        HttpUtils.escapeJson(e.getMessage() != null ? e.getMessage() : "unknown")));
            }
        }
    }

    static final class WeatherRefreshHandler implements HttpHandler {
        private final DeviceState deviceState;
        WeatherRefreshHandler(DeviceState deviceState) { this.deviceState = deviceState; }

        public void handle(HttpExchange ex) throws IOException {
            if (!HttpUtils.requireMethod(ex, "POST")) return;
            String city = AppConfig.normalizeCity(deviceState.getConfig().city);
            String validErr = AppConfig.validate(city);
            if (validErr != null) {
                HttpUtils.send(ex, 400, "application/json",
                    String.format("{\"ok\":false,\"error\":\"%s\"}", HttpUtils.escapeJson(validErr)));
                return;
            }
            AppDataManager dm = deviceState.getDataManager();
            String imgPath = dm != null
                ? new File(dm.getImgDir(), "weather.png").getAbsolutePath()
                : "/mnt/us/drone-app/img/weather.png";
            try {
                KindleWeatherNoKey.generateAndSaveForCity(imgPath, city);
                KindleUtils.exec("eips", "-c");
                KindleUtils.sleep(200);
                KindleUtils.exec("eips", "-g", imgPath);
                HttpUtils.send(ex, 200, "application/json",
                    String.format("{\"ok\":true,\"city\":\"%s\",\"imageUrl\":\"/api/img/weather.png\"}",
                        HttpUtils.escapeJson(city)));
            } catch (Exception e) {
                HttpUtils.send(ex, 500, "application/json",
                    String.format("{\"ok\":false,\"error\":\"%s\"}",
                        HttpUtils.escapeJson(e.getMessage() != null ? e.getMessage() : "unknown")));
            }
        }
    }
}
