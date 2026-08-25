package com.yep.kindle.dron.service;

import java.util.ArrayList;
import java.util.List;

import com.yep.kindle.dron.model.AP;
import com.yep.kindle.dron.util.WifiUtils;

/**
 * Stateless parser for the output of {@code iwlist <interface> scan}.
 * Keeping parsing independent of process execution makes it reusable by the
 * detector, diagnostic tools, and host-side tests.
 */
public final class WifiScanParser {

    private static final int UNKNOWN_SIGNAL_DBM = -999;

    private WifiScanParser() {
    }

    public static List<AP> parse(String output) {
        List<AP> accessPoints = new ArrayList<AP>();
        if (output == null || output.isEmpty()) return accessPoints;

        String[] lines = output.split("\\r?\\n");
        AP current = null;
        for (String line : lines) {
            String text = line.trim();
            if (text.contains("Address:")) {
                addIfUsable(accessPoints, current);
                current = new AP();
                current.mac = text.substring(text.indexOf("Address:") + 8).trim().toUpperCase();
            } else if (current == null) {
                continue;
            } else if (text.startsWith("ESSID:")) {
                String ssid = unquote(text.substring(6).trim());
                current.ssid = ssid.isEmpty() ? "[HIDDEN]" : ssid;
                current.hidden = ssid.isEmpty();
            } else if (text.startsWith("Mode:")) {
                current.mode = text.substring(5).trim();
            } else if (text.startsWith("Frequency:")) {
                parseChannel(current, text);
            } else if (text.contains("Signal level=")) {
                current.signalDbm = parseDbm(text, "Signal level=", current.signalDbm);
            } else if (text.contains("WPA2") || text.contains("802.11i")) {
                current.encryption = "WPA2";
            } else if (text.contains("WPA Version")) {
                if (!"WPA2".equals(current.encryption)) current.encryption = "WPA";
            } else if (text.startsWith("Encryption key:on") && "Open".equals(current.encryption)) {
                current.encryption = "WEP";
            }
        }
        addIfUsable(accessPoints, current);
        return accessPoints;
    }

    private static void parseChannel(AP accessPoint, String text) {
        int channelIndex = text.indexOf("Channel");
        if (channelIndex < 0) return;
        String channel = text.substring(channelIndex + 7).replace(")", "").trim();
        try {
            accessPoint.channel = Integer.parseInt(channel);
        } catch (NumberFormatException ignored) {
            // Firmware variants sometimes omit or decorate the channel number.
        }
    }

    private static int parseDbm(String text, String label, int fallback) {
        int start = text.indexOf(label) + label.length();
        int end = text.indexOf(" dBm", start);
        if (end <= start) return fallback;
        try {
            return Integer.parseInt(text.substring(start, end).trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static String unquote(String value) {
        return value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")
                ? value.substring(1, value.length() - 1) : value;
    }

    private static void addIfUsable(List<AP> accessPoints, AP accessPoint) {
        if (accessPoint == null || accessPoint.signalDbm == UNKNOWN_SIGNAL_DBM) return;
        if (accessPoint.ssid == null || accessPoint.ssid.isEmpty()) {
            accessPoint.ssid = "[HIDDEN]";
            accessPoint.hidden = true;
        }
        accessPoint.dist = WifiUtils.calculateDistance(accessPoint.signalDbm);
        accessPoints.add(accessPoint);
    }
}

