# Kindle Drone Script Changes

Date: 2026-08-24 (Date-Time Transition View & Scanline Animation)

## What was changed

- **New Date & Time Transition View (`time`)**: Added a 10-second transition screen between auto-rotating display pages showing local day of week, giant `HH:MM` time in `@` block characters, date, and daily funny drone quotes.
- **Low-Power Text-Only Rendering**: Rendered via native Kindle `eips` framebuffer commands rather than generating PNG images, saving CPU cycles, RAM, and e-ink flash wear.
- **Animated Row-by-Row Reveal**: Displays line-by-line top-to-bottom with a 60 ms per-row delay for a retro CRT scanline effect (~2.4s total reveal animation).
- **System Timezone Sync**: Added `KindleUtils.syncSystemTimeZone()` at app startup to compare `date +%H` from system shell with Java UTC hour and set `TimeZone.setDefault()`, fixing EEST (+0300) daylight saving offset discrepancies on embedded JVMs.
- **Full Screen Clear Fix**: Fixed an issue where transitioning from PNG pages left residual pixels by ensuring `display.clearForPageSwitch()` resets display caches and clears the screen prior to text/time rendering.

---

Date: 2026-07-29 (button mapping fix + install-script hardening)

## What was changed

- Confirmed the real physical button layout has **4** page-turn buttons, not 2:
  right-NEXT=104, right-PREV=193, left-NEXT=191, left-PREV=109. Previously
  `RIGHT_NEXT_CODE` default (191) and the install script's default (109) didn't
  match — that mismatch was actually two *different real buttons*, not a typo,
  but it meant the "refresh + next page" action silently used the wrong code
  whenever the installer's default was used without an explicit override.
- `drone-button-waitforkey.sh`: generalized button-to-action mapping via a new
  `code_in_list()` helper. `PREV_CODE`/`PREV_ALT_CODE`/`EXTRA_PREV_CODES` all
  trigger `stop_app`; `RIGHT_NEXT_CODE`/`LEFT_NEXT_CODE`/`EXTRA_NEXT_CODES` all
  trigger `trigger_refresh_and_next_page`. Any future button just needs an env
  var, no code changes.
- `drone-button-waitforkey.sh`: unmapped key codes are now logged
  (`key code=X unmapped (no action)`), turning every stray button press into
  free key-code discovery — no separate `waitforkey` loop needed.
- `drone-button-waitforkey.sh`: `ensure_firewall_rule()` now also opens `-i lo`
  (loopback) in addition to `-i wlan0 --dport 5555`. The button script talks to
  the app via `127.0.0.1`, which routes over `lo` — without this rule, a
  default-DROP `INPUT` policy silently blocked local `wget`/`nc` calls even
  though the wlan0 rule was already present (root cause of `wget call failed` /
  `refresh+next failed` in the logs).
- `drone-install-button-autostart.sh`: **fixed a false-success bug**. The old
  `install_rc_local()` always executed `return 0` regardless of whether the
  `echo >> /etc/rc.local` / `chmod` actually succeeded, so on a truly read-only
  rootfs (when `mntroot rw` silently no-ops) the script printed "Installed
  button listener boot hook in /etc/rc.local" even though nothing was written.
  Now:
  - `ensure_writable_rootfs()` verifies write access with a real test-file write
    (not just the exit code of `mntroot rw`), and also tries
    `mount -o remount,rw /` as a fallback.
  - `install_rc_local()` / `install_initd()` only return success if the file
    write is verified afterwards (`grep`/`-s`/`-x` checks).
  - The script now hard-fails with a clear `ERROR: root filesystem is
    read-only...` message (and non-zero exit code) instead of lying about
    success.

## Confirmed working mapping on this Kindle

| Side | Button | Key code | Action |
|---|---|---|---|
| Right | NEXT | `104` | start app |
| Right | PREV | `193` | stop app |
| Left | NEXT | `191` | refresh data + show next page (`RIGHT_NEXT_CODE` env var, kept for compat) |
| Left | PREV | `109` | unmapped by default; `PREV_ALT_CODE=109` to alias to stop app |

## How the final working mode operates

Script: `drone-button-waitforkey.sh`

1. Blocks on `/usr/bin/waitforkey` and receives `KEY_CODE KEY_VALUE`.
2. Logs all button inputs to `/mnt/us/drone-button-waitforkey.log`.
3. If key code is `104`, starts the app (only if not already running).
4. If key code is `193` (or `PREV_ALT_CODE`/`EXTRA_PREV_CODES`), sends `SIGTERM` to the app process.
5. If key code is `191` (or `LEFT_NEXT_CODE`/`EXTRA_NEXT_CODES`), opens firewall rules
   (lo + wlan0), ensures the app is running, then calls
   `http://127.0.0.1:5555/control/next-refresh` (wget, falling back to nc) to make
   the app re-fetch data and advance to the next display page.
6. Any other key code is logged as `unmapped (no action)`.
7. App output goes to `/mnt/us/drone-app.log`.

## Reboot persistence

Run once on Kindle:

```sh
NEXT_CODE=104 PREV_CODE=193 RIGHT_NEXT_CODE=191 PREV_ALT_CODE=109 /mnt/us/drone-install-button-autostart.sh
```

This writes boot startup into `/etc/rc.local` when present and writable, otherwise
installs an init script symlink in `/etc/rc5.d/`. If the rootfs cannot be remounted
read-write at all, the script now exits with an error instead of a false success —
see README.md's "Read-only filesystem?" troubleshooting section.

## Runtime commands

```sh
# Start listener now
NEXT_CODE=104 PREV_CODE=193 RIGHT_NEXT_CODE=191 PREV_ALT_CODE=109 /mnt/us/drone-button-waitforkey.sh </dev/null >> /mnt/us/drone-button-waitforkey.log 2>&1 &

# Check listener/app
pgrep -fl '/mnt/us/drone-button-waitforkey.sh'
pgrep -fl 'drone-app-1.0.0-SNAPSHOT.jar'

# Logs
tail -n 50 /mnt/us/drone-button-waitforkey.log
tail -n 50 /mnt/us/drone-app.log

# Verify firewall rules
iptables-save | grep -E 'lo|5555'
```
