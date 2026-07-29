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

## Fix timezone/date drift on Kindle

If `date` shows a broken zone (for example `GMT-10:44`), persist timezone first, then sync time:

```sh
mntroot rw
echo 'EET-2EEST,M3.5.0/3,M10.5.0/4' > /var/local/system/tz
echo 'EET-2EEST,M3.5.0/3,M10.5.0/4' > /etc/TZ
rdate -s time.nist.gov
hwclock -w
mntroot ro
date
```

`drone-install-autostart.sh` and `drone-install-button-autostart.sh` now apply the same timezone value automatically.

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

### Confirmed physical layout on this Kindle (4 page-turn buttons)

| Side | Button | Key code | Action |
|---|---|---|---|
| Right edge | NEXT | `104` | `NEXT_CODE` — ensure app is running |
| Right edge | PREV | `193` | `PREV_CODE` — stop app |
| Left edge  | NEXT | `191` | `RIGHT_NEXT_CODE` — force data refresh + show next page |
| Left edge  | PREV | `109` | unmapped by default — set `PREV_ALT_CODE=109` to alias it to stop app |

> **Naming note**: the env var is still called `RIGHT_NEXT_CODE` for backward
> compatibility, even though `191` is physically the **left**-edge button on this
> device. `LEFT_NEXT_CODE` and `EXTRA_NEXT_CODES`/`EXTRA_PREV_CODES` let you wire up
> further buttons without editing the script.

Available variables:
- `NEXT_CODE` (104): ensure app is running
- `PREV_CODE` (193): stop app
- `RIGHT_NEXT_CODE` (191): force immediate data refresh and show next page
- `LEFT_NEXT_CODE` (unset by default): additional alias for the refresh+next action
- `PREV_ALT_CODE` (unset by default): additional alias for stop app, e.g. `PREV_ALT_CODE=109`
- `EXTRA_NEXT_CODES` (unset by default): space-separated extra refresh+next codes, e.g. `"55 56"`
- `EXTRA_PREV_CODES` (unset by default): space-separated extra stop-app codes

1) Discover key codes (only needed if your unit's codes differ from the table above):

```sh
while true; do /usr/bin/waitforkey; done
```

Press each button, note the two numbers printed (first is key code, second is
1=press / 0=release).

**Shortcut**: this script logs every unmapped key press, so you can also just run
the listener and press a button — look for:

```
key code=109 value=1
key code=109 unmapped (no action)
```

2) Run listener in foreground:

```sh
NEXT_CODE=104 PREV_CODE=193 RIGHT_NEXT_CODE=191 /mnt/us/drone-button-waitforkey.sh
```

3) Run listener in background:

```sh
NEXT_CODE=104 PREV_CODE=193 RIGHT_NEXT_CODE=191 /mnt/us/drone-button-waitforkey.sh </dev/null >> /mnt/us/drone-button-waitforkey.log 2>&1 &
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

Custom mapping at install time (e.g. also map the left-PREV button to stop app):

```sh
NEXT_CODE=104 PREV_CODE=193 RIGHT_NEXT_CODE=191 PREV_ALT_CODE=109 /mnt/us/drone-install-button-autostart.sh
```

**Read-only filesystem?** If you see `cannot create /etc/rc.local: Read-only file
system` etc., `mntroot rw` did not actually remount root read-write on this
firmware. The installer now detects this with a real write test (not just the
exit code of `mntroot`) and will print a clear `ERROR: root filesystem is
read-only...` instead of falsely claiming success. Fix it manually first:

```sh
mntroot rw
touch /etc/.test && echo OK && rm /etc/.test   # confirm it's actually writable
# if that still fails, try:
mount -o remount,rw /
```

Then re-run `drone-install-button-autostart.sh`.

### If `/etc/rc.local` does not exist (manual fallback, now automated by `drone-install-button-autostart.sh`)

The steps below are what the installer does for you automatically (init.d + rc5.d
fallback path). Only use this manually if the installer itself fails.

```sh
mntroot rw

cat > /etc/init.d/drone-buttons <<'EOF'
#!/bin/sh
case "$1" in
  start)
    NEXT_CODE=104 PREV_CODE=193 RIGHT_NEXT_CODE=191 /mnt/us/drone-button-waitforkey.sh </dev/null >> /mnt/us/drone-button-waitforkey.log 2>&1 &
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
- `drone-button-waitforkey.sh` opens two firewall rules automatically: one for `-i lo` (loopback,
  required because the button script talks to the app via `127.0.0.1`) and one for `-i wlan0 --dport 5555`
  (external access). If the RIGHT_NEXT button logs `wget call failed` / `refresh+next failed` even though
  the wlan0 rule is present, the `lo` rule is the missing piece — check with:
  ```sh
  iptables-save | grep -E 'lo|5555'
  ```
