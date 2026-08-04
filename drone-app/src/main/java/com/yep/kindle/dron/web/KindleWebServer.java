package com.yep.kindle.dron.web;

import com.sun.net.httpserver.HttpServer;
import com.yep.kindle.dron.KindleHomeTemp;
import com.yep.kindle.dron.KindleMoonCalendarNoKey;
import com.yep.kindle.dron.KindleSpaceWeatherNoKey;
import com.yep.kindle.dron.display.RadarRenderer;

import com.yep.kindle.dron.util.AppLog;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.Executors;

/**
 * Lightweight HTTP server for Kindle device control and monitoring.
 * Port 8080, 2-thread pool (conserves RAM on constrained device).
 *
 * Endpoints:
 *   GET  /                   — dashboard HTML
 *   GET  /gallery            — image gallery HTML
 *   GET  /logs               — log viewer HTML
 *   GET  /api/status         — JSON: battery, charging, batteryTemp, temperature, status, message
 *   POST /api/message        — send overlay message to Kindle screen (body = plain text)
 *   POST /api/next-page      — advance to next display page
 *   GET  /api/img/<name>     — serve image from img/ folder
 *   GET  /api/config         — JSON: current city config
 *   POST /api/config         — save city (plain text body)
 *   POST /api/weather-refresh  — geocode city, generate weather PNG, show on Kindle
 *   POST /api/hometemp-refresh    — read PMIC sensor, update history CSV, show hometemp PNG on Kindle
 *   POST /api/moon-refresh        — generate moon calendar PNG, show on Kindle
 *   POST /api/spaceweather-refresh — fetch NOAA space weather, show PNG on Kindle
 *   POST /api/radar-refresh       — re-display current radar.png on Kindle
 *   GET  /health             — "ok"
 */
public class KindleWebServer {

    private static final int PORT = 8080;

    private final DeviceState deviceState;
    private HttpServer        server;

    public KindleWebServer(DeviceState deviceState) {
        this.deviceState = deviceState;
    }

    public void start() throws IOException {
        if (server != null) return;
        HttpServer s = HttpServer.create(new InetSocketAddress("0.0.0.0", PORT), 16);
        s.createContext("/",              new WebHandlers.RootHandler());
        s.createContext("/gallery",       new WebHandlers.GalleryHandler(deviceState));
        s.createContext("/logs",          new WebHandlers.LogsHandler());
        s.createContext("/api/status",    new WebHandlers.StatusHandler(deviceState));
        s.createContext("/api/message",   new WebHandlers.MessageHandler(deviceState));
        s.createContext("/api/next-page", new WebHandlers.NextPageHandler(deviceState));
        s.createContext("/api/img/",             new WebHandlers.ImageServeHandler(deviceState));
        s.createContext("/api/config",           new WebHandlers.ConfigHandler(deviceState));
        s.createContext("/api/weather-refresh",  new WebHandlers.WeatherRefreshHandler(deviceState));
        s.createContext("/api/hometemp-refresh",     new WebHandlers.KindleViewHandler(deviceState, "hometemp.png",    "/mnt/us/drone-app/img/hometemp.png",    KindleHomeTemp::generateAndSave));
        s.createContext("/api/moon-refresh",          new WebHandlers.KindleViewHandler(deviceState, "moon.png",         "/mnt/us/drone-app/img/moon.png",         KindleMoonCalendarNoKey::generateAndSave));
        s.createContext("/api/spaceweather-refresh",  new WebHandlers.KindleViewHandler(deviceState, "spaceweather.png", "/mnt/us/drone-app/img/spaceweather.png", KindleSpaceWeatherNoKey::generateAndSave));
        s.createContext("/api/radar-refresh",         new WebHandlers.KindleViewHandler(deviceState, "radar.png",        RadarRenderer.IMAGE_FILE,                 imgPath -> {}));
        s.createContext("/api/logs",             new WebHandlers.LogsApiHandler());
        s.createContext("/api/logs/list",        new WebHandlers.LogsListHandler());
        s.createContext("/api/logs/view",        new WebHandlers.LogsViewHandler());
        s.createContext("/api/logs/file",        new WebHandlers.LogsFileHandler());
        s.createContext("/health",               new WebHandlers.HealthHandler());
        s.setExecutor(Executors.newFixedThreadPool(2));
        s.start();
        server = s;
        AppLog.info("=== Web UI: http://0.0.0.0:" + PORT + " ===");
    }

    public void stop() {
        if (server == null) return;
        server.stop(3);
        server = null;
    }

    public boolean isRunning() { return server != null; }
}
