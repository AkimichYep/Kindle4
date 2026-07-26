# Kindle Drone Tools

Wi-Fi drone detection and spectrum analysis tools for a jailbroken Amazon Kindle 4,
using the built-in Atheros AR6003 chipset and e-ink display as a passive RF surveillance HUD.

- Detection logic, scoring, and tuning → [SummaryPro.md](SummaryPro.md)
- AR6003 chipset capabilities and firmware investigation → [Chipset.md](Chipset.md)

---

## Project Structure

```
src/main/java/com/yep/kindle/dron/
├── KindleDroneDetectorPro.java   # Full detector v2.1 — primary tool
├── KindleDroneDetector.java      # Detector v1 — simpler, no temporal engine
├── KindleSpectrum.java           # 2.4 GHz channel / spectrum analyzer
├── KindleWifiHud.java            # Basic Wi-Fi distance HUD
├── KindleDisplay.java            # TCP server → e-ink passthrough
├── KindleDroneListener.java      # UDP Remote ID packet listener
├── KindleTcpListener.java        # Simple TCP receive + log
├── TestClient.java               # TCP send test helper
├── KindleUtils.java              # Shared: exec, sleep, renderToEInk, screen constants
├── DroneSignatures.java          # Shared: OUI map, SSID keywords, lookup
└── WifiUtils.java                # Shared: distance formula, /proc/net/wireless
```

## Build

```bash
mvn -DskipTests package
```

## Run (local JVM)

```bash
java -cp target/classes com.yep.kindle.dron.KindleDroneDetectorPro
java -cp target/classes com.yep.kindle.dron.KindleDroneDetector
java -cp target/classes com.yep.kindle.dron.KindleSpectrum
java -cp target/classes com.yep.kindle.dron.KindleSpectrum active
java -cp target/classes com.yep.kindle.dron.KindleWifiHud
java -cp target/classes com.yep.kindle.dron.KindleDisplay
```

## On-Device Deployment

Copy the fat JAR to `/mnt/us/` and run:

```bash
# One-time setup
iptables -F && iptables -P INPUT ACCEPT
wmiconfig -i wlan0 --version

# Start detector
cd /mnt/us
java/jre/bin/java -jar KindleDroneDetectorPro.jar
```
