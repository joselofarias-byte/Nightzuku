#!/data/data/com.termux/files/usr/bin/bash
# Read-only checks; no pairing, permissions, or service restarts.
set -u
ADB_SERIAL="${ADB_SERIAL:-127.0.0.1:5555}"
CONTROLLER_PORT="${CONTROLLER_PORT:-15556}"
echo "=== Local ADB ==="
timeout 8 adb -s "$ADB_SERIAL" get-state 2>&1 || true
echo "=== Operator TCP tunnel ==="
if command -v python >/dev/null; then
  python - "$CONTROLLER_PORT" <<'PY'
import socket,sys
try:
    s=socket.create_connection(("127.0.0.1",int(sys.argv[1])),2)
    s.close()
    print("TCP open (ADB authentication not yet verified)")
except OSError as e:
    print("TCP unavailable:",e)
PY
else
  echo "Python unavailable; skipping TCP check"
fi
echo "=== Reminder ==="
echo "TCP open does not prove remote ADB authorization or scrcpy compatibility."
