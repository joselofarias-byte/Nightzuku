#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

PINNED_VERSION='1.18.0'
INSTALL_BASE="${ENGINEERING_TOOLS_HOME:-$HOME/.local/share/engineering-tools}"
INSTALL_ROOT="${REPOMIX_HOME:-$INSTALL_BASE/repomix/$PINNED_VERSION}"
PINNED_BIN="$INSTALL_ROOT/node_modules/.bin/repomix"
NODE22_HOME="${ENGINEERING_NODE22_HOME:-$INSTALL_BASE/node-v22}"
NODE22_BASE_URL='https://nodejs.org/dist/latest-v22.x'

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
  install             install/verify isolated runtime and Repomix 1.18.0
  install-runtime     install verified isolated Node 22 only when needed
  doctor
  pack [--mode compact|full|metadata] [--style markdown|xml|json|plain]
       [--include GLOBS] [--ignore GLOBS] [--token-budget N] [--name NAME]
  mcp

Policy:
- Repomix is pinned and installed outside application checkouts.
- if the host lacks Node >=22, Node 22 is downloaded from nodejs.org and SHA-256 verified into an isolated tool directory.
- pack and mcp require an active work order.
- output is stored under the active order's evidence/context directory.
- Secretlint/security checking remains enabled; --no-security-check is never used.
- repository Repomix configs are bypassed with a generated safe JSON config.
- remote repository packing and remote config trust are intentionally not exposed.
- MCP always runs with --sandbox confined to this repository.
USAGE
}

node_major_for() {
  local bin="$1"
  "$bin" -p 'Number(process.versions.node.split(".")[0])' 2>/dev/null
}

activate_node_runtime() {
  local current major
  current="$(command -v node 2>/dev/null || true)"
  if [[ -n "$current" ]]; then
    major="$(node_major_for "$current" || true)"
    if [[ "$major" =~ ^[0-9]+$ && "$major" -ge 22 ]]; then
      return 0
    fi
  fi
  if [[ -x "$NODE22_HOME/bin/node" ]]; then
    major="$(node_major_for "$NODE22_HOME/bin/node" || true)"
    if [[ "$major" =~ ^[0-9]+$ && "$major" -ge 22 ]]; then
      export PATH="$NODE22_HOME/bin:$PATH"
      return 0
    fi
  fi
  return 1
}

node_major() {
  activate_node_runtime >/dev/null 2>&1 || return 1
  node_major_for "$(command -v node)"
}

node_archive_arch() {
  case "$(uname -m)" in
    aarch64|arm64) printf 'arm64' ;;
    x86_64|amd64) printf 'x64' ;;
    *) return 1 ;;
  esac
}

