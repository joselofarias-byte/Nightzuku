#!/data/data/com.termux/files/usr/bin/bash
# Operator-side SSH tunnel. Run only for a device whose owner authorized access.
set -Eeuo pipefail
umask 077
: "${REMOTE_SSH_HOST:?Set REMOTE_SSH_HOST}"
: "${REMOTE_SSH_USER:?Set REMOTE_SSH_USER}"
: "${REMOTE_SSH_KEY:?Set REMOTE_SSH_KEY}"
REMOTE_SSH_PORT="${REMOTE_SSH_PORT:-22}"
REMOTE_BIND_PORT="${REMOTE_BIND_PORT:-15555}"
CONTROLLER_PORT="${CONTROLLER_PORT:-15556}"
KNOWN_HOSTS="${KNOWN_HOSTS:-$HOME/.ssh/known_hosts}"
[[ -r "$REMOTE_SSH_KEY" && -s "$KNOWN_HOSTS" ]] || { echo "Missing key or verified known_hosts" >&2; exit 2; }
for p in "$REMOTE_SSH_PORT" "$REMOTE_BIND_PORT" "$CONTROLLER_PORT"; do
  [[ "$p" =~ ^[0-9]+$ ]] && (( p >= 1 && p <= 65535 )) || { echo "Invalid port: $p" >&2; exit 2; }
done
echo "Forwarding localhost:$CONTROLLER_PORT to authorized remote Android; Ctrl-C to stop."
exec ssh -NT -i "$REMOTE_SSH_KEY" -p "$REMOTE_SSH_PORT" \
  -o BatchMode=yes -o StrictHostKeyChecking=yes \
  -o UserKnownHostsFile="$KNOWN_HOSTS" -o ExitOnForwardFailure=yes \
  -o ServerAliveInterval=20 -o ServerAliveCountMax=3 \
  -L "127.0.0.1:$CONTROLLER_PORT:127.0.0.1:$REMOTE_BIND_PORT" \
  "$REMOTE_SSH_USER@$REMOTE_SSH_HOST"
