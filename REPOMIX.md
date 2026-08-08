# Repomix — transporte de contexto para LLM

Repomix se integra como una **capa opcional de transporte de contexto**, no como reemplazo de CodeGraph, del sistema de órdenes ni de los respaldos.

Versión fijada: **1.18.0**. Licencia upstream: **MIT**. Requisito upstream: **Node.js >= 22**.

## Qué aporta

Repomix empaqueta archivos seleccionados del repositorio en un único artefacto orientado a LLM, con:

- formatos Markdown, XML, JSON o texto;
- conteo de tokens y presupuesto máximo;
- compresión estructural mediante Tree-sitter;
- respeto por `.gitignore` y `.repomixignore`;
- detección de secretos mediante Secretlint;
- lectura/búsqueda incremental cuando se usa como MCP.

## Qué NO reemplaza

- **CodeGraph**: sigue siendo la herramienta principal para símbolos, callers/callees, dependencias e impacto.
- **Graphify**: sigue reservado a arquitectura y refactors grandes.
- **`system-docs.sh`**: documenta estado operativo e historial; no transporta el código completo.
- **`llm-workflow.sh`**: continúa siendo la única fuente de verdad para orden, backup, evidencia, tests y cierre.
- **Repowise**: permanece como referencia/piloto de análisis; no se incorpora una segunda wiki/grafo obligatorio.

La decisión es usar Repomix cuando haga falta **mover contexto de código** entre herramientas o entregar una vista autocontenida a un LLM que no tenga acceso directo al checkout.

## Instalación aislada

No se instala dentro del proyecto ni se agrega como dependencia de la aplicación.

```bash
bash tools/context-pack.sh install
bash tools/context-pack.sh doctor
```

El wrapper instala exactamente Repomix 1.18.0 bajo:

```text
$HOME/.local/share/engineering-tools/repomix/1.18.0/
```

La instalación usa `npm --ignore-scripts`, no ejecuta scripts de ciclo de vida del paquete y exige Node.js >=22.

## Uso dentro de una orden

Repomix no debe generar artefactos de trabajo fuera de una orden activa.

Modo recomendado, compacto:

```bash
bash tools/work.sh context \
  --mode compact \
  --include 'app/src/**/*.kt,docs/**/*.md' \
  --name architecture
```

Modo completo cuando se necesitan implementaciones completas:

```bash
bash tools/work.sh context \
  --mode full \
  --include 'app/src/main/**/*.kt' \
  --name implementation
```

Sólo estructura/metadata:

```bash
bash tools/work.sh context --mode metadata --name inventory
```

Por defecto se usa Markdown y un presupuesto de 120.000 tokens. Puede bajarse explícitamente:

```bash
bash tools/work.sh context --mode compact --token-budget 60000 --name focused
```

Los resultados se escriben fuera del checkout, dentro de la orden activa:

```text
<work-order>/evidence/context/
```

Cada paquete conserva:

- salida Repomix;
- log de ejecución;
- SHA-256;
- configuración JSON segura usada en esa ejecución;
- metadata con HEAD, modo, filtros, tamaño y código de salida;
- una nota en la orden de trabajo.

## Seguridad

El wrapper mantiene siempre habilitado el security check de Repomix. Se rechazan explícitamente `--no-security-check`, `--remote`, `--remote-trust-config`, `--force` y cualquier `--config` suministrado por el agente.

### Configuración local del repositorio: no se ejecuta

Repomix puede cargar configuraciones de proyecto, incluidas variantes JavaScript/TypeScript capaces de ejecutar código. Para que un archivo agregado más adelante por upstream no amplíe silenciosamente la superficie de confianza, el wrapper **no usa la configuración Repomix del checkout**.

En cada paquete genera dentro de la evidencia una configuración JSON mínima:

```json
{
  "security": {
    "enableSecurityCheck": true
  }
}
```

y llama a Repomix mediante `--config <archivo-seguro>`. El mismo criterio se aplica al servidor MCP. Por lo tanto una futura `repomix.config.js`, `.mjs`, `.ts`, `.json` u otra configuración del proyecto no se convierte automáticamente en código confiable del flujo.

`.repomixignore` excluye además:

- `.env` y variantes;
- propiedades locales y de firma;
- keystores/certificados/claves;
- `google-services.json`;
- credenciales JSON;
- outputs de build;
- índices CodeGraph/Graphify;
- worktrees locales;
- APK/AAB/JAR/archives/databases;
- outputs previos de Repomix.

Secretlint es una defensa heurística, **no una garantía de que el artefacto sea inocuo**. Revisar el paquete antes de compartirlo fuera del entorno local.

## Repositorios remotos

El wrapper **no expone `--remote` ni `--remote-trust-config`**. Un repositorio externo debe clonarse/auditarse primero y luego empaquetarse como directorio local.

La razón es que Repomix documenta que un `repomix.config.ts/js/mjs` remoto puede ejecutar código y que processors/configuración pueden leer rutas externas. Evitamos esa superficie por diseño.

## MCP

Para un agente compatible con MCP:

```bash
bash tools/context-pack.sh mcp
```

Sólo funciona con una orden activa. El wrapper genera primero la configuración segura y ejecuta conceptualmente:

```text
repomix --config <safe-config> --mcp --sandbox <repository-root>
```

En el sandbox de Repomix:

- las rutas quedan confinadas al repositorio;
- se rechazan escapes mediante rutas absolutas, `..` y symlinks fuera de raíz;
- sólo se registran herramientas de lectura/pack local;
- remote packing y generación de skills quedan deshabilitados.

Esto es defensa en profundidad a nivel aplicación, no un sandbox del sistema operativo.

## Modos

| Modo | Uso | Comportamiento |
|---|---|---|
| `compact` | contexto amplio con costo de tokens controlado | usa Tree-sitter `--compress` |
| `full` | implementación exacta de un conjunto pequeño | conserva contenido completo |
| `metadata` | inventario/estructura | usa `--no-files` |

## Política de uso

1. CodeGraph primero para localizar impacto y archivos relevantes.
2. Repomix después, si hace falta transportar ese contexto a otro modelo o mantener un artefacto autocontenido.
3. Preferir `--include` para no empaquetar un repositorio completo sin necesidad.
4. Preferir `compact` salvo que el receptor necesite implementación exacta.
5. Nunca considerar el paquete Repomix como backup o fuente de verdad.
6. No generar paquetes automáticamente en todas las tareas: hacerlo sólo cuando aporte contexto real y ahorre lecturas/tokens posteriores.
7. Nunca desactivar el escaneo de secretos ni confiar automáticamente en configuración Repomix proveniente del checkout.
