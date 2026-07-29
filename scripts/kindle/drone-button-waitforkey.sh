#!/bin/sh
# Kindle button listener using /usr/bin/waitforkey.
#
# Confirmed physical layout on this Kindle (4 page-turn buttons total):
#   Right edge - NEXT: 104 -> start_app  (ensure app is running)
#   Right edge - PREV: 193 -> stop_app
#   Left  edge - NEXT: 191 -> trigger_refresh_and_next_page (get data + next page)
#   Left  edge - PREV: 109 -> unmapped by default; set PREV_ALT_CODE=109 to make
#                              it act as an alias for stop_app.
#
# NOTE: the env var is still named RIGHT_NEXT_CODE for backward compatibility
# with earlier installs/log greps, even though 191 is physically the LEFT
# edge button on this device. LEFT_NEXT_CODE/EXTRA_NEXT_CODES/EXTRA_PREV_CODES
# exist so you can wire up further buttons without touching the logic below.

GET_KEY_CODES="${GET_KEY_CODES:-/usr/bin/waitforkey}"
NEXT_CODE="${NEXT_CODE:-104}"
PREV_CODE="${PREV_CODE:-193}"
RIGHT_NEXT_CODE="${RIGHT_NEXT_CODE:-191}"
LEFT_NEXT_CODE="${LEFT_NEXT_CODE:-}"
PREV_ALT_CODE="${PREV_ALT_CODE:-}"
# Space-separated lists of any further key codes that should behave like
# RIGHT_NEXT_CODE / PREV_CODE, without touching the dispatch logic below.
EXTRA_NEXT_CODES="${EXTRA_NEXT_CODES:-}"
EXTRA_PREV_CODES="${EXTRA_PREV_CODES:-}"
APP_JAR="${APP_JAR:-/mnt/us/drone-app-1.0.0-SNAPSHOT.jar}"
JAVA_BIN="${JAVA_BIN:-/mnt/us/java/jre/bin/java}"
APP_LOG="${APP_LOG:-/mnt/us/drone-app.log}"
LISTENER_LOG="${LISTENER_LOG:-/mnt/us/drone-button-waitforkey.log}"
IPTABLES_BIN="${IPTABLES_BIN:-/usr/sbin/iptables}"
IPTABLES_SAVE_BIN="${IPTABLES_SAVE_BIN:-/usr/sbin/iptables-save}"
LISTENER_PORT="${LISTENER_PORT:-5555}"
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

# Returns 0 (true) if $1 matches any of the remaining space-separated codes.
# Empty/unset codes (e.g. LEFT_NEXT_CODE when not configured) are ignored.
code_in_list() {
  needle="$1"
  shift
  for candidate in "$@"; do
    [ -n "$candidate" ] || continue
    [ "$candidate" = "$needle" ] && return 0
  done
  return 1
}

ensure_firewall_rule() {
  if [ ! -x "$IPTABLES_BIN" ] || [ ! -x "$IPTABLES_SAVE_BIN" ]; then
    log "iptables tools missing, skip firewall open"
    return 0
  fi

  # Loopback rule: the button script talks to the app via 127.0.0.1, which
  # routes over the "lo" interface, not "wlan0". Without this, a default
  # DROP policy silently blocks the local wget/nc call even though the
  # wlan0 rule below is present.
  if "$IPTABLES_SAVE_BIN" 2>/dev/null | grep -q -- "-A INPUT -i lo -j ACCEPT"; then
    log "firewall rule already present for lo"
  elif "$IPTABLES_BIN" -I INPUT 1 -i lo -j ACCEPT; then
    log "firewall rule added for lo"
  else
    log "failed to add firewall rule for lo"
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

trigger_refresh_and_next_page() {
  # Only act on key-press (value=1), ignore key-release (value=0)
  [ "$KEY_VALUE" = "1" ] || return 0

  ensure_firewall_rule

  if ! pgrep -f 'drone-app-1.0.0-SNAPSHOT.jar' >/dev/null 2>&1; then
    start_app
  fi

  # Locate wget — BusyBox sh does not support 'command -v'
  WGET_BIN=""
  for _p in /usr/bin/wget /bin/wget /usr/local/bin/wget; do
    [ -x "$_p" ] && WGET_BIN="$_p" && break
  done

  if [ -n "$WGET_BIN" ]; then
    if "$WGET_BIN" -q -T 3 -O /dev/null \
        "http://127.0.0.1:$LISTENER_PORT/control/next-refresh" 2>/dev/null; then
      log "refresh+next sent via http (wget)"
      return 0
    fi
    log "wget call failed, trying nc"
  fi

  # Locate nc (netcat)
  NC_BIN=""
  for _p in /usr/bin/nc /bin/nc /usr/local/bin/nc; do
    [ -x "$_p" ] && NC_BIN="$_p" && break
  done

  if [ -n "$NC_BIN" ]; then
    if printf "cmd:next-refresh\r\n" | \
        "$NC_BIN" -w 2 127.0.0.1 "$LISTENER_PORT" >/dev/null 2>&1; then
      log "refresh+next sent via tcp (nc)"
      return 0
    fi
  fi

  log "refresh+next failed: wget=$WGET_BIN nc=$NC_BIN port=$LISTENER_PORT"
  return 1
}

if [ ! -x "$GET_KEY_CODES" ]; then
  echo "Missing executable: $GET_KEY_CODES" >&2
  exit 1
fi

log "waitforkey listener start next=$NEXT_CODE prev=$PREV_CODE right_next=$RIGHT_NEXT_CODE left_next=${LEFT_NEXT_CODE:-unset} prev_alt=${PREV_ALT_CODE:-unset} extra_next=${EXTRA_NEXT_CODES:-none} extra_prev=${EXTRA_PREV_CODES:-none}"

while true; do
  KEY_CODES="$($GET_KEY_CODES 2>/dev/null)"
  set -- $KEY_CODES
  KEY_CODE="$1"
  KEY_VALUE="$2"

  [ -n "$KEY_CODE" ] || continue
  log "key code=$KEY_CODE value=$KEY_VALUE"

  if [ "$KEY_CODE" = "$NEXT_CODE" ]; then
    start_app
  elif code_in_list "$KEY_CODE" "$PREV_CODE" "$PREV_ALT_CODE" $EXTRA_PREV_CODES; then
    stop_app
  elif code_in_list "$KEY_CODE" "$RIGHT_NEXT_CODE" "$LEFT_NEXT_CODE" $EXTRA_NEXT_CODES; then
    trigger_refresh_and_next_page
  else
    log "key code=$KEY_CODE unmapped (no action)"
  fi
done
