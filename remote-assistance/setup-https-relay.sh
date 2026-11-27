#!/usr/bin/env bash
set -euo pipefail

if (( EUID != 0 )); then
    echo "Ejecuta este instalador con sudo en el Oracle." >&2
    exit 2
fi

domain="${1:-remoto.jolufasoft.com}"
bind_ip="${2:-10.0.0.85}"
caddyfile="${3:-/etc/caddy/Caddyfile}"
expected_public_ip="${4:-}"
source_dir="$(cd -- "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
relay_dir=/etc/jremote-login
ssh_config="$relay_dir/sshd_config"
site_file=/etc/jremote-login/https.caddy
include_line="import $site_file"
unit=/etc/systemd/system/jremote-wss.service

[[ "$domain" =~ ^[a-z0-9][a-z0-9.-]*\.[a-z]{2,}$ ]]
[[ "$bind_ip" =~ ^([0-9]{1,3}\.){3}[0-9]{1,3}$ ]]
[[ -f "$caddyfile" && -f "$ssh_config" && -f "$relay_dir/config.json" ]]
# Check public DNS independently of the Oracle's system resolver.
public_dns() {
    curl -fsS --connect-timeout 4 --max-time 8 \
        --get \
        --data-urlencode "name=$domain" --data-urlencode 'type=A' \
        'https://dns.google/resolve' ||
    curl -fsS --connect-timeout 4 --max-time 8 \
        --get \
        -H 'accept: application/dns-json' \
        --data-urlencode "name=$domain" --data-urlencode 'type=A' \
        'https://cloudflare-dns.com/dns-query'
}

if ! dns_answer="$(public_dns)"; then
    printf 'No pude consultar el DNS público de %s.\n' "$domain" >&2
    exit 2
fi
if ! printf '%s' "$dns_answer" | /usr/bin/python3 -c '
import ipaddress, json, sys
expected = sys.argv[1]
reply = json.load(sys.stdin)
addresses = {
    ipaddress.IPv4Address(item["data"]) for item in reply.get("Answer", [])
    if item.get("type") == 1
}
if reply.get("Status") != 0 or not addresses or (expected and addresses != {ipaddress.IPv4Address(expected)}):
    print("DNS público incompatible. Direcciones encontradas:", ", ".join(map(str, sorted(addresses))) or "ninguna",
          "· IPv4 esperada:", expected or "cualquiera válida", file=sys.stderr)
    sys.exit(2)
print("DNS público verificado:", ", ".join(map(str, sorted(addresses))))
' "$expected_public_ip"; then
    exit 2
fi
if ! ip -o -4 address show | awk -v ip="$bind_ip/" '$4 ~ ("^" ip) {found=1} END {exit !found}'; then
    printf 'La IPv4 %s no pertenece a una interfaz del Oracle.\n' "$bind_ip" >&2
    exit 2
fi
if ! systemctl show -p ExecStart --value caddy.service | tr ' ;{}[]' '\n' | grep -Fxq -- "$caddyfile"; then
    printf 'Caddy usa otra configuración. Indica su Caddyfile como tercer argumento.\n' >&2
    exit 2
fi
if ! /usr/bin/python3 -c 'from websockets.legacy.server import serve' >/dev/null 2>&1; then
    apt-get update
    apt-get install -y python3-websockets
fi

stage="$(mktemp -d)"
caddy_dir="$(dirname "$caddyfile")"
candidate="$(mktemp "$caddy_dir/.jremote-candidate.XXXXXXXX")"
caddy_changed=0
rollback() {
    local rc=$?
    trap - EXIT
    if (( rc != 0 && caddy_changed )); then
        cp -p -- "$stage/Caddyfile.old" "$caddyfile"
        if [[ -f "$stage/site.old" ]]; then
            cp -p -- "$stage/site.old" "$site_file"
        else
            rm -f -- "$site_file"
        fi
        systemctl reload caddy.service || true
    fi
    rm -f -- "$candidate"
    rm -rf -- "$stage"
    exit "$rc"
}
trap rollback EXIT

cp -p -- "$caddyfile" "$stage/Caddyfile.old"
[[ ! -f "$site_file" ]] || cp -p -- "$site_file" "$stage/site.old"
sed "\|^$include_line\$|d" "$caddyfile" > "$candidate"
cat >"$stage/site" <<SITE

$domain {
    bind $bind_ip
    handle /jremote/tunnel {
        reverse_proxy 127.0.0.1:17667
    }
    handle /jremote/* {
        reverse_proxy 127.0.0.1:17666
    }
    handle {
        respond 404
    }
}
SITE
cat "$stage/site" >>"$candidate"
caddy validate --config "$candidate" --adapter caddyfile
cp -p -- "$ssh_config" "$stage/sshd_config"
if ! grep -qE '^ListenAddress[[:space:]]+127\.0\.0\.1:2223([[:space:]]|$)' "$stage/sshd_config"; then
    sed -i '1iListenAddress 127.0.0.1:2223' "$stage/sshd_config"
fi
/usr/sbin/sshd -t -f "$stage/sshd_config"
/usr/bin/python3 - "$source_dir/relay-login.py" "$source_dir/relay-wss.py" <<'PY_CHECK'
import sys
from pathlib import Path

for name in sys.argv[1:]:
    path = Path(name)
    compile(path.read_bytes(), str(path), "exec")
PY_CHECK
user="$(systemctl show -p User --value jremote-login.service)"
[[ -n "$user" ]]
install -m 0644 "$source_dir/relay-login.py" "$relay_dir/relay-login.py"
install -m 0644 "$source_dir/relay-wss.py" "$relay_dir/relay-wss.py"
if ! cmp -s "$stage/sshd_config" "$ssh_config"; then
    install -m 0644 "$stage/sshd_config" "$ssh_config"
    systemctl kill --kill-whom=main --signal=HUP jremote-ssh.service
fi
cat >"$stage/jremote-wss.service" <<UNIT
[Unit]
Description=JolufaSoft Remote WebSocket transport
After=jremote-ssh.service jremote-login.service
Requires=jremote-ssh.service jremote-login.service

[Service]
User=$user
ExecStart=/usr/bin/python3 $relay_dir/relay-wss.py /var/lib/jremote-login/state.json
Restart=on-failure
NoNewPrivileges=true
ProtectSystem=strict

[Install]
WantedBy=multi-user.target
UNIT
install -m 0644 "$stage/jremote-wss.service" "$unit"
systemctl daemon-reload
systemctl restart jremote-login.service
systemctl enable jremote-wss.service
systemctl restart jremote-wss.service
systemctl is-active --quiet jremote-login.service
systemctl is-active --quiet jremote-wss.service

caddy_changed=1
install -m 0644 "$stage/site" "$site_file"
sed "\|^$include_line\$|d" "$caddyfile" >"$candidate"
printf '\n%s\n' "$include_line" >>"$candidate"
caddy validate --config "$candidate" --adapter caddyfile
cat "$candidate" >"$caddyfile"
systemctl reload caddy.service
systemctl is-active --quiet caddy.service

printf 'Caddy y el relay activos para https://%s\n' "$domain"
printf 'Prueba desde un teléfono en datos móviles para comprobar DNS, firewall y certificado.\n'
