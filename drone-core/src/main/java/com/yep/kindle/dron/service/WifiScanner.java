package com.yep.kindle.dron.service;

import java.util.List;

import com.yep.kindle.dron.model.AP;
import com.yep.kindle.dron.util.AppLog;
import com.yep.kindle.dron.util.KindleUtils;
import com.yep.kindle.dron.util.WifiUtils;

/**
 * WifiScanner — handles all Wi-Fi hardware interaction:
 * firmware initialisation, iwlist scanning, firmware stats reading,
 * and /proc/net/wireless monitoring.
 */
public final class WifiScanner {

    private WifiScanner() {}

    // =========================================================================
    // Firmware management
    // =========================================================================

    /**
     * Apply maxperf power mode and configure aggressive scan parameters
     * on the AR6003 chipset (wlan0).
     */
    public static void initFirmware() {
        KindleUtils.exec("wmiconfig", "-i", "wlan0", "--power", "maxperf");
        KindleUtils.sleep(200);
        KindleUtils.exec("wmiconfig", "-i", "wlan0", "--scan",
                "--fgstart=1", "--fgend=1", "--bg=3",
                "--minact=30", "--maxact=150", "--pas=200",
                "--scanctrlflags", "1", "1", "1", "1", "1", "1");
        KindleUtils.sleep(200);
        KindleUtils.exec("wmiconfig", "-i", "wlan0", "--getTargetStats", "--clearStats");
        KindleUtils.sleep(200);
        AppLog.info("FW: maxperf, 200ms dwell, BSS reporting ON");
    }

    /**
     * Re-apply maxperf power mode (call periodically to prevent power save reverting).
     */
    public static void reapplyMaxperf() {
        KindleUtils.exec("wmiconfig", "-i", "wlan0", "--power", "maxperf");
    }

    // =========================================================================
    // Directed probe
    // =========================================================================

    public static void sendProbe(String ssid) {
        KindleUtils.exec("wmiconfig", "-i", "wlan0", "--scanprobedssid", ssid);
    }

    // =========================================================================
    // Scan
    // =========================================================================

    /**
     * Run {@code iwlist wlan0 scan} and return all parsed access points.
     */
    public static List<AP> scan() {
        String output = KindleUtils.readCommand("iwlist", "wlan0", "scan");
        if (output == null) {
            AppLog.err("scan failed or timed out");
            return java.util.Collections.emptyList();
        }
        return WifiScanParser.parse(output);
    }

    // =========================================================================
    // Firmware stats
    // =========================================================================

    /**
     * Read CRC error counter (and optionally noise floor + SNR) from wmiconfig.
     *
     * @param result  array of at least 3 elements: [0]=crc, [1]=noiseFloor, [2]=csSnr
     * @param updateNoise  if true, also update noiseFloor and csSnr in result
     * @return rx_crcerr count
     */
    public static long readStats(int[] result, boolean updateNoise) {
        long crc = 0;
        if (result == null || result.length < 3) {
            throw new IllegalArgumentException("result must contain crc, noise floor, and SNR slots");
        }
        String output = KindleUtils.readCommand("wmiconfig", "-i", "wlan0", "--getTargetStats");
        if (output != null) {
            String[] lines = output.split("\\r?\\n");
            for (String line : lines) {
                line = line.trim();
                if (line.startsWith("rx_crcerr")) {
                    try { crc = Long.parseLong(line.split("=")[1].trim()); }
                    catch (Exception ignored) {}
                } else if (updateNoise && line.startsWith("noise_floor")) {
                    try { result[1] = Integer.parseInt(line.split("=")[1].trim()); }
                    catch (Exception ignored) {}
                } else if (updateNoise && line.startsWith("cs_snr")) {
                    try { result[2] = Integer.parseInt(line.split("=")[1].trim().split("\\s")[0]); }
                    catch (Exception ignored) {}
                }
            }
        }
        result[0] = (int) crc;
        return crc;
    }

    /**
     * Clear firmware stats counter and wait 2 s for idle CRC measurement.
     * Returns the CRC count observed during the idle window.
     */
    public static long measureIdleCRC(int[] result) {
        KindleUtils.exec("wmiconfig", "-i", "wlan0", "--getTargetStats", "--clearStats");
        KindleUtils.sleep(2000);
        return readStats(result, false);
    }

    // =========================================================================
    // /proc/net/wireless reader
    // =========================================================================

    /**
     * Read link quality, signal level, and noise from /proc/net/wireless.
     * Returns int[]{linkQuality, level, noise} or null if unavailable.
     */
    public static int[] readProcWireless() {
        return WifiUtils.readProcWireless();
    }

}
