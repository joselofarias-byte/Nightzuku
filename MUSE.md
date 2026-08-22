# Meta Muse Code

Muse Code debe cumplir `START-HERE.md`, [`AI_WORKFLOW.md`](AI_WORKFLOW.md) y [`AGENTS.md`](AGENTS.md).

## Estado actual

Muse Code es **opcional e inactivo** en este entorno. La auditoría real sobre Linux `aarch64` no encontró evidencia suficiente de soporte ARM64 en el instalador oficial, por lo que la instalación permanece bloqueada. Esto no afecta el flujo normal con otros agentes.

## Antes de usarlo, si alguna vez queda habilitado

```bash
bash tools/system-docs.sh summary
bash tools/system-docs.sh doctor
```

No iniciar Muse directamente. Usar el adaptador:

```bash
bash tools/muse-workflow.sh \
  --objective "<objetivo concreto>"
```

Para arquitectura, módulos, IPC, servicios, dependencias principales o flujos centrales:

```bash
bash tools/muse-workflow.sh \
  --objective "<objetivo concreto>" \
  --structural
```

`muse-workflow.sh` es una excepción controlada: abre la orden mediante el motor base porque necesita lanzar el proceso de Muse en la misma operación. Una vez abierta la orden, usar los comandos autodocumentados de `tools/work.sh` para evidencia y cierre.

## Skills de Muse

Al comenzar la sesión:

1. usar `/plan` antes de editar;
2. usar `/grill` antes de cambios estructurales o de riesgo alto;
3. usar `/goal` únicamente dentro del objetivo y alcance autorizados.

Los subagentes persistentes forman parte de la misma orden. No crean órdenes, commits o respaldos independientes.

## Evidencia

```bash
bash tools/work.sh note "<hallazgo>"
bash tools/work.sh run -- <prueba/build>
bash tools/work.sh checkpoint "<etapa>"
```

El registro local de eventos de Muse es evidencia complementaria; no sustituye bundles, patches, manifiestos, pruebas ni Git.

## Cierre

El lanzador deja la orden activa cuando Muse termina. Cerrarla mediante el front door autodocumentado:

```bash
bash tools/work.sh status
bash tools/work.sh finish "<resultado>"
```

Si quedó bloqueada o cancelada:

```bash
bash tools/work.sh abort "<motivo>"
```

Así el trabajo queda además incorporado al historial persistente, snapshots y Obsidian.

## Autorización

Muse Code no puede crear commits, hacer push, abrir/cerrar PR, fusionar ni alterar guardas sin autorización expresa. Está prohibido usar `--no-verify`.

## Instalación

No ejecutar `curl | bash` directamente. Primero auditar el instalador oficial sin ejecutarlo:

```bash
bash tools/audit-muse-installer.sh
```

La instalación sólo puede reconsiderarse después de demostrar soporte para la arquitectura real del entorno y volver a revisar el instalador que se pretenda ejecutar.
