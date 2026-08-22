#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
REPO_ROOT="$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel 2>/dev/null || true)"
[[ -n "$REPO_ROOT" ]] || { echo 'ERROR: not inside a Git repository' >&2; exit 1; }
ENGINE="$REPO_ROOT/tools/swarm-workflow.sh"
DOCS="$REPO_ROOT/tools/system-docs.sh"
CONTEXT="$REPO_ROOT/tools/context-pack.sh"
GIT_DIR="$(git -C "$REPO_ROOT" rev-parse --absolute-git-dir)"
ACTIVE_FILE="$GIT_DIR/llm-work-current"
LAST_FILE="$GIT_DIR/llm-work-last"

[[ -f "$ENGINE" ]] || { echo 'ERROR: missing swarm-workflow.sh' >&2; exit 1; }
[[ -f "$DOCS" ]] || { echo 'ERROR: missing system-docs.sh' >&2; exit 1; }
[[ -f "$CONTEXT" ]] || { echo 'ERROR: missing context-pack.sh' >&2; exit 1; }

usage() {
  cat <<'USAGE'
Canonical self-documenting two-role swarm command.

Usage: bash tools/swarm.sh <command> [args]

Delegates to swarm-workflow.sh and automatically captures/publishes system state.
Main commands:
  start --objective TEXT [--structural] [--implementer NAME] [--reviewer NAME]
  status
  prompt implementer|reviewer
  spawn implementer|reviewer -- COMMAND [ARG ...]
  attach implementer|reviewer
  handoff
  review-note TEXT
  context [Repomix context-pack options]
  finish [SUMMARY]
  abort [REASON]
  cleanup
  verify
  docs
  history
USAGE
}

snapshot_active() {
  local label="$1" order=""
  [[ -s "$ACTIVE_FILE" ]] && order="$(cat "$ACTIVE_FILE")"
  [[ -n "$order" && -d "$order" ]] || return 0
  local dir="$order/evidence/system-snapshots"
  mkdir -p "$dir"
  bash "$DOCS" snapshot "$dir/$(date '+%Y%m%d-%H%M%S')-swarm-$label.md" >/dev/null || true
}

snapshot_last() {
  local label="$1" order=""
  [[ -s "$LAST_FILE" ]] && order="$(cat "$LAST_FILE")"
  [[ -n "$order" && -d "$order" ]] || return 0
  local dir="$order/evidence/system-snapshots"
  mkdir -p "$dir"
  bash "$DOCS" snapshot "$dir/$(date '+%Y%m%d-%H%M%S')-swarm-$label.md" >/dev/null || true
}

publish_quiet() {
  bash "$DOCS" publish || true
}

cmd="${1:-}"
case "$cmd" in
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
    printf '\n--- SWARM ENGINE ---\n'
    bash "$ENGINE" status "$@"
    ;;
  handoff)
    shift
    bash "$ENGINE" handoff "$@"
    snapshot_active handoff
    publish_quiet
    ;;
  context)
    shift
    bash "$CONTEXT" pack "$@"
    snapshot_active context-pack
    ;;
  finish)
    shift
    summary="${*:-Swarm work completed; see recorded evidence.}"
    bash "$ENGINE" finish "$@"
    bash "$DOCS" record closed "$summary"
    snapshot_last finish
    publish_quiet
    ;;
  abort)
    shift
    reason="${*:-Swarm aborted without a supplied reason.}"
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
  prompt|spawn|attach|review-note|cleanup)
    shift
    bash "$ENGINE" "$cmd" "$@"
    ;;
  -h|--help|help|'')
    usage
    ;;
  *)
    usage >&2
    echo "ERROR: unknown command: $cmd" >&2
    exit 1
    ;;
esac
