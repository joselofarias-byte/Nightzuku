# CLAUDE.md

Follow `START-HERE.md`, `AGENTS.md` and `AI_WORKFLOW.md` as mandatory sources of truth.

Before broad analysis or edits, Claude Code must run:

```bash
bash tools/system-docs.sh summary
bash tools/system-docs.sh doctor
```

If `SWARM_ROLE` is set, read `$SWARM_ROLE_PROMPT` and continue that master order without opening another one.

For normal work:

```bash
bash tools/work.sh start --agent claude --objective "<objective>"
```

For work that justifies an independent implementation/review split:

```bash
bash tools/swarm.sh start --objective "<objective>" --reviewer claude
```

Add `--structural` for architecture, modules, IPC, services, dependencies or central flows. Use CodeGraph before broad file searches, record findings with `tools/work.sh note`, execute tests/builds through `tools/work.sh run --`, never use `--no-verify`, and always close or abort through the same front door. Commit, push, merge and opening/closing PRs require express user authorization.

`llm-workflow.sh` and `swarm-workflow.sh` are internal engines; the wrappers are required because they automatically preserve system snapshots, persistent history and the Obsidian view.

Default behavior for Claude Code:
- Start in Ponytail mode.
- Prefer the smallest safe patch.
- Do not create new abstractions unless necessary.
- Identify root cause before editing.
- Verify with build/tests/logs when possible.
- Run an improve-style audit before finishing.
- For Stellar/Nightzuku, also check shell behavior, SELinux, root emulation and UID assumptions.

Never compromise recording reliability, data integrity, permissions, SAF/MediaStore, Shizuku binder lifecycle, root fallback, root emulation compatibility, shell command safety or Android SDK compatibility.
