#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
SOURCE_ROOT="$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

REPO="$TMP/repo"
STATE="$TMP/state"
mkdir -p \
  "$REPO/tools" \
  "$REPO/.githooks" \
  "$REPO/.github" \
  "$REPO/.cursor/rules"

cp "$SOURCE_ROOT/tools/llm-workflow.sh" "$REPO/tools/llm-workflow.sh"
cp "$SOURCE_ROOT/.githooks/pre-commit" "$REPO/.githooks/pre-commit"
cp "$SOURCE_ROOT/.githooks/commit-msg" "$REPO/.githooks/commit-msg"
for file in AI_WORKFLOW.md AGENTS.md CLAUDE.md GEMINI.md; do
  printf 'fixture\n' > "$REPO/$file"
done
printf 'fixture\n' > "$REPO/.github/copilot-instructions.md"
printf 'fixture\n' > "$REPO/.cursor/rules/llm-workflow.mdc"
printf 'base\n' > "$REPO/README.md"

(
  cd "$REPO"
  git init -q
  git config user.name 'Workflow Test'
  git config user.email 'workflow-test@example.invalid'
  git add .
  git commit -q -m 'test: initialize fixture'

  LLM_WORK_ROOT="$STATE" \
    bash tools/llm-workflow.sh start \
      --agent synthetic-test \
      --objective 'validate standard workflow'

  LLM_WORK_ROOT="$STATE" \
    bash tools/llm-workflow.sh note \
      'Synthetic investigation note.'

  printf 'change\n' >> README.md
  LLM_WORK_ROOT="$STATE" \
    bash tools/llm-workflow.sh run -- \
      bash -lc 'grep -q change README.md'

  git add README.md
  LLM_WORK_ROOT="$STATE" git commit -q -m 'test: validate guarded commit'

  git log -1 --pretty=%B | grep -q '^Work-Order: '
  git log -1 --pretty=%B | grep -q '^Agent: synthetic-test$'

  LLM_WORK_ROOT="$STATE" \
    bash tools/llm-workflow.sh finish \
      'Synthetic workflow completed.'

  last="$(git rev-parse --absolute-git-dir)/llm-work-last"
  test -s "$last"
  order="$(cat "$last")"
  test -s "$order/backup/repository.bundle"
  test -s "$order/backup/bundle-verify.txt"
  test -s "$order/MANIFEST.sha256"
  grep -q '^status: closed$' "$order/WORK-ORDER.md"
  (
    cd "$order"
    sha256sum -c MANIFEST.sha256 >/dev/null
  )

  printf 'blocked\n' >> README.md
  git add README.md
  if git commit -q -m 'test: must be blocked' >/dev/null 2>&1; then
    printf 'ERROR: commit succeeded without an active order.\n' >&2
    exit 1
  fi
)

printf 'LLM workflow synthetic test: OK\n'
