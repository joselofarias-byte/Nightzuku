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

log="$(mktemp)"
trap 'rm -f -- "$log"' EXIT
remote="$(cat <<'ORACLE'
set -eu
domain=$1
bind_ip=$2
printf '=== Oracle: servicios del relay ===\n'
for unit in caddy jremote-login jremote-wss; do
    printf '%s: ' "$unit"
    systemctl is-active "$unit" || true
done

printf '\n=== Oracle: API interna y Caddy con SNI ===\n'
for endpoint in internal caddy; do
    if [[ "$endpoint" == internal ]]; then
        url='http://127.0.0.1:17666/jremote/devices'
        route=()
    else
        url="https://$domain/jremote/devices"
        route=(--resolve "$domain:443:$bind_ip")
    fi
    curl --noproxy '*' -4 -sS --connect-timeout 3 --max-time 5         "${route[@]}" -o /dev/null -w "$endpoint: HTTP %{http_code}, TCP %{time_connect}s, TLS %{time_appconnect}s\n"         -H 'Content-Type: application/json' --data '{}' "$url" || true
done

printf '\n=== Oracle: escucha TCP ===\n'
sudo -n ss -H -lntp '( sport = :443 or sport = :17666 or sport = :17667 )' || true
printf '\n=== Oracle: cabeceras TCP 443 durante la prueba pública ===\n'
if command -v tcpdump >/dev/null 2>&1; then
    LC_ALL=C sudo -n timeout 16 tcpdump -p -nn -l -s 96 -i any -c 12         "host $bind_ip and tcp port 443 and tcp[tcpflags] & (tcp-syn|tcp-rst) != 0" || true
else
    printf 'Sin tcpdump: no se puede confirmar si llegan paquetes al host.\n'
fi

printf '\n=== Oracle: reglas INPUT y contadores ===\n'
if command -v iptables >/dev/null 2>&1; then
    sudo -n iptables -w 2 -n -v -L INPUT --line-numbers | head -35 || true
fi

printf '\n=== Oracle: eventos recientes del certificado ===\n'
sudo -n journalctl -u caddy.service --since '-20 min' --no-pager -o cat     | grep -F "$domain" | grep -Ei 'certificate|challenge|acme|tls|obtain' | tail -8 || true
ORACLE
)"
printf -v invocation 'bash -c %q -- %q %q' "$remote" "$domain" "$local_ip"

ssh -T -o ConnectTimeout=10 -o BatchMode=yes -o "HostName=$tailnet_ip"     -l "$user" "$oracle" "$invocation" </dev/null >"$log" 2>&1 &
watcher=$!
for attempt in $(seq 1 20); do
    if grep -q 'listening on\|Sin tcpdump\|sudo: a password is required' "$log"; then
        break
    fi
    if ! kill -0 "$watcher" 2>/dev/null; then
        break
    fi
    sleep 1
done

printf '=== Termux: prueba pública IPv4, con SNI y sin proxy HTTP ===\n'
curl --noproxy '*' -4 -sS --connect-timeout 8 --max-time 10     --resolve "$domain:443:$public_ip"     -o /dev/null -w 'Público: HTTP %{http_code}, TCP %{time_connect}s, TLS %{time_appconnect}s\n'     -H 'Content-Type: application/json' --data '{}' "https://$domain/jremote/devices" || true

if ! wait "$watcher"; then
    printf '\nSSH terminó con error.\n'
fi
printf '\n'
cat "$log"
