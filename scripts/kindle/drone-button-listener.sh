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
BTN_SERVICE="${BTN_SERVICE:-com.lab126.appmgrd}"
NEXT_EVENT="${NEXT_EVENT:-nextPage}"
PREV_EVENT="${PREV_EVENT:-prevPage}"

log() {
  echo "$(date): $*" >> "$LISTENER_LOG"
}

start_app() {
  if pgrep -f 'drone-app-1.0.0-SNAPSHOT.jar' >/dev/null 2>&1; then
    log "start ignored (already running)"
    return 0
  fi

  "$JAVA_BIN" -jar "$APP_JAR" </dev/null >> "$APP_LOG" 2>&1 &
  sleep 1

  if pgrep -f 'drone-app-1.0.0-SNAPSHOT.jar' >/dev/null 2>&1; then
    log "app started"
    return 0
  fi

  log "app start failed"
  return 1
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

