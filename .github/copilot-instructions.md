# Mandatory repository workflow

Read and obey `START-HERE.md`, `AI_WORKFLOW.md` and `AGENTS.md`.

Before broad analysis or editing, run:

```bash
bash tools/system-docs.sh summary
bash tools/system-docs.sh doctor
```

For normal work start through `bash tools/work.sh start --agent copilot --objective "<objective>"`. For complex work use `bash tools/swarm.sh start --objective "<objective>"`.

If `SWARM_ROLE` is set, read `$SWARM_ROLE_PROMPT` and do not open another order. Use CodeGraph before broad searches, log validation with `tools/work.sh run --`, never bypass hooks, and close/abort through the same front door. The wrappers automatically capture system snapshots, persistent history and the Obsidian view.

Commit, push, merge and opening/closing PRs require express user authorization.
