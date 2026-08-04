package com.yep.kindle.dron.tool;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.yep.kindle.dron.util.KindleUtils;
import com.yep.kindle.dron.util.WifiUtils;

public class KindleWifiHud {

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
        System.out.println("=== Kindle Synchronized Wi-Fi HUD Started ===");

        while (true) {
            List<AccessPoint> apList = new ArrayList<>();
            try {
                // Execute iwlist scan
                Process process = Runtime.getRuntime().exec(new String[]{"iwlist", "wlan0", "scan"});

                // CRITICAL FIX: Wait for the system scan process to fully execute and finish before reading
                int exitCode = process.waitFor();
                if (exitCode != 0) {
                    System.err.println("iwlist failed or returned non-zero exit code.");
                }

                BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                String line;
                String currentSsid = "Unknown";
                int currentSignal = -999;

                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.startsWith("ESSID:")) {
                        String marker = "ESSID:";
                        currentSsid = line.substring(marker.length()).trim();
                        // Remove quotes from ESSID if present
                        if (currentSsid.startsWith("\"") && currentSsid.endsWith("\"")) {
                            currentSsid = currentSsid.substring(1, currentSsid.length() - 1);
                        }
                        System.err.println("DEBUG: Found ESSID: " + currentSsid);
                    } else if (line.contains("Signal level=")) {
                        try {
                            String marker = "Signal level=";
                            int startIdx = line.indexOf(marker) + marker.length();
                            int endIdx = line.indexOf(" dBm", startIdx);
                            if (endIdx > startIdx) {
                                String signalStr = line.substring(startIdx, endIdx).trim();
                                currentSignal = Integer.parseInt(signalStr);
                                System.err.println("DEBUG: Found Signal: " + currentSignal + " for " + currentSsid);
                                apList.add(new AccessPoint(currentSsid, currentSignal));
                                currentSsid = "Unknown";
                                currentSignal = -999;
                            }
                        } catch (Exception e) {
                            System.err.println("DEBUG: Signal parsing error: " + e.getMessage() + " | Line: " + line);
                        }
                    }
                }
                reader.close();
                System.err.println("DEBUG: Total APs found: " + apList.size());

                apList.sort(Comparator.comparingDouble(ap -> ap.distanceMeters));

                // Build display text
                StringBuilder hudText = new StringBuilder();
                hudText.append("=== Wi-Fi (").append(LocalTime.now().toString().substring(0, 5)).append(" Count:").append(apList.size()).append(") ===\n");

                if (apList.isEmpty()) {
                    hudText.append("No networks found.\n");
                    System.err.println("DEBUG: No networks found!");
                } else {
                    int count = 0;
                    for (AccessPoint ap : apList) {
                        if (count < 39) { // Kindle 4 screen fits 40 rows (1 header + 39)
                            String distanceStr = String.format("%.1f", ap.distanceMeters);
                            String displayLine = distanceStr + "m | " + ap.signalDbm + "dBm | " + ap.ssid;
                            hudText.append(displayLine).append("\n");
                            System.err.println("DEBUG: Displaying: " + displayLine);
                            count++;
                        }
                    }
                }

                KindleUtils.renderToEInk(hudText.toString());

            } catch (Exception e) {
                System.err.println("HUD Error: " + e.getMessage());
            }

            KindleUtils.sleep(10000);
        }
    }
}