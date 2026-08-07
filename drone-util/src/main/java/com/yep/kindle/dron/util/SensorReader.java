package com.yep.kindle.dron.util;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.FileWriter;

/**
 * Reads real hardware sensor values from a Kindle 4 device.
 * All methods return sentinel values (-1 or -1.0) when the sensor is unavailable.
 *
 * Confirmed working paths (tested on actual device):
 *   Battery %   : lipc-get-prop -i com.lab126.powerd battLevel        → clean int
 *   Charging    : lipc-get-prop -i com.lab126.powerd isCharging       → 0 or 1
 *   Room temp   : /sys/bus/i2c/devices/1-0048/papyrus_temperature     → °C int
 *   Batt temp   : yoshi_battery0/battery_temperature                  → °F int (confirmed 78°F = 25.6°C)
 */
public class SensorReader {

    private static final String BATTERY_SYSFS =
        "/sys/devices/system/yoshi_battery/yoshi_battery0/battery_capacity";
    private static final String BATTERY_TEMP_SYSFS =
        "/sys/devices/system/yoshi_battery/yoshi_battery0/battery_temperature";
    private static final String ROOM_TEMP_SYSFS =
        "/sys/bus/i2c/devices/1-0048/papyrus_temperature";
    private static final String BATTERY_SUSPEND_CURRENT_SYSFS =
        "/sys/devices/system/yoshi_battery/yoshi_battery0/battery_suspend_current";

    /** Battery charge 0-100. Returns -1 if unreadable. */
    public static int readBatteryPercent() {
        String out = KindleUtils.readCommand(
            "lipc-get-prop", "-i", "com.lab126.powerd", "battLevel");
        if (out != null && !out.isEmpty()) {
            try {
                int v = Integer.parseInt(out);
                if (v >= 0 && v <= 100) return v;
            } catch (NumberFormatException ignored) {}
        }
        // Fallback: yoshi sysfs returns e.g. "69%"
        String raw = readSysfs(BATTERY_SYSFS);
        if (raw != null) {
            try {
                int v = Integer.parseInt(raw.replace("%", "").trim());
                if (v >= 0 && v <= 100) return v;
            } catch (NumberFormatException ignored) {}
        }
        return -1;
    }

    /** Charging status: 1=charging, 0=not charging, -1=unknown. */
    public static int readIsCharging() {
        String out = KindleUtils.readCommand(
            "lipc-get-prop", "-i", "com.lab126.powerd", "isCharging");
        if (out != null && !out.isEmpty()) {
            try { return Integer.parseInt(out.trim()) != 0 ? 1 : 0; }
            catch (NumberFormatException ignored) {}
        }
        return -1;
    }

    /** Room (panel) temperature in °C. Returns -1 if unreadable. */
    public static int readRoomTemperatureCelsius() {
        String raw = readSysfs(ROOM_TEMP_SYSFS);
        if (raw != null) {
            try { return Integer.parseInt(raw.trim()); }
            catch (NumberFormatException ignored) {}
        }
        return -1;
    }

    /**
     * Battery cell temperature in °C (one decimal).
     * Returns -1.0 if unreadable.
     * The yoshi_battery driver reports in °F on Kindle 4 (78°F = 25.6°C confirmed).
     */
    public static double readBatteryTemperatureCelsius() {
        String raw = readSysfs(BATTERY_TEMP_SYSFS);
        if (raw != null) {
            try {
                int fahrenheit = Integer.parseInt(raw.trim());
                return Math.round((fahrenheit - 32.0) * 5.0 / 9.0 * 10.0) / 10.0;
            } catch (NumberFormatException ignored) {}
        }
        return -1.0;
    }

