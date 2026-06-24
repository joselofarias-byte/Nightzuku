# Nightzuku Fork

Personal adaptation of Nightzuku / Shizuku focused on Spanish support, usability improvements, Android TV and Wear OS polish, and selected safe compatibility fixes.

This repository is not the original Nightzuku/Shizuku project. It is a personal fork/adaptation maintained for practical use, accessibility, localization, and quality-of-life improvements.

## Español

Este repositorio es una adaptación personal basada en Nightzuku / Shizuku.

No soy el programador original del proyecto. Mi trabajo se centra en adaptar la aplicación a mis necesidades, agregar soporte en español para usuarios hispanohablantes, mejorar detalles de uso en Android TV y Wear OS, corregir estados de carga confusos y aplicar algunos fixes seguros revisados manualmente.

El objetivo no es reemplazar el proyecto original, sino ofrecer una versión adaptada, documentada y más accesible para quienes prefieren usar la aplicación en español.

## Main changes / Cambios principales

* Spanish localization added and refined.
* Home service status loading improved.
* Android TV labels, icons, navigation, and loading states improved.
* Wear OS loading behavior and scroll transformations improved.
* Application Management improved across Phone, TV, and Wear.
* Accessibility descriptions added for icon-only actions.
* Selected safe fixes from upstream r49 integrated manually.
* ADB pairing peer info fixed.
* Release build optimized with R8 and resource shrinking.
* Debug, info, and verbose logs stripped from release builds.
* Signed release APK prepared for GitHub releases.

## Documentation / Documentación

* [Personal Adaptation Notice / Aviso de adaptación personal](docs/ADAPTATION_NOTICE.md)
* [Fork Improvements / Mejoras del fork](docs/FORK_IMPROVEMENTS.md)
* [Fork Changelog / Registro de cambios del fork](CHANGELOG_FORK.md)
* [Release Notes / Notas de release](RELEASE_NOTES.md)
* [Final Release Notes v1.0.0](RELEASE_FINAL_v1.0.0.md)

## Release

Latest stable fork release:

* Tag: `v1.0.0-nightzuku-fork`
* APK type: signed release
* Build optimization: R8 + resource shrinking
* APK size: approximately 3.6 MB
* ADB pairing and Wireless Debugging validated on the maintainer device

## Important notice

This fork intentionally avoids broad changes to sensitive areas such as server behavior, binder / IPC internals, authorization logic, permission granting or revoking, package identity, rish, shell runtime, automatic startup, foreground service behavior, Manifest changes, and Gradle changes unless they are reviewed explicitly.

Some historical sensitive changes already present in the fork were audited and documented before preparing the stable release.

## Attribution

This project is based on the original Nightzuku / Shizuku work. All original rights, licenses, and credits belong to their respective authors.

This fork only represents my own adaptation, translation work, documentation, release preparation, and selected improvements.
