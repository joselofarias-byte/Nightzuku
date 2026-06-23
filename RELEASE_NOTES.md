# Release Notes / Notas de release

## fork-2026-06-23-rc1

## English

This release candidate represents a stable checkpoint of the Nightzuku personal fork/adaptation.

This is not the original Nightzuku/Shizuku project. It is a personal adaptation focused on Spanish language support, usability improvements, Android TV and Wear OS polish, clearer loading states, and selected safe compatibility fixes.

### Highlights

- Added and polished Spanish support.
- Improved Home service status feedback.
- Added clearer loading state while checking the service.
- Improved Android TV labels, navigation, loading indicators, and icons.
- Improved Wear OS loading behavior and scroll visual transformations.
- Improved Application Management behavior across Phone, TV, and Wear.
- Added accessibility descriptions for icon-only actions.
- Added documentation explaining the fork/adaptation clearly.
- Added fork changelog and detailed improvements documentation.
- Integrated selected safe changes from upstream r49.
- Fixed ADB pairing peer info handling in AdbPairingClient.kt.
- Audited sensitive historical changes before release preparation.

### Validation

- Build: BUILD SUCCESSFUL
- Gradle task: :manager:assembleDebug
- git diff --check HEAD: clean
- git status --short: clean
- Branch: feat/application-management-search-ui

### Notes

The fork intentionally avoids broad changes to server, shell, rish, permissions, authorization, startup, Manifest, and Gradle unless explicitly reviewed.

ADB pairing and Wireless Debugging should still be validated on real devices before considering this a final public release.

## Español

Este release candidate representa un punto estable del fork/adaptación personal de Nightzuku.

Este no es el proyecto original Nightzuku/Shizuku. Es una adaptación personal enfocada en soporte en español, mejoras de uso, pulido para Android TV y Wear OS, estados de carga más claros y algunos ajustes seguros de compatibilidad.

### Puntos principales

- Se agregó y pulió soporte en español.
- Se mejoró la información del estado del servicio en Home.
- Se agregó un estado de carga más claro mientras se comprueba el servicio.
- Se mejoraron etiquetas, navegación, indicadores de carga e iconos en Android TV.
- Se mejoró el comportamiento de carga y las transformaciones visuales de scroll en Wear OS.
- Se mejoró Application Management en Phone, TV y Wear.
- Se agregaron descripciones de accesibilidad para acciones solo con iconos.
- Se agregó documentación clara explicando el fork/adaptación.
- Se agregó changelog del fork y documentación detallada de mejoras.
- Se integraron cambios seguros seleccionados desde upstream r49.
- Se corrigió el manejo de peer info de ADB pairing en AdbPairingClient.kt.
- Se auditaron cambios sensibles históricos antes de preparar el release.

### Validación

- Build: BUILD SUCCESSFUL
- Tarea Gradle: :manager:assembleDebug
- git diff --check HEAD: limpio
- git status --short: limpio
- Rama: feat/application-management-search-ui

### Notas

El fork evita cambios amplios en server, shell, rish, permisos, autorización, startup, Manifest y Gradle salvo que sean revisados explícitamente.

ADB pairing y Wireless Debugging todavía deberían validarse en dispositivos reales antes de considerar este punto como release público final.
