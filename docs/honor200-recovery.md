# Recuperación ADB en HONOR 200

Objetivo: HONOR 200 ELI-NX9, Android 16 / MagicOS 10, sin root, con
Nightzuku `com.joselofarias.nightzuku` y Termux `com.termux`.
Esta guía describe el comportamiento implementado; no certifica pruebas en ese teléfono.

## Qué hace NightDog

- Comprueba Binder cada cuatro segundos mientras vive el proceso de la aplicación.
  Binder operativo es la evidencia de servicio activo: un anuncio mDNS, un socket
  abierto o terminar el comando de inicio no bastan.
- Sin Binder y con inicio deseado habilitado, espera 1,5 segundos y solicita a
  `AdbTransportResolver.discover(context)` un descubrimiento nuevo, limitado a
  ocho segundos. Siempre usa mDNS fresco, sin prioridad para puertos guardados,
  sin fallback a propiedades o endpoints cacheados y sin sesión mDNS permanente.
  La preferencia TCP persistente queda reservada a sus controles explícitos de
  habilitar/deshabilitar; no determina el endpoint de recuperación ni de PID.
- Cada intento descubre el puerto actual: no usa `~/.honor200-adb-serial`, un
  serial de Termux, un puerto guardado de una sesión anterior ni procesos shell
  auxiliares para descubrirlo.
- Autentica con la clave ADB existente antes de ejecutar el mismo
  `Starter.internalCommand` utilizado por el inicio headless existente. No abre
  una actividad desde segundo plano, no cambia permisos ni activa depuración
  inalámbrica por su cuenta. No solicita root ni modifica rish.
- Revisa cancelación, estado deseado y Binder después del descubrimiento y de la
  autenticación. La detención manual cancela los intentos y cierra sus clientes;
  un Binder tardío no vuelve a habilitar la recuperación. Una orden ya entregada
  a Android no puede deshacerse retroactivamente: comprueba que la detención haya
  terminado si coinciden ambas acciones.
- Los reintentos esperan al menos 8, 16, 32 y hasta 60 segundos, además del
  intervalo de comprobación y de la duración del intento. No hay bucle apretado.
  Los tiempos de conexión y lectura dependen de los límites de `AdbClient`;
  no equivalen a un plazo total de ejecución de un proceso remoto.
- La consulta opcional de PID también descubre por operación y autentica, pero
  se intenta una vez por recepción de Binder, no cada cuatro segundos. Su fallo
  no degrada un Binder sano ni dispara recuperación.

## Restricciones de Android y MagicOS

El watchdog es **del proceso**, no un servicio inmortal. No mantiene CPU ni Wi-Fi
con wakelocks, no elude SELinux, no evita el cierre forzado, no crea permisos ADB
ni promete conservar la depuración inalámbrica tras reinicios o cambios de red.
Android puede cambiar el puerto o desactivar la depuración; MagicOS puede cerrar
la aplicación o restringir su ejecución. Las restricciones concretas de MagicOS
10 deben medirse en el dispositivo, no inferirse como errores confirmados.

El watchdog espera al desbloqueo para leer el estado deseado protegido por
credenciales; no intenta recuperar durante Direct Boot. Tras un reinicio, desbloquea el teléfono, comprueba Wi-Fi y depuración inalámbrica
y abre Nightzuku si es necesario. Si Android retiró la autorización o el
emparejamiento, completa nuevamente el flujo oficial desde la interfaz. La
recuperación no puede reemplazar esa aprobación. El receptor de inicio existente
tiene sus propios requisitos de permisos y versión; esta modificación no los
amplía ni garantiza que Android lo ejecute.

Termux/rish conservan el paquete Nightzuku y sus autorizaciones existentes.
Tener rish funcional confirma el camino Shizuku cuando Binder está disponible;
no habilita por sí solo ADB ni recupera un servicio muerto. Un inicio por root
sigue dependiendo de una autorización root real; este cambio no altera ese flujo.

Referencias: [restricciones de actividades en segundo plano](https://developer.android.com/guide/components/activities/background-starts),
[depuración inalámbrica y emparejamiento](https://developer.android.com/tools/adb#wireless),
[límites de ejecución en segundo plano](https://developer.android.com/about/versions/oreo/background).

## Diagnóstico y validación pendiente en equipo físico

Consulta el estado NightDog y `adb logcat -s NightDogRecovery AdbClient` desde una
conexión ADB autorizada. Se distinguen búsqueda sin autenticar, ADB autenticado,
comando de inicio enviado y Binder verificado. Los fallos de recuperación
registran la clase de excepción, sin claves ni códigos de emparejamiento.
Evita publicar logs completos sin revisar sus datos privados.

Matriz pendiente en HONOR 200 (no ejecutada en este entorno):

1. Con Binder sano, comprobar que no existen sesiones mDNS continuas ni consultas
   de PID repetidas; confirmar que Termux/rish y clientes autorizados funcionan.
2. Desconectar/reconectar Wi-Fi, cambiar de red y desactivar/reactivar depuración;
   verificar puerto nuevo y autenticación antes de iniciar. Sin anuncio válido,
   comprobar espera/reintentos y mensaje de autorización, sin falsos «activo».
3. Detener manualmente durante descubrimiento, conexión e inicio; entregar un
   Binder tardío y confirmar que el inicio deseado sigue deshabilitado. Volver a
   iniciar explícitamente y verificar recuperación normal.
4. Revocar el emparejamiento, denegar autorización y simular servidor inaccesible;
   comprobar errores visibles, cierre de conexiones y reintentos espaciados.
5. Reiniciar, bloquear/desbloquear, cerrar el proceso y forzar detención; registrar
   qué requiere reapertura o aprobación del usuario. No esperar recuperación
   automática tras una detención forzada.
6. Comparar consumo en reposo, suspensión prolongada y modo ahorro de batería;
   registrar versión exacta de MagicOS y ajustes OEM, sin desactivar controles de
   seguridad como solución.
7. En equipos separados que dispongan de root autorizado, verificar el inicio
   root existente y los clientes/rish; no es validable con el HONOR sin root.

La compilación, las pruebas JVM y el examen de código no sustituyen esta matriz
ni demuestran persistencia, comportamiento OEM o compatibilidad física Android 16.
