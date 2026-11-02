# Qué se verificó en esta rama

Corrida en la máquina de desarrollo, el 2026-11-02. No se conectó ningún teléfono. No se usó el HONOR 200, ni el OPPO, ni el Oracle.

## Verificado

- Lectura del código de Nightzuku citada en `auditoria-nightzuku.md`, incluido `BootCompleteReceiver`, el otorgamiento de `WRITE_SECURE_SETTINGS` y `NightDogRecovery`.
- `:core:test` con Gradle 8.11.1 y JDK 21: 18 pruebas, 0 fallos. Incluye que un puerto observado junto a `192.168.1.20` o `8.8.8.8` no copia esa IP al JSON remoto.
  - JSON, direcciones Tailscale (incluido el archivo `shared/host-cases.list`), elección de interfaz, token.
  - SMS común, código de un solo uso, RCS, vencimiento del consentimiento, que el HTTP no entrega el código pendiente y que `sms.approve` no llega al manejador.
  - Paquetes protegidos, línea base, contactos mal formados.
  - El servidor rechaza `8.8.8.8` y `0.0.0.0` antes de escuchar.
  - El fuente del agente no abre `ServerSocket`, no nombra accesibilidad, `locksettings` ni `DevicePolicyManager`, y usa `BindPolicy.production()`.
- `python3 -m unittest discover -s oppo-assist/termux/tests`: 8 pruebas, 0 fallos. Una comprueba que un puerto informado no cambia el host Tailscale ni adopta `192.168.1.20`.
- `bash oppo-assist/termux/tests/test_jolufa_cli.sh`: el host público no arma `adb`; con bloqueo o volcado desconocido no hay `input` ni `scrcpy`; desbloqueado sí; `reiniciar` sin `--confirmo` no arma `reboot`; `cerrar-sesion` no escribe el valor 1; con ADB caído, `recuperar` imprime `ALERTA` y reconecta a `100.64.8.8:39999`.
- `:agent:assembleDebug` terminó bien. APK resultante:
  - ruta: `oppo-assist/agent/build/outputs/apk/debug/agent-debug.apk`
  - tamaño, después de sumar la observación del puerto: 8624591 bytes
  - `aapt dump badging`: `com.joselofarias.oppoassist`, versionName `1.0.0`, minSdk 29, targetSdk 35, compileSdk 35
  - permisos presentes: SMS, contactos, notificaciones, servicio en primer plano `specialUse`, arranque, `QUERY_ALL_PACKAGES`
- El APK no se commitea. El workflow `.github/workflows/oppo-assist.yml` vuelve a correr las pruebas y `assembleDebug`.

## No verificado (hipótesis o comportamiento de plataforma)

- Que el OPPO deje el socket atado a la IP de Tailscale cuando Tailscale está conectado.
- Que `adb pair` / `adb connect` contra la IP `100.` funcione en ese ColorOS. El adbd suele escuchar en todas las interfaces; no se midió.
- Que Nightzuku, después de un reinicio real, deje `adb_wifi_enabled` en 1 y que mDNS publique un puerto usable. El receptor existe y las condiciones están en la auditoría. No se arrancó el teléfono.
- Que la depuración inalámbrica se apague con `settings put global adb_wifi_enabled 0` en ese modelo.
- Que `toybox date` o `auto_time` funcionen con UID 2000.
- scrcpy desde Termux:X11 + Debian `proot` en el HONOR 200, y que no reaparezca el diálogo de captura.
- Que ColorOS no mate el servicio ni se trague `SMS_RECEIVED`.
- Que después de un reinicio se pueda volver a controlar el teléfono. El código no lo promete y no hay evidencia en un aparato.
- La matriz marca `demonstratedOnTargetDevice=false` en todas las capacidades a propósito.
