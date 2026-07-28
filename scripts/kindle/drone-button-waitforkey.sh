#!/bin/sh
# Kindle button listener using /usr/bin/waitforkey.
# NEXT page -> start app
# PREV page -> stop app
# Default mapping confirmed on this Kindle: NEXT=104, PREV=193.

GET_KEY_CODES="${GET_KEY_CODES:-/usr/bin/waitforkey}"
NEXT_CODE="${NEXT_CODE:-104}"
PREV_CODE="${PREV_CODE:-193}"
APP_JAR="${APP_JAR:-/mnt/us/drone-app-1.0.0-SNAPSHOT.jar}"
JAVA_BIN="${JAVA_BIN:-/mnt/us/java/jre/bin/java}"
APP_LOG="${APP_LOG:-/mnt/us/drone-app.log}"
LISTENER_LOG="${LISTENER_LOG:-/mnt/us/drone-button-waitforkey.log}"
IPTABLES_BIN="${IPTABLES_BIN:-/usr/sbin/iptables}"
IPTABLES_SAVE_BIN="${IPTABLES_SAVE_BIN:-/usr/sbin/iptables-save}"
LISTENER_PORT="${LISTENER_PORT:-5555}"

log() {
  echo "$(date): $*" >> "$LISTENER_LOG"
}

ensure_firewall_rule() {
  if [ ! -x "$IPTABLES_BIN" ] || [ ! -x "$IPTABLES_SAVE_BIN" ]; then
    log "iptables tools missing, skip firewall open"
    return 0
  fi

  if "$IPTABLES_SAVE_BIN" 2>/dev/null | grep -q -- "-A INPUT -i wlan0 -p tcp -m tcp --dport $LISTENER_PORT -j ACCEPT"; then
    log "firewall rule already present for tcp/$LISTENER_PORT"
    return 0
  fi

  if "$IPTABLES_BIN" -I INPUT 1 -i wlan0 -p tcp -m tcp --dport "$LISTENER_PORT" -j ACCEPT; then
    log "firewall rule added for tcp/$LISTENER_PORT"
  else
    log "failed to add firewall rule for tcp/$LISTENER_PORT"
  fi
}

start_app() {
  ensure_firewall_rule

  if pgrep -f 'drone-app-1.0.0-SNAPSHOT.jar' >/dev/null 2>&1; then
    log "start ignored (already running)"
    return 0
  fi

  "$JAVA_BIN" -jar "$APP_JAR" </dev/null >> "$APP_LOG" 2>&1 &
  sleep 1

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

if [ ! -x "$GET_KEY_CODES" ]; then
  echo "Missing executable: $GET_KEY_CODES" >&2
  exit 1
fi

log "waitforkey listener start next=$NEXT_CODE prev=$PREV_CODE"

while true; do
  KEY_CODES="$($GET_KEY_CODES 2>/dev/null)"
  set -- $KEY_CODES
  KEY_CODE="$1"
  KEY_VALUE="$2"

  [ -n "$KEY_CODE" ] || continue
  log "key code=$KEY_CODE value=$KEY_VALUE"

  if [ "$KEY_CODE" = "$NEXT_CODE" ]; then
    start_app
  elif [ "$KEY_CODE" = "$PREV_CODE" ]; then
    stop_app
  fi
done
