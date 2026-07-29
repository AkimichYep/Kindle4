#!/bin/sh
# Kindle button listener:
# - NEXT page button starts drone app
# - PREV page button stops drone app
#
# Event names vary by Kindle firmware. Defaults can be overridden:
#   BTN_SERVICE=com.lab126.appmgrd
#   NEXT_EVENT=nextPage
#   PREV_EVENT=prevPage

APP_JAR="${APP_JAR:-/mnt/us/drone-app-1.0.0-SNAPSHOT.jar}"
JAVA_BIN="${JAVA_BIN:-/mnt/us/java/jre/bin/java}"
APP_LOG="${APP_LOG:-/mnt/us/drone-app.log}"
LISTENER_LOG="${LISTENER_LOG:-/mnt/us/drone-button-listener.log}"
START_SCRIPT="${START_SCRIPT:-/mnt/us/drone-start.sh}"
BTN_SERVICE="${BTN_SERVICE:-com.lab126.appmgrd}"
NEXT_EVENT="${NEXT_EVENT:-nextPage}"
PREV_EVENT="${PREV_EVENT:-prevPage}"
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

log() {
  echo "$(date): $*" >> "$LISTENER_LOG"
}

start_app() {
  sh "$START_SCRIPT"
  if pgrep -f 'drone-app-1.0.0-SNAPSHOT.jar' >/dev/null 2>&1; then
    log "app started"
  else
    log "app start failed"
  fi
}

stop_app() {
  PIDS="$(pgrep -f 'drone-app-1.0.0-SNAPSHOT.jar')"
  if [ -z "$PIDS" ]; then
    log "stop ignored (not running)"
    return 0
  fi

  for p in $PIDS; do
    kill "$p" 2>/dev/null
  done
  sleep 1

  if pgrep -f 'drone-app-1.0.0-SNAPSHOT.jar' >/dev/null 2>&1; then
    log "app still running after SIGTERM"
  else
    log "app stopped"
  fi
}

wait_and_handle() {
  EVENT_NAME="$1"
  ACTION="$2"

  while true; do
    if lipc-wait-event -m "$BTN_SERVICE" -s "$EVENT_NAME" >/dev/null 2>&1; then
      log "event=$EVENT_NAME action=$ACTION"
      if [ "$ACTION" = "start" ]; then
        start_app
      else
        stop_app
      fi
    else
      sleep 1
    fi
  done
}

log "listener starting service=$BTN_SERVICE next=$NEXT_EVENT prev=$PREV_EVENT"

wait_and_handle "$NEXT_EVENT" start &
NEXT_WATCH_PID=$!
wait_and_handle "$PREV_EVENT" stop &
PREV_WATCH_PID=$!

trap 'kill "$NEXT_WATCH_PID" "$PREV_WATCH_PID" 2>/dev/null; log "listener stopped"; exit 0' INT TERM HUP

wait

