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

origin="$(git -C "$REPO_ROOT" remote get-url origin 2>/dev/null || true)"
repo_name="$(basename "$REPO_ROOT")"
if [[ -n "$origin" ]]; then
  repo_name="$(printf '%s' "$origin" | sed -E 's#^[^:]+://##; s#^[^@]+@[^:]+:##; s#\.git$##; s#/#-#g')"
fi
repo_name="$(printf '%s' "$repo_name" | tr -cs '[:alnum:]_. ' '-' | sed -E 's/^-+//; s/-+$//')"
STATE_BASE="${LLM_WORK_ROOT:-$HOME/.local/state/llm-work}"
STATE_ROOT="$STATE_BASE/$repo_name"

usage() {
  cat <<'USAGE'
Canonical self-documenting work command.

Usage: bash tools/work.sh <command> [args]

Commands:
  install
  start --agent NAME --objective TEXT [--structural]
  start --light --agent NAME --objective TEXT
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

`--light` is for read-only analysis. It requires a clean checkout, creates no Git
bundle, blocks commits, skips heavy checkpoints/index refresh at close, and
refuses to finish if HEAD or the worktree changed.

`context` delegates to the governed Repomix wrapper and requires an active order.
Use this wrapper instead of invoking llm-workflow.sh directly for normal work.
USAGE
}

now_iso() {
  date -Iseconds
}

slugify() {
  printf '%s' "$*" |
    tr '[:upper:]' '[:lower:]' |
    tr -cs '[:alnum:]' '-' |
    sed -E 's/^-+//; s/-+$//; s/^(.{1,48}).*$/\1/'
}

write_manifest() {
  local dir="$1"
  (
    cd "$dir"
    find . -type f ! -name MANIFEST.sha256 -print0 |
      LC_ALL=C sort -z |
      xargs -0 sha256sum > MANIFEST.sha256
  )
}

active_order_path() {
  [[ -s "$ACTIVE_FILE" ]] || return 1
  local order
  order="$(cat "$ACTIVE_FILE")"
  [[ -d "$order" && -f "$order/WORK-ORDER.md" ]] || return 1
  printf '%s\n' "$order"
}

is_light_order() {
  local order="${1:-}"
  [[ -n "$order" && -f "$order/MODE.txt" ]] || return 1
  [[ "$(cat "$order/MODE.txt")" == 'light' ]]
}

