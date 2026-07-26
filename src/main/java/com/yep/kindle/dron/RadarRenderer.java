package com.yep.kindle.dron;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.*;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * RadarRenderer — pure-Java bitmap radar chart for the Kindle 4 e-ink display.
 *
 * Produces a grayscale PNG file that eips can display with:
 *   eips -g /mnt/us/radar.png
 *
 * Layout (600 × 800 px, 4-bit grey):
 *
 *   ┌─────────────────────────────────────────┐
 *   │  DRONE RADAR  HH:MM:SS  AP:N  ARMED     │  header row (~32 px)
 *   ├─────────────────────────────────────────┤
 *   │                                         │
 *   │          radar disc (560 × 560)         │
 *   │    concentric rings: 10/30/60/100/200m  │
 *   │    cross-hair, N/S/W/E labels           │
 *   │    each AP = filled circle + label      │
 *   │                                         │
 *   ├─────────────────────────────────────────┤
 *   │  legend / stats rows (~208 px)          │
 *   │  threat-sorted AP list with dist/flags  │
 *   └─────────────────────────────────────────┘
 *
 * Coordinate system: the radar centre represents "us" (the Kindle).
 * AP distance → radial pixel position via a log-ish scale so that near
 * targets (5-30 m) are spread out and distant ones (100-200 m+) compress
 * toward the rim.  Azimuth is randomised per-MAC (stable across renders for
 * the same MAC via hashCode) because Wi-Fi RSSI gives no bearing.
 *
 * Pixel font: 5 × 7 bitmask glyphs for 0-9, A-Z, space, common punctuation.
 * Scale ×2 for body text, ×3 for header.
 */
public class RadarRenderer {

    // ── Output files ─────────────────────────────────────────────────────────
    // Current (latest) frame — also the target for eips -g on first show.
    static final String IMAGE_FILE = "/mnt/us/radar.png";
    static final String IMAGE_TMP  = "/mnt/us/radar.png.tmp";

    // Ring-buffer of the last 3 rendered frames for Kindle animation.
    static final int    FRAME_COUNT = 3;
    static final String[] FRAME_FILES = {
        "/mnt/us/radar0.png",
        "/mnt/us/radar1.png",
        "/mnt/us/radar2.png"
    };
    // Index of the slot that will be written on the *next* render() call.
    static int nextFrame = 0;

    // ── Canvas dimensions ─────────────────────────────────────────────────────
    static final int W = 600;
    static final int H = 800;

    // ── Radar disc geometry ───────────────────────────────────────────────────
    static final int RADAR_R   = 270;               // disc radius in pixels
    static final int RADAR_CX  = W / 2;             // disc centre X
    static final int RADAR_CY  = 32 + 8 + RADAR_R; // disc centre Y (below header)

    // ── Greyscale palette (0 = black, 255 = white) ────────────────────────────
    // Output is raw 8-bit greyscale (no header) consumed by `eips -g`.
    // eips maps the full 0-255 range to the 16 E-ink grey levels.
    static final int BG         = 255; // canvas background: pure white
    static final int DISC_BG    = 220; // radar disc fill: light grey
    static final int RING_GREY  = 140; // range-ring outlines: medium grey
    static final int AXIS_GREY  = 120; // cross-hair: slightly darker
    static final int LABEL_GREY =  80; // ring distance labels: dark grey
    static final int AP_NORM    = 100; // normal AP dot
    static final int AP_NEW     =  40; // new AP (darker)
    static final int AP_THREAT  =   0; // threat AP (solid black)
    static final int AP_GHOST   = 175; // ghost AP (absent but recently seen — very light)
    static final int AP_TRAIL_OLD = 195; // oldest trail point
    static final int AP_TRAIL_MID = 165; // middle trail point
    static final int AP_TRAIL_NEW = 135; // newest trail point
    static final int TEXT_FG    =   0; // body text: black
    static final int TEXT_GHOST = 160; // ghost label text: light grey
    static final int HDR_FG     =   0; // header text (drawn over black bar → use 255 below)
    static final int DIV_LINE   = 120; // legend divider: medium grey

