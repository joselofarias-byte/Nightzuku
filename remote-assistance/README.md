# JolufaSoft Remote 1.2

Instala el APK en ambos teléfonos y únelos a la misma red de Tailscale que el Oracle. Cada teléfono asistido se registra una vez con usuario, contraseña y un nombre. En el operador, inicia sesión una vez y elige el teléfono de la lista. La aplicación registra automáticamente sus claves SSH y obtiene la huella del servidor mediante HTTPS. Las contraseñas se usan al iniciar sesión. El operador conserva un token y el teléfono asistido conserva su identidad SSH en el almacenamiento privado de la app.

## Oracle

Desde una copia de este directorio, ejecuta:

```bash
sudo bash setup-tailnet-login.sh
```

El Oracle necesita OpenSSH, Python 3 y Tailscale con HTTPS habilitado. Limita TCP 8443 y 2223 en la política del tailnet a los teléfonos que usan la app. El instalador muestra las dos contraseñas generadas y el nombre del servidor. El servicio HTTPS de Tailscale Serve queda en 8443 y el SSH dedicado, en 2223. La API asigna un puerto de reenvío a cada teléfono y lo mantiene en bucle local. El instalador deja el SSH administrativo del Oracle y el servicio de la versión 1.1 en sus puertos anteriores.

## Teléfonos

- **Recibir asistencia** usa `remote-device-01` y su contraseña. Al iniciar sesión, ponle un nombre al teléfono y habilita la disponibilidad. Se mantiene mientras Tailscale y el ADB local autorizado de Nightzuku estén disponibles. Puedes desactivarla desde la notificación.
- **Dar asistencia** usa `remote-operator-01` y su contraseña. Pulsa **Teléfonos** y elige el equipo. Acepta una vez en el equipo asistido la autorización ADB del cliente si Android la pide. Los teléfonos registrados aparecen en la lista aunque estén apagados o la disponibilidad esté desactivada.
- En ambos modos se ingresa el mismo nombre `.ts.net` que imprime el instalador.

## Compilación

`bash build-android.sh` aplica el parche a la revisión fijada, ejecuta las pruebas y verifica el APK. Usa JDK 17, Android SDK 36 y Build Tools 36.0.0. La variante distribuida por CI usa firma de prueba.
