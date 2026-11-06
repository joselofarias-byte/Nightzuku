# Auditoría de Nightzuku

Lectura del código de este repositorio. No se ejecutó Nightzuku en un teléfono.

## Qué es

Nightzuku es un fork personal de Shizuku. La app de gestión se identifica como `com.joselofarias.nightzuku` (`manager/build.gradle`) y el servidor busca esa misma identidad en `server/src/main/java/rikka/shizuku/server/ServerConstants.java`.

El proceso privilegiado entra por `manager/src/main/jni/starter.cpp`. Ese programa:

- rechaza cualquier UID que no sea 0 (root) o 2000 (shell de ADB);
- hace `fork` y ejecuta `/system/bin/app_process`;
- la clase Java es `rikka.shizuku.server.ShizukuService`.

`ShizukuService.main` prepara un `Looper` y publica un binder. No abre un puerto TCP. El binder vuelve a la app por un `ContentProvider` local (`sendBinder` en `ShizukuService.java`). El arranque que muestra la app es la ruta de `libshizuku.so` (`manager/src/main/java/moe/shizuku/manager/starter/Starter.kt`): `adb shell` de esa biblioteca, en el mismo aparato.

## Qué no es

No es un servidor ADB remoto. No acepta `adb connect`. No reenvía la pantalla de otro teléfono. No sustituye a Tailscale ni a scrcpy.

`AdbClient` (`manager/src/main/java/moe/shizuku/manager/adb/AdbClient.kt`) es un cliente del protocolo ADB: abre un `Socket` hacia un host y un puerto que le pasan. `AdbTcpController` lo usa para mandar `tcpip:` o `usb:` al adbd local y, solo si el puerto responde, guarda la preferencia. `AdbMdns` busca el servicio `_adb-tls-connect` en la red del propio teléfono. El único `ServerSocket` de ese archivo está en `isPortAvailable`: intenta ocupar el puerto descubierto para saber si está libre. Si el bind funciona, ese puerto no es el de adbd.

`rish` / `ShizukuShellLoader` pide el binder con un broadcast local `rikka.shizuku.intent.action.REQUEST_BINDER`. Es una shell en el mismo dispositivo.

Los módulos ADB (`docs/adb-modules-guide.md`) son ZIP que el servidor local ejecuta con los privilegios con los que fue arrancado (shell o root). No son un plano de control para otro teléfono.

El conector `content://kerneldroid.nightzuku.connector` (`docs/nightzuku-connectors.md`) devuelve el comando local de arranque. Viene apagado. No es una API de red.

## Depuración inalámbrica y arranque

Está implementado, con condiciones. No se ejecutó en un teléfono.

`BootCompleteReceiver` (`manager/src/main/java/moe/shizuku/manager/receiver/BootCompleteReceiver.kt`, líneas 31-104) escucha `LOCKED_BOOT_COMPLETED` y `BOOT_COMPLETED` (manifiesto, líneas 165-176). Si el equipo está en modo seguro, sale. Si el usuario no es el 0 o el binder de Shizuku ya responde, sale. Si el último arranque guardado es root, intenta `su`. El camino ADB entra solo si se cumplen las tres cosas de las líneas 49-51:

- API 33 o más (`TIRAMISU`);
- la app ya tiene `WRITE_SECURE_SETTINGS`;
- `ShizukuSettings.getLastLaunchMode()` es ADB.

Ese modo se graba cuando la pantalla de inicio ve el servidor corriendo y el UID no es 0 (`HomeActivity.kt`, líneas 146-151). El permiso lo otorga el servidor a sí mismo al enganchar el manager (`ShizukuService.java`, líneas 321-326), o sea después de un arranque exitoso por shell o root. No es un permiso que la app pida en el diálogo normal.

Si entra, `adbStart` (líneas 68-103) escribe:

- `adb_wifi_enabled = 1`
- `ADB_ENABLED = 1`
- `adb_allowed_connection_time = 0`

y durante 3 segundos busca `_adb-tls-connect._tcp` para correr el starter con la clave ADB ya guardada. El mismo interruptor, si el permiso está, también lo escribe el diálogo de descubrimiento (`HomeDialogs.kt`, líneas 323-327).

Eso no fija el puerto. El puerto de la depuración inalámbrica sigue saliendo de mDNS (`AdbMdns.kt`, línea 178) o, para el TCP clásico, de `service.adb.tcp.port` / `persist.adb.tcp.port` (`EnvironmentUtils.kt`, líneas 27-30, y `AdbTransportResolver.kt`, líneas 49-77). `AdbTcpController` solo guarda un host y un puerto después de un probe; no es el mecanismo de arranque.

`NightDogRecovery` (`NightDogRecovery.kt`, líneas 28-158 y 231-246) es otro camino: lo arranca `ShizukuApplication.onCreate` (líneas 39-43). Cada 4 segundos, si el binder murió y el usuario no lo detuvo a mano, busca el endpoint y abre `StarterActivity`. No escribe `adb_wifi_enabled`. Solo vive mientras el proceso de Nightzuku vive. Después de un reinicio, el que pide el interruptor es el receptor, no este watchdog.

Lo que el código no hace: no empareja solo (no hay código de 6 dígitos en el receptor), no ata adbd a Tailscale, y no promete que el sistema acepte esos `settings`. Si mDNS tarda más de 3 segundos, el starter de ese intento no corre aunque el interruptor se haya escrito.

## Persistencia del servidor

El proceso `shizuku_server` vive mientras el proceso vive. El receptor de arranque intenta levantarlo de nuevo solo en el camino de arriba.

## Papel en OPPO ASSIST

En el HONOR, Nightzuku puede darles a las apps de ese teléfono una shell local. El cliente `jolufa` no lo necesita: usa el `adb` de Termux (`android-tools`) como cliente, contra el adbd del OPPO, y solo por una IP de Tailscale.

El agente del OPPO no se engancha a la API de Shizuku. Sigue siendo una app normal, con una notificación fija.

## Qué quedó como hipótesis

No se comprobó en el HONOR 200 ni en el OPPO:

- si este binario de arranque sobrevive al cierre en segundo plano de ColorOS o de MagicOS;
- denegaciones SELinux concretas;
- si el UID 2000 de ese ColorOS puede cambiar la hora o apagar la depuración inalámbrica.

Eso está separado en `limitaciones.md`.
