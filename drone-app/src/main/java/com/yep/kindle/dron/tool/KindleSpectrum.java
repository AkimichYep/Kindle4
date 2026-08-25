package com.yep.kindle.dron.tool;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.yep.kindle.dron.util.AppLog;
import com.yep.kindle.dron.util.KindleUtils;
import com.yep.kindle.dron.util.WifiUtils;

/**
 * Kindle 4 - 2.4 GHz Wi-Fi Spectrum / Channel Analyzer
 * <p>
 * Demonstrates "playing with frequencies" on a jailbroken Kindle 4 (Atheros AR6000).
 * <p>
 * Two modes of operation:
 * <p>
 * 1) PASSIVE (default, reliable):
 * Runs one `iwlist wlan0 scan`, groups the results by channel and builds a
 * per-channel occupancy graph (AP count + peak signal). Works even while
 * associated to an AP.
 * <p>
 * 2) ACTIVE channel dwelling (experimental, pass "active" as arg):
 * Iterates every 2.4 GHz channel, tunes the radio with
 * iwconfig wlan0 freq <f>G
 * dwells briefly, reads the live noise floor from /proc/net/wireless,
 * then scans. This lets a channel-hopping drone reveal itself as bursts
 * of activity on specific channels.
 * NOTE: active tuning may drop your Wi-Fi association and, without monitor
 * mode, ambient (non-AP) energy still isn't visible. It is mainly useful
 * for measuring the noise floor per channel and forcing scans per band.
 * <p>
 * Useful commands this program wraps:
 * iwconfig wlan0 freq 2.412G   - lock to channel 1
 * iwlist   wlan0 scan          - list APs
 * cat /proc/net/wireless       - live link quality / noise
 */
public class KindleSpectrum {

    private static volatile boolean running = true;

    // 2.4 GHz channel -> center frequency (GHz). Kindle 4 supports 1-13.
    private static final double[] CH_FREQ = {
            0,      // index 0 unused
            2.412,  // ch1
            2.417,  // ch2
            2.422,  // ch3
            2.427,  // ch4
            2.432,  // ch5
            2.437,  // ch6
            2.442,  // ch7
            2.447,  // ch8
            2.452,  // ch9
            2.457,  // ch10
            2.462,  // ch11
            2.467,  // ch12
            2.472   // ch13
    };
    private static final int MAX_CH = 13;

    static class ChannelStats {
        int apCount = 0;
        int peakDbm = -999;
        int noiseDbm = -999;
        List<String> ssids = new ArrayList<>();
    }

    /**
     * One access-point observation from a single scan.
     */
    static class Obs {
        String mac;
        String ssid;
        int channel;
        int signal;

        Obs(String mac, String ssid, int channel, int signal) {
            this.mac = mac;
            this.ssid = ssid;
            this.channel = channel;
            this.signal = signal;
        }
    }

    /**
     * Persistent per-MAC history across scans - used to detect channel hopping.
     */
    static class HopRecord {
        String mac;
        String ssid = "";
        int lastChannel = -1;
        int lastSignal = -999;
        int hopCount = 0;                       // number of channel changes observed
        long firstSeen = System.currentTimeMillis();
        long lastSeen = System.currentTimeMillis();
        List<Integer> channelHistory = new ArrayList<>(); // ordered distinct-ish channel trail
    }

    // Hop-tracking tuning
    private static final int HOP_HISTORY_MAX = 6;     // channels remembered per MAC in trail
    private static final long STALE_MS = 120000; // forget a MAC after 2 min unseen