install_node22() {
  if activate_node_runtime; then
    printf 'NODE_RUNTIME_ALREADY_OK=%s\n' "$(node --version)"
    return 0
  fi
  command -v curl >/dev/null 2>&1 || fail "curl is required to bootstrap Node 22"
  command -v tar >/dev/null 2>&1 || fail "tar is required to bootstrap Node 22"
  command -v xz >/dev/null 2>&1 || fail "xz is required to bootstrap Node 22"
  command -v sha256sum >/dev/null 2>&1 || fail "sha256sum is required"

  local arch sums tmp archive line filename expected actual extracted old cleanup
  arch="$(node_archive_arch 2>/dev/null || true)"
  [[ -n "$arch" ]] || fail "automatic Node 22 bootstrap supports only Linux arm64/aarch64 and x86_64"
  [[ "$(uname -s)" == 'Linux' ]] || fail "automatic Node 22 bootstrap is Linux-only"

  tmp="${NODE22_HOME}.tmp.$$"
  old="${NODE22_HOME}.old.$$"
  rm -rf "$tmp" "$old"
  mkdir -p "$tmp/download" "$tmp/root"
  printf -v cleanup 'rm -rf -- %q' "$tmp"
  trap "$cleanup" EXIT

  sums="$tmp/download/SHASUMS256.txt"
  curl --fail --location --silent --show-error \
    --proto '=https' --tlsv1.2 \
    "$NODE22_BASE_URL/SHASUMS256.txt" -o "$sums"

  line="$(grep -E "^[0-9a-f]{64}  node-v22[^ ]*-linux-${arch}\\.tar\\.xz$" "$sums" | tail -n1)"
  [[ -n "$line" ]] || fail "official Node 22 checksum list has no linux-$arch archive"
  expected="${line%%  *}"
  filename="${line#*  }"
  archive="$tmp/download/$filename"

  curl --fail --location --silent --show-error \
    --proto '=https' --tlsv1.2 \
    "$NODE22_BASE_URL/$filename" -o "$archive"
  actual="$(sha256sum "$archive" | awk '{print $1}')"
  [[ "$actual" == "$expected" ]] || fail "Node archive SHA-256 mismatch"

  tar -xJf "$archive" -C "$tmp/root" --strip-components=1
  [[ -x "$tmp/root/bin/node" && -x "$tmp/root/bin/npm" ]] || fail "extracted Node runtime is incomplete"
  local major
  major="$(node_major_for "$tmp/root/bin/node" || true)"
  [[ "$major" =~ ^[0-9]+$ && "$major" -ge 22 ]] || fail "downloaded runtime is not Node >=22"

  mkdir -p "$INSTALL_BASE"
  chmod 700 "$INSTALL_BASE" 2>/dev/null || true
  if [[ -e "$NODE22_HOME" ]]; then
    mv "$NODE22_HOME" "$old"
  fi
  mv "$tmp/root" "$NODE22_HOME"
  {
    printf 'source=%s/%s\n' "$NODE22_BASE_URL" "$filename"
    printf 'sha256=%s\n' "$expected"
    printf 'installed_at=%s\n' "$(date -Iseconds)"
  } > "$NODE22_HOME/ENGINEERING-RUNTIME.txt"
  rm -rf "$old" "$tmp/download" "$tmp"
  trap - EXIT
  export PATH="$NODE22_HOME/bin:$PATH"
  printf 'NODE_RUNTIME_INSTALLED=%s\n' "$NODE22_HOME"
  printf 'NODE_VERSION=%s\n' "$(node --version)"
  printf 'NODE_ARCHIVE_SHA256=%s\n' "$expected"
}

resolve_bin() {
  activate_node_runtime >/dev/null 2>&1 || true
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
  activate_node_runtime >/dev/null 2>&1 || true
  out="$("$bin" --version 2>/dev/null | head -n1 || true)"
  printf '%s' "$out" | grep -Eo '[0-9]+\.[0-9]+\.[0-9]+' | head -n1
}

status() {
  local brief=0
  [[ "${1:-}" == "--brief" ]] && brief=1
  activate_node_runtime >/dev/null 2>&1 || true
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
    printf 'NODE_ISOLATED_HOME=%s\n' "$NODE22_HOME"
    printf 'INSTALL_ROOT=%s\n' "$INSTALL_ROOT"
    printf 'LICENSE=MIT\n'
    printf 'SECURITY_CHECK=MANDATORY\n'
    printf 'LOCAL_REPOMIX_CONFIG=AUTOLOAD_BYPASSED\n'
    printf 'REMOTE_PACKING=DISABLED_BY_WRAPPER\n'
    printf 'MCP_SANDBOX=MANDATORY\n'
  fi
}

require_node22() {
  activate_node_runtime || fail "Node.js >=22 is required; run: bash tools/context-pack.sh install-runtime"
  command -v npm >/dev/null 2>&1 || fail "npm is required"
  local major
  major="$(node_major)"
  [[ "$major" =~ ^[0-9]+$ && "$major" -ge 22 ]] \
    || fail "Repomix $PINNED_VERSION requires Node.js >=22; found $(node --version 2>/dev/null || printf unknown)"
}

install_repomix() {
  activate_node_runtime >/dev/null 2>&1 || install_node22
  require_node22
  local tmp old bin version cleanup
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
  printf -v cleanup 'rm -rf -- %q' "$tmp"
  trap "$cleanup" EXIT
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
  trap - EXIT

  printf 'REPOMIX_INSTALLED=%s\n' "$PINNED_BIN"
  status
}

