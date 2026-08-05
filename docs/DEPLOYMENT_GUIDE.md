# Production Deployment Guide

## Overview

This guide walks through setting up the Kindle Drone Detector for production use, including build, deployment, firewall configuration, logging, and troubleshooting.

---

## Prerequisites

### On Development Machine

- **Java 8+** (for Maven compilation)
- **Maven 3.6+** (for building)
- **Bash/PowerShell** (for deployment scripts)
- **Git** (optional, for version control)

### On Kindle

- **Jailbroken Kindle 4** (2011 model)
- **SSH access enabled** (via custom firmware)
- **Writable `/mnt/us/` partition** (default on jailbroken devices)
- **256 MB RAM minimum** (for Java 8 JVM)
- **Wi-Fi adapter** (AR6003 chipset, built-in)

### Network

- **Static IP or DHCP reservation** for Kindle (recommended)
- **Port 5555 accessible** (for HTTP control interface)
- **No egress firewall blocking** (for time sync, weather API)

---

## Building

### Step 1: Compile on Development Machine

```bash
cd /path/to/drone

# Build all modules
mvn clean compile

# Or, skip tests for faster build
mvn -DskipTests clean compile
```

### Step 2: Package JAR

```bash
# Create fat JAR with all dependencies
mvn -DskipTests package

# Output: drone-app/target/drone-app-2.0.0-SNAPSHOT.jar
```

### Step 3: Verify Output

```bash
ls -lh drone-app/target/drone-app-2.0.0-SNAPSHOT.jar

# Expected size: ~50–80 MB
```

---

## Deployment to Kindle

### Option A: Automated Deployment Script (Recommended)

```bash
#!/bin/bash
# deploy-to-kindle.sh

KINDLE_IP="192.168.88.14"
KINDLE_USER="root"
JAR_FILE="drone-app/target/drone-app-2.0.0-SNAPSHOT.jar"
SCRIPTS_DIR="scripts/kindle"

# Copy JAR
echo "Uploading JAR..."
scp "$JAR_FILE" "$KINDLE_USER@$KINDLE_IP:/mnt/us/"

# Copy control scripts
echo "Uploading control scripts..."
scp "$SCRIPTS_DIR/drone-*.sh" "$KINDLE_USER@$KINDLE_IP:/mnt/us/"

# Make executable
echo "Setting permissions..."
ssh "$KINDLE_USER@$KINDLE_IP" "chmod +x /mnt/us/drone-*.sh"

echo "Deployment complete!"
```

### Option B: Manual Deployment

```bash
# 1. Copy JAR
scp drone-app/target/drone-app-2.0.0-SNAPSHOT.jar root@192.168.88.14:/mnt/us/

# 2. Copy scripts
scp scripts/kindle/drone-control.sh root@192.168.88.14:/mnt/us/
scp scripts/kindle/drone-start.sh root@192.168.88.14:/mnt/us/
scp scripts/kindle/drone-install-autostart.sh root@192.168.88.14:/mnt/us/
scp scripts/kindle/drone-button-*.sh root@192.168.88.14:/mnt/us/

# 3. Make executable
ssh root@192.168.88.14 "chmod +x /mnt/us/drone-*.sh"

# 4. Verify
ssh root@192.168.88.14 "ls -la /mnt/us/drone*.jar /mnt/us/drone*.sh"
```

### Step 4: Verify Deployment

```bash
ssh root@192.168.88.14 "cd /mnt/us && ls -lh drone-app-*.jar drone-*.sh"

# Expected output:
# -rw-r--r-- 1 root root 60M Jul 26 12:00 drone-app-2.0.0-SNAPSHOT.jar
# -rwxr-xr-x 1 root root 2.3K Jul 26 12:00 drone-control.sh
# ...
```

---

## Initial Setup (One-Time)

### Step 1: Prepare Kindle Filesystem

```bash
# SSH into Kindle
ssh root@192.168.88.14

# Create storage directory
mkdir -p /mnt/us

# Verify write access
touch /mnt/us/.test && echo "OK" && rm /mnt/us/.test
```

### Step 2: Clear Iptables & Set Baseline Firewall

```bash
# WARNING: This will drop all existing firewall rules
ssh root@192.168.88.14 << 'EOF'
iptables -F
iptables -P INPUT ACCEPT
iptables -P FORWARD ACCEPT
iptables -P OUTPUT ACCEPT

# Save backup
iptables-save > /mnt/us/iptables_backup.conf

echo "Firewall reset and backed up"
EOF
```

### Step 3: Enable Autostart (Optional)

