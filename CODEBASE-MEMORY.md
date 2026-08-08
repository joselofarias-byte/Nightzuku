# Codebase Memory MCP — backend candidato de inteligencia de código

`DeusData/codebase-memory-mcp` se integra como **backend candidato y complementario**, no como un segundo índice obligatorio ejecutándose siempre junto a CodeGraph.

Versión fijada: **0.9.0**. Licencia upstream: **MIT**.

La versión 0.9.0 es la release estable actual verificada al integrar esta capa. No se conserva 0.8.1: upstream declara las versiones `<0.9` fuera de soporte y 0.9.0 incluye correcciones relevantes de indexado, memoria, Kotlin/Java, CLI y `detect_changes`.

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

- descarga la release **v0.9.0**, no `latest`;
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
$HOME/.local/share/engineering-tools/codebase-memory-mcp/0.9.0/
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
CBM_WORKERS=4
CBM_MEM_BUDGET_MB=1024
```

El límite de workers/memoria es deliberado para no dejar que el PRoot tome la cantidad de CPU/RAM del host como permiso para consumirla completa.

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

El modo `cli` es el preferido: ejecuta una consulta puntual y termina sin crear un daemon persistente. Los comandos gobernados se registran bajo `evidence/code-intel/cbm/runs/` de la orden activa.

## MCP experimental

Sólo si una tarea justifica probar el MCP candidato:

```bash
bash tools/code-intel.sh cbm-mcp
```

Antes de arrancarlo, el wrapper fija `auto_index=false` y `auto_watch=false`. Conserva raíz y caché confinadas al repositorio y no registra el servidor globalmente.

### Red del MCP

Indexado, consultas y búsqueda semántica son locales. Sin embargo, upstream documenta que, **después de `initialize` del MCP**, inicia un chequeo best-effort de nueva versión contra la metadata pública de releases de GitHub. No envía código, rutas, índices, consultas ni telemetría del proyecto, pero sí existe esa conexión HTTPS de actualización.

Por esa razón, **CLI one-shot es el modo predeterminado** de nuestra integración. El MCP candidato es explícito y su arranque deja esta característica documentada en la evidencia.

## Regla de selección

1. **CodeGraph primero** para trabajo diario.
2. **Codebase Memory** sólo cuando se necesite una capacidad diferencial o para comparación controlada.
3. Si demuestra mejores resultados de forma repetida en Nightzuku/Vega, se puede promover y retirar CodeGraph en una migración separada.
4. No mantener dos índices obligatorios por inercia.

## Criterios para promoción

Antes de sustituir CodeGraph medir en repos reales:

- exactitud de callers/callees;
- resolución Kotlin/Java;
- cobertura de símbolos;
- calidad de impacto/detect_changes;
- calidad de búsqueda semántica;
- tiempo de indexado inicial e incremental;
- RAM pico;
- tamaño en disco;
- tokens/llamadas requeridos por agentes;
- estabilidad en Debian PRoot ARM64;
- facilidad de recuperación/actualización.

Hasta completar esa comparación, el estado es **CANDIDATE / SHADOW**, no `PRIMARY`.
