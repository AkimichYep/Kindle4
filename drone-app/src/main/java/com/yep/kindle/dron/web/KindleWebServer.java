package com.yep.kindle.dron.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.yep.kindle.dron.util.SensorReader;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.Executors;

/**
 * Lightweight HTTP server for Kindle device control and monitoring.
 * Port 8080, 2-thread pool (conserves RAM on constrained device).
 *
 * Endpoints:
 *   GET  /                   — dashboard HTML (loads sensor data once)
 *   GET  /gallery            — image gallery HTML
 *   GET  /api/status         — JSON: battery, charging, batteryTemp, temperature, status, message
 *   POST /api/message        — send overlay message to Kindle screen (body = plain text)
 *   POST /api/next-page      — advance to next display page
 *   GET  /api/img/<name>     — serve image from img/ folder
 *   GET  /health             — "ok"
 */
public class KindleWebServer {

    private static final int PORT = 8080;

    private final DeviceState deviceState;
    private HttpServer        server;
    private volatile boolean  running = false;

    public KindleWebServer(DeviceState deviceState) {
        this.deviceState = deviceState;
    }

    public void start() throws IOException {
        if (running) return;
        server = HttpServer.create(new InetSocketAddress("0.0.0.0", PORT), 16);
        server.createContext("/",              new RootHandler());
        server.createContext("/gallery",       new GalleryHandler());
        server.createContext("/api/status",    new StatusHandler());
        server.createContext("/api/message",   new MessageHandler());
        server.createContext("/api/next-page", new NextPageHandler());
        server.createContext("/api/img/",      new ImageServeHandler());
        server.createContext("/health",        new HealthHandler());
        server.setExecutor(Executors.newFixedThreadPool(2));
        server.start();
        running = true;
        System.out.println("=== Web UI: http://0.0.0.0:" + PORT + " ===");
    }

    public void stop() {
        if (!running) return;
        server.stop(3);
        running = false;
    }

    public boolean isRunning() { return running; }

    // =========================================================================
    // Handlers
    // =========================================================================

    private class RootHandler implements HttpHandler {
        public void handle(HttpExchange ex) throws IOException {
            if (!"GET".equals(ex.getRequestMethod())) { send(ex, 405, "text/plain", "Method Not Allowed"); return; }
            send(ex, 200, "text/html; charset=UTF-8", buildDashboardHtml());
        }
    }

    private class GalleryHandler implements HttpHandler {
        public void handle(HttpExchange ex) throws IOException {
            if (!"GET".equals(ex.getRequestMethod())) { send(ex, 405, "text/plain", "Method Not Allowed"); return; }
            send(ex, 200, "text/html; charset=UTF-8", buildGalleryHtml());
        }
    }

    /** Reads sensors fresh on every request and returns full payload. */
    private class StatusHandler implements HttpHandler {
        public void handle(HttpExchange ex) throws IOException {
            if (!"GET".equals(ex.getRequestMethod())) { send(ex, 405, "text/plain", "Method Not Allowed"); return; }
            int    battery  = SensorReader.readBatteryPercent();
            int    charging = SensorReader.readIsCharging();
            double battTemp = SensorReader.readBatteryTemperatureCelsius();
            int    roomTemp = SensorReader.readRoomTemperatureCelsius();
            String json = String.format(
                "{\"battery\":%d,\"charging\":%d,\"batteryTemp\":%.1f,\"temperature\":%d," +
                "\"status\":\"%s\",\"userMessage\":\"%s\",\"ts\":%d}",
                battery, charging, battTemp, roomTemp,
                escapeJson(deviceState.getStatusMessage()),
                escapeJson(deviceState.getLastMessage()),
                System.currentTimeMillis());
            send(ex, 200, "application/json", json);
        }
    }

    private class MessageHandler implements HttpHandler {
        public void handle(HttpExchange ex) throws IOException {
            if (!"POST".equals(ex.getRequestMethod())) { send(ex, 405, "text/plain", "Method Not Allowed"); return; }
            String text = null;
            // Accept body as plain text (primary) or ?text= query param (compat)
            byte[] body = readBody(ex);
            if (body.length > 0) text = new String(body, StandardCharsets.UTF_8).trim();
            if (text == null || text.isEmpty()) {
                String query = ex.getRequestURI().getQuery();
                if (query != null) {
                    for (String p : query.split("&")) {
                        if (p.startsWith("text=")) { text = urlDecode(p.substring(5)); break; }
                    }
                }
            }
            if (text == null || text.trim().isEmpty()) { send(ex, 400, "text/plain", "Empty message"); return; }
            text = text.trim();
            if (text.length() > 200) text = text.substring(0, 200);
            deviceState.setMessage(text);
            send(ex, 200, "application/json", String.format("{\"ok\":true,\"message\":\"%s\"}", escapeJson(text)));
        }
    }

