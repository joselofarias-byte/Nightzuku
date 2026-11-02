# Matriz de limitaciones

Leyenda: **código** = está en esta rama y tiene prueba automática. **plataforma** = comportamiento documentado de Android, no reproducido en el OPPO. **hipótesis** = no se midió en el HONOR 200 ni en el OPPO.

| Función | Desde dónde | Después de reiniciar | Estado |
| --- | --- | --- | --- |
| Ver y tocar la pantalla con scrcpy | `adb` + scrcpy, con la sesión ya autorizada y el bloqueo abierto | No. La sesión muere. | código del cliente; hipótesis en el aparato. scrcpy no usa el diálogo de captura de MediaProjection (así está hecho upstream). No se comparó con AnyDesk en este ColorOS. |
| Captura suelta y toques | `adb exec-out screencap` y `input tap` | No | código. La captura puede salir negra con el bloqueo seguro. |
| Eludir el PIN, el patrón o la contraseña | Nadie | No | No está implementado. El cliente no llama a `locksettings` ni escribe un PIN. |
| Abrir, cerrar, desinstalar, ficha de la app | Cerrar y desinstalar: shell ADB. Abrir y ficha: shell, y el agente puede intentar un intent que Android 10+ suele bloquear en segundo plano. | No | código. Desinstalar una app de usuario con `pm uninstall` no muestra el diálogo de Play; por eso pide `--confirmo` y rechaza paquetes protegidos. El agente, si llega a abrir `ACTION_DELETE`, deja la confirmación en la pantalla del OPPO. |
| Detectar instalaciones | Línea base + `PACKAGE_ADDED` + instalador vacío | El inventario vuelve si el agente vive | código. No borra sola. Un instalador vacío es una señal, no una prueba de sideload (hipótesis en ColorOS). |
| Impedir una instalación | No se puede enganchar el instalador del sistema sin ser device owner | No | Se avisa y se puede marcar como desalentada. |
| Reiniciar | `adb reboot` | El control no queda | código del comando. **No está demostrado** que después se pueda volver a entrar. La plataforma no deja la depuración inalámbrica prendida al reiniciar. |
| Fecha y hora | `settings put global auto_time` y `toybox date`, y solo si la relectura coincide | No | código. `SET_TIME` no lo tiene una app común. La shell (UID 2000) en muchos equipos no puede fijar el reloj. Si falla, el cliente lo dice. Hipótesis en este ColorOS. |
| Contactos | Agente, con los dos permisos | Si el proceso sigue | código de validación. El proveedor de contactos no se probó en un aparato. |
| Diagnóstico | Agente: batería, red, almacenamiento, permisos, cantidad de apps | Si el proceso sigue | código de la forma del resultado. Los números salen del teléfono real recién cuando esté instalado. |
| SMS tradicional | `SMS_RECEIVED` y la bandeja `Telephony.Sms` | Si el proceso y el permiso siguen | código. No usa saldo: sale por Tailscale. |
| RCS | No se lee | No aplica | código: un mensaje marcado RCS se descarta. En el teléfono el agente nunca marca RCS porque no mira esa base. Distinguir “no llegó como SMS” de “era RCS” no es posible desde esta app. Hipótesis: ColorOS puede no entregar `SMS_RECEIVED` a una app que no es la de mensajes. |
| Código 2FA | Se muestra en el OPPO y espera un toque | El pendiente vence a los 10 minutos | código. La red no puede aprobarlo. Puede haber falsos positivos (un mensaje con la palabra “código” y dígitos). |
| Socket del agente | Solo IP Tailscale, puerto 8765, token | Solicitado con `START_STICKY` y `BOOT_COMPLETED`. No demostrado. | código del bind. El cifrado del tramo es el de Tailscale (WireGuard), no un TLS propio. |
| adbd | Puerto que muestra Opciones de desarrollador. El sistema lo abre; esta app no elige la interfaz. | No demostrado y, por la plataforma, no persiste. El puerto cambia. | El cliente no hace `adb tcpip`. `jolufa` no conecta a una IP que no sea Tailscale. Eso no cierra el puerto en la Wi-Fi local: Android suele escuchar en todas las interfaces. Hipótesis en este OPPO. Mitigación: datos móviles + Tailscale, ACL, y `jolufa cerrar-sesion` (que solo vale si `settings` realmente queda en 0). |
| scrcpy dentro de Termux | Hace falta Termux:X11 y, en la práctica, scrcpy de Debian por `proot-distro` | No | Hipótesis. No se corrió en el HONOR 200. Sin `DISPLAY`, el cliente no finge que hay video. |
| ColorOS en segundo plano | Inicio automático y batería sin restricciones, a mano | Puede no entregar el arranque o matar el servicio igual | Hipótesis. El modelo exacto del OPPO no está en el encargo. |
| Nightzuku como puente | No | No | Ver la auditoría. |
| Oracle A1 | No se usa | No aplica | No hay proxy de ADB. |

Nada de esta tabla fue ejecutado contra el OPPO ni contra el HONOR. Las filas marcadas como código se probaron con JVM, Python y bash en la máquina de desarrollo, con teléfonos simulados.
