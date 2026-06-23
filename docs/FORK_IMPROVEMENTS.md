# Fork Improvements / Mejoras del fork

## English

This document summarizes the improvements added in this personal fork/adaptation of Nightzuku/Shizuku.

This fork does not claim authorship of the original project. The goal is to adapt the software to personal needs, improve usability where possible, and add Spanish support for users who may have difficulty using the application in English.

Current branch: feat/application-management-search-ui

Recent confirmed HEAD: 3436036f Fix ADB pairing peer info from r49

## 1. Spanish localization and copy improvements

This fork adds and polishes Spanish text across user-facing areas of the application.

Added or improved visible strings include:

- More options / Más opciones
- Back / Atrás
- Authorized / Autorizado
- Checking service status… / Comprobando el estado del servicio…
- Home service status text
- Application Management text
- TV labels
- Wear labels
- Shell / Termux / ADB tutorial visible copy

English copy was also corrected where necessary, including:

- Apps that have requested or declared Nightzuku will show here.
- * requires Nightzuku to run as root

## 2. Home service status improvements

The Home service status behavior was improved to reduce stale or misleading states.

Improvements include:

- lifecycle-aware periodic service status refresh;
- cancellation-aware refresh loop;
- keeping checkServerStatus() active in onResume();
- clearer loading state while the service status is unknown;
- avoiding false “not running” display during loading;
- avoiding the error icon during loading;
- adding Checking service status… / Comprobando el estado del servicio…

Related commits:

- 04e86425 Make Home service refresh loop cancellation-aware
- 0ca6f5a5 Make Home refresh lifecycle-aware
- f8b1c835 Polish Home loading service status
- 47d9cf95 Polish diagnostics and TV authorized label

## 3. Accessibility improvements

Icon-only actions were reviewed and improved where appropriate.

Notable change:

- Home more-options icon now has a proper contentDescription.

Related commit:

- 9ad6129e Polish Home accessibility and TV loading state

## 4. Android TV UX improvements

Android TV received several usability and label improvements.

Improvements include:

- TV Application Management no longer shows an empty state while still loading.
- TV Application Management shows a loading indicator during loading.
- TV Start button no longer uses the same icon as Refresh.
- TV Start uses R.drawable.ic_server_start_24dp.
- TV Application Management back button uses R.string.action_back instead of android.R.string.cancel.
- TV Shell Tutorial back button also uses R.string.action_back.
- TV Application Management uses R.string.app_management_item_authorized instead of reusing a module-related label.

Related commits:

- 76800b88 Fix MF-1 MF-2 TV loading and English grammar
- 3ac8e968 Fix SF-1 SF-2 SF-3 TV label and cleanup
- 9ad6129e Polish Home accessibility and TV loading state
- 47d9cf95 Polish diagnostics and TV authorized label
- 6348838f Fix TV sidebar Start icon and App Management back label
- 92dcc937 Fix TV Shell Tutorial back button label

## 5. Wear OS UX improvements

Wear OS received multiple UX improvements.

Improvements include:

- Wear Home no longer shows Start actions while service status is still loading.
- Wear Home keeps the clearer loading status text.
- Wear Application Management no longer shows an empty state before data has loaded.
- Wear Application Management receives an explicit loading flag.
- Wear Home, Wear Modules, and Wear Settings restored scroll transformation behavior from upstream r49.
- Wear Home main title icon now uses R.drawable.ic_system_icon.
- Icons.Rounded.PlayArrow is kept only where it is semantically correct: inside the Start button.
- WearScreenTitle now supports drawable icons through a DrawableRes overload.

Related commits:

- e0ede118 Hide Wear start actions while status is loading
- a63ff55f Fix Wear App Management empty state during load and EN grammar
- cbe4a7ee Restore Wear scroll transformations from r49

## 6. Application Management improvements

Application Management was improved across Phone, TV, and Wear.

Improvements include:

- safer UI mapping around nullable applicationInfo;
- avoiding unsafe applicationInfo!! in relevant UI paths;
- preserving permission and authorization behavior unchanged;
- better search query state placement;
- clearer TV authorized label;
- better loading and empty-state behavior;
- improved English and Spanish text.

Related commits:

- cb5b587c Fix M2 M3 M4 M6 application management polish
- 47d9cf95 Polish diagnostics and TV authorized label
- a63ff55f Fix Wear App Management empty state during load and EN grammar

## 7. Diagnostics cleanup

Diagnostics output was cleaned up to avoid redundant information.

Improvement:

- removed a duplicated status line where Status and Service showed the same service status value.

Related commit:

- 47d9cf95 Polish diagnostics and TV authorized label

## 8. Selective upstream r49 integration

