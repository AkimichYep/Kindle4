package com.yep.kindle.dron.tool;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.time.LocalTime;
import java.util.*;

import com.yep.kindle.dron.detection.DroneSignatures;
import com.yep.kindle.dron.util.KindleUtils;
import com.yep.kindle.dron.util.WifiUtils;

public class KindleDroneDetector {

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
            this.distanceMeters = WifiUtils.calculateDistance(signalDbm);
            this.isHidden = (ssid == null || ssid.isEmpty() || ssid.equals("[HIDDEN]"));
        }
    }

    private static void scoreThreat(RFSignal s, Map<String, Integer> channelHops) {
        int score = 0;
        StringBuilder reason = new StringBuilder();

        String vendor = DroneSignatures.lookupOUI(s.macAddress);
        if (vendor != null) { score += 50; reason.append("OUI=").append(vendor).append(" "); }

        if (DroneSignatures.matchesKeyword(s.ssid)) { score += 40; reason.append("SSID-kw "); }

        if (s.isHidden && s.distanceMeters < 150) { score += 15; reason.append("hidden-near "); }

        Integer hops = channelHops.get(s.macAddress);
        if (hops != null && hops > 1) { score += 20; reason.append("hop=").append(hops).append(" "); }

        if (s.mode != null && !s.mode.equalsIgnoreCase("Master") && !s.mode.isEmpty()) {
            score += 15; reason.append("mode=").append(s.mode).append(" ");
        }

        if (s.distanceMeters < 50 && s.encryption.equals("Open")) { score += 10; reason.append("open-near "); }

        s.threatScore = Math.min(score, 100);
        s.threatReason = reason.toString().trim();
    }

    public static void main(String[] args) {
        System.out.println("=== Kindle 4 Drone Detector Started ===");
        printSupportedFrequencies();

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
                        if (cur != null && cur.signalDbm != -999) { cur.finalizeSignal(); signals.add(cur); }
                        cur = new RFSignal();
                        cur.macAddress = t.substring(t.indexOf("Address:") + 9).trim();
                    } else if (cur == null) {
                        continue;
                    } else if (t.startsWith("ESSID:")) {
                        String v = t.substring(6).trim();
                        if (v.startsWith("\"") && v.endsWith("\"") && v.length() >= 2) v = v.substring(1, v.length() - 1);
                        cur.ssid = v.isEmpty() ? "[HIDDEN]" : v;
                    } else if (t.startsWith("Mode:")) {
                        cur.mode = t.substring(5).trim();
                    } else if (t.startsWith("Frequency:")) {
                        String v = t.substring(10).trim();
                        cur.frequency = v;
                        int ch = v.indexOf("Channel");
                        if (ch > 0) cur.channel = v.substring(ch + 8).replace(")", "").trim();
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
                        cur.encryption = t.substring(15).trim().equalsIgnoreCase("on") ? "WEP?" : "Open";
                    } else if (t.contains("WPA2") || t.contains("802.11i")) {
                        cur.encryption = "WPA2";
                    } else if (t.contains("WPA Version")) {
                        if (!cur.encryption.equals("WPA2")) cur.encryption = "WPA";
                    }
                }
                if (cur != null && cur.signalDbm != -999) { cur.finalizeSignal(); signals.add(cur); }
                reader.close();

                for (RFSignal s : signals) macChannels.computeIfAbsent(s.macAddress, k -> new HashSet<>()).add(s.channel);
                Map<String, Integer> channelHops = new HashMap<>();
                for (Map.Entry<String, Set<String>> e : macChannels.entrySet()) channelHops.put(e.getKey(), e.getValue().size());

                for (RFSignal s : signals) {
                    scoreThreat(s, channelHops);
                    Integer prev = lastSignalByMac.get(s.macAddress);
                    if (prev != null && Math.abs(prev - s.signalDbm) >= 8) {
                        s.threatScore = Math.min(s.threatScore + 10, 100);
                        s.threatReason += " moving";
                    }
                    lastSignalByMac.put(s.macAddress, s.signalDbm);
                }

                signals.sort((a, b) -> b.threatScore != a.threatScore ? b.threatScore - a.threatScore
                        : Double.compare(a.distanceMeters, b.distanceMeters));

                System.err.println("DEBUG: signals=" + signals.size());

                System.out.println("\n===== FULL SCAN (" + signals.size() + " APs) @ "
                        + LocalTime.now().toString().substring(0, 8) + " =====");
                int idxLog = 1;
                for (RFSignal s : signals) {
                    String vend = DroneSignatures.lookupOUI(s.macAddress);
                    System.out.printf("%2d) %-17s %-14s C%-3s %4ddBm %5.0fm Q%d/%d %-4s thr=%d %s%n",
                            idxLog++, s.macAddress, s.ssid,
                            s.channel.isEmpty() ? "?" : s.channel,
                            s.signalDbm, s.distanceMeters, s.qualityNum, s.qualityMax,
                            s.encryption, s.threatScore,
                            (vend != null ? "<" + vend + "> " : "") + s.threatReason);
                }

                StringBuilder hud = new StringBuilder();
                int threats = 0;
                for (RFSignal s : signals) if (s.threatScore >= 40) threats++;
                hud.append("DRONE ").append(LocalTime.now().toString().substring(0, 8))
                        .append(" AP:").append(signals.size()).append(" THR:").append(threats).append("\n");

                if (signals.isEmpty()) {
                    hud.append("No signals detected.\n");
                } else {
                    int maxRows = KindleUtils.ROWS - 2;
                    int count = 0;
                    for (RFSignal s : signals) {
                        if (count >= maxRows) break;
                        String flag = s.threatScore >= 70 ? "!!" : (s.threatScore >= 40 ? "! " : "  ");
                        String vend = DroneSignatures.lookupOUI(s.macAddress);
                        String ssidShort = s.ssid.length() > 20 ? s.ssid.substring(0, 20) : s.ssid;
                        String row = String.format("%s%3.0fm %ddB C%-3s %-4s %s",
                                flag, s.distanceMeters, s.signalDbm,
                                s.channel.isEmpty() ? "?" : s.channel, s.encryption, ssidShort);
                        if (vend != null) row += " <" + vend + ">";
                        if (row.length() > KindleUtils.COLS) row = row.substring(0, KindleUtils.COLS);
                        hud.append(row).append("\n");
                        count++;
                    }
                    if (signals.size() > maxRows) hud.append("...+").append(signals.size() - maxRows).append(" more (see console)\n");
                }

                KindleUtils.renderToEInk(hud.toString());

            } catch (Exception e) {
                System.err.println("Detector Error: " + e.getMessage());
            }

            KindleUtils.sleep(4000);
        }
    }

    private static void printSupportedFrequencies() {
        try {
            System.out.println("--- Supported frequencies (iwlist wlan0 frequency) ---");
            Process p = Runtime.getRuntime().exec(new String[]{"iwlist", "wlan0", "frequency"});
            p.waitFor();
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String l;
            while ((l = r.readLine()) != null) System.out.println(l);
            r.close();
        } catch (Exception e) {
            System.err.println("Could not list frequencies: " + e.getMessage());
        }
    }
}
