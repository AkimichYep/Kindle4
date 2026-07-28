# Kindle Drone Script Changes

Date: 2026-07-28

## What was changed

- Added `drone-start.sh` for idempotent app startup (`/mnt/us/java/jre/bin/java -jar /mnt/us/drone-app-1.0.0-SNAPSHOT.jar`).
- Added `drone-wake-daemon.sh` and lock-based process protection for wake-event mode.
- Added `drone-control.sh` (`start|stop|restart|status|logs`) for simpler operations.
- Added `drone-button-listener.sh` (LIPC event mode).
- Added `drone-button-evdev.sh` (raw input event mode for devices with `od` available).
- Added `drone-button-waitforkey.sh` (recommended mode on this Kindle).
- Added `drone-install-autostart.sh` (boot hook for wake daemon mode).
- Added `drone-install-button-autostart.sh` (boot hook for waitforkey mode).
- Updated `README.md` with tested key mapping and reboot setup.

## Confirmed working mapping on this Kindle

- NEXT page button -> key code `104` -> start app
- PREV page button -> key code `193` -> stop app

## How the final working mode operates

Script: `drone-button-waitforkey.sh`

1. Blocks on `/usr/bin/waitforkey` and receives `KEY_CODE KEY_VALUE`.
2. Logs all button inputs to `/mnt/us/drone-button-waitforkey.log`.
3. If key code is `104`, starts the app (only if not already running).
4. If key code is `193`, sends `SIGTERM` to the app process.
5. App output goes to `/mnt/us/drone-app.log`.

## Reboot persistence

Run once on Kindle:

```sh
/mnt/us/drone-install-button-autostart.sh
```

This writes boot startup into `/etc/rc.local` when present, otherwise installs an init script symlink in `/etc/rc5.d/`.

### Manual fallback used on this firmware (`/etc/rc.local` missing)

```sh
mntroot rw

cat > /etc/init.d/drone-buttons <<'EOF'
#!/bin/sh
case "$1" in
  start)
    NEXT_CODE=104 PREV_CODE=193 /mnt/us/drone-button-waitforkey.sh </dev/null >> /mnt/us/drone-button-waitforkey.log 2>&1 &
    ;;
  stop)
    for p in $(pgrep -f '/mnt/us/drone-button-waitforkey.sh'); do kill "$p" 2>/dev/null; done
    ;;
  restart)
    "$0" stop
    sleep 1
    "$0" start
    ;;
  *)
    echo "Usage: $0 {start|stop|restart}"
    exit 1
    ;;
esac
exit 0
EOF

chmod +x /etc/init.d/drone-buttons
ln -sf /etc/init.d/drone-buttons /etc/rc5.d/S99drone-buttons

mntroot ro
```

This is exactly the fallback path used when `rc.local` is unavailable.

## Runtime commands

```sh
# Start listener now
NEXT_CODE=104 PREV_CODE=193 /mnt/us/drone-button-waitforkey.sh </dev/null >> /mnt/us/drone-button-waitforkey.log 2>&1 &

# Check listener/app
pgrep -fl '/mnt/us/drone-button-waitforkey.sh'
pgrep -fl 'drone-app-1.0.0-SNAPSHOT.jar'

# Logs
tail -n 50 /mnt/us/drone-button-waitforkey.log
tail -n 50 /mnt/us/drone-app.log
```
