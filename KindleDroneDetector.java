import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Kindle 4 Drone Detector
 *
 * Extracts the maximum amount of information from the Wi-Fi module using the
 * wireless-tools available on a jailbroken Kindle 4 (Atheros AR6000 chipset).
 *
 * Available Wi-Fi commands on a jailbroken Kindle 4:
 *   iwlist wlan0 scan       - full AP scan (SSID, MAC, freq, quality, encryption...)
 *   iwlist wlan0 frequency  - list supported channels/frequencies
 *   iwlist wlan0 channel    - list supported channels
 *   iwconfig wlan0          - current interface config, bit rate, TX power
 *   iwlist wlan0 bitrate    - supported bit rates
 *   iwlist wlan0 txpower    - supported TX power levels
 *   iwpriv wlan0            - driver-private extensions
 *   cat /proc/net/wireless  - live link quality / noise counters
 *
 * Drone detection heuristics (see scoreThreat):
 *   - Known drone OUI (DJI, Parrot, Autel, Skydio, Yuneec, ESP32/DIY)
 *   - Hidden SSID + strong/close signal
 *   - SSID keyword match (drone, dji, fpv, mavic, tello, parrot, anafi...)
 *   - Same MAC appearing on multiple channels (channel hopping)
 *   - Ad-Hoc / non-Master mode (drone<->controller links)
 *   - Rapid signal-strength change between scans (moving target)
 */
public class KindleDroneDetector {

    // Kindle 4 e-ink text mode (eips): 50 columns x 40 rows (600x800, 12x20 font).
    private static final int MAX_SCREEN_ROWS = 40;
    private static final int MAX_SCREEN_COLS = 50;

    static class RFSignal {
        String macAddress = "";
        String ssid = "";
        String channel = "";
        String frequency = "";
        int signalDbm = -999;
        int noiseDbm = -999;
        int qualityNum = 0;
        int qualityMax = 94;
        double distanceMeters;
        String mode = "";
        String encryption = "Open";
        String protocol = "";
        String bitRates = "";
        boolean isHidden;
        int threatScore = 0;
        String threatReason = "";

        void finalizeSignal() {
            this.distanceMeters = calculateDistance(signalDbm);
            this.isHidden = (ssid == null || ssid.isEmpty() || ssid.equals("[HIDDEN]"));
        }
    }

    // Known drone-manufacturer OUI prefixes (first 3 MAC octets)
    private static final Map<String, String> DRONE_OUI = new HashMap<>();
    static {
        // DJI
        DRONE_OUI.put("60:60:1F", "DJI");
        DRONE_OUI.put("34:D2:62", "DJI");
        DRONE_OUI.put("0C:43:96", "DJI");
        DRONE_OUI.put("18:97:D0", "DJI");
        DRONE_OUI.put("48:1C:B9", "DJI");
        DRONE_OUI.put("E4:7A:2C", "DJI");
        DRONE_OUI.put("A4:77:61", "DJI");
        DRONE_OUI.put("FC:77:74", "DJI");
        // Parrot
        DRONE_OUI.put("00:26:19", "Parrot");
        DRONE_OUI.put("00:12:1C", "Parrot");
        DRONE_OUI.put("90:03:B7", "Parrot");
        DRONE_OUI.put("A0:14:3D", "Parrot");
        // Autel Robotics
        DRONE_OUI.put("94:E3:6D", "Autel");
        // Skydio
        DRONE_OUI.put("38:1D:14", "Skydio");
        // Yuneec
        DRONE_OUI.put("E0:B6:F5", "Yuneec");
        // Espressif (ESP32 - common in DIY drones / FPV links)
        DRONE_OUI.put("24:0A:C4", "ESP32/DIY");
        DRONE_OUI.put("30:AE:A4", "ESP32/DIY");
        DRONE_OUI.put("7C:9E:BD", "ESP32/DIY");
        DRONE_OUI.put("A4:CF:12", "ESP32/DIY");
    }

    // SSID keywords that suggest a drone / FPV / RC device
    private static final String[] DRONE_KEYWORDS = {
        "drone", "dji", "mavic", "tello", "phantom", "spark",
        "parrot", "anafi", "bebop", "fpv", "skydio", "autel", "yuneec",
        "goggles", "avata", "inspire", "matrice", "gimbal"
    };

    private static double calculateDistance(int signalDbm) {
        final int TX_POWER = -30;
        final double PATH_LOSS_EXPONENT = 2.7;
        double distance = Math.pow(10.0, ((double) (TX_POWER - signalDbm) / (10.0 * PATH_LOSS_EXPONENT)));
        return Math.max(distance, 0.5);
    }

    private static String getDroneVendor(String mac) {
        if (mac == null || mac.length() < 8) return null;
        return DRONE_OUI.get(mac.substring(0, 8).toUpperCase());
    }

    private static boolean ssidLooksLikeDrone(String ssid) {
        if (ssid == null) return false;
        String lower = ssid.toLowerCase();
        for (String kw : DRONE_KEYWORDS) {
            if (lower.contains(kw)) return true;
        }
        return false;
    }

