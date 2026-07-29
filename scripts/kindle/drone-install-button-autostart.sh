#!/bin/sh
# Install Kindle boot autostart for waitforkey button listener.
#
# Confirmed physical layout on this Kindle:
#   Right edge - NEXT: 104   Right edge - PREV: 193
#   Left  edge - NEXT: 191   Left  edge - PREV: 109 (optional, unmapped by default)

LISTENER_SCRIPT="${LISTENER_SCRIPT:-/mnt/us/drone-button-waitforkey.sh}"
NEXT_CODE="${NEXT_CODE:-104}"
PREV_CODE="${PREV_CODE:-193}"
RIGHT_NEXT_CODE="${RIGHT_NEXT_CODE:-191}"
LEFT_NEXT_CODE="${LEFT_NEXT_CODE:-}"
PREV_ALT_CODE="${PREV_ALT_CODE:-}"
EXTRA_NEXT_CODES="${EXTRA_NEXT_CODES:-}"
EXTRA_PREV_CODES="${EXTRA_PREV_CODES:-}"
LOG_FILE="${LOG_FILE:-/mnt/us/drone-button-waitforkey.log}"
RC_LOCAL="/etc/rc.local"
INITD_SCRIPT="/etc/init.d/drone-button-listener"
RC5_LINK="/etc/rc5.d/S99drone-button-listener"
TZ_VALUE="${TZ_VALUE:-EET-2EEST,M3.5.0/3,M10.5.0/4}"
KINDLE_TZ_FILE="/var/local/system/tz"
ETC_TZ_FILE="/etc/TZ"
RW_TEST_FILE="/etc/.drone-rw-test.$$"

BOOT_CMD="NEXT_CODE=$NEXT_CODE PREV_CODE=$PREV_CODE RIGHT_NEXT_CODE=$RIGHT_NEXT_CODE LEFT_NEXT_CODE=$LEFT_NEXT_CODE PREV_ALT_CODE=$PREV_ALT_CODE EXTRA_NEXT_CODES=\"$EXTRA_NEXT_CODES\" EXTRA_PREV_CODES=\"$EXTRA_PREV_CODES\" $LISTENER_SCRIPT </dev/null >> $LOG_FILE 2>&1 &"

# 0 = not writable, 1 = writable (verified with an actual file write, not just
# the exit code of the remount command, since mntroot can be a no-op/missing).
ROOTFS_WRITABLE=0

ensure_writable_rootfs() {
  if command -v mntroot >/dev/null 2>&1; then
    mntroot rw >/dev/null 2>&1
  fi
  # Fallback in case mntroot is missing or didn't actually remount this firmware.
  mount -o remount,rw / >/dev/null 2>&1

  if ( : > "$RW_TEST_FILE" ) 2>/dev/null; then
    rm -f "$RW_TEST_FILE" 2>/dev/null
    ROOTFS_WRITABLE=1
  else
    ROOTFS_WRITABLE=0
  fi
}

restore_readonly_rootfs() {
  [ "$ROOTFS_WRITABLE" = "1" ] || return 0
  if command -v mntroot >/dev/null 2>&1; then
    mntroot ro >/dev/null 2>&1
  fi
}

configure_timezone() {
  if [ "$ROOTFS_WRITABLE" != "1" ]; then
    echo "WARN: rootfs read-only, skipping timezone persistence" >&2
    return 0
  fi
  echo "$TZ_VALUE" > "$KINDLE_TZ_FILE" 2>/dev/null || true
  if [ -w "$ETC_TZ_FILE" ] || [ ! -e "$ETC_TZ_FILE" ]; then
    echo "$TZ_VALUE" > "$ETC_TZ_FILE" 2>/dev/null || true
  fi
}

# Returns 0 only if the boot hook line is verifiably present in rc.local
# afterwards — never claims success on a failed/read-only write.
install_rc_local() {
  [ "$ROOTFS_WRITABLE" = "1" ] || return 1
  [ -f "$RC_LOCAL" ] || return 1
  grep -q 'drone-button-waitforkey.sh' "$RC_LOCAL" 2>/dev/null && return 0
  echo "$BOOT_CMD" >> "$RC_LOCAL" 2>/dev/null
  chmod +x "$RC_LOCAL" 2>/dev/null
  grep -q 'drone-button-waitforkey.sh' "$RC_LOCAL" 2>/dev/null
}

# Returns 0 only if the init script and symlink were verifiably created.
install_initd() {
  [ "$ROOTFS_WRITABLE" = "1" ] || return 1
  cat > "$INITD_SCRIPT" <<EOF
#!/bin/sh
case "\$1" in
  start)
    $BOOT_CMD
    ;;
  stop)
    for p in \$(pgrep -f '/mnt/us/drone-button-waitforkey.sh'); do kill "\$p" 2>/dev/null; done
    ;;
  restart)
    "\$0" stop
    sleep 1
    "\$0" start
    ;;
  *)
    echo "Usage: \$0 {start|stop|restart}"
    exit 1
    ;;
esac
exit 0
EOF
  [ -s "$INITD_SCRIPT" ] || return 1
  chmod +x "$INITD_SCRIPT" 2>/dev/null
  ln -sf "$INITD_SCRIPT" "$RC5_LINK" 2>/dev/null
  [ -x "$INITD_SCRIPT" ] && [ -e "$RC5_LINK" ]
}

ensure_writable_rootfs
configure_timezone

if [ "$ROOTFS_WRITABLE" != "1" ]; then
  echo "ERROR: root filesystem is read-only and could not be remounted rw." >&2
  echo "  Tried: mntroot rw   and   mount -o remount,rw /" >&2
  echo "  Verify manually:  mntroot rw ; touch /etc/.test && echo OK && rm /etc/.test" >&2
  echo "Boot autostart was NOT installed. Re-run this script after fixing rootfs mount." >&2
  exit 1
fi

if install_rc_local; then
  echo "Installed button listener boot hook in $RC_LOCAL"
elif install_initd; then
  echo "Installed init script $INITD_SCRIPT and link $RC5_LINK"
else
  echo "ERROR: failed to install boot hook (rc.local and init.d writes both failed)." >&2
  restore_readonly_rootfs
  exit 1
fi

restore_readonly_rootfs

