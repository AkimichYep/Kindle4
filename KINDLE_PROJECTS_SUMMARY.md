# 📱 Kindle Projects — Summary & Reference

> Summarized from `tmp/kindle/`, `tmp/kindle-clock/`, `tmp/kindle-spring/`  
> Date: July 26, 2026

---

## 📦 Project Overview

| Project | Description | Status |
|---|---|---|
| `kindle/` | B2 English study display — PNG images rotated on Kindle via SSH | ✅ Complete |
| `kindle-clock/` | Clock + Weather display using `fbink` + `wttr.in` | ✅ Complete |
| `kindle-spring/` | Spring Boot backend for Kindle content delivery | 🔧 In progress |

---

## 🔧 Kindle Access Requirements

- **Jailbroken Kindle** with SSH enabled
- **Default IP**: `192.168.88.14` (USB network / WiFi)
- **User**: `root`
- **Content storage**: `/mnt/us/`
- **Screensaver path**: `/mnt/us/screensavers/`

### Quick SSH test
```sh
ssh root@192.168.88.14 "echo OK"
```

### Detect Kindle model
```sh
ssh root@192.168.88.14 "cat /proc/usid"
```

---

## 📚 Project 1 — B2 Study Display

### What it does
- Parses `subjects_to_learn.txt` (71+ Q&A pairs, 8 topics)
- Generates **758×1024 grayscale PNG** images optimized for e-ink
- Uploads to Kindle `/mnt/us/screensavers/`
- Rotates images randomly every N minutes via **crontab** or **daemon**

### Topics covered
- Technology & Social Media (13 Q&A)
- Environment & Climate Change (11 Q&A)
- Education (10 Q&A)
- Health & Lifestyle (9 Q&A)
- Work & Economy (9 Q&A)
- Travel & Globalisation (7 Q&A)
- Society & Equality (6 Q&A)
- Science & Medicine (6 Q&A)

---

### 🐍 Image Generation (Python)

**Requirements**: `pip install Pillow requests`

```python
# Key config from kindle_study_display_v2.py
KINDLE_WIDTH  = 758
KINDLE_HEIGHT = 1024
FONT_SIZE_TITLE    = 38
FONT_SIZE_SUBTITLE = 30
FONT_SIZE_BODY     = 26
MARGIN        = 50

# Font fallback chain (Windows → Linux)
# 1. arial.ttf / arialbd.ttf
# 2. DejaVuSans.ttf / DejaVuSans-Bold.ttf
# 3. C:\Windows\Fonts\arial.ttf
# 4. ImageFont.load_default()
```

**Generate all images locally:**
```powershell
cd C:\Users\Maksym_Yepaneshnikov\spring\kindle\src
python kindle_study_display_v2.py
# Outputs: kindle_images/study_b2_000.png ... study_b2_070.png
```

---

### 📤 Upload Commands

```powershell
# Upload all images at once
cd C:\Users\Maksym_Yepaneshnikov\spring\kindle\src\kindle_images
scp study_b2_*.png root@192.168.88.14:/mnt/us/screensavers/

# Upload a single image
scp study_b2_042.png root@192.168.88.14:/mnt/us/screensavers/

# Upload control scripts
scp show_random_study.sh root@192.168.88.14:/mnt/us/
scp control.sh root@192.168.88.14:/mnt/us/
ssh root@192.168.88.14 "chmod +x /mnt/us/show_random_study.sh /mnt/us/control.sh"
```

---

### ⚙️ Rotation — Crontab Method (Recommended)

**One-click setup:**
```powershell
powershell -ExecutionPolicy Bypass -File C:\Users\Maksym_Yepaneshnikov\spring\kindle\setup_crontab.ps1
```

**Manual setup:**
```sh
# On Kindle via SSH
crontab -e
# Add: */10 * * * * /mnt/us/show_random_study.sh
```

**Cron intervals:**
```
*/5  * * * *   Every 5 minutes
*/10 * * * *   Every 10 minutes (default)
*/15 * * * *   Every 15 minutes
*/30 * * * *   Every 30 minutes
0  * * * *     Every hour
```

---

### ⚙️ Rotation — Daemon Method (Background Process)

```sh
# Start
ssh root@192.168.88.14 "/mnt/us/control.sh start"
# → Rotates every 3 minutes

# Stop
ssh root@192.168.88.14 "/mnt/us/control.sh stop"

# Status + last 10 log entries
ssh root@192.168.88.14 "/mnt/us/control.sh status"

# Show random image NOW
ssh root@192.168.88.14 "/mnt/us/control.sh test"

# View full log
ssh root@192.168.88.14 "/mnt/us/control.sh log"

# Follow log live
ssh root@192.168.88.14 "/mnt/us/control.sh tail"

# Restart
ssh root@192.168.88.14 "/mnt/us/control.sh restart"

# Uninstall (stops + removes log)
ssh root@192.168.88.14 "/mnt/us/control.sh uninstall"
```

---

