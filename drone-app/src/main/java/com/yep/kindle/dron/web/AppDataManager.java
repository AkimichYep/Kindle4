package com.yep.kindle.dron.web;

import com.yep.kindle.dron.util.AppLog;

import java.io.*;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

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
    private final File booksDir;

    public AppDataManager(String basePath) {
        appDir    = new File(basePath);
        staticDir = new File(appDir, "static");
        dataDir   = new File(appDir, "data");
        logsDir   = new File(appDir, "logs");
        configDir = new File(appDir, "config");
        imgDir    = new File(appDir, "img");

        File docs = (appDir.getParentFile() != null) ? new File(appDir.getParentFile(), "documents") : null;
        if (docs != null && (docs.exists() || appDir.getAbsolutePath().startsWith("/mnt/us"))) {
            booksDir = docs;
        } else {
            booksDir = new File(appDir, "documents");
        }

        initFolders();
    }

    private void initFolders() {
        for (File d : new File[]{ appDir, staticDir, dataDir, logsDir, configDir, imgDir, booksDir }) {
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
    public File getConfigFile()       { return new File(configDir, "app.json"); }

    // ── Image helpers ─────────────────────────────────────────────────────────

    public File[] listImageFiles() {
        if (!imgDir.exists()) return new File[0];
        File[] files = imgDir.listFiles((dir, name) ->
            name.toLowerCase().matches(".*\\.(png|jpg|jpeg|gif|bmp)$"));
        return files != null ? files : new File[0];
    }

    /** Returnsnull if filename is unsafe or file does not exist. */
    public File getImageFile(String filename) {
        if (filename == null || filename.contains("..") ||
                filename.contains("/") || filename.contains("\\")) return null;
        File f = new File(imgDir, filename);
        return (f.exists() && f.isFile()) ? f : null;
    }

    // ── Books / Mobi helpers ──────────────────────────────────────────────────

    public static class BookItem {
        public final String name;
        public final Path path;
        public final long size;
        public final long lastModified;

        public BookItem(String name, Path path, long size, long lastModified) {
            this.name = name;
            this.path = path;
            this.size = size;
            this.lastModified = lastModified;
        }
    }

    public File getBooksDir() {
        return booksDir;
    }

    public static String extractUtf8Filename(Path path) {
        if (path == null) return "";
        Path fileNamePath = path.getFileName();
        if (fileNamePath == null) return path.toString();

        try {
            java.lang.reflect.Field pathField = fileNamePath.getClass().getDeclaredField("path");
            pathField.setAccessible(true);
            byte[] rawBytes = (byte[]) pathField.get(fileNamePath);
            if (rawBytes != null && rawBytes.length > 0) {
                String utf8 = new String(rawBytes, java.nio.charset.StandardCharsets.UTF_8);
                if (!utf8.contains("\uFFFD")) {
                    return utf8;
                }
            }
        } catch (Throwable ignored) {}

        return decodeFilename(fileNamePath.toString());
    }

    public static String decodeFilename(String name) {
        if (name == null) return "";
        boolean hasHighBytes = false;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c >= 0x0080 && c <= 0x00FF) {
                hasHighBytes = true;
                break;
            }
        }
        if (hasHighBytes) {
            try {
                byte[] bytes = new byte[name.length()];
                for (int i = 0; i < name.length(); i++) {
                    bytes[i] = (byte) (name.charAt(i) & 0xFF);
                }
                String utf8 = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
                if (!utf8.contains("\uFFFD")) {
                    return utf8;
                }
            } catch (Exception ignored) {}
        }
        return name;
    }

    public List<BookItem> listBooks() {
        List<BookItem> result = new ArrayList<>();
        if (!booksDir.exists()) return result;

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(booksDir.toPath())) {
            for (Path entry : stream) {
                if (Files.isDirectory(entry)) continue;

                String name = extractUtf8Filename(entry);
                String nLower = name.toLowerCase();
                boolean isBook = nLower.endsWith(".mobi") || nLower.endsWith(".azw") || nLower.endsWith(".azw3")
                              || nLower.endsWith(".pdf") || nLower.endsWith(".epub") || nLower.endsWith(".txt")
                              || nLower.endsWith(".fb2");

                if (!isBook) continue;

                long size = 0;
                long lastModified = 0;
                try {
                    size = Files.size(entry);
                } catch (Exception e) {
                    size = entry.toFile().length();
                }
                try {
                    lastModified = Files.getLastModifiedTime(entry).toMillis();
                } catch (Exception e) {
                    lastModified = entry.toFile().lastModified();
                }

                result.add(new BookItem(name, entry, size, lastModified));
            }
        } catch (IOException e) {
            AppLog.err("Error listing books in " + booksDir + ": " + e.getMessage());
        }

        result.sort((b1, b2) -> b1.name.compareToIgnoreCase(b2.name));
        AppLog.info("[Books] Scanned " + booksDir.getAbsolutePath() + ": found " + result.size() + " book(s)");
        return result;
    }

    public BookItem getBookItem(String filename) {
        if (filename == null || filename.contains("..") ||
                filename.contains("/") || filename.contains("\\")) return null;

        List<BookItem> list = listBooks();
        for (BookItem item : list) {
            if (item.name.equalsIgnoreCase(filename) || item.path.getFileName().toString().equalsIgnoreCase(filename)) {
                return item;
            }
        }
        Path direct = booksDir.toPath().resolve(filename);
        if (Files.exists(direct)) {
            long sz = 0, lm = 0;
            try { sz = Files.size(direct); } catch (Exception ignored) {}
            try { lm = Files.getLastModifiedTime(direct).toMillis(); } catch (Exception ignored) {}
            return new BookItem(extractUtf8Filename(direct), direct, sz, lm);
        }
        return null;
    }

    public boolean deleteBook(String filename) {
        BookItem item = getBookItem(filename);
        if (item != null && item.path != null) {
            try {
                Files.delete(item.path);
                AppLog.info("[Books] Deleted book file: " + item.name);
                return true;
            } catch (IOException e) {
                boolean del = item.path.toFile().delete();
                if (del) AppLog.info("[Books] Deleted book file (fallback): " + item.name);
                return del;
            }
        }
        return false;
    }

    // ── Persistence helpers ───────────────────────────────────────────────────

    public void saveDeviceState(DeviceState state) throws IOException {
        File f = new File(dataDir, "device_state.json");
        String json = String.format(
            "{\"message\":\"%s\",\"status\":\"%s\",\"ts\":%d}",
            HttpUtils.escapeJson(state.getLastMessage()),
            HttpUtils.escapeJson(state.getStatusMessage()),
            System.currentTimeMillis());
        Files.write(f.toPath(), json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    public AppConfig loadConfig() {
        return AppConfig.load(getConfigFile());
    }

    public void saveConfig(AppConfig cfg) throws IOException {
        cfg.save(getConfigFile());
    }

    public void writeLog(String message) throws IOException {
        File logFile = new File(logsDir, "web.log");
        String entry = "[" + LocalDateTime.now() + "] " + message + "\n";
        Files.write(logFile.toPath(), entry.getBytes(java.nio.charset.StandardCharsets.UTF_8),
            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

}
