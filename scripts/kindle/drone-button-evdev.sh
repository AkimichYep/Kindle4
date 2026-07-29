#!/bin/sh
# Kindle button listener via /dev/input/eventX.
# NEXT button -> start app
# PREV button -> stop app

INPUT_DEV="${INPUT_DEV:-/dev/input/event0}"
NEXT_CODE="${NEXT_CODE:-104}"
PREV_CODE="${PREV_CODE:-109}"
APP_JAR="${APP_JAR:-/mnt/us/drone-app-1.0.0-SNAPSHOT.jar}"
JAVA_BIN="${JAVA_BIN:-/mnt/us/java/jre/bin/java}"
APP_LOG="${APP_LOG:-/mnt/us/drone-app.log}"
LISTENER_LOG="${LISTENER_LOG:-/mnt/us/drone-button-evdev.log}"
START_SCRIPT="${START_SCRIPT:-/mnt/us/drone-start.sh}"
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
    log "app failed to start"
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

read_event() {
  # Linux input_event on 32-bit Kindle kernel is 16 bytes.
  # Fields printed as: type code value
  dd if="$INPUT_DEV" bs=16 count=1 2>/dev/null \
    | od -An -tu2 \
    | awk 'NF>=8 { printf "%s %s %s\n", $5, $6, $7 }'
}

log "evdev listener start dev=$INPUT_DEV next=$NEXT_CODE prev=$PREV_CODE"

while true; do
  EVT="$(read_event)"
  [ -n "$EVT" ] || continue

  set -- $EVT
  TYPE="$1"
  CODE="$2"
  VALUE="$3"

  # type=1 is EV_KEY; value=1 is key press.
  [ "$TYPE" = "1" ] || continue
  [ "$VALUE" = "1" ] || continue

  if [ "$CODE" = "$NEXT_CODE" ]; then
    log "NEXT code=$CODE"
    start_app
  elif [ "$CODE" = "$PREV_CODE" ]; then
    log "PREV code=$CODE"
    stop_app
  fi
done

