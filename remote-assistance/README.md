# JolufaSoft Remote 1.6.2

Instala el APK en ambos teléfonos y selecciona el mismo servidor en los dos. Cada teléfono asistido se registra una vez con usuario, contraseña y un nombre. En el operador, inicia sesión una vez y elige el teléfono de la lista. La aplicación registra automáticamente sus claves SSH y obtiene la huella del servidor mediante HTTPS. Las contraseñas se usan al iniciar sesión. El operador conserva un token y el teléfono asistido conserva su identidad SSH en el almacenamiento privado de la app.

## Acceso HTTPS con Caddy

En una instalación existente, crea el registro DNS del dominio público y descarga juntos
`setup-https-relay.sh`, `relay-login.py` y `relay-wss.py`. Ejecuta
`sudo bash setup-https-relay.sh dominio IP-local /etc/caddy/Caddyfile` en el Oracle.
El instalador valida Caddy y conserva los otros sitios. La API y el túnel WebSocket comparten
HTTPS en el 443. El SSH del relay también escucha en bucle local y sus cuentas se autentican
con la llave privada de cada instalación. Los puertos internos de ADB y del relay no se publican.

El selector muestra `remoto.jolufasoft.com` como opción principal. La etiqueta guardada `remoto` se resuelve al dominio completo. En **Menú ⋮ → Configuración de servidores** puedes añadir, editar o quitar dominios personalizados. El selector permanece visible en el inicio antes y después de iniciar sesión. Los dominios se editan desde Configuración de servidores. Para cambiar de servidor con una sesión activa, la app pide confirmar el cierre y exige iniciar sesión en el nuevo destino.

El respaldo **Tailscale · Oracle A1 Hop** usa la ruta privada del mismo Oracle. Un VPS independiente se añade por dominio una vez que tiene su propio relay operativo y cuentas configuradas.

## Oracle

Desde una copia de este directorio, ejecuta:

```bash
sudo bash setup-tailnet-login.sh
```

El Oracle necesita OpenSSH, Python 3 y Tailscale con HTTPS habilitado. Limita TCP 8443 y 2223 en la política del tailnet a los teléfonos que usan la app. El instalador muestra las dos contraseñas generadas y el nombre del servidor. El servicio HTTPS de Tailscale Serve queda en 8443 y el SSH dedicado, en 2223. La API asigna un puerto de reenvío a cada teléfono y lo mantiene en bucle local. El instalador deja el SSH administrativo del Oracle y el servicio de la versión 1.1 en sus puertos anteriores.

## Cuentas y roles

La cuenta inicial `remote-operator-01` administra las cuentas desde **Dar asistencia → Menú ⋮ → Administrar cuentas**. El acceso **Menú ⋮ → Agregar usuario** abre directamente el alta de cuentas. Puede crear operadores y cuentas para equipos asistidos, cambiar o generar sus contraseñas y desactivar o reactivar cuentas. Los operadores creados pueden dar asistencia. Los equipos asistidos se registran con su cuenta y un nombre.

Al cambiar una contraseña o desactivar una cuenta se revocan sus credenciales para conexiones nuevas. Un túnel SSH ya establecido puede seguir abierto hasta que termine su conexión. Los teléfonos afectados deben volver a iniciar sesión para registrar su llave.

En una instalación existente, actualiza `/etc/jremote-login/relay-login.py` y reinicia solo `jremote-login.service`. La API migra las dos cuentas iniciales y los equipos registrados durante el arranque.

## Nombres visibles, avatares y bloqueo local

Los catálogos de cuentas y teléfonos usan un selector desplegable. El administrador puede editar el nombre visible de una cuenta o de un teléfono. Las cuentas mantienen su usuario de acceso y un nombre de teléfono elegido por el administrador se conserva al volver a registrar el mismo dispositivo con la misma cuenta.

Toca el avatar junto al menú para elegir gato, robot, zorro o planeta. El avatar se guarda localmente. En **Administrar cuentas → Opciones de la cuenta → Elegir avatar** puedes asignar una imagen a cada usuario de la lista. El encabezado muestra la versión instalada.

En cada teléfono, **Menú ⋮ → Bloqueo de la aplicación** permite configurar un PIN local, una contraseña local o el desbloqueo de Android (biometría fuerte o credencial del dispositivo, en Android 11 o posterior). Se solicita al volver con la aplicación en segundo plano y al volver a abrirla. **Bloquear ahora** permite hacerlo de inmediato. El bloqueo protege la interfaz del teléfono y la asistencia en segundo plano sigue disponible. Una vez desbloqueada la aplicación, puedes capturar el diagnóstico y los errores.

## Teléfonos

- **Recibir asistencia** usa `remote-device-01` y su contraseña. Al iniciar sesión, ponle un nombre al teléfono y habilita la disponibilidad. Se mantiene mientras el servidor elegido y el ADB local autorizado estén disponibles. La puesta en marcha del ADB en el teléfono asistido sigue dependiendo de un componente externo como Nightzuku. La notificación indica **Disponible para José Luis** cuando el servicio está disponible. Para desactivar la asistencia, abre la aplicación y usa **Desactivar asistencia**.
- **Dar asistencia** usa `remote-operator-01` y su contraseña. Pulsa **Teléfonos** y elige el equipo. Acepta una vez en el equipo asistido la autorización ADB del cliente si Android la pide. Los teléfonos registrados aparecen en la lista aunque estén apagados o la disponibilidad esté desactivada.

## Compilación

`bash build-android.sh` compila la variante de publicación y exige las variables `SCRCPY_KEYSTORE`, `SCRCPY_KEYSTORE_PASSWORD`, `SCRCPY_KEY_ALIAS` y `SCRCPY_KEY_PASSWORD`. Usa JDK 17, Android SDK 36 y Build Tools 36.0.0. El APK de entrega debe superar `verify-release-signature.sh` con el certificado de la versión 1.5.0. El workflow publica el artefacto `jremote-release` solo con ese control.

`bash build-android.sh local` compila explícitamente para pruebas y deja el resultado en `dist/local`.

Para preconfigurar un dominio principal distinto antes de compilar, define `ORG_GRADLE_PROJECT_jremoteDefaultServer` con el nombre completo.
