#!/data/data/com.termux/files/usr/bin/bash
set -u

# HONOR 200 / Android 16 physical validation for Nightzuku TAPI.
# Produces one Markdown report in shared Downloads.
# Optional: TARGET=ip:port INSTALL=0 RUN_ID=... ARTIFACT=...

RUN_ID="${RUN_ID:-35673782416}"
ARTIFACT="${ARTIFACT:-nightzuku-manager-release-signed-5f58ab8}"
INSTALL="${INSTALL:-1}"
PKG="com.joselofarias.nightzuku"
DOWNLOADS="$HOME/storage/downloads"
WORK="$HOME/.cache/nightzuku-tapi-physical"
OUT="$WORK/HONOR200-TAPI-PHYSICAL-VALIDATION.md"
FINAL="$DOWNLOADS/HONOR200-TAPI-PHYSICAL-VALIDATION.md"

mkdir -p "$DOWNLOADS" "$WORK/artifact" "$WORK/runtime"
rm -rf "$WORK/artifact" "$WORK/runtime"
mkdir -p "$WORK/artifact" "$WORK/runtime"

if [ -z "${TARGET:-}" ]; then
  TARGET="$(adb devices | awk 'NR>1 && $2=="device" {print $1; exit}')"
fi

{
  echo "# HONOR 200 - Nightzuku TAPI physical validation"
  echo
  echo "Fecha: $(date -Iseconds)"
  echo "Run: $RUN_ID"
  echo "Artifact: $ARTIFACT"
  echo "Target: ${TARGET:-NO_CONECTADO}"
  echo

  echo "## ADB"
  adb devices -l 2>&1
  if [ -z "${TARGET:-}" ]; then
    echo "ADB_TARGET_NO_DISPONIBLE"
    exit 20
  fi
  adb -s "$TARGET" shell 'id; getprop ro.product.manufacturer; getprop ro.product.model; getprop ro.build.version.release; getprop ro.build.version.sdk' 2>&1
  echo

  echo "## Descargar APK firmado de CI"
  gh run download "$RUN_ID" -R joselofarias-byte/Nightzuku -n "$ARTIFACT" -D "$WORK/artifact" 2>&1
  APK="$(find "$WORK/artifact" -type f -name '*.apk' | head -n 1)"
  echo "APK=$APK"
  if [ -z "$APK" ] || [ ! -s "$APK" ]; then
    echo "APK_NO_ENCONTRADO"
    exit 21
  fi
  sha256sum "$APK" 2>&1 || true
  echo

  if [ "$INSTALL" = "1" ]; then
    echo "## Instalar Nightzuku"
    adb -s "$TARGET" install -r "$APK" 2>&1
    echo "INSTALL_RC=$?"
    echo
  else
    echo "## Instalacion omitida por INSTALL=0"
    echo
  fi

  echo "## Version instalada"
  adb -s "$TARGET" shell "dumpsys package $PKG | grep -m 12 -E 'versionName=|versionCode=|lastUpdateTime='" 2>&1 || true
  echo

  echo "## Extraer TAPI y rish dex del APK probado"
  unzip -p "$APK" assets/tapi > "$WORK/runtime/tapi" 2>/dev/null || true
  unzip -p "$APK" assets/rish_shizuku.dex > "$WORK/runtime/rish_shizuku.dex" 2>/dev/null || true
  chmod 700 "$WORK/runtime/tapi" 2>/dev/null || true
  chmod 400 "$WORK/runtime/rish_shizuku.dex" 2>/dev/null || true
  ls -l "$WORK/runtime" 2>&1 || true
  echo

  echo "## TAPI status"
  echo "Si TAPI esta desactivado, debe rechazar rapido. Si esta activado, debe mostrar estado."
  if [ -x "$WORK/runtime/tapi" ] && [ -s "$WORK/runtime/rish_shizuku.dex" ]; then
    (cd "$WORK/runtime" && RISH_DEBUG=1 timeout 15 ./tapi nightzuku status) 2>&1
    echo "TAPI_STATUS_RC=$?"
  else
    echo "TAPI_RUNTIME_NO_DISPONIBLE"
  fi
  echo

  echo "## rish normal"
  if command -v rish >/dev/null 2>&1; then
    RISH_DEBUG=1 timeout 15 rish -c id 2>&1
    echo "RISH_RC=$?"
  elif [ -x "$HOME/rish" ]; then
    RISH_DEBUG=1 timeout 15 "$HOME/rish" -c id 2>&1
    echo "RISH_RC=$?"
  else
    echo "RISH_WRAPPER_NO_ENCONTRADO"
  fi
  echo

  echo "## Siguiente pasada"
  echo "Si TAPI estaba desactivado, activalo en Nightzuku > Funciones experimentales > TAPI y ejecuta:"
  echo "INSTALL=0 TARGET=$TARGET ~/HONOR200-TAPI-PHYSICAL-VALIDATION.sh"
} > "$OUT" 2>&1
RC=$?
cp -f "$OUT" "$FINAL"
echo "Informe final: $FINAL"
echo "EXIT=$RC"
exit "$RC"