start_light() {
  local agent="" objective=""
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --light) shift ;;
      --agent)
        [[ $# -ge 2 ]] || { echo 'ERROR: --agent requires a value' >&2; exit 1; }
        agent="$2"; shift 2 ;;
      --objective)
        [[ $# -ge 2 ]] || { echo 'ERROR: --objective requires a value' >&2; exit 1; }
        objective="$2"; shift 2 ;;
      --structural)
        echo 'ERROR: --light and --structural are mutually exclusive.' >&2
        exit 1 ;;
      *)
        printf 'ERROR: unknown light start argument: %s\n' "$1" >&2
        exit 1 ;;
    esac
  done

  [[ -n "$agent" ]] || agent="${LLM_AGENT:-unknown-llm}"
  [[ -n "$objective" ]] || { echo 'ERROR: light start requires --objective TEXT' >&2; exit 1; }
  [[ ! -s "$ACTIVE_FILE" ]] || {
    printf 'ERROR: a work order is already active: %s\n' "$(cat "$ACTIVE_FILE")" >&2
    exit 1
  }
  [[ -z "$(git -C "$REPO_ROOT" status --porcelain=v1 -uall)" ]] || {
    echo 'ERROR: --light requires a clean checkout; use a normal work order when changes already exist.' >&2
    git -C "$REPO_ROOT" status --short >&2
    exit 1
  }

  bash "$ENGINE" install >/dev/null
  mkdir -p "$STATE_ROOT"
  chmod 700 "$STATE_BASE" "$STATE_ROOT" 2>/dev/null || true

  local stamp slug order_dir suffix=0
  stamp="$(date '+%Y%m%d-%H%M%S')"
  slug="$(slugify "$objective")"
  [[ -n "$slug" ]] || slug='read-only-analysis'
  order_dir="$STATE_ROOT/$stamp-$slug"
  while [[ -e "$order_dir" ]]; do
    suffix=$((suffix + 1))
    order_dir="$STATE_ROOT/$stamp-$slug-$suffix"
  done

  mkdir -p "$order_dir/backup" "$order_dir/evidence/baseline" "$order_dir/evidence/runs"
  git -C "$REPO_ROOT" status --short --branch > "$order_dir/backup/status.txt"
  {
    printf 'captured_at=%s\n' "$(now_iso)"
    printf 'repository=%s\n' "$REPO_ROOT"
    printf 'origin=%s\n' "$origin"
    printf 'branch=%s\n' "$(git -C "$REPO_ROOT" branch --show-current)"
    printf 'head=%s\n' "$(git -C "$REPO_ROOT" rev-parse HEAD)"
    printf 'mode=light\n'
  } > "$order_dir/backup/repository-metadata.txt"
  if command -v codegraph >/dev/null 2>&1; then
    codegraph status "$REPO_ROOT" > "$order_dir/evidence/baseline/codegraph-status.txt" 2>&1 || true
  fi

  printf '%s\n' "$agent" > "$order_dir/AGENT.txt"
  printf '%s\n' "$objective" > "$order_dir/OBJECTIVE.txt"
  printf '0\n' > "$order_dir/STRUCTURAL.txt"
  printf 'light\n' > "$order_dir/MODE.txt"
  git -C "$REPO_ROOT" rev-parse HEAD > "$order_dir/BASE-HEAD.txt"

  cat > "$order_dir/WORK-ORDER.md" <<ORDER
---
id: $(basename "$order_dir")
status: active
project: $repo_name
agent: $agent
opened_at: $(now_iso)
mode: light
structural_change: 0
base_branch: $(git -C "$REPO_ROOT" branch --show-current)
base_commit: $(git -C "$REPO_ROOT" rev-parse HEAD)
---

# Orden de trabajo liviana

## Objetivo

$objective

## Política read-only

- Sin bundle Git ni archivo de untracked: el checkout debe iniciar limpio y permanecer limpio.
- Sin commits, checkpoints pesados, Graphify ni refresco de índices al cierre.
- CodeGraph puede consultarse; grep/lectura directa completan cobertura.
- Codebase Memory sólo si aporta una capacidad diferencial concreta.
- Repomix compact se usa después de cerrar el conjunto relevante, cuando reduzca contexto para el LLM.
- El cierre falla si cambia HEAD o aparece cualquier cambio en el worktree.

## Notas y decisiones

ORDER

  printf '%s\n' "$order_dir" > "$ACTIVE_FILE"
  write_manifest "$order_dir"
  printf 'WORK_ORDER=%s\nMODE=light\n' "$order_dir"
}

finish_light() {
  local summary="${*:-Read-only analysis completed.}" order base_head final_head
  order="$(active_order_path)" || { echo 'ERROR: no active work order' >&2; exit 1; }
  is_light_order "$order" || { echo 'ERROR: active order is not light' >&2; exit 1; }
  base_head="$(cat "$order/BASE-HEAD.txt")"
  final_head="$(git -C "$REPO_ROOT" rev-parse HEAD)"
  [[ "$final_head" == "$base_head" ]] || {
    echo 'ERROR: light work order changed HEAD; finish refused.' >&2
    exit 1
  }
  [[ -z "$(git -C "$REPO_ROOT" status --porcelain=v1 -uall)" ]] || {
    echo 'ERROR: light work order changed the worktree; finish refused.' >&2
    git -C "$REPO_ROOT" status --short >&2
    exit 1
  }

  mkdir -p "$order/evidence/final"
  git -C "$REPO_ROOT" status --short --branch > "$order/evidence/final/status.txt"
  cat >> "$order/WORK-ORDER.md" <<ORDER

## Cierre liviano

- Cerrada: $(now_iso)
- Resumen: $summary
- HEAD sin cambios: $final_head
- Worktree limpio: sí
- Refresco de índices: omitido por modo light
- Backup Git completo: omitido por diseño read-only
ORDER
  sed -i 's/^status: active$/status: closed/' "$order/WORK-ORDER.md"
  printf '%s\n' "$order" > "$LAST_FILE"
  rm -f "$ACTIVE_FILE"
  write_manifest "$order"
  printf 'CLOSED_WORK_ORDER=%s\nMODE=light\n' "$order"
}