Upstream r49 was reviewed, but it was not merged directly because a full merge could remove or conflict with this fork changes.

Risky areas from the full r49 comparison included:

- server;
- shell;
- rish;
- AndroidManifest.xml;
- Gradle files;
- localization files;
- documentation and helper files.

Instead, only safe parts were integrated manually:

- cbe4a7ee Restore Wear scroll transformations from r49
- 3436036f Fix ADB pairing peer info from r49

The ADB pairing fix was limited to manager/src/main/java/moe/shizuku/manager/adb/AdbPairingClient.kt.

It corrected:

- data: ByteArray -> dataBytes: ByteArray
- ADB_DEVICE_GUID(0.toByte()) -> ADB_DEVICE_GUID(1.toByte())

No manifest, service, startup, shell, rish, permission, or authorization changes were included.

## 9. Areas intentionally avoided

To reduce risk, this fork intentionally avoided broad modifications to sensitive areas:

- server behavior;
- binder / IPC internals;
- authorization behavior;
- permission granting or revoking logic;
- package identity;
- rish runtime behavior;
- shell runtime behavior;
- automatic startup;
- foreground service behavior;
- broad Gradle changes;
- broad Manifest changes;
- unrelated refactors;
- unrelated formatting-only rewrites.

## 10. Summary

In simple terms, this fork improves Spanish support, visible English and Spanish copy, Home service status clarity, loading-state behavior, Android TV UX, Wear OS UX, Application Management consistency, accessibility for icon actions, selective compatibility with upstream r49, and ADB pairing peer info correctness.

The project remains a personal adaptation of the original work, focused on usability and controlled improvements rather than rewriting the original architecture.

---

# Mejoras del fork

## Español

Este documento resume las mejoras añadidas en este fork/adaptación personal de Nightzuku/Shizuku.

Este fork no reclama autoría sobre el proyecto original. El objetivo es adaptar el software a necesidades personales, mejorar la experiencia de uso cuando sea posible y agregar soporte en español para usuarios que puedan tener dificultades usando la aplicación en inglés.

Rama actual: feat/application-management-search-ui

HEAD reciente confirmado: 3436036f Fix ADB pairing peer info from r49

## 1. Traducción al español y mejoras de textos

Este fork agrega y pule textos en español en varias áreas visibles de la aplicación.

Textos agregados o mejorados:

- More options / Más opciones
- Back / Atrás
- Authorized / Autorizado
- Checking service status… / Comprobando el estado del servicio…
- textos de estado en Home;
- textos de Application Management;
- etiquetas de TV;
- etiquetas de Wear;
- textos visibles de Shell / Termux / tutorial ADB.

También se corrigieron textos en inglés cuando era necesario, incluyendo:

- Apps that have requested or declared Nightzuku will show here.
- * requires Nightzuku to run as root

## 2. Mejoras en el estado del servicio en Home

Se mejoró el estado del servicio en Home para reducir estados obsoletos o confusos.

Mejoras incluidas:

- actualización periódica respetando el ciclo de vida;
- loop de actualización cancelable;
- mantenimiento de checkServerStatus() en onResume();
- estado de carga más claro mientras todavía no se conoce el estado del servicio;
- eliminación del falso “not running” durante carga;
- eliminación del icono de error durante carga;
- agregado de Checking service status… / Comprobando el estado del servicio…

Commits relacionados:

- 04e86425 Make Home service refresh loop cancellation-aware
- 0ca6f5a5 Make Home refresh lifecycle-aware
- f8b1c835 Polish Home loading service status
- 47d9cf95 Polish diagnostics and TV authorized label

## 3. Mejoras de accesibilidad

Se revisaron acciones con iconos y se mejoraron cuando correspondía.

Cambio destacado:

- el botón de más opciones de Home ahora tiene contentDescription.

Commit relacionado:

- 9ad6129e Polish Home accessibility and TV loading state

## 4. Mejoras de UX en Android TV

Android TV recibió varias mejoras de usabilidad y etiquetas.

Mejoras incluidas:

- TV Application Management ya no muestra estado vacío mientras carga.
- TV Application Management muestra indicador de carga.
- El botón Start de TV ya no usa el mismo icono que Refresh.
- El botón Start usa R.drawable.ic_server_start_24dp.
- El botón Back de TV Application Management usa R.string.action_back en lugar de android.R.string.cancel.
- El botón Back de TV Shell Tutorial también usa R.string.action_back.
- TV Application Management usa R.string.app_management_item_authorized en lugar de reutilizar una etiqueta de módulos.

Commits relacionados:

- 76800b88 Fix MF-1 MF-2 TV loading and English grammar
- 3ac8e968 Fix SF-1 SF-2 SF-3 TV label and cleanup
- 9ad6129e Polish Home accessibility and TV loading state
- 47d9cf95 Polish diagnostics and TV authorized label
- 6348838f Fix TV sidebar Start icon and App Management back label
- 92dcc937 Fix TV Shell Tutorial back button label

## 5. Mejoras de UX en Wear OS

Wear OS recibió varias mejoras de experiencia de usuario.

Mejoras incluidas:

- Wear Home ya no muestra acciones Start mientras el estado del servicio está cargando.
- Wear Home conserva el texto claro de carga.
- Wear Application Management ya no muestra estado vacío antes de que carguen los datos.
- Wear Application Management recibe un flag explícito de carga.
- Wear Home, Wear Modules y Wear Settings restauran transformaciones visuales de scroll desde upstream r49.
- El título principal de Wear Home usa R.drawable.ic_system_icon.
- Icons.Rounded.PlayArrow se mantiene solo donde corresponde: dentro del botón Start.
- WearScreenTitle ahora soporta iconos drawable mediante un overload con DrawableRes.

Commits relacionados:

- e0ede118 Hide Wear start actions while status is loading
- a63ff55f Fix Wear App Management empty state during load and EN grammar
- cbe4a7ee Restore Wear scroll transformations from r49

## 6. Mejoras en Application Management

Las pantallas de gestión de aplicaciones fueron mejoradas en Phone, TV y Wear.

Mejoras incluidas:

- mapeo de UI más seguro alrededor de applicationInfo nullable;
- eliminación de usos inseguros de applicationInfo!! en rutas relevantes de UI;
- conservación intacta del comportamiento de permisos y autorización;
- mejor ubicación del estado de búsqueda;
- etiqueta de autorizado más clara en TV;
- mejor comportamiento de carga y estado vacío;
- textos en inglés y español más claros.

Commits relacionados:

- cb5b587c Fix M2 M3 M4 M6 application management polish
- 47d9cf95 Polish diagnostics and TV authorized label
- a63ff55f Fix Wear App Management empty state during load and EN grammar

## 7. Limpieza de diagnósticos

Se limpió la salida de diagnósticos para evitar información redundante.

Mejora:

- se eliminó una línea duplicada donde Status y Service mostraban el mismo valor.

Commit relacionado:

- 47d9cf95 Polish diagnostics and TV authorized label

## 8. Integración selectiva de upstream r49

Se revisó upstream r49, pero no se hizo merge directo porque podía eliminar o entrar en conflicto con cambios de este fork.

Áreas riesgosas de la comparación completa con r49:

- server;
- shell;
- rish;
- AndroidManifest.xml;
- archivos Gradle;
- archivos de localización;
- documentación y archivos auxiliares.

En lugar de integrar todo, solo se aplicaron manualmente partes seguras:

- cbe4a7ee Restore Wear scroll transformations from r49
- 3436036f Fix ADB pairing peer info from r49

El fix de ADB pairing se limitó a manager/src/main/java/moe/shizuku/manager/adb/AdbPairingClient.kt.

Corrigió:

- data: ByteArray -> dataBytes: ByteArray
- ADB_DEVICE_GUID(0.toByte()) -> ADB_DEVICE_GUID(1.toByte())

No se incluyeron cambios de manifest, service, startup, shell, rish, permisos ni autorización.

## 9. Áreas evitadas intencionalmente

Para reducir riesgos, este fork evitó modificaciones amplias en áreas sensibles:

- comportamiento del servidor;
- binder / IPC;
- autorización;
- otorgamiento o revocación de permisos;
- identidad del paquete;
- comportamiento de rish;
- comportamiento del shell runtime;
- arranque automático;
- foreground service;
- cambios amplios de Gradle;
- cambios amplios de Manifest;
- refactors no relacionados;
- reformatos generales sin necesidad.

## 10. Resumen

En términos simples, este fork mejora soporte en español, textos visibles en inglés y español, claridad del estado del servicio en Home, comportamiento de estados de carga, UX de Android TV, UX de Wear OS, consistencia de Application Management, accesibilidad en acciones con iconos, integración selectiva con upstream r49 y corrección del peer info de ADB pairing.

El proyecto sigue siendo una adaptación personal del trabajo original, con cambios enfocados en usabilidad y mejoras controladas, no en reescribir la arquitectura original.

## Audited sensitive historical changes / Cambios sensibles históricos auditados

## English

Some historical changes in this fork affect sensitive areas compared with upstream. They were reviewed before preparing this release candidate.

These changes are not broad rewrites, but they are documented because they touch important parts of the application behavior.

### Server startup wait timeout