```bash
ssh root@192.168.88.14 "/mnt/us/drone-install-autostart.sh"

# Verify
ssh root@192.168.88.14 "cat /etc/rc.local | grep drone"
```

### Step 4: Set Timezone (Recommended)

```bash
ssh root@192.168.88.14 << 'EOF'
mntroot rw
echo 'EET-2EEST,M3.5.0/3,M10.5.0/4' > /var/local/system/tz
rdate -s time.nist.gov
hwclock -w
mntroot ro
date
EOF
```

---

## Starting the Application

### Quick Start

```bash
# Start detector
ssh root@192.168.88.14 "/mnt/us/drone-control.sh start"

# View status
ssh root@192.168.88.14 "/mnt/us/drone-control.sh status"

# Tail log (real-time)
ssh root@192.168.88.14 "/mnt/us/drone-control.sh tail"

# Stop detector
ssh root@192.168.88.14 "/mnt/us/drone-control.sh stop"
```

### Expected Output

```
Starting drone detector...
Waiting for startup...
[✓] Detector started (PID: 1234)
App is RUNNING
Log: /mnt/us/drone-app.log
```

---

## Logging & Monitoring

### Application Log

**Path:** `/mnt/us/drone-app.log`

**View:**
```bash
# Last 20 lines
ssh root@192.168.88.14 "tail -20 /mnt/us/drone-app.log"

# Follow live (Ctrl+C to stop)
ssh root@192.168.88.14 "tail -f /mnt/us/drone-app.log"

# Last 100 lines
ssh root@192.168.88.14 "tail -100 /mnt/us/drone-app.log"

# Count detections
ssh root@192.168.88.14 "grep -c 'THREAT' /mnt/us/drone-app.log"
```

### CSV History

**Path:** `/mnt/us/drone_nets.csv`

**Format:** 14-column tab-separated

```bash
# View recent entries
ssh root@192.168.88.14 "tail -10 /mnt/us/drone_nets.csv"

# Count unique MACs
ssh root@192.168.88.14 "cut -f1 /mnt/us/drone_nets.csv | sort -u | wc -l"

# Find high-threat devices
ssh root@192.mentor.88.14 "awk -F'\t' '\$11 > 60 {print}' /mnt/us/drone_nets.csv"
```

### System Performance

```bash
# Check CPU usage
ssh root@192.168.88.14 "top -n1 | grep -E 'java|PID'"

# Check memory
ssh root@192.168.88.14 "ps aux | grep java | awk '{print \$6}'"

# Check disk space
ssh root@192.168.88.14 "df -h /mnt/us"

# Check WiFi state
ssh root@192.168.88.14 "wmiconfig -i wlan0 --wlan query"

# Check power mode
ssh root@192.168.88.14 "wmiconfig -i wlan0 --getpower"
```

---

## Firewall Configuration

### Automatic Setup

The installation script handles firewall automatically:

```bash
ssh root@192.168.88.14 "/mnt/us/drone-install-autostart.sh"
# Automatically opens:
# - Loopback (lo) for local control
# - port 5555 (wlan0) for remote access
```

### Manual Firewall Setup

```bash
ssh root@192.168.88.14 << 'EOF'
# Allow loopback (required for button control)
iptables -A INPUT -i lo -j ACCEPT

# Allow port 5555 on WiFi (HTTP/TCP)
iptables -A INPUT -i wlan0 -p tcp --dport 5555 -j ACCEPT

# Allow port 5555 on WiFi (UDP, if needed)
iptables -A INPUT -i wlan0 -p udp --dport 5555 -j ACCEPT

# Save rules
iptables-save > /etc/iptables/rules.v4

# View rules
iptables -L -n
EOF
```

### Verify Firewall

```bash
# Check if port 5555 is open
ssh root@192.168.88.14 "iptables -L -n | grep 5555"

# Expected output:
# ACCEPT     tcp  --  0.0.0.0/0            0.0.0.0/0            tcp dpt:5555
```

### Troubleshooting Access Denied

```bash
# Firewall is blocking; add rule:
ssh root@192.168.88.14 "iptables -A INPUT -i wlan0 -p tcp --dport 5555 -j ACCEPT"

# If still failing, check if localhost is blocked:
ssh root@192.168.88.14 "iptables -L -n | grep 127.0.0.1"

# If not found, add loopback rule:
ssh root@192.168.88.14 "iptables -A INPUT -i lo -j ACCEPT"

# Save rules
ssh root@192.168.88.14 "iptables-save > /etc/iptables/rules.v4"

# Restart app
ssh root@192.168.88.14 "/mnt/us/drone-control.sh restart"
```

---

## Configuration Tuning

### Scan Parameters

Edit `drone-app/src/main/resources/application.properties` before building:

