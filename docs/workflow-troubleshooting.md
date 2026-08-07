# Troubleshooting del sistema de ingeniería

Este documento registra fallos reales observados durante la validación del flujo para evitar volver a diagnosticarlos desde cero.

## `proot warning: can't sanitize binding /proc/self/fd/0`

Se observó al entrar a Debian PRoot desde Termux.

Estado: **warning tolerable mientras el proceso posterior continúe**.

No se considera por sí solo un fallo del flujo. La validación debe juzgar el código de salida y las comprobaciones posteriores.

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

1. detener la actualización ante repositorios piloto sucios u órdenes activas;
2. conservar la salida completa;
3. comparar la huella del Nightzuku original antes y después;
4. no hacer push ni merge;
5. no borrar worktrees con cambios;
6. convertir todo fallo reproducible en una prueba de regresión cuando sea razonable.

La validación del 7 de agosto de 2026 confirmó que el Nightzuku original permaneció intacto incluso cuando falló el test de autodocumentación.