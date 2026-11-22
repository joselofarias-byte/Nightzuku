# Recuperación automática ADB, persistencia inalámbrica y `rish` en HONOR 200

Equipo objetivo: HONOR 200 ELI-NX9, Android 16 / MagicOS 10, sin root, paquete `com.joselofarias.nightzuku` y Termux (`com.termux` o forks compatibles).
Este documento describe la arquitectura completa de recuperación y persistencia implementada, las limitaciones reales de Android 16 / MagicOS 10 y la matriz de validación física en el dispositivo.

## Comportamiento implementado

1. **Redescubrimiento por intento (`AdbMdns`, `AdbTransportResolver`)**:
   - Cada intento crea una sesión NSD fresca (`_adb-tls-connect._tcp`, timeout 8 s) con `WifiManager.MulticastLock` acotado a la ventana de descubrimiento (liberado siempre en `finally`).
   - Serialización de `NsdManager.resolveService` para evitar `FAILURE_ALREADY_ACTIVE` cuando hay múltiples dispositivos o anuncios mDNS en la misma red Wi-Fi.
   - En Android 14–16, evaluación de todas las direcciones devueltas (`hostAddresses`) contra las interfaces locales activas (`AdbEndpointPolicy.selectLocalHost`), priorizando IPv4 local válida.
   - Sin caché estática de endpoints mDNS ni fallback a `getprop service.adb.tcp.port`.
   - Descarte estricto de callbacks tardíos por generación de sesión.

2. **Persistencia de depuración inalámbrica (`AdbTransportResolver`, `NightDogRecovery`, `NightDogPersistence`)**:
   - Detecta y diferencia: Wi-Fi desconectado (`WIFI_DISCONNECTED`), depuración inalámbrica desactivada (`WIRELESS_DEBUGGING_DISABLED`), endpoint ausente (`ENDPOINT_ABSENT`), transporte `offline` (`OFFLINE`), autorización revocada (`AUTH_FAILED`), fallo de shell (`SHELL_UNAVAILABLE`) y Binder no confirmado (`BINDER_NOT_CONFIRMED`).
   - Conserva `desiredRunning` frente a cualquier interrupción no manual (caída de Wi-Fi, cambio de AP/IP, reinicio, cierre de proceso, desactivación del ajuste por MagicOS).
   - `ContentObserver` sobre `Settings.Global.adb_wifi_enabled` y `JobScheduler` (`TriggerContentUri`) para redescubrir inmediatamente al recuperar disponibilidad.
   - **Uso legal y condicionado de `WRITE_SECURE_SETTINGS`**: una aplicación normal **no** puede modificar `Settings.Global.adb_wifi_enabled` sin permiso del sistema. Únicamente si el usuario ya concedió `WRITE_SECURE_SETTINGS` por ADB/Shizuku (`pm grant com.joselofarias.nightzuku android.permission.WRITE_SECURE_SETTINGS`), la opción está habilitada en Lab Features, `desiredRunning == true`, hay Wi-Fi y Opciones de desarrollador activas, y el usuario **no** desactivó manualmente el interruptor con Wi-Fi estable, NightDog intenta una única restauración acotada de `adb_wifi_enabled` (nunca toca `adb_enabled` ni reinicia el demonio ADB global). Si Android/MagicOS rechaza la escritura (`SecurityException` o reversión), detiene los intentos en ese ciclo e informa el motivo exacto.
   - Notificación silenciosa deduplicada (`NightDogNotificationHelper`) y accesos directos en la tarjeta de estado de `HomeActivity` hacia Opciones de desarrollador (`toggle_adb_wireless`), emparejamiento (`AdbPairingTutorialActivity`) o Wi-Fi cuando se requiere intervención manual.

