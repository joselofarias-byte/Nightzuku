# GEMINI.md

Follow `AGENTS.md` and `AI_WORKFLOW.md` as mandatory sources of truth.

Before any edit, Gemini CLI must run:

```bash
bash tools/llm-workflow.sh status
bash tools/llm-workflow.sh start --agent gemini --objective "<objective>"
```

Add `--structural` for architecture, modules, IPC, services, dependencies or central flows. Use CodeGraph before broad file searches, record findings with `note`, execute tests/builds through `run --`, never use `--no-verify`, and always `finish` or `abort`. Commit, push and merge require express user authorization.

Gemini CLI default workflow:
- Use minimal-change engineering.
- Avoid speculative refactors.
- Prefer Android/Kotlin/Java built-ins.
- Validate assumptions before modifying architecture.
- Do not introduce dependencies unless justified.
- Always check Android lifecycle, permissions, storage, Shizuku/root behavior and recording reliability.
- For Stellar/Nightzuku, always check shell command safety, root emulation limits, SELinux context and UID assumptions.

Before completion, provide changed files, validation performed, risks left and next safest action.
