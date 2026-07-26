# Kindle Drone Detector Pro — session notes

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

## LED blink commands
```sh
# Check available LEDs
ls /sys/class/leds/
# pmic_ledsb  pmic_ledsg  pmic_ledsr

# Turn on/off
echo 1 > /sys/class/leds/pmic_ledsg/brightness
echo 0 > /sys/class/leds/pmic_ledsg/brightness

# Blink via kernel timer (if available)
echo timer > /sys/class/leds/pmic_ledsg/trigger
echo 200  > /sys/class/leds/pmic_ledsg/delay_on
echo 200  > /sys/class/leds/pmic_ledsg/delay_off
# Stop
echo none > /sys/class/leds/pmic_ledsg/trigger
echo 0    > /sys/class/leds/pmic_ledsg/brightness

# Manual blink loop fallback
for i in $(seq 1 10); do
  echo 1 > /sys/class/leds/pmic_ledsg/brightness
  sleep 0.2
  echo 0 > /sys/class/leds/pmic_ledsg/brightness
  sleep 0.2
done
```
