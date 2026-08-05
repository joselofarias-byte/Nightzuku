# Inteligencia de código

Este repositorio usa índices generados localmente:

- **CodeGraph:** símbolos, llamadas, dependencias y análisis de impacto para agentes de programación.
- **Graphify:** mapa de arquitectura, comunidades y reporte navegable.
- **Obsidian:** archivo central de informes, decisiones y evidencia de trabajo.

El código se procesa localmente. Los índices generados quedan fuera de Git.

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

Toda orden de trabajo que use estas herramientas debe registrar:

1. repositorio, rama y commit base;
2. objetivo concreto de la investigación;
3. si se consultó CodeGraph y qué símbolos o rutas se analizaron;
4. callers, callees, dependencias o impactos relevantes;
5. si se usó Graphify y para qué decisión;
6. archivos previstos para modificar;
7. pruebas, compilación y criterios de aceptación;
8. resultado final y commit producido, o constancia de que no hubo commit/push.

Plantilla: [`work-order-template.md`](work-order-template.md).

## Integración con respaldos

El respaldo recuperable continúa basándose en Git:

- `status.txt`;
- `unstaged.patch`;
- `staged.patch`;
- `branches.txt`;
- `worktrees.txt`;
- `log.txt`;
- bundle Git completo;
- inventario de archivos untracked cuando corresponda;
- manifiesto SHA-256.

Agregar como evidencia liviana:

- versión de CodeGraph y Graphify;
- salida de `codegraph status`;
- commit y rama usados para generar el índice;
- SHA-256 del reporte y del HTML de Graphify, si existen;
- ruta del vault donde quedaron los informes.

No respaldar como datos esenciales:

- `.codegraph/`;
- `graphify-out/`;
- bases SQLite, WAL, cachés AST o archivos temporales de Graphify.

Son artefactos regenerables. El informe Markdown puede conservarse en Obsidian como evidencia, pero no es necesario para restaurar el repositorio.

Guía: [`backup-manifest.md`](backup-manifest.md).

## Comandos

```bash
bash tools/knowledge-graph.sh install
bash tools/knowledge-graph.sh index
bash tools/knowledge-graph.sh status
bash tools/knowledge-graph.sh obsidian
```

Para actualizar después de cambios relevantes:

```bash
bash tools/knowledge-graph.sh sync
bash tools/knowledge-graph.sh obsidian
```

El checkout debe estar en el sistema de archivos nativo de Debian. No crear índices SQLite en `/sdcard` ni `/storage/emulated`.
