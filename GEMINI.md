# GEMINI.md

Follow `START-HERE.md`, `AGENTS.md` and `AI_WORKFLOW.md` as mandatory sources of truth.

Before broad analysis or edits, Gemini CLI must run:

```bash
bash tools/system-docs.sh summary
bash tools/system-docs.sh doctor
```

If `SWARM_ROLE` is set, read `$SWARM_ROLE_PROMPT` and continue that master order without opening another one.

For normal work:

```bash
bash tools/work.sh start --agent gemini --objective "<objective>"
```

For work that justifies independent implementation/review:

```bash
bash tools/swarm.sh start --objective "<objective>"
```

Add `--structural` for architecture, modules, IPC, services, dependencies or central flows. Use CodeGraph before broad file searches, record findings with `tools/work.sh note`, execute tests/builds through `tools/work.sh run --`, never use `--no-verify`, and always close or abort through the same front door. Commit, push, merge and opening/closing PRs require express user authorization.

`llm-workflow.sh` and `swarm-workflow.sh` are internal stable engines; wrappers are required because they preserve system snapshots, persistent history and the Obsidian view automatically.

Gemini CLI default workflow:
- Use minimal-change engineering.
- Avoid speculative refactors.
- Prefer Android/Kotlin/Java built-ins.
- Validate assumptions before modifying architecture.
- Do not introduce dependencies unless justified.
- Always check Android lifecycle, permissions, storage, Shizuku/root behavior and recording reliability.
- For Stellar/Nightzuku, always check shell command safety, root emulation limits, SELinux context and UID assumptions.

Before completion, provide changed files, validation performed, risks left and next safest action.
