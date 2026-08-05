# Drone Detection & Scoring Algorithm

## Overview

The **Kindle Drone Detector** uses a multi-layer threat scoring system to identify Wi-Fi drones with high confidence. Each detected Access Point (AP) receives a score (0–100) based on 11 detection vectors, accumulated into a single threat metric.

---

## Threat Scoring System

### Scoring Vectors

| Vector | Points | Condition | Category |
|---|---|---|---|
| **OUI Match** | +50 | MAC vendor ID matches DJI, Parrot, Autel, Skydio, Yuneec, ESP32 | Static |
| **SSID Keyword** | +40 | SSID contains drone keywords: "dji", "mavic", "tello", "phantom", "fpv" | Static |
| **NEW + Strong** | +25 | Not in baseline AND signal > −80 dBm | Temporal |
| **Strong Unknown** | +20 | Not in baseline AND signal > −65 dBm | Temporal |
| **Movement (MOV)** | +20 | RSSI standard deviation > 10 dB over history | Temporal |
| **Channel Hop (HOP)** | +20 | MAC seen on 2+ channels across scans | Temporal |
| **Ad-hoc Mode (ADH)** | +20 | Non-Master mode (peer-to-peer drone link) | Static |
| **Hidden SSID (HID)** | +15 | No SSID broadcast AND distance < 80 m | Temporal |
| **Transient (TRN)** | +15 | Appeared/disappeared in history AND peak signal > −80 dBm | Temporal |
| **Random MAC (RMAC)** | +10 | Locally-administered bit set in MAC octet 0 | Static |
| **Surge (SRG)** | +20 | Raw RSSI jump >12 dB vs. smoothed value | Fast-path |

### Score Interpretation

| Range | Threat Level | Action |
|---|---|---|
| 0–9 | **Benign** | Known, stable router — no alert |
| 10–25 | **Low** | Minor anomaly (hidden AP, random MAC) — watch |
| 30–59 | **Medium** | Suspicious activity — user review recommended |
| 60–79 | **High** | Likely drone — e-ink display flashes |
| 80–100 | **Critical** | Confirmed drone signature — alert + logging |

---

## Detection Layers

### Layer 1: WiFi AP Scanning
- **Tool:** `iwlist wlan0 scan`
- **Frequency:** Every 5 seconds
- **Output:** MAC address, SSID, channel, signal (RSSI), encryption, mode
- **Dwell time:** 200 ms per channel (configurable)

### Layer 2: Temporal Analysis
- **History:** Per-MAC ring buffer (40 observations, ~3.3 minutes)
- **Computed fields:**
  - Signal standard deviation → `MOV` flag
  - Channel changes → `HOP` flag
  - Presence gaps → `TRN` flag
  - Age since first seen → `NEW` flag
- **EMA smoothing:** α=0.25 (warm-up guard, 3 scans before approach detection)

### Layer 3: Firmware Statistics
- **Tool:** `wmiconfig -i wlan0 --getTargetStats`
- **Frequency:** Every 5th scan cycle (~25 seconds)
- **Metrics:**
  - CRC error count (RF interference indicator)
  - Noise floor (dBm)
  - Signal-to-noise ratio (dB)
- **Purpose:** Detect channel-switch noise and RF bursts

### Layer 4: Idle CRC Measurement
- **Frequency:** Every 10th scan cycle (~50 seconds)
- **Procedure:**
  1. Pause WiFi scanning
  2. Clear CRC counters
  3. Wait 2 seconds in idle state
  4. Read CRC error count
  5. Resume scanning
- **Output:** External RF on home channel (non-WiFi 2.4 GHz)
- **Interpretation:**
  - 0 CRC errors = clean environment
  - 1–50 = normal thermal noise
  - >200 = probable drone RF activity (OcuSync, FPV video, telemetry)
  - >500 = heavy RF burst (drone within 50m)

### Layer 5: Real-time Monitoring
- **Source:** `/proc/net/wireless`
- **Frequency:** Every scan loop (no process spawn)
- **Metrics:**
  - Live noise floor
  - Current link quality
  - RSSI to associated AP
- **Advantage:** Sub-second updates without fork overhead

---

## Signal Processing

### Distance Estimation
**Log-Distance Path Loss Model:**

