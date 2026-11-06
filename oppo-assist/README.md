# JOLUFASOFT OPPO ASSIST

Asistencia remota para un OPPO con ColorOS, usada por un cuidador desde un HONOR 200 con Termux. La persona del OPPO tiene que autorizarla en persona.

Nightzuku no es el canal hacia el OPPO. Es un servidor local de binder, en el mismo teléfono donde está instalado.

## Qué hay

- `agent/`: aplicación Android en Kotlin (`com.joselofarias.oppoassist`).
- `core/`: reglas que se pueden probar sin teléfono (Tailscale, SMS, bloqueo de operaciones).
- `termux/`: cliente `jolufa` para el HONOR.
- `docs/`: arquitectura, auditoría, límites y la configuración presencial.

## Documentos

- [Arquitectura](docs/arquitectura.md)
- [Auditoría de Nightzuku](docs/auditoria-nightzuku.md)
- [Matriz de limitaciones](docs/limitaciones.md)
- [Configuración presencial](docs/configuracion-presencial.md)
- [Qué se verificó en esta rama](docs/verificacion.md)
- [Servidor Oracle](docs/oracle.md)

La licencia del repositorio es Apache 2.0. Este módulo también.
