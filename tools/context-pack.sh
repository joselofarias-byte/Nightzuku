#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

PINNED_VERSION='1.18.0'
INSTALL_BASE="${ENGINEERING_TOOLS_HOME:-$HOME/.local/share/engineering-tools}"
INSTALL_ROOT="${REPOMIX_HOME:-$INSTALL_BASE/repomix/$PINNED_VERSION}"
PINNED_BIN="$INSTALL_ROOT/node_modules/.bin/repomix"

fail() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 1
}

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
REPO_ROOT="$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel 2>/dev/null || true)"
[[ -n "$REPO_ROOT" ]] || fail "must run inside a Git repository"
GIT_DIR="$(git -C "$REPO_ROOT" rev-parse --absolute-git-dir)"
ACTIVE_FILE="$GIT_DIR/llm-work-current"
WORKFLOW="$REPO_ROOT/tools/llm-workflow.sh"

usage() {
  cat <<'USAGE'
Governed Repomix context packaging.

Usage: bash tools/context-pack.sh <command> [args]

Commands:
  status [--brief]
  install
  doctor
  pack [--mode compact|full|metadata] [--style markdown|xml|json|plain]
       [--include GLOBS] [--ignore GLOBS] [--token-budget N] [--name NAME]
  mcp

Policy:
- Repomix is pinned and installed outside application checkouts.
- pack and mcp require an active work order.
- output is stored under the active order's evidence/context directory.
- Secretlint/security checking remains enabled; --no-security-check is never used.
- remote repository packing and remote config trust are intentionally not exposed.
- MCP always runs with --sandbox confined to this repository.
USAGE
}

node_major() {
  command -v node >/dev/null 2>&1 || return 1
  node -p 'Number(process.versions.node.split(".")[0])' 2>/dev/null
}

