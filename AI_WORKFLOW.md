# Flujo obligatorio para agentes de IA

Esta política se aplica a Codex, Claude, ChatGPT, Gemini, Meta Muse Code, Copilot, Cursor y cualquier otro agente o LLM que analice o modifique este repositorio.

## Cero memoria requerida

Todo agente debe comenzar leyendo [`START-HERE.md`](START-HERE.md) y ejecutando:

```bash
bash tools/system-docs.sh summary
bash tools/system-docs.sh doctor
```

El sistema debe ser operable sin recordar conversaciones anteriores. Estado, historial, herramientas, órdenes y próximos comandos se obtienen desde el repositorio y la evidencia persistente.

## Congelación de herramientas y LLM

El stack actual se considera **suficiente y cerrado para trabajo normal**. No investigar, instalar ni integrar rutinariamente nuevos LLM, agentes, runtimes, grafos, memorias, orquestadores o sistemas documentales por novedad.

Una herramienta nueva sólo se evalúa cuando se cumple al menos una de estas condiciones:

1. existe una carencia concreta que el stack actual no puede resolver de forma razonable; o
2. puede reemplazar una capa existente y demuestra una mejora medible en precisión, cobertura, tiempo, tokens, estabilidad o mantenimiento.

No se mantiene una herramienta adicional sólo como respaldo hipotético. Primero se usan las capacidades ya disponibles; si aparece una limitación real, se documenta y recién entonces se compara una alternativa.

La prioridad desde esta decisión es **producir y mejorar el proyecto**, no seguir ampliando el taller.

## Elegir el modo de trabajo

### Investigación o auditoría sin cambios: modo light

Para consultas, auditorías, benchmarks, lectura de código y análisis que **no deben modificar el checkout**, usar:

```bash
bash tools/work.sh start --light \
  --agent <nombre-del-agente> \
  --objective "<objetivo concreto>"
```

El modo `--light`:

- exige checkout limpio;
- no crea bundle Git completo ni archivo de untracked;
- no permite checkpoints pesados ni commits;
- omite Graphify y refresco de índices al cierre;
- conserva objetivo, notas, comandos y evidencia necesaria;
- falla al cerrar si cambió HEAD o apareció cualquier cambio en el worktree.

Si durante la investigación surge la necesidad de modificar código, cerrar/abortar la orden light y abrir una orden normal. `--light` y `--structural` son mutuamente excluyentes.

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

Muse Code es un backend opcional documentado en [`MUSE.md`](MUSE.md). No es requisito y su instalador remoto no se ejecuta sin auditoría/compatibilidad demostrada. La congelación de herramientas impide promoverlo o reemplazar capas actuales sin una carencia real y evidencia comparativa.

## Capas canónicas y no duplicación

- `tools/llm-workflow.sh`: motor único de orden, backup, evidencia, tests y cierre para trabajo con cambios.
- `tools/work.sh`: front door normal y modo read-only `--light`.
- `tools/swarm-workflow.sh`: motor de worktrees/handoff de dos roles.
- `tools/swarm.sh`: front door swarm con autodocumentación.
- `tools/system-docs.sh`: dashboard, snapshots, historial, doctor y publicación a Obsidian.
- **CodeGraph: backend PRIMARY de inteligencia de código.**
- `tools/code-intel.sh` / **Codebase Memory MCP: backend CANDIDATE/SHADOW**, no segundo índice obligatorio.
- Repomix / `tools/context-pack.sh`: transporte opcional de contexto seleccionado a otros LLM.
- Graphify: sólo para trabajo estructural cuando aporte una decisión.
- Obsidian: espejo humano; no fuente de verdad.

No agregar otro sistema paralelo para backups, órdenes, logs, constitución de agentes, handoffs o índice obligatorio sin documentar primero el reemplazo del sistema canónico y medir la ventaja.

