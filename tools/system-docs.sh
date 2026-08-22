#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

export PATH="$HOME/.local/bin:$PATH"

fail() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 1
}

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
REPO_ROOT="$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel 2>/dev/null || true)"
[[ -n "$REPO_ROOT" ]] || fail "must run inside a Git repository"
GIT_DIR="$(git -C "$REPO_ROOT" rev-parse --absolute-git-dir)"
ACTIVE_FILE="$GIT_DIR/llm-work-current"
LAST_FILE="$GIT_DIR/llm-work-last"
SWARM_CURRENT="$GIT_DIR/swarm-current"
SWARM_LAST="$GIT_DIR/swarm-last"
CONTEXT_TOOL="$REPO_ROOT/tools/context-pack.sh"
CODE_INTEL_TOOL="$REPO_ROOT/tools/code-intel.sh"

origin_raw="$(git -C "$REPO_ROOT" remote get-url origin 2>/dev/null || true)"
sanitize_origin() {
  printf '%s' "$1" | sed -E 's#(https?://)[^/@]+@#\1#; s#(ssh://)[^/@]+@#\1#'
}
origin="$(sanitize_origin "$origin_raw")"
project="$(basename "$REPO_ROOT")"
repo_key="$project"
if [[ -n "$origin" ]]; then
  repo_key="$(printf '%s' "$origin" | sed -E 's#^[^:]+://##; s#^[^@]+@[^:]+:##; s#\.git$##; s#/#-#g')"
fi
repo_key="$(printf '%s' "$repo_key" | tr -cs '[:alnum:]_.-' '-' | sed -E 's/^-+//; s/-+$//')"
STATE_BASE="${LLM_WORK_ROOT:-$HOME/.local/state/llm-work}"
STATE_ROOT="$STATE_BASE/$repo_key"
HISTORY="$STATE_ROOT/HISTORY.md"

usage() {
  cat <<'USAGE'
Usage: bash tools/system-docs.sh <command> [args]

Commands:
  summary                 concise human/LLM dashboard
  snapshot [FILE]         write a full Markdown snapshot; stdout if FILE omitted
  history                 show persistent completed/aborted work history
  record STATUS SUMMARY   append active or most recently closed order to persistent history
  publish                 publish snapshot + history + START-HERE to Obsidian when available
  doctor                  validate the documentation/workflow installation
  explain                 print START-HERE.md
USAGE
}

now_iso() { date -Iseconds; }

presence() {
  command -v "$1" >/dev/null 2>&1 && printf 'AVAILABLE' || printf 'NOT_INSTALLED'
}

repomix_state() {
  if [[ ! -f "$CONTEXT_TOOL" ]]; then
    printf 'NOT_CONFIGURED'
    return
  fi
  local line
  line="$(bash "$CONTEXT_TOOL" status --brief 2>/dev/null | sed -n '1p' || true)"
  [[ -n "$line" ]] && printf '%s' "${line#REPOMIX=}" || printf 'UNKNOWN'
}

cbm_state() {
  if [[ ! -f "$CODE_INTEL_TOOL" ]]; then
    printf 'NOT_CONFIGURED'
    return
  fi
  local line
  line="$(bash "$CODE_INTEL_TOOL" status 2>/dev/null | sed -n '/^CBM=/p' | head -n1 || true)"
  [[ -n "$line" ]] && printf '%s' "${line#CBM=}" || printf 'UNKNOWN'
}

active_order() {
  [[ -s "$ACTIVE_FILE" ]] && cat "$ACTIVE_FILE" || true
}

last_order() {
  [[ -s "$LAST_FILE" ]] && cat "$LAST_FILE" || true
}

swarm_state() {
  if [[ -s "$SWARM_CURRENT" ]]; then
    printf 'ACTIVE:%s' "$(cat "$SWARM_CURRENT")"
  elif [[ -s "$SWARM_LAST" ]]; then
    printf 'NO_ACTIVE;LAST:%s' "$(cat "$SWARM_LAST")"
  else
    printf 'NONE'
  fi
}