    private static String readSysfs(String path) {
        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            return br.readLine();
        } catch (Exception e) {
            return null;
        }
    }

    // ── CPU utilisation ───────────────────────────────────────────────────────

    /**
     * Holds a /proc/stat snapshot for differential CPU measurement.
     * Fields mirror the first "cpu" line: user nice system idle iowait irq softirq.
     */
    public static final class CpuSnapshot {
        public final long idle;
        public final long total;

        private CpuSnapshot(long idle, long total) {
            this.idle  = idle;
            this.total = total;
        }
    }

    /**
     * Take a raw /proc/stat CPU snapshot (non-blocking, returns instantly).
     * Returns {@code null} if the file is unreadable (e.g. on non-Linux host).
     *
     * <p>Usage pattern for correct utilisation measurement:</p>
     * <pre>{@code
     *   CpuSnapshot s1 = SensorReader.readCpuSnapshot();
     *   Thread.sleep(500);  // or use the scheduler — no busy-wait
     *   CpuSnapshot s2 = SensorReader.readCpuSnapshot();
     *   int pct = SensorReader.cpuPercent(s1, s2);
     * }</pre>
     */
    public static CpuSnapshot readCpuSnapshot() {
        try (BufferedReader br = new BufferedReader(new FileReader("/proc/stat"))) {
            String line = br.readLine(); // first line: "cpu  user nice sys idle iowait irq softirq ..."
            if (line == null || !line.startsWith("cpu")) return null;
            String[] tok = line.trim().split("\\s+");
            if (tok.length < 5) return null;
            // tok[0]="cpu", tok[1]=user, tok[2]=nice, tok[3]=system, tok[4]=idle,
            // tok[5]=iowait (count as idle), tok[6]=irq, tok[7]=softirq, tok[8]=steal
            long user    = parseLong(tok, 1);
            long nice    = parseLong(tok, 2);
            long system  = parseLong(tok, 3);
            long idle    = parseLong(tok, 4);
            long iowait  = parseLong(tok, 5);
            long irq     = parseLong(tok, 6);
            long softirq = parseLong(tok, 7);
            long steal   = parseLong(tok, 8);
            long totalIdle  = idle + iowait;
            long totalBusy  = user + nice + system + irq + softirq + steal;
            return new CpuSnapshot(totalIdle, totalIdle + totalBusy);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Calculate CPU utilisation percentage (0–100) between two snapshots.
     * Returns -1 if either snapshot is null or if the interval is zero.
     *
     * @param s1 earlier snapshot
     * @param s2 later snapshot (must be taken after s1)
     */
    public static int cpuPercent(CpuSnapshot s1, CpuSnapshot s2) {
        if (s1 == null || s2 == null) return -1;
        long totalDelta = s2.total - s1.total;
        if (totalDelta <= 0) return 0;
        long idleDelta = s2.idle - s1.idle;
        int pct = (int) Math.round(100.0 * (totalDelta - idleDelta) / totalDelta);
        return Math.max(0, Math.min(100, pct));
    }

    private static long parseLong(String[] arr, int idx) {
        if (idx >= arr.length) return 0;
        try { return Long.parseLong(arr[idx]); }
        catch (NumberFormatException e) { return 0; }
    }

    // ── RAM (from /proc/meminfo) ──────────────────────────────────────────────

    /**
     * Memory stats read from /proc/meminfo.
     * All values are in kibibytes (KiB).
     */
    public static final class MemInfo {
        /** Total physical RAM in KiB. */
        public final long totalKiB;
        /** "Available" RAM in KiB (MemAvailable, kernel 3.14+; falls back to MemFree). */
        public final long availableKiB;
        /** Used RAM = total - available, in KiB. */
        public final long usedKiB;
        /** Used RAM as percentage 0–100. */
        public final int  usedPercent;

        MemInfo(long totalKiB, long availableKiB) {
            this.totalKiB     = totalKiB;
            this.availableKiB = availableKiB;
            this.usedKiB      = Math.max(0, totalKiB - availableKiB);
            this.usedPercent  = totalKiB > 0
                    ? (int) Math.round(100.0 * usedKiB / totalKiB)
                    : -1;
        }
    }

    /**
     * Read memory statistics from /proc/meminfo.
     * Returns a sentinel {@code MemInfo(-1, -1)} on error.
     *
     * <p>Works on ejdk-8u211-linux-arm-sflt: no java.lang.management required.</p>
     *
     * <p><b>Kindle 4 kernel compatibility (2.6.x):</b> {@code MemAvailable} was
     * added in Linux 3.14 and is absent on the Kindle 4. For old kernels the
     * "available" estimate is {@code MemFree + Buffers + Cached}, which matches
     * what {@code free} and {@code top} display on those devices.
     * Example from a live device:
     * <pre>
     *   MemTotal:  256112 kB
     *   MemFree:     6240 kB   ← misleading alone; most is reclaimable cache
     *   Buffers:   33824 kB
     *   Cached:    80000 kB
     *   → available = 6240 + 33824 + 80000 = 120064 kB  (~47 % free)
     * </pre>
     * Without this correction the widget would show ~97 % RAM used.</p>
     */
    public static MemInfo readMemInfo() {
        long total     = -1;
        long free      = -1;
        long buffers   = -1;
        long cached    = -1;
        long available = -1; // MemAvailable (kernel >= 3.14)

        try (BufferedReader br = new BufferedReader(new FileReader("/proc/meminfo"))) {
            String line;
            while ((line = br.readLine()) != null) {
                int colon = line.indexOf(':');
                if (colon < 0) continue;
                String key = line.substring(0, colon).trim();
                long val = parseMemLine(line, colon);
                if      ("MemTotal".equals(key))     total     = val;
                else if ("MemFree".equals(key))      free      = val;
                else if ("Buffers".equals(key))      buffers   = val;
                else if ("Cached".equals(key))       cached    = val;
                else if ("MemAvailable".equals(key)) available = val;
                // Stop once we have everything we need
                if (total > 0 && free >= 0 && buffers >= 0 && cached >= 0 && available >= 0) break;
            }
        } catch (Exception e) {
            return new MemInfo(-1, -1);
        }

        if (total <= 0) return new MemInfo(-1, -1);

        long avail;
        if (available >= 0) {
            // Modern kernel (3.14+): use the accurate MemAvailable value
            avail = available;
        } else if (free >= 0 && buffers >= 0 && cached >= 0) {
            // Old kernel (2.6.x, Kindle 4): MemFree + Buffers + Cached
            avail = free + buffers + cached;
        } else if (free >= 0) {
            avail = free;
        } else {
            avail = 0;
        }
        return new MemInfo(total, avail);
    }

    private static long parseMemLine(String line, int colon) {
        // Value is the first token after the colon, optionally followed by "kB"
        String rest = line.substring(colon + 1).trim();
        int space = rest.indexOf(' ');
        String num = space > 0 ? rest.substring(0, space) : rest;
        try { return Long.parseLong(num); }
        catch (NumberFormatException e) { return -1; }
    }

    // ── Uptime ────────────────────────────────────────────────────────────────

    /**
     * System uptime in seconds from /proc/uptime.
     * Returns -1 on error.
     */
    public static long readUptimeSeconds() {
        try (BufferedReader br = new BufferedReader(new FileReader("/proc/uptime"))) {
            String line = br.readLine();
            if (line == null) return -1;
            String[] parts = line.trim().split("\\s+");
            return (long) Double.parseDouble(parts[0]);
        } catch (Exception e) {
            return -1;
        }
    }

    // ── Power bank keepalive ──────────────────────────────────────────────────

    /**
     * Reads battery_current in mA from sysfs. Positive = charging, negative = discharging.
     * Returns Integer.MIN_VALUE if unreadable.
     */
    public static int readBatteryCurrent() {
        String raw = readSysfs(
            "/sys/devices/system/yoshi_battery/yoshi_battery0/battery_current");
        if (raw != null) {
            try { return Integer.parseInt(raw.trim()); }
            catch (NumberFormatException ignored) {}
        }
        return Integer.MIN_VALUE;
    }

    /**
     * Writes battery_suspend_current to cap the charge current.
     * Pass 500 to limit charging (~keep USB load up for power bank).
     * Pass 0 to restore normal charging.
     *
     * @return true if the write succeeded
     */
    public static boolean writeBatterySuspendCurrent(int mA) {
        try (FileWriter fw = new FileWriter(BATTERY_SUSPEND_CURRENT_SYSFS)) {
            fw.write(String.valueOf(mA));
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
