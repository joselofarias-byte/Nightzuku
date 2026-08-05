# Manifiesto de respaldo — inteligencia de código

## Principio

Los índices de CodeGraph y Graphify son derivados del código y se pueden reconstruir. El respaldo recuperable debe priorizar Git, parches, archivos untracked relevantes y evidencia textual.

## Contenido obligatorio del respaldo Git

- estado del repositorio;
- diff unstaged binario;
- diff staged binario;
- ramas y upstreams;
- worktrees;
- historial reciente;
- bundle Git completo;
- inventario de untracked;
- manifiesto SHA-256.

## Evidencia adicional recomendada

Guardar un archivo `code-intelligence.txt` con una captura equivalente a:

```bash
{
  date -Iseconds
  printf 'repository=%s\n' "$(git rev-parse --show-toplevel)"
  printf 'branch=%s\n' "$(git branch --show-current)"
  printf 'commit=%s\n' "$(git rev-parse HEAD)"
  printf 'codegraph_version='; codegraph --version || true
  printf 'graphify_version='; graphify --version || true
  codegraph status "$(git rev-parse --show-toplevel)" || true
} > code-intelligence.txt
```

Cuando existan artefactos Graphify, guardar sólo sus huellas y ubicación:

```bash
{
  printf 'vault=%s\n' '/storage/emulated/0/Documents/Engineering-KB/Projects/Nightzuku'
  sha256sum graphify-out/GRAPH_REPORT.md graphify-out/graph.html 2>/dev/null || true
} > graphify-evidence.txt
```

El informe Markdown puede copiarse al respaldo si se necesita una fotografía documental de la arquitectura. El HTML interactivo puede permanecer únicamente en el vault.

## Exclusiones recomendadas

Excluir de TBM y de otros respaldos de archivos:

```text
.codegraph/
graphify-out/
```

También excluir bases SQLite, archivos `-wal`, `-shm`, cachés AST y temporales generados. Estas exclusiones no afectan la recuperación porque los índices se regeneran mediante:

```bash
bash tools/knowledge-graph.sh index
bash tools/knowledge-graph.sh obsidian
```

## Restauración

1. restaurar o clonar el repositorio desde bundle/remoto;
2. aplicar staged y unstaged patches;
3. restaurar untracked relevantes;
4. verificar rama y commit;
5. validar el entorno de Debian;
6. reconstruir índices sólo cuando vuelvan a ser necesarios.

## Regla

No considerar `.codegraph/` ni `graphify-out/` como fuentes de verdad. La fuente de verdad es el repositorio Git y la evidencia de la orden de trabajo.
