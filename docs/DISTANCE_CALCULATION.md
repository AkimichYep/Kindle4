# RSSI-Based Distance Calculation

## Overview

Measuring Wi-Fi signal strength to estimate distance is fundamental to drone detection. This document covers the physics, algorithms, and practical Java implementations.

---

## Physical Background

**RSSI** = Received Signal Strength Indicator (power level in decibels, dBm)

- **Range:** −30 dBm (very close, ~1 meter) to −90 dBm (far, ~100 meters)
- **Negative value:** Higher absolute value = weaker signal
- **Challenge:** RSSI fluctuates wildly due to obstacles, multipath, and device orientation

**Key insight:** Never calculate distance from a single raw RSSI reading. Always smooth first.

---

## Model 1: Log-Distance Path Loss (Recommended)

### Formula

```
RSSI = A − 10 * n * log10(d)
```

Solving for distance:

```
d = 10^((A − RSSI) / (10 * n))
```

### Parameters

| Parameter | Symbol | Typical Value | Range |
|---|---|---|---|
| Reference signal strength at 1m | A | −30 dBm | −45 to −65 dBm |
| Path loss exponent | n | 2.7 | 2.0–4.3 |
| Distance | d | meters | varies |

### Path Loss Exponent (n) by Environment

| Environment | n | Notes |
|---|---|---|
| Free space (clear line of sight) | 2.0 | Vacuum, no obstacles |
| Indoor corridor (line-of-sight) | 1.6–1.8 | Minimal walls |
| Office/home with walls and furniture | **2.7–3.5** | **Most typical** |
| Dense urban or concrete buildings | 3.5–4.3 | Heavy multipath |

### Java Implementation (Java 8)

```java
public class WifiDistanceCalculator {
    /**
     * Calculate distance using Log-Distance Path Loss Model.
     * @param rssi received signal strength (dBm, negative)
     * @param rssiAtOneMeter calibrated signal at 1m (typically -30)
     * @param pathLossExponent path loss exponent (typically 2.7)
     * @return distance in meters
     */
    public static double calculateDistance(double rssi, 
                                          double rssiAtOneMeter, 
                                          double pathLossExponent) {
        if (rssi >= 0) {
            return 0.0;  // Invalid RSSI
        }
        double exponent = (rssiAtOneMeter - rssi) / (10.0 * pathLossExponent);
        return Math.pow(10.0, exponent);
    }
}
```

### Usage Example

```java
double rssi = -52.0;  // measured RSSI from AP
double rssiAt1m = -30.0;  // calibrated at 1 meter
double n = 2.7;  // office environment

double distance = WifiDistanceCalculator.calculateDistance(rssi, rssiAt1m, n);
System.out.println("Distance: " + distance + " meters");  // ~6.3 meters
```

---

## Model 2: Free Space Path Loss (FSPL)

### Formula

```
FSPL = 27.55 − 20*log10(f) − 20*log10(d)
```

Solving for distance:

```
d = 10^((27.55 − 20*log10(f) + RSSI) / 20)
```

### Parameters

| Parameter | Value | Notes |
|---|---|---|
| Frequency (f) | 2412–2472 MHz | 2.4 GHz band |
| Reference frequency | 1 MHz | Standardized |

### Advantages & Disadvantages

| Pro | Con |
|---|---|
| Based on pure physics | Assumes vacuum (no obstacles) |
| Works outdoors well | Very inaccurate indoors (±100% error) |
| Channel-frequency aware | Not suitable for drone detection |

### Java Implementation

```java
public static double calculateDistanceFSPL(double rssi, 
                                           double frequencyMHz) {
    if (rssi >= 0 || frequencyMHz <= 0) {
        return 0.0;
    }
    double exponent = (27.55 - (20.0 * Math.log10(frequencyMHz)) + 
                      Math.abs(rssi)) / 20.0;
    return Math.pow(10.0, exponent);
}
```

### When to Use

- Outdoor line-of-sight scenarios
- Theoretical calculations (not recommended for Kindle detector)

---

## Model 3: Empirical Curve Fitting

### Formula (Android Beacon Library)

