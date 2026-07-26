import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

public class KindleWifiHud {

    static class AccessPoint {
        String ssid;
        int signalDbm;
        double distanceMeters;

        AccessPoint(String ssid, int signalDbm) {
            this.ssid = ssid;
            this.signalDbm = signalDbm;
            this.distanceMeters = calculateDistance(signalDbm);
        }
    }

    /**
     * Converts Wi-Fi signal strength (dBm) to approximate distance in meters.
     * Uses the free-space path loss model with typical Wi-Fi parameters.
     * Formula: distance = 10^((TxPower - RSSI) / (10 * N))
     * TxPower: -30 dBm (typical for Wi-Fi access points)
     * N: 2.7 (path loss exponent for 2.4 GHz indoor environment)
     */
    private static double calculateDistance(int signalDbm) {
        final int TX_POWER = -30; // Typical transmit power in dBm
        final double PATH_LOSS_EXPONENT = 2.7; // For 2.4 GHz indoor

        double distance = Math.pow(10.0, ((double) (TX_POWER - signalDbm) / (10.0 * PATH_LOSS_EXPONENT)));
        return Math.max(distance, 0.5); // Minimum 0.5m to avoid invalid values
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

                // Sort ascending: closest distance (lowest meters) first
                for (int i = 0; i < apList.size() - 1; i++) {
                    for (int j = i + 1; j < apList.size(); j++) {
                        if (apList.get(i).distanceMeters > apList.get(j).distanceMeters) {
                            AccessPoint temp = apList.get(i);
                            apList.set(i, apList.get(j));
                            apList.set(j, temp);
                        }
                    }
                }

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

                renderToEInk(hudText.toString());

            } catch (Exception e) {
                System.err.println("HUD Error: " + e.getMessage());
            }

            try {
                Thread.sleep(10000); // Wait 10 seconds before next scan loop
            } catch (InterruptedException ignored) {
            }
        }
    }

    private static void renderToEInk(String text) {
        try {
            Process clearProc = Runtime.getRuntime().exec(new String[]{"eips", "-c"});
            clearProc.waitFor();

            String[] lines = text.split("\n");
            int y = 0;
            for (String l : lines) {
                if (y < 40 && !l.isEmpty()) { // 40 text rows on Kindle 4 (600x800)
                    if (l.length() > 50) {    // 50 columns wide (12px cells)
                        l = l.substring(0, 50);
                    }
                    Process p = Runtime.getRuntime().exec(new String[]{"eips", "0", String.valueOf(y), l});
                    p.waitFor();
                    y++;
                }
            }
        } catch (Exception e) {
            System.err.println("E-Ink render error: " + e.getMessage());
        }
    }
}