# Meta Muse Code

Muse Code debe cumplir la política canónica de [`AI_WORKFLOW.md`](AI_WORKFLOW.md) y todas las reglas de [`AGENTS.md`](AGENTS.md).

## Inicio obligatorio

No iniciar Muse directamente. Usar el adaptador estándar:

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

El adaptador abre antes una orden recuperable mediante `tools/llm-workflow.sh`, crea respaldo Git, activa guardas y registra el agente como `muse-code`.

## Skills de Muse

Al comenzar la sesión:

1. usar `/plan` antes de editar;
2. usar `/grill` antes de cambios estructurales o de riesgo alto;
3. usar `/goal` únicamente dentro del objetivo y alcance ya autorizados.

Los subagentes persistentes forman parte de la misma orden de trabajo. No crean órdenes, commits o respaldos independientes.

## Evidencia

- Registrar hallazgos con `bash tools/llm-workflow.sh note "..."`.
- Ejecutar pruebas y compilaciones con `bash tools/llm-workflow.sh run -- ...`.
- Crear checkpoints antes de operaciones importantes.
- El registro local de eventos de Muse es evidencia complementaria; no sustituye bundles, patches, manifiestos, pruebas ni Git.

## Cierre

El lanzador deja la orden activa cuando termina el proceso de Muse. Revisar el estado y cerrarla expresamente:

```bash
bash tools/llm-workflow.sh status
bash tools/llm-workflow.sh finish "<resultado>"
```

Si el trabajo quedó bloqueado o cancelado:

```bash
bash tools/llm-workflow.sh abort "<motivo>"
```

## Autorización

Muse Code no puede crear commits, hacer push, abrir o cerrar PR, fusionar ni alterar las guardas sin autorización expresa del usuario. Está prohibido usar `--no-verify`.

## Instalación

No ejecutar `curl | bash` directamente. Primero auditar el instalador oficial sin ejecutarlo:

```bash
bash tools/audit-muse-installer.sh
```

La instalación sólo se evalúa después de revisar el reporte y demostrar soporte para la arquitectura real del entorno.
