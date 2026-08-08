#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
SOURCE_ROOT="$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

REPO="$TMP/repo"
STATE="$TMP/state"
FAKE="$TMP/repomix"
ARGS="$TMP/args.log"
mkdir -p "$REPO/tools" "$REPO/.githooks" "$REPO/.github" "$REPO/.cursor/rules"

cp "$SOURCE_ROOT/tools/llm-workflow.sh" "$REPO/tools/llm-workflow.sh"
cp "$SOURCE_ROOT/tools/context-pack.sh" "$REPO/tools/context-pack.sh"
cp "$SOURCE_ROOT/.githooks/pre-commit" "$REPO/.githooks/pre-commit"
cp "$SOURCE_ROOT/.githooks/commit-msg" "$REPO/.githooks/commit-msg"
printf 'fixture\n' > "$REPO/AI_WORKFLOW.md"
printf 'fixture\n' > "$REPO/AGENTS.md"
printf 'fixture\n' > "$REPO/CLAUDE.md"
printf 'fixture\n' > "$REPO/GEMINI.md"
printf 'fixture\n' > "$REPO/.github/copilot-instructions.md"
printf 'fixture\n' > "$REPO/.cursor/rules/llm-workflow.mdc"
printf '.env\n*.keystore\n' > "$REPO/.repomixignore"
printf 'base\n' > "$REPO/README.md"
printf 'secret\n' > "$REPO/.env"
printf 'throw new Error("repository config must not be auto-loaded by wrapper");\n' > "$REPO/repomix.config.js"

cat > "$FAKE" <<'FAKE'
#!/usr/bin/env bash
set -Eeuo pipefail
if [[ "${1:-}" == '--version' ]]; then
  printf 'repomix 1.18.0\n'
  exit 0
fi
printf '%q ' "$@" >> "${FAKE_ARGS_FILE:?}"
printf '\n' >> "$FAKE_ARGS_FILE"
mcp=0
for arg in "$@"; do
  case "$arg" in
    --no-security-check|--remote|--remote-trust-config)
      printf 'forbidden arg reached fake repomix: %s\n' "$arg" >&2
      exit 91
      ;;
    --mcp) mcp=1 ;;
  esac
done
if [[ "$mcp" == '1' ]]; then
  printf 'fake mcp ok\n'
  exit 0
fi
out=''
while [[ $# -gt 0 ]]; do
  case "$1" in
    -o|--output) out="$2"; shift 2 ;;
    --config|--style|--token-count-tree|--top-files-len|--token-budget|--include|--ignore) shift 2 ;;
    *) shift ;;
  esac
done
[[ -n "$out" ]] || { printf 'missing output\n' >&2; exit 92; }
mkdir -p "$(dirname "$out")"
cat > "$out" <<'OUT'
# fake repomix output
README.md: base
OUT
printf 'Total Tokens: 42\n'
FAKE
chmod +x "$FAKE"

(
  cd "$REPO"
  git init -q
  git config user.name 'Context Pack Test'
  git config user.email 'context-pack-test@example.invalid'
  git add . ':!.env'
  git commit -q -m 'test: initialize fixture'

  export LLM_WORK_ROOT="$STATE"
  export REPOMIX_BIN="$FAKE"
  export FAKE_ARGS_FILE="$ARGS"

  status_out="$(bash tools/context-pack.sh status --brief)"
  grep -q '^REPOMIX=AVAILABLE version=1.18.0 ' <<< "$status_out"

  if bash tools/context-pack.sh pack --mode compact --name should-fail >/dev/null 2>&1; then
    printf 'ERROR: context pack succeeded without active work order.\n' >&2
    exit 1
  fi

  bash tools/llm-workflow.sh start \
    --agent context-pack-test \
    --objective 'validate governed Repomix context packaging'

  gd="$(git rev-parse --absolute-git-dir)"
  order="$(cat "$gd/llm-work-current")"

  pack_out="$(bash tools/context-pack.sh pack --mode compact --include 'README.md' --token-budget 5000 --name focused)"
  context_file="$(sed -n 's/^CONTEXT_PACK=//p' <<< "$pack_out")"
  meta_file="$(sed -n 's/^CONTEXT_META=//p' <<< "$pack_out")"
  [[ -f "$context_file" && -f "$meta_file" && -f "$context_file.sha256" ]]
  [[ "$context_file" == "$order/evidence/context/"* ]]
  grep -q 'Security check: enabled/mandatory' "$meta_file"
  grep -q 'Repository Repomix config: bypassed' "$meta_file"
  grep -q -- '--compress' "$ARGS"
  grep -q -- '--output-show-line-numbers' "$ARGS"
  grep -q -- '--config' "$ARGS"
  ! grep -q -- '--no-security-check' "$ARGS"
  test ! -e "$REPO/repomix-output.xml"

  safe_config="$(awk '{for (i=1;i<=NF;i++) if ($i=="--config") {print $(i+1); exit}}' "$ARGS")"
  [[ -n "$safe_config" && -f "$safe_config" ]]
  [[ "$safe_config" == "$order/evidence/context/"* ]]
  grep -q '"enableSecurityCheck": true' "$safe_config"
  [[ "$safe_config" != "$REPO/repomix.config.js" ]]

  if bash tools/context-pack.sh pack --no-security-check >/dev/null 2>&1; then
    printf 'ERROR: wrapper accepted --no-security-check.\n' >&2
    exit 1
  fi
  if bash tools/context-pack.sh pack --config "$REPO/repomix.config.js" >/dev/null 2>&1; then
    printf 'ERROR: wrapper accepted caller-supplied --config.\n' >&2
    exit 1
  fi

  : > "$ARGS"
  bash tools/context-pack.sh mcp >/dev/null
  grep -q -- '--mcp' "$ARGS"
  grep -q -- '--sandbox' "$ARGS"
  grep -q -- '--config' "$ARGS"
  grep -Fq "$REPO" "$ARGS"
  ! grep -q -- '--remote' "$ARGS"
  mcp_config="$(awk '{for (i=1;i<=NF;i++) if ($i=="--config") {print $(i+1); exit}}' "$ARGS")"
  [[ -f "$mcp_config" && "$mcp_config" == "$order/evidence/context/"* ]]
  grep -q '"enableSecurityCheck": true' "$mcp_config"

  bash tools/llm-workflow.sh finish 'Governed Repomix context pack test completed.'
)

printf 'Governed Repomix context pack synthetic test: OK\n'
