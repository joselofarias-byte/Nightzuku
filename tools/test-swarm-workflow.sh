#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
SOURCE_ROOT="$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

REPO="$TMP/repo"
STATE="$TMP/state"
mkdir -p "$REPO/tools" "$REPO/.githooks" "$REPO/.github" "$REPO/.cursor/rules"

cp "$SOURCE_ROOT/tools/llm-workflow.sh" "$REPO/tools/llm-workflow.sh"
cp "$SOURCE_ROOT/tools/swarm-workflow.sh" "$REPO/tools/swarm-workflow.sh"
cp "$SOURCE_ROOT/.githooks/pre-commit" "$REPO/.githooks/pre-commit"
cp "$SOURCE_ROOT/.githooks/commit-msg" "$REPO/.githooks/commit-msg"
printf 'fixture\n' > "$REPO/AI_WORKFLOW.md"
printf 'fixture\n' > "$REPO/AGENTS.md"
printf 'fixture\n' > "$REPO/CLAUDE.md"
printf 'fixture\n' > "$REPO/GEMINI.md"
printf 'fixture\n' > "$REPO/.github/copilot-instructions.md"
printf 'fixture\n' > "$REPO/.cursor/rules/llm-workflow.mdc"
printf '/.worktrees/\n' > "$REPO/.gitignore"
printf 'base\n' > "$REPO/README.md"

(
  cd "$REPO"
  git init -q
  git config user.name 'Swarm Workflow Test'
  git config user.email 'swarm-workflow-test@example.invalid'
  git add .
  git commit -q -m 'test: initialize fixture'

  export LLM_WORK_ROOT="$STATE"

  bash tools/swarm-workflow.sh start \
    --objective 'validate two-role swarm lifecycle' \
    --implementer synthetic-implementer \
    --reviewer synthetic-reviewer

  gd="$(git rev-parse --absolute-git-dir)"
  test -s "$gd/swarm-current"
  swarm_state="$(cat "$gd/swarm-current")"
  test -s "$swarm_state/SWARM.conf"
  order="$(dirname "$swarm_state")"
  grep -q '^status: active$' "$order/WORK-ORDER.md"

  impl="$(sed -n 's/^implementer_worktree=//p' "$swarm_state/SWARM.conf")"
  test -d "$impl"
  printf 'implementation\n' >> "$impl/README.md"
  printf 'new file\n' > "$impl/NEW.txt"

  bash tools/swarm-workflow.sh handoff

  reviewer="$(sed -n 's/^reviewer_worktree=//p' "$swarm_state/SWARM.conf")"
  test -d "$reviewer"
  grep -q '^implementation$' "$reviewer/README.md"
  grep -q '^new file$' "$reviewer/NEW.txt"
  test -s "$swarm_state/roles/reviewer.md"
  test -n "$(sed -n 's/^handoff_snapshot=//p' "$swarm_state/SWARM.conf")"

  bash tools/swarm-workflow.sh review-note 'Synthetic review completed.'
  grep -q 'Synthetic review completed' "$swarm_state/REVIEW.md"

  bash tools/swarm-workflow.sh finish 'Synthetic swarm completed.'

  test ! -e "$gd/swarm-current"
  test ! -e "$gd/llm-work-current"
  test -s "$gd/swarm-last"
  grep -q '^status: closed$' "$order/WORK-ORDER.md"
  grep -q '^status=closed$' "$swarm_state/SWARM.conf"

  if bash tools/swarm-workflow.sh cleanup >/dev/null 2>&1; then
    printf 'ERROR: cleanup should refuse dirty worktrees.\n' >&2
    exit 1
  fi

  git -C "$impl" reset --hard -q
  git -C "$impl" clean -fdq
  git -C "$reviewer" reset --hard -q
  git -C "$reviewer" clean -fdq
  bash tools/swarm-workflow.sh cleanup
  test ! -d "$impl"
  test ! -d "$reviewer"
)

printf 'Swarm workflow synthetic test: OK\n'