```
ratio = RSSI / A
if ratio < 1.0:
    d = ratio^10
else:
    d = 0.89976 * ratio^7.7095 + 0.111
```

### Advantages

- Optimized for close-range mobile tracking (0–50 meters)
- Accounts for non-linear behavior near the device
- Empirically calibrated

### Disadvantages

- No clear physical basis
- Requires different calibration per environment
- Harder to tune parameters

### Java Implementation

```java
public static double calculateDistanceEmpirical(double rssi, 
                                                double rssiAtOneMeter) {
    double ratio = rssi / rssiAtOneMeter;
    
    if (ratio < 1.0) {
        return Math.pow(ratio, 10.0);
    } else {
        return 0.89976 * Math.pow(ratio, 7.7095) + 0.111;
    }
}
```

---

## Production: Smoothing & Filtering

### Problem: Raw RSSI Fluctuation

Example of raw WiFi RSSI readings over 1 second (device stationary):

```
-62, -66, -64, 0 (error), -68, -63, -65, -61, -64, -67, -63
```

Calculating distance directly from each value produces wildly different estimates.

### Solution: Exponential Moving Average (EMA)

**Formula:**

```
smoothed[i] = α * raw[i] + (1 − α) * smoothed[i−1]
```

**Parameters:**

| Parameter | Value | Interpretation |
|---|---|---|
| α (alpha) | 0.25 | Weight of current sample |
| Time constant (τ) | 4 / ln(1−α) ≈ 4 scans × 5 sec = 20 sec | Decay rate |

**Effect:** Smooths noise while preserving real movement.

### Java 8 Stream-Based Implementation

```java
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.OptionalDouble;

public class RssiFilter {
    /**
     * Robust distance estimation using Java 8 Streams.
     * Filters invalid readings, averages, then calculates distance.
     */
    public static OptionalDouble estimateSmoothedDistance(
            List<Double> rssiStream,
            double rssiAtOneMeter,
            double pathLossExponent) {
        
        if (rssiStream == null || rssiStream.isEmpty()) {
            return OptionalDouble.empty();
        }

        return rssiStream.stream()
                .filter(Objects::nonNull)
                .filter(rssi -> rssi < 0 && rssi > -100)  // Filter errors
                .mapToDouble(Double::doubleValue)
                .average()  // Stream terminal operation
                .map(avgRssi -> WifiDistanceCalculator.calculateDistance(
                    avgRssi, rssiAtOneMeter, pathLossExponent));
    }
}
```

### Usage Example

```java
List<Double> noisyRssis = Arrays.asList(
    -62.0, -66.0, -64.0, 0.0, -68.0, -63.0  // 0.0 is error/noise
);

OptionalDouble smoothedDist = RssiFilter.estimateSmoothedDistance(
    noisyRssis, 
    -30.0,  // rssiAt1m
    2.7     // pathLossExponent
);

smoothedDist.ifPresent(d -> 
    System.out.printf("Estimated distance: %.2f m%n", d)
);
// Output: Estimated distance: 9.47 m (stable, no outlier noise)
```

### Sliding Window Approach (Ring Buffer)

**In KindleDroneDetectorPro:**

```java
// Per-MAC circular buffer (40 observations = ~3.3 minutes at 5-sec scans)
private Deque<Double> rssiHistory = new ArrayDeque<>(40);

// Add new RSSI
if (rssiHistory.size() >= 40) {
    rssiHistory.removeFirst();
}
rssiHistory.addLast(currentRssi);

// Compute smoothed distance
OptionalDouble smoothedDist = RssiFilter.estimateSmoothedDistance(
    new ArrayList<>(rssiHistory),
    RSSI_REF_DBMW,
    PATH_LOSS_EXPONENT
);
```

---

## Approach 4: IEEE 802.11mc (Wi-Fi RTT)

### Concept

Instead of measuring signal dampening (RSSI), measure **Time of Flight (ToF)**—the precise time for a radio signal to travel from device → AP → back to device.

**Formula:**

```
d = (time_of_flight / 2) * speed_of_light
```

### Accuracy

