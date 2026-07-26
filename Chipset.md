# Atheros AR6003 Chipset — Capabilities & Firmware Investigation

Kindle 4 Wi-Fi chipset: **Atheros AR6003 hw2.1.1**, firmware **3.1.87.30**  
Driver: `ar6003.ko` (proprietary, not `ath6kl`)  
Interface: SDIO bus

---

## Architecture

The AR6003 driver stack is split across two domains:

| Layer | Location | Role |
|---|---|---|
| **Target firmware** | On-chip network processor | Real-time MAC/PHY operations |
| **WMI** (Wireless Module Interface) | Host ↔ chip | Control messages via proprietary opcodes |
| **HTC** (Host/Target Communication) | Host ↔ chip | Transport, flow control, memory addressing |

Configuration happens entirely through WMI commands — standard Linux wireless extensions (`iwpriv`) are not supported.

### Power design

The AR6003 was designed for mobile handsets and has aggressive power-saving mechanics:

- Requires an external 32 kHz sleep clock alongside the primary 40 MHz reference clock
- SDIO bus power states can be scaled down or cut completely when idle
- The Kindle power management daemon **resets the radio to "rec" mode** periodically — `--power maxperf` must be reapplied every ~2.5 minutes

---

## wmiconfig Command Reference

Commands run as: `wmiconfig -i wlan0 <command>`

### Useful commands

| Command | Effect |
|---|---|
| `--power maxperf` | Disable power saving; fastest scanning |
| `--scan --pas=200 --minact=30 --maxact=150` | 200 ms passive dwell per channel |
| `--scanctrlflags 1 1 1 1 1 1` | Enable BSS reporting, active scan, auto scan |
| `--scanprobedssid <SSID>` | Directed probe — forces hidden devices to respond |
| `--startscan --scanlist <ch>` | Targeted single-channel scan (replaces full cache) |
| `--getTargetStats` | Read CRC errors, noise floor, RSSI, SNR |
| `--getTargetStats --clearStats` | Read stats then reset counters for delta measurement |
| `--wlan query` | Confirm WLAN state is enabled |
| `--detecterror` | Trigger health monitoring |
| `--getheartbeat` | Read health monitoring heartbeat |

### Key fields from `--getTargetStats`

| Field | Description |
|---|---|
| `rx_crcerr` | CRC error count — primary RF interference indicator |
| `noise_floor_calibation` | Baseline noise floor (typically −96 dBm) |
| `cs_rssi` | Current RSSI to associated AP |
| `cs_snr` | Current signal-to-noise ratio |
| `rx_errors` | Total RX error count |

### Dead-end commands

| Command | Result |
|---|---|
| `--getwmode` | "Operation not supported" |
| `--getcountry` | "Operation not supported" |
| `--getsta` | "Operation not supported" |
| `--getdbglogs` | Empty — firmware doesn't generate debug events |
| `--setdbglogconfig` | Accepted silently, no visible effect |
| `--getRSSI` | Not recognized by this firmware build |
| `--getroamtable` | Returns empty |
| `--sendframe` (0 IE length) | Syntax error |
| `iwpriv wlan0` | "no private ioctls" |

---

## Firmware Investigation Results

### Power mode resets

```
wmiconfig --getpower → "rec"   ← NOT maxperf
```

`--power maxperf` from init doesn't persist. The Kindle power daemon resets it.
**Fix:** re-apply `--power maxperf` every 30 scan loops (~2.5 min).

### CRC errors are scan-induced

```
# After clearStats + 10 s idle (no scanning):
rx_crcerr = 0   ← zero when not scanning
```

The 30–100 CRC errors visible per scan interval are caused by our own radio switching channels.
A real external RF source would produce CRC errors **even during idle**.

**Detection method:** pause scanning briefly → read CRC during idle → non-zero = external RF activity.

### /proc/net/wireless — fast live monitor

```
wlan0: 0001   42   203   160   0   0   0   0   0   0
              │     │     │
              │     │     └── noise: 160 − 256 = −96 dBm
              │     └──────── level: 203 − 256 = −53 dBm
              └────────────── link quality: 42
```

Much faster than `wmiconfig --getTargetStats` — a simple file read vs. spawning a process.
Safe to poll every scan loop.

### Unknown WMI event in dmesg

```
wmi_control_rx() : Unknown id 0x101e
```

The firmware sends an event that the driver doesn't recognize. Suggests hidden capabilities
requiring driver modification to access.

### Debug logs empty

`--getdbglogs` returns nothing even after enabling with `--setdbglogconfig`.
This firmware build doesn't generate debug events for normal operation.

---

## processDot11Hdr — Raw 802.11 Frames

`processDot11Hdr` is a kernel module load-time parameter for `ar6003.ko` that passes raw
802.11 frame headers to the host. If it worked, it would expose probe requests from drones
scanning for their controllers.

### Test result

```bash
echo 1 > /sys/module/ar6003/parameters/processDot11Hdr
# → WiFi drops instantly, SSH session dies
```

The parameter is **writable at runtime, but enabling it immediately kills the network stack**.
The driver switches to passing raw 802.11 frames instead of Ethernet frames, which disconnects
all active sessions.

| Action | Outcome |
|---|---|
| Write `1` to the parameter | WiFi drops instantly |
| Recovery | Reboot Kindle (hold power 20 s) — parameter resets to 0 on boot |
| Module reload approach | Requires serial console; SSH dies before reconnect |

**Verdict:** unusable while staying online. Would require a completely different architecture
(offline capture → reboot → analyze).

### Enabling via module reload (risky, reference only)

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

| Capability | Status |
|---|---|
| Passive AP scan (iwlist) | Working |
| 200 ms dwell + BSS reporting | Working |
| Directed SSID probing | Working |
| CRC error monitoring | Working |
| /proc/net/wireless polling | Working |
| Idle CRC (external RF detection) | Working |
| Monitor mode | Not available — AR6003 hardware limitation |
| Raw frame capture (processDot11Hdr) | Kills WiFi instantly — dead end |
| `iwpriv` private ioctls | Not supported |
| `--getRSSI` | Not recognized by this firmware |
| `--dumpchipmem` | Crashes WiFi — never use in production |
