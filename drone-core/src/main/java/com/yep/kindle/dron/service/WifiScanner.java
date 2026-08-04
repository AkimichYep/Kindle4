package com.yep.kindle.dron.service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
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
        List<AP> list = new ArrayList<>();
        Process p = null;
        try {
            p = Runtime.getRuntime().exec(new String[]{"iwlist", "wlan0", "scan"});
            final Process fp = p;
            Thread errDrain = new Thread(() -> KindleUtils.drain(fp.getErrorStream()), "err-drain");
            errDrain.setDaemon(true);
            errDrain.start();

            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            AP cur = null;
            while ((line = r.readLine()) != null) {
                String t = line.trim();
                if (t.contains("Address:")) {
                    if (cur != null && cur.signalDbm != -999) { finishAP(cur); list.add(cur); }
                    cur = new AP();
                    cur.mac = t.substring(t.indexOf("Address:") + 9).trim().toUpperCase();
                } else if (cur == null) {
                    continue;
                } else if (t.startsWith("ESSID:")) {
                    String v = t.substring(6).trim();
                    if (v.startsWith("\"") && v.endsWith("\"") && v.length() >= 2)
                        v = v.substring(1, v.length() - 1);
                    cur.ssid   = v.isEmpty() ? "[HIDDEN]" : v;
                    cur.hidden = v.isEmpty();
                } else if (t.startsWith("Mode:")) {
                    cur.mode = t.substring(5).trim();
                } else if (t.startsWith("Frequency:")) {
                    int ci = t.indexOf("Channel");
                    if (ci > 0) {
                        try {
                            cur.channel = Integer.parseInt(t.substring(ci + 8).replace(")", "").trim());
                        } catch (NumberFormatException ignored) {}
                    }
                } else if (t.contains("Signal level=")) {
                    try {
                        int si = t.indexOf("Signal level=") + 13;
                        int se = t.indexOf(" dBm", si);
                        if (se > si) cur.signalDbm = Integer.parseInt(t.substring(si, se).trim());
                    } catch (NumberFormatException ignored) {}
                } else if (t.contains("WPA2") || t.contains("802.11i")) {
                    cur.encryption = "WPA2";
                } else if (t.contains("WPA Version")) {
                    if (!cur.encryption.equals("WPA2")) cur.encryption = "WPA";
                } else if (t.startsWith("Encryption key:on")) {
                    if (cur.encryption.equals("Open")) cur.encryption = "WEP";
                }
            }
            if (cur != null && cur.signalDbm != -999) { finishAP(cur); list.add(cur); }
            r.close();
            p.waitFor();
        } catch (Exception e) {
            AppLog.err("scan err: " + e.getMessage());
        } finally {
            if (p != null) p.destroy();
        }
        return list;
    }

    private static void finishAP(AP a) {
        a.dist = WifiUtils.calculateDistance(a.signalDbm);
        if (a.ssid == null || a.ssid.isEmpty()) { a.ssid = "[HIDDEN]"; a.hidden = true; }
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
        Process p = null;
        try {
            p = Runtime.getRuntime().exec(new String[]{"wmiconfig", "-i", "wlan0", "--getTargetStats"});
            final Process fp = p;
            Thread errDrain = new Thread(() -> KindleUtils.drain(fp.getErrorStream()), "stat-err-drain");
            errDrain.setDaemon(true);
            errDrain.start();

            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            while ((line = r.readLine()) != null) {
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
            r.close();
            p.waitFor();
        } catch (Exception ignored) {
        } finally {
            if (p != null) p.destroy();
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
