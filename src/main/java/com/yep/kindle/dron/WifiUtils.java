package com.yep.kindle.dron;

import java.io.BufferedReader;
import java.io.InputStreamReader;

public class WifiUtils {

    static double calculateDistance(int signalDbm) {
        return Math.max(0.5, Math.pow(10.0, (-30.0 - signalDbm) / 27.0));
    }

    /** Returns [linkQuality, level, noise] from /proc/net/wireless, or null on failure. */
    static int[] readProcWireless() {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"cat", "/proc/net/wireless"});
            p.waitFor();
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
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
                            r.close();
                            return new int[]{link, level, noise};
                        } catch (Exception ignored) {}
                    }
                    break;
                }
            }
            r.close();
        } catch (Exception ignored) {}
        return null;
    }
}
