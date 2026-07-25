# Kindle 4 Drone Detector — Project Summary

## Overview

A Wi-Fi based drone detection system running on a jailbroken Amazon Kindle 4,
using the built-in Atheros AR6003 chipset and e-ink display as a passive RF
surveillance HUD.

**Hardware:** Kindle 4 (2011), 600×800 e-ink, ARM CPU, 256MB RAM  
**Chipset:** Atheros AR6003 hw2.1.1, firmware 3.1.87.30  
**Software:** Java 8 (compact JRE), BusyBox, wmiconfig, iwlist/iwconfig  
**Display:** eips text mode — 50 columns × 40 rows

---

## What It Detects

| Detection Method | Points | Description |
|-----------------|--------|-------------|
| OUI Match | +50 | DJI, Parrot, Autel, Skydio, Yuneec, ESP32 MAC prefixes |
| SSID Keyword | +40 | "dji", "mavic", "tello", "phantom", "fpv", etc. |
| NEW + Strong | +25 | Device not in baseline, signal > -80 dBm |
| Strong Unknown | +20 | Not baselined, signal > -65 dBm |
| Moving (MOV) | +20 | Signal std deviation > 10 dB |
| Channel Hopping | +20 | MAC seen on 2+ different channels |
| Ad-Hoc Mode | +20 | Non-Master (peer-to-peer drone link) |
| Hidden SSID | +15 | No SSID broadcast, within 80m |
| Transient (TRN) | +15 | Appears/disappears, was once strong |
| Random MAC (RMAC) | +10 | Locally-administered bit set |

**Score interpretation:**
- 0: Known, stable, normal router
- 10-25: Minor curiosity (hidden AP, random MAC)
- 30-59: Suspicious — worth watching
- 60-79: Likely drone — visual alert triggered
- 80-100: Confirmed drone signature

---

## Hardware Capabilities Discovered

### AR6003 via `wmiconfig`

| Command | Use |
|---------|-----|
| `--power maxperf` | No power saving, fastest scanning |
| `--scan --pas=200 --minact=30 --maxact=150` | 200ms passive dwell per channel |
| `--scanctrlflags 1 1 1 1 1 1` | BSS reporting, active scan, auto scan |
| `--getTargetStats` | CRC errors, noise floor, RSSI, SNR |
| `--scanprobedssid <SSID>` | Directed probe for specific drone SSIDs |
| `--startscan --scanlist <ch>` | Targeted channel scan (replaces cache!) |
| `--getTargetStats --clearStats` | Reset counters for delta measurement |

### Key Stats from `--getTargetStats`
rx_crcerr → CRC error count (RF interference indicator)
noise_floor_calibation → Noise floor (baseline -96 dBm)
cs_rssi → Current RSSI to associated AP
cs_snr → Current SNR
rx_errors → Total RX errors




**CRC error rate interpretation:**
- 30-100 per 5-second interval: Normal background
- 200+: Significant RF burst (non-WiFi 2.4GHz activity)
- 500+: Heavy interference (possible drone OcuSync/FPV nearby)

### What Does NOT Work

| Capability | Status |
|-----------|--------|
| Monitor mode | Not available (AR6003 limitation) |
| Raw frame capture | Requires `processDot11Hdr=1` (untested, may break connectivity) |
| `iwpriv wlan0` | "no private ioctls" |
| `--getRSSI` | Not recognized by this firmware build |
| `--getroamtable` | Returns empty |
| `--sendframe` with 0 IE length | Syntax error |

---

## Architecture
┌───────────────────────────────────────────────────┐
│ KindleDroneDetectorPro v2.0 │
├───────────────────────────────────────────────────┤
│ │
│ INIT: │
│ wmiconfig: maxperf, 200ms dwell, BSS report │
│ Clear stats baseline │
│ │
│ MAIN LOOP (every 5 seconds): │
│ │
│ ┌─ PRE-SCAN ─────────────────────────────┐ │
│ │ Every 6th loop: --scanprobedssid │ │
│ │ After probe: reset to "any" │ │
│ └─────────────────────────────────────────┘ │
│ │
│ ┌─ SCAN ──────────────────────────────────┐ │
│ │ iwlist wlan0 scan (full 13 channels) │ │
│ │ Parse: MAC, SSID, channel, signal, enc │ │
│ └─────────────────────────────────────────┘ │
│ │
│ ┌─ TEMPORAL ENGINE ───────────────────────┐ │
│ │ Per-MAC history (40 observations) │ │
│ │ Compute: stddev, channel changes, gaps │ │
│ │ Flags: NEW, MOV, HOP, TRN │ │
│ └─────────────────────────────────────────┘ │
│ │
│ ┌─ FIRMWARE STATS (every 5th loop) ───────┐ │
│ │ wmiconfig --getTargetStats │ │
│ │ Read: rx_crcerr, noise_floor, cs_snr │ │
│ │ Compute: CRC delta (RF activity) │ │
│ └─────────────────────────────────────────┘ │
│ │
│ ┌─ SCORING ───────────────────────────────┐ │
│ │ OUI + SSID + Temporal + Signal + MAC │ │
│ │ Sort by threat score descending │ │
│ └─────────────────────────────────────────┘ │
│ │
│ ┌─ OUTPUT ────────────────────────────────┐ │
│ │ E-ink display (eips) │ │
│ │ Console log (stdout) │ │
│ │ File log (/mnt/us/drone_log.txt) │ │
│ │ Flash alert if score >= 60 │ │
│ └─────────────────────────────────────────┘ │
│ │
└───────────────────────────────────────────────────┘




---

## Detection Performance

### Tested Environment (residential, ~50 APs in range)