    // ── Range rings (metres) ──────────────────────────────────────────────────
    static final double[] RINGS_M = {10, 30, 60, 120, 200};

    // ── Legend area Y start ───────────────────────────────────────────────────
    static final int LEGEND_Y = RADAR_CY + RADAR_R + 12;

    // ── 5×7 pixel font ────────────────────────────────────────────────────────
    // Each char is 5 columns × 7 rows, stored as 7 ints, each int's low 5 bits
    // = one row (bit 4 = leftmost pixel).
    // Characters: space(32) through Z(90), digits 0-9, colon, dot, slash, hyphen
    static final int[][] FONT = buildFont();

    static final int TRAIL_KEEP = 4;
    static final Map<String, ArrayDeque<Integer>> TRAILS = new HashMap<>(); // distance in pixels

    // =========================================================================
    // Public entry point
    // =========================================================================

    /**
     * Render the radar image and write it to IMAGE_FILE.
     * Called from the main loop once per minute with a pre-filtered AP list
     * that contains only: new APs, moving APs, threat APs, and ghost entries
     * (known-threat MACs not currently visible but seen recently).
     *
     * @param aps     filtered AP list (scored + sorted, may include ghost entries)
     * @param armed   arming state
     * @param loop    loop counter
     * @param extRF   external RF flag
     */
    public static void render(List<KindleDroneDetectorPro.AP> aps,
                              boolean armed, int loop, boolean extRF) {
        byte[] px = new byte[W * H];
        fill(px, (byte) BG);

        // ── Header bar ───────────────────────────────────────────────────────
        fillRect(px, 0, 0, W, 32, (byte) 0);
        int thr   = 0;
        int ghosts = 0;
        for (KindleDroneDetectorPro.AP a : aps) {
            if (a.threat >= 30) thr++;
            if (a.ghost) ghosts++;
        }
        int live = aps.size() - ghosts;
        String hdr = String.format("DRONE RADAR  %tT  LIVE:%d  THR:%d  GHOST:%d  %s%s",
                System.currentTimeMillis(), live, thr, ghosts,
                armed ? "ARMED" : "LEARN",
                extRF  ? " !!RF!!" : "");
        drawString(px, 4, 8, hdr, (byte) 255, 2);

        // ── Radar disc ───────────────────────────────────────────────────────
        fillCircle(px, RADAR_CX, RADAR_CY, RADAR_R, (byte) DISC_BG);
        drawCircle(px, RADAR_CX, RADAR_CY, RADAR_R, (byte) RING_GREY);

        // Cross-hair
        hline(px, RADAR_CX - RADAR_R, RADAR_CX + RADAR_R, RADAR_CY, (byte) AXIS_GREY);
        vline(px, RADAR_CX, RADAR_CY - RADAR_R, RADAR_CY + RADAR_R, (byte) AXIS_GREY);

        // Compass labels
        drawString(px, RADAR_CX - 5,  RADAR_CY - RADAR_R - 14, "N", (byte) LABEL_GREY, 2);
        drawString(px, RADAR_CX - 5,  RADAR_CY + RADAR_R +  3, "S", (byte) LABEL_GREY, 2);
        drawString(px, RADAR_CX - RADAR_R - 12, RADAR_CY - 7,  "W", (byte) LABEL_GREY, 2);
        drawString(px, RADAR_CX + RADAR_R +  3,  RADAR_CY - 7, "E", (byte) LABEL_GREY, 2);

        // Range rings
        double maxDist = maxDistM(aps);
        double scale   = computeScale(maxDist);

        for (double rm : RINGS_M) {
            int pr = distToPixels(rm, scale);
            if (pr > RADAR_R) continue;
            drawCircle(px, RADAR_CX, RADAR_CY, pr, (byte) RING_GREY);
            // Label at top of ring (just inside)
            String lbl = rm >= 1000 ? (int)(rm/1000)+"km" : (int)rm+"m";
            drawString(px, RADAR_CX + 3, RADAR_CY - pr + 2, lbl, (byte) LABEL_GREY, 1);
        }

        // Update per-MAC trail buffers from current live AP distances.
        updateTrails(aps, scale);

        // ── AP dots ──────────────────────────────────────────────────────────
        // Draw ghost (absent) APs first so live APs paint over them
        for (KindleDroneDetectorPro.AP a : aps) {
            if (!a.ghost) continue;
            if (a.dist <= 0) continue;
            double angle = stableAngle(a.mac);
            int    pr    = Math.min(distToPixels(a.dist, scale), RADAR_R - 6);
            int    ax    = RADAR_CX + (int)(pr * Math.cos(angle));
            int    ay    = RADAR_CY + (int)(pr * Math.sin(angle));

            // Ghost: outline circle only (no fill) with lighter colour
            drawCircle(px, ax, ay, 5, (byte) AP_GHOST);
            drawCircle(px, ax, ay, 4, (byte) AP_GHOST);

            // Label in light grey
            String lbl = shortLabel(a) + "?";
            int lx = ax + 7;
            int ly = ay - 5;
            if (lx + lbl.length() * 6 > RADAR_CX + RADAR_R) lx = ax - 7 - lbl.length() * 6;
            if (ly < RADAR_CY - RADAR_R + 2) ly = ay + 8;
            drawString(px, lx, ly, lbl, (byte) TEXT_GHOST, 1);
        }

        // Draw live APs on top
        for (KindleDroneDetectorPro.AP a : aps) {
            if (a.ghost) continue;
            if (a.dist <= 0) continue;
            double angle = stableAngle(a.mac);
            int    pr    = Math.min(distToPixels(a.dist, scale), RADAR_R - 6);
            int    ax    = RADAR_CX + (int)(pr * Math.cos(angle));
            int    ay    = RADAR_CY + (int)(pr * Math.sin(angle));

            // Draw trail first (oldest -> newest), then current dot on top.
            drawTrail(px, a.mac, angle);

            int dotR = a.threat >= 60 ? 8 : (a.threat >= 30 ? 6 : 4);
            int col  = a.threat >= 60 ? AP_THREAT : (a.threat >= 30 ? AP_NEW : AP_NORM);
            fillCircle(px, ax, ay, dotR, (byte) col);

            // Short label: SSID (truncated) + dist
            String lbl = shortLabel(a);
            int lx = ax + dotR + 2;
            int ly = ay - 6;
            // keep inside disc
            if (lx + lbl.length() * 6 > RADAR_CX + RADAR_R) lx = ax - dotR - lbl.length() * 6 - 2;
            if (ly < RADAR_CY - RADAR_R + 2) ly = ay + dotR + 2;
            drawString(px, lx, ly, lbl, (byte) TEXT_FG, 1);
        }

        // ── Divider ──────────────────────────────────────────────────────────
        hline(px, 0, W - 1, LEGEND_Y - 4, (byte) DIV_LINE);

        // ── Legend / stats rows ───────────────────────────────────────────────
        int ly = LEGEND_Y;
        int maxRows = (H - ly - 22) / 10; // 1× font = 7px + 3 spacing; reserve 2 rows for footer
        int shown = 0;
        // Live APs first
        for (KindleDroneDetectorPro.AP a : aps) {
            if (shown >= maxRows) break;
            if (a.ghost) continue;
            int col = a.threat >= 60 ? AP_THREAT : (a.threat >= 30 ? AP_NEW : AP_NORM);
            // coloured bullet
            fillRect(px, 4, ly + 1, 7, 5, (byte) col);
            String ssid = a.ssid.length() > 13 ? a.ssid.substring(0, 13) : a.ssid;
            String row = String.format("%3d %-13s %4.0fm%s C%-2d %s",
                    a.threat, ssid, a.dist,
                    KindleDroneDetectorPro.formatDistDeltaTag(a.distDeltaM),
                    a.channel, a.flags);
            drawString(px, 14, ly, row, (byte) TEXT_FG, 1);
            ly += 10;
            shown++;
        }
        // Ghost APs after live
        for (KindleDroneDetectorPro.AP a : aps) {
            if (shown >= maxRows) break;
            if (!a.ghost) continue;
            // hollow bullet for ghost
            set(px, 4, ly + 1, (byte) AP_GHOST); set(px, 10, ly + 1, (byte) AP_GHOST);
            set(px, 4, ly + 5, (byte) AP_GHOST); set(px, 10, ly + 5, (byte) AP_GHOST);
            String ssid = a.ssid.length() > 13 ? a.ssid.substring(0, 13) : a.ssid;
            String row = String.format("  ? %-13s %4.0fm %s",
                    ssid, a.dist, a.flags);
            drawString(px, 14, ly, row, (byte) TEXT_GHOST, 1);
            ly += 10;
            shown++;
        }

        // Stats line at bottom
        String stats = String.format("NF:%d SNR:%d  loop#%d",
                KindleDroneDetectorPro.noiseFloor,
                KindleDroneDetectorPro.csSnr,
                loop);
        drawString(px, 4, H - 20, stats, (byte) LABEL_GREY, 1);

        String wx = KindleDroneDetectorPro.weatherSummaryForRadar();
        if (!wx.isEmpty()) {
            drawString(px, 4, H - 10, wx, (byte) LABEL_GREY, 1);
        }

        writePng(px);
    }

