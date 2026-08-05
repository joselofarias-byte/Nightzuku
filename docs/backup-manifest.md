# Manifiesto de respaldo — inteligencia de código

## Automatización

Los agentes no deben construir este respaldo manualmente. Al iniciar una tarea:

```bash
bash tools/llm-workflow.sh start --agent <llm> --objective "<objetivo>"
```

se crea una orden privada fuera del checkout con respaldo recuperable, evidencia de índices y `MANIFEST.sha256`. Los permisos se crean con `umask 077`.

## Principio

Los índices de CodeGraph y Graphify son derivados del código y se pueden reconstruir. El respaldo recuperable prioriza Git, parches, archivos untracked relevantes y evidencia textual.

## Contenido generado automáticamente

- estado del repositorio;
- diff unstaged binario;
- diff staged binario;
- ramas y upstreams;
- worktrees;
- historial reciente;
- bundle Git completo y verificado;
- inventario y archivo `tar.gz` de untracked no ignorados;
- rama, commit, origin y fecha;
- versión y estado de CodeGraph;
- versión y huellas de Graphify cuando existen;
- manifiesto SHA-256 de toda la orden.

## Checkpoints y cierre

`checkpoint` vuelve a capturar Git e índices. El hook `pre-commit` lo ejecuta automáticamente antes de todo commit autorizado.

```bash
bash tools/llm-workflow.sh checkpoint "antes del cambio crítico"
bash tools/llm-workflow.sh finish "resultado final"
```

`finish` captura el estado final, sincroniza CodeGraph, regenera Graphify sólo si la orden fue marcada `--structural`, exporta la evidencia disponible a Obsidian y cierra la orden.

## Exclusiones

No considerar datos esenciales:

```text
.codegraph/
graphify-out/
```

También quedan fuera como fuentes de verdad las bases SQLite, archivos `-wal`, `-shm`, cachés AST y temporales. Se reconstruyen mediante:

```bash
bash tools/knowledge-graph.sh index
bash tools/knowledge-graph.sh obsidian
```

El informe Markdown puede conservarse como fotografía documental; el HTML interactivo puede permanecer en el vault.

## Restauración

1. restaurar o clonar desde `backup/repository.bundle` o el remoto;
2. aplicar `staged.patch` y `unstaged.patch`;
3. restaurar `untracked.tar.gz` cuando exista;
4. verificar rama y commit con `repository-metadata.txt`;
5. validar `MANIFEST.sha256`;
6. reconstruir los índices sólo cuando vuelvan a ser necesarios.

## Regla

La fuente de verdad es Git más la evidencia de la orden. `.codegraph/`, `graphify-out/` y Obsidian no sustituyen al bundle, los parches ni los untracked recuperables.