summary() {
  local active last
  active="$(active_order)"
  last="$(last_order)"
  printf 'PROJECT=%s\n' "$project"
  printf 'BRANCH=%s\n' "$(git -C "$REPO_ROOT" branch --show-current)"
  printf 'HEAD=%s\n' "$(git -C "$REPO_ROOT" rev-parse --short=12 HEAD)"
  printf 'WORKTREE=%s\n' "$(git -C "$REPO_ROOT" status --porcelain=v1 | wc -l | tr -d ' ') change(s)"
  printf 'WORK_ORDER=%s\n' "${active:-NONE}"
  printf 'LAST_ORDER=%s\n' "${last:-NONE}"
  printf 'SWARM=%s\n' "$(swarm_state)"
  printf 'HOOKS=%s\n' "$(git -C "$REPO_ROOT" config --get core.hooksPath || printf 'NOT_CONFIGURED')"
  printf 'CODE_INTEL_PRIMARY=CodeGraph:%s\n' "$(presence codegraph)"
  printf 'CODE_INTEL_CANDIDATE=CodebaseMemory:%s\n' "$(cbm_state)"
  printf 'GRAPHIFY=%s\n' "$(presence graphify)"
  printf 'REPOMIX=%s\n' "$(repomix_state)"
  printf 'TMUX=%s\n' "$(presence tmux)"
  printf 'CODEX=%s CLAUDE=%s GEMINI=%s\n' "$(presence codex)" "$(presence claude)" "$(presence gemini)"
  printf 'DOCS=START-HERE.md -> AI_WORKFLOW.md -> AGENTS.md\n'
  if [[ -n "$active" ]]; then
    printf 'NEXT=continue the active order; do not start another one\n'
    printf 'CODE_INTEL=bash tools/code-intel.sh status\n'
    printf 'CONTEXT=bash tools/work.sh context --mode compact --include <globs> --name <name>\n'
  else
    printf 'NEXT_NORMAL=bash tools/work.sh start --agent <agent> --objective "<objective>"\n'
    printf 'NEXT_COMPLEX=bash tools/swarm.sh start --objective "<objective>"\n'
  fi
}

render_snapshot() {
  local active last hooks changes codegraph_status=""
  active="$(active_order)"
  last="$(last_order)"
  hooks="$(git -C "$REPO_ROOT" config --get core.hooksPath || true)"
  changes="$(git -C "$REPO_ROOT" status --short --branch)"

  if command -v codegraph >/dev/null 2>&1; then
    codegraph_status="$(codegraph status "$REPO_ROOT" 2>&1 | sed -n '1,25p' || true)"
  fi

  printf '# System snapshot — %s\n\n' "$project"
  printf 'Generated: %s\n\n' "$(now_iso)"
  printf '## Repository\n\n'
  printf -- '- Path: %s\n' "$REPO_ROOT"
  printf -- '- Origin: %s\n' "${origin:-NONE}"
  printf -- '- Branch: %s\n' "$(git -C "$REPO_ROOT" branch --show-current)"
  printf -- '- HEAD: %s\n' "$(git -C "$REPO_ROOT" rev-parse HEAD)"
  printf -- '- Hooks: %s\n\n' "${hooks:-NOT_CONFIGURED}"
  printf '### Git status\n\n~~~text\n%s\n~~~\n\n' "$changes"

  printf '## Workflow state\n\n'
  printf -- '- Active work order: %s\n' "${active:-NONE}"
  printf -- '- Last work order: %s\n' "${last:-NONE}"
  printf -- '- Swarm: %s\n' "$(swarm_state)"
  printf -- '- Persistent history: %s\n\n' "$HISTORY"

  printf '## Available tooling\n\n'
  printf '| Tool | State | Role |\n|---|---|---|\n'
  printf '| CodeGraph | %s | PRIMARY code index |\n' "$(presence codegraph)"
  printf '| Codebase Memory MCP | %s | CANDIDATE/SHADOW code index |\n' "$(cbm_state)"
  printf '| Graphify | %s | occasional structural visualization |\n' "$(presence graphify)"
  printf '| Repomix context transport | %s | selected-code transport |\n' "$(repomix_state)"
  printf '| tmux | %s | optional sessions |\n' "$(presence tmux)"
  printf '| Codex CLI | %s | agent backend |\n' "$(presence codex)"
  printf '| Claude CLI | %s | agent backend |\n' "$(presence claude)"
  printf '| Gemini CLI | %s | agent backend |\n' "$(presence gemini)"
  printf '| GitHub CLI | %s | repository operations |\n\n' "$(presence gh)"

  cat <<'SNAP'
## Canonical layers

1. START-HERE.md — human/LLM entry point.
2. AGENTS.md — repository engineering rules.
3. AI_WORKFLOW.md — mandatory operational policy.
4. tools/llm-workflow.sh — stable engine for order, backup, evidence, tests and close.
5. tools/work.sh — canonical self-documenting front door for single-agent work.
6. tools/swarm-workflow.sh — stable two-role orchestration engine.
7. tools/swarm.sh — canonical self-documenting front door for swarm work.
8. CodeGraph — PRIMARY code index for daily relationships and impact.
9. tools/code-intel.sh / Codebase Memory — CANDIDATE/SHADOW advanced index; one-shot CLI by default.
10. Repomix / tools/context-pack.sh — optional context transport with token budgeting and secret scanning.
11. Graphify — occasional structural visualization.
12. Obsidian — human-readable mirror, never the source of truth.
13. Muse Code — optional backend only; not required.

## Duplication policy

Do not introduce a second implementation for backups, work orders, test logs, agent constitutions, handoffs or mandatory code indexing unless the existing canonical layer is intentionally replaced and the migration is documented.

Known decisions:

- TBM: historical; do not duplicate current work-order backups.
- SwarmForge: concepts absorbed; external runtime not vendored.
- Loop Engineering: reference for patterns; not a second mandatory runtime.
- Repowise: reference/pilot while CodeGraph covers daily graph needs.
- Codebase Memory MCP: candidate only. No auto-watch, global agent mutation or mandatory parallel indexing; promotion requires real-repo evidence.
- Repomix: context transport only; not a graph, backup, wiki or mandatory per-task step.
- Graphify: not run by default.
- Muse Code: optional and gated by real platform compatibility.

## CodeGraph status

~~~text
SNAP
  printf '%s\n' "${codegraph_status:-NOT_AVAILABLE}"
  printf '~~~\n\n## Recent work history\n\n'
  if [[ -s "$HISTORY" ]]; then
    tail -n 40 "$HISTORY"
  else
    printf 'No recorded completed/aborted orders yet.\n'
  fi

  cat <<'SNAP'

## Safe commands

~~~bash
bash tools/system-docs.sh summary
bash tools/system-docs.sh doctor
bash tools/code-intel.sh status
bash tools/context-pack.sh status
bash tools/work.sh status
bash tools/swarm.sh status
~~~

Read START-HERE.md when no other context is available.
SNAP
}

