package com.yep.kindle.dron.web;

import java.io.*;
import java.nio.file.*;
import java.time.LocalDateTime;

/**
 * File-system bookkeeping for /mnt/us/drone-app/.
 * Creates the directory tree on startup and provides typed accessors for each
 * sub-folder so path strings never appear elsewhere in the code.
 */
public class AppDataManager {

    private final File appDir;
    private final File staticDir;
    private final File dataDir;
    private final File logsDir;
    private final File configDir;
    private final File imgDir;

    public AppDataManager(String basePath) {
        appDir    = new File(basePath);
        staticDir = new File(appDir, "static");
        dataDir   = new File(appDir, "data");
        logsDir   = new File(appDir, "logs");
        configDir = new File(appDir, "config");
        imgDir    = new File(appDir, "img");
        initFolders();
    }

    private void initFolders() {
        for (File d : new File[]{ appDir, staticDir, dataDir, logsDir, configDir, imgDir }) {
            if (!d.exists()) d.mkdirs();
        }
    }

    // ── Directory accessors ───────────────────────────────────────────────────

    public File getAppDir()    { return appDir; }
    public File getStaticDir() { return staticDir; }
    public File getDataDir()   { return dataDir; }
    public File getLogsDir()   { return logsDir; }
    public File getConfigDir() { return configDir; }
    public File getImgDir()    { return imgDir; }

    // ── Well-known file accessors ─────────────────────────────────────────────

    public File getCsvFile()          { return new File(dataDir, "drone_nets.csv"); }
    public File getCsvTempFile()      { return new File(dataDir, "drone_nets.csv.tmp"); }
    public File getHomeTempCsvFile()  { return new File(dataDir, "hometemp.csv"); }

    // ── Image helpers ─────────────────────────────────────────────────────────

    public File[] listImageFiles() {
        if (!imgDir.exists()) return new File[0];
        File[] files = imgDir.listFiles((dir, name) ->
            name.toLowerCase().matches(".*\\.(png|jpg|jpeg|gif|bmp)$"));
        return files != null ? files : new File[0];
    }

    /** Returns null if filename is unsafe or file does not exist. */
    public File getImageFile(String filename) {
        if (filename == null || filename.contains("..") ||
                filename.contains("/") || filename.contains("\\")) return null;
        File f = new File(imgDir, filename);
        return (f.exists() && f.isFile()) ? f : null;
    }

    // ── Persistence helpers ───────────────────────────────────────────────────

    public void saveDeviceState(DeviceState state) throws IOException {
        File f = new File(dataDir, "device_state.json");
        String json = String.format(
            "{\"message\":\"%s\",\"status\":\"%s\",\"ts\":%d}",
            escapeJson(state.getLastMessage()),
            escapeJson(state.getStatusMessage()),
            System.currentTimeMillis());
        Files.write(f.toPath(), json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    public void writeLog(String message) throws IOException {
        File logFile = new File(logsDir, "web.log");
        String entry = "[" + LocalDateTime.now() + "] " + message + "\n";
        Files.write(logFile.toPath(), entry.getBytes(java.nio.charset.StandardCharsets.UTF_8),
            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }
}
