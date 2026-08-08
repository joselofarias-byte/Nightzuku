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
- **CodeGraph: backend PRIMARY de inteligencia de código.**
- `tools/code-intel.sh` / **Codebase Memory MCP: backend CANDIDATE/SHADOW**, no segundo índice obligatorio.
- Repomix / `tools/context-pack.sh`: transporte opcional de contexto seleccionado a otros LLM.
- Graphify: sólo para trabajo estructural cuando aporte una decisión.
- Obsidian: espejo humano; no fuente de verdad.

No agregar otro sistema paralelo para backups, órdenes, logs, constitución de agentes, handoffs o índice obligatorio sin documentar primero el reemplazo del sistema canónico.

Decisiones ya tomadas: TBM queda histórico; SwarmForge aportó conceptos pero no se vende su runtime; Loop Engineering queda como referencia de patrones y no segundo runtime obligatorio; Repowise sigue como referencia/piloto; **Codebase Memory se evalúa sin auto-watch, sin configuración global de agentes y sin reemplazar aún CodeGraph**; Repomix sólo transporta contexto y no reemplaza grafo, backups, handoffs ni documentación operativa; Graphify no se ejecuta por defecto.

## Investigación

### Selección de backend

Antes de una investigación amplia:

```bash
bash tools/code-intel.sh status
```

La regla normal es **CodeGraph primero**. Usar Codebase Memory únicamente cuando:

- se necesite búsqueda semántica local;
- Hybrid LSP pueda mejorar la resolución Kotlin/Java;
- se necesite `detect_changes`, Cypher o análisis de clones/relaciones semánticas;
- se esté ejecutando una comparación controlada entre backends.

Codebase Memory no se indexa automáticamente en todas las órdenes. Su índice se crea sólo dentro de una orden activa:

```bash
bash tools/code-intel.sh cbm-index
```

Las consultas candidatas se registran como evidencia:

```bash
bash tools/code-intel.sh cbm <tool> [flags...]
```

Ver [`CODEBASE-MEMORY.md`](CODEBASE-MEMORY.md). No activar su `install` nativo, configuración automática de agentes, `auto_watch` ni daemon por rutina. El MCP candidato sólo se inicia explícitamente con `tools/code-intel.sh cbm-mcp`.

Cuando CodeGraph esté disponible, usarlo antes de recorrer masivamente archivos. Registrar símbolos, callers, callees, rutas, dependencias e impacto:

```bash
bash tools/work.sh note "Hallazgo y decisión técnica."
```

Ningún índice sustituye lectura de código, diff, pruebas ni compilación. Una afirmación negativa o de impacto crítico debe verificarse contra fuente cuando corresponda.

### Contexto transportable con Repomix

Usar Repomix **después de acotar el alcance con un backend de inteligencia**, cuando otro LLM necesite una vista autocontenida del código o cuando un paquete reduzca lecturas repetidas.

Ejemplo recomendado:

```bash
bash tools/work.sh context \
  --mode compact \
  --include 'app/src/**/*.kt,docs/**/*.md' \
  --name focused-context
```

Reglas obligatorias:

- Repomix está fijado a la versión documentada en [`REPOMIX.md`](REPOMIX.md).
- Debe existir una orden activa.
- El output va a `evidence/context/` de la orden, fuera del checkout.
- El security check/Secretlint no se desactiva.
- El wrapper no permite remote packing ni `--remote-trust-config`.
- MCP sólo se inicia mediante `tools/context-pack.sh mcp`, que fuerza `--sandbox` al root del repositorio.
- Preferir `--include` y modo `compact`; usar `full` sólo para un conjunto pequeño que requiera implementación exacta.
- Un paquete Repomix no es backup ni fuente de verdad y debe revisarse antes de compartirse externamente.
- No generar Repomix automáticamente en todas las tareas: hacerlo cuando el beneficio de contexto supere su costo de tokens/tiempo.

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

Si se cancela, usar `abort` en el mismo front door. El cierre registra automáticamente el historial persistente y publica `System Status`, `START HERE`, `Repomix Context Packs`, `Codebase Memory Candidate` y `Work History` en Obsidian cuando el vault esté disponible.

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

Los worktrees del swarm, `.codegraph/`, cachés Codebase Memory, SQLite/WAL, Graphify, paquetes Repomix y otros índices regenerables no son fuentes de verdad. Los paquetes Repomix y logs CBM se conservan como evidencia sólo cuando una orden los genera.
