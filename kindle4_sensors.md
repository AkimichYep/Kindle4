---
name: kindle4-sensors
description: "Kindle 4 hardware sensors — what exists, how to read each, what returns empty"
metadata: 
  node_type: memory
  type: reference
  originSessionId: 9c4d2127-2a33-409e-9f8b-2a833b7a7dd9
  modified: 2026-07-29T08:26:45.819Z
---

# Kindle 4 Hardware Sensors

## I2C bus 1 — device map

| Address | Chip | Driver bound | Description |
|---|---|---|---|
| 1-0006 | summit_smb347 | no | Battery charger IC |
| 1-001a | wm8962 | no | Audio codec — no sensor value |
| 1-0035 | maxim_al32 | no | Ambient light sensor — no kernel driver, unreadable |
| 1-0048 | papyrus | yes | eink PMIC — temperature + VCOM voltage |
| 1-0055 | Yoshi_Battery | yes | Battery fuel gauge |

---

## Working sensors

### Room temperature (Papyrus PMIC)
- **Path:** `/sys/bus/i2c/devices/1-0048/papyrus_temperature`
- **Also at:** `/sys/devices/virtual/i2c-adapter/i2c-1/1-0048/papyrus_temperature`
- **Unit:** integer °C
- **Example:** `27`
- **Notes:** Sensor is inside the eink display PMIC. Reflects ambient room temperature well since the Kindle runs cool. Used by `KindleHomeTemp.java`.

### Battery percentage
- **Path:** LIPC — `lipc-get-prop -i com.lab126.powerd battLevel`
- **Unit:** integer 0–100 (percent)
- **Example:** `51`

### Battery temperature
- **Path:** `/sys/devices/system/yoshi_battery/yoshi_battery0/battery_temperature`
- **Unit:** unknown — observed `80` at ~27°C room temp. Possibly tenths of °C (×10), proprietary scale, or raw ADC. Needs calibration before use.
- **Also present:** `battery_temp_thresholds`, `battery_temp_errthresh` in same directory

### VCOM voltage (eink panel)
- **Path:** `/sys/bus/i2c/devices/1-0048/papyrus_vcom_voltage`
- **Unit:** mV (negative, panel calibration voltage)
- **Notes:** Not an environmental sensor, but voltage is temperature-compensated by papyrus firmware

---

## Dead ends

### Ambient light (maxim_al32 / pmic_light)
- I2C device `1-0035` is registered but no kernel driver is loaded or loadable (`find /lib/modules` returns nothing for al32/maxim_light)
- Platform device `/sys/devices/platform/pmic_light.1` exists with a `lit` attribute — but `cat lit` returns empty
- No LIPC property found
- No input event device for ALS
- **Verdict:** Unreadable without custom native code or cross-compiled i2c-tools

### Thermal zones
- `/sys/class/thermal/` does not exist on Kindle 4 kernel

### Power supply class
- `/sys/class/power_supply/` is empty — Yoshi driver does not register there

---

## Discovery commands (for future reference)

```sh
# List all I2C devices and their names
for dev in /sys/bus/i2c/devices/1-*/; do echo "$dev: $(cat $dev/name 2>/dev/null)"; done

# Find all temperature/light/sensor sysfs entries
find /sys -name "*temp*" -o -name "*lux*" -o -name "*accel*" -o -name "*light*" 2>/dev/null | grep -v "power\|uevent"

# LIPC battery data
lipc-get-prop -i com.lab126.powerd battLevel
```