require_pinned_bin() {
  require_node22
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

write_safe_config() {
  local path="$1"
  cat > "$path" <<'JSON'
{
  "security": {
    "enableSecurityCheck": true
  }
}
JSON
  chmod 600 "$path" 2>/dev/null || true
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
      --no-security-check|--remote|--remote-trust-config|--force|--config)
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

  local bin order dir stamp out log meta safe_config rc sha bytes
  bin="$(require_pinned_bin)"
  order="$(active_order)"
  dir="$order/evidence/context"
  mkdir -p "$dir"
  stamp="$(date '+%Y%m%d-%H%M%S')"
  out="$dir/${stamp}-repomix-${name}-${mode}.${ext}"
  log="$out.log"
  meta="$out.meta.md"
  safe_config="$dir/${stamp}-repomix-safe-config.json"
  write_safe_config "$safe_config"

  local -a args=(
    "$REPO_ROOT"
    --config "$safe_config"
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
    printf -- '- Node: `%s`\n' "$(node --version)"
    printf -- '- Mode: `%s`\n' "$mode"
    printf -- '- Style: `%s`\n' "$style"
    printf -- '- Token budget: `%s`\n' "$token_budget"
    printf -- '- Include: `%s`\n' "${include:-DEFAULT}"
    printf -- '- Additional ignore: `%s`\n' "${ignore:-NONE}"
    printf -- '- `.repomixignore`: enabled when present\n'
    printf -- '- Security check: enabled/mandatory\n'
    printf -- '- Repository Repomix config: bypassed; generated safe config used\n'
    printf -- '- Safe config: `%s`\n' "${safe_config#"$order/"}"
    printf -- '- Remote packing: not used\n'
    printf -- '- Bytes: `%s`\n' "$bytes"
    printf -- '- SHA-256: `%s`\n' "$sha"
    printf -- '- Repomix exit code: `%s`\n' "$rc"
    printf '\nThis artifact is context transport, not a backup or source of truth. Review before sharing externally.\n'
  } > "$meta"

  if [[ -f "$WORKFLOW" ]]; then
    bash "$WORKFLOW" note "Repomix context pack created: ${out#"$order/"}; mode=$mode; sha256=$sha; rc=$rc. Security scan enabled; repository Repomix config bypassed." || true
  fi

  printf 'CONTEXT_PACK=%s\n' "$out"
  printf 'CONTEXT_META=%s\n' "$meta"
  printf 'CONTEXT_SHA256=%s\n' "$sha"
  printf 'REPOMIX_RC=%s\n' "$rc"
  [[ "$rc" -eq 0 ]] || return "$rc"
}

run_mcp() {
  local bin order dir safe_config
  bin="$(require_pinned_bin)"
  order="$(active_order)"
  dir="$order/evidence/context"
  mkdir -p "$dir"
  safe_config="$dir/repomix-mcp-safe-config.json"
  write_safe_config "$safe_config"
  if [[ -f "$WORKFLOW" ]]; then
    bash "$WORKFLOW" note "Starting Repomix MCP with generated safe config and mandatory sandbox mode confined to repository root. Remote packing and skill generation are disabled by Repomix sandbox mode." || true
  fi
  printf 'REPOMIX_MCP_SANDBOX=%s\n' "$REPO_ROOT" >&2
  printf 'REPOMIX_SAFE_CONFIG=%s\n' "$safe_config" >&2
  printf 'WORK_ORDER=%s\n' "$order" >&2
  cd "$REPO_ROOT"
  exec "$bin" --config "$safe_config" --mcp --sandbox "$REPO_ROOT"
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
  printf 'LOCAL_REPOMIX_CONFIG=AUTOLOAD_BYPASSED\n'
  printf 'MCP_SANDBOX=MANDATORY\n'
  printf 'REMOTE_PACKING=DISABLED_BY_WRAPPER\n'
  printf 'CONTEXT_PACK_DOCTOR=OK\n'
}

case "${1:-}" in
  status) shift; status "$@" ;;
  install) shift; install_repomix "$@" ;;
  install-runtime) shift; install_node22 "$@" ;;
  doctor) shift; doctor "$@" ;;
  pack) shift; pack_context "$@" ;;
  mcp) shift; run_mcp "$@" ;;
  -h|--help|help|'') usage ;;
  *) usage >&2; fail "unknown command: $1" ;;
esac
