package com.yep.kindle.dron.tool;

import java.time.LocalTime;
import java.util.*;

import com.yep.kindle.dron.detection.DroneSignatures;
import com.yep.kindle.dron.model.AP;
import com.yep.kindle.dron.service.WifiScanner;
import com.yep.kindle.dron.util.AppLog;
import com.yep.kindle.dron.util.KindleUtils;
import com.yep.kindle.dron.util.WifiUtils;

public class KindleDroneDetector {

    private static volatile boolean running = true;

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

    private static RFSignal fromAccessPoint(AP accessPoint) {
        RFSignal signal = new RFSignal();
        signal.macAddress = accessPoint.mac;
        signal.ssid = accessPoint.ssid;
        signal.channel = accessPoint.channel == 0 ? "" : String.valueOf(accessPoint.channel);
        signal.signalDbm = accessPoint.signalDbm;
        signal.distanceMeters = accessPoint.dist;
        signal.mode = accessPoint.mode;
        signal.encryption = accessPoint.encryption;
        signal.isHidden = accessPoint.hidden;
        return signal;
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
        AppLog.init(System.getProperty("kindle.log.file", "drone-detector-tool.log"), 256 * 1024L);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            running = false;
            AppLog.info("Shutdown requested for KindleDroneDetector");
        }, "shutdown-kindle-drone-detector"));
        AppLog.info("=== Kindle 4 Drone Detector Started ===");
        printSupportedFrequencies();

        Map<String, Integer> lastSignalByMac = new HashMap<>();

        while (running) {
            List<RFSignal> signals = new ArrayList<>();
            Map<String, Set<String>> macChannels = new HashMap<>();

            try {
                for (AP accessPoint : WifiScanner.scan()) signals.add(fromAccessPoint(accessPoint));

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

                AppLog.info("DEBUG signals=" + signals.size());

                AppLog.info("\n===== FULL SCAN (" + signals.size() + " APs) @ "
                        + LocalTime.now().toString().substring(0, 8) + " =====");
                int idxLog = 1;
                for (RFSignal s : signals) {
                    String vend = DroneSignatures.lookupOUI(s.macAddress);
                    AppLog.info(String.format("%2d) %-17s %-14s C%-3s %4ddBm %5.0fm Q%d/%d %-4s thr=%d %s",
                            idxLog++, s.macAddress, s.ssid,
                            s.channel.isEmpty() ? "?" : s.channel,
                            s.signalDbm, s.distanceMeters, s.qualityNum, s.qualityMax,
                            s.encryption, s.threatScore,
                            (vend != null ? "<" + vend + "> " : "") + s.threatReason));
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
                    if (signals.size() > maxRows) hud.append("...+").append(signals.size() - maxRows).append(" more (see logs)\n");
                }

                KindleUtils.renderToEInk(hud.toString());

            } catch (Exception e) {
                AppLog.exception("Detector loop error", e);
            }

            KindleUtils.sleep(4000);
        }
    }

    private static void printSupportedFrequencies() {
        AppLog.info("--- Supported frequencies (iwlist wlan0 frequency) ---");
        String output = KindleUtils.readCommand("iwlist", "wlan0", "frequency");
        if (output != null) AppLog.info(output);
        else AppLog.err("Could not list frequencies (command failed or timed out)");
    }
}