    // =========================================================================
    // Scale helpers
    // =========================================================================

    /** Choose a scale so the furthest AP sits at ~90 % of the radar radius. */
    static double computeScale(double maxDist) {
        if (maxDist < 20)  maxDist = 20;
        if (maxDist > 600) maxDist = 600;
        // log scale: pixel = RADAR_R * 0.9 * log(1 + dist/D0) / log(1 + maxDist/D0)
        // D0 tunes the curve; 15 m spreads near targets well.
        return maxDist; // passed into distToPixels for normalisation
    }

    static double maxDistM(List<KindleDroneDetectorPro.AP> aps) {
        double m = 20;
        for (KindleDroneDetectorPro.AP a : aps) if (a.dist > m) m = a.dist;
        return m;
    }

    static final double D0 = 15.0; // log-scale knee (metres)

    static int distToPixels(double distM, double maxDist) {
        double norm = Math.log1p(distM / D0) / Math.log1p(maxDist / D0);
        return (int)(RADAR_R * 0.90 * norm);
    }

    /** Stable pseudo-angle for a MAC (radians, 0..2π). */
    static double stableAngle(String mac) {
        int h = mac.hashCode();
        // Use upper and lower halves for finer spread
        long u = (h & 0xFFFFFFFFL);
        return (u % 3600) / 3600.0 * 2 * Math.PI;
    }

