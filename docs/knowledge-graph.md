# Code intelligence

This repository uses local-only generated indexes:

- CodeGraph: symbol, call, dependency and impact index for coding agents.
- Graphify: architecture map and human-readable report.
- Obsidian: central engineering knowledge base; only reports are exported.

Generated data is intentionally excluded from Git. Source code is processed locally.

## Commands

```bash
bash tools/knowledge-graph.sh install
bash tools/knowledge-graph.sh index
bash tools/knowledge-graph.sh status
bash tools/knowledge-graph.sh obsidian
```

For routine work:

```bash
bash tools/knowledge-graph.sh sync
```

The checkout must be on Debian's native filesystem. Do not create the SQLite index on `/sdcard` or `/storage/emulated`.
