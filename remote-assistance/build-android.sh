#!/usr/bin/env bash
set -euo pipefail

mode="${1:-release}"
gradle_args=()
case "$mode" in
    local)
        variant=localRelease
        ;;
    unsigned)
        variant=localRelease
        gradle_args+=(-PjremoteUnsigned=true)
        ;;
    release)
        variant=release
        : "${SCRCPY_KEYSTORE:?Configura el almacén de claves de publicación}"
        : "${SCRCPY_KEYSTORE_PASSWORD:?Configura la contraseña del almacén de publicación}"
        : "${SCRCPY_KEY_ALIAS:?Configura el alias de la clave de publicación}"
        : "${SCRCPY_KEY_PASSWORD:?Configura la contraseña de la clave de publicación}"
        SCRCPY_KEYSTORE="$(realpath -e -- "$SCRCPY_KEYSTORE")"
        export SCRCPY_KEYSTORE
        ;;
    *) echo "Uso: bash remote-assistance/build-android.sh [local|unsigned|release]" >&2; exit 2 ;;
esac
sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
: "${sdk:?Configura ANDROID_HOME con Android SDK 36 y Build Tools 36.0.0}"
sdk="$(cd -- "$sdk" && pwd)"
source_dir="$(cd -- "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
output_dir="${JREMOTE_OUTPUT_DIR:-$source_dir/dist}"
if [[ "$mode" != release ]]; then
    output_dir="$output_dir/$mode"
fi
mkdir -p "$output_dir"
output_dir="$(cd -- "$output_dir" && pwd)"
upstream=48886d9a633179a64c0c10cc95b9df1bac046efa
workdir="$(mktemp -d)"
trap 'rm -rf -- "$workdir"' EXIT
git -C "$workdir" init -q
git -C "$workdir" fetch --depth=1 https://github.com/dhun2666/scrcpy-client-for-android.git "$upstream"
git -C "$workdir" checkout -q --detach FETCH_HEAD
[[ "$(git -C "$workdir" rev-parse HEAD)" == "$upstream" ]]
git -C "$workdir" apply --check "$source_dir/android-client.patch"
git -C "$workdir" apply "$source_dir/android-client.patch"

(
    cd "$workdir"
    ANDROID_HOME="$sdk" ./gradlew --no-daemon \
        "${gradle_args[@]}" :app:testDebugUnitTest :libadb:testDebugUnitTest ":app:assemble${variant^}"
)
shopt -s nullglob
apks=("$workdir/app/build/outputs/apk/$variant/"*.apk)
if (( ${#apks[@]} != 1 )); then
    printf 'Se esperaba un único APK para %s.\n' "$variant" >&2
    exit 1
fi
apk=${apks[0]}
apksigner="$sdk/build-tools/36.0.0/apksigner"
case "$mode" in
    release) bash "$source_dir/verify-release-signature.sh" "$apk" ;;
    local) "$apksigner" verify --print-certs "$apk" ;;
    unsigned)
        if "$apksigner" verify "$apk" >/dev/null 2>&1; then
            printf 'El paquete intermedio debe estar sin firma.\n' >&2
            exit 1
        fi
        ;;
esac
badging=$("$sdk/build-tools/36.0.0/aapt" dump badging "$apk")
version=$(sed -nE "s/^package: .*versionName='([^']+)'.*/\1/p" <<<"$badging")
[[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]
filename="JolufaSoft-Remote-$version-$mode.apk"
install -m 644 "$apk" "$output_dir/$filename"
(
    cd "$output_dir"
    sha256sum "$filename" > SHA256SUMS
)
printf '%s\n' "$upstream" > "$output_dir/upstream-commit.txt"
printf '%s\n' "APK: $output_dir/$filename"
