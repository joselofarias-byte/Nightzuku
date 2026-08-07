# Flujo obligatorio para agentes de IA

Esta política se aplica a Codex, Claude, ChatGPT, Gemini, Meta Muse Code, Copilot, Cursor y cualquier otro agente o LLM que analice o modifique este repositorio.

## Elegir el modo de trabajo

### Trabajo normal: un solo agente

Antes de modificar archivos, abrir una orden estándar:

```bash
bash tools/llm-workflow.sh status
bash tools/llm-workflow.sh start --agent <nombre-del-agente> --objective "<objetivo concreto>"
```

Agregar `--structural` cuando el trabajo cambie arquitectura, módulos, dependencias principales, IPC, servicios, flujos centrales o límites entre componentes.

### Trabajo complejo: implementador + revisor independiente

No abrir primero una orden normal. Usar directamente:

```bash
bash tools/swarm-workflow.sh start --objective "<objetivo concreto>"
```

Agregar `--structural` cuando corresponda. El swarm reutiliza `llm-workflow.sh` y crea **una sola orden maestra y un solo respaldo**, por lo que no debe abrirse una orden separada por cada agente.

El diseño, comandos y criterios de uso están en [`SWARM_WORKFLOW.md`](SWARM_WORKFLOW.md).

Si el entorno contiene `SWARM_ROLE` y `SWARM_ROLE_PROMPT`, el agente ya pertenece a una orden maestra: debe leer el archivo indicado por `SWARM_ROLE_PROMPT` y **no ejecutar `start` nuevamente**.

Usar swarm únicamente cuando una revisión independiente aporte valor suficiente para justificar el consumo adicional. Las tareas pequeñas siguen con un solo agente.

### Meta Muse Code

Muse Code se integra como backend opcional mediante [`MUSE.md`](MUSE.md) y `tools/muse-workflow.sh`. No ejecutar su instalador remoto sin auditoría previa. La ausencia de Muse no bloquea el flujo estándar.

## Qué automatiza la orden maestra

`tools/llm-workflow.sh` sigue siendo la única fuente de verdad para:

- guardas Git;
- orden de trabajo externa al checkout;
- estado Git y patches binarios;
- ramas, worktrees e historial;
- bundle Git verificado;
- untracked recuperables;
- evidencia CodeGraph/Graphify;
- manifiesto SHA-256;
- pruebas, builds, checkpoints y cierre.

Ningún orquestador debe duplicar esas funciones.

## Investigación

Cuando CodeGraph esté disponible, usarlo antes de recorrer masivamente archivos. Registrar hallazgos relevantes:

```bash
bash tools/llm-workflow.sh note "Símbolos consultados, callers, callees, flujo e impacto encontrado."
```

Graphify se utiliza sólo para trabajo marcado como estructural o cuando una vista global aporta una decisión concreta.

Los índices orientan la investigación, pero no sustituyen la lectura del código, el diff, las pruebas ni la compilación.

## Pruebas y compilaciones

Ejecutar comandos verificables mediante el registrador estándar:

```bash
bash tools/llm-workflow.sh run -- <comando> <argumentos>
```

Para expresiones de shell complejas:

```bash
bash tools/llm-workflow.sh run -- bash -lc '<comando complejo>'
```

Cada ejecución conserva comando, salida completa, fecha y código de salida dentro de la orden activa.

## Checkpoints

```bash
bash tools/llm-workflow.sh checkpoint "descripción"
```

## Commits y publicación

- No crear commits, hacer push, abrir o cerrar PR, ni fusionar sin autorización expresa del usuario.
- No usar `--no-verify`.
- No desactivar ni modificar `core.hooksPath` para eludir el flujo.
- Todo commit autorizado requiere una orden activa. Los hooks agregan automáticamente los trailers `Work-Order` y `Agent`.
- Un handoff entre agentes no requiere commit: `swarm-workflow.sh handoff` transfiere una fotografía binaria reproducible.

## Cierre

Para una orden normal:

```bash
bash tools/llm-workflow.sh finish "resumen del resultado"
```

Para un swarm:

```bash
bash tools/swarm-workflow.sh finish "resumen del resultado"
```

Si el trabajo se cancela o bloquea, usar el `abort` del mismo motor con el que se inició.

Nunca dejar una orden activa sin cerrarla o abortarla.

## Ubicación de evidencia

Por defecto, órdenes y respaldos se guardan fuera del repositorio en:

```text
$HOME/.local/state/llm-work/<repositorio>/<fecha-objetivo>/
```

Los worktrees del swarm viven bajo `/.worktrees/`, están ignorados por Git y no son respaldo. `.codegraph/` y `graphify-out/` siguen siendo artefactos regenerables y tampoco son fuentes de verdad.
