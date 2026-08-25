package com.yep.kindle.dron.tool;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.yep.kindle.dron.util.AppLog;
import com.yep.kindle.dron.util.KindleUtils;
import com.yep.kindle.dron.util.WifiUtils;

public class KindleWifiHud {

    private static volatile boolean running = true;

    static class AccessPoint {
        String ssid;
        int signalDbm;
        double distanceMeters;

        AccessPoint(String ssid, int signalDbm) {
            this.ssid = ssid;
            this.signalDbm = signalDbm;
            this.distanceMeters = WifiUtils.calculateDistance(signalDbm);
        }
    }

    public static void main(String[] args) {
        AppLog.init(System.getProperty("kindle.log.file", "wifi-hud.log"), 256 * 1024L);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            running = false;
            AppLog.info("Shutdown requested for KindleWifiHud");
        }, "shutdown-kindle-wifi-hud"));
        AppLog.info("=== Kindle Synchronized Wi-Fi HUD Started ===");

        while (running) {
            List<AccessPoint> apList = new ArrayList<>();
            try {
                String output = KindleUtils.readCommand("iwlist", "wlan0", "scan");
                if (output == null || output.trim().isEmpty()) {
                    AppLog.warn("iwlist scan produced no output");
                    KindleUtils.sleep(1000);
                    continue;
                }

                String currentSsid = "Unknown";
                int currentSignal = -999;

                String[] lines = output.split("\\r?\\n");
                for (String line : lines) {
                    line = line.trim();
                    if (line.startsWith("ESSID:")) {
                        String marker = "ESSID:";
                        currentSsid = line.substring(marker.length()).trim();
                        // Remove quotes from ESSID if present
                        if (currentSsid.startsWith("\"") && currentSsid.endsWith("\"")) {
                            currentSsid = currentSsid.substring(1, currentSsid.length() - 1);
                        }
                        AppLog.info("DEBUG Found ESSID: " + currentSsid);
                    } else if (line.contains("Signal level=")) {
                        try {
                            String marker = "Signal level=";
                            int startIdx = line.indexOf(marker) + marker.length();
                            int endIdx = line.indexOf(" dBm", startIdx);
                            if (endIdx > startIdx) {
                                String signalStr = line.substring(startIdx, endIdx).trim();
                                currentSignal = Integer.parseInt(signalStr);
                                AppLog.info("DEBUG Found Signal: " + currentSignal + " for " + currentSsid);
                                apList.add(new AccessPoint(currentSsid, currentSignal));
                                currentSsid = "Unknown";
                                currentSignal = -999;
                            }
                        } catch (Exception e) {
                            AppLog.warn("DEBUG Signal parsing error: " + e.getMessage() + " | line=" + line);
                        }
                    }
                }
                AppLog.info("DEBUG Total APs found: " + apList.size());

                apList.sort(Comparator.comparingDouble(ap -> ap.distanceMeters));

                // Build display text
                StringBuilder hudText = new StringBuilder();
                hudText.append("=== Wi-Fi (").append(LocalTime.now().toString().substring(0, 5)).append(" Count:").append(apList.size()).append(") ===\n");

                if (apList.isEmpty()) {
                    hudText.append("No networks found.\n");
                    AppLog.info("DEBUG No networks found");
                } else {
                    int count = 0;
                    for (AccessPoint ap : apList) {
                        if (count < 39) { // Kindle 4 screen fits 40 rows (1 header + 39)
                            String distanceStr = String.format("%.1f", ap.distanceMeters);
                            String displayLine = distanceStr + "m | " + ap.signalDbm + "dBm | " + ap.ssid;
                            hudText.append(displayLine).append("\n");
                            AppLog.info("DEBUG Displaying: " + displayLine);
                            count++;
                        }
                    }
                }

                KindleUtils.renderToEInk(hudText.toString());

            } catch (Exception e) {
                AppLog.exception("HUD error", e);
            }

            KindleUtils.sleep(10000);
        }
    }
}