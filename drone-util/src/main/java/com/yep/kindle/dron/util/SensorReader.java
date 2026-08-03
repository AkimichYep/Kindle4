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
