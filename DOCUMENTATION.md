# Documentation Index

Welcome to the **Kindle Drone Tools** documentation. Start with [README.md](README.md) for a quick overview, then consult the guides below based on your needs.

---

## Quick Navigation

| Goal | Document |
|---|---|
| **Get started quickly** | [README.md](README.md) — Features, quick start, build & run |
| **Deploy to production** | [docs/DEPLOYMENT_GUIDE.md](docs/DEPLOYMENT_GUIDE.md) — Step-by-step setup |
| **Understand detection** | [docs/DETECTION_SCORING.md](docs/DETECTION_SCORING.md) — Threat algorithm, scoring |
| **Configure WiFi scanning** | [docs/CHIPSET_CAPABILITIES.md](docs/CHIPSET_CAPABILITIES.md) — AR6003, wmiconfig commands |
| **Distance estimation** | [docs/DISTANCE_CALCULATION.md](docs/DISTANCE_CALCULATION.md) — RSSI models, Java code |
| **Hardware sensors** | [docs/HARDWARE_SENSORS.md](docs/HARDWARE_SENSORS.md) — Temperature, battery, I2C devices |
| **Control scripts** | [scripts/kindle/README.md](scripts/kindle/README.md) — Start/stop, button mapping |
| **Changelog** | [scripts/kindle/CHANGES.md](scripts/kindle/CHANGES.md) — Version history, fixes |

---

## Documentation Structure

### Root Level

- **[README.md](README.md)** (250 lines)
  - Project overview and features
  - Hardware requirements
  - Quick start (build, deploy, run)
  - Logging and monitoring
  - E-ink display guide
  - Firewall configuration
  - Configuration tuning
  - Troubleshooting
  - Project structure

### docs/ Folder

#### [docs/DETECTION_SCORING.md](docs/DETECTION_SCORING.md) (400 lines)

**Purpose:** Understand how the threat scoring algorithm works

**Contains:**
- Threat scoring system (11 vectors)
- Score interpretation thresholds
- 5-layer detection architecture
  - Layer 1: WiFi AP scanning (iwlist)
  - Layer 2: Temporal analysis (ring buffer, EMA, flags)
  - Layer 3: Firmware statistics (CRC, noise floor)
  - Layer 4: Idle CRC measurement (external RF detection)
  - Layer 5: Real-time monitoring (/proc/net/wireless)
- Signal processing (distance, EMA smoothing)
- Temporal flags (NEW, MOV, HOP, TRN, HID)
- CRC error analysis
- Baseline period behavior
- Surge detection (fast-path)
- Example detections (drone, suspicious, benign)
- Configuration parameters
- Performance metrics

---

#### [docs/CHIPSET_CAPABILITIES.md](docs/CHIPSET_CAPABILITIES.md) (300 lines)

**Purpose:** Deep dive into AR6003 chipset and wmiconfig commands

**Contains:**
- AR6003 architecture (WMI, HTC, firmware)
- Power management details
- WMI command reference (scan, stats, diagnostics)
- Known dead-end commands
- Firmware investigation results
  - Power mode resets
  - CRC errors analysis
  - /proc/net/wireless fast monitoring
  - Debug log limitations
- Raw 802.11 frame capture (processDot11Hdr) — why it doesn't work
- Capability summary table
- Performance tuning tips
- Known issues & workarounds

**Key insight:** Explains why certain features are unavailable and provides workarounds.

---

#### [docs/DISTANCE_CALCULATION.md](docs/DISTANCE_CALCULATION.md) (500 lines)

**Purpose:** RSSI-based distance estimation algorithms and implementation

**Contains:**
- Physical background of RSSI
- **Model 1:** Log-Distance Path Loss (recommended)
  - Formula, parameters, environment-specific n values
  - Java 8 implementation with Stream API
- **Model 2:** Free Space Path Loss (FSPL)
  - Theory, when to use, Java implementation
- **Model 3:** Empirical Curve Fitting
  - Android Beacon Library approach
