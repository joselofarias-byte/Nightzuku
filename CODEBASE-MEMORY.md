# Codebase Memory MCP — backend candidato de inteligencia de código

`DeusData/codebase-memory-mcp` se integra como **backend candidato y complementario**, no como un segundo índice obligatorio ejecutándose siempre junto a CodeGraph.

Versión evaluada/fijada inicialmente: **0.8.1**. Licencia upstream: **MIT**.

## Decisión de arquitectura

### CodeGraph sigue siendo el backend primario

CodeGraph ya está validado en este entorno y continúa siendo la ruta diaria para:

- símbolos;
- callers/callees;
- dependencias;
- trazado;
- impacto básico;
- MCP existente para agentes.

### Codebase Memory queda como candidato avanzado

Se usa cuando aporte una capacidad concreta que justifique un segundo índice temporal/local:

- búsqueda semántica local con embeddings incluidos;
- Hybrid LSP para Kotlin/Java y otros lenguajes;
- `detect_changes` con blast radius/riesgo;
- Cypher de lectura;
- búsqueda de clones/relaciones semánticas;
- arquitectura enriquecida;
- validación experimental de precisión contra CodeGraph.

No se promueve a backend primario sólo por benchmarks upstream. La promoción requiere evidencia sobre nuestros repositorios reales.

## Qué se descarta por duplicidad

Por defecto **NO** se activa:

- auto-index al iniciar agentes;
- `auto_watch`/watcher permanente;
- daemon compartido;
- instalación automática en configuraciones de Claude/Codex/Gemini/etc.;
- skills/agentes propios de Codebase Memory;
- `manage_adr` como segunda memoria arquitectónica;
- UI 3D como requisito;
- un segundo sistema de documentación o historial.

`START-HERE.md`, `AI_WORKFLOW.md`, `llm-workflow`, `system-docs`, Obsidian y CodeGraph siguen siendo canónicos.

## Integración segura

La entrada gobernada es:

```bash
bash tools/code-intel.sh status
```

Instalación del candidato:

```bash
bash tools/code-intel.sh install-cbm
```

La instalación:

- descarga una **release fijada**, no `latest`;
- selecciona Linux ARM64/AMD64 portable;
- descarga `checksums.txt` de la misma release;
- verifica SHA-256 antes de extraer;
- instala el binario fuera del checkout;
- no ejecuta `curl | bash`;
- no ejecuta `codebase-memory-mcp install`;
- no modifica configuraciones de agentes;
- no agrega nada a `PATH` global.

Ubicación por defecto:

```text
$HOME/.local/share/engineering-tools/codebase-memory-mcp/0.8.1/
```

## Estado y caché

Cada repositorio usa una caché candidata propia por defecto:

```text
$HOME/.local/state/code-intel/cbm/<repo-id>/
```

Esto evita que un agente de un proyecto vea accidentalmente el grafo de otro proyecto. Los índices son regenerables y no son backup.

El wrapper fija:

```text
CBM_ALLOWED_ROOT=<repository-root>
CBM_CACHE_DIR=<repo-specific-cache>
CBM_DIAGNOSTICS=0
CBM_LOG_LEVEL=warn
```

No se habilitan watchers ni daemon en el uso normal.

## Uso CLI one-shot

Indexar el candidato dentro de una orden activa:

```bash
bash tools/code-intel.sh cbm-index
```

Arquitectura:

```bash
bash tools/code-intel.sh cbm get_architecture --project <project>
```

Buscar símbolos:

```bash
bash tools/code-intel.sh cbm search_graph \
  --project <project> \
  --name-pattern '.*Shizuku.*'
```

Trazar llamadas:

```bash
bash tools/code-intel.sh cbm trace_path \
  --project <project> \
  --function-name <name> \
  --direction both
```

Detectar impacto del diff:

```bash
bash tools/code-intel.sh cbm detect_changes --project <project>
```

El modo `cli` de Codebase Memory es deliberado: ejecuta una sola consulta y termina, sin daemon persistente.

## MCP experimental

Sólo si una tarea justifica probar el MCP candidato:

```bash
bash tools/code-intel.sh cbm-mcp
```

El wrapper conserva raíz y caché confinadas al repositorio. Esto sigue siendo un piloto: no reemplaza la configuración MCP de CodeGraph ni se registra globalmente.

## Regla de selección

1. **CodeGraph primero** para trabajo diario.
2. **Codebase Memory** sólo cuando se necesite una de sus capacidades diferenciales o para comparación controlada.
3. Si Codebase Memory demuestra mejores resultados de forma repetida en Nightzuku/Vega, se puede promover y retirar CodeGraph en una migración separada.
4. No mantener dos índices obligatorios por inercia.

## Criterios para promoción

Antes de sustituir CodeGraph medir en repos reales:

- exactitud de callers/callees;
- resolución Kotlin/Java;
- cobertura de símbolos;
- calidad de impacto/detect_changes;
- tiempo de indexado incremental;
- RAM pico;
- tamaño en disco;
- tokens/llamadas requeridos por agentes;
- estabilidad en Debian PRoot ARM64;
- facilidad de recuperación/actualización.

Hasta completar esa comparación, el estado es **CANDIDATE / SHADOW**, no `PRIMARY`.