The server code includes a timeout while waiting for system services. This avoids waiting indefinitely if a required system service never becomes available.

File:

- server/src/main/java/rikka/shizuku/server/ShizukuService.java

Decision:

- keep;
- document as intentional;
- avoid further server changes unless strictly necessary.

### Rish package targeting

The rish loader targets the package ID used by this fork:

- kerneldroid.nightzuku

This is consistent with the current applicationId of the fork.

Files:

- shell/src/main/java/rikka/shizuku/shell/ShizukuShellLoader.java
- manager/src/main/assets/rish

Decision:

- keep;
- do not change unless package identity changes.

### ADB loopback pairing behavior

ADB pairing related code uses 127.0.0.1 and port-based discovery. This keeps the ADB pairing flow internally consistent across AdbMdns, AdbPairingService, and BootCompleteReceiver.

Files:

- manager/src/main/java/moe/shizuku/manager/adb/AdbMdns.kt
- manager/src/main/java/moe/shizuku/manager/adb/AdbPairingService.kt
- manager/src/main/java/moe/shizuku/manager/receiver/BootCompleteReceiver.kt

Decision:

- keep for now;
- validate with real Wireless Debugging / ADB pairing tests before a final public release.

### Manifest-sensitive components

Manifest entries related to ADB pairing, binder requests, foreground service type, and permission-protected activities were reviewed.

The RequestPermissionActivity remains protected by a system permission:

- android.permission.INTERACT_ACROSS_USERS_FULL

The REQUEST_BINDER receiver remains exported because it is part of the rish/binder delivery flow.

File:

- manager/src/main/AndroidManifest.xml

Decision:

- keep;
- do not modify casually;
- re-audit before changing startup, rish, shell, binder, or ADB behavior.

### Gradle and dependency versions

The fork uses dependency and Gradle versions that currently build successfully in the local environment.

Files:

- build.gradle
- manager/build.gradle
- gradle/wrapper/gradle-wrapper.properties

Decision:

- keep for this release candidate;
- treat dependency upgrades as a separate future task.

## Español

Algunos cambios históricos de este fork afectan áreas sensibles comparadas con upstream. Fueron revisados antes de preparar este release candidate.

Estos cambios no son reescrituras amplias, pero se documentan porque tocan partes importantes del comportamiento de la aplicación.

### Timeout al esperar servicios del sistema

El código del servidor incluye un timeout al esperar servicios del sistema. Esto evita esperas indefinidas si un servicio requerido nunca queda disponible.

Archivo:

- server/src/main/java/rikka/shizuku/server/ShizukuService.java

Decisión:

- mantener;
- documentar como intencional;
- evitar más cambios en server salvo necesidad estricta.

### Target de paquete para rish

El loader de rish apunta al package ID usado por este fork:

- kerneldroid.nightzuku

Esto es coherente con el applicationId actual del fork.

Archivos:

- shell/src/main/java/rikka/shizuku/shell/ShizukuShellLoader.java
- manager/src/main/assets/rish

Decisión:

- mantener;
- no cambiar salvo que cambie la identidad del paquete.

### Comportamiento ADB pairing por loopback

El código relacionado con ADB pairing usa 127.0.0.1 y detección basada en puerto. Esto mantiene coherente el flujo entre AdbMdns, AdbPairingService y BootCompleteReceiver.

Archivos:

- manager/src/main/java/moe/shizuku/manager/adb/AdbMdns.kt
- manager/src/main/java/moe/shizuku/manager/adb/AdbPairingService.kt
- manager/src/main/java/moe/shizuku/manager/receiver/BootCompleteReceiver.kt

Decisión:

- mantener por ahora;
- validar con pruebas reales de Wireless Debugging / ADB pairing antes de un release público final.

### Componentes sensibles del Manifest

Se revisaron entradas del Manifest relacionadas con ADB pairing, binder requests, foreground service type y actividades protegidas por permisos.

RequestPermissionActivity sigue protegida por un permiso de sistema:

- android.permission.INTERACT_ACROSS_USERS_FULL

El receiver REQUEST_BINDER sigue exportado porque forma parte del flujo de entrega binder/rish.

Archivo:

- manager/src/main/AndroidManifest.xml

Decisión:

- mantener;
- no modificar casualmente;
- volver a auditar antes de cambiar startup, rish, shell, binder o ADB.

### Versiones Gradle y dependencias

El fork usa versiones de dependencias y Gradle que actualmente compilan correctamente en el entorno local.

Archivos:

- build.gradle
- manager/build.gradle
- gradle/wrapper/gradle-wrapper.properties

Decisión:

- mantener para este release candidate;
- tratar actualizaciones de dependencias como tarea futura separada.
