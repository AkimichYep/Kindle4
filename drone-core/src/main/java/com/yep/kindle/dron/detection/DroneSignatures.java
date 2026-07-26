package com.yep.kindle.dron.detection;

import java.util.HashMap;
import java.util.Map;

public class DroneSignatures {

    static final Map<String, String> OUI = new HashMap<>();
    static final String[] KEYWORDS = {
            "drone", "dji", "mavic", "tello", "phantom", "spark",
            "parrot", "anafi", "bebop", "fpv", "skydio", "autel",
            "yuneec", "goggles", "avata", "inspire", "matrice", "gimbal"
    };

    static {
        OUI.put("60:60:1F", "DJI");
        OUI.put("34:D2:62", "DJI");
        OUI.put("0C:43:96", "DJI");
        OUI.put("18:97:D0", "DJI");
        OUI.put("48:1C:B9", "DJI");
        OUI.put("E4:7A:2C", "DJI");
        OUI.put("A4:77:61", "DJI");
        OUI.put("FC:77:74", "DJI");
        OUI.put("DC:54:75", "DJI");
        OUI.put("C8:4D:44", "DJI");
        OUI.put("00:26:19", "Parrot");
        OUI.put("00:12:1C", "Parrot");
        OUI.put("90:03:B7", "Parrot");
        OUI.put("A0:14:3D", "Parrot");
        OUI.put("94:E3:6D", "Autel");
        OUI.put("38:1D:14", "Skydio");
        OUI.put("E0:B6:F5", "Yuneec");
        OUI.put("24:0A:C4", "ESP32/DIY");
        OUI.put("30:AE:A4", "ESP32/DIY");
        OUI.put("7C:9E:BD", "ESP32/DIY");
        OUI.put("A4:CF:12", "ESP32/DIY");
    }

    public static String lookupOUI(String mac) {
        if (mac == null || mac.length() < 8) return null;
        return OUI.get(mac.substring(0, 8).toUpperCase());
    }

    public static boolean matchesKeyword(String ssid) {
        if (ssid == null) return false;
        String l = ssid.toLowerCase();
        for (String k : KEYWORDS) if (l.contains(k)) return true;
        return false;
    }
}
