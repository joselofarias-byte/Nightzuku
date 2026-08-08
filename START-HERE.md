# START HERE — sistema de ingeniería

Este archivo es el **punto de entrada único** para cualquier persona o LLM que llegue al repositorio sin contexto previo.

No hace falta recordar qué agregamos ni cómo se conecta cada herramienta. El sistema debe poder explicarse, diagnosticarse y reconstruir su historial por sí mismo.

## 1. Si no recordás nada

Ejecutar:

```bash
bash tools/system-docs.sh summary
bash tools/system-docs.sh doctor
```

`summary` muestra proyecto, rama, HEAD, cambios locales, orden activa, último trabajo, swarm, hooks, herramientas disponibles y próximos comandos seguros. `doctor` valida que la instalación documental/operativa esté completa.

## 2. Jerarquía canónica

1. `START-HERE.md` — entrada humana y para LLM.
2. `AGENTS.md` — reglas de ingeniería del repositorio.
3. `AI_WORKFLOW.md` — política operativa obligatoria.
4. `tools/llm-workflow.sh` — motor estable de orden, backup, evidencia, pruebas y cierre.
5. `tools/work.sh` — **front door obligatorio para trabajo normal**, añade autodocumentación al motor.
6. `tools/swarm-workflow.sh` — motor interno de worktrees/handoff.
7. `tools/swarm.sh` — **front door obligatorio para trabajo multi-LLM**.
8. `tools/system-docs.sh` — dashboard, snapshots, historial, doctor y publicación a Obsidian.
9. CodeGraph — índice principal del código y relaciones.
10. Repomix / `tools/context-pack.sh` — transporte opcional de contexto seleccionado a otros LLM, con presupuesto de tokens y escaneo de secretos.
11. Graphify — arquitectura/refactors grandes, sólo cuando aporte valor.
12. Obsidian — espejo humano, no fuente de verdad.
13. Muse Code — backend experimental opcional, no requisito.

## 3. Trabajo normal: un agente

```bash
bash tools/work.sh start \
  --agent <agente> \
  --objective "<objetivo>"
```

Agregar `--structural` para cambios de arquitectura, módulos, IPC, servicios, dependencias principales o flujos centrales.

Durante el trabajo:

```bash
bash tools/work.sh note "<hallazgo o decisión>"
bash tools/work.sh run -- <test/build/comando>
bash tools/work.sh checkpoint "<etapa>"
```

Si hace falta entregar a otro LLM una vista autocontenida del código relevante, después de usar CodeGraph para acotar el alcance:

```bash
bash tools/work.sh context \
  --mode compact \
  --include 'app/src/**/*.kt,docs/**/*.md' \
  --name focused-context
```

Ver [`REPOMIX.md`](REPOMIX.md). Los paquetes se guardan dentro de la evidencia de la orden, nunca como archivos fuente ni backups.

Cerrar siempre:

```bash
bash tools/work.sh finish "<resultado>"
```

O cancelar preservando evidencia:

```bash
bash tools/work.sh abort "<motivo>"
```

El wrapper genera snapshots del sistema, actualiza el historial persistente y publica la vista humana a Obsidian cuando el vault está disponible.

## 4. Trabajo complejo: implementador + revisor

```bash
bash tools/swarm.sh start --objective "<objetivo>"
```

Topología:

```text
orden + respaldo únicos
        ↓
implementador / worktree aislado
        ↓
handoff binario reproducible sin commit
        ↓
revisor independiente / segundo worktree
        ↓
evidencia + decisión + cierre
```

Comandos principales:

```bash
bash tools/swarm.sh prompt implementer
bash tools/swarm.sh handoff
bash tools/swarm.sh prompt reviewer
bash tools/swarm.sh review-note "<observación>"
bash tools/swarm.sh finish "<resultado>"
```

Usar swarm sólo cuando una revisión realmente independiente compense el consumo adicional de cuota/contexto. Repomix puede usarse dentro de la orden maestra si un rol necesita un paquete de contexto, pero no sustituye el handoff reproducible.

## 5. Recuperar qué se hizo

```bash
bash tools/system-docs.sh history
```

Generar una fotografía completa:

```bash
bash tools/system-docs.sh snapshot
```

Actualizar manualmente Obsidian:

```bash
bash tools/system-docs.sh publish
```

Los cierres normales y swarm hacen el registro/publicación automáticamente.

## 6. No duplicar

No crear otro sistema paralelo para:

- backups Git;
- órdenes de trabajo;
- logs de pruebas;
- worktrees/handoffs;
- políticas de agentes;
- índice obligatorio de código;
- historial operativo.

Decisiones vigentes:

- TBM: histórico; no duplicar el backup actual.
- SwarmForge: se absorbieron roles/worktrees/handoffs, no su runtime.
- Loop Engineering: referencia de patrones, no segundo runtime obligatorio.
- Repowise: referencia/piloto mientras CodeGraph cubra la necesidad diaria.
- **Repomix: contexto transportable, no grafo, backup, wiki ni paso obligatorio de cada tarea.**
- Graphify: no se ejecuta por defecto.
- Muse Code: opcional y condicionado a compatibilidad real.

## 7. Seguridad y autorización

- No usar `--no-verify`.
- No desactivar `core.hooksPath` para saltar guardas.
- Commit, push, merge y apertura/cierre de PR requieren autorización expresa.
- No versionar secretos ni índices regenerables.
- El wrapper de Repomix mantiene Secretlint habilitado, prohíbe remote packing/config trust y confina MCP mediante `--sandbox`.
- Un scan de secretos es heurístico: revisar cualquier paquete antes de compartirlo externamente.
- Ante una situación incierta, preservar evidencia y abortar antes que destruir estado.

## 8. Troubleshooting conocido

Antes de volver a diagnosticar un warning o fallo del harness, consultar [`docs/workflow-troubleshooting.md`](docs/workflow-troubleshooting.md).

Ese documento registra fallos reales ya observados, sus causas y las pruebas de regresión incorporadas. Incluye el warning PRoot de `/proc/self/fd/0`, el antiguo `rc=127` generado accidentalmente al renderizar prompts del swarm y el `Broken pipe` del test de autodocumentación.

## 9. Regla para cualquier LLM futuro

Si una CLI o modelo nuevo no conoce este entorno, debe leer este archivo y `AI_WORKFLOW.md`, ejecutar `tools/system-docs.sh summary` y continuar desde el estado real. No debe pedir al usuario que recuerde el historial técnico si éste ya puede recuperarse del sistema.
