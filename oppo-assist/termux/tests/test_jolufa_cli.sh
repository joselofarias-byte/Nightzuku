#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BIN="$ROOT/bin/jolufa"
FIX="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/fixtures"
export JOLUFA_CONFIG="/no/such/jolufa-config"
export JOLUFA_TOKEN="$(printf 'e%.0s' {1..64})"
export JOLUFA_DRY=1
export JOLUFA_ADB_PORT=4242

fail() { printf 'FAIL %s\n' "$1" >&2; exit 1; }

export JOLUFA_HOST=8.8.8.8
set +e
out="$( "$BIN" conectar 2>&1 )"
code=$?
set -e
[[ "$code" -ne 0 ]] || fail "publico debio fallar"
if grep -q "adb" <<<"$out"; then fail "publico no debe armar adb"; fi

export JOLUFA_HOST=100.64.8.8
out="$( "$BIN" conectar )"
grep -q "adb connect 100.64.8.8:4242" <<<"$out" || fail "connect"

export JOLUFA_DUMPSYS_FILE="$FIX/locked.dump"
set +e
out="$( "$BIN" tocar 10 20 2>&1 )"
code=$?
set -e
[[ "$code" -ne 0 ]] || fail "bloqueo debio negar"
if grep -q "input" <<<"$out"; then fail "no debe inyectar con bloqueo"; fi

export JOLUFA_DUMPSYS_FILE="$FIX/unknown.dump"
set +e
out="$( "$BIN" pantalla 2>&1 )"
code=$?
set -e
[[ "$code" -ne 0 ]] || fail "desconocido debio negar"
if grep -q "scrcpy" <<<"$out"; then fail "no debe abrir scrcpy sin estado claro"; fi

export JOLUFA_DUMPSYS_FILE="$FIX/unlocked.dump"
out="$( "$BIN" tocar 10 20 )"
grep -q "input tap 10 20" <<<"$out" || fail "tap"
out="$( "$BIN" pantalla )"
grep -q "scrcpy" <<<"$out" || fail "pantalla"
if grep -q "8.8.8.8" <<<"$out"; then fail "pantalla publica"; fi

set +e
out="$( "$BIN" reiniciar 2>&1 )"
code=$?
set -e
[[ "$code" -ne 0 ]] || fail "reboot sin confirmacion"
if grep -q "reboot" <<<"$out"; then fail "reboot no debe armarse"; fi

out="$( "$BIN" reiniciar --confirmo )"
grep -q "reboot" <<<"$out" || fail "reboot confirmado"

out="$( "$BIN" cerrar-sesion )"
grep -q "adb_wifi_enabled" <<<"$out" || fail "cerrar"
if grep -q " 1" <<<"$out"; then
  if grep -q "adb_wifi_enabled 1" <<<"$out"; then fail "no encender"; fi
fi

printf '%s\n' "jolufa cli ok"
