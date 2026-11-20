# Recuperación automática ADB y rish en HONOR 200

Equipo objetivo: HONOR 200 ELI-NX9, Android 16 / MagicOS 10, sin root, paquete `com.joselofarias.nightzuku` y Termux (`com.termux` o forks compatibles).
Este documento describe la implementación actual y la matriz pendiente en el teléfono; las pruebas JVM y la compilación no sustituyen la validación física.

## Comportamiento implementado

1. **Redescubrimiento por intento (`AdbMdns`, `AdbTransportResolver`)**:
   - Cada intento crea una sesión NSD fresca (`_adb-tls-connect._tcp`, timeout 8 s) y la cierra al finalizar o cancelar.
   - Se eliminó la caché estática de endpoints mDNS y el fallback a `getprop service.adb.tcp.port`.
   - El fallback TCP legacy solo se usa si el usuario lo habilitó explícitamente y su host pertenece a una interfaz local actual (`AdbEndpointPolicy`).
   - Los callbacks tardíos de sesiones NSD detenidas o reemplazadas se descartan mediante generación de sesión.

2. **Autenticación, verificación shell y respeto de autorización (`AdbClient`, `BootCompleteReceiver`, `StarterActivity`)**:
   - Autentica con la clave ADB ya emparejada (`requireTls` según transporte; `allowPublicKeyPrompt = false` en recuperación headless).
   - Antes de ejecutar `Starter.internalCommand`, verifica que el transporte permita abrir y ejecutar un comando shell real (`AdbClient.verifyShell()`).
   - No reactiva `adb_wifi_enabled` en segundo plano ni abre actividades desde el watchdog. Si Android desactivó la depuración inalámbrica o revocó el emparejamiento, informa el estado para completar el flujo oficial de autorización/emparejamiento.

3. **Confirmación de Binder y `rish` (`NightDogRecovery`, `ShellBinderRequestHandler`, `ShizukuShellLoader`)**:
   - `NightDogRecovery` solo declara `Stage.RUNNING` tras confirmar `Shizuku.pingBinder()` (ventana acotada de 6 s).
   - `ShellBinderRequestHandler` valida `pingBinder()` antes de entregar Binder a `rish`.
   - `ShizukuShellLoader` cancela el timeout de adquisición de 5 s al recibir Binder (`BinderAcquisitionGuard`) para que una sesión `rish` iniciada no se corte al superar los 5 segundos.

4. **Concurrencia, parada manual y diagnóstico (`NightDogRecovery`, `HomeDialogs`)**:
   - Eventos duplicados no lanzan recuperaciones simultáneas.
   - Un token de intento descarta callbacks tardíos tras cancelación o parada manual y cierra inmediatamente las conexiones `AdbClient` activas.
   - `HomeStopDialog` invoca `NightDogRecovery.prepareForManualStop(context)` antes de `Shizuku.exit()`, y un Binder tardío no vuelve a habilitar `desiredRunning`.
   - Se distinguen `WIRELESS_DEBUGGING_DISABLED`, `ENDPOINT_ABSENT`, `OFFLINE`, `AUTH_FAILED`, `SHELL_UNAVAILABLE`, `BINDER_NOT_CONFIRMED` y `UNKNOWN`, con backoff exponencial acotado (8 s, 16 s, 32 s, máximo 60 s).

## Verificación automatizada

```sh
bash ./gradlew --no-daemon -Pandroid.injected.build.abi=arm64-v8a \
  :manager:testDebugUnitTest :shell:testDebugUnitTest :manager:assembleDebug
```

## Validación física pendiente en HONOR 200 (no ejecutada en este entorno)

1. **Cambio de Wi-Fi o puerto dinámico**: desconectar/reconectar Wi-Fi o reiniciar depuración inalámbrica, matar `shizuku_server` y verificar que NightDog redescubre el nuevo puerto, autentica, valida shell y recupera Binder sin depender de puertos previos.
2. **`rish` > 5 segundos tras recuperación**: desde Termux, ejecutar `rish -c "sleep 6 && id"` tras la reconexión de Binder y confirmar que adquiere Binder y no se corta al segundo 5.
3. **Revocación de autorización / depuración desactivada**: revocar el emparejamiento inalámbrico y desactivar depuración inalámbrica; comprobar que NightDog informa el estado diferenciado sin reactivar ajustes ni declarar `RUNNING`, y que al re-emparejar por el flujo oficial vuelve a recuperar.
4. **Parada manual durante un intento**: iniciar una detención manual desde Nightzuku mientras NightDog está descubriendo/conectando; comprobar cancelación inmediata, cierre de conexión y que `desiredRunning` permanece deshabilitado aunque llegue un Binder tardío.
