package com.yep.kindle.dron.tool;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

import com.yep.kindle.dron.event.AppEvent;
import com.yep.kindle.dron.event.EventBus;

public class KindleTcpListener {
    private static final int PORT = 5555;
    private static final long DEFAULT_OVERLAY_MS = 20_000L;
    private static final String CONTROL_NEXT_REFRESH = "/control/next-refresh";
    private static volatile String lastMessage = "Waiting for message...";
    private static volatile OverlayState overlayState = null;
    private static volatile long overlaySeq = 0;
    private static volatile long refreshAndNextSeq = 0;
    private static volatile boolean started = false;

    public static final class OverlaySnapshot {
        public final long id;
        public final String message;
        public final long expiresAtEpochMs;

        OverlaySnapshot(long id, String message, long expiresAtEpochMs) {
            this.id = id;
            this.message = message;
            this.expiresAtEpochMs = expiresAtEpochMs;
        }
    }

    private static final class OverlayState {
        final long id;
        final String message;
        final long expiresAtEpochMs;

        OverlayState(long id, String message, long expiresAtEpochMs) {
            this.id = id;
            this.message = message;
            this.expiresAtEpochMs = expiresAtEpochMs;
        }
    }

    public static void main(String[] args) {
        runLoop();
    }

    public static synchronized boolean startAsync() {
        if (started) {
            return false;
        }
        started = true;
        Thread thread = new Thread(KindleTcpListener::runLoop, "kindle-tcp-listener");
        thread.setDaemon(true);
        thread.start();
        return true;
    }

    public static OverlaySnapshot getActiveOverlay() {
        OverlayState current = overlayState;
        long now = System.currentTimeMillis();
        if (current == null || current.expiresAtEpochMs <= now) {
            return null;
        }
        return new OverlaySnapshot(current.id, current.message, current.expiresAtEpochMs);
    }

    public static long publishOverlayMessage(String message, long durationMs) {
        String cleaned = stripQuotes(message);
        if (cleaned == null || cleaned.trim().isEmpty()) {
            return -1;
        }
        long ttl = durationMs > 0 ? durationMs : DEFAULT_OVERLAY_MS;
        long id;
        synchronized (KindleTcpListener.class) {
            id = ++overlaySeq;
            overlayState = new OverlayState(id, cleaned, System.currentTimeMillis() + ttl);
        }
        lastMessage = cleaned;
        // Wake up the main detector loop immediately via the event bus.
        EventBus.INSTANCE.post(AppEvent.overlayMessage(cleaned, "tcp"));
        return id;
    }

    public static synchronized long requestRefreshAndNextPage() {
        long seq = ++refreshAndNextSeq;
        // Wake up the main detector loop immediately via the event bus.
        EventBus.INSTANCE.post(AppEvent.pageAdvance("tcp"));
        return seq;
    }

    public static long getRefreshAndNextPageSeq() {
        return refreshAndNextSeq;
    }

    private static void runLoop() {
        System.out.println("=== Kindle TCP Drone Listener Active on port " + PORT + " ===");
        try {
            // Explicitly bind to 0.0.0.0 (all interfaces, including wlan0)
            ServerSocket serverSocket = new ServerSocket();
            serverSocket.setReuseAddress(true);
            serverSocket.bind(new InetSocketAddress("0.0.0.0", PORT));

            while (true) {
                try (Socket clientSocket = serverSocket.accept()) {
                    handleClient(clientSocket);
                } catch (Exception e) {
                    System.err.println("Read error: " + e.getMessage());
                }
            }
        } catch (Exception e) {
            System.err.println("Server exception: " + e.getMessage());
        }
    }