3. **Persistencia tras reinicio, cambios de red y segundo plano (`BootCompleteReceiver`, `NightDogRecoveryJobService`, `ShizukuReceiver`)**:
   - `BootCompleteReceiver` atiende `LOCKED_BOOT_COMPLETED`, `USER_UNLOCKED`, `BOOT_COMPLETED` y `MY_PACKAGE_REPLACED` respetando Direct Boot (`isUserUnlocked`), Modo Seguro y `desiredRunning`.
   - `ConnectivityManager.NetworkCallback` detecta reconexiones Wi-Fi, cambios de AP y cambios de IP, restableciendo backoff y redescubriendo el nuevo puerto dinámico sin perturbar un Binder ya sano.
   - Receptor dinámico de `ACTION_USER_PRESENT` y salida de Doze (`ACTION_DEVICE_IDLE_MODE_CHANGED`), con debounce y sin wakelocks indefinidos.
   - `NightDogRecoveryJobService` (`JobScheduler`) mantiene trabajos compatibles con Android 16: recuperación condicionada a red no medida (`setPersisted(true)`), trigger por cambio de `adb_wifi_enabled` y vigilancia periódica ligera (15 min), todos cancelados de inmediato al detener manualmente.
   - `ShizukuReceiver` reactiva NightDog si llega un `REQUEST_BINDER` de `rish` mientras Binder está caído y `desiredRunning == true`.

4. **Confirmación de Binder, concurrencia, parada manual y `rish`**:
   - Solo declara `Stage.RUNNING` tras confirmar `Shizuku.pingBinder()`.
   - Un `onBinderReceived` tardío o con `pingBinder() == false` no cancela una recuperación en curso ni produce falsos `RUNNING`.
   - La parada manual (`prepareForManualStop`) cierra sockets activos, cancela jobs y notificaciones, borra `desiredRunning` y prevalece sobre boot, red, wakeups y Binder tardíos.
   - `ShizukuShellLoader` cancela el timeout de 5 s al recibir Binder (`BinderAcquisitionGuard`), permitiendo sesiones `rish` prolongadas.

## Limitaciones reales de Android 16 / MagicOS 10 en HONOR 200 (sin root)

1. **Restablecimiento de `adb_wifi_enabled` tras reinicio o cambio de red**: Android 16/MagicOS 10 desactiva `adb_wifi_enabled` al reiniciar o desconectar Wi-Fi. Sin `WRITE_SECURE_SETTINGS` (o si MagicOS bloquea la escritura del ajuste), Android exige activación manual desde Opciones de desarrollador o el mosaico rápido.
2. **Gestión agresiva de batería e inicio de apps (MagicOS App Launch / PowerGenie)**: Si Nightzuku queda en "Gestionar automáticamente", MagicOS puede detener el proceso y diferir broadcasts o `JobScheduler`. Solución menos intrusiva recomendada: *Ajustes → Batería → Inicio de aplicaciones → Nightzuku → Gestionar manualmente* (habilitar *Inicio automático*, *Inicio secundario* y *Ejecutar en segundo plano*) + desactivar optimización de batería desde *Lab Features → Batería y segundo plano*.
3. **Direct Boot (antes del primer desbloqueo)**: Por seguridad de Android, las claves ADB emparejadas y el almacenamiento cifrado de credenciales no se usan hasta que el usuario desbloquea el dispositivo (`ACTION_USER_UNLOCKED`).
4. **Revocación de emparejamiento / clave ADB**: Si se revocan las autorizaciones de depuración inalámbrica, ninguna app sin root puede reautorizarse sola; debe repetirse el emparejamiento oficial con código de 6 dígitos.

## Verificación automatizada

```sh
bash ./gradlew --no-daemon :manager:testDebugUnitTest :shell:testDebugUnitTest
```

## Validación física en HONOR 200 (comandos Termux)

1. **Confirmar instalación actualizable, firma y `versionCode`**:
   ```sh
   dumpsys package com.joselofarias.nightzuku | grep -E "versionCode|versionName|signatures"
   ```
2. **Conceder (opcional) `WRITE_SECURE_SETTINGS` vía `rish` o ADB para habilitar restauración de `adb_wifi_enabled`**:
   ```sh
   rish -c "pm grant com.joselofarias.nightzuku android.permission.WRITE_SECURE_SETTINGS"
   ```
3. **Verificar `rish` > 5 segundos tras recuperación**:
   ```sh
   rish -c "sleep 6 && id && pidof shizuku_server"
   ```
4. **Simular caída de `shizuku_server` y medir recuperación automática**:
   ```sh
   rish -c "kill -9 \$(pidof shizuku_server)" && sleep 12 && rish -c "id && pidof shizuku_server"
   ```
5. **Probar cambio de Wi-Fi / puerto dinámico / reinicio / parada manual** siguiendo la matriz de pruebas físicas en el reporte final.
