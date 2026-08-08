#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

readonly CBM_PIN='0.8.1'
readonly SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
readonly REPO_ROOT="$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel 2>/dev/null || true)"
readonly INSTALL_BASE="${ENGINEERING_TOOLS_HOME:-$HOME/.local/share/engineering-tools}"
readonly CBM_ROOT="${CBM_HOME:-$INSTALL_BASE/codebase-memory-mcp/$CBM_PIN}"
readonly CBM_BIN="$CBM_ROOT/codebase-memory-mcp"
readonly RELEASE_BASE="https://github.com/DeusData/codebase-memory-mcp/releases/download/v$CBM_PIN"

fail() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }
[[ -n "$REPO_ROOT" ]] || fail 'not inside a Git repository'

GIT_DIR="$(git -C "$REPO_ROOT" rev-parse --absolute-git-dir)"
ACTIVE_FILE="$GIT_DIR/llm-work-current"
WORKFLOW="$REPO_ROOT/tools/llm-workflow.sh"

origin="$(git -C "$REPO_ROOT" remote get-url origin 2>/dev/null || true)"
repo_id="${origin:-$(basename "$REPO_ROOT")}"
repo_id="$(printf '%s' "$repo_id" | sed -E 's#^[^:]+://##; s#^[^@]+@[^:]+:##; s#\.git$##; s#/#-#g' | tr -cs '[:alnum:]_.-' '-' | sed -E 's/^-+//; s/-+$//')"
CBM_CACHE_ROOT="${CBM_CACHE_ROOT:-$HOME/.local/state/code-intel/cbm/$repo_id}"

usage() {
  cat <<'USAGE'
Governed code-intelligence entry point.

Usage: bash tools/code-intel.sh <command> [args]

Commands:
  status                    Show primary and candidate backend state.
  doctor                    Validate integration; CBM may be absent.
  install-cbm               Install pinned CBM binary, isolated and checksum-verified.
  doctor-cbm                Strictly validate the pinned CBM installation.
  cbm-index                 Index current repository with one-shot CBM CLI.
  cbm <tool> [flags...]     Run one CBM CLI tool and record evidence.
  cbm-mcp                   Start CBM MCP explicitly for this repo/order.
  codegraph-status          Show current CodeGraph status.

Policy:
- CodeGraph remains PRIMARY until real-repo comparison justifies migration.
- Codebase Memory MCP is CANDIDATE/SHADOW.
- No curl|bash, no native `install`, no automatic agent config mutation.
- CBM cache is repo-specific and outside the checkout.
- CBM analysis/indexing requires an active work order.
- CLI one-shot mode is preferred; daemon/watch are not enabled by default.
USAGE
}

active_order() {
  [[ -s "$ACTIVE_FILE" ]] || fail 'code-intel operation requires an active work order'
  local order
  order="$(cat "$ACTIVE_FILE")"
  [[ -d "$order" ]] || fail "invalid active work order: $order"
  printf '%s\n' "$order"
}

cbm_env() {
  export CBM_ALLOWED_ROOT="$REPO_ROOT"
  export CBM_CACHE_DIR="$CBM_CACHE_ROOT"
  export CBM_DIAGNOSTICS=0
  export CBM_LOG_LEVEL="${CBM_LOG_LEVEL:-warn}"
  export CBM_WORKERS="${CBM_WORKERS:-4}"
  export CBM_MEM_BUDGET_MB="${CBM_MEM_BUDGET_MB:-1024}"
}

cbm_version() {
  [[ -x "$CBM_BIN" ]] || return 1
  "$CBM_BIN" --version 2>/dev/null | grep -Eo '[0-9]+\.[0-9]+\.[0-9]+' | head -n1
}

status() {
  printf 'CODE_INTEL_PRIMARY=CodeGraph\n'
  if command -v codegraph >/dev/null 2>&1; then
    printf 'CODEGRAPH=AVAILABLE %s\n' "$(codegraph --version 2>/dev/null | head -n1 || true)"
  else
    printf 'CODEGRAPH=NOT_INSTALLED\n'
  fi

  if [[ -x "$CBM_BIN" ]]; then
    local v
    v="$(cbm_version || true)"
    if [[ "$v" == "$CBM_PIN" ]]; then
      printf 'CBM=CANDIDATE_AVAILABLE version=%s path=%s\n' "$v" "$CBM_BIN"
    else
      printf 'CBM=CANDIDATE_VERSION_MISMATCH expected=%s actual=%s path=%s\n' "$CBM_PIN" "${v:-UNKNOWN}" "$CBM_BIN"
    fi
  else
    printf 'CBM=CANDIDATE_NOT_INSTALLED expected=%s\n' "$CBM_PIN"
  fi
  printf 'CBM_CACHE=%s\n' "$CBM_CACHE_ROOT"
  printf 'CBM_ALLOWED_ROOT=%s\n' "$REPO_ROOT"
  printf 'CBM_DEFAULT_MODE=CLI_ONE_SHOT\n'
  printf 'CBM_AUTO_WATCH=DISABLED_BY_POLICY\n'
  printf 'CBM_AGENT_CONFIG_MUTATION=DISABLED\n'
}