```properties
# Scan interval (milliseconds)
# Higher = less CPU, lower = more responsive
detector.scan.interval=5000

# Detection dwell time per channel (milliseconds)
# Higher = more sensitive, lower = faster
detector.wifi.dwell.ms=200

# Baseline learning period (number of scans)
detector.baseline.loops=30
```

### Threat Thresholds

```properties
# Score at which display flashes
detector.threat.alert=60

# Score at which device is flagged "suspicious"
detector.threat.suspicious=30

# CRC interference alert threshold
detector.crc.threshold=200
```

### Display Parameters

```properties
# E-ink refresh interval (milliseconds)
display.refresh.interval=5000

# Time to hold each page (milliseconds)
display.page.hold.ms=300000
```

### After Editing Properties

```bash
# Rebuild JAR
mvn -DskipTests package

# Redeploy to Kindle
scp drone-app/target/drone-app-2.0.0-SNAPSHOT.jar root@192.168.88.14:/mnt/us/

# Restart app
ssh root@192.168.88.14 "/mnt/us/drone-control.sh restart"
```

---

## Troubleshooting

### App Won't Start

**Symptom:** `[✗] Failed to start detector` or app crashes immediately

**Diagnostic:**
```bash
ssh root@192.168.88.14 "cat /mnt/us/drone-app.log | tail -20"
```

**Common causes:**

1. **Java not found:**
   ```bash
   # Check Java availability
   ssh root@192.168.88.14 "which java || find / -name 'java' -type f 2>/dev/null"
   ```

2. **Port 5555 already in use:**
   ```bash
   ssh root@192.168.88.14 "netstat -tlnp | grep 5555"
   # Kill existing process and restart
   ```

3. **Insufficient memory:**
   ```bash
   ssh root@192.168.88.14 "free -h"
   # If <100 MB available, kill other apps first
   ```

**Solution:**
```bash
# Kill any stale processes
ssh root@192.168.88.14 "pkill -9 java; sleep 2"

# Clear stale lock files
ssh root@192.168.88.14 "rm -f /mnt/us/drone-app.lock"

# Restart
ssh root@192.168.88.14 "/mnt/us/drone-control.sh start"
```

### App Crashes or Freezes

**Symptom:** Log stops updating, app unresponsive

**Diagnostic:**
```bash
# Check if process is running
ssh root@192.168.88.14 "ps aux | grep java"

# Check for crash messages
ssh root@192.168.88.14 "dmesg | tail -20"
```

**Common causes:**

1. **Out of memory:**
   ```bash
   # Monitor memory usage
   watch -n1 'ssh root@192.168.88.14 "ps aux | grep java | awk '\''NR==2 {print \$6}'\'' MB"'
   ```

2. **WiFi driver crash:**
   ```bash
   ssh root@192.168.88.14 "wmiconfig -i wlan0 --wlan query"
   # If not available, WiFi is hung
   # Solution: Reboot Kindle
   ```

**Solution:**
```bash
# Increase scan interval (reduce WiFi load)
# Edit application.properties: detector.scan.interval=10000

# Or reboot Kindle
ssh root@192.168.88.14 "sync && reboot"
```

### High Battery Drain

**Symptom:** Battery drops >5% per hour

**Diagnostic:**
```bash
# Check battery current while app is running
ssh root@192.168.88.14 "cat /sys/devices/system/yoshi_battery/yoshi_battery0/battery_current"
# >150 mA = excessive
```

**Solutions:**

1. **Increase scan interval:**
   ```bash
   # Edit application.properties
   detector.scan.interval=15000  # 15 seconds instead of 5
   ```

2. **Disable radar display:**
   ```bash
   # Comment out radar page rotation in KindleDroneDetectorPro.java
   // PAGE_ROTATION = [..., PAGE_RADAR, ...]  // disabled
   ```

3. **Enable power saving mode:**
   ```bash
   ssh root@192.168.88.14 << 'EOF'
   # Set CPU to power-save governor
   echo powersave > /sys/devices/system/cpu/cpu0/cpufreq/scaling_governor
   
   # Reduce scan dwell time
   wmiconfig -i wlan0 --scan --pas=100  # 100 ms instead of 200 ms
   EOF
   ```

### WiFi Disconnects During Scanning

**Symptom:** WiFi drops after 5–10 minutes of scanning

**Diagnostic:**
```bash
ssh root@192.168.88.14 "wmiconfig -i wlan0 --getpower"
# Should show "maxperf"; if showing "rec", power mode was reset
```

