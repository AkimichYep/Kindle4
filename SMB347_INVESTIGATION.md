# SMB347 Charger IC Investigation

## Goal
Program the SMB347 charger IC to limit float voltage to 4.10V (~85% battery cap),
preventing the battery from reaching 100% and triggering power bank auto-shutoff.

## Kindle 4 Power Architecture
- Parallel USB + battery: when USB connected, system runs from USB rail AND charges battery
- Battery current +190mA when freshly plugged in (bulk charge)
- Battery current +86mA when near-full (trickle)
- Battery current ~21mA at 100% (near-zero trickle / system at idle)
- System idle draw ~66mA direct from USB

**Problem:** At 100% battery, charging current → ~0. Total USB draw may fall below
power bank cutoff threshold (~50-100mA). Power bank auto-shuts off.
User must manually unplug/replug power bank.

**Solution target:** Keep battery at ≤85% so charging current never drops to zero.
Power bank always sees load (66mA system + charging current).

---

## I2C Bus Map (bus 1)

| Sysfs path      | Driver           | Address | Chip              |
|-----------------|------------------|---------|-------------------|
| `1-0006`        | (none loaded)    | 0x06    | summit_smb347     |
| `1-001a`        | wm8962           | 0x1A    | Wolfson audio     |
| `1-0035`        | maxim_al32       | 0x35    | LED/light sensor  |
| `1-0048`        | papyrus          | 0x48    | e-ink controller  |
| `1-0055`        | Yoshi_Battery    | 0x55    | BQ27xxx fuel gauge|

Scan via `I2C_RDWR` confirmed responding devices on bus 1: **0x35, 0x48, 0x55 only.**
Address 0x06 (SMB347) does **not** respond. Bus 0 has no devices.

---

## SMB347 Direct I2C Access — Failed

### Attempt 1: raw write/read
`I2C_SLAVE` + `write(fd, &reg, 1)` + `read(fd, &val, 1)` → all registers read as `0x00`.

**Root cause:** Two bugs:
1. Kernel driver `summit_smb347` owns the device → need `I2C_SLAVE_FORCE`
2. Two separate transactions (no repeated-start) → SMB347 needs SMBus combined read

### Attempt 2: I2C_SMBUS ioctl
`I2C_SLAVE_FORCE` + `I2C_SMBUS` ioctl with `I2C_SMBUS_BYTE_DATA` → `Remote I/O error` (errno 121).

**Root cause:** `summit_smb347` driver module is NOT loaded (no `/sys/bus/i2c/drivers/summit_smb347/`
directory, `dmesg | grep smb` returns nothing, `find / -name '*smb347*'` finds only our binary).
The device is registered in the kernel device tree but has no driver bound. The IC is running
on its hardware-programmed defaults with no software configuration.

### Attempt 3: I2C_RDWR ioctl (no slave address needed)
Two-message combined write+read. Device at 0x06 still does **not respond** — confirmed by
full bus scan which found 0x35, 0x48, 0x55 but not 0x06.

**Conclusion:** The SMB347 hardware does not ACK any user-space I2C transaction on bus 1.
Possible reasons:
- i.MX508 I2C controller or kernel filters transactions to reserved address range 0x00–0x07
- SMB347 not physically wired to the SoC I2C bus (routed through PMIC internally)
- IC is in a power state that disables I2C

---

## Yoshi Battery Sysfs Interface

Full node list at `/sys/devices/system/yoshi_battery/yoshi_battery0/`:

```
battery_capacity               battery_cyct                   battery_id_valid
battery_current                battery_error                  battery_lmd
battery_current_diags          battery_i2c_address            battery_mAH
battery_cycl                   battery_id                     battery_overheat
battery_polling_intervals      battery_send_uevent            battery_temp_errthresh
battery_suspend_current        battery_temp_thresholds        battery_voltage_thresholds
battery_suspend_current_diags  battery_temperature            battery_voltage
battreg                        battreg_value                  resume_stats
```

### battreg / battreg_value
Register read/write proxy into the fuel gauge (BQ27xxx at 0x55), NOT the SMB347.

Evidence: register 3 read as `0x05` — does not match SMB347 float voltage default `0x23` (= 4.20V).

**WARNING:** We wrote `0x30` to register 3 during investigation. This was restored to `0x05`
before ending the session. Do not write arbitrary values here.

### battery_suspend_current
Previous session observation: writing `500` reduced `battery_current` from +190 to +86mA.
**However** — this may be coincidental (battery naturally entering trickle phase at that moment).

Current state: `battery_suspend_current = 0`, `battery_current = 21` (near-full or USB unplugged).

**Unverified** whether this node is a real charging limiter or a fuel gauge threshold parameter.

---

## Quick Reference — Force Load on Power Bank

**Situation:** Kindle on power bank, battery not yet full, power bank stays on normally.
**Problem:** At ~100% battery, charging current → ~0, total USB draw drops below power bank cutoff → auto-shutoff.
**Workaround (manual):** Unplug and replug the power bank.

**Preventive command — run before battery reaches ~85%:**

```sh
BATT=/sys/devices/system/yoshi_battery/yoshi_battery0
cat $BATT/battery_capacity    # confirm current %
cat $BATT/battery_current     # should be 150-200 mA while charging
echo 500 > $BATT/battery_suspend_current   # limit charge, keep USB load up
```

**Restore default:**

```sh
echo 0 > $BATT/battery_suspend_current
```

> **Note:** `battery_suspend_current` is unverified — may be a fuel gauge threshold, not a real charge limiter.
> Run the verification test below (at 60–70% battery) to confirm before relying on it.
> **UPDATE:** Tested at 36% → 100% — power bank stayed on. Command appears to work.
> Logic is now embedded in the app (see `KindleDroneDetectorPro.java` — PBANK keepalive block,
> `SensorReader.writeBatterySuspendCurrent()`). App throttles at ≥85%, restores at <70%.

---

## TODO: Verification Test Needed

Must be run with battery at **60–70%** and **USB actively plugged in** (baseline ~190mA):

```bash
BATT=/sys/devices/system/yoshi_battery/yoshi_battery0
cat $BATT/battery_capacity    # confirm ~60-70%
cat $BATT/battery_current     # confirm ~150-200mA baseline
echo 10000 > $BATT/battery_suspend_current
cat $BATT/battery_current     # does it drop?
echo 500 > $BATT/battery_suspend_current
cat $BATT/battery_current     # lower limit?
echo 0 > $BATT/battery_suspend_current
cat $BATT/battery_current     # restore
```

If confirmed: implement charge cycling in `KindleDroneDetectorPro` main loop —
when battery ≥ 85%, write limiting value; when < 70%, write 0 to restore.

---

## Compiled Binary

`smb347.c` in project root — cross-compile for ARMv7:
```bash
arm-linux-gnueabihf-gcc -static -marm -o smb347 smb347.c
```

Commands: `status`, `limit`, `default`, `suspend`, `resume`, `scan`, `dump [addr]`.
The binary is deployed at `/mnt/us/drone-app/smb347` but all register operations fail
(EREMOTEIO) because the SMB347 does not respond to user-space I2C.
