# Fork Changelog / Registro de cambios del fork

## English

This changelog summarizes the user-facing improvements added in this personal fork/adaptation of Nightzuku/Shizuku.

This is not the original project. It is a personal adaptation focused on Spanish language support, usability improvements, and small safe compatibility fixes.

## Current highlights

- Added and polished Spanish language support.
- Improved Home service status feedback.
- Added clearer loading messages while the service status is being checked.
- Improved Android TV navigation labels, icons, and loading states.
- Improved Wear OS loading behavior and scroll visual transformations.
- Improved Application Management stability and empty/loading state handling.
- Added accessibility descriptions for icon-only actions.
- Integrated selected safe improvements from upstream r49.
- Fixed ADB pairing peer info handling in AdbPairingClient.kt.
- Documented the fork clearly as a personal adaptation of the original project.

## User-facing improvements

### Spanish support

Spanish strings were added or improved across several visible parts of the application, including Home, Application Management, TV, Wear, Shell, Termux, and ADB-related screens.

Examples:

- More options / Más opciones
- Back / Atrás
- Authorized / Autorizado
- Checking service status… / Comprobando el estado del servicio…

### Home

The Home screen now handles service status more clearly. While the app is checking whether the service is running, it shows a loading status instead of briefly showing a misleading not-running state.

The service status refresh also respects the screen lifecycle, reducing unnecessary background work when the screen is not active.

### Android TV

Android TV received several usability improvements:

- clearer Back labels;
- better Start icon;
- loading indicator in Application Management;
- no premature empty state while apps are still loading;
- clearer Authorized label.

### Wear OS

Wear OS received several improvements:

- Start actions are hidden while service status is still loading;
- Application Management no longer shows an empty state before loading finishes;
- scroll transformations were restored from upstream r49;
- the Wear Home title icon was improved.

### Application Management

Application Management was improved across Phone, TV, and Wear:

- safer handling of nullable application information;
- better loading and empty state behavior;
- clearer labels;
- improved English and Spanish text;
- permission and authorization behavior preserved unchanged.

### ADB pairing

A small safe fix from upstream r49 was applied to AdbPairingClient.kt. It corrects peer info data handling and the ADB device GUID type value without touching Manifest, services, startup, permissions, shell, rish, or authorization logic.

## Safety notes

This fork intentionally avoids broad changes to sensitive areas such as:

- server behavior;
- binder / IPC internals;
- authorization logic;
- permission granting or revoking;
- package identity;
- rish;
- shell runtime;
- automatic startup;
- foreground service behavior;
- broad Manifest changes;
- broad Gradle changes.

The goal is to improve usability while keeping the original architecture and core behavior as stable as possible.

---

# Registro de cambios del fork

## Español

Este registro resume las mejoras visibles para el usuario añadidas en este fork/adaptación personal de Nightzuku/Shizuku.

Este no es el proyecto original. Es una adaptación personal enfocada en soporte en español, mejoras de uso y pequeños ajustes seguros de compatibilidad.

## Puntos principales

- Se agregó y pulió soporte en español.
- Se mejoró la información del estado del servicio en Home.
- Se agregaron mensajes de carga más claros mientras se comprueba el estado del servicio.
- Se mejoraron etiquetas, iconos y estados de carga en Android TV.
- Se mejoró el comportamiento de carga y las transformaciones visuales de scroll en Wear OS.
- Se mejoró la estabilidad visual y el manejo de carga/estado vacío en Application Management.
- Se agregaron descripciones de accesibilidad para acciones solo con iconos.
- Se integraron mejoras seguras seleccionadas desde upstream r49.
- Se corrigió el manejo de peer info en ADB pairing dentro de AdbPairingClient.kt.
- Se documentó claramente el repositorio como una adaptación personal del proyecto original.

## Mejoras visibles para usuarios

### Soporte en español

Se agregaron o mejoraron textos en español en varias partes visibles de la aplicación, incluyendo Home, Application Management, TV, Wear, Shell, Termux y pantallas relacionadas con ADB.

Ejemplos:

- More options / Más opciones
- Back / Atrás
- Authorized / Autorizado
- Checking service status… / Comprobando el estado del servicio…

### Home

La pantalla Home ahora muestra el estado del servicio de forma más clara. Mientras la app comprueba si el servicio está activo, muestra un estado de carga en lugar de mostrar temporalmente un estado engañoso de servicio detenido.

La actualización del estado del servicio también respeta el ciclo de vida de la pantalla, reduciendo trabajo innecesario cuando la pantalla no está activa.

### Android TV

Android TV recibió varias mejoras de uso:

- etiquetas Back/Atrás más claras;
- mejor icono para Start;
- indicador de carga en Application Management;
- se evita mostrar estado vacío antes de terminar la carga;
- etiqueta Authorized/Autorizado más clara.

### Wear OS

Wear OS recibió varias mejoras:

- las acciones Start se ocultan mientras el estado del servicio está cargando;
- Application Management ya no muestra estado vacío antes de terminar la carga;
- se restauraron transformaciones visuales de scroll desde upstream r49;
- se mejoró el icono del título principal en Wear Home.

### Application Management

Application Management fue mejorado en Phone, TV y Wear:

- manejo más seguro de información de aplicación nullable;
- mejor comportamiento de carga y estado vacío;
- etiquetas más claras;
- textos en inglés y español más correctos;
- comportamiento de permisos y autorización conservado sin cambios.

### ADB pairing

Se aplicó un pequeño fix seguro de upstream r49 en AdbPairingClient.kt. Corrige el manejo de datos de peer info y el valor del tipo ADB device GUID sin tocar Manifest, services, startup, permisos, shell, rish ni lógica de autorización.

## Notas de seguridad

Este fork evita intencionalmente cambios amplios en áreas sensibles como:

- comportamiento del servidor;
- binder / IPC;
- lógica de autorización;
- otorgamiento o revocación de permisos;
- identidad del paquete;
- rish;
- shell runtime;
- arranque automático;
- foreground service;
- cambios amplios de Manifest;
- cambios amplios de Gradle.

El objetivo es mejorar la experiencia de uso manteniendo la arquitectura y el comportamiento central del proyecto original lo más estables posible.
