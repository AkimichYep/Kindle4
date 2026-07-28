# Kindle runtime scripts

These scripts are designed for BusyBox-based Kindle shells (no `nohup`, minimal `pgrep` flags).

## Files

- `drone-start.sh` - idempotent app starter for `/mnt/us/drone-app-1.0.0-SNAPSHOT.jar`
- `drone-wake-daemon.sh` - listens for wake events and ensures app is running
- `drone-control.sh` - helper (`start|stop|restart|status|logs`)
- `drone-install-autostart.sh` - boot registration (`/etc/rc.local` or `/etc/init.d` fallback)
- `drone-button-listener.sh` - map NEXT/PREV hardware button events to start/stop app
- `drone-button-evdev.sh` - map NEXT/PREV keycodes from `/dev/input/eventX` to start/stop app
- `drone-button-waitforkey.sh` - map NEXT/PREV keycodes using `/usr/bin/waitforkey` (recommended for older Kindle BusyBox)
- `drone-install-button-autostart.sh` - boot registration for waitforkey mode (`NEXT=104`, `PREV=193` by default)

## Copy to Kindle

```sh
scp scripts/kindle/drone-*.sh root@<kindle-ip>:/mnt/us/
ssh root@<kindle-ip> "chmod +x /mnt/us/drone-*.sh"
```

## Quick start on Kindle

```sh
/mnt/us/drone-control.sh start
/mnt/us/drone-control.sh status
/mnt/us/drone-control.sh logs
```

## Enable boot autostart on Kindle

```sh
/mnt/us/drone-install-autostart.sh
```

## Button listener mode (NEXT=start, PREV=stop)

```sh
# default event names
/mnt/us/drone-button-listener.sh

# override events/service for your firmware
BTN_SERVICE=com.lab126.appmgrd NEXT_EVENT=nextPage PREV_EVENT=prevPage /mnt/us/drone-button-listener.sh
```

## Evdev button mode (recommended when LIPC has no page events)

1) Discover key codes on Kindle:

```sh
while true; do
  dd if=/dev/input/event0 bs=16 count=1 2>/dev/null | od -An -tu2 | awk 'NF>=8 && $5==1 {print "type=" $5 " code=" $6 " value=" $7}'
done
```

Press NEXT/PREV a few times and note the `code=` values where `value=1`.

2) Run listener with detected codes:

```sh
NEXT_CODE=<next_code> PREV_CODE=<prev_code> /mnt/us/drone-button-evdev.sh
```

3) Run in background:

```sh
NEXT_CODE=<next_code> PREV_CODE=<prev_code> /mnt/us/drone-button-evdev.sh </dev/null >> /mnt/us/drone-button-evdev.log 2>&1 &
```

Logs:

```sh
tail -n 50 /mnt/us/drone-button-evdev.log
tail -n 50 /mnt/us/drone-app.log
```

## Waitforkey button mode (recommended on this device)

This Kindle already uses `/usr/bin/waitforkey` inside `/opt/amazon/ebook/bin/start.sh`.
You can reuse the same tool for reliable page-button capture.

1) Discover key codes:

```sh
while true; do /usr/bin/waitforkey; done
```

Press NEXT/PREV, note the two numbers printed (first is key code).

Detected on this Kindle:
- NEXT page: `104`
- PREV page: `193`

2) Run listener in foreground:

```sh
NEXT_CODE=104 PREV_CODE=193 /mnt/us/drone-button-waitforkey.sh
```

3) Run listener in background:

```sh
NEXT_CODE=104 PREV_CODE=193 /mnt/us/drone-button-waitforkey.sh </dev/null >> /mnt/us/drone-button-waitforkey.log 2>&1 &
```

Logs:

```sh
tail -n 50 /mnt/us/drone-button-waitforkey.log
tail -n 50 /mnt/us/drone-app.log
```

## Enable this mode after reboot

```sh
/mnt/us/drone-install-button-autostart.sh
```

Custom mapping at install time:

```sh
NEXT_CODE=104 PREV_CODE=193 /mnt/us/drone-install-button-autostart.sh
```

### If `/etc/rc.local` does not exist (manual fallback you used)

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

Verify after reboot:

```sh
pgrep -fl '/mnt/us/drone-button-waitforkey.sh'
tail -n 50 /mnt/us/drone-button-waitforkey.log
```

## Notes

- Lock directories (`/mnt/us/drone-wake.lock`, `/mnt/us/drone-app.lock`) prevent duplicate starts.
- If you force-kill processes, stale locks can remain; `drone-start.sh` and `drone-wake-daemon.sh` remove stale locks automatically.
