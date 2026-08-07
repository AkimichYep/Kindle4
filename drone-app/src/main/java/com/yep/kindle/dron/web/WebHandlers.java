package com.yep.kindle.dron.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.yep.kindle.dron.KindleWeatherNoKey;
import com.yep.kindle.dron.util.AppLog;
import com.yep.kindle.dron.util.KindleUtils;
import com.yep.kindle.dron.util.SensorReader;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

final class WebHandlers {

    private WebHandlers() {}

    static final class RootHandler implements HttpHandler {
        public void handle(HttpExchange ex) throws IOException {
            if (!HttpUtils.requireMethod(ex, "GET")) return;
            ex.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
            ex.getResponseHeaders().set("Cache-Control", "no-store, no-cache, must-revalidate");
            ex.getResponseHeaders().set("Pragma", "no-cache");
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

            // Hardware sensors
            int    battery  = SensorReader.readBatteryPercent();
            int    charging = SensorReader.readIsCharging();
            double battTemp = SensorReader.readBatteryTemperatureCelsius();
            int    roomTemp = SensorReader.readRoomTemperatureCelsius();
            long   uptime   = SensorReader.readUptimeSeconds();

            // CPU: differential measurement — take two snapshots 400 ms apart.
            // ejdk-8u211 has no java.lang.management, so we read /proc/stat directly.
            SensorReader.CpuSnapshot snap1 = SensorReader.readCpuSnapshot();
            try { Thread.sleep(400); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            SensorReader.CpuSnapshot snap2 = SensorReader.readCpuSnapshot();
            int cpuPct = SensorReader.cpuPercent(snap1, snap2);

            // RAM from /proc/meminfo
            SensorReader.MemInfo mem = SensorReader.readMemInfo();

            String json = String.format(
                "{\"battery\":%d,\"charging\":%d,\"batteryTemp\":%.1f,\"temperature\":%d," +
                "\"status\":\"%s\",\"userMessage\":\"%s\",\"currentPage\":\"%s\"," +
                "\"cpu\":%d," +
                "\"ramTotalKiB\":%d,\"ramUsedKiB\":%d,\"ramUsedPct\":%d," +
                "\"uptimeSec\":%d,\"ts\":%d}",
                battery, charging, battTemp, roomTemp,
                HttpUtils.escapeJson(deviceState.getStatusMessage()),
                HttpUtils.escapeJson(deviceState.getLastMessage()),
                HttpUtils.escapeJson(deviceState.getCurrentPage()),
                cpuPct,
                mem.totalKiB, mem.usedKiB, mem.usedPercent,
                uptime,
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
        private final String         pageName;

        KindleViewHandler(DeviceState ds, String filename, String fallbackPath, ImageGenerator gen, String pageName) {
            this.deviceState  = ds;
            this.filename     = filename;
            this.fallbackPath = fallbackPath;
            this.generator    = gen;
            this.pageName     = pageName;
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
                deviceState.setCurrentPage(pageName);
                HttpUtils.send(ex, 200, "application/json",
                    String.format("{\"ok\":true,\"imageUrl\":\"/api/img/%s\"}", filename));
            } catch (Exception e) {
                HttpUtils.send(ex, 500, "application/json",
                    String.format("{\"ok\":false,\"error\":\"%s\"}",
                        HttpUtils.escapeJson(e.getMessage() != null ? e.getMessage() : "unknown")));
            }
        }
    }

    static final class LogsHandler implements HttpHandler {
        public void handle(HttpExchange ex) throws IOException {
            if (!HttpUtils.requireMethod(ex, "GET")) return;
            ex.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
            ex.getResponseHeaders().set("Cache-Control", "no-cache");
            ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            HttpUtils.writeResponse(ex, 200, LogsView.BYTES);
        }
    }

    static final class LogsApiHandler implements HttpHandler {
        public void handle(HttpExchange ex) throws IOException {
            if (!HttpUtils.requireMethod(ex, "GET")) return;
            String query = ex.getRequestURI().getQuery();
            int n = 400;
            if (query != null && query.startsWith("n=")) {
                try { n = Math.min(600, Math.max(10, Integer.parseInt(query.substring(2)))); }
                catch (NumberFormatException ignored) {}
            }
            List<String> lines = AppLog.getRecentLines(n);
            StringBuilder sb = new StringBuilder("{\"errCount\":").append(AppLog.getErrCount())
                .append(",\"total\":").append(AppLog.getTotalLines())
                .append(",\"lines\":[");
            for (int i = 0; i < lines.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append('"').append(HttpUtils.escapeJson(lines.get(i))).append('"');
            }
            sb.append("]}");
            HttpUtils.send(ex, 200, "application/json", sb.toString());
        }
    }

    static final class LogsFileHandler implements HttpHandler {
        public void handle(HttpExchange ex) throws IOException {
            if (!HttpUtils.requireMethod(ex, "GET")) return;
            String mainPath = AppLog.getFilePath();
            if (mainPath == null) { HttpUtils.send(ex, 404, "text/plain", "Log not configured"); return; }
            File logDir = new File(mainPath).getParentFile();
            // if ?name= param present, serve that file; otherwise serve the main log
            String query = ex.getRequestURI().getQuery();
            String name = null;
            if (query != null) {
                for (String part : query.split("&")) {
                    if (part.startsWith("name=")) { name = HttpUtils.urlDecode(part.substring(5)); break; }
                }
            }
            File f;
            if (name != null && !name.isEmpty()) {
                if (name.contains("/") || name.contains("\\") || name.contains("..") || name.indexOf(0) >= 0) {
                    HttpUtils.send(ex, 400, "text/plain", "Invalid name"); return;
                }
                f = new File(logDir, name);
            } else {
                f = new File(mainPath);
            }
            if (!f.exists()) { HttpUtils.send(ex, 404, "text/plain", "File not found"); return; }
            try {
                byte[] data = Files.readAllBytes(f.toPath());
                ex.getResponseHeaders().set("Content-Disposition",
                    "attachment; filename=\"" + f.getName() + "\"");
                HttpUtils.sendBinary(ex, 200, "text/plain; charset=UTF-8", data);
            } catch (IOException e) {
                HttpUtils.send(ex, 500, "text/plain", "Read error");
            }
        }
    }

    static final class LogsListHandler implements HttpHandler {
        public void handle(HttpExchange ex) throws IOException {
            if (!HttpUtils.requireMethod(ex, "GET")) return;
            String mainPath = AppLog.getFilePath();
            if (mainPath == null) { HttpUtils.send(ex, 200, "application/json", "{\"files\":[]}"); return; }
            File logDir = new File(mainPath).getParentFile();
            File[] files = logDir.listFiles();
            StringBuilder sb = new StringBuilder("{\"files\":[");
            if (files != null) {
                Arrays.sort(files, (a, b) -> a.getName().compareTo(b.getName()));
                boolean first = true;
                for (File file : files) {
                    if (!file.isFile()) continue;
                    if (!first) sb.append(',');
                    first = false;
                    sb.append("{\"name\":\"").append(HttpUtils.escapeJson(file.getName()))
                      .append("\",\"size\":").append(file.length())
                      .append(",\"modified\":").append(file.lastModified())
                      .append('}');
                }
            }
            sb.append("]}");
            HttpUtils.send(ex, 200, "application/json", sb.toString());
        }
    }

    static final class LogsViewHandler implements HttpHandler {
        public void handle(HttpExchange ex) throws IOException {
            if (!HttpUtils.requireMethod(ex, "GET")) return;
            String mainPath = AppLog.getFilePath();
            if (mainPath == null) { HttpUtils.send(ex, 404, "application/json", "{\"error\":\"Log not configured\"}"); return; }
            File logDir = new File(mainPath).getParentFile();
            String query = ex.getRequestURI().getQuery();
            String name = null;
            int maxLines = 500;
            if (query != null) {
                for (String part : query.split("&")) {
                    if (part.startsWith("name=")) name = HttpUtils.urlDecode(part.substring(5));
                    else if (part.startsWith("n=")) {
                        try { maxLines = Math.min(2000, Math.max(10, Integer.parseInt(part.substring(2)))); }
                        catch (NumberFormatException ignored) {}
                    }
                }
            }
            if (name == null || name.isEmpty()) {
                HttpUtils.send(ex, 400, "application/json", "{\"error\":\"Missing name\"}"); return;
            }
            if (name.contains("/") || name.contains("\\") || name.contains("..") || name.indexOf(0) >= 0) {
                HttpUtils.send(ex, 400, "application/json", "{\"error\":\"Invalid name\"}"); return;
            }
            File f = new File(logDir, name);
            if (!f.exists()) { HttpUtils.send(ex, 404, "application/json", "{\"error\":\"File not found\"}"); return; }
            List<String> lines = new ArrayList<String>();
            try {
                BufferedReader br = new BufferedReader(new FileReader(f));
                String line;
                while ((line = br.readLine()) != null) lines.add(line);
                br.close();
            } catch (IOException e) {
                HttpUtils.send(ex, 500, "application/json", "{\"error\":\"Read error\"}"); return;
            }
            // return last maxLines lines
            int from = lines.size() > maxLines ? lines.size() - maxLines : 0;
            StringBuilder sb = new StringBuilder("{\"name\":\"")
                .append(HttpUtils.escapeJson(name)).append("\",\"size\":").append(f.length())
                .append(",\"modified\":").append(f.lastModified())
                .append(",\"totalLines\":").append(lines.size())
                .append(",\"lines\":[");
            for (int i = from; i < lines.size(); i++) {
                if (i > from) sb.append(',');
                sb.append('"').append(HttpUtils.escapeJson(lines.get(i))).append('"');
            }
            sb.append("]}");
            HttpUtils.send(ex, 200, "application/json", sb.toString());
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
                deviceState.setCurrentPage("weather");
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
