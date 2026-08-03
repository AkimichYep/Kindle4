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

resolve_tz() {
  if [ -n "$TZ" ]; then
    echo "$TZ"
    return
  fi

  if [ -s "$TZ_FILE" ]; then
    cat "$TZ_FILE" 2>/dev/null
    return
  fi

  echo "$DEFAULT_TZ"
}

export TZ="$(resolve_tz)"

# Keep Kindle timezone files aligned when storage is writable.
if [ -w "$TZ_FILE" ] && [ "$(cat "$TZ_FILE" 2>/dev/null)" != "$TZ" ]; then
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

rdate -s time.nist.gov >> "$LOG_FILE" 2>&1 || true
if command -v hwclock >/dev/null 2>&1; then
  hwclock -w >> "$LOG_FILE" 2>&1 || true
fi
mkdir -p "$APP_DIR/logs" 2>/dev/null || true
cd "$APP_DIR" && "$JAVA_BIN" -jar "$APP_JAR" </dev/null >> "$LOG_FILE" 2>&1 &
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

