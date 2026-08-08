# Troubleshooting del sistema de ingeniería

Este documento registra fallos reales observados durante la validación del flujo para evitar volver a diagnosticarlos desde cero.

## `proot warning: can't sanitize binding /proc/self/fd/0`

Se observó al entrar a Debian PRoot desde Termux.

Estado: **warning tolerable mientras el proceso posterior continúe**.

No se considera por sí solo un fallo del flujo. La validación debe juzgar el código de salida y las comprobaciones posteriores.

## CodeGraph aparece como `NOT_INSTALLED` en un shell no interactivo

### Síntoma real

Durante la validación de Codebase Memory 0.9.0, el sistema ya tenía CodeGraph instalado, pero `tools/code-intel.sh codegraph-status` devolvió:

```text
ERROR: CodeGraph is not installed
```

### Causa

CodeGraph estaba instalado bajo `$HOME/.local/bin`, pero el `bash -s` no interactivo lanzado dentro de Debian PRoot no heredó esa ruta en `PATH`.

No era una desinstalación ni una pérdida del índice.

### Corrección

`tools/code-intel.sh` agrega `$HOME/.local/bin` a `PATH` y además resuelve herramientas conocidas por rutas explícitas (`$HOME/.local/bin`, `/usr/local/bin`, `/usr/bin`). Los validadores también pueden exponer de forma estable un binario local existente en `/usr/local/bin` sin reinstalarlo ni sobrescribir otro binario.

Regla: **antes de afirmar que una herramienta local desapareció, distinguir `command not found` por PATH de una instalación realmente ausente**.

## Codebase Memory y el artefacto `.codebase-memory/graph.db.zst`

Codebase Memory 0.9.0 soporta un artefacto compartible del grafo dentro del repositorio. Su pipeline sólo lo exporta cuando `persistence` está habilitado.

Para nuestros pilotos, el índice candidato no debe modificar el checkout. La validación debe comprobar que `index_repository --help` expone `--persistence` y ejecutar el indexado con `--persistence false`. Si esa capacidad no está disponible, se aborta antes de indexar; no se limpia silenciosamente el repositorio después.

## `ERROR ... rc=127 ... tee -a "$log_file"` durante el test swarm

### Causa real

No era falta de `tee`.

`tools/swarm-workflow.sh` generaba los prompts de implementador y revisor mediante un heredoc expandible. Algunos ejemplos de comandos Markdown estaban escritos entre backticks. Bash interpreta backticks como sustitución de comandos dentro de un heredoc no citado, por lo que intentaba ejecutar esos ejemplos mientras generaba el archivo de instrucciones.

El comando de ejemplo terminaba llegando a `tools/llm-workflow.sh run`, y el comando ficticio producía `127`. La traza mostraba `tee` porque `run_logged` canaliza la salida mediante `tee`.

### Corrección

Los prompts ya no contienen sintaxis de sustitución ejecutable dentro del heredoc. Los comandos se escriben como texto simple.

`tools/test-swarm-workflow.sh` incluye una guarda de regresión que exige que **cero logs de ejecución** aparezcan sólo por crear los prompts de los roles.

## `printf: write error: Broken pipe` en `system-docs.sh`

### Causa real

El test ejecutaba:

```bash
bash tools/system-docs.sh summary | grep -q '^WORK_ORDER=NONE$'
```

`grep -q` termina en cuanto encuentra la coincidencia. El productor seguía escribiendo varias líneas; con `set -o pipefail`, el cierre temprano de la tubería podía convertirse en un error aunque el dashboard fuera correcto.

### Corrección

La prueba captura primero la salida completa y luego la inspecciona:

```bash
summary_out="$(bash tools/system-docs.sh summary)"
grep -q '^WORK_ORDER=NONE$' <<< "$summary_out"
```

Se aplica el mismo criterio a la salida de `history` y a otras comprobaciones donde un consumidor de salida corta podría cerrar una tubería antes de tiempo.

## `core.hooksPath=NOT_CONFIGURED` dentro de una fixture sintética

Una fixture de prueba es un repositorio Git nuevo y no hereda la configuración local del clon real. El test ahora ejecuta `tools/llm-workflow.sh install` antes de `doctor`, de modo que la propia fixture verifica el estado esperado de los hooks.

## Regla de seguridad ante cualquier fallo

Los validadores deben:

1. detener la actualización ante repositorios piloto sucios u órdenes activas, salvo una orden de validación propia que pueda abortarse preservando evidencia;
2. conservar la salida completa;
3. comparar la huella del Nightzuku original antes y después;
4. no hacer push ni merge;
5. no borrar worktrees con cambios;
6. convertir todo fallo reproducible en una prueba de regresión cuando sea razonable;
7. no reparar automáticamente un checkout generado por una herramienta si puede desactivar esa escritura antes de ejecutarla.

La validación del 7 de agosto de 2026 confirmó que el Nightzuku original permaneció intacto incluso cuando falló el test de autodocumentación. La validación de Codebase Memory del 8 de agosto volvió a confirmar el mismo comportamiento ante un falso negativo de PATH.