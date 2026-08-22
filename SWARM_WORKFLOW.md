# Orquestación multi-LLM estándar

Este repositorio incorpora un flujo inspirado en las ideas útiles de SwarmForge, pero **sin copiar ni depender de su runtime**.

`tools/swarm-workflow.sh` es el motor interno estable. Para uso normal, humano o por LLM, el punto de entrada es **`tools/swarm.sh`**, que añade snapshots automáticos, historial persistente y publicación a Obsidian.

## Qué se conserva

- separación por roles;
- worktrees Git aislados;
- handoff reproducible entre implementador y revisor;
- sesiones opcionales en tmux;
- backends distintos por rol;
- una única orden y un único respaldo para todo el trabajo.

## Qué se descarta por duplicidad o costo

No se incorpora:

- una segunda constitución de agentes: `AGENTS.md` y `AI_WORKFLOW.md` ya gobiernan;
- otro sistema de órdenes/backups/logs: `llm-workflow.sh` ya cubre bundle, patches, untracked, SHA-256, pruebas y checkpoints;
- daemon externo de handoffs: un snapshot binario determinista cubre el flujo de dos roles;
- descargas dinámicas de scripts;
- Babashka como dependencia adicional;
- inhibidores de suspensión de escritorio;
- runtime state versionado dentro del repositorio;
- commits obligatorios para traspasar trabajo.

No se copia código del proyecto externo mientras no exista una licencia explícita verificable. Loop Engineering se conserva como referencia de patrones y herramientas, no como segundo runtime obligatorio.

## Topología por defecto

```text
orden + backup único
        ↓
implementador (Codex sugerido)
        ↓
snapshot staged + unstaged + untracked + SHA-256
        ↓
revisor independiente (Claude sugerido)
        ↓
evidencia + decisión humana
```

Los backends son configurables; Codex y Claude son sugerencias, no requisitos.

## Antes de iniciar

```bash
bash tools/system-docs.sh summary
bash tools/system-docs.sh doctor
```

## Inicio

```bash
bash tools/swarm.sh start --objective "<objetivo concreto>"
```

Cambio estructural:

```bash
bash tools/swarm.sh start \
  --objective "<objetivo concreto>" \
  --structural
```

Backends explícitos:

```bash
bash tools/swarm.sh start \
  --objective "<objetivo>" \
  --implementer codex \
  --reviewer claude
```

El wrapper abre una sola orden a través del motor y captura inmediatamente el estado del sistema.

## Implementador

```bash
bash tools/swarm.sh prompt implementer
bash tools/swarm.sh spawn implementer -- codex
```

Dentro del rol, `SWARM_ROLE` y `SWARM_ROLE_PROMPT` indican que ya existe una orden maestra. El agente no debe abrir otra.

Pruebas y builds pueden registrarse mediante:

```bash
bash tools/work.sh run -- <comando>
```

## Handoff sin commit

```bash
bash tools/swarm.sh handoff
```

El motor:

1. captura staged y unstaged como patch binario;
2. archiva untracked no ignorados;
3. genera SHA-256;
4. crea el worktree del revisor desde el mismo HEAD base;
5. aplica exactamente la fotografía;
6. enlaza el revisor a la misma orden;
7. el wrapper captura un nuevo snapshot del sistema y actualiza Obsidian.

## Revisor

```bash
bash tools/swarm.sh prompt reviewer
bash tools/swarm.sh spawn reviewer -- claude
bash tools/swarm.sh review-note "<observación>"
```

El revisor es independiente y, por defecto, no modifica código.

## Cierre

```bash
bash tools/swarm.sh finish "<resultado>"
```

El cierre:

- detiene sesiones tmux del swarm;
- conserva snapshots finales;
- desactiva la orden en los worktrees;
- cierra la única orden maestra;
- registra automáticamente el trabajo en `HISTORY.md` externo al checkout;
- genera un snapshot final;
- publica `System Status`, `START HERE` y `Work History` a Obsidian cuando está disponible;
- conserva los worktrees para inspección.

Si se cancela:

```bash
bash tools/swarm.sh abort "<motivo>"
```

## Historial y diagnóstico

```bash
bash tools/swarm.sh status
bash tools/swarm.sh history
bash tools/system-docs.sh snapshot
```

## Limpieza

```bash
bash tools/swarm.sh cleanup
```

La limpieza se niega si algún worktree conserva cambios no comprometidos. Nunca borra trabajo silenciosamente.

## Autorización

La orquestación no concede permisos adicionales. Ningún agente puede hacer commit, push, merge ni abrir/cerrar PR sin autorización expresa del usuario. No usar `--no-verify` ni eludir hooks.

## Principio de uso

No usar dos agentes para tareas triviales. El swarm se reserva para trabajos donde una revisión realmente independiente compense el consumo adicional de contexto, cuota y tiempo.
