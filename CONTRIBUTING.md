# Contributing & Development

This guide explains how to extend and contribute to the Kindle Drone Detector project.

---

## Project Structure

### Source Layout

```
drone/                              # Parent project
├── drone-util/                     # Shared utilities
│   └── src/main/java/com/yep/kindle/
│       ├── KindleUtils.java       # Exec, logging, screen constants
│       ├── WifiUtils.java         # Distance formulas, /proc/net/wireless
│       ├── DroneSignatures.java   # OUI map, SSID keywords
│       └── SensorReader.java      # I2C sensor access
│
├── drone-core/                     # Detection engine
│   └── src/main/java/com/yep/kindle/
│       ├── KindleDroneDetectorPro.java    # Main detector (v2.1)
│       ├── KindleDroneDetector.java       # Detector v1 (legacy)
│       ├── NetCsvStore.java               # CSV persistence (14 columns)
│       ├── MovementMetrics.java           # Temporal analysis (EMA, trends)
│       ├── DroneDetectionEngine.java      # Scoring logic
│       └── CrcAnalyzer.java               # CRC error tracking
│
├── drone-display/                  # E-ink rendering
│   └── src/main/java/com/yep/kindle/
│       ├── KindleDisplay.java      # TCP server interface
│       ├── HudRenderer.java        # Text HUD (50×40 ASCII)
│       ├── RadarRenderer.java      # Graphical radar image (P4 PBM format)
│       ├── WeatherPageRenderer.java# Weather + moon/space pages
│       └── HomeTemperatureGraph.java # Home temp history chart
│
├── drone-app/                      # Main executable
│   ├── pom.xml                     # Uber-JAR assembly
│   ├── src/main/resources/
│   │   └── application.properties  # Configuration parameters
│   └── target/
│       └── drone-app-2.0.0-SNAPSHOT.jar  # Deployable JAR
│
└── scripts/kindle/                 # Runtime automation
    ├── drone-control.sh            # Main control interface
    ├── drone-start.sh              # Startup script
    ├── drone-install-autostart.sh  # Boot registration
    ├── drone-button-*.sh           # Button mapping (3 variants)
    └── README.md                   # Script documentation
```

### Configuration Flow

```
application.properties
    ↓
KindleDroneDetectorPro.java (main())
    ↓ reads config
DroneDetectionEngine.java (scoring)
    ↓
NetCsvStore.java (persistence)
    ↓
HudRenderer.java (display)
    ↓
eips command (e-ink update)
```

---

## Key Classes

### KindleDroneDetectorPro.java

**Entry point** (main method) and orchestrator.

**Responsibilities:**
1. Initialize WiFi scanning (`wmiconfig` commands)
2. Execute main loop (5-second cycle)
3. Read WiFi APs (`iwlist wlan0 scan`)
4. Update temporal history (per-MAC ring buffers)
5. Calculate threat scores
6. Render e-ink display
7. Persist to CSV
8. Handle firewall & power bank keepalive

**Key constants:**
```java
static final int SCAN_INTERVAL_MS = 5000;
static final int BASELINE_LOOPS = 30;
static final double MOV_STDDEV_THRESHOLD = 10.0;
static final int THREAT_ALERT_THRESHOLD = 60;
static final int CRC_ALERT_THRESHOLD = 200;
```

**Main loop structure:**
```java
while (true) {
    // PRE-SCAN: Set WiFi parameters
    if (loopCount % 6 == 0) {
        wmiconfig("--scanprobedssid <SSID>");
    }
    
    // SCAN: Get AP list
    List<AccessPoint> aps = iwlistScan();
    
    // TEMPORAL: Update per-MAC history
    for (AccessPoint ap : aps) {
        history.get(ap.mac).add(ap);  // Ring buffer
    }
    
    // SCORING: Compute threat scores
    List<AccessPoint> ranked = score(aps);
    
    // DISPLAY: Render HUD
    render(ranked);
    
    // PERSIST: Write CSV
    csvStore.append(ranked);
    
    // SLEEP & LOOP
    Thread.sleep(SCAN_INTERVAL_MS);
    loopCount++;
}
```

