# CLAUDE.md

Follow `AGENTS.md` and `AI_WORKFLOW.md` as mandatory sources of truth.

Before any edit, Claude Code must run:

```bash
bash tools/llm-workflow.sh status
bash tools/llm-workflow.sh start --agent claude --objective "<objective>"
```

Add `--structural` for architecture, modules, IPC, services, dependencies or central flows. Use CodeGraph before broad file searches, record findings with `note`, execute tests/builds through `run --`, never use `--no-verify`, and always `finish` or `abort`. Commit, push and merge require express user authorization.

Default behavior for Claude Code:
- Start in Ponytail mode.
- Prefer the smallest safe patch.
- Do not create new abstractions unless necessary.
- Identify root cause before editing.
- Verify with build/tests/logs when possible.
- Run an improve-style audit before finishing.
- For Stellar/Nightzuku, also check shell behavior, SELinux, root emulation and UID assumptions.

Never compromise recording reliability, data integrity, permissions, SAF/MediaStore, Shizuku binder lifecycle, root fallback, root emulation compatibility, shell command safety or Android SDK compatibility.
