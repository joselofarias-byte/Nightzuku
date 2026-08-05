#!/usr/bin/env bash
set -Eeuo pipefail

readonly CODEGRAPH_PIN="0.9.6"
readonly GRAPHIFY_VERSION="0.9.23"
readonly SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
readonly REPO_ROOT="$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel 2>/dev/null || true)"

export CODEGRAPH_TELEMETRY=0
export DO_NOT_TRACK=1
export GRAPHIFY_QUERY_LOG_DISABLE=1
export PATH="$HOME/.local/bin:$PATH"

fail() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 1
}

on_error() {
  local rc=$?
  printf 'ERROR: command failed (rc=%s) at line %s: %s\n' \
    "$rc" "${BASH_LINENO[0]:-?}" "${BASH_COMMAND:-?}" >&2
  exit "$rc"
}
trap on_error ERR

usage() {
  cat <<'USAGE'
Usage: tools/knowledge-graph.sh <command>

Commands:
  install    Install pinned CodeGraph and Graphify, disable telemetry/logging,
             and register project-aware agent integrations globally.
  index      Build/update the local CodeGraph and Graphify indexes.
  sync       Incrementally synchronize CodeGraph and Graphify.
  status     Validate installations and print local index status.
  obsidian   Export human-readable reports to the Engineering-KB vault.
  all        Run install, index, status and obsidian.

Optional environment:
  OBSIDIAN_VAULT=/absolute/path/to/Engineering-KB
USAGE
}

