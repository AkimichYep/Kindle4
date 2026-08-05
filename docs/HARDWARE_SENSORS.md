# Kindle 4 Hardware Sensors & Interface

## Overview

The Kindle 4 includes several I2C sensors accessible via the `/sys` filesystem. This guide documents which sensors are readable, which are dead ends, and how to integrate them into applications.

---

## I2C Bus 1 — Device Map

| Address | Chip | Driver | Type | Status |
|---|---|---|---|---|
| 1-0006 | summit_smb347 | None | Battery charger IC | ❌ Not responding |
| 1-001a | wm8962 | wm8962 | Wolfson audio codec | ❌ No sensor values |
| 1-0035 | maxim_al32 | (none) | Ambient light sensor | ❌ No kernel driver |
| 1-0048 | papyrus | papyrus | E-ink PMIC | ✅ **Working** |
| 1-0055 | Yoshi_Battery | yoshi_battery | Battery fuel gauge | ✅ **Working** |

**Note:** I2C bus 0 has no devices. Bus 1 is the only available I2C interface.

---

## Working Sensors

### Room Temperature (Papyrus PMIC)

**Purpose:** Monitor ambient temperature (primarily for e-ink panel stability)

**Path:**
```
/sys/bus/i2c/devices/1-0048/papyrus_temperature
```

**Alternative paths:**
```
/sys/devices/virtual/i2c-adapter/i2c-1/1-0048/papyrus_temperature
/sys/devices/platform/imxsdi.2/i2c-0/0-0048/papyrus_temperature
```

**Read:**
```bash
cat /sys/bus/i2c/devices/1-0048/papyrus_temperature
# Output: 27
```

**Unit:** Integer °C (celsius)

**Range:** Observed 0–50°C

**Notes:**
- Sensor is integrated into the e-ink PMIC package
- Temperature-compensates VCOM (panel driving voltage)
- Well-calibrated; reflects actual room temperature
- Used in `KindleHomeTemp.java` for 24-point history graph

**Java Implementation:**

```java
import java.nio.file.Files;
import java.nio.file.Paths;

public class TemperatureSensor {
    public static int readRoomTemperature() throws Exception {
        String path = "/sys/bus/i2c/devices/1-0048/papyrus_temperature";
        String content = Files.readString(Paths.get(path)).trim();
        return Integer.parseInt(content);
    }
}
```

### Battery Capacity

**Purpose:** Display current battery level

**Method 1: LIPC (Preferred)**
```bash
lipc-get-prop -i com.lab126.powerd battLevel
# Output: 51
```

**Unit:** Integer 0–100 (percent)

**Method 2: Sysfs (Fallback)**
```bash
cat /sys/devices/system/yoshi_battery/yoshi_battery0/battery_capacity
# Output: 51
```

**Java Implementation:**

```java
public class BatterySensor {
    public static int getBatteryCapacity() throws Exception {
        // Method 1: Try LIPC first
        try {
            ProcessBuilder pb = new ProcessBuilder(
                "lipc-get-prop", "-i", "com.lab126.powerd", "battLevel"
            );
            Process proc = pb.start();
            String output = new String(proc.getInputStream().readAllBytes()).trim();
            return Integer.parseInt(output);
        } catch (Exception e) {
            // Fallback to sysfs
            String path = "/sys/devices/system/yoshi_battery/yoshi_battery0/battery_capacity";
            String content = Files.readString(Paths.get(path)).trim();
            return Integer.parseInt(content);
        }
    }
}
```

### Battery Temperature

**Purpose:** Monitor battery health (detect thermal runaway)

**Path:**
```bash
/sys/devices/system/yoshi_battery/yoshi_battery0/battery_temperature
```

**Read:**
```bash
cat /sys/devices/system/yoshi_battery/yoshi_battery0/battery_temperature
# Output: 80
```

**Unit:** **Unknown** — Observed value `80` at ~27°C room temp

**Possible interpretations:**
- Tenths of °C (×10) → 8°C (too low, contradicts room temp)
- Proprietary scale (e.g., linear 0–120 = −20°C to +60°C)
- Raw ADC value (requires calibration)

**Status:** ⚠️ **Unverified** — needs calibration against known reference before use

**Related files in same directory:**
- `battery_temp_thresholds` — Charge/discharge cut-off temperatures
- `battery_temp_errthresh` — Temperature error threshold

### E-Ink VCOM Voltage

**Purpose:** Panel calibration (not an environmental sensor)

**Path:**
```bash
/sys/bus/i2c/devices/1-0048/papyrus_vcom_voltage
```

**Read:**
```bash
cat /sys/bus/i2c/devices/1-0048/papyrus_vcom_voltage
# Output: -4500
```

