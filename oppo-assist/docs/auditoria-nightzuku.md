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

## Persistencia

El proceso `shizuku_server` vive mientras el proceso vive. No hay en este código un servicio que vuelva a levantar adbd después de un reinicio.

`AdbTcpController` no crea un adbd permanente. Guarda host y puerto después de un probe. El puerto de la depuración inalámbrica de Android 11+ es dinámico: por eso este fork lo descubre por mDNS en vez de asumir el 5555.

## Papel en OPPO ASSIST

En el HONOR, Nightzuku puede darles a las apps de ese teléfono una shell local. El cliente `jolufa` no lo necesita: usa el `adb` de Termux (`android-tools`) como cliente, contra el adbd del OPPO, y solo por una IP de Tailscale.

El agente del OPPO no se engancha a la API de Shizuku. Sigue siendo una app normal, con una notificación fija.

## Qué quedó como hipótesis

No se comprobó en el HONOR 200 ni en el OPPO:

- si este binario de arranque sobrevive al cierre en segundo plano de ColorOS o de MagicOS;
- denegaciones SELinux concretas;
- si el UID 2000 de ese ColorOS puede cambiar la hora o apagar la depuración inalámbrica.

Eso está separado en `limitaciones.md`.