    static String shortLabel(KindleDroneDetectorPro.AP a) {
        String s = a.ssid.equals("[HIDDEN]") ? a.mac.substring(9) : a.ssid;
        if (s.length() > 10) s = s.substring(0, 10);
        return s + " " + (int) a.dist + "m";
    }

    static void updateTrails(List<KindleDroneDetectorPro.AP> aps, double scale) {
        for (KindleDroneDetectorPro.AP a : aps) {
            if (a.ghost || a.dist <= 0 || a.mac == null || a.mac.isEmpty()) continue;
            int pr = Math.min(distToPixels(a.dist, scale), RADAR_R - 6);
            ArrayDeque<Integer> q = TRAILS.get(a.mac);
            if (q == null) {
                q = new ArrayDeque<>();
                TRAILS.put(a.mac, q);
            }
            q.addLast(pr);
            while (q.size() > TRAIL_KEEP) q.removeFirst();
        }
    }

    static void drawTrail(byte[] px, String mac, double angle) {
        ArrayDeque<Integer> q = TRAILS.get(mac);
        if (q == null || q.size() < 2) return;

        int idx = 0;
        int n = q.size();
        for (int pr : q) {
            // Skip newest point (current dot will be drawn over it).
            if (idx == n - 1) break;
            int ax = RADAR_CX + (int) (pr * Math.cos(angle));
            int ay = RADAR_CY + (int) (pr * Math.sin(angle));
            int shade = (idx <= 0) ? AP_TRAIL_OLD : (idx == n - 2 ? AP_TRAIL_NEW : AP_TRAIL_MID);
            int r = (idx == n - 2) ? 3 : 2;
            fillCircle(px, ax, ay, r, (byte) shade);
            idx++;
        }
    }