platform_archive() {
  [[ "$(uname -s)" == 'Linux' ]] || fail 'CBM candidate integration currently targets Linux/Debian'
  local arch
  case "$(uname -m)" in
    aarch64|arm64) arch='arm64' ;;
    x86_64|amd64) arch='amd64' ;;
    *) fail "unsupported architecture: $(uname -m)" ;;
  esac
  printf 'codebase-memory-mcp-linux-%s-portable.tar.gz\n' "$arch"
}

install_cbm() {
  command -v curl >/dev/null 2>&1 || fail 'curl is required'
  command -v tar >/dev/null 2>&1 || fail 'tar is required'
  command -v sha256sum >/dev/null 2>&1 || fail 'sha256sum is required'

  local current=''
  current="$(cbm_version 2>/dev/null || true)"
  if [[ "$current" == "$CBM_PIN" ]]; then
    printf 'CBM_ALREADY_INSTALLED=%s\n' "$CBM_BIN"
    status
    return 0
  fi

  local archive tmp expected actual extracted staged old
  archive="$(platform_archive)"
  tmp="$(mktemp -d)"
  trap 'rm -rf "$tmp"' EXIT

  curl -fL --proto '=https' --proto-redir '=https' --max-redirs 5 \
    "$RELEASE_BASE/$archive" -o "$tmp/$archive"
  curl -fL --proto '=https' --proto-redir '=https' --max-redirs 5 \
    "$RELEASE_BASE/checksums.txt" -o "$tmp/checksums.txt"

  [[ "$(wc -c < "$tmp/checksums.txt")" -le 1048576 ]] || fail 'checksums.txt exceeds 1 MiB'
  expected="$(awk -v f="$archive" '$2 == f || $2 == "*" f {print $1}' "$tmp/checksums.txt" | head -n1)"
  [[ "$expected" =~ ^[0-9A-Fa-f]{64}$ ]] || fail "no valid SHA-256 published for $archive"
  actual="$(sha256sum "$tmp/$archive" | awk '{print $1}')"
  [[ "${actual,,}" == "${expected,,}" ]] || fail "checksum mismatch for $archive"

  mkdir -p "$tmp/extract"
  tar -xzf "$tmp/$archive" -C "$tmp/extract"
  extracted="$(find "$tmp/extract" -type f -name codebase-memory-mcp -print -quit)"
  [[ -n "$extracted" ]] || fail 'binary missing from verified archive'
  chmod 755 "$extracted"

  local version
  version="$("$extracted" --version 2>/dev/null | grep -Eo '[0-9]+\.[0-9]+\.[0-9]+' | head -n1 || true)"
  [[ "$version" == "$CBM_PIN" ]] || fail "candidate version mismatch: expected $CBM_PIN, got ${version:-UNKNOWN}"

  mkdir -p "$(dirname "$CBM_ROOT")"
  staged="${CBM_ROOT}.new.$$"
  old="${CBM_ROOT}.old.$$"
  rm -rf "$staged" "$old"
  mkdir -p "$staged"
  cp "$extracted" "$staged/codebase-memory-mcp"
  chmod 755 "$staged/codebase-memory-mcp"
  {
    printf 'version=%s\n' "$CBM_PIN"
    printf 'source=%s/%s\n' "$RELEASE_BASE" "$archive"
    printf 'sha256=%s\n' "$actual"
    printf 'installed_at=%s\n' "$(date -Iseconds)"
    printf 'native_install_command_executed=NO\n'
    printf 'agent_config_modified=NO\n'
  } > "$staged/INSTALL-METADATA.txt"

  if [[ -e "$CBM_ROOT" ]]; then mv "$CBM_ROOT" "$old"; fi
  mv "$staged" "$CBM_ROOT"
  rm -rf "$old"
  trap - EXIT
  rm -rf "$tmp"

  printf 'CBM_INSTALLED=%s\n' "$CBM_BIN"
  printf 'CBM_ARCHIVE_SHA256=%s\n' "$actual"
  doctor_cbm
}

doctor_cbm() {
  [[ -x "$CBM_BIN" ]] || fail "CBM $CBM_PIN is not installed; run install-cbm"
  local v
  v="$(cbm_version || true)"
  [[ "$v" == "$CBM_PIN" ]] || fail "CBM version mismatch: expected $CBM_PIN, got ${v:-UNKNOWN}"
  [[ -f "$REPO_ROOT/.cbmignore" ]] || fail 'missing .cbmignore'
  cbm_env
  mkdir -p "$CBM_CACHE_ROOT"
  chmod 700 "$CBM_CACHE_ROOT" 2>/dev/null || true
  printf 'CBM_VERSION=%s\n' "$v"
  printf 'CBM_LICENSE=MIT\n'
  printf 'CBM_ALLOWED_ROOT=%s\n' "$CBM_ALLOWED_ROOT"
  printf 'CBM_CACHE_DIR=%s\n' "$CBM_CACHE_DIR"
  printf 'CBM_DIAGNOSTICS=%s\n' "$CBM_DIAGNOSTICS"
  printf 'CBM_WORKERS=%s\n' "$CBM_WORKERS"
  printf 'CBM_MEM_BUDGET_MB=%s\n' "$CBM_MEM_BUDGET_MB"
  printf 'CBM_NATIVE_INSTALL=NOT_USED\n'
  printf 'CBM_AGENT_CONFIG_MUTATION=NO\n'
  printf 'CBM_DOCTOR=OK\n'
}