### 🖥️ How to View on Kindle Screen

**Method 1 — Screensaver (Recommended, battery-friendly)**
1. Press **Power** button → Kindle sleeps → screensaver shows study image
2. Press **Power** again → wakes up
3. Each sleep/wake cycle shows next image automatically

**Method 2 — Force screensaver timeout**
```sh
ssh root@192.168.88.14 "lipc-set-prop com.lab126.powerd touchScreenSaverTimeout 1"
```

**Method 3 — Write directly to framebuffer**
```sh
ssh root@192.168.88.14 "cat /mnt/us/screensavers/study_b2_042.png > /dev/fb0"
# With ImageMagick (if available):
convert image.png -depth 8 -size 758x1024 gray:- | dd of=/dev/fb0 bs=758
```

---

### 🔍 Troubleshooting

```sh
# Check images exist
ssh root@192.168.88.14 "ls -lh /mnt/us/screensavers/study*.png"

# Check crontab
ssh root@192.168.88.14 "crontab -l"

# Kill stale display scripts
ssh root@192.168.88.14 "killall kindle_display.sh"

# View last 20 log lines
ssh root@192.168.88.14 "tail -20 /mnt/us/study_display.log"

# Test framebuffer write directly
ssh root@192.168.88.14 "cat /mnt/us/screensavers/study_b2_042.png > /dev/fb0"
```

---

### 📁 File Layout (Kindle)

```
/mnt/us/
├── show_random_study.sh      ← Main randomizer script
├── control.sh                ← start/stop/status management
├── kindle_rotation.pid       ← Daemon PID file
├── study_display.log         ← Activity log (auto-trimmed at 10KB)
└── screensavers/
    ├── study_b2_000.png
    ├── study_b2_001.png
    └── ... (71 images total)
```

---

## 🕐 Project 2 — Kindle Clock + Weather (`kindle-clock/`)

### What it does
- Shows **large clock** + **date** + **weather** on e-ink screen
- Fetches weather from `wttr.in` (free, no API key needed)
- Uses `fbink` for framebuffer rendering (crisp text on e-ink)
- Sleeps between updates using `rtcwake` (very battery efficient)
- Updates every **1 minute**, fetches weather every **1 hour**

### Key design patterns

**1. Weather fetch (no API key!)**
```sh
WEATHER=$(curl -s -f -m 5 https://ru.wttr.in/Kharkiv?format="%C,+%t")
# Returns e.g.: "Partly cloudy, +22°C"
COND=${WEATHER%,*}           # "Partly cloudy"
TEMP=$(echo ${WEATHER##*,} | sed s/+//)  # "22°C"
```

**2. Precise 1-minute sleep with rtcwake**
```sh
NOW=$(date +%s)
let WAKEUP_TIME="((($NOW + 59)/60)*60)"   # Round up to next full minute
let SLEEP_SECS=$WAKEUP_TIME-$NOW
rtcwake -d /dev/rtc1 -m no -s $SLEEP_SECS
echo "mem" > /sys/power/state              # Suspend to RAM
```

**3. fbink text rendering (positioned)**
```sh
FBINK="/mnt/us/extensions/MRInstaller/bin/K5/fbink -q"
FONT="regular=/usr/java/lib/fonts/Palatino-Regular.ttf"

$FBINK -b -c -m -t $FONT,size=150,top=10  "$TIME"    # Large clock
$FBINK -b    -m -t $FONT,size=20,top=410  "$DATE"    # Date line
$FBINK -b       -t $FONT,size=10,top=0,left=900 "Bat: $BAT"  # Battery
$FBINK -b    -m -t $FONT,size=20,top=510  "$COND"   # Weather condition
$FBINK -b    -m -t $FONT,size=30,top=600  "$TEMP | $INSIDE_TEMP_C°C"
$FBINK -w -s    # Force framebuffer update
```

**4. Disable screensaver for clock mode**
```sh
lipc-set-prop com.lab126.powerd preventScreenSaver 1
```

**5. Set CPU to power-save mode**
```sh
echo powersave > /sys/devices/system/cpu/cpu0/cpufreq/scaling_governor
```

**6. Stop unnecessary Kindle processes (PW2/PW3)**
```sh
stop lab126_gui
stop otaupd
stop phd
stop tmd
stop x
stop todo
stop mcsd
```

**7. Framebuffer rotation fix**
```sh
# PW2:
echo -n 0 > /sys/devices/platform/mxc_epdc_fb/graphics/fb0/rotate
# PW3:
echo 0 > /sys/devices/platform/imx_epdc_fb/graphics/fb0/rotate
```

### Hardware paths per model

| Feature | K4NT | PW2 | PW3 |
|---|---|---|---|
| Battery | `yoshi_battery/battery_capacity` | `yoshi_battery/battery_capacity` | `wario_battery/battery_capacity` |
| Backlight | `/dev/null` | `fl_tps6116x/fl_intensity` | `max77696-bl/brightness` |
| Temp sensor | `i2c-1/1-0048/papyrus_temperature` | `i2c-1/1-0068/papyrus_temperature` | `i2c-1/1-0068/papyrus_temperature` |

