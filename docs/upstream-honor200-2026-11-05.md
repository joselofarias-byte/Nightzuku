# Auditoría upstream y selección HONOR 200 — 2026-11-05

## Resultado y alcance

**Recomendación: adaptación selectiva de correcciones, no merge ni sustitución del árbol upstream.** El fork conserva NightDog propio, identidad JolufaSoft, permisos Shizuku y rish Java/C++; upstream introduce API Kotlin independiente, protocolo dual y nativos Rust. Compartir nombre y relación de fork en GitHub no equivale a compartir ancestro Git.

El trabajo delegado de auditoría no integró código, creó commits/PR ni modificó `main`; su único archivo escrito es `docs/upstream-honor200-2026-11-05.md`. La rama de integración sí contiene cambios de código realizados por el integrador, resumidos a continuación: no debe describirse el conjunto como un PR exclusivamente documental. Este documento no afirma compilación Android ni validación física. Se leyeron `AGENTS.md` y `CLAUDE.md`; no hay instrucciones anidadas en `docs`. Se compararon refs fijadas, no el worktree compartido que otros agentes pueden modificar simultáneamente. La revisión comprende inventario completo de commits y árboles, equivalencias de parches, cambios críticos y selección técnica; no es una certificación formal de seguridad de cada línea ni una auditoría completa de dependencias transitivas.

## Aplicado en la integración

Según el estado comunicado por el integrador, se adaptaron los fundamentos de `2ca359a` y `7c4af2d` al código y contratos JolufaSoft; **no se realizó un cherry-pick literal ni una migración a la reescritura upstream**. Las comparaciones y hallazgos de las secciones posteriores siguen referidos al baseline `origin/main` fijado, no describen necesariamente el estado final de la rama.

| P0 de esta auditoría | Aplicado / pendiente en integración |
|---|---|
| Límites, cierre y tiempos ADB | Aplicados timeout de conexión/lectura/TLS, máximo de 4 MiB antes de reservar el payload y cierre de recursos parcialmente inicializados. Presupuestos adaptados: 5 s de conexión, 10 s de lectura/TLS y 8 s de descubrimiento; no son los valores copiados del upstream. |
| TLS y transporte autenticado | Aplicado `requireTls` para el endpoint inalámbrico recién descubierto. No se da por incorporada toda la clasificación especializada de errores TLS/pairing del upstream: confirmar cobertura antes de considerarla completa. |
| Ciclo de vida mDNS | Aplicado descubrimiento inalámbrico fresco sin caché de endpoints y gestión de ciclo de vida NSD. Si no hay anuncio TLS, se conserva el TCP legacy habilitado explícitamente, solo en una interfaz local actual y con autenticación ADB; no se reutilizan puertos inalámbricos anteriores. |
| Timeout rish | Aplicada cancelación **solo** del timeout de adquisición cuando llega Binder, conservando el resto de callbacks y el camino rish existente. La validación adicional de `sourceDir` propuesta en P0 no se da por incorporada. |
| Pairing acotado | Los cambios específicos de timeout de `AdbPairingClient`, exclusión de SPAKE2 concurrente y ciclo de vida/notificaciones de `AdbPairingService` no se consideran completados por estos cambios ADB; quedan pendientes de confirmación o trabajo separado. |

El backport posterior identificado en `ApkChangedObservers` (`90081f9`) queda **fuera de este PR ADB mínimo**, por decisión de alcance. Tampoco se incorporan por esta sección las mejoras P1 ni las familias rechazadas/diferidas. Esta actualización registra la selección comunicada por el integrador, no una nueva ejecución de tests: compilación, regresiones automatizadas, APK/firma/SHA-256 y pruebas físicas deben respaldarse con sus evidencias independientes. En particular, la persistencia y recuperación real en HONOR 200 continúan sujetas a las restricciones y gates indicados al final.

## Identidad, refs e historia comprobadas

Consulta de GitHub y fetch realizados el 2026-11-05 UTC:

| Concepto | Evidencia |
|---|---|
| Fork | `joselofarias-byte/Nightzuku`, default `main`, `fork: true` |
| Parent y source GitHub | Ambos `kerneldroid/Nightzuku` |
| Upstream | `kerneldroid/Nightzuku`, default `main`, `parent: null`, `source: null` |
| Fork `origin/main` | `611aa4b085790ec6d79540b737517775a9491c76` |
| Upstream `main` | `4109165ad82d5f129b4560943cb5140be8d24cd9` |
| Upstream `dev` | `43b64d66f0da51785b27ebb8fd8fc4d2a06650d2` |
| Raíz Git del fork | `f7d10aa45e05579efcb4c9bfbffb6575601bddd5` |
| Raíz Git upstream | `6d18ddd7c79b42b99ae8f3c1dc4113d4666d65f7` |
| Historia disponible | No shallow; 157 commits alcanzables del fork, 163 de upstream |
| Merge base | Ninguna; `git merge-base` devuelve código 1 |
| `rev-list --left-right --count` | `157 163`; **no** interpretar como atraso convencional de una rama descendiente |
| Equivalencia por patch-id (`git cherry`) | 51 parches equivalentes (`-`), 112 no equivalentes (`+`) de upstream |
| `dev...main` upstream | `0 9`: `dev` es ancestro de `main`, nueve commits detrás |

Los 51 equivalentes explican parte de la ascendencia funcional anterior al cambio de historia; no prueban igualdad de estado final. Los 112 `+` tampoco son 112 mejoras faltantes: incluyen reescrituras, versiones, documentación y correcciones cuya intención ya puede estar adaptada en el fork. No usar cherry-pick masivo ni `--allow-unrelated-histories`.

### Diferencia de árboles

`git diff --shortstat origin/main audit-upstream/main`: **350 archivos, 34.755 inserciones, 12.962 eliminaciones** (con detección de renombres). Estados: 134 añadidos, 115 eliminados, 100 modificados y un renombre `R050`. Sin normalizar renombres, hay 337 rutas en el fork, 356 upstream; 221 compartidas, 121 con objeto idéntico, 116 solo fork y 135 solo upstream. El conteo incluye gitlinks pero no el contenido interno de submódulos.

Diferencias determinantes:

