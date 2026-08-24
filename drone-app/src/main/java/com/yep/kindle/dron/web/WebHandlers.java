package com.yep.kindle.dron.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.yep.kindle.dron.KindleWeatherNoKey;
import com.yep.kindle.dron.util.AppLog;
import com.yep.kindle.dron.util.KindleUtils;
import com.yep.kindle.dron.util.SensorReader;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
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
                "\"status\":\"%s\",\"userMessage\":\"%s\",\"currentPage\":\"%s\",\"rotationEnabled\":%b," +
                "\"cpu\":%d," +
                "\"ramTotalKiB\":%d,\"ramUsedKiB\":%d,\"ramUsedPct\":%d," +
                "\"uptimeSec\":%d,\"ts\":%d}",
                battery, charging, battTemp, roomTemp,
                HttpUtils.escapeJson(deviceState.getStatusMessage()),
                HttpUtils.escapeJson(deviceState.getLastMessage()),
                HttpUtils.escapeJson(deviceState.getCurrentPage()),
                deviceState.isRotationEnabled(),
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

    // ── Rotation toggle ───────────────────────────────────────────────────────

    static final class RotationHandler implements HttpHandler {
        private final DeviceState deviceState;
        RotationHandler(DeviceState deviceState) { this.deviceState = deviceState; }

        public void handle(HttpExchange ex) throws IOException {
            if (!HttpUtils.requireMethod(ex, "POST")) return;
            // Body "true" / "false", or omit to toggle
            String body = HttpUtils.readTextParam(ex);
            boolean enabled;
            if ("true".equalsIgnoreCase(body))       enabled = true;
            else if ("false".equalsIgnoreCase(body)) enabled = false;
            else                                     enabled = !deviceState.isRotationEnabled();

            deviceState.setRotationEnabled(enabled);
            AppLog.info("[WEB] rotation=" + enabled);
            HttpUtils.send(ex, 200, "application/json",
                String.format("{\"ok\":true,\"rotation\":%b}", enabled));
        }
    }

    // ── WiFi monitor start / stop / SSE stream ────────────────────────────────

    static final class WifiMonitorStartHandler implements HttpHandler {
        private final DeviceState deviceState;
        WifiMonitorStartHandler(DeviceState deviceState) { this.deviceState = deviceState; }

        public void handle(HttpExchange ex) throws IOException {
            if (!HttpUtils.requireMethod(ex, "POST")) return;
            WifiMonitor mon = deviceState.getWifiMonitor();
            if (mon == null) {
                HttpUtils.send(ex, 503, "application/json", "{\"ok\":false,\"error\":\"Monitor not initialized\"}");
                return;
            }
            boolean started = mon.start();
            AppLog.info("[WEB] wifi-monitor start already=" + !started);
            HttpUtils.send(ex, 200, "application/json",
                String.format("{\"ok\":true,\"running\":true,\"started\":%b}", started));
        }
    }

    static final class WifiMonitorStopHandler implements HttpHandler {
        private final DeviceState deviceState;
        WifiMonitorStopHandler(DeviceState deviceState) { this.deviceState = deviceState; }

        public void handle(HttpExchange ex) throws IOException {
            if (!HttpUtils.requireMethod(ex, "POST")) return;
            WifiMonitor mon = deviceState.getWifiMonitor();
            if (mon == null) {
                HttpUtils.send(ex, 503, "application/json", "{\"ok\":false,\"error\":\"Monitor not initialized\"}");
                return;
            }
            boolean stopped = mon.stop();
            AppLog.info("[WEB] wifi-monitor stop wasRunning=" + stopped);
            HttpUtils.send(ex, 200, "application/json",
                String.format("{\"ok\":true,\"running\":false,\"stopped\":%b}", stopped));
        }
    }

    /**
     * Server-Sent Events stream for the WiFi monitor.
     *
     * <p>Endpoint: {@code GET /api/wifi-monitor/stream}<br>
     * Content-Type: {@code text/event-stream}<br>
     * Each SSE event carries one JSON object from {@link WifiMonitor#pollLine}.</p>
     *
     * <p>The handler blocks in a loop draining the monitor queue until the client
     * disconnects (detected by {@code OutputStream.write} throwing {@link IOException})
     * or the monitor is stopped (queue returns {@code null} for > 30 s).</p>
     */
    static final class WifiMonitorStreamHandler implements HttpHandler {
        private static final long POLL_TIMEOUT_MS  = 5_000L;
        private static final int  MAX_IDLE_POLLS   = 6;   // ~30 s of no data → heartbeat then exit
        private final DeviceState deviceState;
        WifiMonitorStreamHandler(DeviceState deviceState) { this.deviceState = deviceState; }

        public void handle(HttpExchange ex) throws IOException {
            if (!HttpUtils.requireMethod(ex, "GET")) return;
            WifiMonitor mon = deviceState.getWifiMonitor();
            if (mon == null) {
                HttpUtils.send(ex, 503, "text/plain", "Monitor not initialized");
                return;
            }

            ex.getResponseHeaders().set("Content-Type",  "text/event-stream; charset=UTF-8");
            ex.getResponseHeaders().set("Cache-Control", "no-cache");
            ex.getResponseHeaders().set("Connection",    "keep-alive");
            ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            ex.sendResponseHeaders(200, 0);   // 0 = chunked / keep-alive

            OutputStream os = ex.getResponseBody();
            AppLog.info("[STREAM] client connected");
            int idleCount = 0;
            try {
                // Send initial state immediately so the browser knows the monitor status
                String init = "{\"type\":\"init\",\"running\":" + mon.isRunning() + "}";
                os.write(("data: " + init + "\n\n").getBytes(StandardCharsets.UTF_8));
                os.flush();

                while (true) {
                    String line;
                    try {
                        line = mon.pollLine(POLL_TIMEOUT_MS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    if (line == null) {
                        idleCount++;
                        // Heartbeat comment keeps the TCP connection alive through proxies
                        os.write(": heartbeat\n\n".getBytes(StandardCharsets.UTF_8));
                        os.flush();
                        if (idleCount >= MAX_IDLE_POLLS && !mon.isRunning()) break;
                        continue;
                    }
                    idleCount = 0;
                    os.write(("data: " + line + "\n\n").getBytes(StandardCharsets.UTF_8));
                    os.flush();
                }
            } catch (IOException ignored) {
                // Client disconnected — normal exit
            } finally {
                AppLog.info("[STREAM] client disconnected");
                try { os.close(); } catch (IOException ignored) {}
            }
        }
    }

    // ── RF monitor start / stop / SSE stream ─────────────────────────────────

    static final class RfMonitorStartHandler implements HttpHandler {
        private final DeviceState deviceState;
        RfMonitorStartHandler(DeviceState deviceState) { this.deviceState = deviceState; }

        public void handle(HttpExchange ex) throws IOException {
            if (!HttpUtils.requireMethod(ex, "POST")) return;
            ChipStatsMonitor mon = deviceState.getChipStatsMonitor();
            if (mon == null) {
                HttpUtils.send(ex, 503, "application/json", "{\"ok\":false,\"error\":\"RF monitor not initialized\"}");
                return;
            }
            boolean started = mon.start();
            AppLog.info("[WEB] rf-monitor start already=" + !started);
            HttpUtils.send(ex, 200, "application/json",
                String.format("{\"ok\":true,\"running\":true,\"started\":%b}", started));
        }
    }

    static final class RfMonitorStopHandler implements HttpHandler {
        private final DeviceState deviceState;
        RfMonitorStopHandler(DeviceState deviceState) { this.deviceState = deviceState; }

        public void handle(HttpExchange ex) throws IOException {
            if (!HttpUtils.requireMethod(ex, "POST")) return;
            ChipStatsMonitor mon = deviceState.getChipStatsMonitor();
            if (mon == null) {
                HttpUtils.send(ex, 503, "application/json", "{\"ok\":false,\"error\":\"RF monitor not initialized\"}");
                return;
            }
            boolean stopped = mon.stop();
            AppLog.info("[WEB] rf-monitor stop wasRunning=" + stopped);
            HttpUtils.send(ex, 200, "application/json",
                String.format("{\"ok\":true,\"running\":false,\"stopped\":%b}", stopped));
        }
    }

    static final class RfMonitorStreamHandler implements HttpHandler {
        private static final long POLL_TIMEOUT_MS = 5_000L;
        private static final int  MAX_IDLE_POLLS  = 6;
        private final DeviceState deviceState;
        RfMonitorStreamHandler(DeviceState deviceState) { this.deviceState = deviceState; }

        public void handle(HttpExchange ex) throws IOException {
            if (!HttpUtils.requireMethod(ex, "GET")) return;
            ChipStatsMonitor mon = deviceState.getChipStatsMonitor();
            if (mon == null) {
                HttpUtils.send(ex, 503, "text/plain", "RF monitor not initialized");
                return;
            }

            ex.getResponseHeaders().set("Content-Type",  "text/event-stream; charset=UTF-8");
            ex.getResponseHeaders().set("Cache-Control", "no-cache");
            ex.getResponseHeaders().set("Connection",    "keep-alive");
            ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            ex.sendResponseHeaders(200, 0);

            OutputStream os = ex.getResponseBody();
            AppLog.info("[RF-STREAM] client connected");
            int idleCount = 0;
            try {
                String init = "{\"type\":\"rf-init\",\"running\":" + mon.isRunning() + "}";
                os.write(("data: " + init + "\n\n").getBytes(StandardCharsets.UTF_8));
                os.flush();

                while (true) {
                    String line;
                    try {
                        line = mon.pollLine(POLL_TIMEOUT_MS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    if (line == null) {
                        idleCount++;
                        os.write(": heartbeat\n\n".getBytes(StandardCharsets.UTF_8));
                        os.flush();
                        if (idleCount >= MAX_IDLE_POLLS && !mon.isRunning()) break;
                        continue;
                    }
                    idleCount = 0;
                    os.write(("data: " + line + "\n\n").getBytes(StandardCharsets.UTF_8));
                    os.flush();
                }
            } catch (IOException ignored) {
            } finally {
                AppLog.info("[RF-STREAM] client disconnected");
                try { os.close(); } catch (IOException ignored) {}
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

    static final class BooksPageHandler implements HttpHandler {
        public void handle(HttpExchange ex) throws IOException {
            if (!HttpUtils.requireMethod(ex, "GET")) return;
            ex.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
            ex.getResponseHeaders().set("Cache-Control", "no-store, no-cache, must-revalidate");
            ex.getResponseHeaders().set("Pragma", "no-cache");
            ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            HttpUtils.writeResponse(ex, 200, BooksView.BYTES);
        }
    }

    static final class ListBooksHandler implements HttpHandler {
        private final DeviceState deviceState;
        ListBooksHandler(DeviceState deviceState) { this.deviceState = deviceState; }

        public void handle(HttpExchange ex) throws IOException {
            if (!HttpUtils.requireMethod(ex, "GET")) return;
            AppDataManager dm = deviceState.getDataManager();
            if (dm == null) {
                HttpUtils.send(ex, 503, "application/json", "{\"error\":\"DataManager not initialized\"}");
                return;
            }

            File booksDir = dm.getBooksDir();
            List<AppDataManager.BookItem> books = dm.listBooks();
            long totalMobiSize = 0;

            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < books.size(); i++) {
                AppDataManager.BookItem b = books.get(i);
                totalMobiSize += b.size;
                String dateStr = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm")
                    .format(new java.util.Date(b.lastModified));

                if (i > 0) sb.append(",");
                sb.append("{")
                  .append("\"name\":\"").append(HttpUtils.escapeJson(b.name)).append("\",")
                  .append("\"size\":").append(b.size).append(",")
                  .append("\"sizeFormatted\":\"").append(HttpUtils.escapeJson(formatSize(b.size))).append("\",")
                  .append("\"lastModified\":").append(b.lastModified).append(",")
                  .append("\"date\":\"").append(dateStr).append("\"")
                  .append("}");
            }
            sb.append("]");

            long freeSpace = booksDir.getFreeSpace();
            long totalSpace = booksDir.getTotalSpace();

            String json = String.format(
                "{\"books\":%s,\"count\":%d,\"totalMobiSize\":%d,\"totalMobiSizeFormatted\":\"%s\"," +
                "\"freeSpace\":%d,\"freeSpaceFormatted\":\"%s\"," +
                "\"totalSpace\":%d,\"totalSpaceFormatted\":\"%s\"}",
                sb.toString(), books.size(), totalMobiSize, HttpUtils.escapeJson(formatSize(totalMobiSize)),
                freeSpace, HttpUtils.escapeJson(formatSize(freeSpace)),
                totalSpace, HttpUtils.escapeJson(formatSize(totalSpace))
            );

            HttpUtils.send(ex, 200, "application/json", json);
        }

        private static String formatSize(long bytes) {
            if (bytes < 1024) return bytes + " B";
            if (bytes < 1024 * 1024) return String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0);
            if (bytes < 1024 * 1024 * 1024) return String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
            return String.format(java.util.Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
        }
    }

    static final class DeleteBooksHandler implements HttpHandler {
        private final DeviceState deviceState;
        DeleteBooksHandler(DeviceState deviceState) { this.deviceState = deviceState; }

        public void handle(HttpExchange ex) throws IOException {
            if (!HttpUtils.requireMethod(ex, "POST")) return;
            AppDataManager dm = deviceState.getDataManager();
            if (dm == null) {
                HttpUtils.send(ex, 503, "application/json", "{\"ok\":false,\"error\":\"DataManager not ready\"}");
                return;
            }

            byte[] bodyBytes = HttpUtils.readBody(ex);
            String body = new String(bodyBytes, StandardCharsets.UTF_8).trim();

            List<String> names = new ArrayList<>();
            if (body.startsWith("[")) {
                String content = body.substring(1, body.length() - (body.endsWith("]") ? 1 : 0));
                for (String item : content.split(",")) {
                    String s = item.trim().replaceAll("^\"|\"$", "");
                    if (!s.isEmpty()) names.add(s);
                }
            } else if (!body.isEmpty()) {
                names.add(body);
            }

            int deletedCount = 0;
            List<String> failed = new ArrayList<>();
            for (String name : names) {
                if (dm.deleteBook(name)) {
                    deletedCount++;
                } else {
                    failed.add(name);
                }
            }

            String json = String.format(
                "{\"ok\":true,\"deletedCount\":%d,\"failedCount\":%d}",
                deletedCount, failed.size());
            HttpUtils.send(ex, 200, "application/json", json);
        }
    }

    static final class UploadBookHandler implements HttpHandler {
        private final DeviceState deviceState;
        UploadBookHandler(DeviceState deviceState) { this.deviceState = deviceState; }

        public void handle(HttpExchange ex) throws IOException {
            if (!HttpUtils.requireMethod(ex, "POST")) return;
            AppDataManager dm = deviceState.getDataManager();
            if (dm == null) {
                HttpUtils.send(ex, 503, "application/json", "{\"ok\":false,\"error\":\"DataManager not ready\"}");
                return;
            }

            String filename = null;
            String query = ex.getRequestURI().getQuery();
            if (query != null && query.contains("name=")) {
                for (String p : query.split("&")) {
                    if (p.startsWith("name=")) {
                        filename = HttpUtils.urlDecode(p.substring(5));
                        break;
                    }
                }
            }

            if (filename == null || filename.trim().isEmpty()) {
                filename = ex.getRequestHeaders().getFirst("X-Filename");
                if (filename != null) {
                    if (filename.contains("%")) {
                        try { filename = java.net.URLDecoder.decode(filename, "UTF-8"); } catch (Exception ignored) {}
                    } else {
                        try {
                            byte[] b = filename.getBytes(StandardCharsets.ISO_8859_1);
                            String dec = new String(b, StandardCharsets.UTF_8);
                            if (!dec.contains("\uFFFD")) filename = dec;
                        } catch (Exception ignored) {}
                    }
                }
            }

            if (filename == null || filename.trim().isEmpty()) {
                filename = "uploaded_book_" + System.currentTimeMillis() + ".mobi";
            }

            filename = new File(filename).getName();
            String fnLower = filename.toLowerCase();
            boolean hasKnownExt = fnLower.endsWith(".mobi") || fnLower.endsWith(".azw") || fnLower.endsWith(".azw3")
                               || fnLower.endsWith(".pdf") || fnLower.endsWith(".epub") || fnLower.endsWith(".txt");
            if (!hasKnownExt) {
                filename = filename + ".mobi";
            }

            File targetFile = new File(dm.getBooksDir(), filename);

            long totalBytes = 0;
            try (InputStream is = ex.getRequestBody();
                 OutputStream fos = new FileOutputStream(targetFile)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) != -1) {
                    fos.write(buf, 0, n);
                    totalBytes += n;
                }
            } catch (IOException e) {
                if (targetFile.exists()) targetFile.delete();
                HttpUtils.send(ex, 500, "application/json",
                    String.format("{\"ok\":false,\"error\":\"Save failed: %s\"}", HttpUtils.escapeJson(e.getMessage())));
                return;
            }

            HttpUtils.send(ex, 200, "application/json",
                String.format("{\"ok\":true,\"name\":\"%s\",\"size\":%d}",
                    HttpUtils.escapeJson(filename), totalBytes));
        }
    }

    static final class DownloadBookHandler implements HttpHandler {
        private final DeviceState deviceState;
        DownloadBookHandler(DeviceState deviceState) { this.deviceState = deviceState; }

        public void handle(HttpExchange ex) throws IOException {
            if (!HttpUtils.requireMethod(ex, "GET")) return;
            AppDataManager dm = deviceState.getDataManager();
            if (dm == null) {
                HttpUtils.send(ex, 503, "text/plain", "DataManager not ready");
                return;
            }

            String query = ex.getRequestURI().getQuery();
            String name = null;
            if (query != null) {
                for (String p : query.split("&")) {
                    if (p.startsWith("name=")) {
                        name = HttpUtils.urlDecode(p.substring(5));
                        break;
                    }
                }
            }

            AppDataManager.BookItem book = dm.getBookItem(name);
            if (book == null || book.path == null || !java.nio.file.Files.exists(book.path)) {
                HttpUtils.send(ex, 404, "text/plain", "Book not found");
                return;
            }

            ex.getResponseHeaders().set("Content-Type", HttpUtils.mimeForFilename(book.name));
            ex.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"" + HttpUtils.escapeHtml(book.name) + "\"");
            ex.getResponseHeaders().set("Content-Length", String.valueOf(book.size));

            ex.sendResponseHeaders(200, book.size);
            try (InputStream is = java.nio.file.Files.newInputStream(book.path);
                 OutputStream os = ex.getResponseBody()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) != -1) {
                    os.write(buf, 0, n);
                }
            }
        }
    }
}