    public static void main(String[] args) {
        boolean active = args.length > 0 && args[0].equalsIgnoreCase("active");
        AppLog.init(System.getProperty("kindle.log.file", "spectrum.log"), 256 * 1024L);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            running = false;
            AppLog.info("Shutdown requested for KindleSpectrum");
        }, "shutdown-kindle-spectrum"));
        AppLog.info("=== Kindle 2.4GHz Spectrum Analyzer ===");
        AppLog.info("Mode: " + (active ? "ACTIVE (channel dwelling)" : "PASSIVE (scan grouping)"));

        // Persistent across scans: MAC -> hop history (time-series channel tracking)
        Map<String, HopRecord> hopTracker = new HashMap<>();

        while (running) {
            Map<Integer, ChannelStats> spectrum = new HashMap<>();
            for (int c = 1; c <= MAX_CH; c++) spectrum.put(c, new ChannelStats());
            List<Obs> observations = new ArrayList<>();

            try {
                if (active) {
                    activeSweep(spectrum, observations);
                } else {
                    passiveScan(spectrum, observations);
                }

                // Update the time-series channel-hop tracker with this scan
                updateHopTracker(hopTracker, observations);

                renderSpectrum(spectrum, hopTracker, active);
            } catch (Exception e) {
                AppLog.exception("Spectrum error", e);
            }

            KindleUtils.sleep(3000);
        }
    }

    /**
     * Time-series channel-hop tracking.
     * For each MAC seen this scan, compares its channel to the channel we last
     * saw it on. A change increments hopCount and appends to its channel trail -
     * the strongest passive drone/RC-link signature this hardware can detect.
     */
    private static void updateHopTracker(Map<String, HopRecord> tracker, List<Obs> observations) {
        long now = System.currentTimeMillis();

        for (Obs o : observations) {
            if (o.mac == null || o.mac.isEmpty() || o.channel < 1) continue;
            HopRecord rec = tracker.get(o.mac);
            if (rec == null) {
                rec = new HopRecord();
                rec.mac = o.mac;
                rec.lastChannel = o.channel;
                rec.channelHistory.add(o.channel);
                tracker.put(o.mac, rec);
            } else if (o.channel != rec.lastChannel) {
                // Channel change detected between scans -> a hop!
                rec.hopCount++;
                rec.lastChannel = o.channel;
                rec.channelHistory.add(o.channel);
                while (rec.channelHistory.size() > HOP_HISTORY_MAX) {
                    rec.channelHistory.remove(0);
                }
            }
            rec.ssid = (o.ssid == null || o.ssid.isEmpty()) ? "[hidden]" : o.ssid;
            rec.lastSignal = o.signal;
            rec.lastSeen = now;
        }

        // Prune stale MACs so the tracker doesn't grow unbounded
        java.util.Iterator<Map.Entry<String, HopRecord>> it = tracker.entrySet().iterator();
        while (it.hasNext()) {
            if (now - it.next().getValue().lastSeen > STALE_MS) it.remove();
        }
    }

    /**
     * PASSIVE: one scan, group APs by channel and collect per-AP observations.
     */
    private static void passiveScan(Map<Integer, ChannelStats> spectrum, List<Obs> observations) throws Exception {
        String output = KindleUtils.readCommand("iwlist", "wlan0", "scan");
        if (output == null || output.trim().isEmpty()) {
            throw new Exception("iwlist scan returned no data");
        }
        String[] lines = output.split("\\r?\\n");
        String mac = "";
        String ssid = "";
        int channel = -1;
        int signal = -999;

        for (String line : lines) {
            String t = line.trim();
            if (t.contains("Address:")) {
                int idx = t.indexOf("Address:");
                mac = t.substring(idx + 9).trim();
            } else if (t.startsWith("ESSID:")) {
                String v = t.substring(6).trim();
                if (v.startsWith("\"") && v.endsWith("\"") && v.length() >= 2)
                    v = v.substring(1, v.length() - 1);
                ssid = v.isEmpty() ? "[hidden]" : v;
            } else if (t.startsWith("Frequency:")) {
                int ci = t.indexOf("Channel");
                if (ci > 0) {
                    try {
                        channel = Integer.parseInt(t.substring(ci + 8).replace(")", "").trim());
                    } catch (Exception ignored) {
                        channel = -1;
                    }
                }
            } else if (t.contains("Signal level=")) {
                try {
                    int si = t.indexOf("Signal level=") + 13;
                    int se = t.indexOf(" dBm", si);
                    if (se > si) signal = Integer.parseInt(t.substring(si, se).trim());
                } catch (Exception ignored) {
                }

                if (channel >= 1 && channel <= MAX_CH) {
                    ChannelStats cs = spectrum.get(channel);
                    cs.apCount++;
                    if (signal > cs.peakDbm) cs.peakDbm = signal;
                    cs.ssids.add(ssid); // keep all SSIDs for detail view
                    if (observations != null) {
                        observations.add(new Obs(mac, ssid, channel, signal));
                    }
                }
                // reset per-cell
                mac = "";
                ssid = "";
                channel = -1;
                signal = -999;
            }
        }
    }

    /**
     * ACTIVE: tune each channel, read noise floor, then scan that band.
     */
    private static void activeSweep(Map<Integer, ChannelStats> spectrum, List<Obs> observations) throws Exception {
        for (int c = 1; c <= MAX_CH; c++) {
            // 1) Lock the radio to this channel's frequency
            String freq = String.format("%.3fG", CH_FREQ[c]);
            try {
                KindleUtils.exec("iwconfig", "wlan0", "freq", freq);
            } catch (Exception e) {
                AppLog.warn("Frequency set failed ch" + c + ": " + e.getMessage());
            }

            // 2) Dwell so the radio settles
            KindleUtils.sleep(150);

            // 3) Read live noise floor from /proc/net/wireless
            int noise = readNoiseFloor();
            spectrum.get(c).noiseDbm = noise;

            AppLog.info("Dwell ch" + c + " (" + freq + ") noise=" + noise);
        }

        // 4) After sweeping, one full scan to populate AP counts + observations
        passiveScan(spectrum, observations);
    }

    private static int readNoiseFloor() {
        int[] r = WifiUtils.readProcWireless();
        return r != null ? r[2] : -999;
    }

    /**
     * Renders the HOPPERS panel + per-channel network details to the e-ink screen.
     */
    private static void renderSpectrum(Map<Integer, ChannelStats> spectrum,
                                       Map<String, HopRecord> hopTracker, boolean active) {
        int totalAps = 0;
        int busiest = 1;
        int busiestCount = -1;
        for (int c = 1; c <= MAX_CH; c++) {
            ChannelStats cs = spectrum.get(c);
            totalAps += cs.apCount;
            if (cs.apCount > busiestCount) {
                busiestCount = cs.apCount;
                busiest = c;
            }
        }

        // Collect MACs that have hopped channels, most active first
        List<HopRecord> hoppers = new ArrayList<>();
        for (HopRecord h : hopTracker.values()) {
            if (h.hopCount > 0) hoppers.add(h);
        }
        hoppers.sort((a, b) -> b.hopCount - a.hopCount);

        StringBuilder hud = new StringBuilder();
        hud.append("2.4GHz +HOP ").append(LocalTime.now().toString().substring(0, 8))
                .append(active ? " [ACT]" : "").append("\n");
        hud.append("APs:").append(totalAps)
                .append(" Busy:C").append(busiest).append("(").append(busiestCount).append(")")
                .append(" Hop:").append(hoppers.size()).append("\n");

        int rows = 2; // header lines used above

        // ---- HOPPERS panel (channel-changers = likely drone/RC links) ----
        hud.append("== HOPPERS (channel-changers) ==\n");
        rows++;
        if (hoppers.isEmpty()) {
            hud.append(" none yet - keep scanning\n");
            rows++;
        } else {
            int shown = 0;
            for (HopRecord h : hoppers) {
                if (rows >= KindleUtils.ROWS - 4 || shown >= 8) break;
                // Build channel trail like C6>3>11
                StringBuilder trail = new StringBuilder();
                for (int i = 0; i < h.channelHistory.size(); i++) {
                    if (i == 0) trail.append("C");
                    else trail.append(">");
                    trail.append(h.channelHistory.get(i));
                }
                String macShort = h.mac.length() >= 8 ? h.mac.substring(0, 8) : h.mac;
                String row = String.format("!%s h%d %s %ddB %s",
                        macShort, h.hopCount, trail.toString(), h.lastSignal, h.ssid);
                if (row.length() > KindleUtils.COLS) row = row.substring(0, KindleUtils.COLS);
                hud.append(row).append("\n");
                rows++;
                shown++;
            }
        }

        // ---- Per-channel network detail (fills remaining rows) ----
        hud.append("== CHANNELS ==\n");
        rows++;
        for (int c = 1; c <= MAX_CH && rows < KindleUtils.ROWS; c++) {
            ChannelStats cs = spectrum.get(c);
            if (cs.apCount == 0) continue;

            String noiseStr = validNoise(cs.noiseDbm) ? (cs.noiseDbm + "dB") : "";
            String head = String.format("C%-2d %.3fG %dAP peak%ddB %s",
                    c, CH_FREQ[c], cs.apCount, cs.peakDbm, noiseStr);
            if (head.length() > KindleUtils.COLS) head = head.substring(0, KindleUtils.COLS);
            hud.append(head).append("\n");
            rows++;

            for (String ssid : cs.ssids) {
                if (rows >= KindleUtils.ROWS) break;
                String s = (ssid == null || ssid.isEmpty()) ? "[hidden]" : ssid;
                String row = "  - " + s;
                if (row.length() > KindleUtils.COLS) row = row.substring(0, KindleUtils.COLS);
                hud.append(row).append("\n");
                rows++;
            }
        }

        if (totalAps == 0) {
            hud.append("No networks detected.\n");
        }

        // ---- Console: full detail (unlimited) ----
        AppLog.info("\n===== SPECTRUM @ " + LocalTime.now().toString().substring(0, 8) + " =====");
        for (int c = 1; c <= MAX_CH; c++) {
            ChannelStats cs = spectrum.get(c);
            String n = validNoise(cs.noiseDbm) ? (cs.noiseDbm + "dBm") : "n/a";
            AppLog.info(String.format("Ch%2d %.3fGHz APs=%d peak=%ddBm noise=%s %s",
                    c, CH_FREQ[c], cs.apCount, cs.peakDbm, n, cs.ssids));
        }
        if (!hoppers.isEmpty()) {
            AppLog.info("--- CHANNEL HOPPERS (time-series) ---");
            for (HopRecord h : hoppers) {
                long ageSec = (System.currentTimeMillis() - h.firstSeen) / 1000;
                AppLog.info(String.format("%s hops=%d trail=%s sig=%ddBm age=%ds ssid=%s",
                        h.mac, h.hopCount, h.channelHistory, h.lastSignal, ageSec, h.ssid));
            }
        }

        KindleUtils.renderToEInk(hud.toString());
    }

    private static boolean validNoise(int noise) {
        return noise != -999 && noise < 0 && noise > -130;
    }
}

