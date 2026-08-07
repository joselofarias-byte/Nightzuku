# Orquestación multi-LLM estándar

Este repositorio incorpora un flujo inspirado en las ideas útiles de SwarmForge, pero **sin copiar ni depender de su runtime**.

## Qué se conserva

- separación por roles;
- worktrees Git aislados;
- handoff reproducible entre implementador y revisor;
- sesiones opcionales en tmux;
- posibilidad de usar backends distintos por rol.

## Qué se descarta por duplicidad o costo

No se incorpora:

- una segunda constitución de agentes: `AGENTS.md` y `AI_WORKFLOW.md` ya son la política canónica;
- un segundo sistema de órdenes, respaldos o logs: `tools/llm-workflow.sh` ya cubre bundle, patches, untracked, SHA-256, pruebas, checkpoints y cierre;
- el daemon propio de handoffs: para nuestro flujo de dos roles alcanza un snapshot binario determinista;
- descargas dinámicas de scripts al comenzar una orden;
- Babashka como dependencia adicional;
- inhibidores de suspensión específicos de escritorio;
- estados de runtime versionados dentro del repositorio;
- commits obligatorios para pasar trabajo entre agentes.

Tampoco se copia código del proyecto externo mientras no exista una licencia explícita verificable en el repositorio consultado.

## Topología por defecto

```text
orden/backup único
        |
        v
implementador (Codex por defecto)
        |
        | snapshot: staged + unstaged + untracked + SHA-256
        v
revisor independiente (Claude por defecto)
        |
        v
orden maestra / evidencia / decisión humana
```

Los backends son configurables. Los nombres `codex` y `claude` son valores sugeridos, no requisitos.

## Inicio

```bash
bash tools/swarm-workflow.sh start \
  --objective "<objetivo concreto>"
```

Para cambios estructurales:

```bash
bash tools/swarm-workflow.sh start \
  --objective "<objetivo concreto>" \
  --structural
```

Para elegir otros backends:

```bash
bash tools/swarm-workflow.sh start \
  --objective "<objetivo>" \
  --implementer codex \
  --reviewer claude
```

`start` abre **una sola** orden con `llm-workflow.sh` y crea únicamente el worktree del implementador.

## Rol implementador

Ver instrucciones generadas:

```bash
bash tools/swarm-workflow.sh prompt implementer
```

Opcionalmente lanzar una CLI en tmux:

```bash
bash tools/swarm-workflow.sh spawn implementer -- codex
```

El agente puede editar sólo su worktree. Tests y builds se registran con el motor existente:

```bash
bash tools/llm-workflow.sh run -- <comando>
```

## Handoff sin commit

Cuando la implementación está lista para revisión:

```bash
bash tools/swarm-workflow.sh handoff
```

El handoff:

1. captura diff staged y unstaged en formato binario;
2. archiva untracked no ignorados;
3. genera SHA-256;
4. crea un worktree de revisión desde el mismo commit base;
5. aplica allí exactamente la fotografía recibida;
6. enlaza el revisor a la misma orden maestra.

Así el revisor ve una copia reproducible sin exigir commits intermedios.

## Rol revisor

```bash
bash tools/swarm-workflow.sh prompt reviewer
bash tools/swarm-workflow.sh spawn reviewer -- claude
```

El revisor es independiente y, por defecto, no modifica código. Sus observaciones se registran con:

```bash
bash tools/swarm-workflow.sh review-note "<observación>"
```

## Cierre

```bash
bash tools/swarm-workflow.sh finish "<resultado>"
```

El cierre:

- detiene sesiones tmux del swarm;
- conserva snapshots finales;
- desactiva la orden en los worktrees;
- cierra la única orden maestra mediante `llm-workflow.sh`;
- conserva los worktrees para inspección.

Si se cancela:

```bash
bash tools/swarm-workflow.sh abort "<motivo>"
```

## Limpieza

```bash
bash tools/swarm-workflow.sh cleanup
```

La limpieza se niega si algún worktree conserva cambios no comprometidos. Nunca borra trabajo silenciosamente.

## Autorización

La orquestación no concede permisos adicionales. Ningún agente puede hacer commit, push, merge ni abrir/cerrar PR sin autorización expresa del usuario. Los hooks y las reglas de `AI_WORKFLOW.md` siguen siendo obligatorios.

## Principio de uso

No usar dos agentes para tareas triviales. El swarm se reserva para trabajos donde una revisión realmente independiente compense el consumo adicional de contexto, cuota y tiempo.