    private static void handleClient(Socket clientSocket) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(clientSocket.getInputStream(), StandardCharsets.UTF_8));
        String firstLine = reader.readLine();
        if (firstLine == null) {
            return;
        }

        if (isHttpRequestLine(firstLine)) {
            String path = extractRequestPath(firstLine);

            if ("/health".equals(path)) {
                sendHttpResponse(clientSocket.getOutputStream(), "200 OK", "text/plain; charset=UTF-8", "ok".getBytes(StandardCharsets.UTF_8));
                return;
            }

            if (pathEquals(path, CONTROL_NEXT_REFRESH) || pathStartsWith(path, CONTROL_NEXT_REFRESH + "?")) {
                long seq = requestRefreshAndNextPage();
                byte[] body = ("queued refresh+next seq=" + seq).getBytes(StandardCharsets.UTF_8);
                sendHttpResponse(clientSocket.getOutputStream(), "200 OK", "text/plain; charset=UTF-8", body);
                return;
            }

            // Serve weather icon font so the browser can render a weather-style header icon.
            if (path.startsWith("/font/")) {
                serveResource(clientSocket.getOutputStream(), path.substring(1));
                return;
            }

            String messageFromRequest = extractMessageFromPath(path);
            if (messageFromRequest != null && !messageFromRequest.trim().isEmpty()) {
                publishOverlayMessage(messageFromRequest, DEFAULT_OVERLAY_MS);
                logMessage("HTTP", lastMessage);
            }

            String body = buildHtml(lastMessage);
            sendHttpResponse(clientSocket.getOutputStream(), "200 OK", "text/html; charset=UTF-8", body.getBytes(StandardCharsets.UTF_8));
            return;
        }

        String parsed = parseTcpMessage(firstLine);
        if (isRefreshAndNextCommand(parsed)) {
            requestRefreshAndNextPage();
            return;
        }
        if (parsed != null && !parsed.trim().isEmpty()) {
            publishOverlayMessage(parsed, DEFAULT_OVERLAY_MS);
            logMessage("TCP", lastMessage);
        }
    }

    private static boolean isRefreshAndNextCommand(String parsed) {
        if (parsed == null) {
            return false;
        }
        String normalized = parsed.trim().toLowerCase();
        return "cmd:next-refresh".equals(normalized)
                || "next-refresh".equals(normalized)
                || "refresh-next".equals(normalized);
    }

    private static boolean isHttpRequestLine(String line) {
        return line.contains(" HTTP/") &&
               (line.startsWith("GET ") || line.startsWith("POST ") || line.startsWith("HEAD "));
    }

    private static String extractRequestPath(String requestLine) {
        String[] parts = requestLine.split(" ");
        if (parts.length < 2) {
            return "/";
        }
        return parts[1];
    }

    private static boolean pathEquals(String path, String target) {
        return target.equals(path);
    }

    private static boolean pathStartsWith(String path, String prefix) {
        return path != null && path.startsWith(prefix);
    }

    private static String extractMessageFromPath(String path) {
        if (path == null || path.isEmpty() || "/".equals(path)) {
            return null;
        }

        String decodedPath = urlDecode(path);

        // Supports: /?msg=Hello  or  /?message=Hello
        int queryIndex = decodedPath.indexOf('?');
        if (queryIndex >= 0 && queryIndex < decodedPath.length() - 1) {
            String query = decodedPath.substring(queryIndex + 1);
            String[] pairs = query.split("&");
            for (String pair : pairs) {
                int eq = pair.indexOf('=');
                if (eq > 0 && eq < pair.length() - 1) {
                    String key = pair.substring(0, eq);
                    String value = pair.substring(eq + 1);
                    if ("msg".equalsIgnoreCase(key) || "message".equalsIgnoreCase(key) || "text".equalsIgnoreCase(key)) {
                        return stripQuotes(urlDecode(value));
                    }
                }
            }
        }

        // Supports browser URL: http://host:5555/&"Hello"
        if (decodedPath.startsWith("/&")) {
            return stripQuotes(decodedPath.substring(2));
        }

        return null;
    }

    private static String parseTcpMessage(String line) {
        String decoded = urlDecode(line.trim());
        int amp = decoded.indexOf('&');
        if (amp >= 0 && amp < decoded.length() - 1) {
            return stripQuotes(decoded.substring(amp + 1));
        }
        return stripQuotes(decoded);
    }

    private static String stripQuotes(String value) {
        if (value == null) {
            return null;
        }
        String result = value.trim();
        if (result.length() >= 2 && result.startsWith("\"") && result.endsWith("\"")) {
            result = result.substring(1, result.length() - 1);
        }
        return result;
    }

    private static String urlDecode(String value) {
        try {
            return URLDecoder.decode(value, "UTF-8");
        } catch (UnsupportedEncodingException ignored) {
            return value;
        }
    }

    private static void logMessage(String source, String message) {
        String timestamp = LocalDateTime.now().toString();
        System.out.println("[" + timestamp + "] " + source + " RECEIVED: " + message);
    }

    private static String buildHtml(String message) {
        String safe = htmlEscape(message);
        return "<!doctype html><html><head><meta charset=\"utf-8\">"
            + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
            + "<title>Kindle Drone Message</title>"
            + "<style>"
            + "@font-face{font-family:'weathericons';src:url('/font/weathericons-regular-webfont.ttf') format('truetype');}"
            + "body{margin:0;background:#fff;color:#111;display:flex;min-height:100vh;align-items:center;justify-content:center;}"
            + ".wrap{text-align:center;padding:24px;}"
            + ".title{font-family:'weathericons',sans-serif;font-size:54px;line-height:1;margin-bottom:18px;}"
            + ".msg{font-family:'Comic Sans MS','Chalkboard SE','Marker Felt',cursive;font-size:56px;font-weight:700;line-height:1.2;}"
            + ".hint{margin-top:16px;font-family:Arial,sans-serif;font-size:14px;color:#555;}"
            + "</style></head><body><div class=\"wrap\">"
            + "<div class=\"title\">&#xf00d;</div>"
            + "<div class=\"msg\">" + safe + "</div>"
            + "<div class=\"hint\">Try: /?msg=Hello or /&quot;Hello&quot;</div>"
            + "</div></body></html>";
    }

    private static String htmlEscape(String raw) {
        if (raw == null) {
            return "";
        }
        return raw
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;");
    }

    private static void serveResource(OutputStream out, String resourcePath) throws IOException {
        InputStream input = KindleTcpListener.class.getClassLoader().getResourceAsStream(resourcePath);
        if (input == null) {
            sendHttpResponse(out, "404 Not Found", "text/plain; charset=UTF-8", "Not found".getBytes(StandardCharsets.UTF_8));
            return;
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = input.read(buffer)) != -1) {
            baos.write(buffer, 0, read);
        }

        String contentType = "application/octet-stream";
        if (resourcePath.endsWith(".ttf")) {
            contentType = "font/ttf";
        } else if (resourcePath.endsWith(".woff")) {
            contentType = "font/woff";
        } else if (resourcePath.endsWith(".woff2")) {
            contentType = "font/woff2";
        }

        sendHttpResponse(out, "200 OK", contentType, baos.toByteArray());
    }

    private static void sendHttpResponse(OutputStream out, String status, String contentType, byte[] body) throws IOException {
        String headers = "HTTP/1.1 " + status + "\r\n"
            + "Content-Type: " + contentType + "\r\n"
            + "Content-Length: " + body.length + "\r\n"
            + "Connection: close\r\n\r\n";
        out.write(headers.getBytes(StandardCharsets.UTF_8));
        out.write(body);
        out.flush();
    }
}