require_repo() {
  [[ -n "$REPO_ROOT" ]] || fail "the script is not inside a Git repository"
  [[ -f "$REPO_ROOT/settings.gradle" ]] || fail "settings.gradle not found in $REPO_ROOT"
  grep -q "include ':server', ':starter', ':shell'" "$REPO_ROOT/settings.gradle" \
    || fail "repository does not match Nightzuku"

  case "$REPO_ROOT" in
    /sdcard/*|/storage/emulated/*|/mnt/media_rw/*)
      fail "move the checkout to Debian's native filesystem before indexing: $REPO_ROOT"
      ;;
  esac
}

require_linux() {
  [[ "$(uname -s)" == "Linux" ]] || fail "this integration targets Debian/Linux"
  case "$(uname -m)" in
    aarch64|arm64|x86_64|amd64) ;;
    *) fail "unsupported architecture: $(uname -m)" ;;
  esac
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || fail "missing required command: $1"
}

require_python_310() {
  require_command python3
  python3 - <<'PY'
import sys
if sys.version_info < (3, 10):
    raise SystemExit("Python 3.10+ is required")
PY
}

install_uv() {
  if command -v uv >/dev/null 2>&1; then
    return
  fi

  require_command curl
  local tmp
  tmp="$(mktemp)"
  curl -LsSf https://astral.sh/uv/install.sh -o "$tmp"
  sh "$tmp"
  rm -f "$tmp"
  export PATH="$HOME/.local/bin:$PATH"
  command -v uv >/dev/null 2>&1 || fail "uv installation completed but uv is not on PATH"
}

install_codegraph() {
  require_command curl

  local current=""
  if command -v codegraph >/dev/null 2>&1; then
    current="$(codegraph --version 2>/dev/null | grep -Eo '[0-9]+\.[0-9]+\.[0-9]+' | head -n1 || true)"
  fi

  if [[ "$current" != "$CODEGRAPH_PIN" ]]; then
    local tmp
    tmp="$(mktemp)"
    curl -fsSL https://raw.githubusercontent.com/colbymchenry/codegraph/main/install.sh -o "$tmp"
    CODEGRAPH_VERSION="v$CODEGRAPH_PIN" sh "$tmp"
    rm -f "$tmp"
    export PATH="$HOME/.local/bin:$PATH"
  fi

  require_command codegraph
  codegraph telemetry off >/dev/null
  codegraph install --target=claude,codex,gemini --location=global --yes --no-permissions
}

install_graphify() {
  install_uv
  uv tool install --force "graphifyy==$GRAPHIFY_VERSION"
  require_command graphify

  graphify install
  graphify install --platform codex
  graphify install --platform gemini
}

install_tools() {
  require_linux
  require_python_310
  install_codegraph
  install_graphify
  printf 'Installed CodeGraph %s and Graphify %s.\n' \
    "$CODEGRAPH_PIN" "$GRAPHIFY_VERSION"
}

index_codegraph() {
  require_command codegraph
  if [[ -d "$REPO_ROOT/.codegraph" ]]; then
    codegraph index "$REPO_ROOT" --force --quiet
  else
    codegraph init "$REPO_ROOT"
  fi
}

index_graphify() {
  require_command graphify
  (
    cd "$REPO_ROOT"
    graphify extract . --code-only
  )
}

index_all() {
  index_codegraph
  index_graphify
}

sync_all() {
  require_command codegraph
  require_command graphify

  codegraph sync "$REPO_ROOT"
  (
    cd "$REPO_ROOT"
    if [[ -f graphify-out/graph.json ]]; then
      graphify extract . --code-only --update
    else
      graphify extract . --code-only
    fi
  )
}

resolve_vault() {
  if [[ -n "${OBSIDIAN_VAULT:-}" ]]; then
    printf '%s\n' "$OBSIDIAN_VAULT"
    return
  fi

  local candidate
  for candidate in \
    /storage/emulated/0/Documents/Engineering-KB \
    /sdcard/Documents/Engineering-KB \
    "$HOME/Engineering-KB"; do
    if [[ -d "$candidate" ]]; then
      printf '%s\n' "$candidate"
      return
    fi
  done

  printf '%s\n' "$HOME/Engineering-KB"
}

export_obsidian() {
  require_command codegraph

  local vault project_dir commit branch generated
  vault="$(resolve_vault)"
  project_dir="$vault/Projects/Nightzuku"
  commit="$(git -C "$REPO_ROOT" rev-parse HEAD)"
  branch="$(git -C "$REPO_ROOT" branch --show-current)"
  generated="$(date -u '+%Y-%m-%dT%H:%M:%SZ')"

  mkdir -p "$project_dir/Attachments"

  if [[ -f "$REPO_ROOT/graphify-out/GRAPH_REPORT.md" ]]; then
    cp -f "$REPO_ROOT/graphify-out/GRAPH_REPORT.md" \
      "$project_dir/Graphify Report.md"
  fi
  if [[ -f "$REPO_ROOT/graphify-out/graph.html" ]]; then
    cp -f "$REPO_ROOT/graphify-out/graph.html" \
      "$project_dir/Attachments/graph.html"
  fi

  codegraph status "$REPO_ROOT" > "$project_dir/CodeGraph Status.txt"

  cat > "$project_dir/Project Status.md" <<NOTE
---
project: Nightzuku
repository: joselofarias-byte/Nightzuku
branch: "$branch"
commit: "$commit"
generated_utc: "$generated"
---

# Nightzuku

- [[Graphify Report]]
- [[Architecture/Knowledge Graphs]]
- CodeGraph status: [[CodeGraph Status.txt]]
- Interactive graph: [[Attachments/graph.html]]

## Refresh

\`\`\`bash
bash tools/knowledge-graph.sh sync
bash tools/knowledge-graph.sh obsidian
\`\`\`
NOTE

  printf 'Obsidian export written to %s\n' "$project_dir"
}

show_status() {
  require_command codegraph
  require_command graphify

  printf 'Repository: %s\n' "$REPO_ROOT"
  printf 'CodeGraph: %s\n' "$(codegraph --version 2>/dev/null || printf unknown)"
  printf 'Graphify: %s\n' "$(graphify --version 2>/dev/null || printf unknown)"
  codegraph status "$REPO_ROOT"

  if [[ -f "$REPO_ROOT/graphify-out/graph.json" ]]; then
    printf 'Graphify graph: %s\n' "$REPO_ROOT/graphify-out/graph.json"
  else
    printf 'Graphify graph: NOT INITIALIZED\n'
  fi
}

main() {
  require_repo
  case "${1:-}" in
    install) install_tools ;;
    index) index_all ;;
    sync) sync_all ;;
    status) show_status ;;
    obsidian) export_obsidian ;;
    all)
      install_tools
      index_all
      show_status
      export_obsidian
      ;;
    -h|--help|help|'') usage ;;
    *) usage >&2; fail "unknown command: $1" ;;
  esac
}

main "$@"
