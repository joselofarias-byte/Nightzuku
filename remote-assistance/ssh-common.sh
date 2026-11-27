# Validación y opciones compartidas por el operador y el teléfono asistido.
validate_port() {
    if [[ ! "$1" =~ ^[1-9][0-9]{0,4}$ ]] || (( $1 > 65535 )); then
        printf 'Puerto inválido: %s\n' "$1" >&2
        exit 2
    fi
}

ssh_options() {
    if [[ ! "$REMOTE_SSH_USER" =~ ^[[:alnum:]_][[:alnum:]_.-]*$ ||
          ! "$REMOTE_SSH_HOST" =~ ^[[:alnum:]:][[:alnum:].:-]*$ ]]; then
        printf 'Usuario o servidor SSH inválido.\n' >&2
        exit 2
    fi
    if [[ ! -r "$REMOTE_SSH_KEY" || ! -s "$KNOWN_HOSTS" ]]; then
        printf 'Falta la clave del cliente o el archivo known_hosts verificado.\n' >&2
        exit 2
    fi
    ssh_args=(-F /dev/null -NT -i "$REMOTE_SSH_KEY" -p "$REMOTE_SSH_PORT"
        -o IdentitiesOnly=yes -o BatchMode=yes -o StrictHostKeyChecking=yes
        -o "UserKnownHostsFile=$KNOWN_HOSTS" -o GlobalKnownHostsFile=/dev/null
        -o ExitOnForwardFailure=yes -o ServerAliveInterval=15
        -o ServerAliveCountMax=3 -o ConnectTimeout=12)
}
