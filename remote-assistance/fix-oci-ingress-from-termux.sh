#!/usr/bin/env bash
set -euo pipefail

if (( $# != 7 )); then
    printf 'Uso: bash %s repo-privado alias usuario ip-tailscale ip-vnic ip-publica dominio\n' "$0" >&2
    exit 2
fi
repo=$1
shift
local_ip=$4
public_ip=$5
[[ "$repo" =~ ^[a-zA-Z0-9_.-]+/[a-zA-Z0-9_.-]+$ ]]
for address in "$local_ip" "$public_ip"; do
    [[ "$address" =~ ^([0-9]{1,3}\.){3}[0-9]{1,3}$ ]]
done
branch=jremote-oci-ingress
workflow=.github/workflows/jremote-oci-ingress.yml
base=https://raw.githubusercontent.com/joselofarias-byte/Nightzuku/7c7d666a99dc588452b1b6d3d857fd142bfe62db/remote-assistance
work=$(mktemp -d)
trap 'rm -rf -- "$work"' EXIT

if ! command -v gh >/dev/null || ! gh auth status --hostname github.com >/dev/null 2>&1; then
    printf 'Ejecutá el comando desde la sesión de Termux que ya tiene gh autenticado.\n' >&2
    exit 2
fi
default_branch=$(gh api "repos/$repo" --jq 'select(.private) | .default_branch')
if [[ -z "$default_branch" ]]; then
    printf 'El repositorio de ejecución debe ser privado.\n' >&2
    exit 2
fi

cat >"$work/workflow.yml" <<'YAML'
name: JolufaSoft Remote OCI ingress
on:
  push:
    branches: [jremote-oci-ingress]
    paths: [.github/workflows/jremote-oci-ingress.yml]
permissions: {}
concurrency: jremote-oci-ingress
jobs:
  ingress:
    runs-on: ubuntu-latest
    timeout-minutes: 6
    steps:
      - name: Install OCI SDK
        run: |
          python3 -m venv "$RUNNER_TEMP/jremote-oci"
          "$RUNNER_TEMP/jremote-oci/bin/pip" install --quiet 'oci==2.188.0'
      - name: Apply ingress to the JolufaSoft Remote VNIC
        env:
          OCI_CONFIG_B64: ${{ secrets.OCI_CONFIG_B64 }}
          OCI_KEY_B64: ${{ secrets.OCI_KEY_B64 }}
YAML
printf '          JREMOTE_PUBLIC_IP: "%s"\n          JREMOTE_PRIVATE_IP: "%s"\n' \
    "$public_ip" "$local_ip" >>"$work/workflow.yml"
cat >>"$work/workflow.yml" <<'YAML'
        shell: bash
        run: |
          "$RUNNER_TEMP/jremote-oci/bin/python" - <<'PY'
          import base64
          import configparser
          import ipaddress
          import os
          import sys
          import tempfile
          from hashlib import sha256
          from pathlib import Path

          import oci
          from oci.core import models


          def apply_ingress(client, public_address, private_address):
              public = client.get_public_ip_by_ip_address(
                  models.GetPublicIpByIpAddressDetails(ip_address=public_address)
              ).data
              private_id = public.assigned_entity_id or public.private_ip_id
              if not private_id or not private_id.startswith("ocid1.privateip."):
                  sys.exit("La IP pública no está asignada a una IP privada de una VNIC.")
              private = client.get_private_ip(private_id).data
              if private.ip_address != private_address:
                  sys.exit("La IP pública apunta a otra IP privada.")

              vnic = client.get_vnic(private.vnic_id).data
              subnet = client.get_subnet(vnic.subnet_id).data
              if subnet.prohibit_internet_ingress:
                  sys.exit("La subred prohíbe el ingreso desde Internet.")
              route_id = private.route_table_id or vnic.route_table_id or subnet.route_table_id
              route = client.get_route_table(route_id).data
              gateways = {
                  rule.network_entity_id
                  for rule in route.route_rules
                  if (rule.destination or rule.cidr_block) == "0.0.0.0/0"
                  and rule.network_entity_id.startswith("ocid1.internetgateway.")
              }
              if not any(client.get_internet_gateway(gateway).data.is_enabled for gateway in gateways):
                  sys.exit("La ruta efectiva carece de una salida IPv4 por defecto a un Internet Gateway habilitado.")
              print("IP pública, VNIC y ruta por Internet Gateway verificadas.")

              suffix = sha256(vnic.id.encode()).hexdigest()[:12]
              name = f"jremote-caddy-ingress-{suffix}"
              groups = oci.pagination.list_call_get_all_results(
                  client.list_network_security_groups,
                  compartment_id=vnic.compartment_id,
                  vcn_id=subnet.vcn_id,
                  display_name=name,
              ).data
              groups = [group for group in groups if group.display_name == name]
              if len(groups) > 1:
                  sys.exit("Hay más de un NSG con el nombre dedicado.")
              group = groups[0] if groups else client.create_network_security_group(
                  models.CreateNetworkSecurityGroupDetails(
                      compartment_id=vnic.compartment_id, vcn_id=subnet.vcn_id, display_name=name,
                  )
              ).data

              rules = oci.pagination.list_call_get_all_results(
                  client.list_network_security_group_security_rules, group.id
              ).data
              missing = []
              for port in (80, 443):
                  if any(
                      rule.direction == "INGRESS" and rule.protocol == "6"
                      and rule.source == "0.0.0.0/0" and not rule.is_stateless
                      and rule.tcp_options and rule.tcp_options.source_port_range is None
                      and rule.tcp_options.destination_port_range
                      and rule.tcp_options.destination_port_range.min == port
                      and rule.tcp_options.destination_port_range.max == port
                      for rule in rules
                  ):
                      continue
                  missing.append(models.AddSecurityRuleDetails(
                      direction="INGRESS", protocol="6", source="0.0.0.0/0",
                      source_type="CIDR_BLOCK", is_stateless=False,
                      description=f"JolufaSoft Remote Caddy TCP {port}",
                      tcp_options=models.TcpOptions(
                          destination_port_range=models.PortRange(min=port, max=port)
                      ),
                  ))
              if missing:
                  client.add_network_security_group_security_rules(
                      group.id, models.AddNetworkSecurityGroupSecurityRulesDetails(security_rules=missing)
                  )

              current = client.get_vnic(vnic.id)
              attached = current.data.nsg_ids or []
              if group.id not in attached:
                  etag = current.headers.get("etag")
                  if not etag:
                      sys.exit("OCI no devolvió un ETag para actualizar los grupos de la VNIC.")
                  client.update_vnic(
                      vnic.id, models.UpdateVnicDetails(nsg_ids=[*attached, group.id]), if_match=etag
                  )
              print("Ingreso OCI configurado para TCP 80 y 443 en la VNIC de JolufaSoft Remote.")


          try:
              public_address = str(ipaddress.IPv4Address(os.environ["JREMOTE_PUBLIC_IP"]))
              private_address = str(ipaddress.IPv4Address(os.environ["JREMOTE_PRIVATE_IP"]))
              with tempfile.TemporaryDirectory() as directory:
                  key_path = Path(directory) / "key.pem"
                  if not os.environ["OCI_CONFIG_B64"] or not os.environ["OCI_KEY_B64"]:
                      sys.exit("Faltan los secretos OCI_CONFIG_B64 u OCI_KEY_B64 del repositorio privado.")
                  key_path.write_bytes(base64.b64decode(os.environ["OCI_KEY_B64"]))
                  key_path.chmod(0o600)
                  config = configparser.ConfigParser(interpolation=None)
                  config.read_string(base64.b64decode(os.environ["OCI_CONFIG_B64"]).decode())
                  profile = dict(config["DEFAULT"])
                  profile["key_file"] = str(key_path)
                  profile["region"] = "sa-saopaulo-1"
                  profile.pop("key_content", None)
                  client = oci.core.VirtualNetworkClient(profile, timeout=(5, 20))
                  apply_ingress(client, public_address, private_address)
          except oci.exceptions.ServiceError as error:
              sys.exit(f"OCI rechazó {error.operation_name}: HTTP {error.status}, {error.code}.")
          PY
YAML

printf '# Ejecución solicitada desde Termux: %s-%s\n' "$(date -u +%s)" "$$" >>"$work/workflow.yml"
printf '=== Aplicando el ingreso OCI desde el repositorio privado ===\n'
if ! gh api "repos/$repo/git/ref/heads/$branch" >/dev/null 2>&1; then
    head=$(gh api "repos/$repo/git/ref/heads/$default_branch" --jq '.object.sha')
    gh api -X POST "repos/$repo/git/refs" -f "ref=refs/heads/$branch" -f "sha=$head" >/dev/null
fi

update=()
if current=$(gh api "repos/$repo/contents/$workflow?ref=$branch" --jq '.sha' 2>"$work/github-error"); then
    update=(-f "sha=$current")
elif ! grep -q 'HTTP 404' "$work/github-error"; then
    cat "$work/github-error" >&2
    exit 2
fi
content=$(base64 <"$work/workflow.yml" | tr -d '\n')
commit=$(gh api -X PUT "repos/$repo/contents/$workflow" \
    -f 'message=Aplicar ingreso OCI para JolufaSoft Remote' -f "branch=$branch" \
    -f "content=$content" "${update[@]}" --jq '.commit.sha')

run=
for ((attempt = 1; attempt <= 30; attempt++)); do
    run=$(gh run list -R "$repo" --commit "$commit" --event push --json databaseId,name \
        --jq '.[] | select(.name == "JolufaSoft Remote OCI ingress") | .databaseId')
    [[ -z "$run" ]] || break
    sleep 3
done
if [[ -z "$run" ]]; then
    printf 'El commit %s quedó creado, pero GitHub Actions todavía no inició el workflow.\n' "$commit" >&2
    exit 3
fi
printf 'GitHub Actions: ejecución %s.\n' "$run"
for ((attempt = 1; attempt <= 90; attempt++)); do
    result=$(gh run view -R "$repo" "$run" --json status,conclusion \
        --jq 'if .status == "completed" then .conclusion else .status end')
    case "$result" in
        queued|in_progress|pending|requested|waiting) sleep 5 ;;
        *) break ;;
    esac
done
if [[ "$result" != success ]]; then
    gh run view -R "$repo" "$run" --log-failed
    exit 4
fi

printf '\n=== Verificando certificado, API y WebSocket ===\n'
curl -fsSLo "$work/verify.sh" "$base/fix-https-ingress-from-termux.sh"
bash "$work/verify.sh" "$@"