    // =========================================================================
    // Pixel drawing primitives  (all clipped to [0,W) × [0,H))
    // =========================================================================

    static void fill(byte[] px, byte v) { java.util.Arrays.fill(px, v); }

    static void set(byte[] px, int x, int y, byte v) {
        if (x >= 0 && x < W && y >= 0 && y < H) px[y * W + x] = v;
    }

    static void hline(byte[] px, int x0, int x1, int y, byte v) {
        if (y < 0 || y >= H) return;
        int a = Math.max(0, Math.min(x0, x1));
        int b = Math.min(W - 1, Math.max(x0, x1));
        for (int x = a; x <= b; x++) px[y * W + x] = v;
    }

    static void vline(byte[] px, int x, int y0, int y1, byte v) {
        if (x < 0 || x >= W) return;
        int a = Math.max(0, Math.min(y0, y1));
        int b = Math.min(H - 1, Math.max(y0, y1));
        for (int y = a; y <= b; y++) px[y * W + x] = v;
    }

    static void fillRect(byte[] px, int x, int y, int w, int h, byte v) {
        for (int dy = 0; dy < h; dy++)
            for (int dx = 0; dx < w; dx++)
                set(px, x + dx, y + dy, v);
    }

    /** Midpoint circle — outline only. */
    static void drawCircle(byte[] px, int cx, int cy, int r, byte v) {
        int x = r, y = 0, err = 0;
        while (x >= y) {
            set(px, cx+x, cy+y, v); set(px, cx+y, cy+x, v);
            set(px, cx-y, cy+x, v); set(px, cx-x, cy+y, v);
            set(px, cx-x, cy-y, v); set(px, cx-y, cy-x, v);
            set(px, cx+y, cy-x, v); set(px, cx+x, cy-y, v);
            y++;
            if (err <= 0) { err += 2*y + 1; }
            else          { x--; err += 2*(y-x)+1; }
        }
    }

    /** Filled circle using horizontal scanlines. */
    static void fillCircle(byte[] px, int cx, int cy, int r, byte v) {
        for (int dy = -r; dy <= r; dy++) {
            int dx = (int) Math.sqrt((double)(r*r - dy*dy));
            hline(px, cx - dx, cx + dx, cy + dy, v);
        }
    }

    // =========================================================================
    // Text rendering  (5×7 pixel font, scalable)
    // =========================================================================

    static void drawString(byte[] px, int x, int y, String s, byte fg, int scale) {
        if (s == null) return;
        int cx = x;
        for (int i = 0; i < s.length(); i++) {
            int[] glyph = glyph(s.charAt(i));
            drawGlyph(px, cx, y, glyph, fg, scale);
            cx += (5 + 1) * scale; // 5 columns + 1 gap
        }
    }

    static void drawGlyph(byte[] px, int x, int y, int[] rows, byte fg, int scale) {
        for (int row = 0; row < 7; row++) {
            int bits = rows[row];
            for (int col = 0; col < 5; col++) {
                if ((bits & (1 << (4 - col))) != 0) {
                    fillRect(px, x + col * scale, y + row * scale, scale, scale, fg);
                }
            }
        }
    }

    // =========================================================================
    // PNG writer (8-bit grayscale)
    // =========================================================================

    /**
     * Write the pixel buffer as an 8-bit grayscale PNG.
     */
    static void writePng(byte[] px) {
        File tmp  = new File(IMAGE_TMP);
        File dest = new File(IMAGE_FILE);

        try {
            BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_BYTE_GRAY);
            byte[] out = ((DataBufferByte) img.getRaster().getDataBuffer()).getData();
            System.arraycopy(px, 0, out, 0, px.length);
            ImageIO.write(img, "PNG", tmp);
        } catch (IOException e) {
            System.err.println("RadarRenderer writePng: " + e.getMessage());
            return;
        }