---

### DroneDetectionEngine.java

**Scoring logic.**

**Threat vectors (11 total):**

```java
int threatScore = 0;

// Static signatures
if (isOuiMatch(ap.mac)) threatScore += 50;
if (hasSsidKeyword(ap.ssid)) threatScore += 40;
if (isAdHocMode(ap.mode)) threatScore += 20;

// Temporal signals
if (isNew(ap) && ap.rssi > -80) threatScore += 25;
if (isNew(ap) && ap.rssi > -65) threatScore += 20;
if (isMoving(ap)) threatScore += 20;
if (hasChannelHop(ap)) threatScore += 20;
if (isHiddenSsid(ap) && dist < 80) threatScore += 15;
if (isTransient(ap)) threatScore += 15;
if (hasRandomMac(ap)) threatScore += 10;

// Fast-path surge
if (rssiSurge > 12) threatScore += 20;

return Math.min(threatScore, 100);  // Cap at 100
```

**Temporal flag implementation:**

```java
// MOV (Movement) - RSSI standard deviation > 10 dB
if (stddev(rssiHistory) > MOV_STDDEV_THRESHOLD) {
    flags.add("MOV");
}

// NEW - Not in baseline AND signal > -80 dBm
if (!isInBaseline && rssi > -80) {
    flags.add("NEW");
}

// HOP - Seen on 2+ channels
if (channelSet.size() >= 2) {
    flags.add("HOP");
}

// TRN - Transient appearance
if (hasGaps && peakRssi > -80) {
    flags.add("TRN");
}

// HID - Hidden SSID < 80m
if (ssid.isEmpty() && distance < 80) {
    flags.add("HID");
}
```

---

### NetCsvStore.java

**Persistent storage (14 columns).**

**CSV format:**
```
mac,ssid,firstSeen,lastSeen,count,peakSignal,oui,keyword,obsTime,distHist,lastCh,surgeCnt,maxApp,lastFlags
60:60:1F:A3:B7:22,MAVIC-3-xxxx,2026-08-05T02:48:34Z,2026-08-05T02:49:12Z,5,-52,DJI,"drone",0,[-52,-51,-52,-53],6,1,95,"OUI STR NEW"
```

**Columns:**

| # | Name | Type | Purpose |
|---|---|---|---|
| 1 | mac | String | MAC address (key) |
| 2 | ssid | String | SSID (empty if hidden) |
| 3 | firstSeen | ISO8601 | First detection timestamp |
| 4 | lastSeen | ISO8601 | Most recent scan timestamp |
| 5 | count | Int | Scan cycles seen |
| 6 | peakSignal | Int | Strongest RSSI observed |
| 7 | oui | String | MAC vendor ID (OUI) |
| 8 | keyword | String | Matched SSID keyword (if any) |
| 9 | obsTime | Long | Observation duration (seconds) |
| 10 | distHist | JSON array | Last 5 distance estimates |
| 11 | lastCh | Int | Last channel seen |
| 12 | surgeCnt | Int | Surge events count |
| 13 | maxApp | Int | Max approach (consecutive distance decreases) |
| 14 | lastFlags | String | Space-separated threat flags |

**Usage:**
```java
NetCsvStore store = new NetCsvStore("/mnt/us/drone_nets.csv");
store.load();  // Load from disk

for (AccessPoint ap : detectedAps) {
    store.update(ap);  // Add or update row
}

store.save();  // Persist to disk
```

---

### MovementMetrics.java

**Temporal analysis** (per-MAC history tracking).

**Data structure:**
```java
class MacHistory {
    Deque<Double> rssiHistory = new ArrayDeque<>(40);  // Ring buffer
    Deque<Long> timestamps = new ArrayDeque<>(40);
    Deque<Integer> channels = new ArrayDeque<>(40);
    int emaWarmupCount = 0;
    
    void add(double rssi, int channel, long timestamp) {
        if (rssiHistory.size() >= 40) rssiHistory.removeFirst();
        rssiHistory.addLast(rssi);
        // ... timestamp, channel tracking ...
    }
}
```

