#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

fail() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 1
}

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
REPO_ROOT="$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel 2>/dev/null || true)"
[[ -n "$REPO_ROOT" ]] || fail "this script must run from a Git checkout"
WORKFLOW="$REPO_ROOT/tools/llm-workflow.sh"
[[ -f "$WORKFLOW" ]] || fail "missing tools/llm-workflow.sh"

usage() {
  cat <<'USAGE'
Usage:
  bash tools/muse-workflow.sh --objective "TEXT" [--structural] [--binary PATH] [-- MUSE_ARGS...]

The wrapper opens the mandatory recoverable work order before launching Meta Muse Code.
It does not install Muse Code, finish the order, commit, push or merge.
USAGE
}

objective=""
structural=0
binary="${MUSE_CODE_BIN:-}"
declare -a muse_args=()

while [[ $# -gt 0 ]]; do
  case "$1" in
    --objective)
      [[ $# -ge 2 ]] || fail "--objective requires a value"
      objective="$2"
      shift 2
      ;;
    --structural)
      structural=1
      shift
      ;;
    --binary)
      [[ $# -ge 2 ]] || fail "--binary requires a value"
      binary="$2"
      shift 2
      ;;
    --)
      shift
      muse_args=("$@")
      break
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      fail "unknown argument: $1"
      ;;
  esac
done

[[ -n "$objective" ]] || fail "--objective is required"

looks_like_meta_muse() {
  local candidate="$1" output=""
  [[ -x "$candidate" ]] || return 1
  if command -v timeout >/dev/null 2>&1; then
    output="$(timeout 8 "$candidate" --help 2>&1 || true)"
  else
    output="$("$candidate" --help 2>&1 || true)"
  fi
  printf '%s' "$output" | grep -Eqi 'Muse[[:space:]]+Code|Muse[[:space:]]+Spark|/plan|/grill|/goal|Meta[[:space:]]+Model'
}

resolve_binary() {
  local candidate path
  if [[ -n "$binary" ]]; then
    if [[ "$binary" == */* ]]; then
      path="$binary"
    else
      path="$(command -v "$binary" 2>/dev/null || true)"
    fi
    [[ -n "$path" && -x "$path" ]] || fail "Muse Code binary is not executable: $binary"
    looks_like_meta_muse "$path" || fail "binary does not identify itself as Meta Muse Code: $path"
    printf '%s\n' "$path"
    return
  fi

  for candidate in muse-code musecode muse; do
    path="$(command -v "$candidate" 2>/dev/null || true)"
    [[ -n "$path" ]] || continue
    if looks_like_meta_muse "$path"; then
      printf '%s\n' "$path"
      return
    fi
  done
  fail "Meta Muse Code was not found. Audit the official installer first with tools/audit-muse-installer.sh"
}

muse_bin="$(resolve_binary)"

start_args=(start --agent muse-code --objective "$objective")
if [[ "$structural" == "1" ]]; then
  start_args+=(--structural)
fi

if bash "$WORKFLOW" status | grep -q '^ACTIVE='; then
  fail "a work order is already active; finish or abort it before launching a new Muse Code session"
fi

bash "$WORKFLOW" "${start_args[@]}"
bash "$WORKFLOW" note "Muse Code session opened through the standard wrapper. Begin with /plan; use /grill before structural or high-risk implementation; use /goal only within the approved scope."

if [[ "$structural" == "1" ]]; then
  bash "$WORKFLOW" note "Structural order: /grill is required before editing and Graphify will refresh at finish when available."
fi

set +e
(
  cd "$REPO_ROOT"
  "$muse_bin" "${muse_args[@]}"
)
rc=$?
set -e

bash "$WORKFLOW" checkpoint "muse-code-session-exit-rc-$rc" >/dev/null || true
bash "$WORKFLOW" note "Muse Code process exited with rc=$rc. The order remains active for review; finish or abort it explicitly." || true
printf 'Muse Code exited with rc=%s. Work order remains active.\n' "$rc"
exit "$rc"
