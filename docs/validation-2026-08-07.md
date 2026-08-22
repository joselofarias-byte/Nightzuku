# Validación real — 7 de agosto de 2026

## Entorno

- Debian PRoot sobre Termux/Android
- Arquitectura: ARM64/aarch64
- Rama: `chore/knowledge-graph-pilot`
- Nightzuku piloto HEAD validado: `c995ec8e7b1af0bd02e45feb5aee54ce94d3d122`
- Nightzuku original: **intacto**

## Resultado

Validación completa: **PASS**.

### Motor base

`tools/test-llm-workflow.sh`:

- PASS
- orden creada y cerrada correctamente;
- bundle, manifiesto, hooks y guardas funcionales.

### Swarm

`tools/test-swarm-workflow.sh`:

- PASS
- worktree de implementador creado;
- handoff reproducible sin commit;
- worktree de revisor creado desde el mismo HEAD base;
- cierre y cleanup seguros;
- no se reprodujo el anterior `rc=127`.

### Autodocumentación

`tools/test-system-docs.sh`:

- PASS
- `doctor` correcto;
- snapshots de inicio/checkpoint/cierre generados;
- historial persistente actualizado;
- idempotencia verificada;
- no se reprodujo el anterior `Broken pipe`.

### Dashboard real

Estado observado:

- `WORKTREE=0 change(s)`
- `WORK_ORDER=NONE`
- `SWARM=NONE`
- `HOOKS=.githooks`
- `CODEGRAPH=AVAILABLE`
- `GRAPHIFY=AVAILABLE`
- `TMUX=AVAILABLE`
- `CODEX=AVAILABLE`
- `CLAUDE=NOT_INSTALLED`
- `GEMINI=NOT_INSTALLED`

La ausencia local de Claude/Gemini no es un fallo del sistema: son backends opcionales.

### Obsidian

Publicación real exitosa en:

`/storage/emulated/0/Documents/Engineering-KB/Projects/Nightzuku/Operations`

### Identidad de motores compartidos

Los motores compartidos entre Nightzuku y Vega fueron verificados como idénticos por SHA-256:

- `tools/llm-workflow.sh`: `7f7746de9ae453e4ffe4b97e19b342d0a4947ec25354e1d598bb0a8226b035f9`
- `tools/swarm-workflow.sh`: `e25fdfb381780cefaf84abc4a595abe377b09b347d6b945cf26d75c5bd371e67`
- `tools/work.sh`: `aa7fe06554b8ba745ddecc9945c4617b863fd6d5e06e8ef3bfe73573e269eb8f`
- `tools/swarm.sh`: `ad077552949af9e2e197f820fd7101d51b4fef8d9817f7df50ef31da967947f5`
- `tools/system-docs.sh`: `995b51c8192dfd3f3fe05ad455efe1a038f9e5e72c357185f17db5ef6b1191fd`
- `tools/test-swarm-workflow.sh`: `67491a57fcaf31c6a62158dd58355b89e923f28db49f09ad1bc83557232ad5c3`
- `tools/test-system-docs.sh`: `9384fe9eb1403a40be39dbf7c62eaf4dfd06f4e3e7d2e5b11716f01f31aa43da`

## Seguridad

Huella del Nightzuku original antes y después:

`7b263456e79d1f5588b4f9e744fbb0bf88b33b25b6068961a4f92ac82f8fb709`

Resultado: **idéntica**.

No se instaló ni vendorizó SwarmForge externo. Muse Code no fue requerido ni instalado. No se hizo push ni merge durante la validación.

## Evidencia local

Directorio exportado por el validador:

`/storage/emulated/0/Download/Engineering-System-Validation-20260807-172547`

## Conclusión

El sistema de ingeniería autodocumentado queda **validado en el entorno real Debian PRoot/ARM64**. El PR puede permanecer en borrador hasta que exista autorización expresa para cualquier paso de integración/merge.