**Computed metrics:**
```java
// RSSI standard deviation (for MOV flag)
double stddev = calculateStddev(rssiHistory);

// Consecutive approach (distance decreases)
int consecutiveDecreases = countConsecutiveApproaches(distanceHistory);

// Channel set (for HOP flag)
Set<Integer> uniqueChannels = new HashSet<>(channels);

// EMA-smoothed RSSI (for trend detection)
double smoothedRssi = emaFilter(rssiHistory);

// Transient detection (gaps in presence)
int presenceGaps = countGaps(timestamps);
```

---

### HudRenderer.java

**Text HUD rendering** (50 columns × 40 rows).

**Layout:**
```
DRONE 02:48:34 AP:30 THR:2 #45      ← Header (loop #45, 30 APs, 2 threats)
CRC+52 NF:-96 SNR:33 LQ:42 ARMED    ← Stats (CRC delta, noise floor, armed state)
--------------------------------------← Separator
!~* 7m  -53dB C6  DJI_MAVIC_3      DJI NEW STR  ← Top 1 (threat=95, symbols, flags)
+   43m -74dB C3  [HIDDEN]         HID RMAC     ← Top 2
    65m -79dB C9  HomeNetwork                   ← Top 3 (benign, no symbols)
... up to 20 rows ...
```

**Symbol meanings:**
- `!` = Threat ≥ 60 (likely drone)
- `+` = Threat ≥ 30 (suspicious)
- `~` = Moving (MOV flag)
- `*` = New device (NEW flag)

**Implementation:**
```java
void render(List<AccessPoint> ranked) {
    StringBuilder hud = new StringBuilder();
    
    // Header
    hud.append(String.format("DRONE %s AP:%d THR:%d #%d\n",
        timeStr, rankedfsize(), threatCount(), loopNum));
    
    // Stats line
    hud.append(String.format("CRC+%d NF:%d SNR:%d LQ:%d %s\n",
        crcDelta, noiseFloor, snr, linkQuality, armedState));
    
    // Separator
    hud.append("--------------------------------------\n");
    
    // AP rows (top 20)
    for (AccessPoint ap : ranked.subList(0, Math.min(20, ranked.size()))) {
        String symbols = buildSymbols(ap);
        String flags = String.join(" ", ap.flags);
        hud.append(String.format("%s %3dm %4ddB %2d %s %s\n",
            symbols, ap.distance, ap.rssi, ap.channel, ap.ssid, flags));
    }
    
    // Write to e-ink
    writeToScreen(hud.toString());
}
```

**E-ink output:**
```bash
eips -c                      # Clear screen
eips -t 0,0 "DRONE 02:48:34" # Top-left text
eips -t 0,1 "CRC+52 ..."     # Next line
# ... etc
```

---

### RadarRenderer.java

**Graphical radar image** (600×800 pixels, P4 PBM format).

**Rendering:**
```java
void renderRadar(List<AccessPoint> aps) {
    // Create 600×800 white image
    byte[] pixels = new byte[600 * 800];
    Arrays.fill(pixels, (byte) 255);
    
    // Draw concentric circles (range rings)
    drawCircle(pixels, 300, 400, 50, 0);   // 50m ring
    drawCircle(pixels, 300, 400, 100, 0);  // 100m ring
    
    // Plot APs as dots
    for (AccessPoint ap : aps) {
        if (ap.threatScore >= 30) {
            int x = 300 + (int) ap.distance * Math.cos(ap.bearing);
            int y = 400 + (int) ap.distance * Math.sin(ap.bearing);
            if (x >= 0 && x < 600 && y >= 0 && y < 800) {
                drawDot(pixels, x, y, ap.threatScore > 60 ? 0 : 128);
            }
        }
    }
    
    // Write P4 format (1-bit monochrome, packed bytes)
    writePbmP4(pixels, "/mnt/us/radar.pgm");
    
    // Display
    exec("eips", "/mnt/us/radar.pgm");
}
```

