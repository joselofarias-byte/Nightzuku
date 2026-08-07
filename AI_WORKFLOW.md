# Flujo obligatorio para agentes de IA

Esta política se aplica a Codex, Claude, ChatGPT, Gemini, Meta Muse Code, Copilot, Cursor y cualquier otro agente o LLM que analice o modifique este repositorio.

## Cero memoria requerida

Todo agente debe comenzar leyendo [`START-HERE.md`](START-HERE.md) y ejecutando:

```bash
bash tools/system-docs.sh summary
bash tools/system-docs.sh doctor
```

El sistema debe ser operable sin recordar conversaciones anteriores. Estado, historial, herramientas, órdenes y próximos comandos se obtienen desde el repositorio y la evidencia persistente.

## Elegir el modo de trabajo

### Trabajo normal: un solo agente

Usar el **front door canónico**:

```bash
bash tools/work.sh start \
  --agent <nombre-del-agente> \
  --objective "<objetivo concreto>"
```

Agregar `--structural` cuando el trabajo cambie arquitectura, módulos, dependencias principales, IPC, servicios, flujos centrales o límites entre componentes.

`tools/work.sh` delega al motor estable `tools/llm-workflow.sh` y agrega automáticamente snapshots del sistema, historial y publicación a Obsidian cuando esté disponible.

### Trabajo complejo: implementador + revisor independiente

No abrir primero una orden normal. Usar:

```bash
bash tools/swarm.sh start --objective "<objetivo concreto>"
```

Agregar `--structural` cuando corresponda. El swarm crea **una sola orden maestra y un solo respaldo**, y usa worktrees separados para implementación y revisión.

El diseño está en [`SWARM_WORKFLOW.md`](SWARM_WORKFLOW.md). `tools/swarm.sh` es el front door autodocumentado; `tools/swarm-workflow.sh` es el motor interno.

Si el entorno contiene `SWARM_ROLE` y `SWARM_ROLE_PROMPT`, el agente ya pertenece a una orden maestra: debe leer `SWARM_ROLE_PROMPT` y **no iniciar otra orden**.

Usar swarm sólo cuando una revisión independiente justifique el consumo extra de cuota/contexto.

### Meta Muse Code

Muse Code es un backend opcional documentado en [`MUSE.md`](MUSE.md). No es requisito y su instalador remoto no se ejecuta sin auditoría/compatibilidad demostrada.

## Capas canónicas y no duplicación

- `tools/llm-workflow.sh`: motor único de orden, backup, evidencia, tests y cierre.
- `tools/work.sh`: front door normal con autodocumentación.
- `tools/swarm-workflow.sh`: motor de worktrees/handoff de dos roles.
- `tools/swarm.sh`: front door swarm con autodocumentación.
- `tools/system-docs.sh`: dashboard, snapshots, historial, doctor y publicación a Obsidian.
- CodeGraph: índice principal de código.
- Graphify: sólo para trabajo estructural cuando aporte una decisión.
- Obsidian: espejo humano; no fuente de verdad.

No agregar otro sistema paralelo para backups, órdenes, logs, constitución de agentes, handoffs o índice obligatorio sin documentar primero el reemplazo del sistema canónico.

Decisiones ya tomadas: TBM queda histórico; SwarmForge aportó conceptos pero no se vende su runtime; Loop Engineering queda como referencia de patrones y no segundo runtime obligatorio; Repowise sigue como referencia/piloto; Graphify no se ejecuta por defecto.

## Investigación

Cuando CodeGraph esté disponible, usarlo antes de recorrer masivamente archivos. Registrar hallazgos:

```bash
bash tools/work.sh note "Símbolos consultados, callers, callees, flujo e impacto encontrado."
```

Los índices orientan la investigación, pero no sustituyen lectura de código, diff, pruebas ni compilación.

## Pruebas y compilaciones

```bash
bash tools/work.sh run -- <comando> <argumentos>
```

Para expresiones complejas:

```bash
bash tools/work.sh run -- bash -lc '<comando complejo>'
```

## Checkpoints

```bash
bash tools/work.sh checkpoint "descripción"
```

El wrapper agrega además una fotografía del sistema y actualiza la vista humana cuando es posible.

## Commits y publicación

- No crear commits, hacer push, abrir/cerrar PR ni fusionar sin autorización expresa del usuario.
- No usar `--no-verify`.
- No desactivar `core.hooksPath` para eludir guardas.
- Todo commit autorizado requiere orden activa; los hooks agregan `Work-Order` y `Agent`.
- El handoff del swarm no necesita commit: transfiere staged + unstaged + untracked con hashes.

## Cierre obligatorio

Trabajo normal:

```bash
bash tools/work.sh finish "resumen del resultado"
```

Swarm:

```bash
bash tools/swarm.sh finish "resumen del resultado"
```

Si se cancela, usar `abort` en el mismo front door. El cierre registra automáticamente el historial persistente y publica `System Status`, `START HERE` y `Work History` en Obsidian cuando el vault esté disponible.

Nunca dejar una orden activa sin cerrar o abortar.

## Recuperar contexto sin recordar nada

```bash
bash tools/system-docs.sh summary
bash tools/system-docs.sh history
bash tools/system-docs.sh snapshot
```

Por defecto, órdenes, respaldos e historial viven fuera del checkout bajo:

```text
$HOME/.local/state/llm-work/<repositorio>/
```

Los worktrees del swarm, `.codegraph/` y `graphify-out/` son regenerables y no son fuentes de verdad.
