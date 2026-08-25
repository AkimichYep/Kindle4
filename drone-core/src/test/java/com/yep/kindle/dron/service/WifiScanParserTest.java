package com.yep.kindle.dron.service;

import java.util.List;

import org.junit.Test;

import com.yep.kindle.dron.model.AP;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class WifiScanParserTest {

    @Test
    public void parsesVisibleAndHiddenAccessPointsFromIwlistOutput() {
        String output = "wlan0     Scan completed :\n"
                + "          Cell 01 - Address: 00:11:22:33:44:55\n"
                + "                    ESSID:\"Home WiFi\"\n"
                + "                    Mode:Master\n"
                + "                    Frequency:2.437 GHz (Channel 6)\n"
                + "                    Quality=42/94  Signal level=-62 dBm\n"
                + "                    IE: IEEE 802.11i/WPA2 Version 1\n"
                + "          Cell 02 - Address: aa:bb:cc:dd:ee:ff\n"
                + "                    ESSID:\"\"\n"
                + "                    Mode:Ad-Hoc\n"
                + "                    Frequency:2.462 GHz (Channel 11)\n"
                + "                    Signal level=-77 dBm\n"
                + "                    Encryption key:on\n";

        List<AP> accessPoints = WifiScanParser.parse(output);

        assertEquals(2, accessPoints.size());
        AP visible = accessPoints.get(0);
        assertEquals("00:11:22:33:44:55", visible.mac);
        assertEquals("Home WiFi", visible.ssid);
        assertEquals(6, visible.channel);
        assertEquals(-62, visible.signalDbm);
        assertEquals("WPA2", visible.encryption);
        assertFalse(visible.hidden);
        assertTrue(visible.dist > 0.0);

        AP hidden = accessPoints.get(1);
        assertEquals("AA:BB:CC:DD:EE:FF", hidden.mac);
        assertEquals("[HIDDEN]", hidden.ssid);
        assertEquals(11, hidden.channel);
        assertEquals("WEP", hidden.encryption);
        assertTrue(hidden.hidden);
    }

    @Test
    public void ignoresCellsWithoutAUsableSignalLevel() {
        String output = "Cell 01 - Address: 00:11:22:33:44:55\n"
                + "          ESSID:\"No RSSI\"\n"
                + "Cell 02 - Address: 00:11:22:33:44:56\n"
                + "          ESSID:\"Valid\"\n"
                + "          Signal level=-65 dBm\n";

        List<AP> accessPoints = WifiScanParser.parse(output);

        assertEquals(1, accessPoints.size());
        assertEquals("Valid", accessPoints.get(0).ssid);
    }
}