- **Approach 4:** IEEE 802.11mc (Wi-Fi RTT)
  - Why it's not available on Kindle 4
- **Approach 5:** Trilateration
  - Solving for 2D position from 3+ APs
  - Apache Commons Math implementation
- **Approach 6:** Wi-Fi Fingerprinting
  - ML-based pattern matching, KNN classifier
- Production best practices
  - Kalman filtering for smoothing
  - Thread safety
  - Error handling
- Comparison table of all approaches
- Academic references

**Code examples:** All in production-ready Java 8 format, runnable directly.

---

#### [docs/HARDWARE_SENSORS.md](docs/HARDWARE_SENSORS.md) (380 lines)

**Purpose:** I2C sensors available on Kindle 4 and how to read them

**Contains:**
- I2C bus 1 device map (5 devices)
- **Working sensors:**
  - Room temperature (Papyrus PMIC) — ✅ Verified
  - Battery capacity (LIPC + sysfs) — ✅ Verified
  - Battery temperature — ⚠️ Unit unknown
  - E-ink VCOM voltage — ✅ Read-only
- **Dead ends:**
  - Ambient light sensor (no kernel driver)
  - Audio codec (no sensor values)
  - Thermal zones (not in kernel)
  - Power supply class (not registered)
- Battery charging & suspend current
- Power bank keepalive feature (battery_suspend_current)
- Discovery commands for exploring sensors
- Java implementations for each sensor
- Summary table
- Production integration example

**Practical:** Includes working code to integrate sensors into applications.

---

#### [docs/DEPLOYMENT_GUIDE.md](docs/DEPLOYMENT_GUIDE.md) (600 lines)

**Purpose:** Step-by-step production deployment and operations guide

**Contains:**
- **Prerequisites** (development machine, Kindle, network)
- **Building** (compile, package, verify)
- **Deployment** (automated script, manual SCP)
- **Initial setup** (one-time: filesystem, firewall, timezone)
- **Starting the app** (quick start commands)
- **Logging & monitoring**
  - Application log format and viewing
  - CSV history queries
  - System performance metrics
- **Firewall configuration**
  - Automatic setup (installation script)
  - Manual firewall rules
  - Verification & troubleshooting
- **Configuration tuning**
  - Scan parameters
  - Threat thresholds
  - Display parameters
  - Rebuild & redeploy
- **Troubleshooting** (detailed solutions for 8 common issues)
- **Backup & recovery**
- **Maintenance** (weekly, monthly tasks)
- **Performance benchmarks**
- **Support** (debugging checklist)

**Audience:** DevOps engineers, system administrators.

---

### scripts/kindle/ Folder

#### [scripts/kindle/README.md](scripts/kindle/README.md) (240 lines)

**Purpose:** Runtime control and automation scripts for the Kindle

**Contains:**
- **Script list:** 9 shell scripts for control, autostart, button mapping
- **Quick start** (`drone-control.sh` usage)
- **Autostart setup** (boot registration)
- **Timezone/date drift** (fixes common Kindle issues)
- **Button listener modes:**
  - LIPC mode (page up/down events)
  - Evdev mode (raw input device events)
  - Waitforkey mode (BusyBox-compatible, recommended)
- **Per-device button mapping** (4 physical buttons on Kindle 4)
- **Firewall integration** (automatic loopback + port 5555 rules)
- **Notes** (lock files, stale locks, firewall debugging)

**Practical:** Copy & paste commands to set up button control.

---

#### [scripts/kindle/CHANGES.md](scripts/kindle/CHANGES.md) (150+ lines)

**Purpose:** Version history and recent changes

**Contains:**
- Dated change log of script updates
- Bug fixes and new features
- Known issues
- Version compatibility notes

---

## Reading Path by User Role

### 1. **Quick Evaluator** (15 min)
1. [README.md](README.md) — Overview
2. [docs/DETECTION_SCORING.md](docs/DETECTION_SCORING.md) — Scoring section only
3. Decide: useful for my needs?

