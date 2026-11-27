#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

: "${REMOTE_SSH_HOST:?Configura REMOTE_SSH_HOST}"
: "${REMOTE_SSH_USER:?Configura REMOTE_SSH_USER}"
: "${REMOTE_SSH_KEY:?Configura REMOTE_SSH_KEY}"
REMOTE_SSH_PORT="${REMOTE_SSH_PORT:-22}"
REMOTE_BIND_PORT="${REMOTE_BIND_PORT:-15555}"
LOCAL_ADB_PORT="${LOCAL_ADB_PORT:-5555}"
KNOWN_HOSTS="${KNOWN_HOSTS:-$HOME/.ssh/known_hosts}"
SESSION_MINUTES="${SESSION_MINUTES:-30}"

source "$(dirname "$(readlink -f "${BASH_SOURCE[0]}")")/ssh-common.sh"
for port in "$REMOTE_SSH_PORT" "$REMOTE_BIND_PORT" "$LOCAL_ADB_PORT"; do
    validate_port "$port"
done
if [[ ! "$SESSION_MINUTES" =~ ^[1-9][0-9]?$ ]] || (( SESSION_MINUTES > 60 )); then
    echo "SESSION_MINUTES debe estar entre 1 y 60." >&2
    exit 2
fi
if [[ ! -t 0 || ! -t 1 ]]; then
    echo "Abre Termux y ejecuta este comando en una terminal visible." >&2
    exit 2
fi

ssh_options
printf 'La persona que te asiste podrá ver y controlar este teléfono durante %s minutos.\n' "$SESSION_MINUTES"
printf 'Pulsa Ctrl+C para terminar en cualquier momento.\n'
read -r -p 'Escribe SI para autorizar y comenzar: ' consent
[[ "$consent" == SI ]] || exit 0

printf 'Asistencia autorizada. Mantén Termux abierto. Ctrl+C finaliza la sesión.\n'
status=0
timeout --foreground --kill-after=5s "${SESSION_MINUTES}m" \
    ssh "${ssh_args[@]}" \
    -R "127.0.0.1:$REMOTE_BIND_PORT:127.0.0.1:$LOCAL_ADB_PORT" \
    -- "$REMOTE_SSH_USER@$REMOTE_SSH_HOST" || status=$?
if (( status != 0 && status != 124 && status != 130 )); then
    printf 'El túnel se cerró con código %s.\n' "$status" >&2
    exit "$status"
fi
printf 'Sesión de asistencia finalizada.\n'
