# START HERE — sistema de ingeniería

Este archivo es el **punto de entrada único** para cualquier persona o LLM que llegue al repositorio sin contexto previo.

No es necesario recordar qué herramientas fueron agregadas ni cómo se conectan. El sistema debe poder explicarse y validarse solo.

## 1. Qué manda

La jerarquía es:

1. `AGENTS.md` — reglas de ingeniería del repositorio.
2. `AI_WORKFLOW.md` — política operativa para cualquier LLM.
3. `tools/llm-workflow.sh` — única fuente de verdad para orden, respaldo, evidencia, tests y cierre.
4. `tools/swarm-workflow.sh` — orquestación opcional implementador + revisor; reutiliza la misma orden.
5. `CodeGraph` — índice principal para impacto, dependencias, callers/callees y trazado.
6. `Graphify` — sólo arquitectura/refactors grandes cuando aporte valor.
7. `Obsidian` — vista humana y memoria de ingeniería; no reemplaza Git ni los respaldos.
8. `MUSE.md` / `tools/muse-workflow.sh` — backend experimental opcional, no requisito.

Si dos herramientas cubren lo mismo, gana la capa ya canónica y se evita agregar otra dependencia.

## 2. Antes de hacer cualquier cosa

```bash
bash tools/system-docs.sh summary
bash tools/system-docs.sh doctor
```

Eso muestra el estado real del repositorio, herramientas disponibles, orden activa, hooks, swarm y documentación.

## 3. Trabajo normal: un agente

```bash
bash tools/llm-workflow.sh start \
  --agent <agente> \
  --objective "<objetivo>"
```

Para cambio estructural, agregar `--structural`.

Durante el trabajo:

```bash
bash tools/llm-workflow.sh note "<hallazgo o decisión>"
bash tools/llm-workflow.sh run -- <test/build/comando>
bash tools/llm-workflow.sh checkpoint "<etapa>"
```

Cerrar siempre:

```bash
bash tools/llm-workflow.sh finish "<resultado>"
```

O, si se cancela:

```bash
bash tools/llm-workflow.sh abort "<motivo>"
```

## 4. Trabajo complejo: dos agentes

```bash
bash tools/swarm-workflow.sh start \
  --objective "<objetivo>"
```

Topología estándar:

```text
orden + respaldo únicos
        ↓
implementador en worktree aislado
        ↓
handoff reproducible sin commit
        ↓
revisor independiente en otro worktree
        ↓
decisión / evidencia / cierre
```

Comandos principales:

```bash
bash tools/swarm-workflow.sh prompt implementer
bash tools/swarm-workflow.sh handoff
bash tools/swarm-workflow.sh prompt reviewer
bash tools/swarm-workflow.sh review-note "<observación>"
bash tools/swarm-workflow.sh finish "<resultado>"
```

Usar swarm sólo cuando una revisión independiente compense el consumo extra de cuota/contexto.

## 5. Cómo saber qué ocurrió anteriormente

```bash
bash tools/system-docs.sh history
```

Cada `finish` o `abort` registra automáticamente una entrada en el historial persistente externo al checkout.

Para generar una fotografía completa del sistema:

```bash
bash tools/system-docs.sh snapshot
```

Para actualizar la vista de Obsidian cuando esté disponible:

```bash
bash tools/system-docs.sh publish
```

El propio `llm-workflow.sh` captura snapshots al abrir, en checkpoints y al cerrar, y publica la vista humana al final.

## 6. Qué NO se debe duplicar

No crear otro sistema paralelo para:

- backups Git;
- órdenes de trabajo;
- logs de pruebas;
- worktrees/handoffs;
- políticas de agentes;
- índices de código obligatorios;
- historial de ejecución.

Antes de integrar un proyecto externo, revisar si su función ya existe. En particular:

- TBM permanece histórico y no duplica el backup de `llm-workflow`;
- SwarmForge aportó conceptos, no su runtime;
- Loop Engineering permanece referencia para patrones y técnicas, no un segundo runtime obligatorio;
- Repowise permanece piloto/referencia mientras CodeGraph sea suficiente;
- Graphify no se ejecuta por defecto;
- Muse Code no es dependencia y su instalación sigue condicionada a compatibilidad real.

## 7. Seguridad y autorización

- No usar `--no-verify`.
- No desactivar `core.hooksPath` para saltar guardas.
- Commit, push, merge y apertura/cierre de PR requieren autorización expresa.
- No versionar índices regenerables ni secretos.
- Ante duda, preservar evidencia y abortar antes que destruir estado.

## 8. Si no recordás nada

Ejecutar solamente:

```bash
bash tools/system-docs.sh summary
```

La salida indica qué existe, qué está activo y cuáles son los siguientes comandos seguros.