    private class NextPageHandler implements HttpHandler {
        public void handle(HttpExchange ex) throws IOException {
            if (!"POST".equals(ex.getRequestMethod())) { send(ex, 405, "text/plain", "Method Not Allowed"); return; }
            long seq = deviceState.requestNextPage();
            send(ex, 200, "application/json", String.format("{\"ok\":true,\"seq\":%d}", seq));
        }
    }

    private class ImageServeHandler implements HttpHandler {
        public void handle(HttpExchange ex) throws IOException {
            String path = ex.getRequestURI().getPath();
            String filename = path.substring("/api/img/".length());
            AppDataManager dm = deviceState.getDataManager();
            if (dm == null) { send(ex, 503, "text/plain", "Not ready"); return; }
            File imgFile = dm.getImageFile(filename);
            if (imgFile == null) { send(ex, 404, "text/plain", "Not found"); return; }
            try {
                byte[] data = Files.readAllBytes(imgFile.toPath());
                sendBinary(ex, 200, mimeForFilename(filename), data);
            } catch (IOException e) {
                send(ex, 500, "text/plain", "Read error");
            }
        }
    }

    private class HealthHandler implements HttpHandler {
        public void handle(HttpExchange ex) throws IOException {
            send(ex, 200, "text/plain", "ok");
        }
    }

    // =========================================================================
    // HTML builders
    // =========================================================================