doctor() {
  bash -n "$REPO_ROOT/tools/code-intel.sh"
  [[ -f "$REPO_ROOT/CODEBASE-MEMORY.md" ]] || fail 'missing CODEBASE-MEMORY.md'
  [[ -f "$REPO_ROOT/.cbmignore" ]] || fail 'missing .cbmignore'
  if command -v codegraph >/dev/null 2>&1; then
    printf 'CODEGRAPH_PRIMARY=AVAILABLE\n'
  else
    printf 'WARNING=CodeGraph primary backend is not installed\n'
  fi
  if [[ -x "$CBM_BIN" ]]; then doctor_cbm; else printf 'CBM_CANDIDATE=NOT_INSTALLED (optional)\n'; fi
  printf 'CODE_INTEL_DOCTOR=OK\n'
}

log_run() {
  local order="$1" label="$2"; shift 2
  local dir stamp log rc
  dir="$order/evidence/code-intel/cbm/runs"
  mkdir -p "$dir"
  stamp="$(date '+%Y%m%d-%H%M%S')"
  log="$dir/${stamp}-${label}.log"
  set +e
  "$@" 2>&1 | tee "$log"
  rc=${PIPESTATUS[0]}
  set -e
  printf 'CBM_LOG=%s\n' "$log"
  return "$rc"
}

cbm_cli() {
  [[ $# -ge 1 ]] || fail 'cbm requires a tool name'
  doctor_cbm >/dev/null
  local order tool rc
  order="$(active_order)"
  tool="$1"
  cbm_env
  set +e
  log_run "$order" "$tool" "$CBM_BIN" cli "$@"
  rc=$?
  set -e
  if [[ -f "$WORKFLOW" ]]; then
    bash "$WORKFLOW" note "Codebase Memory candidate CLI: tool=$tool rc=$rc; cache=$CBM_CACHE_ROOT" || true
  fi
  return "$rc"
}

cbm_index() {
  doctor_cbm >/dev/null
  local order started rc elapsed
  order="$(active_order)"
  cbm_env
  mkdir -p "$CBM_CACHE_ROOT"
  started=$SECONDS
  set +e
  log_run "$order" index_repository "$CBM_BIN" cli --progress index_repository --repo-path "$REPO_ROOT"
  rc=$?
  set -e
  elapsed=$((SECONDS - started))
  if [[ -f "$WORKFLOW" ]]; then
    bash "$WORKFLOW" note "Codebase Memory candidate index: rc=$rc elapsed=${elapsed}s cache=$CBM_CACHE_ROOT" || true
  fi
  printf 'CBM_INDEX_SECONDS=%s\n' "$elapsed"
  [[ "$rc" -eq 0 ]] || return "$rc"
  "$CBM_BIN" cli list_projects
}

cbm_mcp() {
  doctor_cbm >/dev/null
  local order
  order="$(active_order)"
  cbm_env
  mkdir -p "$CBM_CACHE_ROOT"
  # Explicit MCP pilot: disable automatic indexing/watcher before daemon-backed mode.
  "$CBM_BIN" config set auto_index false >/dev/null
  "$CBM_BIN" config set auto_watch false >/dev/null
  if [[ -f "$WORKFLOW" ]]; then
    bash "$WORKFLOW" note "Starting Codebase Memory candidate MCP explicitly; allowed_root=$REPO_ROOT cache=$CBM_CACHE_ROOT auto_index=false auto_watch=false" || true
  fi
  printf 'CBM_MCP_MODE=EXPLICIT_PILOT\n' >&2
  printf 'CBM_ALLOWED_ROOT=%s\n' "$REPO_ROOT" >&2
  printf 'CBM_CACHE_DIR=%s\n' "$CBM_CACHE_ROOT" >&2
  printf 'WORK_ORDER=%s\n' "$order" >&2
  cd "$REPO_ROOT"
  exec "$CBM_BIN"
}

codegraph_status() {
  command -v codegraph >/dev/null 2>&1 || fail 'CodeGraph is not installed'
  codegraph status "$REPO_ROOT"
}

case "${1:-}" in
  status) shift; status "$@" ;;
  doctor) shift; doctor "$@" ;;
  install-cbm) shift; install_cbm "$@" ;;
  doctor-cbm) shift; doctor_cbm "$@" ;;
  cbm-index) shift; cbm_index "$@" ;;
  cbm) shift; cbm_cli "$@" ;;
  cbm-mcp) shift; cbm_mcp "$@" ;;
  codegraph-status) shift; codegraph_status "$@" ;;
  -h|--help|help|'') usage ;;
  *) usage >&2; fail "unknown command: $1" ;;
esac