- Fork: `api` apunta a Shizuku-API `a27f6e4151ba7b39965ca47edb2bf0aeed7102e5`; upstream a nightzuku-api `455c1a607c515759c82a2840deb87fecc305fbe2`. `.gitmodules` cambia de `RikkaApps/Shizuku-API` a `kerneldroid/nightzuku-api`. No es un bump intercambiable: cambia proveedor, contratos y estructura de módulos.
- Fork: `compileSdk` 36.1, `targetSdk` 36, `minSdk` 25, Java 21, NDK `29.0.14206865`. Upstream: compile/target 37, build-tools 37, mismo mínimo/Java/NDK. Android 17 no es un prerrequisito para arreglar Android 16.
- `applicationId`: fork `com.joselofarias.nightzuku`; upstream `kerneldroid.nightzuku`. Permisos API cambian de `moe.shizuku.manager.permission.API_V23` al espacio Nightzuku propio, con compatibilidad legacy adicional. No copiar manifiesto, BuildConfig ni authorities sin revisión.
- Upstream elimina `manager/.../shizuku/NightDogRecovery.kt` y agrega `manager/.../nightdog/NightDogClient.kt`, módulo `nightdog` y proceso/watchdog distintos. Eliminar el recuperador del fork perdería comportamiento JolufaSoft.
- Upstream reemplaza loader rish Java y herramientas nativas por Kotlin/Rust (`rust/rish`, `rust/starter`, `rust/adb-pairing`, `gradle/rust-native.gradle`), reestructura configuración Java a Kotlin y agrega `NightServiceEndpoint`/`TransactGuard`.
- Hay 15 archivos `*Test.kt`/`*Test.java` bajo `src/test` en upstream frente a cero en el árbol principal del fork (excluidos submódulos). Son material para adaptar, no evidencia de pruebas ejecutadas aquí.

## Trabajo previo, ramas, PR y Actions

En la consulta había **37 ramas** del fork y **50 PR** históricos: 26 abiertos, 18 cerrados sin merge y 6 fusionados. Upstream tenía dos ramas (`main`, `dev`) y cero PR abiertos en esa consulta.

Referencias abiertas relevantes, **no incorporadas ni fusionadas por esta auditoría**:

