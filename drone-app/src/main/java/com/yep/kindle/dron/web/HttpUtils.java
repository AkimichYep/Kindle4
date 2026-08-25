package com.yep.kindle.dron.web;

import com.sun.net.httpserver.HttpExchange;

import java.io.*;
import java.nio.charset.StandardCharsets;

final class HttpUtils {

    private HttpUtils() {}

    static boolean requireMethod(HttpExchange ex, String method) throws IOException {
        if (method.equals(ex.getRequestMethod())) return true;
        send(ex, 405, "text/plain", "Method Not Allowed");
        return false;
    }

    static void send(HttpExchange ex, int code, String ct, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        if ((ct.startsWith("application/json") || ct.startsWith("text/")) && !ct.contains("charset")) {
            ct = ct + "; charset=UTF-8";
        }
        ex.getResponseHeaders().set("Content-Type", ct);
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        writeResponse(ex, code, bytes);
    }

    static void sendBinary(HttpExchange ex, int code, String ct, byte[] data) throws IOException {
        ex.getResponseHeaders().set("Content-Type", ct);
        ex.getResponseHeaders().set("Cache-Control", "max-age=30");
        writeResponse(ex, code, data);
    }

    static void writeResponse(HttpExchange ex, int code, byte[] data) throws IOException {
        ex.sendResponseHeaders(code, data.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(data); }
    }

    /** Reads text from POST body (primary) or ?text= query param (fallback). Returns null if empty. */
    static String readTextParam(HttpExchange ex) throws IOException {
        byte[] body = readBody(ex);
        if (body.length > 0) {
            String text = new String(body, StandardCharsets.UTF_8).trim();
            if (!text.isEmpty()) return text.length() > 200 ? text.substring(0, 200) : text;
        }
        String query = ex.getRequestURI().getQuery();
        if (query != null) {
            for (String p : query.split("&")) {
                if (p.startsWith("text=")) {
                    String text = urlDecode(p.substring(5)).trim();
                    return text.isEmpty() ? null : (text.length() > 200 ? text.substring(0, 200) : text);
                }
            }
        }
        return null;
    }

    static byte[] readBody(HttpExchange ex) throws IOException {
        try (InputStream is = ex.getRequestBody()) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[1024];
            int n, total = 0;
            while ((n = is.read(chunk)) != -1 && total < 4096) {
                buf.write(chunk, 0, n);
                total += n;
            }
            return buf.toByteArray();
        }
    }

    static String escapeJson(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 32) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }

    static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;")
                .replace("\"","&quot;").replace("'","&#39;");
    }

    static String urlDecode(String s) {
        try { return java.net.URLDecoder.decode(s, "UTF-8"); }
        catch (Exception e) { return s; }
    }

    static String mimeForFilename(String name) {
        String n = name.toLowerCase();
        if (n.endsWith(".png"))  return "image/png";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".gif")) return "image/gif";
        if (n.endsWith(".mobi") || n.endsWith(".azw") || n.endsWith(".azw3")) return "application/x-mobipocket-ebook";
        if (n.endsWith(".pdf")) return "application/pdf";
        return "application/octet-stream";
    }
}