**Unit:** millivolts (mV, negative)

**Notes:**
- VCOM is the panel driving voltage
- Firmware automatically temperature-compensates VCOM for stability
- Not useful for sensing ambient conditions
- Documented for completeness

---

## Dead Ends (Not Readable)

### Ambient Light Sensor (maxim_al32)

**Path:** `/sys/devices/platform/pmic_light.1/lit`

**Problem:**
```bash
cat /sys/devices/platform/pmic_light.1/lit
# Output: (empty)
```

**Why it doesn't work:**
1. I2C device `1-0035` is registered in the kernel device tree
2. No kernel driver is loaded (`find /lib/modules -name '*al32*' -o -name '*maxim_light*'` returns nothing)
3. Platform device exists but reports no values
4. No LIPC property available
5. No input event device (would expose ALS data to userspace)

**Possible solutions (not recommended for production):**
- Cross-compile `maxim_al32` or `drivers/input/light-sensor` kernel module
- Use raw I2C tools (`i2cget`, `i2cset`) with custom protocol reverse-engineering
- Use external USB light sensor via USB-to-I2C adapter

**Verdict:** Unreadable on standard Kindle 4 without kernel driver development.

### Audio Codec (wm8962)

**Path:** `/sys/bus/i2c/devices/1-001a/`

**Contents:**
```
driver -> ../../../../bus/i2c/drivers/wm8962
modalias
name
power/
subsystem -> ../../../../bus/i2c
uevent
```

**Why it's not useful:**
- Driver is loaded but exposes no sensor attributes
- Audio codec does not include temperature or environmental sensors
- No sysfs interface for codec parameters

### Thermal Zones

```bash
ls -la /sys/class/thermal/
# Output: ls: cannot access /sys/class/thermal/: No such file or directory
```

**Finding:** Kindle 4's 2.6.31 kernel predates the thermal framework (added in 2.6.38+).

**Workaround:** Read `/proc/cpuinfo` or `/sys/class/cpufreq/` for CPU performance state only.

### Power Supply Class

```bash
ls -la /sys/class/power_supply/
# Output: (empty)
```

**Finding:** The Yoshi battery driver does not register with the standard Linux power supply subsystem.

**Workaround:** Use LIPC and direct sysfs reads under `/sys/devices/system/yoshi_battery/`.

---

## Battery Charging & Suspend Current

### Current Charging Status

**Path:**
```bash
/sys/devices/system/yoshi_battery/yoshi_battery0/battery_current
```

**Read:**
```bash
cat /sys/devices/system/yoshi_battery/yoshi_battery0/battery_current
# Output: 150  (during charging)
# Output: -50  (discharging, negative = leaving battery)
# Output: 0    (at 100% with USB connected)
```

**Unit:** milliamps (mA)

**Observations:**
- **0–50%:** +190 mA (bulk charging)
- **50–85%:** +100 mA (absorption phase)
- **85–100%:** +10–30 mA (trickle)
- **At 100%:** 0 mA (no charging)

### Power Bank Keepalive (battery_suspend_current)

**Purpose:** Prevent power bank auto-shutoff at 100% battery

**Problem:** When Kindle battery reaches 100%, charging current drops to ~0, making the total USB draw too low for power banks (which auto-shutoff below 50–100 mA).

**Solution:** Artificially limit battery charge to ≤85% to maintain constant charging current.

**Path:**
```bash
/sys/devices/system/yoshi_battery/yoshi_battery0/battery_suspend_current
```

**Set charge limit:**
```bash
# Write a large suspend current to limit charging
echo 500 > /sys/devices/system/yoshi_battery/yoshi_battery0/battery_suspend_current
# Battery will stop charging when it reaches ~85%
```

**Reset to normal:**
```bash
echo 0 > /sys/devices/system/yoshi_battery/yoshi_battery0/battery_suspend_current
```

**Status:** ⚠️ **Unverified assumption** — interpreted as "suspend charging when current exceeds this limit," but may be a fuel gauge threshold.

**Tested:** In prior session at 36%–100% — power bank remained on (command appears effective).

**Java Implementation:**

```java
public class PowerBankKeepalive {
    static final String SUSPEND_CURRENT_PATH = 
        "/sys/devices/system/yoshi_battery/yoshi_battery0/battery_suspend_current";
    static final String BATTERY_CAPACITY_PATH = 
        "/sys/devices/system/yoshi_battery/yoshi_battery0/battery_capacity";

    public static void limitChargeTo85Percent() throws Exception {
        int capacity = Integer.parseInt(
            Files.readString(Paths.get(BATTERY_CAPACITY_PATH)).trim()
        );
        
        if (capacity >= 85) {
            // Limit charging by writing suspend current
            Files.writeString(Paths.get(SUSPEND_CURRENT_PATH), "500\n");
            System.out.println("Charge limited to 85%");
        }
    }

    public static void restoreNormalCharging() throws Exception {
        Files.writeString(Paths.get(SUSPEND_CURRENT_PATH), "0\n");
        System.out.println("Normal charging restored");
    }
}
```