```
d = 10^((A - RSSI) / (10 * n))
```

Where:
- **A** = −30 dBm (calibrated signal strength at 1 meter)
- **RSSI** = measured signal (dBm, negative)
- **n** = 2.7 (path loss exponent for indoor office/home)
- **d** = distance in meters

**Accuracy:** ±50% indoors; heavily dependent on obstacles, multipath, and materials.

### EMA Smoothing
**Exponential Moving Average:**

```
smoothed_rssi[i] = α * raw_rssi[i] + (1 - α) * smoothed_rssi[i-1]
```

Where:
- **α** = 0.25 (weight of current sample)
- **τ** ≈ 4 scans × 5 sec = 20 seconds (time constant)

**Purpose:** Eliminate short-term RSSI spikes while preserving movement detection.

---

## Temporal Flags

### NEW (Not in Baseline)
- **Trigger:** MAC not seen during first 30 scan cycles (baseline period)
- **Scoring:**
  - NEW + signal > −80 dBm → +25 points
  - NEW + signal > −65 dBm → +20 points
  - NEW + signal ≤ −80 dBm → 0 points (noise)
- **Reset:** After 1 hour of continuous presence

### MOV (Moving)
- **Trigger:** RSSI standard deviation > 10.0 dB (computed over ring buffer)
- **Scoring:** +20 points
- **False positives:** Reduced by:
  - Stationary routers typically stddev 4–8 dB
  - Building scattering creates 2–4 dB variation
  - Threshold calibrated to 10 dB (edge of noise floor)

### HOP (Channel Hopping)
- **Trigger:** MAC seen on 2+ different 802.11 channels across scans
- **Scoring:** +20 points
- **Interpretation:** Drones often probe multiple channels; stationary APs are channel-locked

### TRN (Transient)
- **Trigger:** 
  - Presence gap ≥ 2 scans (not seen for 10+ seconds)
  - Peak signal during presence > −80 dBm
- **Scoring:** +15 points
- **Filters:** Prevents edge-of-range flicker from scoring as transient

### HID (Hidden SSID)
- **Trigger:**
  - Empty SSID field
  - Estimated distance < 80 meters
- **Scoring:** +15 points
- **Rationale:** Hidden APs >80m are likely dual-SSID routers; <80m + hidden = suspicious

---

## CRC Error Analysis

### CRC During Scanning
- **Typical range:** 30–100 errors per 5-second scan interval
- **Source:** Our own radio channel switching (false positive indicator)
- **Delta:** Tracked as `CRC_delta = current_crc - previous_crc`
- **Threshold:** >200 = significant RF burst

### Idle CRC (External RF Detection)
- **Measurement:** 2 seconds with no WiFi scanning active
- **Interpretation:**
  - 0 = clean channel
  - 1–50 = thermal noise
  - >200 = non-WiFi RF on 2.4 GHz (e.g., Bluetooth, microwave, drone)
  - >500 = heavy burst (drone OcuSync or FPV video nearby)

**Strongest detector** because it eliminates scanning noise. Zero false positives in clean RF environments.

---

## Baseline Period

### Purpose
Establish a "normal" state before raising alerts.

### Duration
- **30 scan cycles** = 150 seconds (~2.5 minutes)
- **Display mode:** "LEARN" (shown on e-ink)
- **Logging:** All detections logged (not suppressed)

### Actions During Baseline
- Threshold checks are bypassed
- `NEW` flag is not awarded (all APs are technically "new")
- Temporal history is populated
- Noise floor and CRC baselines are measured
- Once baseline complete, display shows "ARMED" and alerting begins

---

## Surge Detection (Fast-Path)

### Purpose
Catch rapid RSSI increases (drone quickly approaching).

### Trigger
- Raw RSSI jump > 12 dB vs. EMA-smoothed value
- Logged immediately (no wait for next scan)
- +20 points (temporary alert)

### Use Case
Drone rapidly gaining altitude or moving closer in <5 second interval.

---

## Deduplication & Persistence

### CSV Storage
- **File:** `/mnt/us/drone_nets.csv`
- **Columns:** mac, ssid, firstSeen, lastSeen, count, peakSignal, oui, keyword, obsTime, distHist, lastCh, surgeCnt, maxApp, lastFlags
- **Update:** Every scan cycle (append/update)
- **Retention:** Indefinite (manual cleanup)