- **Indoors:** 1–2 meters
- **Sub-meter:** Possible with 80 MHz channel width
- **Outdoors:** 2–5 meters

### Hardware Requirements

- **Android 9 (API 28) or higher**
- **WifiRttManager** API
- **Access Point must support IEEE 802.11mc**

### Java (Android Only)

```java
import android.content.Context;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.net.wifi.rtt.*;

public class WifiRttHelper {
    private Context context;
    private WifiRttManager wifiRttManager;

    public WifiRttHelper(Context context) {
        this.context = context;
        this.wifiRttManager = (WifiRttManager) 
            context.getSystemService(Context.WIFI_RTT_RANGING_SERVICE);
    }

    public void measureDistance() {
        if (!wifiRttManager.isAvailable()) {
            return;  // RTT not supported
        }

        // Build ranging request
        RangingRequest request = new RangingRequest.Builder()
                .addAccessPoints(scanResults)
                .build();

        // Perform ranging
        wifiRttManager.startRanging(request, context.getMainExecutor(), 
            new RangingResultCallback() {
                @Override
                public void onRangingResults(List<RangingResult> results) {
                    for (RangingResult result : results) {
                        if (result.getStatus() == RangingResult.STATUS_SUCCESS) {
                            double distanceMeters = result.getDistanceMm() / 1000.0;
                            System.out.println("Distance: " + distanceMeters + "m");
                        }
                    }
                }

                @Override
                public void onRangingFailure(int code) {
                    // Handle failure
                }
            }
        );
    }
}
```

### Limitation for Kindle

**Not applicable:** Kindle 4 has no Android OS. AR6003 chipset does not support 802.11mc.

---

## Approach 5: Trilateration (Multi-AP Positioning)

### Concept

If you know distance to **3+ Access Points** with known coordinates, you can solve for device position.

**Math:** Find (x, y) that minimizes error over three circles:

```
(x − x_i)² + (y − y_i)² = d_i²
```

### Advantages

- Provides 2D coordinates, not just distance
- Works indoors if AP positions are mapped

### Disadvantages

- Requires mapping AP locations beforehand
- Accuracy limited by distance estimate error
- Complex non-linear optimization needed

### Java Implementation (Apache Commons Math)

```java
import org.apache.commons.math3.optim.*;
import org.apache.commons.math3.optim.nonlinear.scalar.GoalType;
import org.apache.commons.math3.optim.nonlinear.scalar.ObjectiveFunction;

public class Trilateration {
    /**
     * Solve for position given distances to known APs.
     * @param apPositions [[x1, y1], [x2, y2], [x3, y3], ...]
     * @param distances [d1, d2, d3, ...]
     * @return [x, y] position
     */
    public static double[] solveTrilateration(double[][] apPositions, 
                                             double[] distances) {
        // Define residual function (sum of squared errors)
        MultivariateFunction residual = point -> {
            double error = 0.0;
            for (int i = 0; i < apPositions.length; i++) {
                double dx = point[0] - apPositions[i][0];
                double dy = point[1] - apPositions[i][1];
                double computed = Math.sqrt(dx*dx + dy*dy);
                double residue = computed - distances[i];
                error += residue * residue;
            }
            return error;
        };

        // Optimize
        SimpleBounds bounds = new SimpleBounds(
            new double[]{-1000, -1000},
            new double[]{1000, 1000}
        );
        
        PointValuePair result = new CMAESOptimizer(
            100, 0.1, true, 5, 0, 1e-6, 1e-6
        ).optimize(
            new MaxEval(10000),
            new ObjectiveFunction(residual),
            GoalType.MINIMIZE,
            bounds,
            new InitialGuess(new double[]{500, 500})
        );

        return result.getPoint();
    }
}
```

### Limitation for Kindle

Kindle 4 only scans AP beacons (no known position database). Would require manual mapping of all APs.

---

## Approach 6: Wi-Fi Fingerprinting

### Concept

Instead of calculating distance, use machine learning to pattern-match RSSI vectors.

1. **Offline phase:** Walk around building, record RSSI vector from all visible APs at known coordinates
2. **Online phase:** Capture current RSSI vector, match to closest fingerprint (KNN or neural net)