    private String buildDashboardHtml() {
        return "<!DOCTYPE html><html lang='en'><head>" +
            "<meta charset='UTF-8'>" +
            "<meta name='viewport' content='width=device-width,initial-scale=1'>" +
            "<title>Kindle Drone</title>" +
            "<style>" +
            "*{margin:0;padding:0;box-sizing:border-box}" +
            "body{font-family:system-ui,Arial,sans-serif;background:#111827;color:#e5e7eb;min-height:100vh;padding:16px}" +
            ".wrap{max-width:480px;margin:0 auto}" +
            "h1{text-align:center;font-size:20px;font-weight:700;margin-bottom:14px;color:#93c5fd;letter-spacing:.5px}" +
            ".nav{display:flex;gap:8px;justify-content:center;margin-bottom:18px}" +
            ".nav a{padding:5px 14px;background:#1e3a5f;color:#93c5fd;text-decoration:none;border-radius:20px;font-size:13px;font-weight:600}" +
            ".nav a:hover{background:#1d4ed8}" +
            ".batt-card{background:#1e293b;border-radius:10px;padding:16px;margin-bottom:12px}" +
            ".batt-card.warn{background:#450a0a}" +
            ".batt-header{display:flex;justify-content:space-between;align-items:center;margin-bottom:8px}" +
            ".batt-pct{font-size:36px;font-weight:800;line-height:1}" +
            ".charge-badge{font-size:12px;font-weight:600;color:#34d399;padding:3px 8px;background:#064e3b;border-radius:12px}" +
            ".batt-bar{height:8px;background:rgba(255,255,255,.1);border-radius:4px;overflow:hidden;margin-bottom:8px}" +
            ".batt-fill{height:100%;border-radius:4px;transition:width .4s,background .4s}" +
            ".batt-sub{font-size:12px;color:#94a3b8}" +
            ".grid{display:grid;grid-template-columns:1fr 1fr;gap:10px;margin-bottom:12px}" +
            ".card{background:#1e293b;border-radius:10px;padding:14px;text-align:center}" +
            ".card-label{font-size:10px;text-transform:uppercase;letter-spacing:1.2px;color:#64748b;margin-bottom:6px}" +
            ".card-val{font-size:28px;font-weight:700;color:#e2e8f0}" +
            ".card-val.sm{font-size:20px}" +
            ".sec-label{font-size:10px;text-transform:uppercase;letter-spacing:1px;color:#64748b;margin-bottom:5px}" +
            ".info-box{background:#0f172a;border-radius:8px;padding:12px;font-size:13px;line-height:1.5;color:#94a3b8;margin-bottom:12px;min-height:36px;word-break:break-word}" +
            ".info-box.msg{color:#5eead4}" +
            ".input-row{display:flex;gap:8px;margin-bottom:12px}" +
            "input[type=text]{flex:1;padding:10px 12px;border:1px solid #1e293b;border-radius:8px;background:#0f172a;color:#e5e7eb;font-size:14px;outline:none}" +
            "input[type=text]:focus{border-color:#3b82f6}" +
            ".btn{padding:10px 16px;border:none;border-radius:8px;font-size:13px;font-weight:700;cursor:pointer;letter-spacing:.5px;text-transform:uppercase}" +
            ".btn-send{background:#1d4ed8;color:#fff}" +
            ".btn-send:active{background:#1e40af}" +
            ".actions{display:grid;grid-template-columns:1fr 1fr;gap:10px;margin-bottom:12px}" +
            ".btn-page{background:#065f46;color:#ecfdf5}" +
            ".btn-page:active{background:#064e3b}" +
            ".btn-refresh{background:#1e293b;color:#94a3b8;border:1px solid #334155}" +
            ".btn-refresh:active{background:#0f172a}" +
            ".toast{text-align:center;padding:8px 12px;border-radius:8px;font-size:12px;margin-top:4px;display:none}" +
            ".toast.ok{background:#064e3b;color:#6ee7b7;display:block}" +
            ".toast.err{background:#450a0a;color:#fca5a5;display:block}" +
            ".dot{display:inline-block;width:7px;height:7px;border-radius:50%;background:#4ade80;margin-left:6px;vertical-align:middle;animation:pulse 2s infinite}" +
            "@keyframes pulse{0%,100%{opacity:1}50%{opacity:.2}}" +
            "</style></head><body><div class='wrap'>" +
            "<h1>Kindle Drone<span class='dot'></span></h1>" +
            "<div class='nav'><a href='/'>Dashboard</a><a href='/gallery'>Gallery</a></div>" +

            // Battery card (full width)
            "<div class='batt-card' id='bcard'>" +
            "<div class='batt-header'>" +
            "<div><div style='font-size:11px;text-transform:uppercase;letter-spacing:1px;color:#64748b;margin-bottom:4px'>Battery</div>" +
            "<div class='batt-pct' id='bpct'>--</div></div>" +
            "<span class='charge-badge' id='cbadge' style='display:none'>\u26a1 Charging</span>" +
            "</div>" +
            "<div class='batt-bar'><div class='batt-fill' id='bfill' style='width:0'></div></div>" +
            "<div class='batt-sub'>Cell temp: <span id='btval'>--</span></div>" +
            "</div>" +

            // 2-col: room temp + charging
            "<div class='grid'>" +
            "<div class='card'><div class='card-label'>Room Temp</div><div class='card-val' id='tval'>--</div></div>" +
            "<div class='card'><div class='card-label'>Charging</div><div class='card-val sm' id='cval'>--</div></div>" +
            "</div>" +

            // Detector status
            "<div class='sec-label'>Detector status</div>" +
            "<div class='info-box' id='det'>Loading\u2026</div>" +

            // Last message
            "<div class='sec-label'>Last message on Kindle</div>" +
            "<div class='info-box msg' id='mdsp'>\u2014</div>" +

            // Send message
            "<div class='input-row'>" +
            "<input type='text' id='msgIn' placeholder='Message to Kindle screen\u2026' maxlength='200'/>" +
            "<button class='btn btn-send' onclick='sendMsg()'>Send</button>" +
            "</div>" +

            // Actions
            "<div class='actions'>" +
            "<button class='btn btn-page' onclick='nextPage()'>Next Page</button>" +
            "<button class='btn btn-refresh' onclick='refresh()'>Refresh</button>" +
            "</div>" +
            "<div class='toast' id='toast'></div>" +
            "</div>" +

            "<script>" +
            "document.addEventListener('DOMContentLoaded',function(){" +
            "  loadStatus();" +
            "  document.getElementById('msgIn').addEventListener('keydown',function(e){if(e.key==='Enter')sendMsg();});" +
            "});" +
            "function loadStatus(){" +
            "  fetch('/api/status')" +
            "    .then(function(r){return r.json();})" +
            "    .then(update)" +
            "    .catch(function(){document.getElementById('det').textContent='Offline \u2014 tap Refresh';});" +
            "}" +
            "function update(d){" +
            "  var b=d.battery;" +
            "  document.getElementById('bpct').textContent=b>=0?b+'%':'--';" +
            "  var f=document.getElementById('bfill');" +
            "  f.style.width=(b>=0?b:0)+'%';" +
            "  f.style.background=b>30?'#4ade80':b>15?'#facc15':'#f87171';" +
            "  document.getElementById('bcard').className='batt-card'+(b>=0&&b<=15?' warn':'');" +
            "  var chg=d.charging;" +
            "  var cb=document.getElementById('cbadge');" +
            "  cb.style.display=chg===1?'':'none';" +
            "  document.getElementById('cval').textContent=chg===1?'Yes \u26a1':chg===0?'No':'\u2014';" +
            "  var bt=d.batteryTemp;" +
            "  document.getElementById('btval').textContent=bt>=-0?bt.toFixed(1)+'\u00b0C':'--';" +
            "  var t=d.temperature;" +
            "  document.getElementById('tval').textContent=t>=0?t+'\u00b0C':'--';" +
            "  document.getElementById('det').textContent=d.status||'\u2014';" +
            "  var m=d.userMessage;" +
            "  document.getElementById('mdsp').textContent=m&&m.length?m:'\u2014';" +
            "}" +
            "function refresh(){loadStatus();}" +
            "function sendMsg(){" +
            "  var t=document.getElementById('msgIn').value.trim();" +
            "  if(!t){toast('Enter a message','err');return;}" +
            "  fetch('/api/message',{method:'POST',headers:{'Content-Type':'text/plain'},body:t})" +
            "    .then(function(r){return r.json();})" +
            "    .then(function(){" +
            "      toast('Sent!','ok');" +
            "      document.getElementById('msgIn').value='';" +
            "      document.getElementById('mdsp').textContent=t;" +
            "    }).catch(function(){toast('Error','err');});" +
            "}" +
            "function nextPage(){" +
            "  fetch('/api/next-page',{method:'POST'})" +
            "    .then(function(r){return r.json();})" +
            "    .then(function(){toast('Next page!','ok');})" +
            "    .catch(function(){toast('Error','err');});" +
            "}" +
            "function toast(msg,cls){" +
            "  var el=document.getElementById('toast');" +
            "  el.textContent=msg;el.className='toast '+cls;" +
            "  setTimeout(function(){el.className='toast';},3000);" +
            "}" +
            "</script></body></html>";
    }

