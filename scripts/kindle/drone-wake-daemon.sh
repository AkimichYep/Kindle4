#!/bin/sh
# Wake daemon: keep drone app alive and restart on Kindle wake events.

APP_DIR="${APP_DIR:-/mnt/us/drone-app}"
START_SCRIPT="${START_SCRIPT:-/mnt/us/drone-start.sh}"
LOG_FILE="${LOG_FILE:-$APP_DIR/logs/wake-daemon.log}"
DAEMON_LOCK_DIR="${DAEMON_LOCK_DIR:-/tmp/drone-wake.lock}"
DAEMON_LOCK_PID_FILE="$DAEMON_LOCK_DIR/pid"
TZ_FILE="${TZ_FILE:-/var/local/system/tz}"
DEFAULT_TZ="${DEFAULT_TZ:-EET-2EEST,M3.5.0/3,M10.5.0/4}"

if [ -z "$TZ" ]; then
  if [ -s "$TZ_FILE" ]; then
    TZ="$(cat "$TZ_FILE" 2>/dev/null)"
  else
    TZ="$DEFAULT_TZ"
  fi
  export TZ
fi

is_alive() {
  [ -n "$1" ] && kill -0 "$1" 2>/dev/null
}

read_pid_file() {
  [ -f "$1" ] && cat "$1" 2>/dev/null
}

acquire_lock() {
  if mkdir "$DAEMON_LOCK_DIR" 2>/dev/null; then
    echo $$ > "$DAEMON_LOCK_PID_FILE"
    return 0
  fi

  LOCK_PID="$(read_pid_file "$DAEMON_LOCK_PID_FILE")"
  if is_alive "$LOCK_PID"; then
    return 1
  fi

  rm -f "$DAEMON_LOCK_PID_FILE" 2>/dev/null
  rmdir "$DAEMON_LOCK_DIR" 2>/dev/null
  if mkdir "$DAEMON_LOCK_DIR" 2>/dev/null; then
    echo $$ > "$DAEMON_LOCK_PID_FILE"
    return 0
  fi

  return 1
}

cleanup() {
  rm -f "$DAEMON_LOCK_PID_FILE" 2>/dev/null
  rmdir "$DAEMON_LOCK_DIR" 2>/dev/null
  exit 0
}

acquire_lock || exit 0
trap cleanup INT TERM HUP

open_ports() {
  iptables -C INPUT -p tcp --dport 8080 -j ACCEPT 2>/dev/null || \
    iptables -I INPUT -p tcp --dport 8080 -j ACCEPT 2>/dev/null || true
}

echo "$(date): daemon started pid=$$" >> "$LOG_FILE"
open_ports
"$START_SCRIPT"

while true; do
  open_ports
  if command -v lipc-wait-event >/dev/null 2>&1; then
    if lipc-wait-event -m com.lab126.powerd -s outOfScreenSaver >/dev/null 2>&1; then
      echo "$(date): outOfScreenSaver -> ensure app running" >> "$LOG_FILE"
      "$START_SCRIPT"
    else
      # Avoid spin loops if event name/syntax differs on firmware.
      sleep 5
      "$START_SCRIPT"
    fi
  else
    # Fallback mode if lipc-wait-event is unavailable.
    sleep 15
    "$START_SCRIPT"
  fi
done