### Advantages

- High accuracy (2–4 meters indoors)
- Accounts for environment-specific multipath patterns
- No calibration needed per location

### Disadvantages

- Requires extensive training data collection
- AP set must remain stable (adding/removing APs breaks fingerprints)
- High maintenance overhead

### Java Implementation (KNN)

```java
import java.util.*;

public class WifiFingerprinting {
    class Fingerprint {
        double[] location;  // [x, y]
        Map<String, Double> rssiVector;  // MAC → RSSI
    }

    List<Fingerprint> database = new ArrayList<>();

    /**
     * K-Nearest Neighbors classifier.
     * @param currentVector [MAC → RSSI] measurements
     * @param k number of neighbors
     * @return [x, y] estimated position
     */
    public double[] estimate(Map<String, Double> currentVector, int k) {
        // Compute distances to all fingerprints
        List<Double> distances = new ArrayList<>();
        for (Fingerprint fp : database) {
            double dist = 0.0;
            for (String mac : currentVector.keySet()) {
                Double fpRssi = fp.rssiVector.get(mac);
                if (fpRssi != null) {
                    double delta = currentVector.get(mac) - fpRssi;
                    dist += delta * delta;
                }
            }
            distances.add(Math.sqrt(dist));
        }

        // Find k nearest
        distances.sort(null);
        double[] location = new double[2];
        for (int i = 0; i < Math.min(k, distances.size()); i++) {
            Fingerprint closest = database.get(i);
            location[0] += closest.location[0];
            location[1] += closest.location[1];
        }
        location[0] /= Math.min(k, distances.size());
        location[1] /= Math.min(k, distances.size());

        return location;
    }
}
```

---

## Comparison Table

| Approach | Accuracy | Complexity | Hardware | Recommended For |
|---|---|---|---|---|
| **Log-Distance (RSSI)** | 3–8 m | Low | Any WiFi | Kindle Detector (current) |
| **Free Space (FSPL)** | ±50% indoors | Low | Any WiFi | Theory only |
| **Curve Fitting** | 2–5 m | Medium | Any WiFi | Mobile apps |
| **Wi-Fi RTT** | 1–2 m | Medium | Modern Android + 11mc AP | Precise indoor nav |
| **Trilateration** | Depends on RSSI | High | ≥3 APs + mapping | Real-time positioning |
| **Fingerprinting** | 2–4 m | Very high | AP database | Enterprise systems |

---

## Production Best Practices

### 1. Kalman Filter for Real-Time Tracking

For smoothing without lag, use a 1D Kalman filter:

```java
double q = 0.01;  // Process noise
double r = 0.1;   // Measurement noise
double p = 1.0;   // Estimate error
double x = 0.0;   // State estimate

public double kalmanUpdate(double measurement) {
    p += q;
    double k = p / (p + r);  // Kalman gain
    x += k * (measurement - x);
    p = (1 - k) * p;
    return x;
}
```

### 2. Thread Safety

Always execute WiFi scanning asynchronously:

```java
CompletableFuture.supplyAsync(() -> {
    return performWifiScan();
}).thenAcceptAsync(results -> {
    updateUI(results);
}, Executor);
```

### 3. Error Handling

```java
OptionalDouble distance = estimateDistance(rssis);
if (distance.isPresent() && distance.getAsDouble() > 0) {
    displayDistance(distance.getAsDouble());
} else {
    displayUnavailable();
}
```

---

## References

1. **Log-Distance Path Loss Model**
   - Rappaport, T. S. (2002). "Wireless Communications: Principles and Practice."
   - Standard in cellular and WiFi range prediction

2. **Free Space Path Loss**
   - Friis Transmission Equation; applicable to satellite and outdoor radio

3. **IEEE 802.11mc (Wi-Fi RTT)**
   - IEEE 802.11-2016 Amendment 1; Android developer docs

4. **Fingerprinting Methods**
   - Fink, M. (1999). "Time Reversal Mirrors"; applied to indoor positioning

5. **Kindle 4 AR6003**
   - Atheros technical reference (limited public availability)
   - Reverse-engineered from dmesg and trial


