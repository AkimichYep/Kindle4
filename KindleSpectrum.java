import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Kindle 4 - 2.4 GHz Wi-Fi Spectrum / Channel Analyzer
 *
 * Demonstrates "playing with frequencies" on a jailbroken Kindle 4 (Atheros AR6000).
 *
 * Two modes of operation:
 *
 *  1) PASSIVE (default, reliable):
 *       Runs one `iwlist wlan0 scan`, groups the results by channel and builds a
 *       per-channel occupancy graph (AP count + peak signal). Works even while
 *       associated to an AP.
 *
 *  2) ACTIVE channel dwelling (experimental, pass "active" as arg):
 *       Iterates every 2.4 GHz channel, tunes the radio with
 *         iwconfig wlan0 freq <f>G
 *       dwells briefly, reads the live noise floor from /proc/net/wireless,
 *       then scans. This lets a channel-hopping drone reveal itself as bursts
 *       of activity on specific channels.
 *       NOTE: active tuning may drop your Wi-Fi association and, without monitor
 *       mode, ambient (non-AP) energy still isn't visible. It is mainly useful
 *       for measuring the noise floor per channel and forcing scans per band.
 *
 * Useful commands this program wraps:
 *   iwconfig wlan0 freq 2.412G   - lock to channel 1
 *   iwlist   wlan0 scan          - list APs
 *   cat /proc/net/wireless       - live link quality / noise
 */
public class KindleSpectrum {

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

    // Kindle 4 e-ink text mode (eips): 50 columns x 40 rows (600x800, 12x20 font).
    private static final int MAX_SCREEN_ROWS = 40;
    private static final int MAX_SCREEN_COLS = 50;

    static class ChannelStats {
        int apCount = 0;
        int peakDbm = -999;
        int noiseDbm = -999;
        List<String> ssids = new ArrayList<>();
    }

    /** One access-point observation from a single scan. */
    static class Obs {
        String mac;
        String ssid;
        int channel;
        int signal;
        Obs(String mac, String ssid, int channel, int signal) {
            this.mac = mac; this.ssid = ssid; this.channel = channel; this.signal = signal;
        }
    }

    /** Persistent per-MAC history across scans - used to detect channel hopping. */
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
    private static final int   HOP_HISTORY_MAX = 6;     // channels remembered per MAC in trail
    private static final long  STALE_MS        = 120000; // forget a MAC after 2 min unseen


