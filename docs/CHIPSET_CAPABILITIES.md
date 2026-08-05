# Atheros AR6003 Chipset Capabilities

**Device:** Amazon Kindle 4 (2011)  
**Chipset:** Atheros AR6003 hw2.1.1  
**Firmware:** Version 3.1.87.30  
**Driver:** `ar6003.ko` (proprietary, not ath6kl)  
**Interface:** SDIO (Secure Digital I/O) bus

---

## Architecture

The AR6003 driver stack is split across two domains:

| Layer | Location | Role |
|---|---|---|
| **Target Firmware** | On-chip network processor | Real-time MAC/PHY operations |
| **WMI** (Wireless Module Interface) | Host ↔ Chip communication | Control messages via proprietary opcodes |
| **HTC** (Host/Target Communication) | Host ↔ Chip transport | Flow control, memory addressing |

**Key:** All configuration happens through WMI commands. Standard Linux wireless extensions (`iwpriv`) are **not supported**.

---

## Power Management

The AR6003 was designed for mobile devices with aggressive power saving:

1. **Requires external 32 kHz sleep clock** (alongside primary 40 MHz reference)
2. **SDIO bus power states** can be scaled down or disabled when idle
3. **Kindle daemon issue:** The Kindle power management daemon resets the radio to "rec" mode every ~2.5 minutes
4. **Workaround:** Application re-applies `--power maxperf` every 30 scan loops (~150 seconds)

---

## WMI Command Reference

**Syntax:** `wmiconfig -i wlan0 <command>`

### Scanning Commands

| Command | Effect | Duration |
|---|---|---|
| `--power maxperf` | Disable power saving; fastest scanning | Immediate |
| `--scan --pas=200 --minact=30 --maxact=150` | 200 ms passive dwell per channel; min/max active probe timing | Per scan |
| `--scanctrlflags 1 1 1 1 1 1` | Enable BSS reporting, active scan, auto scan | Persistent |
| `--scanprobedssid <SSID>` | Directed probe to hidden network | Once per interval |
| `--startscan --scanlist <ch>` | Single-channel targeted scan | Per scan |
| `--scan` | Full multi-channel scan (all 13 channels) | 10–15 sec |

### Statistics & Diagnostics

| Command | Output | Frequency |
|---|---|---|
| `--getTargetStats` | CRC errors, noise floor, RSSI, SNR | Every 25 sec |
| `--getTargetStats --clearStats` | Read stats then reset counters (enables delta measurement) | Every 25 sec |
| `--getpower` | Current power mode ("rec" or "maxperf") | Debug only |
| `--wlan query` | WLAN state (0=disabled, 1=enabled) | Debug only |
| `--detecterror` | Trigger health monitoring | Manual debug |
| `--getheartbeat` | Read health monitoring status | Manual debug |

### Key Statistics Fields

| Field | Description | Interpretation |
|---|---|---|
| `rx_crcerr` | CRC error count | Primary RF interference indicator |
| `noise_floor_calibration` | Baseline noise floor (typically −96 dBm) | Environment noise baseline |
| `cs_rssi` | Current RSSI to associated AP | Link quality to gateway |
| `cs_snr` | Current signal-to-noise ratio (dB) | Link signal quality |
| `rx_errors` | Total RX error count (not just CRC) | General link health |

---

## Dead-End Commands (Not Supported)

| Command | Result | Reason |
|---|---|---|
| `--getwmode` | "Operation not supported" | Not implemented in this firmware |
| `--getcountry` | "Operation not supported" | Regulatory domain locked by bootloader |
| `--getsta` | "Operation not supported" | No station mode enumeration |
| `--getdbglogs` | Empty output | Firmware doesn't generate debug events |
| `--setdbglogconfig` | Silently accepted, no effect | Read-only log configuration |
| `--getRSSI` | "Unknown command" | Not in this firmware build |
| `--getroamtable` | Empty list | Roaming not used in passive scan mode |
| `--sendframe` | Syntax error | Raw frame injection not supported |
| `iwpriv wlan0` | "no private ioctls" | AR6003 uses WMI, not iwpriv |

---

## Firmware Investigation Results

### Power Mode Resets

```bash
wmiconfig -i wlan0 --getpower
# Output: "rec"  ← NOT maxperf
```

**Finding:** `--power maxperf` does not persist between daemon restarts.

**Root cause:** The Kindle power daemon resets it to "rec" mode.

**Solution:** Re-apply `--power maxperf` every 30 scan loops (~2.5 minutes).

### CRC Errors Are Scan-Induced

```bash
# After clear stats + 10 seconds idle (no scanning):
wmiconfig -i wlan0 --getTargetStats --clearStats
sleep 10
wmiconfig -i wlan0 --getTargetStats
# Output: rx_crcerr = 0   ← zero when not scanning
```

**Finding:** The 30–100 CRC errors visible per scan interval are caused by our own radio switching channels during WiFi scans.

**Real external RF source:** Would produce non-zero CRC errors **even during idle** (no scanning).

**Detection method:** Pause scanning briefly, read CRC in idle state. Non-zero = external RF activity on home channel.

### /proc/net/wireless — Fast Live Monitor

```bash
cat /proc/net/wireless
# Output: wlan0: 0001   42   203   160   0   0   0   0   0   0
#                       │     │     │
#                       │     │     └── noise: 160 − 256 = −96 dBm
#                       │     └──────── level: 203 − 256 = −53 dBm
#                       └────────────── link quality: 42
```

**Advantage:** Much faster than `wmiconfig --getTargetStats`

- Simple file read vs. process spawn
- No WMI RPC latency
- Safe to poll every scan loop
- 1–2 ms latency vs. 100+ ms for wmiconfig