| Metric | Value |
|--------|-------|
| APs detected per scan | 25-50 |
| Scan cycle time | ~5 seconds |
| False MOV alerts | 0 (threshold 10.0) |
| False NEW alerts | 0 (requires signal > -80) |
| Background threat scores | 0-25 |
| Expected drone score | 60-100 |
| Score separation (benign vs drone) | 35-75 points |

### What a Real Drone Looks Like
!! 95 60:60:1F:A3:B7:22 MAVIC-3-xxxx C6 -52dB 7m DJI NEW STR




vs. background:
25 52:4F:3B:2F:A3:02 [HIDDEN] C8 -84dB 100m HID RMAC
15 2C:8A:F1:40:86:AE [HIDDEN] C6 -74dB 43m HID
0 48:A9:8A:C1:B8:10 LM2 C12 -56dB 9m




---

## File Structure
/mnt/us/
├── KindleDroneDetectorPro.jar # Main detector
├── drone_log.txt # Persistent detection log
├── java/jre/bin/java # Java 8 runtime
└── iptables_backup.conf # Firewall backup




---

## Usage

### Start Detector
```bash
cd /mnt/us
java/jre/bin/java -jar KindleDroneDetectorPro.jar
Prerequisites (one-time setup)
bash


# Open firewall (if not already done)
iptables -F && iptables -P INPUT ACCEPT

# Verify wmiconfig works
wmiconfig -i wlan0 --version
View Log
bash


cat /mnt/us/drone_log.txt
tail -20 /mnt/us/drone_log.txt
E-Ink Display Legend


DRONE 02:48:34 AP:30 THR:0 #45
CRC+52 NF:-96 SNR:33 ARMED
--------------------------------------
!~* 7m  -53dB C12 DJI_MAVIC_3     DJI NEW STR
+   43m -74dB C3  [HIDDEN]        HID RMAC
    65m -79dB C9  setka
    71m -80dB C6  Buffonn
Symbol	Meaning
!	Threat ≥ 60 (likely drone)
+	Threat ≥ 30 (suspicious)
~	Moving (signal variance > 10 dB)
*	New device (not in baseline)
ARMED	Learning complete, alerting active
LEARN	Still building baseline
CRC+N	CRC errors since last check
NF	Noise floor (dBm)
** RF BURST **	CRC > 200 (non-WiFi RF detected)
Tuning History
Parameter	v1	v2 (final)	Reason
MOV threshold	4.0	10.0	Stationary routers have sd 4-8
NEW signal requirement	any	> -80 dBm	Edge-of-range APs are noise
HID distance limit	120m	80m	Distant hidden = dual-SSID router
TRN requirement	gaps ≥ 2	gaps ≥ 2 AND peakSignal > -80	Filter edge-of-range flicker
Baseline period	15 loops	30 loops	Capture more of environment
Scan interval	4s	5s	Reduce system load
CRC alert threshold	50	200	50-100 is normal
Stats calls	2 per interval	1 per interval	Was calling getTargetStats twice
Probe frequency	every 5 loops	every 6 loops	Less firmware flooding
E-ink redraw	every loop	only on change	Reduce wear and exec calls
Limitations
2.4 GHz only — Cannot detect 5.8 GHz drone links
AP beacons only — Cannot see raw frames without monitor mode
No OcuSync decode — DJI O3/O4 uses proprietary protocol (detected via CRC spikes only)
Scan latency — 5-second cycle means fast-moving drones may be seen in only 1-2 scans
Distance estimation — Based on free-space path loss model, ±50% accuracy indoors
MAC randomization — Newer DJI firmware may use random MACs (detected via RMAC flag)
Future Enhancements (Not Implemented)
processDot11Hdr=1 — Enable raw 802.11 headers for probe request sniffing
Per-channel CRC tracking via active sweep + stats reset
Integration with external SDR via TCP for 5.8 GHz coverage
Remote ID (Broadcast) packet parsing if available
Multiple Kindle mesh for triangulation
undefined

| `processDot11Hdr=1` | Writable at runtime BUT instantly kills WiFi |
|                     | Parameter resets to 0 on reboot                |
|                     | Incompatible with active network association    |

What's New in v2.1
Feature	Implementation
Maxperf re-apply	Every 30 loops (~2.5 min), re-sends --power maxperf since Kindle resets it
/proc/net/wireless polling	Every loop — reads noise/signal/linkQuality without spawning wmiconfig
Idle CRC measurement	Every 10 loops: clears stats → 2s idle → reads CRC. Non-zero = external RF!
External RF alert	Requires 2 consecutive detections (idleCRC > 3) to flag. Decays when clear.
Display: ** EXTERNAL RF **	Shown on e-ink when idle-CRC detects non-WiFi 2.4GHz activity
Log: iCRC field	Idle CRC value logged every loop for post-analysis
Header: LQ field	Link quality from /proc/net/wireless
Flash on external RF	E-ink full refresh triggered by externalRF flag too
Detection Layers (Complete)


Layer 1: WiFi AP Scan (iwlist)
  → OUI, SSID, channel, signal, hidden, mode

Layer 2: Temporal Analysis (in-memory)
  → NEW, MOV, HOP, TRN, RMAC

Layer 3: Firmware Stats (wmiconfig --getTargetStats)
  → CRC delta during scanning (channel-switch noise)

Layer 4: Idle CRC (NEW — strongest non-WiFi detection)
  → CRC during 2s idle = external 2.4GHz RF on home channel
  → Catches: OcuSync, FPV video, drone telemetry
  → Zero false positives in clean RF environment

Layer 5: /proc/net/wireless (fast)
  → Live noise floor, signal, link quality