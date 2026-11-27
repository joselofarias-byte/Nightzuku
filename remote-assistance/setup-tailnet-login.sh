#!/usr/bin/env bash
set -euo pipefail
umask 077

if (( EUID != 0 )); then
    echo "Ejecuta sudo bash setup-tailnet-login.sh en el Oracle." >&2
    exit 2
fi
src="$(cd -- "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
dir=/etc/jremote-login
state=/var/lib/jremote-login
broker=jremote-login
operator=remote-operator-01
device=remote-device-01
sshd=/usr/sbin/sshd
[[ -f "$src/relay-login.py" && -x "$sshd" ]]
command -v tailscale >/dev/null
command -v python3 >/dev/null
ip="$(timeout 10 tailscale ip -4)"
dns="$(timeout 10 tailscale status --json | python3 -c 'import json,sys; print(json.load(sys.stdin)["Self"]["DNSName"].rstrip("."))')"
if [[ ! "$ip" =~ ^100\.([0-9]{1,3})\.[0-9]{1,3}\.[0-9]{1,3}$ ]] ||
    (( 10#${BASH_REMATCH[1]} < 64 || 10#${BASH_REMATCH[1]} > 127 )) ||
    [[ ! "$dns" =~ ^[a-z0-9][a-z0-9.-]*\.ts\.net$ ]]; then
    echo "Activa Tailscale y su nombre completo MagicDNS en el Oracle." >&2
    exit 2
fi
timeout 10 tailscale serve status --json | python3 -c '
import json,sys
data=json.load(sys.stdin) or {}
if any(k.endswith(":8443") and v for k,v in (data.get("AllowFunnel") or {}).items()):
    sys.exit("El puerto 8443 tiene Funnel activo. Usa un equipo sin exposición pública de ese puerto.")
'

for user in "$broker" "$operator" "$device"; do
    if ! id "$user" >/dev/null 2>&1; then
        useradd --system --no-create-home --shell /usr/sbin/nologin "$user"
    elif [[ "$(getent passwd "$user" | cut -d: -f7)" != /usr/sbin/nologin ]]; then
        echo "La cuenta $user ya existe con otro shell." >&2
        exit 2
    fi
done
install -d -m 0755 "$dir" /run/sshd
install -d -m 0700 -o "$broker" "$state"
install -m 0644 "$src/relay-login.py" "$dir/relay-login.py"
if [[ ! -s "$dir/ssh_host" ]]; then
    ssh-keygen -q -t ecdsa -b 256 -N '' -f "$dir/ssh_host"
fi
fingerprint="$(ssh-keygen -lf "$dir/ssh_host.pub" -E sha256 | awk '{print $2}')"
stage="$(mktemp -d "$dir/.setup.XXXXXXXX")"
trap 'rm -rf -- "$stage"' EXIT
python3 - "$dir/config.json" "$stage" "$dns" "$fingerprint" <<'PY'
import json, sys, secrets, hashlib
from pathlib import Path
current, stage, dns, fingerprint = sys.argv[1:]
current, stage = Path(current), Path(stage)
if current.exists():
    users = json.loads(current.read_text())["users"]
else:
    users, passwords = {}, []
    for user in ("remote-operator-01", "remote-device-01"):
        password = secrets.token_urlsafe(18)
        salt = secrets.token_bytes(16)
        users[user] = {"salt": salt.hex(), "digest": hashlib.pbkdf2_hmac("sha256", password.encode(), salt, 600_000).hex()}
        passwords.append(f"{user}  {password}")
    (stage / "passwords").write_text("\n".join(passwords) + "\n")
(stage / "config.json").write_text(json.dumps({"users": users, "ssh": {
    "host": dns, "port": 2223, "fingerprint": fingerprint
}}))
PY
cat > "$stage/sshd_config" <<CONFIG
ListenAddress $ip:2223
HostKey $dir/ssh_host
AuthorizedKeysFile none
AuthorizedKeysCommand /usr/bin/cat $state/%u
AuthorizedKeysCommandUser $broker
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
Match User $device
    AllowTcpForwarding remote
CONFIG
"$sshd" -t -f "$stage/sshd_config"
cat > "$stage/jremote-login.service" <<UNIT
[Unit]
Description=JolufaSoft Remote enrollment
[Service]
User=$broker
ExecStart=/usr/bin/python3 $dir/relay-login.py $dir/config.json $state
Restart=on-failure
NoNewPrivileges=true
ProtectSystem=strict
ReadWritePaths=$state
[Install]
WantedBy=multi-user.target
UNIT
cat > "$stage/jremote-ssh.service" <<UNIT
[Unit]
Description=JolufaSoft Remote SSH on Tailscale
After=tailscaled.service jremote-login.service
Wants=tailscaled.service jremote-login.service
[Service]
ExecStartPre=/usr/bin/install -d -m 0755 /run/sshd
ExecStart=$sshd -D -e -f $dir/sshd_config
Restart=on-failure
RestartSec=5
[Install]
WantedBy=multi-user.target
UNIT
if [[ -s "$stage/passwords" ]]; then
    install -m 0600 "$stage/passwords" "$dir/alta-inicial.txt"
fi
install -m 0640 -o root -g "$(id -gn "$broker")" "$stage/config.json" "$dir/config.json"
install -m 0644 "$stage/sshd_config" "$dir/sshd_config"
install -m 0644 "$stage/"*.service /etc/systemd/system/
systemctl daemon-reload
systemctl enable jremote-login.service jremote-ssh.service
systemctl restart jremote-login.service jremote-ssh.service
systemctl is-active --quiet jremote-login.service jremote-ssh.service
timeout 30 tailscale serve --bg --https=8443 --set-path=/jremote http://127.0.0.1:17666
printf '\nServidor para las dos apps: %s\n' "$dns"
if [[ -s "$dir/alta-inicial.txt" ]]; then
    cat "$dir/alta-inicial.txt"
    rm -f -- "$dir/alta-inicial.txt"
fi
