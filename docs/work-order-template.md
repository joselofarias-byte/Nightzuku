# Orden de trabajo — inteligencia de código

> Referencia humana. Los agentes no deben copiar esta plantilla manualmente: `bash tools/llm-workflow.sh start ...` genera y completa la orden estándar, el respaldo y la evidencia.

## Identificación

- Proyecto: Nightzuku
- Fecha:
- Responsable:
- Rama base:
- Commit base:
- Rama de trabajo:
- Objetivo:

## Respaldo previo

- [ ] `status.txt`
- [ ] `unstaged.patch`
- [ ] `staged.patch`
- [ ] `branches.txt`
- [ ] `worktrees.txt`
- [ ] `log.txt`
- [ ] bundle Git verificado
- [ ] archivo recuperable de untracked
- [ ] manifiesto SHA-256
- Ruta del respaldo:

## Investigación con CodeGraph

- Índice actualizado: sí / no / no necesario
- Versión:
- Símbolos consultados:
- Callers relevantes:
- Callees relevantes:
- Rutas o flujos trazados:
- Dependencias detectadas:
- Impacto probable del cambio:
- Archivos que requieren revisión manual:

## Revisión con Graphify

Completar sólo cuando la orden esté marcada como estructural o aporte una decisión global.

- Utilizado: sí / no
- Motivo:
- Comunidades o nodos revisados:
- Acoplamientos o zonas centrales detectadas:
- Informe consultado:
- Conclusión arquitectónica:

## Plan de modificación

- Archivos previstos:
- Cambios permitidos:
- Cambios expresamente excluidos:
- Riesgos:
- Estrategia de reversión:

## Validación

- [ ] revisión del diff
- [ ] pruebas unitarias pertinentes
- [ ] compilación pertinente
- [ ] comprobación funcional
- [ ] análisis de impacto posterior
- [ ] actualización de CodeGraph
- [ ] actualización de Graphify sólo si hubo cambio estructural importante

## Resultado

- Estado: completado / parcial / bloqueado / cancelado
- Archivos modificados:
- Pruebas ejecutadas:
- Build o APK:
- Commit final:
- Push realizado: sí / no
- PR:
- Hallazgos pendientes:
- Nota en Obsidian:

## Regla de cierre

La orden no se considera cerrada sólo porque CodeGraph o Graphify no detecten problemas. Deben revisarse el diff, las pruebas y la compilación correspondientes. Ningún commit, push o merge se presume autorizado. Los hooks bloquean commits sin una orden activa y agregan automáticamente la identificación de la orden y del agente.
