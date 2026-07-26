# Kindle 4 Drone Detector — Summary

## Overview

A Wi-Fi based drone detection system running on a jailbroken Amazon Kindle 4,
using the built-in Atheros AR6003 chipset and e-ink display as a passive RF surveillance HUD.

| | |
|---|---|
| **Hardware** | Kindle 4 (2011), 600×800 e-ink, ARM CPU, 256 MB RAM |
| **Chipset** | Atheros AR6003 hw2.1.1, firmware 3.1.87.30 |
| **Software** | Java 8 (compact JRE), BusyBox, wmiconfig, iwlist/iwconfig |
| **Display** | eips text mode — 50 columns × 40 rows |

---

## Scoring System

Each detected AP is scored 0–100 across multiple factors. Scores stack.

| Signal | Points | Condition |
|---|---|---|
| OUI match | +50 | MAC prefix matches DJI, Parrot, Autel, Skydio, Yuneec, or ESP32 |
| SSID keyword | +40 | SSID contains "dji", "mavic", "tello", "phantom", "fpv", etc. |
| NEW + strong | +25 | Not in baseline, signal > −80 dBm |
| Strong unknown | +20 | Not in baseline, signal > −65 dBm |
| Moving (MOV) | +20 | Signal standard deviation > 10 dB over history |
| Channel hop (HOP) | +20 | MAC seen on 2+ channels across scans |
| Ad-hoc mode (ADH) | +20 | Non-Master mode (peer-to-peer drone link) |
| Hidden SSID (HID) | +15 | No SSID broadcast, within 80 m |
| Transient (TRN) | +15 | Appeared/disappeared, peak signal was > −80 dBm |
| Random MAC (RMAC) | +10 | Locally-administered bit set in first octet |

**Score thresholds:**

| Range | Interpretation |
|---|---|
| 0 | Known, stable router |
| 10–25 | Minor anomaly (hidden AP, random MAC) |
| 30–59 | Suspicious — worth watching |
| 60–79 | Likely drone — screen flash triggered |
| 80–100 | Confirmed drone signature |

---

## Detection Layers

```
Layer 1 — WiFi AP scan (iwlist)
  → OUI, SSID, channel, signal, hidden, mode

Layer 2 — Temporal analysis (in-memory ring buffer)
  → NEW, MOV, HOP, TRN, RMAC

Layer 3 — Firmware stats (wmiconfig --getTargetStats)
  → CRC delta during scanning (channel-switch noise indicator)

Layer 4 — Idle CRC measurement (strongest non-WiFi detection)
  → CRC errors during 2 s idle = external 2.4 GHz RF on home channel
  → Catches: OcuSync, FPV video, drone telemetry
  → Zero false positives in a clean RF environment

Layer 5 — /proc/net/wireless (fast, every loop)
  → Live noise floor, signal level, link quality
```

**CRC error rate interpretation:**

| Rate (per 5 s interval) | Meaning |
|---|---|
| 30–100 | Normal — caused by our own scanning |
| 200+ | Significant RF burst (non-WiFi 2.4 GHz) |
| 500+ | Heavy interference — likely drone OcuSync/FPV nearby |

---

## Architecture

```
┌─────────────────────────────────────────────────────┐
│              KindleDroneDetectorPro v2.1             │
├─────────────────────────────────────────────────────┤
│                                                     │
│  INIT:                                              │
│    wmiconfig: maxperf, 200 ms dwell, BSS report     │
│    Clear stats baseline                             │
│                                                     │
│  MAIN LOOP (every 5 seconds)                        │
│                                                     │
│  ┌─ PRE-SCAN ──────────────────────────────────┐   │
│  │  Every 6th loop: --scanprobedssid <SSID>    │   │
│  │  Otherwise: reset probe to "any"            │   │
│  └─────────────────────────────────────────────┘   │
│                                                     │
│  ┌─ SCAN ──────────────────────────────────────┐   │
│  │  iwlist wlan0 scan (full 13 channels)       │   │
│  │  Parse: MAC, SSID, channel, signal, enc     │   │
│  └─────────────────────────────────────────────┘   │
│                                                     │
│  ┌─ TEMPORAL ENGINE ───────────────────────────┐   │
│  │  Per-MAC ring buffer (40 observations)      │   │
│  │  Compute: stddev, channel changes, gaps     │   │
│  │  Flags: NEW, MOV, HOP, TRN                  │   │
│  └─────────────────────────────────────────────┘   │
│                                                     │
│  ┌─ FIRMWARE STATS (every 5th loop) ───────────┐   │
│  │  wmiconfig --getTargetStats                 │   │
│  │  Read: rx_crcerr, noise_floor, cs_snr       │   │
│  │  Compute: CRC delta (RF activity)           │   │
│  └─────────────────────────────────────────────┘   │
│                                                     │
│  ┌─ IDLE CRC (every 10th loop) ────────────────┐   │
│  │  Clear stats → 2 s idle → read CRC          │   │
│  │  Non-zero = external RF on home channel     │   │
│  └─────────────────────────────────────────────┘   │
│                                                     │
│  ┌─ SCORING ───────────────────────────────────┐   │
│  │  OUI + SSID + Temporal + Signal + MAC       │   │
│  │  Sort by threat score descending            │   │
│  └─────────────────────────────────────────────┘   │
│                                                     │
│  ┌─ OUTPUT ────────────────────────────────────┐   │
│  │  E-ink display (eips, diff-based redraw)    │   │
│  │  Console log (stdout)                       │   │
│  │  File log (/mnt/us/drone_log.txt)           │   │
│  │  Screen flash if score ≥ 60 or external RF  │   │
│  └─────────────────────────────────────────────┘   │
│                                                     │
└─────────────────────────────────────────────────────┘
```