**P4 Format Details:**
- Header: `P4\n600 800\n`
- Data: Packed bits (MSB-first), 75 bytes per row
- Black = 1, white = 0 (inverted from typical)
- File size: 60 KB (vs. 480 KB for 8-bit grayscale)

---

## Adding New Features

### Example 1: Add a New Threat Vector

**Goal:** Detect drones by manufacturer name pattern in SSID.

**Steps:**

1. **Add to DroneSignatures.java:**
```java
static final Set<String> MANUFACTURER_KEYWORDS = Set.of(
    "dji", "dji_", "phantom", "mavic", "tello", "parrot",
    "autel", "skydio", "yuneec", "esp32", "betafpv"
);

static boolean isManufacturerSsid(String ssid) {
    return MANUFACTURER_KEYWORDS.stream()
        .anyMatch(ssid.toLowerCase()::contains);
}
```

2. **Update scoring in DroneDetectionEngine.java:**
```java
if (DroneSignatures.isManufacturerSsid(ap.ssid)) {
    threatScore += 30;  // New vector
    flags.add("MFG");   // New flag
}
```

3. **Update documentation:**
- Add row to threat scoring table in DETECTION_SCORING.md
- Update symbols reference if adding new flag

4. **Test:**
```bash
mvn test
mvn -DskipTests package
# Deploy and test with known drone SSIDs
```

---

### Example 2: Add a New E-Ink Display Page

**Goal:** Add CPU temperature monitoring page.

**Steps:**

1. **Create new renderer class:**
```java
// drone-display/src/main/java/.../CpuTempPageRenderer.java
public class CpuTempPageRenderer {
    public String renderCpuTempPage() {
        // Read /sys/class/thermal/ or similar
        // Generate 600×800 PNG with temp graph
        return imagePath;
    }
}
```

2. **Register in page rotation:**
```java
// KindleDroneDetectorPro.java
enum DisplayPage {
    PAGE_MAIN(0), PAGE_WEATHER(1), PAGE_MOON(2),
    PAGE_TEMP(3), PAGE_CPU_TEMP(4), PAGE_RADAR(5);
    // Update MAX_PAGE if adding new pages
    static final int MAX_PAGE = 5;
}

void rotateDisplayPage() {
    currentPage = (currentPage + 1) % (MAX_PAGE + 1);
    String image = switch (currentPage) {
        case PAGE_MAIN -> hudRenderer.render(...);
        case PAGE_CPU_TEMP -> cpuTempRenderer.render(...);
        // ...
        default -> "";
    };
    writeToScreen(image);
}
```

3. **Add to documentation:**
- Update HUD display modes in README.md
- Add new page to display rotation explanation

---

### Example 3: Add Configuration Parameter

**Goal:** Make baseline learning period configurable.

**Steps:**

1. **Add to application.properties:**
```properties
detector.baseline.loops=30
```

2. **Read in main class:**
```java
// KindleDroneDetectorPro.java
static int BASELINE_LOOPS;

static {
    // Load from properties or system property
    BASELINE_LOOPS = Integer.parseInt(
        System.getProperty("detector.baseline.loops", "30")
    );
}
```

3. **Use in code:**
```java
if (loopCount < BASELINE_LOOPS) {
    displayState = "LEARN";  // Still learning
} else {
    displayState = "ARMED";  // Ready to alert
}
```

4. **Document in README.md:**
- Add to Configuration & Tuning section
- Explain impact on detection quality

---

## Testing

### Unit Tests

```bash
# Run all tests
mvn test

# Run single test class
mvn test -Dtest=DroneDetectionEngineTest

# Run with coverage
mvn test jacoco:report
```

### Integration Testing on Kindle

