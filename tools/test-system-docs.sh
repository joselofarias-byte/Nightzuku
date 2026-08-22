#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
SOURCE_ROOT="$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

REPO="$TMP/repo"
STATE="$TMP/state"
NO_VAULT="$TMP/no-vault"
TEST_HOME="$TMP/home"
mkdir -p "$REPO/tools" "$REPO/.githooks" "$REPO/.github" "$REPO/.cursor/rules" "$TEST_HOME/.local/bin"

cat > "$TEST_HOME/.local/bin/codegraph" <<'CODEGRAPH'
#!/usr/bin/env bash
case "${1:-}" in
  --version) printf '0.0.0-test\n' ;;
  status) printf 'test index available\n' ;;
  *) exit 0 ;;
esac
CODEGRAPH
chmod +x "$TEST_HOME/.local/bin/codegraph"

for file in \
  llm-workflow.sh \
  work.sh \
  swarm-workflow.sh \
  swarm.sh \
  context-pack.sh \
  code-intel.sh \
  system-docs.sh \
  test-llm-workflow.sh \
  test-swarm-workflow.sh \
  test-context-pack.sh \
  test-code-intel.sh \
  test-system-docs.sh; do
  cp "$SOURCE_ROOT/tools/$file" "$REPO/tools/$file"
done
cp "$SOURCE_ROOT/.githooks/pre-commit" "$REPO/.githooks/pre-commit"
cp "$SOURCE_ROOT/.githooks/commit-msg" "$REPO/.githooks/commit-msg"

cat > "$REPO/START-HERE.md" <<'DOC'
# Start Here
DOC
cat > "$REPO/AI_WORKFLOW.md" <<'DOC'
# Workflow
DOC
cat > "$REPO/AGENTS.md" <<'DOC'
# Agents
DOC
cat > "$REPO/SWARM_WORKFLOW.md" <<'DOC'
# Swarm
DOC
cat > "$REPO/REPOMIX.md" <<'DOC'
# Repomix
DOC
cat > "$REPO/CODEBASE-MEMORY.md" <<'DOC'
# Codebase Memory candidate
DOC
cat > "$REPO/CLAUDE.md" <<'DOC'
# Claude
DOC
cat > "$REPO/GEMINI.md" <<'DOC'
# Gemini
DOC
cat > "$REPO/.github/copilot-instructions.md" <<'DOC'
# Copilot
DOC
cat > "$REPO/.cursor/rules/llm-workflow.mdc" <<'DOC'
---
alwaysApply: true
---
DOC
printf '/.worktrees/\n' > "$REPO/.gitignore"
printf '.env\n*.keystore\n' > "$REPO/.repomixignore"
printf '.git/\nbuild/\n' > "$REPO/.cbmignore"
printf 'base\n' > "$REPO/README.md"

(
  cd "$REPO"
  git init -q
  git config user.name 'System Docs Test'
  git config user.email 'system-docs-test@example.invalid'
  git add .
  git commit -q -m 'test: initialize fixture'

  export LLM_WORK_ROOT="$STATE"
  export OBSIDIAN_VAULT="$NO_VAULT"

  bash tools/llm-workflow.sh install >/dev/null
  bash tools/system-docs.sh doctor
  bash tools/work.sh start \
    --agent synthetic-agent \
    --objective 'validate automatic documentation lifecycle'

  gd="$(git rev-parse --absolute-git-dir)"
  test -s "$gd/llm-work-current"
  order="$(cat "$gd/llm-work-current")"
  test -d "$order/evidence/system-snapshots"
  find "$order/evidence/system-snapshots" -type f -name '*-start.md' -print -quit | grep -q .

  bash tools/work.sh note 'Synthetic documented finding.'
  bash tools/work.sh run -- bash -lc 'printf "validation-ok\\n"'
  bash tools/work.sh checkpoint 'documented-checkpoint'
  find "$order/evidence/system-snapshots" -type f -name '*-checkpoint.md' -print -quit | grep -q .

  bash tools/work.sh finish 'Synthetic documentation flow completed.'
  test ! -e "$gd/llm-work-current"
  test -s "$gd/llm-work-last"
  test -s "$STATE"/*/HISTORY.md
  grep -R -q 'Synthetic documentation flow completed' "$STATE"
  find "$order/evidence/system-snapshots" -type f -name '*-finish.md' -print -quit | grep -q .

  out="$(bash tools/system-docs.sh record closed 'duplicate record must be ignored')"
  grep -q 'HISTORY_ALREADY_RECORDED=' <<< "$out"

  summary_out="$(HOME="$TEST_HOME" PATH="/usr/bin:/bin" bash tools/system-docs.sh summary)"
  grep -q '^WORK_ORDER=NONE$' <<< "$summary_out"
  grep -q '^REPOMIX=' <<< "$summary_out"
  grep -q '^CODE_INTEL_PRIMARY=CodeGraph:AVAILABLE$' <<< "$summary_out"
  grep -q '^CODE_INTEL_CANDIDATE=CodebaseMemory:' <<< "$summary_out"

  history_out="$(bash tools/system-docs.sh history)"
  grep -q 'validate automatic documentation lifecycle' <<< "$history_out"

  bash tools/system-docs.sh snapshot "$TMP/final-system-snapshot.md" >/dev/null
  grep -q '# System snapshot' "$TMP/final-system-snapshot.md"
  grep -q 'Canonical layers' "$TMP/final-system-snapshot.md"
  grep -q 'Repomix' "$TMP/final-system-snapshot.md"
  grep -q 'Codebase Memory MCP' "$TMP/final-system-snapshot.md"
  grep -q 'CANDIDATE/SHADOW' "$TMP/final-system-snapshot.md"
)

printf 'Self-documenting workflow synthetic test: OK\n'