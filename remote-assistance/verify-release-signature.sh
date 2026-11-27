#!/usr/bin/env bash
set -euo pipefail

if (( $# != 1 )); then
    printf 'Uso: bash %s archivo.apk\n' "$0" >&2
    exit 2
fi

sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
: "${sdk:?Configura ANDROID_HOME con Build Tools 36.0.0}"
expected=d47757640fd2c3252b6b3f5c5e9824f285316ef337227c9a44f048b1bbfb539d

certificates=$(LC_ALL=C "$sdk/build-tools/36.0.0/apksigner" verify --print-certs "$1")
actual=$(awk '/^Signer #[0-9]+ certificate SHA-256 digest:/ { print $NF }' <<<"$certificates")
if [[ "$actual" != "$expected" ]]; then
    printf 'APK rechazado. Se esperaba un único firmante SHA-256 %s.\n' "$expected" >&2
    printf 'Firmantes detectados: %s\n' "${actual:-ninguno}" >&2
    exit 1
fi
printf 'Firma de publicación verificada: %s\n' "$actual"
