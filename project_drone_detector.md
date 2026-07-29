---
name: project-drone-detector
description: "Kindle 4 WiFi drone detector — architecture, key bugs fixed, and hardware notes"
metadata: 
  node_type: memory
  type: project
  originSessionId: 4c09a244-e535-4774-9521-4e863fd678d0
  modified: 2026-07-29T08:23:06.746Z
---

# Kindle Drone Detector Pro — project summary

Spring/Java app running on Kindle 4 (e-ink, 50×40 ASCII via `eips`).
Scans WiFi every 5s, estimates distance via log-distance path loss (RSSI → meters),
scores APs by threat flags, shows weather/radar/history pages on e-ink display.

## Key files
- `src/main/java/com/yep/kindle/dron/KindleDroneDetectorPro.java` — main logic
- `src/main/java/com/yep/kindle/dron/NetCsvStore.java` — CSV persistence (14 columns)
- `src/main/java/com/yep/kindle/dron/MovementMetrics.java` — sparkline, trendArrow, distance thresholds
- `src/main/java/com/yep/kindle/dron/WifiUtils.java` — RSSI→distance formula
- `drone_nets.csv` — persisted known networks

## Distance / signal model
- Formula: `dist = 10^((-30 - rssi) / 27)` (ref=-30 dBm @ 1m, n=2.7)
- EMA smoothing α=0.25 (τ≈4 scans = 20s); warm-up guard blocks consecutiveApproach for first 3 scans
- Fast-path surge: raw RSSI jumps >12 dBm vs smoothed → SRG flag (+20 threat), logged immediately
- Consecutive approach: 3+ EMA-distance decreases in a row → APR flag (+20 threat)

## Bugs fixed (this + prior sessions)
1. `peakSignal` initialized to `int` default 0 — RSSI always negative so check never fired. Fixed: `int peakSignal = -999`.
2. `NetCsvStore.load()`: old CSVs stored 0 for peakSignal — now treated as -999 on load.
3. `maxConsecutiveApproach` inflated for static devices: EMA warm-up drift falsely incremented counter. Fixed: `emaWarmup` field, approach tracking blocked until `emaWarmup >= 3`.
4. Radar shown for known static neighbours: `hasInterestingActivity` and `buildRadarAps` now skip baseline devices with `threat==0`. Empty radar list → no PNG written, no `eips -g`, radar page not shown.

## CSV columns (14 total)
`mac, ssid, firstSeen, lastSeen, count, peakSignal, oui, keyword, obsTime, distHist, lastCh, surgeCnt, maxApp, lastFlags`

## Threat flags
NEW(+25) STR(+20) MOV(+20) HOP(+20) HID(+15) TRN(+15) RMAC(+10) OUI(+50) SSID(+40) SRG(+20) APR(+20)

## Radar rules
- Rendered only when `buildRadarAps` returns non-empty list
- Baseline + threat=0 devices excluded from radar entirely
- Snapshot PNG saved on `newDetection` events; keeps last 3 timestamped copies

## Kindle 4 LED (hardware)
LED nodes: `/sys/class/leds/pmic_ledsg` (green), `pmic_ledsr` (red), `pmic_ledsb` (blue).
`lipc-set-prop com.lab126.powerd led` does NOT work (no such property).
Blink: `echo timer > /sys/class/leds/pmic_ledsg/trigger` then set delay_on/delay_off.
Manual blink loop works if timer trigger unavailable.
Root fs is read-only by default — run `mntroot rw` before writing to rootfs.

## Kindle 4 I2C sensors (bus 1)
| Device | Chip | Status | Notes |
|---|---|---|---|
| 1-0006 | summit_smb347 | no driver sysfs | Battery charger IC |
| 1-001a | wm8962 | no driver sysfs | Audio codec — not useful |
| 1-0035 | maxim_al32 | no driver bound | Ambient light sensor — driver missing, raw I2C needs tools |
| 1-0048 | papyrus | driver bound | eink PMIC — `papyrus_temperature` (°C int) and `papyrus_vcom_voltage` |
| 1-0055 | Yoshi_Battery | driver bound | Battery IC — no sysfs attrs exposed directly |

**Working sensor reads:**
- Room temperature: `cat /sys/bus/i2c/devices/1-0048/papyrus_temperature` → integer °C
- Battery percentage: `lipc-get-prop -i com.lab126.powerd battLevel` → integer 0–100
- Battery temperature: `cat /sys/devices/system/yoshi_battery/yoshi_battery0/battery_temperature` → raw value (units unclear, observed 80)
- Ambient light: `/sys/devices/platform/pmic_light.1/lit` exists but returns empty — not usable
- `/sys/class/thermal/` does not exist on Kindle 4

## Home Temperature page (PAGE_TEMP = slot 3)
Added `KindleHomeTemp.java` in drone-app. Reads papyrus_temperature, keeps 24-reading in-memory
history (ArrayDeque), renders 600×800 PNG with: header, huge °C number, timestamp, 0–50°C
progress bar, session min/max, sparkline bar chart, footer citing sensor path.
Page slot: Weather(0) → Moon(1) → Space(2) → **HomeTemp(3)** → Radar(4) → repeat.
PNG path: `/mnt/us/hometemp.png`. Refreshed every PAGE_HOLD_MS (5 min) same as other info pages.
