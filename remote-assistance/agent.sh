#!/data/data/com.termux/files/usr/bin/bash
# JolufaSoft Remote: reverse SSH tunnel for an explicitly authorized Android device.
set -Eeuo pipefail
umask 077
: "${REMOTE_SSH_HOST:?Set REMOTE_SSH_HOST}"
: "${REMOTE_SSH_USER:?Set REMOTE_SSH_USER}"
: "${REMOTE_SSH_KEY:?Set REMOTE_SSH_KEY}"
REMOTE_SSH_PORT="${REMOTE_SSH_PORT:-22}"
REMOTE_BIND_PORT="${REMOTE_BIND_PORT:-15555}"
LOCAL_ADB_PORT="${LOCAL_ADB_PORT:-5555}"
ADB_SERIAL="${ADB_SERIAL:-127.0.0.1:$LOCAL_ADB_PORT}"
KNOWN_HOSTS="${KNOWN_HOSTS:-$HOME/.ssh/known_hosts}"
LOG_DIR="${LOG_DIR:-$HOME/.local/state/jolufasoft-remote}"
mkdir -p "$LOG_DIR"
command -v ssh >/dev/null || { echo "Missing openssh" >&2; exit 2; }
command -v adb >/dev/null || { echo "Missing adb" >&2; exit 2; }
[[ -r "$REMOTE_SSH_KEY" && -s "$KNOWN_HOSTS" ]] || { echo "SSH key or pinned known_hosts missing" >&2; exit 2; }
[[ "$REMOTE_BIND_PORT" =~ ^[0-9]+$ && "$LOCAL_ADB_PORT" =~ ^[0-9]+$ ]] || exit 2
(( REMOTE_BIND_PORT >= 1024 && REMOTE_BIND_PORT <= 65535 && LOCAL_ADB_PORT >= 1 && LOCAL_ADB_PORT <= 65535 )) || exit 2
if [[ "$LOCAL_ADB_PORT" == 5555 ]]; then
  adb connect "$ADB_SERIAL" >/dev/null 2>&1 || true
fi
backoff=2
while :; do
  if ! adb -s "$ADB_SERIAL" get-state 2>/dev/null | grep -qx device; then
    echo "$(date -Is) adb unavailable; waiting" >> "$LOG_DIR/agent.log"
    sleep "$backoff"
    (( backoff < 60 )) && backoff=$((backoff * 2))
    continue
  fi
  backoff=2
  echo "$(date -Is) opening outbound tunnel" >> "$LOG_DIR/agent.log"
  ssh -NT -i "$REMOTE_SSH_KEY" -p "$REMOTE_SSH_PORT" \
    -o BatchMode=yes -o StrictHostKeyChecking=yes \
    -o UserKnownHostsFile="$KNOWN_HOSTS" \
    -o ExitOnForwardFailure=yes -o ServerAliveInterval=20 \
    -o ServerAliveCountMax=3 -o ConnectTimeout=12 \
    -R "127.0.0.1:$REMOTE_BIND_PORT:127.0.0.1:$LOCAL_ADB_PORT" \
    "$REMOTE_SSH_USER@$REMOTE_SSH_HOST" >> "$LOG_DIR/agent.log" 2>&1 || true
  sleep 5
done