        // Atomic rename to main file
        if (!tmp.renameTo(dest)) {
            try {
                java.nio.file.Files.copy(tmp.toPath(), dest.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                tmp.delete();
            } catch (IOException e) {
                System.err.println("RadarRenderer rename: " + e.getMessage());
            }
        }
        // Copy into the ring-buffer slot for animation playback
        File slot = new File(FRAME_FILES[nextFrame]);
        try {
            java.nio.file.Files.copy(dest.toPath(), slot.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            System.err.println("RadarRenderer frame copy: " + e.getMessage());
        }
        nextFrame = (nextFrame + 1) % FRAME_COUNT;
    }

    // =========================================================================
    // 5×7 pixel font  (ASCII 32..90)
    // Each entry is int[7]; row bit layout: bit4=col0 .. bit0=col4
    // =========================================================================

    static int[] glyph(char c) {
        int idx = Character.toUpperCase(c) - 32;
        if (idx < 0 || idx >= FONT.length) idx = 0; // unknown → space
        return FONT[idx];
    }

    @SuppressWarnings("checkstyle:MethodLength")
    static int[][] buildFont() {
        // Indices 0-58 = ASCII 32-90
        int[][] f = new int[59][];
        for (int i = 0; i < f.length; i++) f[i] = new int[]{0,0,0,0,0,0,0}; // default blank

        // 0 = space (32)
        f[0]  = new int[]{0b00000,0b00000,0b00000,0b00000,0b00000,0b00000,0b00000};
        // ! = 33
        f[1]  = new int[]{0b00100,0b00100,0b00100,0b00100,0b00000,0b00100,0b00000};
        // # = 35 (idx 3)
        f[3]  = new int[]{0b01010,0b11111,0b01010,0b01010,0b11111,0b01010,0b00000};
        // * = 42 (idx 10)
        f[10] = new int[]{0b00000,0b10101,0b01110,0b11111,0b01110,0b10101,0b00000};
        // + = 43 (idx 11)
        f[11] = new int[]{0b00000,0b00100,0b00100,0b11111,0b00100,0b00100,0b00000};
        // , = 44 (idx 12)
        f[12] = new int[]{0b00000,0b00000,0b00000,0b00110,0b00100,0b01000,0b00000};
        // - = 45 (idx 13)
        f[13] = new int[]{0b00000,0b00000,0b00000,0b11111,0b00000,0b00000,0b00000};
        // . = 46 (idx 14)
        f[14] = new int[]{0b00000,0b00000,0b00000,0b00000,0b00000,0b00110,0b00000};
        // / = 47 (idx 15)
        f[15] = new int[]{0b00001,0b00010,0b00100,0b01000,0b10000,0b00000,0b00000};
        // 0 = 48 (idx 16)
        f[16] = new int[]{0b01110,0b10001,0b10011,0b10101,0b11001,0b10001,0b01110};
        // 1 = 49 (idx 17)
        f[17] = new int[]{0b00100,0b01100,0b00100,0b00100,0b00100,0b00100,0b01110};
        // 2 = 50 (idx 18)
        f[18] = new int[]{0b01110,0b10001,0b00001,0b00110,0b01000,0b10000,0b11111};
        // 3 = 51 (idx 19)
        f[19] = new int[]{0b11111,0b00010,0b00100,0b00010,0b00001,0b10001,0b01110};
        // 4 = 52 (idx 20)
        f[20] = new int[]{0b00010,0b00110,0b01010,0b10010,0b11111,0b00010,0b00010};
        // 5 = 53 (idx 21)
        f[21] = new int[]{0b11111,0b10000,0b11110,0b00001,0b00001,0b10001,0b01110};
        // 6 = 54 (idx 22)
        f[22] = new int[]{0b00110,0b01000,0b10000,0b11110,0b10001,0b10001,0b01110};
        // 7 = 55 (idx 23)
        f[23] = new int[]{0b11111,0b00001,0b00010,0b00100,0b01000,0b01000,0b01000};
        // 8 = 56 (idx 24)
        f[24] = new int[]{0b01110,0b10001,0b10001,0b01110,0b10001,0b10001,0b01110};
        // 9 = 57 (idx 25)
        f[25] = new int[]{0b01110,0b10001,0b10001,0b01111,0b00001,0b00010,0b01100};
        // : = 58 (idx 26)
        f[26] = new int[]{0b00000,0b00110,0b00110,0b00000,0b00110,0b00110,0b00000};
        // A = 65 (idx 33)
        f[33] = new int[]{0b01110,0b10001,0b10001,0b11111,0b10001,0b10001,0b10001};
        // B = 66 (idx 34)
        f[34] = new int[]{0b11110,0b10001,0b10001,0b11110,0b10001,0b10001,0b11110};
        // C = 67 (idx 35)
        f[35] = new int[]{0b01110,0b10001,0b10000,0b10000,0b10000,0b10001,0b01110};
        // D = 68 (idx 36)
        f[36] = new int[]{0b11110,0b10001,0b10001,0b10001,0b10001,0b10001,0b11110};
        // E = 69 (idx 37)
        f[37] = new int[]{0b11111,0b10000,0b10000,0b11110,0b10000,0b10000,0b11111};
        // F = 70 (idx 38)
        f[38] = new int[]{0b11111,0b10000,0b10000,0b11110,0b10000,0b10000,0b10000};
        // G = 71 (idx 39)
        f[39] = new int[]{0b01110,0b10001,0b10000,0b10111,0b10001,0b10001,0b01111};
        // H = 72 (idx 40)
        f[40] = new int[]{0b10001,0b10001,0b10001,0b11111,0b10001,0b10001,0b10001};
        // I = 73 (idx 41)
        f[41] = new int[]{0b01110,0b00100,0b00100,0b00100,0b00100,0b00100,0b01110};
        // J = 74 (idx 42)
        f[42] = new int[]{0b00111,0b00010,0b00010,0b00010,0b10010,0b10010,0b01100};
        // K = 75 (idx 43)
        f[43] = new int[]{0b10001,0b10010,0b10100,0b11000,0b10100,0b10010,0b10001};
        // L = 76 (idx 44)
        f[44] = new int[]{0b10000,0b10000,0b10000,0b10000,0b10000,0b10000,0b11111};
        // M = 77 (idx 45)
        f[45] = new int[]{0b10001,0b11011,0b10101,0b10001,0b10001,0b10001,0b10001};
        // N = 78 (idx 46)
        f[46] = new int[]{0b10001,0b11001,0b10101,0b10011,0b10001,0b10001,0b10001};
        // O = 79 (idx 47)
        f[47] = new int[]{0b01110,0b10001,0b10001,0b10001,0b10001,0b10001,0b01110};
        // P = 80 (idx 48)
        f[48] = new int[]{0b11110,0b10001,0b10001,0b11110,0b10000,0b10000,0b10000};
        // Q = 81 (idx 49)
        f[49] = new int[]{0b01110,0b10001,0b10001,0b10001,0b10101,0b10010,0b01101};
        // R = 82 (idx 50)
        f[50] = new int[]{0b11110,0b10001,0b10001,0b11110,0b10100,0b10010,0b10001};
        // S = 83 (idx 51)
        f[51] = new int[]{0b01111,0b10000,0b10000,0b01110,0b00001,0b00001,0b11110};
        // T = 84 (idx 52)
        f[52] = new int[]{0b11111,0b00100,0b00100,0b00100,0b00100,0b00100,0b00100};
        // U = 85 (idx 53)
        f[53] = new int[]{0b10001,0b10001,0b10001,0b10001,0b10001,0b10001,0b01110};
        // V = 86 (idx 54)
        f[54] = new int[]{0b10001,0b10001,0b10001,0b10001,0b10001,0b01010,0b00100};
        // W = 87 (idx 55)
        f[55] = new int[]{0b10001,0b10001,0b10001,0b10101,0b10101,0b11011,0b10001};
        // X = 88 (idx 56)
        f[56] = new int[]{0b10001,0b10001,0b01010,0b00100,0b01010,0b10001,0b10001};
        // Y = 89 (idx 57)
        f[57] = new int[]{0b10001,0b10001,0b01010,0b00100,0b00100,0b00100,0b00100};
        // Z = 90 (idx 58)
        f[58] = new int[]{0b11111,0b00001,0b00010,0b00100,0b01000,0b10000,0b11111};

        return f;
    }
}
