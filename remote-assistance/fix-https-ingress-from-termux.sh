#!/usr/bin/env bash
set -euo pipefail

if (( $# != 6 )); then
    printf 'Uso: bash %s alias usuario ip-tailscale ip-vnic ip-publica dominio\n' "$0" >&2
    exit 2
fi
oracle=$1
user=$2
tailnet_ip=$3
local_ip=$4
public_ip=$5
domain=$6

for address in "$tailnet_ip" "$local_ip" "$public_ip"; do
    [[ "$address" =~ ^([0-9]{1,3}\.){3}[0-9]{1,3}$ ]]
done
[[ "$oracle" =~ ^[a-zA-Z0-9][a-zA-Z0-9_.-]*$ ]]
[[ "$user" =~ ^[a-zA-Z0-9_][a-zA-Z0-9_.-]*$ ]]
[[ "$domain" =~ ^[a-z0-9][a-z0-9.-]*\.[a-z]{2,}$ ]]

ssh_args=(-o ConnectTimeout=12 -o "HostName=$tailnet_ip" -l "$user" "$oracle")
remote="$(cat <<'ORACLE'
set -euo pipefail
bind_ip=$1
for service in caddy jremote-login jremote-wss; do
    systemctl is-active --quiet "$service"
done

if (( EUID != 0 )); then root=(sudo); else root=(); fi
"${root[@]}" bash -s -- "$bind_ip" <<'ROOT'
set -euo pipefail
bind_ip=$1
helper=/usr/local/sbin/jremote-caddy-ingress
unit=jremote-caddy-ingress.service

work=$(mktemp -d)
trap 'rm -rf -- "$work"' EXIT
cat >"$work/helper" <<'HELPER'
#!/usr/bin/env bash
set -euo pipefail
bind_ip=$1
action=$2

iface=$(ip -o -4 address show scope global | awk -v wanted="$bind_ip" '
    { split($4, addr, "/"); if (addr[1] == wanted) { print $2; found++ } }
    END { if (found != 1) exit 1 }
')
rule=(-i "$iface" -d "$bind_ip/32" -p tcp
    -m conntrack --ctstate NEW -m multiport --dports 80,443
    -m comment --comment jremote-caddy-ingress -j ACCEPT)

case "$action" in
    apply)
        if ! iptables -w 5 -C INPUT "${rule[@]}" 2>/dev/null; then
            iptables -w 5 -I INPUT 1 "${rule[@]}"
        fi
        printf 'Ingreso local permitido en %s (%s), TCP 80 y 443.\n' "$iface" "$bind_ip"
        ;;
    remove)
        if iptables -w 5 -C INPUT "${rule[@]}" 2>/dev/null; then
            iptables -w 5 -D INPUT "${rule[@]}"
        fi
        ;;
    *) exit 2 ;;
esac
HELPER

cat >"$work/unit" <<UNIT
[Unit]
Description=JolufaSoft Remote Caddy IPv4 ingress
Wants=network-online.target
After=network-online.target netfilter-persistent.service ufw.service
Before=caddy.service

[Service]
Type=oneshot
RemainAfterExit=yes
ExecStart=$helper $bind_ip apply
ExecStop=$helper $bind_ip remove

[Install]
WantedBy=multi-user.target
UNIT

install -m 0755 "$work/helper" "$helper"
install -m 0644 "$work/unit" "/etc/systemd/system/$unit"
systemctl daemon-reload
systemctl enable "$unit"
if systemctl is-active --quiet "$unit"; then
    "$helper" "$bind_ip" apply
else
    systemctl start "$unit"
    printf 'Ingreso local persistente habilitado para %s, TCP 80 y 443.\n' "$bind_ip"
fi
ROOT
ORACLE
)"
printf -v invocation 'bash -c %q -- %q' "$remote" "$local_ip"
printf '=== Habilitando HTTP y HTTPS en el firewall del Oracle ===\n'
ssh -t "${ssh_args[@]}" "$invocation" </dev/tty

probe() {
    curl --noproxy '*' -4 -s --connect-timeout 5 --max-time 8 \
        -o /dev/null -w '%{http_code} %{time_connect} %{time_appconnect}' "$@"
}

api="https://$domain/jremote/devices"
api_args=(-H 'Content-Type: application/json' --data '{}')
https_route=(--resolve "$domain:443:$public_ip")

printf '\n=== Comprobando el acceso público desde Termux ===\n'
http_result=$(probe --resolve "$domain:80:$public_ip" "http://$domain/" || true)
https_result=$(probe "${https_route[@]}" "${api_args[@]}" "$api" || true)
read -r http_code http_tcp _ <<< "$http_result"
read -r https_code https_tcp _ <<< "$https_result"
printf 'HTTP 80: %s (TCP %ss)\nHTTPS 443: %s (TCP %ss)\n' \
    "$http_code" "$http_tcp" "$https_code" "$https_tcp"

if [[ "$http_code" == 000 && "$https_code" == 000 &&
      "$http_tcp" == 0.000000 && "$https_tcp" == 0.000000 ]]; then
    printf 'El firewall local quedó corregido. Los dos puertos públicos siguen sin completar TCP. Revisá el ingreso de la VNIC y la ruta pública en OCI.\n' >&2
    exit 3
fi

if [[ "$https_code" == 000 ]]; then
    printf 'Reintentando la emisión del certificado en Caddy.\n'
    ssh -t "${ssh_args[@]}" 'sudo systemctl reload caddy.service' </dev/tty
    for ((attempt = 1; attempt <= 12; attempt++)); do
        sleep 5
        https_result=$(probe "${https_route[@]}" "${api_args[@]}" "$api" || true)
        read -r https_code https_tcp _ <<< "$https_result"
        [[ "$https_code" == 000 ]] || break
    done
fi

wss_result=$(probe "${https_route[@]}" --http1.1 \
    -H 'Connection: Upgrade' -H 'Upgrade: websocket' \
    -H 'Sec-WebSocket-Version: 13' \
    -H 'Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==' \
    "https://$domain/jremote/tunnel" || true)
read -r wss_code _ <<< "$wss_result"
printf 'API HTTPS: %s\nWebSocket: %s\n' "$https_code" "$wss_code"
if [[ "$https_code" == 401 && "$wss_code" == 401 ]]; then
    printf 'Certificado válido. API y WebSocket responden públicamente y solicitan autenticación.\n'
    exit 0
fi

printf 'La verificación pública sigue pendiente. Últimos eventos de Caddy para %s:\n' "$domain" >&2
printf -v invocation 'sudo -n journalctl -u caddy.service --since %q --no-pager -o cat | grep -F %q | tail -8' \
    '-10 min' "$domain"
ssh -T "${ssh_args[@]}" "$invocation" </dev/null || true
exit 4