snapshot() {
  local out="${1:-}"
  if [[ -z "$out" ]]; then
    render_snapshot
    return
  fi
  mkdir -p "$(dirname "$out")"
  render_snapshot > "$out"
  printf 'SNAPSHOT=%s\n' "$out"
}

history() {
  if [[ -s "$HISTORY" ]]; then
    cat "$HISTORY"
  else
    printf 'No persistent history yet: %s\n' "$HISTORY"
  fi
}

record() {
  local status="${1:-}"; shift || true
  local summary_text="${*:-No summary supplied.}"
  [[ "$status" == "closed" || "$status" == "aborted" ]] || fail "record STATUS must be closed or aborted"
  local order agent objective base_head line order_id
  order="$(active_order)"
  [[ -n "$order" ]] || order="$(last_order)"
  [[ -n "$order" && -d "$order" ]] || fail "record requires an active or most recently closed work order"
  order_id="$(basename "$order")"
  if [[ -s "$HISTORY" ]] && grep -Fq "| \`$order_id\` |" "$HISTORY"; then
    printf 'HISTORY_ALREADY_RECORDED=%s\n' "$order_id"
    return 0
  fi
  agent="$(cat "$order/AGENT.txt" 2>/dev/null || printf 'unknown')"
  objective="$(cat "$order/OBJECTIVE.txt" 2>/dev/null || printf 'unknown')"
  base_head="$(cat "$order/BASE-HEAD.txt" 2>/dev/null || printf 'unknown')"
  summary_text="$(printf '%s' "$summary_text" | tr '\n\r' '  ')"
  objective="$(printf '%s' "$objective" | tr '\n\r' '  ')"

  mkdir -p "$STATE_ROOT"
  chmod 700 "$STATE_BASE" "$STATE_ROOT" 2>/dev/null || true
  if [[ ! -s "$HISTORY" ]]; then
    cat > "$HISTORY" <<'HEAD'
# Work history

This file is generated outside the checkout by tools/system-docs.sh. It is an operational index, not a replacement for each work order's evidence.

| Closed at | Status | Agent | Objective | Base → final | Summary | Order |
|---|---|---|---|---|---|---|
HEAD
  fi
  printf -v line '| %s | %s | %s | %s | `%s` → `%s` | %s | `%s` |\n' \
    "$(now_iso)" "$status" "$agent" \
    "${objective//|/\\|}" "${base_head:0:12}" "$(git -C "$REPO_ROOT" rev-parse --short=12 HEAD)" \
    "${summary_text//|/\\|}" "$order_id"
  printf '%s' "$line" >> "$HISTORY"
  printf 'HISTORY_UPDATED=%s\n' "$HISTORY"
}

