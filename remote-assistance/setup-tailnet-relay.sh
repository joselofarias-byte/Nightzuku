#!/usr/bin/env bash
set -euo pipefail
umask 077

if (( EUID != 0 )) || (( $# < 2 || $# > 3 )); then
    echo "Uso: sudo bash setup-tailnet-relay.sh operador.pub asistido.pub [puerto-ssh]" >&2
    exit 2
fi

port="${3:-2222}"
if [[ ! "$port" =~ ^[1-9][0-9]{3,4}$ ]] || (( port < 1024 || port > 65535 || port == 15555 )); then
    echo "El puerto SSH debe estar entre 1024 y 65535 y ser distinto de 15555." >&2
    exit 2
fi

read_key() {
    local type blob extra
    if ! read -r type blob extra < "$1" ||
        [[ "$type" != ecdsa-sha2-nistp256 || ! "$blob" =~ ^[A-Za-z0-9+/]+={0,2}$ ]] ||
        ! ssh-keygen -lf <(printf '%s %s\n' "$type" "$blob") >/dev/null; then
        printf 'Clave pública ECDSA P-256 inválida: %s\n' "$1" >&2
        return 2
    fi
    printf '%s %s' "$type" "$blob"
}
operator_key="$(read_key "$1")"
device_key="$(read_key "$2")"
if [[ "$operator_key" == "$device_key" ]]; then
    echo "Cada teléfono debe usar su propia clave SSH." >&2
    exit 2
fi

admin_dir=/etc/jolufasoft-remote
sshd=/usr/sbin/sshd
tailscale_cli="$(command -v tailscale)"
[[ -x "$sshd" ]]
tailnet_ip="$(timeout 8 "$tailscale_cli" ip -4)"
if [[ ! "$tailnet_ip" =~ ^100\.([0-9]{1,3})\.[0-9]{1,3}\.[0-9]{1,3}$ ]] ||
    (( 10#${BASH_REMATCH[1]} < 64 || 10#${BASH_REMATCH[1]} > 127 )); then
    echo "Tailscale no devolvió una dirección IPv4 del tailnet." >&2
    exit 2
fi

operator=remote-operator-01
device=remote-device-01
install -d -m 0755 "$admin_dir" "$admin_dir/keys" /run/sshd
for user in "$operator" "$device"; do
    if ! id "$user" >/dev/null 2>&1; then
        useradd --system --no-create-home --shell /usr/sbin/nologin "$user"
    elif [[ "$(getent passwd "$user" | cut -d: -f7)" != /usr/sbin/nologin ]]; then
        echo "La cuenta $user ya existe con un shell distinto de nologin." >&2
        exit 2
    fi
done

host_key="$admin_dir/ssh_host_ecdsa_key"
if [[ ! -s "$host_key" ]]; then
    ssh-keygen -q -t ecdsa -b 256 -N '' -f "$host_key"
fi

stage="$(mktemp -d "$admin_dir/.config.XXXXXXXX")"
trap 'rm -rf -- "$stage"' EXIT
printf 'restrict,port-forwarding,permitopen="127.0.0.1:15555" %s\n' "$operator_key" > "$stage/operator"
printf 'restrict,port-forwarding,permitlisten="127.0.0.1:15555" %s\n' "$device_key" > "$stage/device"
cat > "$stage/sshd_config" <<CONFIG
ListenAddress $tailnet_ip:$port
HostKey $host_key
AuthorizedKeysFile $admin_dir/keys/%u
PidFile none
AllowUsers $operator $device
AuthenticationMethods publickey
PubkeyAuthentication yes
PasswordAuthentication no
KbdInteractiveAuthentication no
UsePAM yes
PermitRootLogin no
GatewayPorts no
AllowStreamLocalForwarding no
AllowAgentForwarding no
X11Forwarding no
PermitTTY no
PermitTunnel no
MaxSessions 0
ClientAliveInterval 60
ClientAliveCountMax 2

Match User $operator
    AllowTcpForwarding local
    PermitOpen 127.0.0.1:15555

Match User $device
    AllowTcpForwarding remote
    PermitListen 127.0.0.1:15555
CONFIG
"$sshd" -t -f "$stage/sshd_config"
cat > "$stage/jolufasoft-remote-relay.service" <<UNIT
[Unit]
Description=JolufaSoft Remote on Tailscale
After=network-online.target tailscaled.service
Wants=network-online.target tailscaled.service

[Service]
ExecStartPre=/usr/bin/install -d -m 0755 /run/sshd
ExecStart=$sshd -D -e -f $admin_dir/sshd_config
Restart=on-failure
RestartSec=5

[Install]
WantedBy=multi-user.target
UNIT

install -m 0644 "$stage/operator" "$admin_dir/keys/$operator"
install -m 0644 "$stage/device" "$admin_dir/keys/$device"
install -m 0644 "$stage/sshd_config" "$admin_dir/sshd_config"
install -m 0644 "$stage/jolufasoft-remote-relay.service" /etc/systemd/system/jolufasoft-remote-relay.service
systemctl daemon-reload
systemctl enable jolufasoft-remote-relay.service
systemctl restart jolufasoft-remote-relay.service
systemctl is-active --quiet jolufasoft-remote-relay.service
printf 'Servidor: %s\nPuerto SSH: %s\nPuerto del dispositivo: 15555\n' "$tailnet_ip" "$port"
ssh-keygen -lf "$host_key.pub" -E sha256