    /** Scores how likely a signal belongs to a drone (0-100). */
    private static void scoreThreat(RFSignal s, Map<String, Integer> channelHops) {
        int score = 0;
        StringBuilder reason = new StringBuilder();

        String vendor = getDroneVendor(s.macAddress);
        if (vendor != null) {
            score += 50;
            reason.append("OUI=").append(vendor).append(" ");
        }
        if (ssidLooksLikeDrone(s.ssid)) {
            score += 40;
            reason.append("SSID-kw ");
        }
        if (s.isHidden && s.distanceMeters < 150) {
            score += 15;
            reason.append("hidden-near ");
        }
        Integer hops = channelHops.get(s.macAddress);
        if (hops != null && hops > 1) {
            score += 20;
            reason.append("hop=").append(hops).append(" ");
        }
        if (s.mode != null && !s.mode.equalsIgnoreCase("Master") && !s.mode.isEmpty()) {
            score += 15;
            reason.append("mode=").append(s.mode).append(" ");
        }
        if (s.distanceMeters < 50 && s.encryption.equals("Open")) {
            score += 10;
            reason.append("open-near ");
        }

        s.threatScore = Math.min(score, 100);
        s.threatReason = reason.toString().trim();
    }

    public static void main(String[] args) {
        System.out.println("=== Kindle 4 Drone Detector Started ===");

        // Print supported frequencies once at startup (diagnostic)
        printSupportedFrequencies();

        // Track signal history to detect moving targets (changing dBm)
        Map<String, Integer> lastSignalByMac = new HashMap<>();

        while (true) {
            List<RFSignal> signals = new ArrayList<>();
            Map<String, Set<String>> macChannels = new HashMap<>();

            try {
                Process process = Runtime.getRuntime().exec(new String[]{"iwlist", "wlan0", "scan"});
                process.waitFor();

                BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                String line;
                RFSignal cur = null;

                while ((line = reader.readLine()) != null) {
                    String t = line.trim();

                    if (t.contains("Address:")) {
                        if (cur != null && cur.signalDbm != -999) {
                            cur.finalizeSignal();
                            signals.add(cur);
                        }
                        cur = new RFSignal();
                        int idx = t.indexOf("Address:");
                        cur.macAddress = t.substring(idx + 9).trim();
                    } else if (cur == null) {
                        continue;
                    } else if (t.startsWith("ESSID:")) {
                        String v = t.substring(6).trim();
                        if (v.startsWith("\"") && v.endsWith("\"") && v.length() >= 2) {
                            v = v.substring(1, v.length() - 1);
                        }
                        cur.ssid = v.isEmpty() ? "[HIDDEN]" : v;
                    } else if (t.startsWith("Mode:")) {
                        cur.mode = t.substring(5).trim();
                    } else if (t.startsWith("Frequency:")) {
                        String v = t.substring(10).trim();
                        cur.frequency = v;
                        int ch = v.indexOf("Channel");
                        if (ch > 0) {
                            cur.channel = v.substring(ch + 8).replace(")", "").trim();
                        }
                    } else if (t.startsWith("Protocol:")) {
                        cur.protocol = t.substring(9).trim();
                    } else if (t.startsWith("Bit Rates:")) {
                        cur.bitRates = t.substring(10).trim();
                    } else if (t.contains("Quality=")) {
                        try {
                            int qi = t.indexOf("Quality=") + 8;
                            int qe = t.indexOf(" ", qi);
                            String q = (qe > qi ? t.substring(qi, qe) : t.substring(qi)).trim();
                            if (q.contains("/")) {
                                String[] parts = q.split("/");
                                cur.qualityNum = Integer.parseInt(parts[0].trim());
                                cur.qualityMax = Integer.parseInt(parts[1].trim());
                            }
                        } catch (Exception ignored) {}

                        if (t.contains("Signal level=")) {
                            try {
                                int si = t.indexOf("Signal level=") + 13;
                                int se = t.indexOf(" dBm", si);
                                if (se > si) cur.signalDbm = Integer.parseInt(t.substring(si, se).trim());
                            } catch (Exception ignored) {}
                        }
                        if (t.contains("Noise level=")) {
                            try {
                                int ni = t.indexOf("Noise level=") + 12;
                                int ne = t.indexOf(" dBm", ni);
                                if (ne > ni) cur.noiseDbm = Integer.parseInt(t.substring(ni, ne).trim());
                            } catch (Exception ignored) {}
                        }
                    } else if (t.startsWith("Encryption key:")) {
                        String enc = t.substring(15).trim();
                        cur.encryption = enc.equalsIgnoreCase("on") ? "WEP?" : "Open";
                    } else if (t.contains("WPA2") || t.contains("802.11i")) {
                        cur.encryption = "WPA2";
                    } else if (t.contains("WPA Version")) {
                        if (!cur.encryption.equals("WPA2")) cur.encryption = "WPA";
                    }
                }
                if (cur != null && cur.signalDbm != -999) {
                    cur.finalizeSignal();
                    signals.add(cur);
                }
                reader.close();

                // Build channel-hop map
                for (RFSignal s : signals) {
                    macChannels.computeIfAbsent(s.macAddress, k -> new HashSet<>()).add(s.channel);
                }
                Map<String, Integer> channelHops = new HashMap<>();
                for (Map.Entry<String, Set<String>> e : macChannels.entrySet()) {
                    channelHops.put(e.getKey(), e.getValue().size());
                }

                // Score threats + detect movement
                for (RFSignal s : signals) {
                    scoreThreat(s, channelHops);
                    Integer prev = lastSignalByMac.get(s.macAddress);
                    if (prev != null && Math.abs(prev - s.signalDbm) >= 8) {
                        s.threatScore = Math.min(s.threatScore + 10, 100);
                        s.threatReason += " moving";
                    }
                    lastSignalByMac.put(s.macAddress, s.signalDbm);
                }

                // Sort: highest threat first, then closest distance
                signals.sort((a, b) -> {
                    if (b.threatScore != a.threatScore) return b.threatScore - a.threatScore;
                    return Double.compare(a.distanceMeters, b.distanceMeters);
                });

                System.err.println("DEBUG: signals=" + signals.size());

                // Full list to console/terminal (all networks, no screen limit)
                System.out.println("\n===== FULL SCAN (" + signals.size() + " APs) @ "
                        + LocalTime.now().toString().substring(0, 8) + " =====");
                int idxLog = 1;
                for (RFSignal s : signals) {
                    String vend = getDroneVendor(s.macAddress);
                    System.out.printf("%2d) %-17s %-14s C%-3s %4ddBm %5.0fm Q%d/%d %-4s thr=%d %s%n",
                            idxLog++, s.macAddress, s.ssid,
                            s.channel.isEmpty() ? "?" : s.channel,
                            s.signalDbm, s.distanceMeters, s.qualityNum, s.qualityMax,
                            s.encryption, s.threatScore,
                            (vend != null ? "<" + vend + "> " : "") + s.threatReason);
                }

                // Build display
                StringBuilder hud = new StringBuilder();

                int threats = 0;
                for (RFSignal s : signals) if (s.threatScore >= 40) threats++;
                // Compact single-line header to save screen rows
                hud.append("DRONE ").append(LocalTime.now().toString().substring(0, 8))
                   .append(" AP:").append(signals.size())
                   .append(" THR:").append(threats).append("\n");

                if (signals.isEmpty()) {
                    hud.append("No signals detected.\n");
                } else {
                    // Show as many as fit on screen (Kindle 4 eips ~ 35 rows).
                    // 1 header row is used, leave a couple for safety.
                    int maxRows = MAX_SCREEN_ROWS - 2;
                    int count = 0;
                    for (RFSignal s : signals) {
                        if (count >= maxRows) break;
                        String flag = s.threatScore >= 70 ? "!!" : (s.threatScore >= 40 ? "! " : "  ");
                        String vend = getDroneVendor(s.macAddress);
                        String ssidShort = s.ssid.length() > 20 ? s.ssid.substring(0, 20) : s.ssid;
                        // flag dist dBm ch enc ssid [vendor]
                        String row = String.format("%s%3.0fm %ddB C%-3s %-4s %s",
                                flag, s.distanceMeters, s.signalDbm,
                                s.channel.isEmpty() ? "?" : s.channel,
                                s.encryption, ssidShort);
                        if (vend != null) row += " <" + vend + ">";
                        if (row.length() > MAX_SCREEN_COLS) row = row.substring(0, MAX_SCREEN_COLS);
                        hud.append(row).append("\n");
                        count++;
                    }
                    // If more networks exist than we could show, note it
                    if (signals.size() > maxRows) {
                        hud.append("...+").append(signals.size() - maxRows).append(" more (see console)\n");
                    }
                }

                renderToEInk(hud.toString());

            } catch (Exception e) {
                System.err.println("Detector Error: " + e.getMessage());
            }

            try {
                Thread.sleep(4000);
            } catch (InterruptedException ignored) {}
        }
    }

    /** Diagnostic: prints channels/frequencies the Kindle Wi-Fi supports. */
    private static void printSupportedFrequencies() {
        try {
            System.out.println("--- Supported frequencies (iwlist wlan0 frequency) ---");
            Process p = Runtime.getRuntime().exec(new String[]{"iwlist", "wlan0", "frequency"});
            p.waitFor();
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String l;
            while ((l = r.readLine()) != null) {
                System.out.println(l);
            }
            r.close();
        } catch (Exception e) {
            System.err.println("Could not list frequencies: " + e.getMessage());
        }
    }

    private static void renderToEInk(String text) {
        try {
            Runtime.getRuntime().exec(new String[]{"eips", "-c"}).waitFor();
            String[] lines = text.split("\n");
            int y = 0;
            for (String l : lines) {
                if (y < MAX_SCREEN_ROWS && !l.isEmpty()) {
                    if (l.length() > MAX_SCREEN_COLS) l = l.substring(0, MAX_SCREEN_COLS);
                    Runtime.getRuntime().exec(new String[]{"eips", "0", String.valueOf(y), l}).waitFor();
                    y++;
                }
            }
        } catch (Exception e) {
            System.err.println("E-Ink render error: " + e.getMessage());
        }
    }
}