| PR | Base / intención | Tratamiento |
|---|---|---|
| [#50](https://github.com/joselofarias-byte/Nightzuku/pull/50) | `main`; auditoría `audit/upstream-2026-11-04` | Referencia, no integración |
| [#40](https://github.com/joselofarias-byte/Nightzuku/pull/40) | `main`; `sync/upstream-rewrite-2026-10-01` | Reescritura experimental, no usar como base automática |
| [#47](https://github.com/joselofarias-byte/Nightzuku/pull/47) | `main`; `feature/honor200-nightdog-complete-adb-20261027` | Recuperación HONOR: revisar en integración separada |
| [#48](https://github.com/joselofarias-byte/Nightzuku/pull/48) | Base de #47; `fix/nightdog-adb-auth-diagnostics-20261029` | Continuación tras errores de autenticación; no equivale a código de main |
| [#46](https://github.com/joselofarias-byte/Nightzuku/pull/46) | `main`; `feature/nightdog-local-adb-discovery` | Descubrimiento local autenticado alternativo |
| [#45](https://github.com/joselofarias-byte/Nightzuku/pull/45) | `diag/boot-self-report-20261005` | Recuperación Binder, rama apilada |
| [#31](https://github.com/joselofarias-byte/Nightzuku/pull/31) | `main`; compatibilidad paquetes Android 17 | Backport pequeño separado |
| [#29](https://github.com/joselofarias-byte/Nightzuku/pull/29), [#28](https://github.com/joselofarias-byte/Nightzuku/pull/28) | Integración persistencia/rish y transporte Binder Android 16 | No asumir que están fusionados |

### PR #50: qué prueba y qué no

Head `e8373f13a2b12ff27817c1c14d2a4bfa50505117`. Contiene únicamente `.github/workflows/upstream-comparison.yml` (49 líneas añadidas) y `docs/upstream/AUDIT-2026-11-04.md` (una línea con secuencias literales `\n`). Se verificó su contenido con `git show`.

Su conclusión «sin ancestro común» es correcta y se reprodujo con historia completa. Su workflow solo informa ausencia de merge-base; en ese caso **no enumera commits ni compara árboles**, de modo que no constituye la comparación exhaustiva pedida. Limita a 80 commits la rama que sí tuviera ancestro. Este documento completa esas lagunas con comparación directa de árboles y patch-id.

Checks de #50 observados: `build`, `upstream` y `verify-server`, todos `SUCCESS`. Links: [build](https://github.com/joselofarias-byte/Nightzuku/actions/runs/41937565676/job/126949794940), [comparación](https://github.com/joselofarias-byte/Nightzuku/actions/runs/41937532990/job/126949680412), [server](https://github.com/joselofarias-byte/Nightzuku/actions/runs/41937565669/job/126949793952). Son resultados de **ese head**, no pruebas de la nueva integración ni pruebas físicas.

### Workflows efectivos versus registro de GitHub

El árbol `origin/main` contiene `android-pr.yml`, `app.yml`, `issue-similarity.yml` y `verify-nightzuku-server-pr-temp.yml`. La API de GitHub conserva 15 definiciones marcadas `active`, incluidas automatizaciones presentes en otras ramas o históricas: no interpretar ese estado como que las 15 existen hoy en `main`.

- `android-pr.yml` verifica checkout del SHA del PR, prepara Java 21/SDK, compila debug y release condicionado a secretos de firma, valida versión/certificado y publica APK. En el baseline **no ejecuta pruebas unitarias ni publica SHA-256 explícito**. No hay filtro ARM64 explícito en `manager/build.gradle`; el gate debe inspeccionar `lib/arm64-v8a`, no inferir ABI del nombre.
- `app.yml` conserva búsqueda de salida `out/apk/shizuku-v*-release.apk`; revisar contra nombre real del APK JolufaSoft antes de confiar en publicación. Firma usa otros nombres de secretos que el workflow PR: mantener coherencia sin imprimir valores. El fallback debug no garantiza actualización sobre una instalación firmada con clave permanente.
- `verify-nightzuku-server-pr-temp.yml` solo compila `:server:assembleDebug`; no demuestra APK manager instalable ni tests.
- Upstream añade CI de tests JVM/Robolectric y Rust, pero su workflow manual depende de SDK 37 y toolchain Rust. Tomar la idea de suites y artefactos, no copiar versiones/acciones/toolchain enteros.
- Release upstream r60: [run 41768156911](https://github.com/kerneldroid/Nightzuku/actions/runs/41768156911), `success`, SHA `4109165ad82d5f129b4560943cb5140be8d24cd9`. Prueba de publicación upstream, no de compatibilidad de nuestra identidad ni HONOR.
- Los cinco runs devueltos por filtro `--branch main` del fork fueron fallos históricos de agosto, no un run nuevo del SHA `611aa4b085790ec6d79540b737517775a9491c76`. No se extrapola el estado actual de compilación a partir de ellos.

## Cambios propios que deben conservarse

| Commit fork | Garantía a preservar |
|---|---|
| `611aa4b` | rish Android 16, detección de Termux/forks, encaminamiento de Binder e instrucciones exportadas |
| `a23e9ec` | NightDog completo e identidad runtime |
| `509d520`, `37d9a56`, `6f58cd4` | Compilación server y migración de permisos persistidos |
| `d727d50` | Consentimiento shell persistido para clientes sin permiso en manifiesto |
| `454a6b1` | Ejecución de scripts ADB desde contenido legible por shell |
| `290dfc1` | Manifiesto endurecido, eliminación de logging de claves, firma |
| `a6a1afc`, `581eae0` | Polling adaptativo y watchdog reactivo |
| `e4568ae`, `c0e5b46` | Binder/autorización y backport previo de rish Android 16 |
| `7ed5e46`, `9b842a9`, `c14ca22`, `eeffef7` | Paquete, marca y apariencia JolufaSoft |

Mantener `com.termux`, paquete `com.joselofarias.nightzuku`, archivo `rish_shizuku.dex`, consentimiento persistido, rutas root opcionales y shell UID 2000 sin afirmar privilegios UID 0. Conservar español y firma de actualización; un APK con otra clave no sustituye la instalación existente.

## Selección técnica priorizada

Los SHAs abreviados de esta sección se expanden en el inventario completo al final. «Adaptar» significa portar el comportamiento al código del fork, no cherry-pick ciego del commit completo.

### P0: pequeñas mejoras compatibles con el objetivo

1. **Límites, cierre y tiempos ADB**: `2ca359a` más `7c4af2d`. En `origin/main` `AdbClient.connect()` usa socket sin timeout explícito y `read()` asigna `ByteArray(dataLength)` sin cota superior antes de validar. Adoptar connect/read/TLS timeout y validación `0..MAX_FRAME_DATA_BYTES` antes de asignar memoria, incluyendo cierre seguro de recursos parcialmente inicializados. Referencia upstream: 10 s connect, 30 s read, 4 MiB/frame; para sondeo repetido usar presupuesto global más corto y no multiplicar 30 s por candidato. Probar payload negativo, cero, excesivo, cabecera truncada, peer sin respuesta y liberación tras fallo TLS.
2. **Errores TLS de autenticación**: `7c4af2d`. Añade `AdbPairingRequiredException` y recoge alertas TLS 1.3 que llegan al primer read, no solo al handshake. Evita reportar simplemente «no hay puerto» cuando el endpoint existe pero la clave fue rechazada. `requireTls` debe aplicarse al transporte `_adb-tls-connect._tcp`, manteniendo ADB legacy solo en camino explícitamente admitido. Revisar la comprobación final: rechazar también conexión plana `A_CNXN` cuando se requiere TLS; no confiar solo en negar `A_AUTH`. No clasificar toda `SSLException` genérica como pérdida de pairing sin evidencia. Continuar otros candidatos antes de concluir fallo global de autenticación.
3. **Ciclo de vida mDNS**: `7c4af2d`, `6611923` y tests de `2c2aea4`. Serializar estados STARTING/STARTED/STOPPING en main looper, detener registro aunque `stop()` preceda a `onDiscoveryStarted`, ignorar callbacks de listeners viejos y resolver excepciones de NSD. **Conservar** backoff del fork (2–30 s); upstream no lo sustituye por una recuperación equivalente tras todo fallo. Agregar generación/red y limpiar caché al terminar/cambiar Wi-Fi. Mantener host y puerto como una unidad. La interfaz upstream `Observer<Pair<String,Int>>` no es drop-in de `Observer<Int>` del fork.
4. **rish: cancelar solo el timeout de adquisición de Binder**: `7c4af2d`. El loader Java del fork programa `abort()`/`System.exit(1)` a los 5 s y no cancela el callback al recibir Binder. Es un riesgo concreto para sesiones interactivas que continúan en el mismo looper; probar sesión mayor a 5 s antes de afirmar que reproduce en HONOR. Adaptar cancelación y validar `sourceDir` con PackageManager; no reemplazar el loader por la arquitectura Kotlin/Rust ni perder el encaminamiento Android 16 del fork. No crear procesos auxiliares que maten shell principal.
5. **Pairing acotado**: `7c4af2d` y `90081f9`. En `AdbPairingClient` agregar connect/read timeout (upstream 10/15 s), `finally` de sockets/contexto nativo; en servicio evitar dos emparejamientos SPAKE2 concurrentes, separar IDs de notificación foreground y resultado y revisar `START_NOT_STICKY`. No reintentar indefinidamente códigos de pairing caducados ni loguearlos.

### P1: útiles, pero separar de la corrección mínima si aumenta el riesgo

- **Boot receiver** (`7c4af2d`): `goAsync()` para root también, `pending.finish()` en `finally`, deduplicación por BOOT_COUNT, operaciones Settings con captura de SecurityException, no asumir permisos por versión. El fork ya evita Safe Mode: conservarlo. Su ADB boot actual habilita ajustes y espera 3 s, lo que no prueba recuperación en OEM. No adoptar políticas de reactivación o eliminación de consentimiento de forma silenciosa.
- **Autorización UI** (`7c4af2d`, `3c65615`, `486675e`): no bloquear main con latch, defensa contra overlays, sanitización de etiquetas y comprobación de paquete/UID. Separar de firma pinning/config v3, que cambia persistencia y protocolo. Migrar permisos existentes explícitamente; nunca convertir registro persistido en autorización automática para paquete sustituto.
- **Archivos de módulos** (`f047b91`): límite incremental de bytes durante copia/extracción. El fork ya tiene 2.048 entradas y 200 MiB, pero `copyTo` sin presupuesto y comprobación después de escribir permiten exceder disco antes del rechazo. Mantener canonical-path traversal check. Probar zip-bomb por entrada, total, entrada insegura y archivos temporales. No portar a ciegas el arreglo CWD: upstream tenía concatenación shell, pero el fork ya pasa `cwd.absolutePath` como tercer argumento de `newProcess`; no se observó ese mismo patrón vulnerable en el fork.
- **Provider de conectores** (`f047b91`): validar pertenencia `callingPackage` a Binder UID antes de devolver comando, manteniendo contratos y permisos existentes. Esa validación es defensa de identidad, no una nueva autorización suficiente para ejecutar comandos privilegiados.
- **Compat de paquetes** (`6e92830`, `8df6f09`, `0797785`): adaptar solo si se confirma fallo en las utilidades del submódulo actual. Android 13+ usa flags long y versiones anteriores int; evitar unificar firmas a costa de romper Android antiguos. No subir target a 37 por un fix de reflexión ni declarar Android 16 validado porque upstream menciona 15–17.
- **Pruebas** (`2c2aea4`, `6611923`, `adad053`): adaptar AdbMessage/AdbMdns y regresiones relevantes a Java 21 y firmas locales. No importar suites de NightClient, config v3 o Rust que no existen en el fork.

### Rechazado para esta integración / diferido con motivo

| Familia upstream | Commits representativos | Motivo |
|---|---|---|
| Nueva API Kotlin, NightProvider, dual wire, standalone runtime | `0758f10`, `10c8213`, `adad053`, `02cd4c2`, `e690445` | Cambia API/submódulo/proveedor/compatibilidad cliente; requiere proyecto de migración y pruebas propias |
| IPC async, attach/exit-fd, cancelExec, call IDs por cliente | `105c822`, `0797785`, `d5b14ca`, `e049a13` | Arreglos reales de la nueva arquitectura, no aplicables textualmente a AIDL legacy. `e049a13` evita colisión entre clientes; no afirmar la misma vulnerabilidad en el fork sin demostrar camino equivalente |
| Nativos Rust y Binder pull proactivo | `c033a44`, `a226812` | Cambia arranque/rish, toolchain, FD y contrato Binder; conservar arreglos Java/C++ y Termux del fork |
| Configuración v3, pinning y pausa global | `486675e`, `2e93d70`, `64e4cf9` | Seguridad valiosa pero breaking: esquema, firma, permisos persistidos y user services no son un parche local |
| Guard/HMAC y anti-fork attestation | `cf745bf`, `9eaa31c`, `3f658fe`, `0797785` | Contrato de identidad upstream incompatible por diseño con fork JolufaSoft; no eliminar controles para fingir ser upstream ni copiar material de identidad. Auditar adaptación propia aparte si se necesita interoperar con módulos Guard |
| Catálogo, token GitHub cifrado, auto-updates, WebUI HTTPS | `748d544`, `afee009`, `28ed591`, `095b263` | Nuevas superficies de red/credenciales/background; no necesarias para ADB HONOR. Keystore es útil si se incorpora catálogo, pero no agrega una razón para incorporarlo ahora |
| Rediseño UI/guías/TV/Wear/locales | `821cb36`, `2794977`, `43fa95b`, `8b21988` | Riesgo de perder marca, español y flujos existentes sin solucionar recuperación ADB |
| Target SDK 37 y permisos nuevos del manifiesto | `84d6020` y evolución posterior | Android 17 no requerido; no copiar permisos especiales como mecanismo de elusión de políticas del fabricante |
| Versiones/releases/API documentación | `4109165`, `6b1ce0d`, `0380ad1` | No son correcciones runtime; preservar versionCode monotónico del fork y firma |

## Brecha de recuperación que upstream no resuelve por sí solo

En el baseline del fork `NightDogRecovery.resolveEndpoint()` consulta `AdbMdns.getResolvedEndpoint()` y luego `EnvironmentUtils.getAdbTcpPort()`. `getResolvedEndpoint()` prioriza preferencia TCP persistente sobre mDNS; el mapa estático de endpoints no es una prueba de frescura. `isPortAvailable()` intenta hacer bind como servidor: que bind falle **no prueba** ADB, autenticación ni Binder saludable. Upstream todavía usa una prueba de bind similar; copiarlo no satisface verificación real.

Diseño mínimo recomendado al integrador:

1. Al iniciar cada operación, resolver servicio **connect** local `_adb-tls-connect._tcp` de nuevo; distinguirlo del puerto temporal de pairing. No leer `~/.honor200-adb-serial`, una preferencia de puerto previa ni un resultado estático de otra red como autoridad. Una preferencia TCP explícita puede seguir existiendo como modo distinto, no suplantar descubrimiento inalámbrico solicitado.
2. Un solo descubrimiento NSD por operación, dentro del proceso y cancelable. Presupuesto total acotado; candidatos locales validados por interfaz/IP y rango, deduplicados. Cambiar generación y limpiar resultados tras Wi-Fi perdido/cambiado; detener listeners/timers al cancelar o completar. No escaneo de red ajena, no barridos permanentes de 65.535 puertos ni `pkill`/`adb kill-server` global.
3. Abrir cliente ADB autenticado; exigir protocolo correcto/TLS según transporte; ejecutar comprobación inocua con salida esperada antes de considerar endpoint disponible. Una conexión TCP abierta no es éxito. Antes de operación privilegiada comprobar Binder y permiso efectivo si se usa Shizuku/rish.
4. Si cae sesión/Binder, invalidar endpoint y volver a descubrir con backoff, presupuesto y recuperación single-flight. No repetir automáticamente comandos no idempotentes: se desconoce si el servidor los alcanzó a ejecutar.
5. Distinguir logs: sin anuncio, timeout, dirección local desaparecida, TLS/clave rechazada, pairing requerido, rechazo del sistema a segundo plano, Binder muerto y permiso denegado. Evitar datos de claves, código pairing, tokens o contenido de comandos sensibles. Aplicar throttling de logs y detener recuperación ante apagado manual.

## HONOR 200 / Android 16: límites y gates pendientes

No se dispone aquí de HONOR 200 ELI-NX9 ni evidencia nueva de MagicOS 10. No atribuir a un bug OEM específico lo que solo se infiere de código. Ningún commit examinado demuestra que se pueda mantener adbd/Binder tras cualquier reinicio o cierre impuesto por firmware.

Sin root, con shell UID 2000 y permisos realmente concedidos, se puede redescubrir/reconectar mientras adbd esté habilitado, anunciado y acepte la clave. No se garantiza reactivar ADB deshabilitado, conservar pairing tras revocación/restablecimiento, arrancar en Safe Mode ni superar restricciones de ejecución/actividades en background, force-stop, bloqueo de usuario o políticas Wi-Fi OEM. Root es opcional, no un supuesto. El otorgamiento de `WRITE_SECURE_SETTINGS` no equivale a root ni vuelve inmortal un proceso.

**Validaciones obligatorias en integración, todavía no realizadas por esta auditoría:**

- Build Android real de manager/server/rish con SDK 36.1 y Java 21; tests JVM pertinentes; revisión de merged manifest (exported, permissions, foreground service, direct boot, authorities).
- APK con `com.joselofarias.nightzuku`, `lib/arm64-v8a`, dex rish y versión/SHA correctos; `apksigner verify`, SHA-256 y publicación de artefactos para el head integrado. Firma permanente para actualizar instalación existente; debug no es sustituto silencioso.
- HONOR físico: frío tras reinicio/desbloqueo, cambio de red/puerto, Wi-Fi apagado/encendido, claves revocadas, desconexión, pantalla apagada/Doze, app expulsada, apagado manual respetado y sesión rish >5 s con `com.termux`. Registrar versión MagicOS, restricciones batería, logs saneados y resultados; pendientes hasta prueba real.
- Regresiones: pairing separado de connect, callback NSD tardío, stop-before-start, red perdida, candidatos rechazados seguidos de candidato válido, cancelación, timeout, doble recuperación y no reejecución destructiva. Las dos regresiones NSD ya existen como tests upstream, pero necesitan adaptación al fork.

## Comandos reproducibles y fuentes

Todos los comandos de comparación operan sobre refs explícitas; no ejecutar merges ni reset del worktree. Para una repetición futura usar los SHAs anteriores, porque `main` cambia.

```sh
# Descubrimiento y metadatos públicos
gh api repos/joselofarias-byte/Nightzuku \
  --jq '{full_name,default_branch,fork,parent:.parent.full_name,source:.source.full_name}'
gh api repos/kerneldroid/Nightzuku --jq '{full_name,default_branch,parent,source}'
git fetch origin main
git fetch https://github.com/kerneldroid/Nightzuku.git \
  '+refs/heads/*:refs/remotes/audit-upstream/*'
git fetch origin refs/pull/50/head:refs/remotes/audit-pr/50

# Historia, equivalencias y árboles
git rev-parse --is-shallow-repository
git rev-list --max-parents=0 origin/main audit-upstream/main
git merge-base origin/main audit-upstream/main; printf 'merge-base exit=%s\n' "$?"
git rev-list --left-right --count origin/main...audit-upstream/main
git cherry origin/main audit-upstream/main
git log --reverse --format='%H %cI %s' audit-upstream/main
git log --format='%H %cI %s' origin/main
git diff --shortstat origin/main audit-upstream/main
git diff --name-status origin/main audit-upstream/main
git ls-tree -r origin/main
git ls-tree -r audit-upstream/main

# Código crítico y referencia previa
git show 7c4af2d -- manager/src/main/java/moe/shizuku/manager/adb
git show 2ca359a -- manager/src/main/java/moe/shizuku/manager/adb/AdbClient.kt
git show f047b91 -- manager/src/main/java/moe/shizuku/manager/module
git show audit-pr/50:docs/upstream/AUDIT-2026-11-04.md
git show audit-pr/50:.github/workflows/upstream-comparison.yml
gh pr list --repo joselofarias-byte/Nightzuku --state all --limit 100 \
  --json number,title,state,headRefName,baseRefName,mergeCommit,url
gh pr view 50 --repo joselofarias-byte/Nightzuku --json headRefOid,body,files,statusCheckRollup
gh api --paginate repos/joselofarias-byte/Nightzuku/branches --jq '.[].name'
gh api repos/joselofarias-byte/Nightzuku/actions/workflows
gh run list --repo kerneldroid/Nightzuku --limit 4 \
  --json databaseId,headSha,status,conclusion,name,url
```

Fuentes primarias: [árbol fork](https://github.com/joselofarias-byte/Nightzuku/tree/611aa4b085790ec6d79540b737517775a9491c76), [árbol upstream](https://github.com/kerneldroid/Nightzuku/tree/4109165ad82d5f129b4560943cb5140be8d24cd9), commits e inventario siguientes. El contenido de submódulos se identificó por URL/gitlink; no se afirma revisión exhaustiva de todos sus commits ni de implementaciones propietarias MagicOS.

## Inventario completo de los 163 commits upstream

Orden cronológico del grafo (`git log --reverse`). `=` significa patch-id equivalente en el fork, no mismo SHA ni prueba de que siga idéntico en HEAD. `+` significa sin equivalente por patch-id; usar selección anterior para decidir, no interpretar como aprobación. Incluye documentación, releases, funcionalidades y arreglos; títulos originales conservados para localizar evidencia sin traducir identificadores.

| SHA completo | Patch-id frente al fork | Asunto original |
|---|---|---|
| `6d18ddd7c79b42b99ae8f3c1dc4113d4666d65f7` | = | chore(repo): import Shizuku baseline |
| `d576e62b7ecf7af41a65b00d84ff173c5c1448ff` | = | chore(repo): restore Shizuku API submodule |
| `84d6020bc9806e6945f760a200398757f5ff9ce8` | = | feat(android): modernize Shizuku for SDK 37 |
| `a1a6bb61b407efbc60a646ff4c5f48d0f0870af7` | = | ci(app): make sdk37 builds manual |
| `dc6c31cffe9b9ee7949dcab278a628aea1305caf` | = | feat(manager): finish Compose expressive migration |
| `0ad8fe5f64a6bc61688892bcbee0b7d214ff1ceb` | = | fix(manager): avoid adaptive icons in Compose cards |
| `2bb2e73a647fe5293b002c80200e61ca241166fe` | = | feat(manager): polish expressive UI and binder delivery |
| `57012f378abd6069718485c5f3f5fc1008c3e3b6` | = | feat(manager): add select all apps functionality and update screenshots |
| `93fe85e7983c377b74254e988a34c8caf9b34ed3` | = | feat(manager): add adb modules support |
| `59f415e2b0185dbde06e89f5c966b2f2c09c1ef3` | = | feat(modules): add JavaScript-to-shell bridge for WebUI |
| `3fea0a949fcc1be51cf57cd47f39dc1e68d29ab0` | = | docs: update API to include JavaScript-to-shell bridge |
| `780e84b82fbf5ab3f7776c8a4ff5b7ad0d040a91` | = | feat(modules): advanced JS-to-shell bridge, file download, and WebUI HTTPS loads |
| `bbc0bd1400dcc7226426b4c5a4099f796c4713b0` | = | fix(modules): preserve JavascriptInterface methods from Proguard/R8 obfuscation |
| `32969bfd3ff42981594d2ac2142dc215635241a4` | = | feat(modules): harden WebUI command execution |
| `528c3fc3e85e87444c6f4c4b2e744ef8c2ba0a13` | = | fix(modules): expose local WebUI bridge safely |
| `86d67d9b34a12ab4d6b900f8b3ca1c4ec5c6a92f` | = | fix(modules): refresh adb test module zip |
| `1bed5c3addb06df91df9b7cb33899b982ad14dec` | = | fix(modules): hide AI checker behind unlock |
| `e0c252062ebe4cb5a4227b25ec262544e8c68e3f` | = | fix(modules): polish ReCommand controls |
| `fbc4301b49f92245c8a584a795c0ea93169c3041` | = | feat(modules): add per-module Full Trust |
| `93da98534b39b38b50fe80fa5f93aade76318724` | = | fix(modules): toggle Full Trust action visibility |
| `55897fd1fd36bd74db9f6f232ea9c90099112904` | = | fix(manager): fix trust/untrust logic to sync with enabled state and reload UI |
| `5d090b9859d732b1cd6f5661aa65964cb1bbaae2` | = | ci: automate apk upload to github releases on tag push |
| `cbb5f0ff668514fc97c6a0c2f5754b9b589991c4` | = | fix(manager): force UI recomposition on trust toggle by using neverEqualPolicy |
| `c9e51e9ac3cfa8d7ebecb13d6185bfc5626667fb` | = | chore: enable Edge-to-Edge and optimize nonTransitiveRClass |
| `8e0edfb99f3066b6c6cdd92e1b9af69f79bfe83c` | = | docs: restructure README, add strict templates, and setup issue similarity bot |
| `8caf3f4f540c8bbc66d7f0261ba07689026c0af7` | = | docs: restructure README, add strict templates, and setup issue similarity bot |
| `753077929b95d8824b89f3dcf375a6ad9897be85` | = | docs: add strict CONTRIBUTING.md guidelines |
| `6f824be6516e4a1d397a23cc2a14e219c3a7c47e` | = | docs: professionalize contributing guidelines and templates |
| `f8d018aa86a71b94f195a889a8e9cdedbf8fdb4e` | = | feat(issue): add concise feature request template |
| `122677b0e2be8efdc947922a766923a9f619ceca` | = | feat(connector): add Shizuku Connectors and remove AI features |
| `9ad129811ff5aadaf5611ee76fd007a3fd76f3b1` | = | feat(connector): add Shizuku Connectors, remove AI, and cleanup languages |
| `d9615352b11f14ad35aa950bed7bb9b3a3bafe9c` | = | fix(security): resolve resource leaks and race conditions |
| `1c64329a51b0cd4bef90809aa00084a0fcfa9c8a` | = | fix(compat): implement Android 17 (API 37) compatibility for PackageManager & PermissionManager |
| `a82d1ad0dbef0ecf6fbe27953de49fcbd0679921` | = | feat(wearos): migrate to native Wear Compose Material 3 UI |
| `3770a84775c55dbfe8fdf6c62b2bf63da7c3af70` | = | readme: fix the mistake |
| `ca6cf7c7f53d947937bc944126f97f3b94ff8f73` | = | readme: fix another mistake |
| `968145958e18f59bc6de5980f4c0f2e8e63bb17b` | = | fix(compat): implement production-grade Android 17 (API 37) compatibility |
| `2f3502f0c0bbed65481b40aa5b17767a89cde4ee` | = | fix(compat): implement production-grade thread-safety and robust overload signature resolution for Android 17 |
| `03c740a54cccf8364495e0e0d3216a0a17249362` | = | rebrand: migrate project identity to Nightzuku |
| `8925c17682928b7a7954b8c1b027c9ea868ef990` | = | readme: update README.md |
| `019d8a03c46a7f98772299d8d9dc825c62fd4587` | = | readme: renaming... |
| `a6342728709a9d01f770ffdaf0bf6c9fc58f852c` | = | docs: Fix hallucinated Nightzuku class names and URI paths to match actual code |
| `7a11086150e08a900a0880aa171ee8ca04223a13` | = | feat: modernize dialogs with Compose Material 3 and enhance WearOS support |
| `f3ebfa0f6426127f0481f23f1b04777673c74128` | = | docs: announce Wear OS 7 support and confirm backward compatibility |
| `a399a81ff4ce16ab0fdbc2f61700bbbc91bc5be2` | = | fix(wear): HOTFIX r44 - refactor imports and pairing state logic |
| `748747d346e05da80bf0cadd672b6b67917591c1` | = | feat(tv): release r46 with full Android TV support |
| `c43ff4d975cefeabec221ce78a04c92406fd0b07` | = | fix(security): sanitize shell args and protect binder/ipc entrypoints |
| `0280d8ca719635e6be0e5588235b9d3f7902a7fa` | = | chore: cleanup localization and finalize r47 security patches |
| `cd84b54d067b7f4503e317de391bb6629fb635f1` | = | feat(l10n): add high-quality Chinese translations (Simplified/Traditional) |
| `958d19557265e57f87dae2b17cd94dcb003e7024` | = | chore: force version to r47 for official release |
| `51f966706d3fa5380edd8acde66a69570bc9592f` | = | readme: delete ai placeholder |
| `84d9f94ceb08f3cd081758ba2778a42d11f80868` | + | fix(adb): resolve Wear OS 5 pairing shadowing and standby freezing |
| `ba78338eba81542094aa7c6f42190c7459e36f3a` | + | fix(wear): restore scrolling transformations and update main screen title icon |
| `3c65ae31c57ba685febc741d308021bf781f7bd4` | + | fix(shell): resolve rish timeout by updating hardcoded package name and fixing Termux linker error |
| `cb30302c8567bccf3b29aa2b0ee1cec4aeb764e3` | + | fix(shell): resolve rish timeout on Android 16 by using startActivity fallback |
| `6e92830e34ca109e9029f1dbdf3c819c64402b8f` | + | fix(server): fix Android 17 getInstalledPackages returning empty list |
| `1894d1943bfcfb2ede7384df82ab3cd1c325b3c5` | + | readme: add credits section |
| `d3da6eead803bc5dca3de826978e9c7a6adadc2c` | + | feat(nightdog): add NightDog watchdog for server health monitoring |
| `854e56dc39f641864542de6dee9c0e3af51fba9f` | + | fix(security): harden manifest, remove key logging, split god files |
| `2a0b83b31b574e481411868ef28114a785781225` | + | feat(tapi+nightdog): add Termux API bridge and fix NightDog watchdog |
| `8aca8158061d807cdbaf14b985e6b51fca3af8c7` | + | feat(tapi): universal bridge for Android 7-17, separate TAPI toggle in Lab Features |
| `47bf92b4693a958ac0bcac3d6d662bdc08f614d8` | + | fix(tapi): keep TapiManager in Proguard and use rish_shizuku.dex |
| `138a134e6cb967986e0c409a09ebddd7a302cb5f` | + | fix(module): remove wrong TvTheme color from phone dialogs |
| `672adafaca18124503f8a2e7a23b96d58304ee48` | + | chore: remove unreachable System.exit and extra blank lines |
| `e5e3902482592dc68bd1bbf1526ce42deeca4dfe` | + | feat(catalog): add online ADB module catalog with GitHub discovery |
| `854afd6187cc4e4ce270ce8a57b428775f2eb96c` | + | docs: add GitHub Catalog documentation |
| `707e3e96dfe019cd757b7f7a5dfe1129b13e4dcd` | + | chore: bump version to 13.7.1 |
| `fce85e42988574e6886eb16218f7b8832e4585f9` | + | fix(catalog): dialog theming + bump version to 13.7.1 r52 |
| `51decffc97a7d3c851906d39331b7f3fc1a2cace` | + | feat(catalog): full catalog UI redesign, module detail screen, danger detection |
| `cd2737af20dca6a2d858b6623331f392f1f8d58f` | + | readme: Update README with repo update status |
| `60a8feb65d1a9c95692624222ef26afb3063b9d3` | + | readme: add warnings |
| `1b64e371078b50f0861bd852cebdc00f1c2fce3c` | + |  readme!: remove old development notes |
| `d17d8b8bd486eb0842bae15927db6e3ce9a01e85` | + | chore: upgrade dependencies, convert the sources to Kotlin and drop TAPI |
| `6a4b8e7fffbccb00928e8addcf38c420ae11198c` | + | build: build the native libraries from Rust, and teach CI to do it |
| `c298262f19d7c606ad6159c2f838f09761df36c7` | + | feat: fix the Android 15+ permission calls, redesign the mobile settings, correct the docs |
| `f3acd3c7767a15506bad41d0b56e12d8261ac78b` | + | feat(manager): give the home screen a Material 3 Expressive hierarchy |
| `7e721836dd5c3f8a0c1a7648a97e2a0977c30c80` | + | style(manager): tighten the Start group on the home screen |
| `b283f507eeadbceb6d52582994bfe39ec962763f` | + | fix(nightdog): harden watchdog lifecycle, rebind recovery, and starter supervisor |
| `a722b57ce9f1885ab5f46dacb1f40d4f9d181977` | + | fix(manager): make NightDogClient offline-safe and update Lab Features UI |
| `69d81ccc8c472e4ecd0ca6ca2c57235e8a1974cf` | + | docs(nightdog): document recovery flow, starter reset, and offline sync |
| `67f7439f44c58e1c80392a93fe8c35a2c49c42db` | + | fix(starter,nightdog): ignore SIGPIPE/SIGHUP, daemonize supervisor, and invalidate Android17Compat cache on rebind |
| `4777da9c9f53941a175a68e9c257b2de3f85e045` | + | fix(modules): fix source ZIP installation, path stripping, nested dir traversal, and module internals |
| `0abfa9df9ddad5c2a6bce1944dbfe8a0ff10ca18` | + | chore(release): bump version counter to r56 |
| `508e5a02019b0ca577ba18ceecf2c9197622d3e2` | + | fix: remove termux tapi backdoor and decouple manifest from legacy API_V23 |
| `fdd37012bd6c616bd4d250e0cbaca363ed85f044` | + | feat: implement zero-stub dual-wire Nightzuku server protocol and security guards |
| `b3b88a9fb57129b80a2c2511f82e158f5599f81b` | + | feat: add Nightzuku client SDK and dual-wire starter/provider integration |
| `667783af766900e85821ff48e267bf972542f214` | + | chore(api): bump nightzuku-api for the Android 17 user-service startup fix |
| `dc7181a1b70c33388792a743dc8f3389899e04ef` | + | fix(manager): shrink the running-status hero and show the real app version |
| `463d045368c71b83c38fd2b69ed719fa69091abf` | + | feat(manager): animate the settings category switch |
| `e183be3dd0af482c9c2a47f833eba0b872a0945f` | + | style(manager): render the app list with native segmented rows |
| `3799b733f4cdc9d4ad848fdb16f07198268592ba` | + | style(modules): widen module cards and nudge the top-bar actions left |
| `35915a71ab324235ca8ffa7d02fb5d1232705d48` | + | feat(catalog): add a downloads chip and per-folder outdated detection |
| `a9aade74ed853fbac35668ed875cb50a2f88f442` | + | fix(server): send legacy binder via BinderContainer and notify manager first |
| `d03c9c3679d791a6f342417fa2c9e0d46c1f155b` | + | fix(provider): hand the user-service binder via BinderContainer as well |
| `b1044ef2249a039f087d05d1047674d8d5eb2c94` | + | fix(manager): tighten app-list spacing and auto-refresh the authorization state |
| `de408fa61a8060d2121a8b3b1843d32d9505f83d` | + | fix(manager): close the starter screen as soon as the service is up |
| `353b46a02f10cf391cf8aaa82df9bcdecf4ea8e7` | + | feat(manager): let the user choose root or ADB before starting |
| `52ab99be00e6e22d8b815e9d9ab657d97b96567a` | + | chore(release): bump version counter to r57 |
| `7502ecf3fab51884b95ae56d7aafea696b18cd57` | + | feat(i18n): add Ukrainian (uk) localization |
| `d169a6a0a1596522a3180fe168a919b6e5fc3df2` | + | docs(readme): document the dual-wire protocol and the calm migration from Shizuku |
| `3251e31672decb46ea04bbdaffc330f931ca1ed8` | + | chore(manager): remove legacy View/RecyclerView cruft, dead XML resources, and unused dependencies |
| `90081f943fc4ada17209a1c838e328b7a4ef08f0` | + | fix(server,manager,common): fix IPC, AdbPairingService, and WebView bugs, socket leaks, and broken localization resources |
| `33c6b7e3941701277745dfbaca16fbb7e1107a15` | + | feat(nightdog,module,ui): implement background module update checks, NightDog integration, and UI output (Phone/TV/Wear) |
| `2ca359a86c3c11081562b2b01f3d68b8bdd21a9b` | + | fix(common,server,manager): second wave of fixes: CountDownLatch in Provider/RequestPermission, NightClient, AdbClient, HTTP leaks, and GitHub rate limits |
| `afee009735820afd896747f70fabf261590bad7b` | + | feat(nightdog,update,ui): flexible optional NightDog-based module updates (auto-update, Wi-Fi filter, notifications, batch update, changelog, and auto-rollback) |
| `4a1a357e2fe1dd01f89d59b6cbd6b5edc3f25cb4` | + | fix(manager): remove invalid @Volatile annotation from local variable |
| `9d609c7ee7615ecd4d3cbf648c3646572a5a94ed` | + | chore(api): bump nightzuku-api submodule |
| `886602a999bc506b420aefe76ac7fa8abde5c1d0` | + | fix(apps): stop app list lag by moving Shizuku IPC and icon decoding off the main thread |
| `c51c4103f80e2c7d1644f16b1bc2f57af4959ff2` | + | feat(manager): use the new owl artwork as the launcher icon |
| `4cb785a190c14a399d2e9a0746e5c0702d8e561f` | + | feat(manager): switch to the palm-tree artwork and pad the launcher icon |
| `eb9a1502e67b9cddbe72fffcffe2065e85d391c4` | + | fix(build): stop Gradle from tracking rust/target during cargo builds |
| `8df6f095bb6c04dd4fcf9d6b0dec2739c81c574c` | + | fix(server): resolve package APIs with int flags on Android 12 and older |
| `75441672998cc5d1e5c1c84c544ac9c0ab70acd6` | + | docs(server): document the API 33 flag-width split for package APIs |
| `3c6561550463c314b9b3d7964a4419e601d08ec8` | + | feat(common)!: add package signature verifier, label sanitizer, and protocol extensions |
| `486675ee1f9901e15d2a95c0c3ee0a7d55272a25` | + | fix!(server): enforce package signature pinning, prompt verification, and global access pause |
| `64e4cf94a526c89b6da4d9aa65a1b8f6ff006692` | + | fix!(starter,nightdog): harden UserService launch verification and NightDog service monitoring |
| `c033a445c56d83f40fee055c4103fc429bb7ec0f` | + | fix(rust): add safe server replacement handoff, UID verification, and fix native FD leaks |
| `7c4af2d36265246662c4f66834361f946e2b4238` | + | fix(adb,manager): harden RequestPermissionActivity, mDNS lifecycle, TLS pairing, and boot start |
| `f047b91607e73e32b6f29801fc329e7aebad8c29` | + | fix(modules): fix shell CWD injection, bounded ZIP extraction, and semantic version comparison |
| `08cd1d79e1d74a1a853f7129c0d3026326d854c4` | + | docs(readme): credit Porter for architectural ideas and document security improvements |
| `2c2aea47399d6de5756d303bbd86a09b3d50f31d` | + | test(core): add internal unit tests for common, server, starter, nightdog, manager, and rust |
| `ab5662d32124fb41e4adec7f2c6901fc235bbfec` | + | ci(tests): add manual workflow_dispatch GitHub Actions pipeline for unit tests |
| `2461f639a8da7c74fbae3946b54dfdc71789a8f8` | + | chore: normalize executable bits on new source and test files |
| `5add97c1a6abfdb59bbc875a7c0e4569fa6627da` | + | fix(common): complete the verifier, sanitizer, and parcel codec APIs |
| `2e93d70204a719fc1dac027e147c727494bec5fc` | + | fix(server): align the v3 config schema and signature verification |
| `6611923bd233910ece0c87a5849327c89e9683b7` | + | fix(manager): make the permission prompt and ADB mDNS tests run on JDK 21 |
| `28ed591ff42b17d12535af14dc3ac6ae13c59fa8` | + | refactor(modules,nightdog): decouple update checks into JobScheduler and deduplicate NightDog/Modules logic |
| `1caf203f5b88871504fcf9e7b3693bb411097206` | + | fix(catalog): remove double horizontal padding and match installed module container proportions |
| `cf745bf93c719c1a24f6a870a96ae8486ce42435` | + | feat(modules): add cryptographic anti-fork host attestation and drop-in guard scripts |
| `9db95b576ba3ce2890c987b0190c804550f5ea5d` | + | fix(ui): resolve Material3 bottom navigation bar strip and add missing tab transitions |
| `9eaa31cfdbe7118cf0f6584a571c614ebc7e1679` | + | docs,chore: update architecture docs, add anti-fork unit tests, and harden build.gradle |
| `3f658fedb1efef7b99fbcae97b8c90207e417c97` | + | fix(modules): align attestation tests and docs with the shipped runtime contract |
| `a17a5b561e5747edabdade0aa66e1ceafe51f5dc` | + | chore(release): bump version counter to r58 |
| `fe5e332121015c06cba4969387a888504716d427` | + | fix(manager): repair Ukrainian translation, wrap locale context, and remove fade transitions |
| `40ae20058867c60830b8149a8799365728f4b1ae` | + | feat(modules,home): add M3 Expressive pull-to-refresh, KArchiver catalog popup, scrollable chips, Nightzuku Guard chip, and dialog icon actions |
| `821cb3683905e70fe1e8b1742581a47aa5b05bbc` | + | feat(ui): redesign rish tutorial, wireless pairing, and starter screens in Material 3 Expressive style |
| `279497703256b0ba5f9d3b408021cfece8777002` | + | feat(management): separate Native/Legacy wire apps, add search bar, and add 3-4s hold deep app control screen |
| `9262e21fcd5d39ea982746432062647cc05bf506` | + | feat(modules): replace top actions with code popup menu, remove check banner, and move background actions toggle to download/menu |
| `095b2635976ecee83cc426d9ab5e4c83c49c6a8b` | + | feat(webui): serve module WebUI over isolated virtual HTTPS origin for ES6 modules without Full Trust |
| `ca02387ec272f5bf57b0cb3ccf80b3a5bffab944` | + | fix(modules): make ModuleWebAssetServer compile against the runtime helpers |
| `c5cec78ec6bcd456e877eb7294d4e2bdd01c28c1` | + | feat(ci): add automated emulator screenshots for phone, Wear OS and TV |
| `8366245ec4037b2e97b3125ff382f36bc75c0170` | + | chore(ci): drop the scheduled screenshot refresh |
| `67cf6d300b4895bcd4e3cf5538faa4782ca2371a` | + | chore(ci): restore the weekly screenshot refresh |
| `0758f100322d97ecb113d636fd493c8a61fbceef` | + | refactor(common): extract Nightzuku API to nightzuku-api and remove duplicate OsUtils |
| `10c8213e4e519c8a8f6ae67895c9999e2c8016f8` | + | refactor(server,starter): align with Kotlin nightzuku-api and unified IContentProviderUtils |
| `adad05349ca1af10a74ddb62b674983cadf42b76` | + | refactor(manager,shizutest,ci): adopt native NightProvider and run shared unit tests in CI |
| `02cd4c210d4392157026a0c353175558a2ba18cb` | + | fix(build): finish wiring the Kotlin nightzuku-api refactor |
| `f79a01454ec92b16bca7e7a33a8a4fb057c506cc` | + | fix(manager): write wireless ADB settings through the server on Android 17 |
| `748d544a3d2991654727a2c1edf24e74e31673ce` | + | fix(catalog): encrypt the GitHub token with Android Keystore |
| `43fa95b6fc627adec5f998314460ee55858adb94` | + | feat(guides): rebuild the rish and wireless pairing guides as expressive step flows |
| `8dd391a58f2a9aae9b0d4734ced75ba8d8a824a5` | + | fix(guides): remove gradients and oversized containers, compact the starter |
| `316bf23a6cc4d787de18d739697dd96b16243721` | + | fix(ui): animate subscreen entry, keep settings route on theme change, and clean up app management |
| `8b2198858a394ee12744cd90099c5c4e37d612de` | + | feat(ui): add the Shizuku-stub notice and animate activity transitions |
| `43b64d66f0da51785b27ebb8fd8fc4d2a06650d2` | + | chore(release): bump version counter to r59 |
| `a22681229fe8129f736012d86b1748edd5990199` | + | feat(server,manager,rust): add proactive client binder pull and harden rish host |
| `105c82290dde09fa1b2de7adfcec9f4d2ae1cbb9` | + | feat(server): add async exec, attach handshake and exit-fd plumbing |
| `e6904452b6a2b9a72fac2d1f7fce9978a08bd883` | + | chore(api): bump nightzuku-api to the standalone server runtime |
| `0797785262fc44cd0881e8734c7d4335d77c6bea` | + | fix(server,manager,locales): fix IPC capabilities, Android 15-17 permission compat, access timers, cancelExec, JS bridge, Wear PAT input, Guard HMAC, SELinux module staging, bounded ZIP downloads, and 100% locales |
| `d5b14cadaf8bc0473d15394b4c08b24893183e6c` | + | fix(server,manager,modules): resolve oneway exec pids and restore full build |
| `e049a134f90371d8dee08aea1a5168db52bc41ff` | + | fix(server): scope exec call ids per client |
| `0380ad1aa877519407a79184704be60699157d52` | + | chore(api): bump submodule for the 13.2.1 release docs |
| `6b1ce0da91cb059b006d95a31ab9961bef66f69a` | + | chore(api): bump submodule for GitHub Packages docs |
| `4109165ad82d5f129b4560943cb5140be8d24cd9` | + | chore(release): bump version counter to r60 |
