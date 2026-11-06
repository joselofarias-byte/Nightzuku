# Arquitectura

Dos teléfonos y un túnel. El servidor Oracle no entra en el camino de los datos.

```text
OPPO (persona)                         HONOR 200 (cuidador)
+-----------------------------+        +-------------------------------+
| Agente Kotlin               |        | Termux                        |
| notificación fija           |        | jolufa                        |
| socket solo en IP Tailscale |<------>| adb + scrcpy (si hay pantalla)|
| SMS, contactos, diagnóstico |  WG    | comandos shell sobre adb     |
|                             |        |                               |
| adbd del sistema            |<------>| cliente adb                   |
| (puerto dinámico,           |        | solo si el host es Tailscale |
|  escucha del sistema)       |        |                               |
+-----------------------------+        +-------------------------------+
        |                                         |
        +---- Tailscale (WireGuard), ACL ---------+
```

Nightzuku, si está en el HONOR, se queda en el HONOR. No se usa como transporte.

## Agente (`com.joselofarias.oppoassist`)

App normal, `minSdk` 29. No es device owner ni perfil de trabajo: eso exigiría sacar las cuentas del teléfono, y no se pide. No es servicio de accesibilidad y no pide borrar la pantalla de bloqueo.

- Servicio en primer plano de tipo `specialUse`, con notificación que no se puede deslizar. Se eligió `specialUse` y no `dataSync` porque en Android 15 `dataSync` tiene un cupo de horas. Eso está en el manifiesto; no se midió en ColorOS.
- `HttpAgentServer` hace bind a una dirección que `TailscaleBind` eligió. La política de producción solo acepta `100.64.0.0/10` o `fd7a:115c:a1e0::/48`. Si la dirección es `0.0.0.0` o no es Tailscale, no arranca. No hay un segundo intento hacia todas las interfaces.
- Cada pedido es `POST /v1/rpc` con `Authorization: Bearer`. El token sale de 32 bytes aleatorios y se muestra en el teléfono. No viaja en la URL.
- El servidor envuelve el manejador con `GuardedHandler`. Por red están negadas `sms.approve`, `sms.deny`, `reboot`, `lockscreen.dismiss`, `settings.time.set`, `adb.tcpip` y `adb.root`. No hay pantalla de permiso por código: todo SMS tradicional entra a la cola, y si parece de un solo uso lleva `otp=true`.
- Los SMS entran por `SMS_RECEIVED` y, si ColorOS se lo saltea, por una lectura de `Telephony.Sms.Inbox`. Los dos caminos marcan el mensaje como SMS. No hay lector de RCS: la política tira un mensaje si alguien lo marca como RCS, y el diagnóstico dice `sms.rcsObservable=false`.
- Un SMS tradicional se guarda en la cola con el cuerpo completo. Si parece un código de un solo uso, el mismo objeto lleva `otp=true`. No hay cola de pendientes ni vencimiento. Un RCS no se guarda.
- Contactos: leer, crear, editar y borrar con `ContactsContract`, si el permiso está dado.
- Apps: inventario, línea base, lista de desalentadas (no desinstala), abrir, ficha de ajustes y un `ACTION_DELETE` que el sistema confirma en pantalla. Cerrar una app desde el agente responde `usar_adb_shell`.
- Reinicio y hora no se hacen desde el agente. La matriz de capacidades lo dice y marca `demonstratedOnTargetDevice=false` en todas las filas.

Al arrancar, `BOOT_COMPLETED` y `MY_PACKAGE_REPLACED` piden de nuevo el servicio. Cada 30 segundos se reintenta el socket si volvió Tailscale. El agente no escribe `adb_wifi_enabled`. Quien puede hacerlo es Nightzuku, instalado en el mismo OPPO, por el receptor descrito en la auditoría. El agente solo observa `_adb-tls-connect._tcp` y `adb_wifi_enabled`, y publica `adb.status` por el socket de Tailscale. Si mDNS anuncia una IP que no es Tailscale, esa IP no sale en la respuesta: sale el puerto.

## Cliente Termux

`termux/bin/jolufa` arma los argv en Python (`jolufa_client.py`) y no usa `eval`.

- El host tiene que ser una IP de Tailscale, o un nombre cuyas direcciones resueltas sean todas de Tailscale. Si una es pública, se niega.
- `pantalla`, `tocar`, `deslizar` y `texto` solo siguen si `dumpsys window` dice con claridad que el bloqueo no está. Si el volcado no trae las marcas conocidas, se niega.
- `reiniciar` y `desinstalar` piden `--confirmo`. La lista `shared/protected-packages.list` y el prefijo `com.android.` no se desinstalan.
- `recuperar` reintenta `adb connect`. Si no hay sesión, avisa con `ALERTA` y, si el agente responde, adopta el puerto nuevo sin cambiar el host Tailscale.
- `cerrar-sesion` solo escribe `adb_wifi_enabled=0` y vuelve a leer el valor. No hay un comando que lo ponga en 1. Nightzuku puede volver a pedirlo en el próximo arranque.

scrcpy se invoca con `--serial` de esa sesión, sin audio. Si no hay `DISPLAY` ni `WAYLAND_DISPLAY`, `jolufa pantalla` no pretende que el video esté andando y deja `jolufa captura` más `tocar`.

## Oracle A1

No hay proxy, no hay `sshd` y no hay un adb escuchando ahí. Ver `oracle.md`.
