# Auditoría de Shizuku y forks para Nightzuku

Fecha: 2026-08-01

## Objetivo

Identificar aportes concretos y transferibles de Shizuku oficial y forks activos, con prioridad en persistencia, TCP/IP, recuperación automática, compatibilidad y UX.

## Fuentes principales

- RikkaApps/Shizuku
- thedjchi/Shizuku
- yangFenTuoZi/Shizuku
- thejaustin/ShizukuPlus

## Prioridad 1: TCP/IP persistente

Fuente principal: `thedjchi/Shizuku`.

Aportes detectados:

- Cambio de `adbd` a modo TCP mediante `adb tcpip` después de un arranque inalámbrico válido.
- Reinicio posterior del servicio sin depender de Wi-Fi cuando TCP mode quedó activo.
- Puerto TCP configurable.
- Opción para desactivar TCP mode.
- Correcciones en la transición ADB TLS → TCP.
- Starter inalámbrico centralizado.
- Cancelación de intentos de arranque en segundo plano cuando el usuario inicia manualmente.
- Errores accionables en lugar de quedar bloqueado en “Connecting to port…”.
- Reinicio solo cuando una configuración pendiente realmente lo requiere.

### Diseño propuesto para Nightzuku

Crear un `AdbTransportManager` único con estados explícitos:

```text
Disconnected
DiscoveringTls
ConnectingTls
SwitchingToTcp
ConnectingTcp
StartingService
WaitingForBinder
Running
Failed
```

Responsabilidades:

- seleccionar TLS o TCP;
- conservar host/puerto válido;
- ejecutar `adb tcpip <port>` solo cuando corresponda;
- verificar el puerto antes de persistirlo;
- impedir dos starters simultáneos;
- cancelar recuperación automática ante arranque manual;
- entregar errores estructurados a UI, notificación y NightDog.

## Prioridad 2: NightDog y arranque persistente

Aportes detectados:

- Watchdog independiente de la actividad principal.
- Arranque al boot esperando conectividad Wi-Fi.
- Manejo explícito de errores al cambiar de TLS a TCP.
- Notificación del watchdog con opción de detenerlo.
- Reintentos limitados y recuperación de fallos.
- Advertencias específicas para limitaciones de Android 11–12.
- Soporte garantizado por el fork para Android 13+.

Shizuku oficial 13.6.0 agregó auto-start sin root en Android 13+ cuando el dispositivo está conectado a una WLAN confiable. Debe compararse con la implementación del fork antes de duplicar lógica.

## Prioridad 3: automatización

Aportes detectados:

- Intents explícitos para iniciar y detener el servicio desde Tasker, MacroDroid o Automate.
- Estados y errores accesibles desde notificaciones.

Requisitos para Nightzuku:

- componentes no exportados por defecto;
- permiso `signature` para acciones sensibles;
- acciones documentadas y versionadas;
- no aceptar host, puerto o comandos arbitrarios desde apps externas sin validación.

## Prioridad 4: compatibilidad y UX

Aportes detectados en forks:

- Android/Google TV navegable con D-pad.
- Pairing para TV con Android 14+.
- Correcciones de User Service en dispositivos MediaTek.
- Soporte para páginas de memoria de 16 KB.
- Legacy pairing para VR y dispositivos sin el flujo normal de notificación.
- Selección masiva de permisos Shizuku.

## Prioridad 5: evaluar por separado

`ShizukuPlus` añade un proveedor universal Root/ADB Shell/Dhizuku y APIs adicionales. No debe mezclarse con el trabajo TCP/NightDog sin una auditoría separada de arquitectura, licencia, superficie de permisos y compatibilidad.

Stealth mode tampoco debe incorporarse como parte del trabajo general sin evaluación específica de seguridad, mantenimiento y propósito.

## Orden de implementación

1. Auditar los commits TCP de `thedjchi/Shizuku`.
2. Comparar esos cambios con `StarterActivity`, `AdbClient`, `AdbKey` y NightDog de Nightzuku.
3. Diseñar e implementar `AdbTransportManager` en una rama independiente.
4. Agregar TCP mode con puerto configurable y rollback.
5. Integrar NightDog con el manager centralizado.
6. Agregar intents de automatización protegidos.
7. Importar correcciones puntuales de TV, MediaTek y 16 KB por ramas separadas.

## Matriz de aceptación

Cada aporte debe cumplir:

- licencia compatible y atribución preservada;
- fuente y commit upstream identificados;
- cambio mínimo y desacoplado;
- compilación validada;
- prueba real en el tipo de dispositivo afectado;
- rollback claro;
- sin secretos ni permisos peligrosos agregados silenciosamente;
- sin mezclar múltiples funciones grandes en un mismo PR.
