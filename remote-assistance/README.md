# JolufaSoft Remote — prototipo de transporte

Estado: **código inicial, sin compilación APK ni pruebas de extremo a extremo**.

Este directorio agrega un agente Termux para conectar un Android previamente autorizado a un servidor SSH privado. No modifica Nightzuku, no concede permisos ni desactiva indicadores de sesión. El cliente de pantalla/control queda pendiente; este agente **no es por sí mismo una aplicación de control remoto**.

## Requisitos

Termux con `pkg install openssh android-tools`; Nightzuku/ADB local ya autorizado y operativo; servidor SSH bajo control propio, con cuenta dedicada, clave pública registrada y `AllowTcpForwarding remote`. Restringir `PermitListen 127.0.0.1:15555` para esa cuenta, y no abrir ese puerto en el firewall. Para varios teléfonos asignar un puerto distinto por equipo. No usar cuentas SSH administrativas.

## Configuración del agente

Crear una clave SSH exclusiva para cada teléfono y registrar su pública en el servidor. Verificar la huella del servidor por un canal independiente y colocarla en `~/.ssh/known_hosts` (no usar `StrictHostKeyChecking=no`).

Ejemplo de inicio con variables de entorno:

```bash
export REMOTE_SSH_HOST=hop.example.org
export REMOTE_SSH_USER=remote-device-01
export REMOTE_SSH_KEY="$HOME/.ssh/jolufasoft_remote_ed25519"
export REMOTE_BIND_PORT=15555
export LOCAL_ADB_PORT=5555
bash remote-assistance/agent.sh
```

El agente reintenta conexiones y crea un reenvío SSH inverso en **127.0.0.1:15555 del servidor**, hacia el ADB local del Android. La conexión ADB necesita conservar su propia autorización. Si Nightzuku usa otro puerto, configurar `LOCAL_ADB_PORT` y `ADB_SERIAL`.

## Conexión de un cliente autorizado

El administrador puede abrir desde su equipo un túnel SSH al mismo servidor:

```bash
ssh -NT -L 127.0.0.1:15556:127.0.0.1:15555 admin@hop.example.org
```

Entonces un cliente scrcpy/ADB compatible puede conectar a `127.0.0.1:15556`. **Advertencia:** no compartir claves SSH, no abrir puertos ADB en interfaces públicas y no permitir acceso a usuarios no autorizados.

## Límites y trabajo pendiente

- No se ha verificado que la instancia Android remota acepte ADB a través de este túnel con el cliente seleccionado.
- No se incluye todavía interfaz Android, gestión de consentimientos, revocación ni APK.
- El agente debe arrancarse mediante un mecanismo explícito elegido por el propietario; no evade restricciones de reinicio o depuración de Android.
- Para uso real hacen falta endurecimiento SSH, autenticación por dispositivo, indicadores de sesión, supervisión y pruebas Android 15/16.
