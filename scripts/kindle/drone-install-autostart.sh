#!/bin/sh
# Install Kindle boot autostart for drone wake daemon.
# Uses /etc/rc.local when present, otherwise creates init.d symlink.

DAEMON_SCRIPT="${DAEMON_SCRIPT:-/mnt/us/drone-wake-daemon.sh}"
RC_LOCAL="/etc/rc.local"
INITD_SCRIPT="/etc/init.d/drone-wake"
RC5_LINK="/etc/rc5.d/S99drone-wake"
TZ_VALUE="${TZ_VALUE:-EET-2EEST,M3.5.0/3,M10.5.0/4}"
KINDLE_TZ_FILE="/var/local/system/tz"
ETC_TZ_FILE="/etc/TZ"

ensure_writable_rootfs() {
  if command -v mntroot >/dev/null 2>&1; then
    mntroot rw >/dev/null 2>&1
  fi
}

restore_readonly_rootfs() {
  if command -v mntroot >/dev/null 2>&1; then
    mntroot ro >/dev/null 2>&1
  fi
}

configure_timezone() {
  echo "$TZ_VALUE" > "$KINDLE_TZ_FILE" 2>/dev/null || true
  if [ -w "$ETC_TZ_FILE" ] || [ ! -e "$ETC_TZ_FILE" ]; then
    echo "$TZ_VALUE" > "$ETC_TZ_FILE" 2>/dev/null || true
  fi
}

install_rc_local() {
  [ -f "$RC_LOCAL" ] || return 1
  grep -q 'drone-wake-daemon.sh' "$RC_LOCAL" 2>/dev/null && return 0
  echo "$DAEMON_SCRIPT </dev/null >> /mnt/us/drone-app/logs/wake-daemon.log 2>&1 &" >> "$RC_LOCAL"
  chmod +x "$RC_LOCAL"
  return 0
}

install_initd() {
  cat > "$INITD_SCRIPT" <<'EOF'
#!/bin/sh
case "$1" in
  start)
    /mnt/us/drone-wake-daemon.sh </dev/null >> /mnt/us/drone-app/logs/wake-daemon.log 2>&1 &
    ;;
  stop)
    for p in $(pgrep -f '/mnt/us/drone-wake-daemon.sh'); do kill "$p" 2>/dev/null; done
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
  chmod +x "$INITD_SCRIPT"
  ln -sf "$INITD_SCRIPT" "$RC5_LINK"
}

ensure_writable_rootfs
configure_timezone
if install_rc_local; then
  echo "Installed boot hook in $RC_LOCAL"
else
  install_initd
  echo "Installed init script $INITD_SCRIPT and link $RC5_LINK"
fi
restore_readonly_rootfs

