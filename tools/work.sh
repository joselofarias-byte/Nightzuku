#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
REPO_ROOT="$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel 2>/dev/null || true)"
[[ -n "$REPO_ROOT" ]] || { echo 'ERROR: not inside a Git repository' >&2; exit 1; }
ENGINE="$REPO_ROOT/tools/llm-workflow.sh"
DOCS="$REPO_ROOT/tools/system-docs.sh"
CONTEXT="$REPO_ROOT/tools/context-pack.sh"
GIT_DIR="$(git -C "$REPO_ROOT" rev-parse --absolute-git-dir)"
ACTIVE_FILE="$GIT_DIR/llm-work-current"
LAST_FILE="$GIT_DIR/llm-work-last"

[[ -f "$ENGINE" ]] || { echo 'ERROR: missing llm-workflow.sh' >&2; exit 1; }
[[ -f "$DOCS" ]] || { echo 'ERROR: missing system-docs.sh' >&2; exit 1; }
[[ -f "$CONTEXT" ]] || { echo 'ERROR: missing context-pack.sh' >&2; exit 1; }

usage() {
  cat <<'USAGE'
Canonical self-documenting work command.

Usage: bash tools/work.sh <command> [args]

Commands:
  install
  start --agent NAME --objective TEXT [--structural]
  status
  note TEXT
  run -- COMMAND [ARG ...]
  checkpoint [LABEL]
  context [Repomix context-pack options]
  finish [SUMMARY]
  abort [REASON]
  verify
  docs
  history

`context` delegates to the governed Repomix wrapper and requires an active order.
Use this wrapper instead of invoking llm-workflow.sh directly for normal work.
USAGE
}

snapshot_active() {
  local label="$1" order=""
  [[ -s "$ACTIVE_FILE" ]] && order="$(cat "$ACTIVE_FILE")"
  [[ -n "$order" && -d "$order" ]] || return 0
  local dir="$order/evidence/system-snapshots"
  mkdir -p "$dir"
  bash "$DOCS" snapshot "$dir/$(date '+%Y%m%d-%H%M%S')-$label.md" >/dev/null || true
}

snapshot_last() {
  local label="$1" order=""
  [[ -s "$LAST_FILE" ]] && order="$(cat "$LAST_FILE")"
  [[ -n "$order" && -d "$order" ]] || return 0
  local dir="$order/evidence/system-snapshots"
  mkdir -p "$dir"
  bash "$DOCS" snapshot "$dir/$(date '+%Y%m%d-%H%M%S')-$label.md" >/dev/null || true
}

publish_quiet() {
  bash "$DOCS" publish || true
}

case "${1:-}" in
  install)
    shift
    bash "$ENGINE" install "$@"
    bash "$DOCS" doctor
    publish_quiet
    ;;
  start)
    shift
    bash "$DOCS" doctor >/dev/null
    bash "$ENGINE" start "$@"
    snapshot_active start
    publish_quiet
    ;;
  status)
    shift
    bash "$DOCS" summary
    printf '\n--- ENGINE ---\n'
    bash "$ENGINE" status "$@"
    ;;
  note)
    shift
    bash "$ENGINE" note "$@"
    ;;
  run)
    shift
    bash "$ENGINE" run "$@"
    ;;
  checkpoint)
    shift
    bash "$ENGINE" checkpoint "$@"
    snapshot_active checkpoint
    publish_quiet
    ;;
  context)
    shift
    bash "$CONTEXT" pack "$@"
    snapshot_active context-pack
    ;;
  finish)
    shift
    summary="${*:-Work completed; see recorded evidence.}"
    bash "$ENGINE" finish "$@"
    bash "$DOCS" record closed "$summary"
    snapshot_last finish
    publish_quiet
    ;;
  abort)
    shift
    reason="${*:-Aborted without a supplied reason.}"
    bash "$ENGINE" abort "$@"
    bash "$DOCS" record aborted "$reason"
    snapshot_last abort
    publish_quiet
    ;;
  verify)
    shift
    bash "$ENGINE" verify "$@"
    bash "$DOCS" doctor
    ;;
  docs)
    shift
    bash "$DOCS" summary
    ;;
  history)
    shift
    bash "$DOCS" history
    ;;
  -h|--help|help|'')
    usage
    ;;
  *)
    usage >&2
    echo "ERROR: unknown command: $1" >&2
    exit 1
    ;;
esac