### Unknown WMI Event in dmesg

```
wmi_control_rx() : Unknown id 0x101e
```

**Finding:** The firmware sends an event that the driver doesn't recognize (ID 0x101e).

**Interpretation:** Suggests hidden capabilities requiring custom driver modification to access.

### Debug Logs Empty

```bash
wmiconfig -i wlan0 --getdbglogs
# Output: (empty)
```

**Finding:** This firmware build doesn't generate debug events even after enabling with `--setdbglogconfig`.

---

## Raw 802.11 Frame Capture (processDot11Hdr)

### Purpose
`processDot11Hdr` is a kernel module parameter that, if enabled, passes raw 802.11 frame headers to the host. Would expose:
- Probe requests from drones scanning for controllers
- Ad-hoc beacons
- Unencrypted management frames

### Test Result

```bash
echo 1 > /sys/module/ar6003/parameters/processDot11Hdr
# → WiFi drops instantly, SSH session dies
```

**Outcome:** Parameter is writable at runtime, but enabling it **immediately kills the network stack**.

| Action | Outcome |
|---|---|
| Write `1` to parameter | WiFi disconnects, SSH dies |
| Recovery | Reboot Kindle (hold power 20 sec) — parameter resets to 0 |
| Module reload approach | Requires serial console; SSH dies before reconnect |

**Verdict:** **Unusable while staying online.** Would require completely different architecture (offline capture mode → reboot → analyze).

### Enabling via Module Reload (Reference Only)

This approach is documented for completeness but is **not recommended** without serial console access:

```bash
# /mnt/us/enable_raw_dot11.sh — run ONLY with serial console access

# Step 1: check current value
cat /sys/module/ar6003/parameters/processDot11Hdr

# Step 2: try runtime write (will drop WiFi)
echo 1 > /sys/module/ar6003/parameters/processDot11Hdr 2>/dev/null

# Step 3: module reload (if runtime write had no effect)
rmmod ar6003
insmod /lib/modules/2.6.31-rt11-lab126/kernel/drivers/net/wireless/ar6003/ar6003.ko processDot11Hdr=1
sleep 3
wpa_cli reconnect
```

---

## Capability Summary

| Capability | Status | Notes |
|---|---|---|
| Passive AP scan (iwlist) | ✅ Working | Full 13-channel scan |
| 200 ms dwell + BSS reporting | ✅ Working | Configurable dwell time |
| Directed SSID probing | ✅ Working | Forces hidden APs to respond |
| CRC error monitoring | ✅ Working | Primary interference detector |
| /proc/net/wireless polling | ✅ Working | Fast, no process spawn |
| Idle CRC (external RF detection) | ✅ Working | 2 sec idle measurement |
| Monitor mode | ❌ Not available | AR6003 hardware limitation |
| Raw frame capture | ❌ Kills WiFi | processDot11Hdr breaks stack |
| iwpriv private ioctls | ❌ Not supported | WMI-only configuration |
| --getRSSI command | ❌ Not recognized | Not in this firmware build |
| --dumpchipmem | ⚠️ Crashes WiFi | Never use in production |

---

## Performance Tuning

### Scan Optimization

**Default settings in KindleDroneDetectorPro:**
```bash
wmiconfig -i wlan0 --power maxperf
wmiconfig -i wlan0 --scan --pas=200 --minact=30 --maxact=150
wmiconfig -i wlan0 --scanctrlflags 1 1 1 1 1 1
```

**To reduce power consumption (less sensitive):**
```bash
# Increase dwell time to 500 ms (might miss fast movers)
wmiconfig -i wlan0 --scan --pas=500 --minact=30 --maxact=150

# Use power saving mode (detection latency +100–200 ms)
wmiconfig -i wlan0 --power rec
```

**To improve detection speed (more power consumption):**
```bash
# Reduce dwell to 100 ms (noisier, more false positives)
wmiconfig -i wlan0 --scan --pas=100 --minact=30 --maxact=150

# Re-enable maxperf more frequently
# Edit KindleDroneDetectorPro.java: MAXPERF_REAPPLY_SCANS = 15 (default 30)
```

### Statistics Reading

**Every scan (expensive):**
```bash
wmiconfig -i wlan0 --getTargetStats  # ~150 ms latency
```

**Every 5th scan (recommended):**
```bash
# Called every 25 seconds; tracked in KindleDroneDetectorPro.statsLoopCounter
```

**Hybrid approach (every scan, no spawn):**
```bash
cat /proc/net/wireless  # <2 ms latency
# Extract noise floor and link quality; read full stats every 25 sec
```

---

## Known Issues & Workarounds

| Issue | Symptom | Workaround |
|---|---|---|
| Power daemon resets maxperf | Detection latency spikes every 2.5 min | Re-apply `--power maxperf` in 30-loop cycle |
| CRC false positives during scan | Normal 30–100 CRC/interval | Measure idle CRC separately; use delta >200 as threshold |
| Module does not log events | `--setdbglogconfig` has no effect | Use dmesg + printf debugging; no WMI event log available |
| Raw frame capture unavailable | `processDot11Hdr=1` crashes WiFi | Use passive AP scanning only |
| iwpriv not supported | "no private ioctls" error | Use `wmiconfig` for all configuration |

---

## References

- **Atheros WMI Documentation:** Limited public availability; reverse-engineered from dmesg and trial
- **ath6kl driver source:** Qualcomm/Linux kernel (Kindle 4 uses older proprietary ar6003.ko)
- **AR6003 datasheet:** Not publicly available; hardware capabilities inferred from firmware behavior


