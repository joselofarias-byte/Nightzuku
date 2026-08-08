#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
SOURCE_ROOT="$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

REPO="$TMP/repo"
STATE="$TMP/state"
CBM_HOME="$TMP/cbm"
CBM_CACHE="$TMP/cbm-cache"
ARGS="$TMP/cbm-args.log"
ENVLOG="$TMP/cbm-env.log"
mkdir -p "$REPO/tools" "$REPO/.githooks" "$CBM_HOME"

cp "$SOURCE_ROOT/tools/llm-workflow.sh" "$REPO/tools/llm-workflow.sh"
cp "$SOURCE_ROOT/tools/code-intel.sh" "$REPO/tools/code-intel.sh"
cp "$SOURCE_ROOT/.githooks/pre-commit" "$REPO/.githooks/pre-commit"
cp "$SOURCE_ROOT/.githooks/commit-msg" "$REPO/.githooks/commit-msg"
printf '# Candidate docs\n' > "$REPO/CODEBASE-MEMORY.md"
printf '.git/\n' > "$REPO/.cbmignore"
printf '# Workflow\n' > "$REPO/AI_WORKFLOW.md"
printf '# Agents\n' > "$REPO/AGENTS.md"
printf 'base\n' > "$REPO/README.md"

cat > "$CBM_HOME/codebase-memory-mcp" <<'FAKE'
#!/usr/bin/env bash
set -Eeuo pipefail
printf 'CBM_ALLOWED_ROOT=%s CBM_CACHE_DIR=%s CBM_DIAGNOSTICS=%s CBM_WORKERS=%s CBM_MEM_BUDGET_MB=%s\n' \
  "${CBM_ALLOWED_ROOT:-}" "${CBM_CACHE_DIR:-}" "${CBM_DIAGNOSTICS:-}" \
  "${CBM_WORKERS:-}" "${CBM_MEM_BUDGET_MB:-}" >> "${FAKE_CBM_ENV:?}"
printf '%q ' "$@" >> "${FAKE_CBM_ARGS:?}"
printf '\n' >> "$FAKE_CBM_ARGS"

if [[ "${1:-}" == '--version' ]]; then
  printf 'codebase-memory-mcp 0.9.0\n'
  exit 0
fi
if [[ "${1:-}" == 'config' ]]; then
  exit 0
fi
if [[ "${1:-}" == 'cli' ]]; then
  if [[ "${2:-}" == '--progress' ]]; then shift; fi
  tool="${2:-}"
  if [[ "$tool" == 'index_repository' && " ${*:3} " == *' --help '* ]]; then
    cat <<'HELP'
Usage: codebase-memory-mcp cli index_repository [options]
  --repo-path <path>       Repository path
  --persistence <boolean> Export shared graph artifact
HELP
    exit 0
  fi
  case "$tool" in
    index_repository)
      printf '{"status":"indexed","project":"repo","nodes":42,"edges":84}\n'
      ;;
    list_projects)
      printf '{"projects":[{"name":"repo","nodes":42,"edges":84}]}\n'
      ;;
    get_architecture)
      printf '{"project":"repo","languages":["Kotlin"]}\n'
      ;;
    *)
      printf '{"tool":"%s","ok":true}\n' "$tool"
      ;;
  esac
  exit 0
fi
printf '{"mcp":"started"}\n'
FAKE
chmod +x "$CBM_HOME/codebase-memory-mcp"

(
  cd "$REPO"
  git init -q
  git config user.name 'Code Intel Test'
  git config user.email 'code-intel-test@example.invalid'
  git remote add origin https://github.com/example/repo.git
  git add .
  git commit -q -m 'test: initialize fixture'

  export LLM_WORK_ROOT="$STATE"
  export CBM_HOME="$CBM_HOME"
  export CBM_CACHE_ROOT="$CBM_CACHE"
  export FAKE_CBM_ARGS="$ARGS"
  export FAKE_CBM_ENV="$ENVLOG"

  status_out="$(bash tools/code-intel.sh status)"
  grep -q '^CODE_INTEL_PRIMARY=CodeGraph$' <<< "$status_out"
  grep -q '^CBM=CANDIDATE_AVAILABLE version=0.9.0 ' <<< "$status_out"
  grep -q '^CBM_MCP_NETWORK_NOTE=' <<< "$status_out"

  doctor_out="$(bash tools/code-intel.sh doctor)"
  grep -q '^CBM_DOCTOR=OK$' <<< "$doctor_out"
  grep -q '^CODE_INTEL_DOCTOR=OK$' <<< "$doctor_out"

  if bash tools/code-intel.sh cbm-index >/dev/null 2>&1; then
    printf 'ERROR: CBM indexing succeeded without an active work order.\n' >&2
    exit 1
  fi

  bash tools/llm-workflow.sh start \
    --agent code-intel-test \
    --objective 'validate governed Codebase Memory candidate backend'

  gd="$(git rev-parse --absolute-git-dir)"
  order="$(cat "$gd/llm-work-current")"

  index_out="$(bash tools/code-intel.sh cbm-index)"
  grep -q '^CBM_INDEX_SECONDS=' <<< "$index_out"
  grep -q '^CBM_INDEX_PERSISTENCE=false$' <<< "$index_out"
  grep -q '"status":"indexed"' <<< "$index_out"
  grep -q -- 'cli index_repository --help' "$ARGS"
  grep -q -- 'cli --progress index_repository --repo-path' "$ARGS"
  grep -q -- '--persistence false' "$ARGS"
  grep -Fq "$REPO" "$ARGS"

  arch_out="$(bash tools/code-intel.sh cbm get_architecture --project repo)"
  grep -q '"languages":\["Kotlin"\]' <<< "$arch_out"
  grep -q -- 'cli get_architecture --project repo' "$ARGS"

  grep -q "CBM_ALLOWED_ROOT=$REPO" "$ENVLOG"
  grep -q "CBM_CACHE_DIR=$CBM_CACHE" "$ENVLOG"
  grep -q 'CBM_DIAGNOSTICS=0' "$ENVLOG"
  grep -q 'CBM_WORKERS=4' "$ENVLOG"
  grep -q 'CBM_MEM_BUDGET_MB=1024' "$ENVLOG"

  find "$order/evidence/code-intel/cbm/runs" -type f -name '*.log' -print -quit | grep -q .

  : > "$ARGS"
  mcp_out="$(bash tools/code-intel.sh cbm-mcp)"
  grep -q '"mcp":"started"' <<< "$mcp_out"
  grep -q -- 'config set auto_index false' "$ARGS"
  grep -q -- 'config set auto_watch false' "$ARGS"

  bash tools/llm-workflow.sh finish 'Governed Codebase Memory candidate test completed.'
)

printf 'Governed code-intelligence candidate synthetic test: OK\n'
