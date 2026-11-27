#!/usr/bin/env bash
set -Eeuo pipefail

if (( $# != 7 )); then
    printf 'Uso: bash %s alias usuario ip-tailscale ip-vnic ip-publica dominio archivo-token\n' "$0" >&2
    exit 2
fi
oracle=$1
user=$2
tailnet_ip=$3
local_ip=$4
expected=$5
domain=$6
token_file=$7
ssh_options=(-o ConnectTimeout=12 -o "HostName=$tailnet_ip" -l "$user")
relay_version=faf97d571c24611d553ca33d14bdd749445654b5
stage="inicio"
trap 'status=$?; printf "Error en %s, línea %s (código %s).\n" "$stage" "$LINENO" "$status" >&2; exit "$status"' ERR

printf '%s\n' '[1/3] Comprobando la IP del Oracle...'
stage="comprobación del Oracle"
for address in "$tailnet_ip" "$local_ip" "$expected"; do
    [[ "$address" =~ ^([0-9]{1,3}\.){3}[0-9]{1,3}$ ]]
done
[[ "$domain" =~ ^[a-z0-9][a-z0-9.-]*\.[a-z]{2,}$ ]]
[[ "$oracle" =~ ^[A-Za-z0-9][A-Za-z0-9_.-]*$ ]]
command -v proot-distro >/dev/null
ip="$(ssh "${ssh_options[@]}" "$oracle"     "curl -4fsS --interface $local_ip --max-time 12 https://api.ipify.org")"
if [[ "$ip" != "$expected" ]]; then
    printf 'La salida IPv4 de Oracle fue %s. Se esperaba %s. DNS sigue intacto.\n' "$ip" "$expected" >&2
    exit 2
fi

printf '%s\n' '[2/3] Validando Cloudflare y el registro DNS...'
stage="registro de Cloudflare"
proot-distro login debian -- python3 -u -c "$(cat <<'PY'
import json
import sys
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

ip, host, token_path, zone_id = sys.argv[1:]
zone = host.split(".", 1)[1]
try:
    token = Path(token_path).read_text().strip()
except OSError:
    sys.exit(f"Debian no pudo leer el token en {token_path}.")
if not token or any(c.isspace() for c in token):
    sys.exit("El archivo no contiene un token DNS válido.")

def request(path, method="GET", data=None):
    req = urllib.request.Request(
        "https://api.cloudflare.com/client/v4" + path,
        data=None if data is None else json.dumps(data).encode(),
        headers={"Authorization": "Bearer " + token, "Content-Type": "application/json"},
        method=method,
    )
    try:
        with urllib.request.urlopen(req, timeout=15) as response:
            result = json.load(response)
    except urllib.error.HTTPError as exc:
        try:
            codes = ",".join(str(e["code"]) for e in json.load(exc).get("errors", []))
        except (OSError, ValueError, KeyError, TypeError):
            codes = ""
        sys.exit(f"Cloudflare HTTP {exc.code} en {method} {path}. Códigos de API: {codes or 'sin detalle'}.")
    except (OSError, ValueError):
        sys.exit(f"No se pudo consultar Cloudflare en {method} {path}.")
    if not result.get("success"):
        sys.exit("Cloudflare rechazó la operación DNS.")
    return result["result"]

if not zone_id:
    zones = request("/zones?" + urllib.parse.urlencode({"name": zone, "status": "active"}))
    if len(zones) != 1:
        sys.exit(f"El token no permitió identificar la zona activa {zone}.")
    zone_id = zones[0]["id"]
path = f"/zones/{zone_id}/dns_records"
records = request(path + "?" + urllib.parse.urlencode({"name": host}))
addresses = [r for r in records if r["type"] in ("A", "AAAA", "CNAME", "NS")]
if addresses:
    if len(addresses) != 1 or any(
        (r["type"], r["content"], r.get("proxied")) != ("A", ip, False)
        for r in addresses
    ):
        sys.exit(f"{host} ya tiene un registro incompatible. Se conservó ese registro.")
    print(f"DNS ya correcto: {host} -> {ip}")
else:
    request(path, "POST", {"type": "A", "name": host, "content": ip, "ttl": 1, "proxied": False})
    print(f"DNS creado: {host} -> {ip}")
PY
)" "$ip" "$domain" "$token_file" "${CF_ZONE_ID:-}"

printf '%s\n' '[3/3] Esperando DNS e instalando el relay HTTPS...'
stage="instalación en Oracle"
remote="$(cat <<'ORACLE'
set -euo pipefail
domain=$1
bind_ip=$2
expected=$3
revision=$4
ready=0
for attempt in $(seq 1 36); do
    if getent ahostsv4 "$domain" | awk -v expected="$expected" '
        $1 == expected { ok = 1 }
        END { exit !ok }
    '; then
        ready=1
        break
    fi
    sleep 5
done
if (( ! ready )); then
    printf 'El registro DNS aún no es visible desde Oracle. Volvé a ejecutar el mismo comando.\n' >&2
    exit 2
fi
dir="$(mktemp -d)"
trap 'rm -rf -- "$dir"' EXIT
base="https://raw.githubusercontent.com/joselofarias-byte/Nightzuku/$revision/remote-assistance"
for file in setup-https-relay.sh relay-login.py relay-wss.py; do
    curl -fsSLo "$dir/$file" "$base/$file"
done
sudo bash "$dir/setup-https-relay.sh" "$domain" "$bind_ip"
ORACLE
)"
printf -v invocation 'bash -c %q -- %q %q %q %q' "$remote" "$domain" "$local_ip" "$expected" "$relay_version"
ssh -t "${ssh_options[@]}" "$oracle" "$invocation"
