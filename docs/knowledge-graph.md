# Inteligencia de código

Este repositorio usa índices generados localmente:

- **CodeGraph:** símbolos, llamadas, dependencias y análisis de impacto para agentes de programación.
- **Graphify:** mapa de arquitectura, comunidades y reporte navegable.
- **Obsidian:** archivo central de informes, decisiones y evidencia de trabajo.

El código se procesa localmente. Los índices generados quedan fuera de Git.

## Flujo automático estándar

Toda tarea realizada por un LLM debe seguir [`AI_WORKFLOW.md`](../AI_WORKFLOW.md). No se copian plantillas manualmente: `tools/llm-workflow.sh` crea la orden, el respaldo y la evidencia.

```bash
bash tools/llm-workflow.sh start --agent <llm> --objective "<objetivo>"
bash tools/llm-workflow.sh note "<hallazgo>"
bash tools/llm-workflow.sh run -- <prueba-o-build>
bash tools/llm-workflow.sh finish "<resultado>"
```

Usar `--structural` en `start` cuando corresponda regenerar Graphify. Un commit autorizado queda bloqueado si no existe una orden activa y recibe automáticamente trailers que identifican la orden y el agente.

## Uso recomendado

### CodeGraph: uso cotidiano

Usarlo antes de modificar flujos complejos, servicios, configuración, IPC, arranque, permisos o módulos con muchas dependencias.

Consultas típicas para Claude, Codex o Gemini:

```text
Usá CodeGraph antes de leer archivos. Trazá el flujo desde StarterActivity hasta ShizukuService.
```

```text
Usá CodeGraph para identificar callers, callees e impacto de modificar ShizukuConfigManager.
```

```text
Usá CodeGraph para enumerar los componentes afectados y después verificá sólo los archivos relevantes.
```

CodeGraph orienta la investigación; la validación final debe revisar el código y ejecutar las pruebas o compilaciones correspondientes.

### Graphify: uso ocasional

Usarlo cuando sea útil una vista global:

- auditoría inicial de un repositorio o módulo desconocido;
- preparación de una refactorización grande;
- detección de zonas muy conectadas o comunidades separadas;
- explicación visual de la arquitectura;
- comparación estructural antes y después de un cambio importante.

No es necesario regenerarlo en cada modificación pequeña.

La gráfica interactiva exportada se abre desde:

```text
Documents/Engineering-KB/Projects/Nightzuku/Attachments/graph.html
```

### Obsidian: memoria de ingeniería

Conservar allí:

- resumen de hallazgos;
- decisiones tomadas y descartadas;
- riesgos y dependencias detectadas;
- reportes de Graphify;
- estado textual de CodeGraph;
- referencias a commits, ramas, builds y respaldos.

Obsidian no sustituye Git, los bundles ni los parches de recuperación.

## Integración con órdenes de trabajo

La orden automática registra:

1. repositorio, rama y commit base;
2. objetivo concreto de la investigación;
3. agente responsable;
4. estado de CodeGraph y Graphify;
5. notas de símbolos, callers, callees, dependencias e impacto;
6. pruebas y compilaciones con salida y código de retorno;
7. checkpoints previos a commits;
8. resultado final, commit alcanzado y estado del árbol.

La plantilla [`work-order-template.md`](work-order-template.md) queda como referencia humana del contenido esperado.

## Integración con respaldos

`start` genera automáticamente:

- `status.txt`;
- `unstaged.patch` y `staged.patch` binarios;
- ramas, worktrees e historial;
- bundle Git completo y verificado;
- archivo recuperable de untracked;
- versiones y estado de CodeGraph/Graphify;
- SHA-256 del reporte y del HTML cuando existen;
- manifiesto SHA-256 integral.

No se respaldan como fuentes de verdad:

- `.codegraph/`;
- `graphify-out/`;
- bases SQLite, WAL, cachés AST o temporales.

Son artefactos regenerables. Guía: [`backup-manifest.md`](backup-manifest.md).

## Comandos directos de índices

```bash
bash tools/knowledge-graph.sh install
bash tools/knowledge-graph.sh index
bash tools/knowledge-graph.sh status
bash tools/knowledge-graph.sh obsidian
```

Para actualizar manualmente:

```bash
bash tools/knowledge-graph.sh sync
bash tools/knowledge-graph.sh obsidian
```

Normalmente el cierre de `llm-workflow.sh` hace la actualización correspondiente. El checkout debe estar en el sistema de archivos nativo de Debian. No crear índices SQLite en `/sdcard` ni `/storage/emulated`.