resolve_bin() {
  local candidate="${REPOMIX_BIN:-}"
  if [[ -n "$candidate" ]]; then
    if [[ "$candidate" != */* ]]; then
      candidate="$(command -v "$candidate" 2>/dev/null || true)"
    fi
    [[ -n "$candidate" && -x "$candidate" ]] || return 1
    printf '%s\n' "$candidate"
    return 0
  fi
  if [[ -x "$PINNED_BIN" ]]; then
    printf '%s\n' "$PINNED_BIN"
    return 0
  fi
  candidate="$(command -v repomix 2>/dev/null || true)"
  [[ -n "$candidate" && -x "$candidate" ]] || return 1
  printf '%s\n' "$candidate"
}

repomix_version() {
  local bin="$1" out
  out="$("$bin" --version 2>/dev/null | head -n1 || true)"
  printf '%s' "$out" | grep -Eo '[0-9]+\.[0-9]+\.[0-9]+' | head -n1
}

status() {
  local brief=0
  [[ "${1:-}" == "--brief" ]] && brief=1
  local major="" bin="" version=""
  major="$(node_major 2>/dev/null || true)"
  if bin="$(resolve_bin 2>/dev/null)"; then
    version="$(repomix_version "$bin")"
    if [[ "$version" == "$PINNED_VERSION" ]]; then
      printf 'REPOMIX=AVAILABLE version=%s path=%s\n' "$version" "$bin"
    else
      printf 'REPOMIX=VERSION_MISMATCH expected=%s actual=%s path=%s\n' "$PINNED_VERSION" "${version:-UNKNOWN}" "$bin"
    fi
  else
    printf 'REPOMIX=NOT_INSTALLED expected=%s\n' "$PINNED_VERSION"
  fi
  if [[ "$brief" == "0" ]]; then
    printf 'NODE=%s\n' "$(command -v node >/dev/null 2>&1 && node --version || printf 'NOT_INSTALLED')"
    printf 'NODE_MAJOR=%s\n' "${major:-UNKNOWN}"
    printf 'INSTALL_ROOT=%s\n' "$INSTALL_ROOT"
    printf 'LICENSE=MIT\n'
    printf 'SECURITY_CHECK=MANDATORY\n'
    printf 'REMOTE_PACKING=DISABLED_BY_WRAPPER\n'
    printf 'MCP_SANDBOX=MANDATORY\n'
  fi
}

require_node22() {
  command -v node >/dev/null 2>&1 || fail "Node.js is required"
  command -v npm >/dev/null 2>&1 || fail "npm is required"
  local major
  major="$(node_major)"
  [[ "$major" =~ ^[0-9]+$ && "$major" -ge 22 ]] \
    || fail "Repomix $PINNED_VERSION requires Node.js >=22; found $(node --version 2>/dev/null || printf unknown)"
}

install_repomix() {
  require_node22
  local tmp old bin version
  mkdir -p "$(dirname "$INSTALL_ROOT")"
  chmod 700 "$INSTALL_BASE" "$(dirname "$INSTALL_ROOT")" 2>/dev/null || true

  if [[ -x "$PINNED_BIN" ]]; then
    version="$(repomix_version "$PINNED_BIN")"
    if [[ "$version" == "$PINNED_VERSION" ]]; then
      printf 'REPOMIX_ALREADY_INSTALLED=%s\n' "$PINNED_BIN"
      status
      return 0
    fi
  fi

  tmp="${INSTALL_ROOT}.tmp.$$"
  old="${INSTALL_ROOT}.old.$$"
  rm -rf "$tmp" "$old"
  trap 'rm -rf "$tmp"' RETURN
  mkdir -p "$tmp"

  npm install \
    --prefix "$tmp" \
    --ignore-scripts \
    --no-audit \
    --no-fund \
    --package-lock=false \
    "repomix@$PINNED_VERSION"

  bin="$tmp/node_modules/.bin/repomix"
  [[ -x "$bin" ]] || fail "npm completed but Repomix binary is missing"
  version="$(repomix_version "$bin")"
  [[ "$version" == "$PINNED_VERSION" ]] \
    || fail "installed Repomix version mismatch: expected $PINNED_VERSION, got ${version:-UNKNOWN}"

  if [[ -e "$INSTALL_ROOT" ]]; then
    mv "$INSTALL_ROOT" "$old"
  fi
  mv "$tmp" "$INSTALL_ROOT"
  rm -rf "$old"
  trap - RETURN

  printf 'REPOMIX_INSTALLED=%s\n' "$PINNED_BIN"
  status
}

require_pinned_bin() {
  local bin version
  bin="$(resolve_bin 2>/dev/null || true)"
  [[ -n "$bin" ]] || fail "Repomix $PINNED_VERSION is not installed; run: bash tools/context-pack.sh install"
  version="$(repomix_version "$bin")"
  [[ "$version" == "$PINNED_VERSION" ]] \
    || fail "Repomix version mismatch: expected $PINNED_VERSION, got ${version:-UNKNOWN}; use the pinned isolated install"
  printf '%s\n' "$bin"
}

active_order() {
  [[ -s "$ACTIVE_FILE" ]] || fail "context packaging requires an active work order; start with tools/work.sh or tools/swarm.sh"
  local order
  order="$(cat "$ACTIVE_FILE")"
  [[ -d "$order" ]] || fail "active work order path is invalid: $order"
  printf '%s\n' "$order"
}

sanitize_name() {
  printf '%s' "$1" | tr -cs '[:alnum:]_.-' '-' | sed -E 's/^-+//; s/-+$//' | cut -c1-60
}

extension_for_style() {
  case "$1" in
    markdown) printf 'md' ;;
    xml) printf 'xml' ;;
    json) printf 'json' ;;
    plain) printf 'txt' ;;
    *) return 1 ;;
  esac
}

pack_context() {
  local mode='compact' style='markdown' include='' ignore='' token_budget="${REPOMIX_TOKEN_BUDGET:-120000}" name='context'
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --mode) [[ $# -ge 2 ]] || fail "--mode requires a value"; mode="$2"; shift 2 ;;
      --style) [[ $# -ge 2 ]] || fail "--style requires a value"; style="$2"; shift 2 ;;
      --include) [[ $# -ge 2 ]] || fail "--include requires a value"; include="$2"; shift 2 ;;
      --ignore) [[ $# -ge 2 ]] || fail "--ignore requires a value"; ignore="$2"; shift 2 ;;
      --token-budget) [[ $# -ge 2 ]] || fail "--token-budget requires a value"; token_budget="$2"; shift 2 ;;
      --name) [[ $# -ge 2 ]] || fail "--name requires a value"; name="$2"; shift 2 ;;
      --no-security-check|--remote|--remote-trust-config|--force)
        fail "option is forbidden by the governed wrapper: $1"
        ;;
      *) fail "unknown pack argument: $1" ;;
    esac
  done

  [[ "$mode" == 'compact' || "$mode" == 'full' || "$mode" == 'metadata' ]] \
    || fail "--mode must be compact, full or metadata"
  local ext
  ext="$(extension_for_style "$style" 2>/dev/null || true)"
  [[ -n "$ext" ]] || fail "--style must be markdown, xml, json or plain"
  [[ "$token_budget" =~ ^[0-9]+$ && "$token_budget" -gt 0 ]] \
    || fail "--token-budget must be a positive integer"
  name="$(sanitize_name "$name")"
  [[ -n "$name" ]] || name='context'

  local bin order dir stamp out log meta rc sha bytes
  bin="$(require_pinned_bin)"
  order="$(active_order)"
  dir="$order/evidence/context"
  mkdir -p "$dir"
  stamp="$(date '+%Y%m%d-%H%M%S')"
  out="$dir/${stamp}-repomix-${name}-${mode}.${ext}"
  log="$out.log"
  meta="$out.meta.md"

  local -a args=(
    "$REPO_ROOT"
    --output "$out"
    --style "$style"
    --parsable-style
    --output-show-line-numbers
    --token-count-tree 500
    --top-files-len 20
    --token-budget "$token_budget"
  )
  [[ "$mode" == 'compact' ]] && args+=(--compress)
  [[ "$mode" == 'metadata' ]] && args+=(--no-files)
  [[ -n "$include" ]] && args+=(--include "$include")
  [[ -n "$ignore" ]] && args+=(--ignore "$ignore")

  set +e
  (
    cd "$REPO_ROOT"
    "$bin" "${args[@]}"
  ) 2>&1 | tee "$log"
  rc=${PIPESTATUS[0]}
  set -e

  [[ -f "$out" ]] || fail "Repomix did not produce the expected output (rc=$rc)"
  sha="$(sha256sum "$out" | awk '{print $1}')"
  bytes="$(wc -c < "$out" | tr -d ' ')"
  sha256sum "$out" > "$out.sha256"

  {
    printf '# Repomix context artifact\n\n'
    printf -- '- Created: %s\n' "$(date -Iseconds)"
    printf -- '- Repository: %s\n' "$REPO_ROOT"
    printf -- '- HEAD: `%s`\n' "$(git -C "$REPO_ROOT" rev-parse HEAD)"
    printf -- '- Worktree changes: %s\n' "$(git -C "$REPO_ROOT" status --porcelain=v1 | wc -l | tr -d ' ')"
    printf -- '- Repomix: `%s`\n' "$PINNED_VERSION"
    printf -- '- Mode: `%s`\n' "$mode"
    printf -- '- Style: `%s`\n' "$style"
    printf -- '- Token budget: `%s`\n' "$token_budget"
    printf -- '- Include: `%s`\n' "${include:-DEFAULT}"
    printf -- '- Additional ignore: `%s`\n' "${ignore:-NONE}"
    printf -- '- `.repomixignore`: enabled when present\n'
    printf -- '- Security check: enabled/mandatory\n'
    printf -- '- Remote packing: not used\n'
    printf -- '- Bytes: `%s`\n' "$bytes"
    printf -- '- SHA-256: `%s`\n' "$sha"
    printf -- '- Repomix exit code: `%s`\n' "$rc"
    printf '\nThis artifact is context transport, not a backup or source of truth. Review before sharing externally.\n'
  } > "$meta"

  if [[ -f "$WORKFLOW" ]]; then
    bash "$WORKFLOW" note "Repomix context pack created: ${out#"$order/"}; mode=$mode; sha256=$sha; rc=$rc. Security scan remained enabled." || true
  fi

  printf 'CONTEXT_PACK=%s\n' "$out"
  printf 'CONTEXT_META=%s\n' "$meta"
  printf 'CONTEXT_SHA256=%s\n' "$sha"
  printf 'REPOMIX_RC=%s\n' "$rc"
  [[ "$rc" -eq 0 ]] || return "$rc"
}

run_mcp() {
  local bin order
  bin="$(require_pinned_bin)"
  order="$(active_order)"
  if [[ -f "$WORKFLOW" ]]; then
    bash "$WORKFLOW" note "Starting Repomix MCP in mandatory sandbox mode, confined to repository root. Remote packing and skill generation are disabled by Repomix sandbox mode." || true
  fi
  printf 'REPOMIX_MCP_SANDBOX=%s\n' "$REPO_ROOT" >&2
  printf 'WORK_ORDER=%s\n' "$order" >&2
  cd "$REPO_ROOT"
  exec "$bin" --mcp --sandbox "$REPO_ROOT"
}

doctor() {
  require_node22
  local bin version
  bin="$(require_pinned_bin)"
  version="$(repomix_version "$bin")"
  [[ -f "$REPO_ROOT/.repomixignore" ]] || fail "missing .repomixignore"
  bash -n "$REPO_ROOT/tools/context-pack.sh"
  printf 'REPOMIX_VERSION=%s\n' "$version"
  printf 'REPOMIX_BIN=%s\n' "$bin"
  printf 'REPOMIX_LICENSE=MIT\n'
  printf 'NODE=%s\n' "$(node --version)"
  printf 'SECURITY_CHECK=MANDATORY\n'
  printf 'MCP_SANDBOX=MANDATORY\n'
  printf 'REMOTE_PACKING=DISABLED_BY_WRAPPER\n'
  printf 'CONTEXT_PACK_DOCTOR=OK\n'
}

case "${1:-}" in
  status) shift; status "$@" ;;
  install) shift; install_repomix "$@" ;;
  doctor) shift; doctor "$@" ;;
  pack) shift; pack_context "$@" ;;
  mcp) shift; run_mcp "$@" ;;
  -h|--help|help|'') usage ;;
  *) usage >&2; fail "unknown command: $1" ;;
esac
