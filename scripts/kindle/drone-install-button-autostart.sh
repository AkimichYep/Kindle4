#!/bin/sh
# Install Kindle boot autostart for waitforkey button listener.
# Default mapping on this device: NEXT=104, PREV=193.

LISTENER_SCRIPT="${LISTENER_SCRIPT:-/mnt/us/drone-button-waitforkey.sh}"
NEXT_CODE="${NEXT_CODE:-104}"
PREV_CODE="${PREV_CODE:-193}"
LOG_FILE="${LOG_FILE:-/mnt/us/drone-button-waitforkey.log}"
RC_LOCAL="/etc/rc.local"
INITD_SCRIPT="/etc/init.d/drone-button-listener"
RC5_LINK="/etc/rc5.d/S99drone-button-listener"

BOOT_CMD="NEXT_CODE=$NEXT_CODE PREV_CODE=$PREV_CODE $LISTENER_SCRIPT </dev/null >> $LOG_FILE 2>&1 &"

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

install_rc_local() {
  [ -f "$RC_LOCAL" ] || return 1
  grep -q 'drone-button-waitforkey.sh' "$RC_LOCAL" 2>/dev/null && return 0
  echo "$BOOT_CMD" >> "$RC_LOCAL"
  chmod +x "$RC_LOCAL"
  return 0
}

install_initd() {
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
  chmod +x "$INITD_SCRIPT"
  ln -sf "$INITD_SCRIPT" "$RC5_LINK"
}

ensure_writable_rootfs
if install_rc_local; then
  echo "Installed button listener boot hook in $RC_LOCAL"
else
  install_initd
  echo "Installed init script $INITD_SCRIPT and link $RC5_LINK"
fi
restore_readonly_rootfs