**Solution:**
```bash
# App automatically re-applies --power maxperf every 30 loops (~2.5 min)
# If still dropping, verify device power state:

ssh root@192.168.88.14 << 'EOF'
# Apply maxperf immediately
wmiconfig -i wlan0 --power maxperf

# Verify it stuck
sleep 2
wmiconfig -i wlan0 --getpower  # Should show "maxperf"
EOF
```

### Firewall Port Access Denied

**Symptom:** Cannot connect to port 5555 from remote machine

**Diagnostic:**
```bash
# From development machine
nc -zv 192.168.88.14 5555
# If "refused", firewall is blocking

# On Kindle
ssh root@192.168.88.14 "iptables -L -n | grep 5555"
# Should show ACCEPT rule for dport 5555
```

**Solution:**
```bash
ssh root@192.168.88.14 "iptables -A INPUT -i wlan0 -p tcp --dport 5555 -j ACCEPT"
ssh root@192.168.88.14 "iptables-save > /etc/iptables/rules.v4"
ssh root@192.168.88.14 "/mnt/us/drone-control.sh restart"
```

### E-Ink Screen Shows Only White

**Symptom:** Display blanks out or shows garbled pixels

**Solution:**

```bash
# Clear e-ink cache
ssh root@192.168.88.14 "eips -c"

# Wait 5 seconds
sleep 5

# Restart app
ssh root@192.168.88.14 "/mnt/us/drone-control.sh restart"
```

**If problem persists:**
- Known issue fixed in v2.1: removed `eips -f` calls (see FIXES_APPLIED.md)
- Ensure JAR is latest build

---

## Backup & Recovery

### Backup Logs

```bash
# Download logs to development machine
scp root@192.168.88.14:/mnt/us/drone-app.log ./logs/drone-app-$(date +%Y%m%d).log
scp root@192.168.88.14:/mnt/us/drone_nets.csv ./logs/drone-nets-$(date +%Y%m%d).csv
```

### Backup Configuration

```bash
# Download firewall rules
scp root@192.168.88.14:/mnt/us/iptables_backup.conf ./backup/

# Download all Kindle config
scp -r root@192.168.88.14:/mnt/us/ ./backup/kindle-mnt-us/
```

### Restore Firewall

```bash
ssh root@192.168.88.14 "iptables-restore < /mnt/us/iptables_backup.conf"
```

---

## Maintenance

### Weekly Tasks

```bash
# Check logs for errors
ssh root@192.168.88.14 "grep -i 'error\|exception' /mnt/us/drone-app.log | wc -l"

# Check disk space
ssh root@192.168.88.14 "du -sh /mnt/us/*"

# Archive old logs
ssh root@192.168.88.14 "gzip /mnt/us/drone-app.log.* 2>/dev/null || true"
```

### Monthly Tasks

```bash
# Rotate logs (clean up old archives)
ssh root@192.168.88.14 << 'EOF'
find /mnt/us -name "drone-app.log.*" -mtime +30 -delete
find /mnt/us -name "*.gz" -mtime +60 -delete
EOF

# Reset CSV history (if too large)
ssh root@192.168.88.14 "head -1000 /mnt/us/drone_nets.csv > /tmp/new.csv && mv /tmp/new.csv /mnt/us/drone_nets.csv"

# Restart app
ssh root@192.168.88.14 "/mnt/us/drone-control.sh restart"
```

---

## Performance Benchmarks

**Expected on Kindle 4:**

| Metric | Value | Notes |
|---|---|---|
| Java startup | ~10–15 seconds | First JAR execution |
| App ready | ~30 seconds | Baseline learning period |
| Scan cycle | ~5 seconds | Full 13-channel scan |
| APs detected | 25–50 | Residential environment |
| CPU usage | 2–5% | ARM processor |
| Memory usage | ~30 MB | Java 8 JVM + app data |
| Battery drain | ~0.5%/hour | Idle scanning |

---

## Support & Debugging

### Collect System Information

```bash
ssh root@192.168.88.14 << 'EOF'
echo "=== Kindle Info ==="
cat /proc/usid
uname -a
free -h
df -h
echo ""
echo "=== WiFi Status ==="
wmiconfig -i wlan0 --version
wmiconfig -i wlan0 --wlan query
wmiconfig -i wlan0 --getpower
echo ""
echo "=== Detector Status ==="
ps aux | grep java
netstat -tlnp | grep 5555
echo ""
echo "=== Recent Logs ==="
tail -20 /mnt/us/drone-app.log
EOF
```

### Report Issues

When reporting issues, include:
- Output of system information above
- Last 50 lines of `/mnt/us/drone-app.log`
- Last 10 lines of `/mnt/us/drone_nets.csv`
- Specific error messages or symptoms
- Network environment (# of WiFi APs, interference level)