---

## E-Ink Display

### Screen layout

```
DRONE 02:48:34 AP:30 THR:2 #45
CRC+52 NF:-96 SNR:33 LQ:42 ARMED
--------------------------------------
!~* 7m  -53dB C6  DJI_MAVIC_3      DJI NEW STR
+   43m -74dB C3  [HIDDEN]         HID RMAC
    65m -79dB C9  HomeNetwork
    71m -80dB C6  Buffonn
```

### Symbol reference

| Symbol / Field | Meaning |
|---|---|
| `!` | Threat ≥ 60 (likely drone) |
| `+` | Threat ≥ 30 (suspicious) |
| `~` | Moving — signal variance > 10 dB |
| `*` | New device, not in baseline |
| `ARMED` | Baseline complete, alerting active |
| `LEARN` | Still building baseline (first 30 loops) |
| `CRC+N` | CRC errors since last stats check |
| `NF` | Noise floor in dBm |
| `SNR` | Signal-to-noise ratio |
| `LQ` | Link quality from /proc/net/wireless |
| `** RF BURST **` | CRC delta > 200 (non-WiFi 2.4 GHz detected) |
| `** EXT RF **` | Idle CRC confirms external RF on home channel |

---

## Detection Performance

Tested in a residential environment with ~50 APs in range.

| Metric | Value |
|---|---|
| APs detected per scan | 25–50 |
| Scan cycle time | ~5 s |
| False MOV alerts | 0 (stddev threshold 10.0) |
| False NEW alerts | 0 (requires signal > −80 dBm) |
| Background threat scores | 0–25 |
| Expected drone score | 60–100 |
| Score gap (benign vs. drone) | 35–75 points |

### Example output

Drone (score 95):
```
!! 95  60:60:1F:A3:B7:22  MAVIC-3-xxxx  C6  -52dB  7m   DJI NEW STR
```

Background noise:
```
   25  52:4F:3B:2F:A3:02  [HIDDEN]      C8  -84dB  100m  HID RMAC
   15  2C:8A:F1:40:86:AE  [HIDDEN]      C6  -74dB   43m  HID
    0  48:A9:8A:C1:B8:10  LM2           C12 -56dB    9m
```

---

## On-Device File Structure

```
/mnt/us/
├── KindleDroneDetectorPro.jar    # main detector JAR
├── drone_log.txt                 # persistent detection log
├── java/jre/bin/java             # Java 8 runtime
└── iptables_backup.conf          # firewall backup
```

View the log:

```bash
cat /mnt/us/drone_log.txt
tail -20 /mnt/us/drone_log.txt
```

---

## Tuning History

Parameters changed between v1 and v2 to eliminate false positives.

| Parameter | v1 | v2 | Reason |
|---|---|---|---|
| MOV stddev threshold | 4.0 | 10.0 | Stationary routers have stddev 4–8 |
| NEW signal minimum | any | > −80 dBm | Edge-of-range APs are noise |
| HID distance limit | 120 m | 80 m | Distant hidden = dual-SSID router |
| TRN condition | gaps ≥ 2 | gaps ≥ 2 AND peak > −80 dBm | Filter edge-of-range flicker |
| Baseline period | 15 loops | 30 loops | Capture more of the environment |
| Scan interval | 4 s | 5 s | Reduce system load |
| CRC alert threshold | 50 | 200 | 50–100 is normal scan noise |
| Stats calls per interval | 2 | 1 | Was calling getTargetStats twice |
| Probe frequency | every 5 loops | every 6 loops | Less firmware flooding |
| E-ink redraw | every loop | on content change only | Reduce wear and exec overhead |

---

## What's New in v2.1

| Feature | Implementation |
|---|---|
| Maxperf re-apply | Every 30 loops (~2.5 min) — Kindle daemon resets it to "rec" |
| /proc/net/wireless polling | Every loop — reads noise/signal/LQ without spawning wmiconfig |
| Idle CRC measurement | Every 10 loops: clear → 2 s idle → read CRC; non-zero = external RF |
| External RF alert | Requires 3 consecutive detections; decays when clear |
| `** EXT RF **` display line | Shown on e-ink with idle CRC count and threshold |
| `iCRC` log field | Idle CRC value logged every loop for post-analysis |
| `LQ` header field | Link quality from /proc/net/wireless |
| Screen flash on external RF | Full e-ink refresh triggered by externalRF flag |

---

## Limitations

- **2.4 GHz only** — cannot detect 5.8 GHz drone links (DJI O3/O4, FPV video)
- **AP beacons only** — no raw frame capture without monitor mode
- **No OcuSync decode** — DJI O3/O4 proprietary protocol; detected only via CRC spikes
- **Scan latency** — 5 s cycle; fast-moving drone may appear in only 1–2 scans
- **Distance accuracy** — free-space path loss model, ±50% indoors
- **MAC randomization** — newer DJI firmware may use random MACs (flagged as RMAC)

---

## Future Enhancements

- `processDot11Hdr=1` — raw 802.11 headers for probe request sniffing *(kills WiFi, see Chipset.md)*
- Per-channel CRC tracking via active sweep + stats reset
- External SDR integration via TCP for 5.8 GHz coverage
- Remote ID broadcast packet parsing
- Multi-Kindle mesh for triangulation