---

## 🌐 Project 3 — Spring Boot Backend (`kindle-spring/`)

A Spring Boot app designed to serve content to the Kindle over HTTP/WiFi. Intended to replace the static PNG approach with dynamic content generation.

**Build:**
```powershell
cd C:\Users\Maksym_Yepaneshnikov\spring\drone\tmp\kindle-spring
mvn clean package
```

**Deploy to Kindle:**
```powershell
.\deploy-kindle.bat
```

---

## 💡 Reusable Approaches & Nice Patterns

### 1. Cryptographic randomness in shell (POSIX)
```sh
# Better than $RANDOM — uses /dev/urandom
RAND=$(od -An -N2 -tu2 /dev/urandom | tr -d ' ')
COUNT=$(ls /mnt/us/screensavers/study*.png 2>/dev/null | wc -l)
INDEX=$((RAND % COUNT))
```

### 2. Self-rotating log file (shell)
```sh
MAX_LOG_SIZE=10000
LOG_SIZE=$(wc -c < "$LOG_FILE" 2>/dev/null || echo 0)
if [ "$LOG_SIZE" -gt "$MAX_LOG_SIZE" ]; then
    tail -n 50 "$LOG_FILE" > "${LOG_FILE}.tmp"
    mv "${LOG_FILE}.tmp" "$LOG_FILE"
fi
```

### 3. PID file pattern (daemon management in sh)
```sh
PID_FILE="/mnt/us/kindle_rotation.pid"
echo $$ > "$PID_FILE"
trap 'rm -f "$PID_FILE"; exit 0' INT TERM
# Check if already running:
if [ -f "$PID_FILE" ]; then
    OLD_PID=$(cat "$PID_FILE")
    kill -0 "$OLD_PID" 2>/dev/null && echo "Already running" && exit 1
fi
```

### 4. Multi-command control script pattern
A single `control.sh` with `case "$1"` covering:
`start | stop | status | restart | test | log | tail | uninstall`

### 5. Kindle LIPC commands (system control via SSH)
```sh
# Disable screensaver
lipc-set-prop com.lab126.powerd preventScreenSaver 1

# Trigger screensaver in 1 second
lipc-set-prop com.lab126.powerd touchScreenSaverTimeout 1

# Enable/disable WiFi
lipc-set-prop com.lab126.cmd wirelessEnable 1
lipc-set-prop com.lab126.cmd wirelessEnable 0

# Check WiFi state
lipc-get-prop com.lab126.wifid cmState  # → "CONNECTED"
```

### 6. Python image generation for e-ink (Pillow)
```python
from PIL import Image, ImageDraw, ImageFont
import textwrap

img = Image.new('L', (758, 1024), color=255)  # Grayscale white
draw = ImageDraw.Draw(img)
wrapped = textwrap.wrap(text, width=40)
for line in wrapped:
    draw.text((50, y), line, fill=0, font=font)
    y += font_size + 8
img.save("output.png", "PNG")
```

### 7. Free weather API (no key required)
```sh
curl -s "https://wttr.in/Kyiv?format=%C,+%t"
# → "Sunny, +25°C"
```

### 8. Windows UTF-8 fix for Python scripts
```python
if sys.platform == 'win32':
    try:
        sys.stdout.reconfigure(encoding='utf-8')
    except:
        pass
```

---

## 📋 Quick Reference Cheatsheet

```sh
# === UPLOAD ALL IMAGES ===
cd C:\Users\Maksym_Yepaneshnikov\spring\kindle\src\kindle_images
scp study_b2_*.png root@192.168.88.14:/mnt/us/screensavers/

# === START ROTATION ===
ssh root@192.168.88.14 "/mnt/us/control.sh start"

# === CHECK STATUS ===
ssh root@192.168.88.14 "/mnt/us/control.sh status"

# === SHOW IMAGE NOW ===
ssh root@192.168.88.14 "/mnt/us/control.sh test"

# === VIEW LOG ===
ssh root@192.168.88.14 "tail -20 /mnt/us/study_display.log"

# === STOP ROTATION ===
ssh root@192.168.88.14 "/mnt/us/control.sh stop"

# === SCREENSAVER TRICK ===
# Press Power → sleep → see image. Press Power again → wake.
```

---

## 🚀 Possible Next Steps

- [ ] Add 6 more B2 topics (Arts, Migration, Food & Culture, Crime, Language, Philosophy)
- [ ] Integrate Spring Boot backend to serve dynamic content instead of static PNGs
- [ ] Combine clock + study display (show time + study content together)
- [ ] Add RSS feed reader (`KindleRSS.java` already exists in `src/`)
- [ ] Add Reddit reader (`KindleReddit.java` already exists in `src/`)
- [ ] Migrate clock project to use the Python image generator instead of `fbink`

