#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

: "${REMOTE_SSH_HOST:?Configura REMOTE_SSH_HOST}"
: "${REMOTE_SSH_USER:?Configura REMOTE_SSH_USER}"
: "${REMOTE_SSH_KEY:?Configura REMOTE_SSH_KEY}"
REMOTE_SSH_PORT="${REMOTE_SSH_PORT:-22}"
REMOTE_BIND_PORT="${REMOTE_BIND_PORT:-15555}"
CONTROLLER_PORT="${CONTROLLER_PORT:-15556}"
KNOWN_HOSTS="${KNOWN_HOSTS:-$HOME/.ssh/known_hosts}"

source "$(dirname "$(readlink -f "${BASH_SOURCE[0]}")")/ssh-common.sh"
for port in "$REMOTE_SSH_PORT" "$REMOTE_BIND_PORT" "$CONTROLLER_PORT"; do
    validate_port "$port"
done
ssh_options

printf 'Puerto en JolufaSoft Remote: %s. Ctrl+C cierra el túnel.\n' "$CONTROLLER_PORT"
exec ssh "${ssh_args[@]}" \
    -L "127.0.0.1:$CONTROLLER_PORT:127.0.0.1:$REMOTE_BIND_PORT" \
    -- "$REMOTE_SSH_USER@$REMOTE_SSH_HOST"
