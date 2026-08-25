package com.yep.kindle.dron;

import com.yep.kindle.dron.display.KindleCanvas;
import com.yep.kindle.dron.display.KindleLayoutKit;
import com.yep.kindle.dron.util.AppLog;
import com.yep.kindle.dron.util.KindleUtils;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;

public final class KindleWelcomePage {

    private KindleWelcomePage() {}

    public static void generateAndSave(String outputPath) throws Exception {
        String ip  = readWlan0Ip();
        String url = "http://" + ip + ":8080";
        ImageIO.write(render(ip, url), "png", new File(outputPath));
    }

    public static void generateAndShow(String outputPath) {
        try {
            generateAndSave(outputPath);
            KindleUtils.exec("eips", "-c");
            KindleUtils.sleep(200);
            KindleUtils.exec("eips", "-g", outputPath);
        } catch (Exception e) {
            AppLog.err("WelcomePage: " + e.getMessage());
        }
    }

    private static BufferedImage render(String ip, String url) {
        BufferedImage img = KindleCanvas.newImage();
        Graphics2D    g   = KindleCanvas.createGraphics(img);
        final int     W   = KindleCanvas.WIDTH;

        // ── Header ───────────────────────────────────────────────────────────
        KindleLayoutKit.drawHeaderBar(g, 100);
        g.setColor(KindleLayoutKit.WHITE);
        g.setFont(new Font("SansSerif", Font.BOLD, 30));
        FontMetrics fm = g.getFontMetrics();
        g.drawString("KINDLE DRONE", (W - fm.stringWidth("KINDLE DRONE")) / 2, 58);
        g.setFont(new Font("SansSerif", Font.PLAIN, 16));
        fm = g.getFontMetrics();
        g.drawString("Welcome", (W - fm.stringWidth("Welcome")) / 2, 83);

        // ── Network section ──────────────────────────────────────────────────
        KindleLayoutKit.drawSectionHeader(g, null, 0, "NETWORK", 110);

        g.setColor(KindleLayoutKit.BLACK);
        g.setFont(new Font("SansSerif", Font.BOLD, 60));
        fm = g.getFontMetrics();
        g.drawString(ip, (W - fm.stringWidth(ip)) / 2, 228);

        g.setFont(new Font("SansSerif", Font.PLAIN, 18));
        fm = g.getFontMetrics();
        g.setColor(new Color(60, 60, 60));
        g.drawString(url, (W - fm.stringWidth(url)) / 2, 258);

        KindleLayoutKit.drawSeparator(g, 275);

        // ── Web UI section ───────────────────────────────────────────────────
        KindleLayoutKit.drawSectionHeader(g, null, 0, "WEB UI", 290);

        g.setColor(KindleLayoutKit.BLACK);
        g.setFont(new Font("SansSerif", Font.PLAIN, 19));
        fm = g.getFontMetrics();
        String[] desc = {
            "Open the address above in your browser.",
            "Weather · Space Weather · Home Temp",
            "Date & Time · Moon Calendar · Radar"
        };
        int lineH = fm.getHeight() + 6;
        int y = 350;
        for (String line : desc) {
            g.drawString(line, (W - fm.stringWidth(line)) / 2, y);
            y += lineH;
        }

        KindleLayoutKit.drawSeparator(g, 440);

        // ── Quick start section ──────────────────────────────────────────────
        KindleLayoutKit.drawSectionHeader(g, null, 0, "QUICK START", 455);

        g.setColor(KindleLayoutKit.BLACK);
        g.setFont(new Font("SansSerif", Font.PLAIN, 18));
        fm = g.getFontMetrics();
        String[] steps = {
            "1.  Open " + url + " in a browser",
            "2.  Set city name → tap Weather button",
            "3.  Press Next Page to cycle views"
        };
        y = 516;
        for (String step : steps) {
            g.drawString(step, (W - fm.stringWidth(step)) / 2, y);
            y += 32;
        }

        KindleLayoutKit.drawSeparator(g, 625);

        g.setFont(new Font("SansSerif", Font.ITALIC, 14));
        fm = g.getFontMetrics();
        g.setColor(new Color(110, 110, 110));
        String hint = "Kindle Drone Detector  ·  Next Page button cycles display views";
        g.drawString(hint, (W - fm.stringWidth(hint)) / 2, 660);

        // ── Footer + border ──────────────────────────────────────────────────
        KindleLayoutKit.drawFooterBar(g, "Kindle Drone  ·  " + url, 752, 48);
        KindleLayoutKit.drawOuterBorder(g);
        g.dispose();
        return img;
    }

    private static String readWlan0Ip() {
        try {
            NetworkInterface ni = NetworkInterface.getByName("wlan0");
            if (ni != null) {
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (!addr.isLoopbackAddress() && addr instanceof Inet4Address) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {}
        try {
            return InetAddress.getLocalHost().getHostAddress();
        } catch (Exception ignored) {}
        return "?.?.?.?";
    }
}