abort_light() {
  local reason="${*:-Read-only analysis aborted.}" order
  order="$(active_order_path)" || { echo 'ERROR: no active work order' >&2; exit 1; }
  is_light_order "$order" || { echo 'ERROR: active order is not light' >&2; exit 1; }
  mkdir -p "$order/evidence/final"
  git -C "$REPO_ROOT" status --short --branch > "$order/evidence/final/status.txt"
  cat >> "$order/WORK-ORDER.md" <<ORDER

## Aborto liviano

- Fecha: $(now_iso)
- Motivo: $reason
- No se generó bundle Git por tratarse de una orden read-only.
ORDER
  sed -i 's/^status: active$/status: aborted/' "$order/WORK-ORDER.md"
  printf '%s\n' "$order" > "$LAST_FILE"
  rm -f "$ACTIVE_FILE"
  write_manifest "$order"
  printf 'ABORTED_WORK_ORDER=%s\nMODE=light\n' "$order"
}

snapshot_active() {
  local label="$1" order=""
  [[ -s "$ACTIVE_FILE" ]] && order="$(cat "$ACTIVE_FILE")"
  [[ -n "$order" && -d "$order" ]] || return 0
  is_light_order "$order" && return 0
  local dir="$order/evidence/system-snapshots"
  mkdir -p "$dir"
  bash "$DOCS" snapshot "$dir/$(date '+%Y%m%d-%H%M%S')-$label.md" >/dev/null || true
}

snapshot_last() {
  local label="$1" order=""
  [[ -s "$LAST_FILE" ]] && order="$(cat "$LAST_FILE")"
  [[ -n "$order" && -d "$order" ]] || return 0
  is_light_order "$order" && return 0
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
    light=0
    for arg in "$@"; do
      [[ "$arg" == '--light' ]] && light=1
    done
    if [[ "$light" == '1' ]]; then
      start_light "$@"
    else
      bash "$DOCS" doctor >/dev/null
      bash "$ENGINE" start "$@"
      snapshot_active start
      publish_quiet
    fi
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
    order="$(active_order_path 2>/dev/null || true)"
    if is_light_order "$order"; then
      echo 'ERROR: checkpoints are intentionally disabled in --light mode; start a normal order before modifying code.' >&2
      exit 1
    fi
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
    order="$(active_order_path 2>/dev/null || true)"
    if is_light_order "$order"; then
      finish_light "$@"
      bash "$DOCS" record closed "$summary"
    else
      bash "$ENGINE" finish "$@"
      bash "$DOCS" record closed "$summary"
      snapshot_last finish
      publish_quiet
    fi
    ;;
  abort)
    shift
    reason="${*:-Aborted without a supplied reason.}"
    order="$(active_order_path 2>/dev/null || true)"
    if is_light_order "$order"; then
      abort_light "$@"
      bash "$DOCS" record aborted "$reason"
    else
      bash "$ENGINE" abort "$@"
      bash "$DOCS" record aborted "$reason"
      snapshot_last abort
      publish_quiet
    fi
    ;;
  verify)
    shift
    bash -n "$REPO_ROOT/tools/work.sh"
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
