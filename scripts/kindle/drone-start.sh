#!/bin/sh
# Start Kindle drone app once (idempotent).

APP_JAR="${APP_JAR:-/mnt/us/drone-app-1.0.0-SNAPSHOT.jar}"
JAVA_BIN="${JAVA_BIN:-/mnt/us/java/jre/bin/java}"
LOG_FILE="${LOG_FILE:-/mnt/us/drone-app.log}"
APP_PID_FILE="${APP_PID_FILE:-/mnt/us/drone-app.pid}"
LOCK_DIR="${LOCK_DIR:-/mnt/us/drone-app.lock}"
LOCK_PID_FILE="$LOCK_DIR/pid"

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
APP_PID="$(pgrep -f 'drone-app-1.0.0-SNAPSHOT.jar' | head -n 1)"
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

"$JAVA_BIN" -jar "$APP_JAR" </dev/null >> "$LOG_FILE" 2>&1 &
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

