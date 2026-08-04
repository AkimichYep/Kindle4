package com.yep.kindle.dron.util;

import java.io.BufferedReader;
import java.io.FileReader;

public class WifiUtils {

    public static double calculateDistance(int signalDbm) {
        return Math.max(0.5, Math.pow(10.0, (-30.0 - signalDbm) / 27.0));
    }

    /** Returns [linkQuality, level, noise] from /proc/net/wireless, or null on failure. */
    public static int[] readProcWireless() {
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/net/wireless"))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.trim().startsWith("wlan0")) {
                    String[] parts = line.trim().split("\\s+");
                    // parts: [wlan0:, status, link, level, noise, ...]
                    if (parts.length >= 5) {
                        try {
                            int link  = Integer.parseInt(parts[2].replace(".", "").trim());
                            int level = Integer.parseInt(parts[3].replace(".", "").trim());
                            int noise = Integer.parseInt(parts[4].replace(".", "").trim());
                            if (level > 127) level -= 256;
                            if (noise > 127) noise -= 256;
                            return new int[]{link, level, noise};
                        } catch (Exception ignored) {}
                    }
                    break;
                }
            }
        } catch (Exception ignored) {}
        return null;
    }
}