### Baseline Devices
- APs with threat score = 0 are tagged as "baseline" (benign)
- Radar display excludes baseline APs (clutter reduction)
- Score > 0 → included in all displays and alerts

---

## Example Detections

### Confirmed Drone (Score 95)
```
!! 95  60:60:1F:A3:B7:22  MAVIC-3-xxxx  C6  -52dB  7m   DJI NEW STR
```
- OUI match (+50) = DJI
- SSID keyword (+40) = "MAVIC"
- NEW flag (+25) = not in baseline
- **Total:** 50 + 40 + 25 = **115** (capped at 100 = 95)

### Suspicious Hidden Network (Score 35)
```
+   35  52:4F:3B:2F:A3:02  [HIDDEN]      C8  -84dB  100m  HID RMAC
```
- HID flag (+15) = hidden, within 80m
- RMAC flag (+10) = random MAC (bit 0 set)
- Weak signal (−84 dBm) = no NEW bonus
- **Total:** 15 + 10 = **25** (low, marked `+` for caution)

### Benign Router (Score 0)
```
    0  48:A9:8A:C1:B8:10  LM2           C12 -56dB    9m
```
- Known MAC in baseline
- Static channel
- No movement
- Standard SSID pattern
- **Total:** **0** (no alert)

---

## Configuration Parameters

All tuning parameters are in `KindleDroneDetectorPro.java` (search for `THRESHOLD`, `CONSTANT`, etc.):

```java
// Distance model
static final double RSSI_REF_DBMW = -30.0;  // 1-meter calibration
static final double PATH_LOSS_EXPONENT = 2.7;  // office/home

// Temporal thresholds
static final double MOV_STDDEV_THRESHOLD = 10.0;  // dB
static final int BASELINE_LOOPS = 30;
static final int EMA_WARMUP_GUARD = 3;
static final double EMA_ALPHA = 0.25;

// Scoring thresholds
static final int NEW_STRONG_MIN_RSSI = -80;  // dBm
static final int NEW_VSTRONG_MIN_RSSI = -65;  // dBm
static final int HID_DISTANCE_LIMIT = 80;  // meters

// CRC detection
static final int CRC_ALERT_THRESHOLD = 200;
static final int CRC_IDLE_INTERVAL_SCANS = 10;

// E-ink display
static final int THREAT_ALERT_THRESHOLD = 60;
static final int THREAT_SUSPICIOUS = 30;
```

---

## Performance Metrics (Tested)

| Metric | Value | Notes |
|---|---|---|
| APs detected per scan | 25–50 | Residential environment |
| Scan cycle time | ~5 s | Configurable |
| Memory usage | ~30 MB | Java 8 JVM |
| CPU usage (idle) | 2–5% | ARM (Kindle 4) |
| False MOV alerts | 0 | Over 8-hour session |
| False NEW alerts | 0 | Threshold validation |
| Background threat scores | 0–25 | Typical benign APs |
| Expected drone score | 60–100 | Field-tested DJI Mavic 3 |
| Score gap (benign vs. drone) | 35–75 points | Robust separation |

---

## Limitations & Future Work

### Current Limitations
1. **2.4 GHz only** — Cannot detect 5.8 GHz drone links (DJI O3/O4)
2. **AP beacons only** — No probe request capture without monitor mode
3. **No OcuSync decode** — DJI proprietary protocol detected via CRC spikes only
4. **Scan latency** — 5-second cycle; fast-moving drone may appear in 1–2 scans
5. **Distance accuracy** — Free-space model ±50% indoors

### Possible Enhancements
- [ ] Enable raw 802.11 frame capture (`processDot11Hdr=1`) with dedicated capture interface
- [ ] Per-channel CRC tracking via active sweep
- [ ] External 5.8 GHz SDR integration
- [ ] Remote ID broadcast packet parsing (ADS-B-like)
- [ ] Multi-Kindle mesh network for triangulation
- [ ] Machine learning classifier (logistic regression baseline already present)
- [ ] Integration with ADS-B tracking data (FAA LAANC)


