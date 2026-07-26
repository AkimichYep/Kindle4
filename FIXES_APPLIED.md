naf0c# Kindle Display Fixes - July 26, 2026

## Issues Fixed

### 1. White Page on Kindle Display (CRITICAL)
**Problem**: Files were generated correctly but Kindle showed only a white page.

**Root Cause**: The code was calling `eips -f` (full E-ink waveform refresh) **after** `eips -g` (load grayscale image). This caused two independent panel refreshes:
- First: `eips -g` loads the image and triggers a refresh
- Second: `eips -f` independently triggers a full-waveform refresh that starts with a white flash, reading from a partially-committed framebuffer state, resulting in a blank white screen

**Fix Applied** (KindleDroneDetectorPro.java):
- Removed all `eips -f` calls after `eips -g`
- `eips -g` is self-contained and already triggers the panel refresh internally
- Increased post-load sleep from 200 ms → 500 ms to allow E-ink panel to complete rendering

---

### 2. Image Format Not Recognized by eips (CRITICAL)
**Problem**: Error `"cannot open radar0.pgm: unknown image type"` when trying to display radar image.

**Root Cause**: The code generated P5 (binary 8-bit grayscale) PGM format, but Kindle 4's `eips` tool **only** recognizes P4 (1-bit monochrome) PBM format. The tool failed to parse the P5 header.

**Fix Applied** (RadarRenderer.java):
- Changed `writePgm()` method to output P4 (PBM - Portable BitMap) format:
  - Threshold: pixels ≤ 127 (dark) → 1-bit, pixels > 127 (light) → 0-bit
  - Pack 8 bits per byte, MSB-first order
  - Write P4 header: `"P4\n" + W + " " + H + "\n"` 
  - File size reduced from ~480 KB (8-bit) to ~60 KB (1-bit packed)

**Command Changes**:
- Old: `eips -g radar.pgm` (grayscale format)
- New: `eips radar.pgm` (auto-detects P4 format from header)

---

### 3. Process Deadlock from Unflushed Pipes (IMPORTANT)
**Problem**: Silent process hangs if `eips` output exceeds OS pipe buffer (64 KB).

**Root Cause**: `KindleUtils.exec()` called `waitFor()` without draining stdout/stderr. If a subprocess writes >64 KB to stdout/stderr without the parent reading, the subprocess blocks on a full pipe, and `waitFor()` deadlocks forever waiting for exit.

**Fix Applied** (KindleUtils.java):
- Added two daemon threads that drain stdout and stderr in the background
- Uses Java 8-compatible byte-array read loop (not `transferTo()` or `nullOutputStream()`)
- Threads start before `waitFor()` is called
- Prevents any subprocess from blocking on pipe buffer full

---

## Files Modified

### 1. KindleUtils.java
- Rewrote `exec(String... cmd)` to spawn daemon drain threads
- Added new `drain(InputStream)` helper method

### 2. RadarRenderer.java
- Rewrote `writePgm(byte[] px)` to generate P4 format instead of P5
- Changed from 480 KB 8-bit grayscale to ~60 KB 1-bit monochrome
- Added bit-packing logic with proper MSB-first byte layout

### 3. KindleDroneDetectorPro.java
- Updated `renderRadarImage()`: removed `eips -f`, changed `eips -g` to `eips`
- Updated `showRadarAnimFrame()`: removed `-g` flag
- Increased sleep delays to allow panel refresh (200→300 ms, then 500 ms post-load)
- Updated Javadoc to explain why `eips -f` is harmful

---

## Technical Details

### PBM P4 Format Reference
```
P4
600 800
[packed binary data: 60000 bytes = 75000 bytes/row × 800 rows]
```
- Each row: `(600 + 7) / 8 = 75` bytes of packed bits
- Bit packing: MSB first, so bit 7 of byte 0 = pixel 0, bit 0 of byte 0 = pixel 7
- Pixel value: 1 = black (dark), 0 = white (light)
- Threshold at 128 maps 8-bit grayscale to binary

### E-ink Refresh Sequence (Fixed)
```
eips -c                         # Clear text ghosting
sleep 300 ms
eips /mnt/us/radar.pgm         # Load and refresh (self-contained)
sleep 500 ms                   # Let panel render
[show on screen for ~50 seconds, cycling through 3 animation frames]
eips -c                         # Restore text HUD
```

---

## Build Status
✅ **BUILD SUCCESS** (July 26, 2026 12:40:47)
- Maven clean compile: **SUCCESS**
- Maven package: **SUCCESS**
- Generated JAR: `target/drone-1.0.0-SNAPSHOT.jar`

---

## Testing Recommendations

1. **Test P4 Format Display**:
   ```bash
   eips /mnt/us/radar.pgm
   # Should display binary radar image (black dots on white background)
   ```

2. **Test Text HUD Restoration**:
   - Monitor radar animation for full duration (~50 sec)
   - Verify text HUD returns automatically without glitches

3. **Monitor for Process Hangs**:
   - Run for extended period (several hours)
   - Watch for display freezes (would indicate process deadlock)

4. **Validate Radar Accuracy**:
   - Verify AP positions match text display
   - Check range rings are visible and labeled
   - Confirm legend rows display correctly