publish() {
  local vault="${OBSIDIAN_VAULT:-/storage/emulated/0/Documents/Engineering-KB}"
  if [[ ! -d "$vault" || ! -w "$vault" ]]; then
    printf 'OBSIDIAN_PUBLISH=SKIPPED vault unavailable or not writable: %s\n' "$vault"
    return 0
  fi
  local out="$vault/Projects/$project/Operations"
  mkdir -p "$out"
  render_snapshot > "$out/System Status.md"
  cp "$REPO_ROOT/START-HERE.md" "$out/START HERE.md"
  [[ -f "$REPO_ROOT/REPOMIX.md" ]] && cp "$REPO_ROOT/REPOMIX.md" "$out/Repomix Context Packs.md"
  [[ -f "$REPO_ROOT/CODEBASE-MEMORY.md" ]] && cp "$REPO_ROOT/CODEBASE-MEMORY.md" "$out/Codebase Memory Candidate.md"
  if [[ -s "$HISTORY" ]]; then
    cp "$HISTORY" "$out/Work History.md"
  else
    printf '# Work history\n\nNo completed/aborted orders recorded yet.\n' > "$out/Work History.md"
  fi
  printf 'OBSIDIAN_PUBLISH=%s\n' "$out"
}

doctor() {
  local missing=0 path
  local required=(
    START-HERE.md
    AGENTS.md
    AI_WORKFLOW.md
    SWARM_WORKFLOW.md
    REPOMIX.md
    CODEBASE-MEMORY.md
    .repomixignore
    .cbmignore
    tools/llm-workflow.sh
    tools/work.sh
    tools/swarm-workflow.sh
    tools/swarm.sh
    tools/context-pack.sh
    tools/code-intel.sh
    tools/system-docs.sh
    tools/test-llm-workflow.sh
    tools/test-swarm-workflow.sh
    tools/test-context-pack.sh
    tools/test-code-intel.sh
    tools/test-system-docs.sh
    .githooks/pre-commit
    .githooks/commit-msg
  )
  for path in "${required[@]}"; do
    if [[ ! -f "$REPO_ROOT/$path" ]]; then
      printf 'MISSING=%s\n' "$path"
      missing=1
    fi
  done

  for path in tools/llm-workflow.sh tools/work.sh tools/swarm-workflow.sh tools/swarm.sh tools/context-pack.sh tools/code-intel.sh tools/system-docs.sh tools/test-llm-workflow.sh tools/test-swarm-workflow.sh tools/test-context-pack.sh tools/test-code-intel.sh tools/test-system-docs.sh .githooks/pre-commit .githooks/commit-msg; do
    [[ -f "$REPO_ROOT/$path" ]] && bash -n "$REPO_ROOT/$path"
  done

  local hooks
  hooks="$(git -C "$REPO_ROOT" config --get core.hooksPath || true)"
  printf 'core.hooksPath=%s\n' "${hooks:-NOT_CONFIGURED}"
  [[ "$hooks" == ".githooks" ]] || printf 'WARNING=Git hooks are not activated; run: bash tools/llm-workflow.sh install\n'

  if [[ -e "$REPO_ROOT/.swarm-forge" || -d "$REPO_ROOT/swarm-forge" ]]; then
    printf 'WARNING=external SwarmForge runtime detected; review for duplication\n'
  else
    printf 'external_swarmforge_runtime=NOT_VENDORED\n'
  fi

  printf '%s\n' "$(bash "$CONTEXT_TOOL" status --brief 2>/dev/null || printf 'REPOMIX=UNKNOWN')"
  bash "$CODE_INTEL_TOOL" doctor

  [[ "$missing" -eq 0 ]] || fail "documentation/workflow installation is incomplete"
  printf 'SYSTEM_DOCS_DOCTOR=OK\n'
}

explain() {
  cat "$REPO_ROOT/START-HERE.md"
}

case "${1:-}" in
  summary) shift; summary "$@" ;;
  snapshot) shift; snapshot "$@" ;;
  history) shift; history "$@" ;;
  record) shift; record "$@" ;;
  publish) shift; publish "$@" ;;
  doctor) shift; doctor "$@" ;;
  explain) shift; explain "$@" ;;
  -h|--help|help|'') usage ;;
  *) usage >&2; fail "unknown command: $1" ;;
esac