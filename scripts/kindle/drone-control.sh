#!/bin/sh
# Simple control helper for Kindle drone daemon/app.

APP_DIR="${APP_DIR:-/mnt/us/drone-app}"
DAEMON_SCRIPT="${DAEMON_SCRIPT:-/mnt/us/drone-wake-daemon.sh}"
DAEMON_LOG="${DAEMON_LOG:-$APP_DIR/logs/wake-daemon.log}"
APP_LOG="${APP_LOG:-$APP_DIR/logs/app.log}"
DAEMON_LOCK_PID_FILE="${DAEMON_LOCK_PID_FILE:-/tmp/drone-wake.lock/pid}"
APP_PID_FILE="${APP_PID_FILE:-$APP_DIR/drone-app.pid}"

is_alive() {
  [ -n "$1" ] && kill -0 "$1" 2>/dev/null
}

pid_from_file() {
  [ -f "$1" ] && cat "$1" 2>/dev/null
}

start() {
  "$DAEMON_SCRIPT" </dev/null >> "$DAEMON_LOG" 2>&1 &
  sleep 1
  status
}

stop() {
  DAEMON_PID="$(pid_from_file "$DAEMON_LOCK_PID_FILE")"
  if is_alive "$DAEMON_PID"; then
    kill "$DAEMON_PID" 2>/dev/null
  fi

  for p in $(pgrep -f '/mnt/us/drone-wake-daemon.sh'); do
    kill "$p" 2>/dev/null
  done

  APP_PID="$(pid_from_file "$APP_PID_FILE")"
  if is_alive "$APP_PID"; then
    kill "$APP_PID" 2>/dev/null
  fi

  # Fallback in case PID file is stale.
  for p in $(pgrep -f 'drone-app-2.0.0-SNAPSHOT.jar'); do
    kill "$p" 2>/dev/null
  done

  sleep 1
  status
}

status() {
  echo "--- daemon ---"
  pgrep -fl '/mnt/us/drone-wake-daemon.sh' 2>/dev/null || echo "not running"
  echo "--- app ---"
  pgrep -fl 'drone-app-2.0.0-SNAPSHOT.jar' 2>/dev/null || echo "not running"
}

logs() {
  echo "=== daemon log (last 40) ==="
  tail -n 40 "$DAEMON_LOG" 2>/dev/null
  echo "=== app log (last 40) ==="
  tail -n 40 "$APP_LOG" 2>/dev/null
}

case "$1" in
  start) start ;;
  stop) stop ;;
  restart) stop; start ;;
  status) status ;;
  logs) logs ;;
  *)
    echo "Usage: $0 {start|stop|restart|status|logs}"
    exit 1
    ;;
esac

