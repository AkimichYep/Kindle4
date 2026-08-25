#!/bin/sh
# Start Kindle drone app once (idempotent).

APP_DIR="${APP_DIR:-/mnt/us/drone-app}"
APP_JAR="${APP_JAR:-$APP_DIR/drone-app-2.0.0-SNAPSHOT.jar}"
JAVA_BIN="${JAVA_BIN:-/mnt/us/java/jre/bin/java}"
LOG_FILE="${LOG_FILE:-$APP_DIR/logs/app.log}"
APP_PID_FILE="${APP_PID_FILE:-$APP_DIR/drone-app.pid}"
LOCK_DIR="${LOCK_DIR:-/tmp/drone-app.lock}"
LOCK_PID_FILE="$LOCK_DIR/pid"
TZ_FILE="${TZ_FILE:-/var/local/system/tz}"
ETC_TZ_FILE="${ETC_TZ_FILE:-/etc/TZ}"
DEFAULT_TZ="${DEFAULT_TZ:-EET-2EEST,M3.5.0/3,M10.5.0/4}"

is_tzif_file() {
  [ -f "$1" ] && [ "$(dd if="$1" bs=1 count=4 2>/dev/null)" = "TZif" ]
}

read_text_tz() {
  [ -s "$1" ] || return 1
  is_tzif_file "$1" && return 1

  # TZ files we consume here must be plain text; take the first non-empty line.
  CANDIDATE_TZ="$(sed -n '/./{p;q;}' "$1" 2>/dev/null | tr -d '\r\n')"
  [ -n "$CANDIDATE_TZ" ] || return 1
  echo "$CANDIDATE_TZ"
}

resolve_tz() {
  # Kindle boot services may export TZ=UTC even though the user-facing system
  # clock is local time. Do not let that inherited value override the persisted
  # Kindle setting; non-UTC TZ remains an explicit caller override.
  if [ -n "$TZ" ] && [ "$TZ" != "UTC" ] && [ "$TZ" != "GMT" ] && [ "$TZ" != "GMT0" ]; then
    echo "$TZ"
    return
  fi

  for SOURCE_FILE in "$ETC_TZ_FILE" "$TZ_FILE"; do
    CANDIDATE_TZ="$(read_text_tz "$SOURCE_FILE")"
    if [ -n "$CANDIDATE_TZ" ]; then
      # Some Kindle images store zone IDs (e.g. Europe/Kiev) that BusyBox
      # cannot resolve without zoneinfo; fallback to POSIX TZ if it resolves to UTC.
      CANDIDATE_OFF="$(TZ="$CANDIDATE_TZ" date +%z 2>/dev/null)"
      DEFAULT_OFF="$(TZ="$DEFAULT_TZ" date +%z 2>/dev/null)"
      if [ -n "$CANDIDATE_OFF" ] && [ "$CANDIDATE_OFF" != "+0000" -o "$DEFAULT_OFF" = "+0000" ]; then
        echo "$CANDIDATE_TZ"
        return
      fi
    fi
  done

  echo "$DEFAULT_TZ"
}

export TZ="$(resolve_tz)"

# A zone ID unsupported by BusyBox resolves as UTC. Use the known-good POSIX
# fallback instead, so Java and its child `date` commands see local time.
if [ "$(date +%z 2>/dev/null)" = "+0000" ]; then
  export TZ="$DEFAULT_TZ"
fi

resolve_jvm_tz() {
  OFF="$(date +%z 2>/dev/null)"
  case "$OFF" in
    [+-][0-9][0-9][0-9][0-9])
      SIGN="${OFF%${OFF#?}}"
      HH="${OFF#?}"
      HH="${HH%??}"
      MM="${OFF##???}"
      echo "GMT${SIGN}${HH}:${MM}"
      return
      ;;
  esac
  echo "GMT+03:00"
}

JVM_TZ="$(resolve_jvm_tz)"

# Keep Kindle timezone files aligned when storage is writable.
if [ -w "$TZ_FILE" ] && ! is_tzif_file "$TZ_FILE" && [ "$(cat "$TZ_FILE" 2>/dev/null)" != "$TZ" ]; then
  echo "$TZ" > "$TZ_FILE" 2>/dev/null || true
fi

if [ -w "$ETC_TZ_FILE" ] && [ "$(cat "$ETC_TZ_FILE" 2>/dev/null)" != "$TZ" ]; then
  echo "$TZ" > "$ETC_TZ_FILE" 2>/dev/null || true
fi

is_alive() {
  [ -n "$1" ] && kill -0 "$1" 2>/dev/null
}

read_pid_file() {
  [ -f "$1" ] && cat "$1" 2>/dev/null
}

# Fast path: valid PID file.
APP_PID="$(read_pid_file "$APP_PID_FILE")"
if is_alive "$APP_PID"; then
  exit 0
fi

# Recovery path: find running JVM if PID file is stale/missing.
APP_PID="$(pgrep -f 'drone-app-2.0.0-SNAPSHOT.jar' | head -n 1)"
if is_alive "$APP_PID"; then
  echo "$APP_PID" > "$APP_PID_FILE"
  exit 0
fi

# Acquire lock atomically (prevents double launch race).
if mkdir "$LOCK_DIR" 2>/dev/null; then
  echo $$ > "$LOCK_PID_FILE"
else
  LOCK_PID="$(read_pid_file "$LOCK_PID_FILE")"
  if is_alive "$LOCK_PID"; then
    exit 0
  fi

  # Stale lock cleanup and single retry.
  rm -f "$LOCK_PID_FILE" 2>/dev/null
  rmdir "$LOCK_DIR" 2>/dev/null
  if mkdir "$LOCK_DIR" 2>/dev/null; then
    echo $$ > "$LOCK_PID_FILE"
  else
    exit 0
  fi
fi

export LC_ALL="${LC_ALL:-en_US.UTF-8}"
export LANG="${LANG:-en_US.UTF-8}"

rdate -s time.nist.gov >> "$LOG_FILE" 2>&1 || true
if command -v hwclock >/dev/null 2>&1; then
  hwclock -w >> "$LOG_FILE" 2>&1 || true
fi
mkdir -p "$APP_DIR/logs" 2>/dev/null || true
cd "$APP_DIR" && "$JAVA_BIN" -Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8 -Duser.timezone="$JVM_TZ" -jar "$APP_JAR" </dev/null >> "$LOG_FILE" 2>&1 &
NEW_PID=$!
sleep 1

if is_alive "$NEW_PID"; then
  echo "$NEW_PID" > "$APP_PID_FILE"
  echo "$NEW_PID" > "$LOCK_PID_FILE"
  exit 0
fi

echo "$(date): failed to start $APP_JAR" >> "$LOG_FILE"
rm -f "$LOCK_PID_FILE" 2>/dev/null
rmdir "$LOCK_DIR" 2>/dev/null
exit 1