Decisiones ya tomadas: TBM queda histórico; SwarmForge aportó conceptos pero no se vende su runtime; Loop Engineering queda como referencia de patrones y no segundo runtime obligatorio; Repowise sigue como referencia/piloto; **Codebase Memory se usa sin auto-watch, sin configuración global de agentes y sin reemplazar CodeGraph**; Repomix sólo transporta contexto y no reemplaza grafo, backups, handoffs ni documentación operativa; Graphify no se ejecuta por defecto.

## Investigación

### Flujo diario validado de impacto

Un benchmark real sobre el flujo `providerFetch` de Vega mostró que ningún backend aislado alcanzó cobertura suficiente. El mejor resultado fue combinar herramientas estructurales con reglas locales baratas: **18/18 archivos relevantes, 100% recall y 81,8% de precisión** en esa prueba. El empaquetado compacto había reducido el payload al LLM de 21.170 a 4.447 tokens en la etapa previa comparable (79% menos); esa cifra corresponde a ese benchmark y no se generaliza automáticamente a todas las tareas.

Por eso, para análisis de impacto, la secuencia normal es:

1. **CodeGraph primero** para símbolos, callers/callees, dependencias e impacto estructural.
2. **Búsqueda textual dirigida** (`git grep`/equivalente) para recuperar referencias que el grafo no cubra.
3. **Cierre determinista barato** sobre el conjunto candidato:
   - imports/dependencias directas;
   - tests asociados o espejo;
   - productores de archivos generados y scripts de build relacionados.
4. Leer manualmente sólo los archivos relevantes finales y verificar afirmaciones críticas contra fuente.
5. **Codebase Memory sólo bajo demanda** si aporta arquitectura, semántica, `detect_changes`, Cypher, clones o una segunda opinión necesaria.
6. **Repomix compact al final**, cuando un LLM necesite contexto autocontenido y la compactación compense su costo.

El objetivo no es minimizar el número de archivos a cualquier precio: para impacto se prioriza recall alto con ruido razonable, porque omitir una dependencia relevante es más costoso que revisar unos pocos candidatos extra.

### Selección de backend

Antes de una investigación amplia:

```bash
bash tools/code-intel.sh status
```

La regla normal es **CodeGraph primero**, complementado por búsqueda textual y cierre determinista. Usar Codebase Memory únicamente cuando:

- se necesite búsqueda semántica local;
- Hybrid LSP pueda mejorar la resolución Kotlin/Java;
- se necesite `detect_changes`, Cypher o análisis de clones/relaciones semánticas;
- exista una duda arquitectónica o de blast radius que justifique una segunda opinión;
- se esté ejecutando una comparación controlada entre backends por una carencia real.

Codebase Memory no se indexa automáticamente en todas las órdenes. Su índice se crea sólo dentro de una orden activa cuando hace falta:

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

Usar Repomix **después de acotar y cerrar el alcance relevante**, cuando otro LLM necesite una vista autocontenida del código o cuando un paquete reduzca lecturas repetidas.

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

En órdenes normales:

```bash
bash tools/work.sh checkpoint "descripción"
```

El wrapper agrega además una fotografía del sistema y actualiza la vista humana cuando es posible. Los checkpoints están deliberadamente deshabilitados en `--light`.

## Commits y publicación

- No crear commits, hacer push, abrir/cerrar PR ni fusionar sin autorización expresa del usuario.
- Las órdenes `--light` nunca autorizan commits; el hook los bloquea.
- No usar `--no-verify`.
- No desactivar `core.hooksPath` para eludir guardas.
- Todo commit autorizado requiere orden normal activa; los hooks agregan `Work-Order` y `Agent`.
- El handoff del swarm no necesita commit: transfiere staged + unstaged + untracked con hashes.

## Cierre obligatorio

Trabajo normal o light:

```bash
bash tools/work.sh finish "resumen del resultado"
```

Swarm:

```bash
bash tools/swarm.sh finish "resumen del resultado"
```

Si se cancela, usar `abort` en el mismo front door. Una orden normal registra historial, snapshots y publicación a Obsidian; una orden `--light` conserva el historial mínimo y omite deliberadamente el cierre pesado. Nunca dejar una orden activa sin cerrar o abortar.

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