### 2. **Developer** (2 hours)
1. [README.md](README.md) — Full read
2. [docs/DETECTION_SCORING.md](docs/DETECTION_SCORING.md) — All sections
3. [docs/CHIPSET_CAPABILITIES.md](docs/CHIPSET_CAPABILITIES.md) — WMI commands section
4. [docs/HARDWARE_SENSORS.md](docs/HARDWARE_SENSORS.md) — Working sensors section
5. Browse source code: `drone-app/src/main/java/`

### 3. **DevOps / System Administrator** (3 hours)
1. [README.md](README.md) — Features, hardware, quick start
2. [docs/DEPLOYMENT_GUIDE.md](docs/DEPLOYMENT_GUIDE.md) — Full read
3. [scripts/kindle/README.md](scripts/kindle/README.md) — Full read
4. [README.md](README.md) — Troubleshooting section

### 4. **Research / Academic** (6 hours)
1. [docs/DETECTION_SCORING.md](docs/DETECTION_SCORING.md) — Algorithm deep dive
2. [docs/CHIPSET_CAPABILITIES.md](docs/CHIPSET_CAPABILITIES.md) — Hardware investigation
3. [docs/DISTANCE_CALCULATION.md](docs/DISTANCE_CALCULATION.md) — Full read (includes references)
4. [docs/HARDWARE_SENSORS.md](docs/HARDWARE_SENSORS.md) — Sensor reverse engineering
5. Review `project_drone_detector.md` (if archived for reference)

### 5. **RF Engineer / Security Professional** (4 hours)
1. [docs/DETECTION_SCORING.md](docs/DETECTION_SCORING.md) — Full read
2. [docs/CHIPSET_CAPABILITIES.md](docs/CHIPSET_CAPABILITIES.md) — CRC analysis section
3. [docs/DISTANCE_CALCULATION.md](docs/DISTANCE_CALCULATION.md) — RSSI/path loss models
4. [README.md](README.md) — Limitations section

---

## Common Tasks

### "How do I deploy the detector?"
→ Follow [docs/DEPLOYMENT_GUIDE.md](docs/DEPLOYMENT_GUIDE.md)

### "What are the threat scoring rules?"
→ See [docs/DETECTION_SCORING.md](docs/DETECTION_SCORING.md) → Threat Scoring System

### "Which sensors can I read on Kindle 4?"
→ See [docs/HARDWARE_SENSORS.md](docs/HARDWARE_SENSORS.md) → Working Sensors

### "How does distance estimation work?"
→ See [docs/DISTANCE_CALCULATION.md](docs/DISTANCE_CALCULATION.md) → Model 1: Log-Distance Path Loss

### "What WiFi scanning commands are available?"
→ See [docs/CHIPSET_CAPABILITIES.md](docs/CHIPSET_CAPABILITIES.md) → WMI Command Reference

### "My app won't start. What do I do?"
→ See [docs/DEPLOYMENT_GUIDE.md](docs/DEPLOYMENT_GUIDE.md) → Troubleshooting → App Won't Start

### "How do I map hardware buttons to start/stop?"
→ See [scripts/kindle/README.md](scripts/kindle/README.md) → Waitforkey button mode

### "I want to change the scan interval or threat thresholds."
→ See [README.md](README.md) → Configuration & Tuning

---

## Project Statistics

| Metric | Value |
|---|---|
| Main documentation files | 5 (in `docs/`) |
| Total documentation lines | ~2,000 |
| Code examples | 30+ |
| Runtime scripts | 9 |
| Supported Kindle models | Kindle 4 (2011) |
| Tested drones | DJI Mavic 3, others |
| Main language | Java 8 |

---

## Feedback & Contributions

- Issues: Report via GitHub Issues
- Improvements: Submit Pull Requests
- Questions: Check relevant documentation section first

---

## License

This documentation is provided as-is for educational and RF research purposes.

---

**Last Updated:** August 2026  
**Version:** 2.0 (Documentation Restructure)