```bash
# 1. Deploy to Kindle
scp drone-app/target/drone-app-2.0.0-SNAPSHOT.jar root@192.168.88.14:/mnt/us/

# 2. Start detector
ssh root@192.168.88.14 "/mnt/us/drone-control.sh start"

# 3. Monitor logs
ssh root@192.168.88.14 "tail -f /mnt/us/drone-app.log"

# 4. Test with known drones in range
# (fly DJI Mavic 3 nearby, confirm detection)

# 5. Check CSV history
ssh root@192.168.88.14 "tail -5 /mnt/us/drone_nets.csv | awk -F'\t' '{print \$1, \$2, \$13}'"
```

### Manual Testing Checklist

- [ ] App starts without errors
- [ ] E-ink HUD displays within 30 seconds
- [ ] Known WiFi APs appear in list (not all threats)
- [ ] Threat scores in range 0–100
- [ ] CSV file created with 14 columns
- [ ] Radar image displays correctly
- [ ] Display rotates through pages
- [ ] Battery % and temperature show correctly
- [ ] No excessive CPU/memory usage
- [ ] WiFi stays connected (no drops)

---

## Code Style & Conventions

### Java Conventions

- **Naming:**
  - Classes: PascalCase (`KindleDroneDetectorPro`)
  - Methods: camelCase (`calculateDistance()`)
  - Constants: UPPER_SNAKE_CASE (`SCAN_INTERVAL_MS`)
  - Private fields: with underscore (`_rssiHistory`)

- **Structure:**
  - Constants at top
  - Fields next
  - Constructors
  - Public methods
  - Private helpers

### Documentation

- **Javadoc for public methods:**
```java
/**
 * Calculate threat score for an access point.
 * @param ap the access point to score
 * @return threat score (0-100)
 */
public int calculateThreat(AccessPoint ap) {
    // ...
}
```

- **Inline comments for complex logic:**
```java
// EMA smoothing: weight current + previous
double smoothed = 0.25 * rssi + 0.75 * previousSmoothed;
```

---

## Debugging

### Enable Debug Logging

```java
// In KindleDroneDetectorPro.java
static final boolean DEBUG = true;  // Set to true

if (DEBUG) {
    System.out.println("Found AP: " + ap.mac + " RSSI=" + ap.rssi);
}
```

### Common Debug Points

```bash
# Check WiFi scanning
wmiconfig -i wlan0 --wlan query

# Check power mode
wmiconfig -i wlan0 --getpower

# Check CRC errors
wmiconfig -i wlan0 --getTargetStats

# Check e-ink
eips -c  # Clear screen
eips "TEST"  # Write text

# Check CSV format
head -1 /mnt/us/drone_nets.csv  # Header
tail -1 /mnt/us/drone_nets.csv  # Last row

# Check system load
top -n1
free -h
```

---

## Versioning & Release

**Current version:** 2.0.0-SNAPSHOT

**Release process:**

1. Finalize changes, test thoroughly
2. Update version in `pom.xml` (remove `-SNAPSHOT`)
3. Tag git release: `git tag v2.0.0`
4. Build final JAR: `mvn clean package`
5. Update CHANGELOG in scripts/kindle/CHANGES.md
6. Push to GitHub

---

## Known Limitations & Future Work

### Current Limitations
- 2.4 GHz only (no 5.8 GHz coverage)
- Scan-based only (no packet capture)
- Single-device detection (no triangulation)
- Indoor distance accuracy ±50%

### Potential Enhancements
- [ ] 5.8 GHz support (external SDR)
- [ ] Packet sniffing (processDot11Hdr workaround)
- [ ] Multi-Kindle mesh network
- [ ] Machine learning classifier
- [ ] REST API for remote monitoring
- [ ] Telegram/email notifications
- [ ] Web dashboard
- [ ] ADS-B integration (FAA LAANC)

---

## Getting Help

- **Documentation:** See [DOCUMENTATION.md](DOCUMENTATION.md)
- **Issues:** GitHub Issues
- **Questions:** GitHub Discussions

---

## License

Contributions are welcome under the same license as the project.