---

## Discovery Commands

### List All I2C Devices

```bash
# Show all I2C device names
for dev in /sys/bus/i2c/devices/1-*/; do 
    echo "$dev: $(cat $dev/name 2>/dev/null)"; 
done

# Output:
# /sys/bus/i2c/devices/1-0006/: summit_smb347
# /sys/bus/i2c/devices/1-001a/: wm8962
# /sys/bus/i2c/devices/1-0035/: maxim_al32
# /sys/bus/i2c/devices/1-0048/: papyrus
# /sys/bus/i2c/devices/1-0055/: Yoshi_Battery
```

### Find All Sensor Attributes

```bash
# Search for temperature, light, and other sensor paths
find /sys -name "*temp*" -o -name "*lux*" -o -name "*accel*" \
    -o -name "*light*" 2>/dev/null | grep -v "power\|uevent"

# Output:
# /sys/bus/i2c/devices/1-0048/papyrus_temperature (working)
# /sys/devices/system/yoshi_battery/yoshi_battery0/battery_temperature (unknown unit)
# /sys/bus/i2c/devices/1-0048/temp_dir (internal register)
```

### Check Drivers Loaded

```bash
# List loaded I2C drivers
ls -la /sys/bus/i2c/drivers/

# Check if a specific driver (e.g., maxim_al32) is loaded
grep maxim_al32 /proc/modules
# (no output = not loaded)
```

### LIPC Properties

```bash
# List all battery properties
lipc-get-prop -i com.lab126.powerd battLevel     # Battery %
lipc-get-prop -i com.lab126.powerd battVoltage  # Voltage mV
lipc-get-prop -i com.lab126.powerd battTemp     # Temperature (unknown unit)

# List all powerd properties
lipc-list-props com.lab126.powerd
```

---

## Summary Table

| Sensor | Path | Method | Unit | Status | Use Case |
|---|---|---|---|---|---|
| Room Temp | `1-0048/papyrus_temp` | Sysfs read | °C | ✅ Working | Home temp display, historical graph |
| Battery % | LIPC property | `lipc-get-prop` | % (0–100) | ✅ Working | Battery indicator on HUD |
| Battery Temp | `yoshi_battery0/battery_temp` | Sysfs read | Unknown | ⚠️ Uncertain | Thermal safety (needs calibration) |
| Battery Current | `yoshi_battery0/battery_current` | Sysfs read | mA | ✅ Working | Charging status, power bank keepalive |
| Light Level | `pmic_light.1/lit` | Sysfs read | Unknown | ❌ Dead | Screen brightness (not available) |
| E-Ink VCOM | `1-0048/papyrus_vcom` | Sysfs read | mV | ✅ Read-only | Panel diagnostics only |
| Charge Limit | `yoshi_battery0/battery_suspend_current` | Sysfs write | mA | ⚠️ Unverified | Power bank keepalive |

---

## Production Integration Example

```java
public class KindleSensorManager {
    public static void readAllSensors() throws Exception {
        System.out.println("=== Kindle 4 Sensors ===");
        System.out.println("Room Temperature: " + 
            Files.readString(Paths.get(
                "/sys/bus/i2c/devices/1-0048/papyrus_temperature")).trim() + "°C");
        System.out.println("Battery Capacity: " + 
            Files.readString(Paths.get(
                "/sys/devices/system/yoshi_battery/yoshi_battery0/battery_capacity")).trim() + "%");
        System.out.println("Battery Current: " + 
            Files.readString(Paths.get(
                "/sys/devices/system/yoshi_battery/yoshi_battery0/battery_current")).trim() + " mA");
        System.out.println("E-Ink VCOM: " + 
            Files.readString(Paths.get(
                "/sys/bus/i2c/devices/1-0048/papyrus_vcom_voltage")).trim() + " mV");
    }

    public static void main(String[] args) throws Exception {
        readAllSensors();
    }
}
```

---

## References

- **Kindle 4 Hacking:** MobileRead wiki (https://www.mobileread.com/)
- **I2C Linux Kernel Docs:** `/Documentation/i2c/`
- **Papyrus PMIC:** E-Ink display controller (proprietary, limited docs)
- **Yoshi Battery Fuel Gauge:** Texas Instruments BQ27xxx (similar, partial reverse-engineering)