    public static void main(String[] args) {
        boolean active = args.length > 0 && args[0].equalsIgnoreCase("active");
        System.out.println("=== Kindle 2.4GHz Spectrum Analyzer ===");
        System.out.println("Mode: " + (active ? "ACTIVE (channel dwelling)" : "PASSIVE (scan grouping)"));

        // Persistent across scans: MAC -> hop history (time-series channel tracking)
        Map<String, HopRecord> hopTracker = new HashMap<>();

        while (true) {
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
                System.err.println("Spectrum error: " + e.getMessage());
            }

            try { Thread.sleep(3000); } catch (InterruptedException ignored) {}
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

    /** PASSIVE: one scan, group APs by channel and collect per-AP observations. */
    private static void passiveScan(Map<Integer, ChannelStats> spectrum, List<Obs> observations) throws Exception {
        Process p = Runtime.getRuntime().exec(new String[]{"iwlist", "wlan0", "scan"});
        p.waitFor();
        BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
        String line;
        String mac = "";
        String ssid = "";
        int channel = -1;
        int signal = -999;

        while ((line = r.readLine()) != null) {
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
                    } catch (Exception ignored) { channel = -1; }
                }
            } else if (t.contains("Signal level=")) {
                try {
                    int si = t.indexOf("Signal level=") + 13;
                    int se = t.indexOf(" dBm", si);
                    if (se > si) signal = Integer.parseInt(t.substring(si, se).trim());
                } catch (Exception ignored) {}

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
                mac = ""; ssid = ""; channel = -1; signal = -999;
            }
        }
        r.close();
    }

    /** ACTIVE: tune each channel, read noise floor, then scan that band. */
    private static void activeSweep(Map<Integer, ChannelStats> spectrum, List<Obs> observations) throws Exception {
        for (int c = 1; c <= MAX_CH; c++) {
            // 1) Lock the radio to this channel's frequency
            String freq = String.format("%.3fG", CH_FREQ[c]);
            try {
                Runtime.getRuntime().exec(new String[]{"iwconfig", "wlan0", "freq", freq}).waitFor();
            } catch (Exception e) {
                System.err.println("freq set failed ch" + c + ": " + e.getMessage());
            }

            // 2) Dwell so the radio settles
            try { Thread.sleep(150); } catch (InterruptedException ignored) {}

            // 3) Read live noise floor from /proc/net/wireless
            int noise = readNoiseFloor();
            spectrum.get(c).noiseDbm = noise;

            System.err.println("Dwell ch" + c + " (" + freq + ") noise=" + noise);
        }

        // 4) After sweeping, one full scan to populate AP counts + observations
        passiveScan(spectrum, observations);
    }

    /**
     * Reads the noise level column from /proc/net/wireless.
     *
     * Typical format:
     *   Inter-| sta-|  Quality       | Discarded packets ...
     *    face | tus | link level noise ...
     *   wlan0: 0000   54.  -56.  -95.  ...
     *
     * Columns after "wlan0:" are: status, link, level, noise.
     * Values may carry a trailing '.'; noise is a negative dBm figure.
     */
    private static int readNoiseFloor() {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"cat", "/proc/net/wireless"});
            p.waitFor();
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.startsWith("wlan0")) {
                    // Strip iface label, then split remaining numeric columns.
                    int colon = line.indexOf(':');
                    String rest = (colon >= 0 ? line.substring(colon + 1) : line).trim();
                    String[] parts = rest.split("\\s+");
                    // parts: [status, link, level, noise, ...]
                    if (parts.length >= 4) {
                        // Remove trailing '.' and parse as integer
                        String noiseStr = parts[3].replace(".", "").trim();
                        r.close();
                        int noise = Integer.parseInt(noiseStr);
                        // /proc reports level/noise as signed 8-bit sometimes (0-255).
                        // Convert values >127 to their negative equivalent.
                        if (noise > 127) noise = noise - 256;
                        return noise;
                    }
                }
            }
            r.close();
        } catch (Exception ignored) {}
        return -999;
    }

    /** Renders the HOPPERS panel + per-channel network details to the e-ink screen. */
    private static void renderSpectrum(Map<Integer, ChannelStats> spectrum,
                                       Map<String, HopRecord> hopTracker, boolean active) {
        int totalAps = 0;
        int busiest = 1;
        int busiestCount = -1;
        for (int c = 1; c <= MAX_CH; c++) {
            ChannelStats cs = spectrum.get(c);
            totalAps += cs.apCount;
            if (cs.apCount > busiestCount) { busiestCount = cs.apCount; busiest = c; }
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
                if (rows >= MAX_SCREEN_ROWS - 4 || shown >= 8) break;
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
                if (row.length() > MAX_SCREEN_COLS) row = row.substring(0, MAX_SCREEN_COLS);
                hud.append(row).append("\n");
                rows++;
                shown++;
            }
        }

        // ---- Per-channel network detail (fills remaining rows) ----
        hud.append("== CHANNELS ==\n");
        rows++;
        for (int c = 1; c <= MAX_CH && rows < MAX_SCREEN_ROWS; c++) {
            ChannelStats cs = spectrum.get(c);
            if (cs.apCount == 0) continue;

            String noiseStr = validNoise(cs.noiseDbm) ? (cs.noiseDbm + "dB") : "";
            String head = String.format("C%-2d %.3fG %dAP peak%ddB %s",
                    c, CH_FREQ[c], cs.apCount, cs.peakDbm, noiseStr);
            if (head.length() > MAX_SCREEN_COLS) head = head.substring(0, MAX_SCREEN_COLS);
            hud.append(head).append("\n");
            rows++;

            for (String ssid : cs.ssids) {
                if (rows >= MAX_SCREEN_ROWS) break;
                String s = (ssid == null || ssid.isEmpty()) ? "[hidden]" : ssid;
                String row = "  - " + s;
                if (row.length() > MAX_SCREEN_COLS) row = row.substring(0, MAX_SCREEN_COLS);
                hud.append(row).append("\n");
                rows++;
            }
        }

        if (totalAps == 0) {
            hud.append("No networks detected.\n");
        }

        // ---- Console: full detail (unlimited) ----
        System.out.println("\n===== SPECTRUM @ " + LocalTime.now().toString().substring(0, 8) + " =====");
        for (int c = 1; c <= MAX_CH; c++) {
            ChannelStats cs = spectrum.get(c);
            String n = validNoise(cs.noiseDbm) ? (cs.noiseDbm + "dBm") : "n/a";
            System.out.printf("Ch%2d %.3fGHz APs=%d peak=%ddBm noise=%s %s%n",
                    c, CH_FREQ[c], cs.apCount, cs.peakDbm, n, cs.ssids);
        }
        if (!hoppers.isEmpty()) {
            System.out.println("--- CHANNEL HOPPERS (time-series) ---");
            for (HopRecord h : hoppers) {
                long ageSec = (System.currentTimeMillis() - h.firstSeen) / 1000;
                System.out.printf("%s hops=%d trail=%s sig=%ddBm age=%ds ssid=%s%n",
                        h.mac, h.hopCount, h.channelHistory, h.lastSignal, ageSec, h.ssid);
            }
        }

        renderToEInk(hud.toString());
    }

    /** Noise floor is always negative (~ -90 to -100 dBm). Reject bogus values. */
    private static boolean validNoise(int noise) {
        return noise != -999 && noise < 0 && noise > -130;
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