    private String buildGalleryHtml() {
        AppDataManager dm = deviceState.getDataManager();
        StringBuilder items = new StringBuilder();
        if (dm != null) {
            File[] images = dm.listImageFiles();
            if (images != null) {
                for (File img : images) {
                    String name = escapeHtml(img.getName());
                    items.append("<div class='item'><a href='/api/img/").append(name)
                         .append("' target='_blank'><img src='/api/img/").append(name)
                         .append("' alt='").append(name).append("'/></a>")
                         .append("<div class='name'>").append(name).append("</div></div>\n");
                }
            }
        }
        if (items.length() == 0) {
            items.append("<p style='grid-column:1/-1;text-align:center;color:#64748b;padding:40px'>No images yet</p>");
        }

        return "<!DOCTYPE html><html lang='en'><head>" +
            "<meta charset='UTF-8'><meta name='viewport' content='width=device-width,initial-scale=1'>" +
            "<title>Gallery \u2014 Kindle Drone</title>" +
            "<style>" +
            "*{margin:0;padding:0;box-sizing:border-box}" +
            "body{font-family:system-ui,Arial,sans-serif;background:#111827;color:#e5e7eb;padding:16px}" +
            ".wrap{max-width:960px;margin:0 auto}" +
            "h1{text-align:center;font-size:20px;font-weight:700;margin-bottom:14px;color:#93c5fd}" +
            ".nav{display:flex;gap:8px;justify-content:center;margin-bottom:18px}" +
            ".nav a{padding:5px 14px;background:#1e3a5f;color:#93c5fd;text-decoration:none;border-radius:20px;font-size:13px;font-weight:600}" +
            ".nav a:hover{background:#1d4ed8}" +
            ".gallery{display:grid;grid-template-columns:repeat(auto-fill,minmax(200px,1fr));gap:14px}" +
            ".item{background:#1e293b;border-radius:8px;overflow:hidden}" +
            ".item a{display:block;height:150px;overflow:hidden;background:#0f172a}" +
            ".item img{width:100%;height:100%;object-fit:cover}" +
            ".item .name{padding:8px;font-size:11px;color:#64748b;text-align:center;word-break:break-all}" +
            "</style></head><body><div class='wrap'>" +
            "<h1>Image Gallery</h1>" +
            "<div class='nav'><a href='/'>Dashboard</a><a href='/gallery'>Gallery</a></div>" +
            "<div class='gallery'>" + items + "</div>" +
            "</div></body></html>";
    }

    // =========================================================================
    // HTTP utilities
    // =========================================================================

    private void send(HttpExchange ex, int code, String ct, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", ct);
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }

    private void sendBinary(HttpExchange ex, int code, String ct, byte[] data) throws IOException {
        ex.getResponseHeaders().set("Content-Type", ct);
        ex.getResponseHeaders().set("Cache-Control", "max-age=30");
        ex.sendResponseHeaders(code, data.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(data); }
    }

    private byte[] readBody(HttpExchange ex) throws IOException {
        try (InputStream is = ex.getRequestBody()) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[1024];
            int n;
            int total = 0;
            while ((n = is.read(chunk)) != -1 && total < 4096) {
                buf.write(chunk, 0, n);
                total += n;
            }
            return buf.toByteArray();
        }
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }

    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;")
                .replace("\"","&quot;").replace("'","&#39;");
    }

    private static String urlDecode(String s) {
        try { return java.net.URLDecoder.decode(s, "UTF-8"); }
        catch (Exception e) { return s; }
    }

    private static String mimeForFilename(String name) {
        String n = name.toLowerCase();
        if (n.endsWith(".png"))  return "image/png";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".gif")) return "image/gif";
        return "application/octet-stream";
    }
}
