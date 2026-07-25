import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class KindleDroneDetector {

    static class RFSignal {
        String macAddress;
        String ssid;
        String channel;
        String frequency;
        int signalDbm;
        double distanceMeters;
        String mode;
        String txPower;
        boolean isHidden;
        boolean isProbeRequest;
        long timestamp;

        RFSignal(String mac, String ssid, String channel, String freq, int signal) {
            this.macAddress = mac;
            this.ssid = ssid;
            this.channel = channel;
            this.frequency = freq;
            this.signalDbm = signal;
            this.distanceMeters = calculateDistance(signal);
            this.isHidden = (ssid == null || ssid.isEmpty());
            this.timestamp = System.currentTimeMillis();
        }
    }

    /**
     * Converts Wi-Fi signal strength (dBm) to approximate distance in meters.
     * Uses the free-space path loss model with typical Wi-Fi parameters.
     */
    private static double calculateDistance(int signalDbm) {
        final int TX_POWER = -30;
        final double PATH_LOSS_EXPONENT = 2.7;
        double distance = Math.pow(10.0, ((double) (TX_POWER - signalDbm) / (10.0 * PATH_LOSS_EXPONENT)));
        return Math.max(distance, 0.5);
    }

    /**
     * Checks if MAC address has drone-like characteristics
     * Drones often use specific OUI (Organization Unique Identifier) ranges
     */
    private static boolean isSuspiciousMac(String mac) {
        if (mac == null || mac.isEmpty()) return false;

        // DJI Drones: 00:1A:3A, 0C:43:96, 18:97:D0, 60:60:1F, A0:14:3D, A4:77:61, E0:76:D0, FC:77:74
        // Parrot Drones: 00:26:19, 00:1E:2C, A0:14:3D
        // Generic probe requests (randomized)
        String prefix = mac.substring(0, 8).toUpperCase();
        return prefix.equals("00:1A:3A") || prefix.equals("0C:43:96") || prefix.equals("18:97:D0") ||
               prefix.equals("60:60:1F") || prefix.equals("A0:14:3D") || prefix.equals("A4:77:61") ||
               prefix.equals("E0:76:D0") || prefix.equals("FC:77:74") || prefix.equals("00:26:19") ||
               prefix.equals("00:1E:2C");
    }

    public static void main(String[] args) {
        System.out.println("=== + ===");
        System.out.println("=== Kindle Drone Detector Started ===");
        System.out.println("Scanning all Wi-Fi channels for RF signals...\n");

        while (true) {
            List<RFSignal> signals = new ArrayList<>();
            Map<String, Integer> macFrequency = new HashMap<>();

            try {
                // Full iwlist scan - captures all available information
                Process process = Runtime.getRuntime().exec(new String[]{"iwlist", "wlan0", "scan"});
                int exitCode = process.waitFor();

                if (exitCode != 0) {
                    System.err.println("iwlist scan failed");
                }

                BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                String line;
                String currentMac = "";
                String currentSsid = "";
                String currentChannel = "";
                String currentFreq = "";
                int currentSignal = -999;
                String currentMode = "";

                while ((line = reader.readLine()) != null) {
                    line = line.trim();

                    // Extract MAC Address (Cell XX - Address: XX:XX:XX:XX:XX:XX)
                    if (line.contains("Address:")) {
                        int idx = line.indexOf("Address:");
                        if (idx >= 0) {
                            currentMac = line.substring(idx + 9).trim();
                            System.err.println("DEBUG: Found MAC: " + currentMac);
                        }
                    }
                    // Extract SSID
                    else if (line.startsWith("ESSID:")) {
                        String marker = "ESSID:";
                        currentSsid = line.substring(marker.length()).trim();
                        if (currentSsid.startsWith("\"") && currentSsid.endsWith("\"")) {
                            currentSsid = currentSsid.substring(1, currentSsid.length() - 1);
                        }
                        if (currentSsid.isEmpty()) {
                            currentSsid = "[HIDDEN]";
                        }
                        System.err.println("DEBUG: Found SSID: " + currentSsid);
                    }
                    // Extract Channel and Frequency
                    else if (line.startsWith("Frequency:")) {
                        String marker = "Frequency:";
                        String freqPart = line.substring(marker.length()).trim();
                        currentFreq = freqPart;
                        // Extract channel from "2.422 GHz (Channel 3)"
                        int chIdx = freqPart.indexOf("Channel");
                        if (chIdx > 0) {
                            currentChannel = freqPart.substring(chIdx + 8).replace(")", "").trim();
                        }
                        System.err.println("DEBUG: Frequency: " + currentFreq + " Channel: " + currentChannel);
                    }
                    // Extract Signal Level
                    else if (line.contains("Signal level=")) {
                        try {
                            String marker = "Signal level=";
                            int startIdx = line.indexOf(marker) + marker.length();
                            int endIdx = line.indexOf(" dBm", startIdx);
                            if (endIdx > startIdx) {
                                String signalStr = line.substring(startIdx, endIdx).trim();
                                currentSignal = Integer.parseInt(signalStr);
                                System.err.println("DEBUG: Signal: " + currentSignal + " for " + currentMac + " (" + currentSsid + ")");

                                // Add to signals list
                                RFSignal signal = new RFSignal(currentMac, currentSsid, currentChannel, currentFreq, currentSignal);
                                signals.add(signal);

                                // Track MAC frequency (detecting rapid channel hopping = drone indicator)
                                macFrequency.put(currentMac, macFrequency.getOrDefault(currentMac, 0) + 1);
                            }
                        } catch (Exception e) {
                            System.err.println("DEBUG: Parse error: " + e.getMessage());
                        }
                    }
                    // Extract Mode (AP, Ad-Hoc, etc.)
                    else if (line.startsWith("Mode:")) {
                        currentMode = line.substring(5).trim();
                    }
                }
                reader.close();
                System.err.println("DEBUG: Total signals found: " + signals.size());

                // Sort by distance (closest first)
                for (int i = 0; i < signals.size() - 1; i++) {
                    for (int j = i + 1; j < signals.size(); j++) {
                        if (signals.get(i).distanceMeters > signals.get(j).distanceMeters) {
                            RFSignal temp = signals.get(i);
                            signals.set(i, signals.get(j));
                            signals.set(j, temp);
                        }
                    }
                }

                // Build display text
                StringBuilder hudText = new StringBuilder();
                hudText.append("=== DRONE DETECTOR ===\n");
                hudText.append("Time: ").append(LocalTime.now().toString().substring(0, 5)).append(" | Count: ").append(signals.size()).append("\n");
                hudText.append("===\n");

                if (signals.isEmpty()) {
                    hudText.append("No signals detected.\n");
                    System.err.println("DEBUG: No signals found!");
                } else {
                    int count = 0;
                    for (RFSignal sig : signals) {
                        if (count < 10) {
                            // Mark suspicious signals (hidden SSID + strong signal + known drone MAC)
                            String marker = "";
                            if (sig.isHidden && sig.distanceMeters < 100) marker = "★ ";
                            if (isSuspiciousMac(sig.macAddress)) marker = "⚠ ";

                            String distStr = String.format("%.0f", sig.distanceMeters);
                            String displayLine = String.format("%s%sm|%3ddBm|%s|%s",
                                marker, distStr, sig.signalDbm, sig.channel, sig.ssid);

                            if (displayLine.length() > 40) {
                                displayLine = displayLine.substring(0, 40);
                            }

                            hudText.append(displayLine).append("\n");
                            System.err.println("DEBUG: Display: " + displayLine);
                            count++;
                        }
                    }
                }

                // Show detected MAC addresses and their hop counts (indicator of drones)
                hudText.append("\n=== MAC ANALYSIS ===\n");
                int macCount = 0;
                for (Map.Entry<String, Integer> entry : macFrequency.entrySet()) {
                    if (macCount < 5 && entry.getValue() > 1) {
                        String suspicious = isSuspiciousMac(entry.getKey()) ? "SUSPECT" : "";
                        hudText.append(entry.getKey()).append(" x").append(entry.getValue()).append(" ").append(suspicious).append("\n");
                        macCount++;
                    }
                }

                renderToEInk(hudText.toString());

            } catch (Exception e) {
                System.err.println("Detector Error: " + e.getMessage());
                e.printStackTrace();
            }

            try {
                Thread.sleep(5000); // Scan every 5 seconds
            } catch (InterruptedException ignored) {}
        }
    }

    private static void renderToEInk(String text) {
        try {
            Process clearProc = Runtime.getRuntime().exec(new String[]{"eips", "-c"});
            clearProc.waitFor();

            String[] lines = text.split("\n");
            int y = 0;
            for (String l : lines) {
                if (y < 24 && !l.isEmpty()) {
                    if (l.length() > 40) {
                        l = l.substring(0, 40);
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

