#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

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

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
REPO_ROOT="$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel 2>/dev/null || true)"
[[ -n "$REPO_ROOT" ]] || fail "tools/llm-workflow.sh must run inside a Git repository"
GIT_DIR="$(git -C "$REPO_ROOT" rev-parse --absolute-git-dir)"
ACTIVE_FILE="$GIT_DIR/llm-work-current"
LAST_FILE="$GIT_DIR/llm-work-last"

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
Usage: bash tools/llm-workflow.sh <command> [arguments]

Commands:
  install
      Activate repository Git hooks for the standard LLM workflow.

  start --agent NAME --objective TEXT [--structural]
      Create a recoverable Git backup, open a work order and activate guards.

  status
      Show the active or most recent work order.

  note TEXT
      Append a timestamped investigation or decision note.

  run -- COMMAND [ARG ...]
      Execute a validation command and preserve its complete output and exit code.

  checkpoint [LABEL]
      Capture current Git and code-intelligence evidence.

  finish [SUMMARY]
      Refresh indexes when available, capture final evidence and close the order.

  abort [REASON]
      Preserve current evidence and close the order as aborted.

  verify
      Verify scripts, policy files and local hook activation.
USAGE
}

slugify() {
  printf '%s' "$*" |
    tr '[:upper:]' '[:lower:]' |
    tr -cs '[:alnum:]' '-' |
    sed -E 's/^-+//; s/-+$//; s/^(.{1,48}).*$/\1/'
}

now_iso() {
  date -Iseconds
}

active_dir() {
  [[ -s "$ACTIVE_FILE" ]] || fail "no active work order; run: bash tools/llm-workflow.sh start ..."
  local dir
  dir="$(cat "$ACTIVE_FILE")"
  [[ -d "$dir" && -f "$dir/WORK-ORDER.md" ]] || fail "active work order is missing or invalid: $dir"
  printf '%s\n' "$dir"
}

assert_git_safe() {
  local marker
  for marker in MERGE_HEAD CHERRY_PICK_HEAD REVERT_HEAD rebase-merge rebase-apply; do
    [[ ! -e "$GIT_DIR/$marker" ]] || fail "unfinished Git operation detected: $marker"
  done
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

capture_repo_state() {
  local out="$1"
  mkdir -p "$out"

  git -C "$REPO_ROOT" status --short --branch > "$out/status.txt"
  git -C "$REPO_ROOT" diff --binary > "$out/unstaged.patch"
  git -C "$REPO_ROOT" diff --cached --binary > "$out/staged.patch"
  git -C "$REPO_ROOT" diff --name-status > "$out/unstaged-name-status.txt"
  git -C "$REPO_ROOT" diff --cached --name-status > "$out/staged-name-status.txt"
  git -C "$REPO_ROOT" branch -vv > "$out/branches.txt"
  git -C "$REPO_ROOT" worktree list --porcelain > "$out/worktrees.txt"
  git -C "$REPO_ROOT" log --all --graph --decorate --oneline -n 250 > "$out/log.txt"
  git -C "$REPO_ROOT" ls-files --others --exclude-standard -z > "$out/untracked.list0"
  tr '\0' '\n' < "$out/untracked.list0" > "$out/untracked.txt"

  {
    printf 'captured_at=%s\n' "$(now_iso)"
    printf 'repository=%s\n' "$REPO_ROOT"
    printf 'origin=%s\n' "$origin"
    printf 'branch=%s\n' "$(git -C "$REPO_ROOT" branch --show-current)"
    printf 'head=%s\n' "$(git -C "$REPO_ROOT" rev-parse HEAD)"
    printf 'git_dir=%s\n' "$GIT_DIR"
  } > "$out/repository-metadata.txt"
}

capture_untracked_archive() {
  local out="$1"
  local list="$out/untracked.list0"
  if [[ -s "$list" ]]; then
    (
      cd "$REPO_ROOT"
      tar --null --files-from="$list" -czf "$out/untracked.tar.gz"
    )
  else
    printf 'No untracked files at capture time.\n' > "$out/untracked-empty.txt"
  fi
}

capture_code_intelligence() {
  local out="$1"
  mkdir -p "$out"

  {
    printf 'captured_at=%s\n' "$(now_iso)"
    printf 'repository=%s\n' "$REPO_ROOT"
    printf 'branch=%s\n' "$(git -C "$REPO_ROOT" branch --show-current)"
    printf 'commit=%s\n' "$(git -C "$REPO_ROOT" rev-parse HEAD)"
    if command -v codegraph >/dev/null 2>&1; then
      printf 'codegraph_version='; codegraph --version || true
    else
      printf 'codegraph_version=NOT_INSTALLED\n'
    fi
    if command -v graphify >/dev/null 2>&1; then
      printf 'graphify_version='; graphify --version || true
    else
      printf 'graphify_version=NOT_INSTALLED\n'
    fi
  } > "$out/versions.txt"

  if command -v codegraph >/dev/null 2>&1; then
    codegraph status "$REPO_ROOT" > "$out/codegraph-status.txt" 2>&1 || true
  fi

  {
    printf 'vault=%s\n' "${OBSIDIAN_VAULT:-/storage/emulated/0/Documents/Engineering-KB}"
    if [[ -f "$REPO_ROOT/graphify-out/GRAPH_REPORT.md" ]]; then
      sha256sum "$REPO_ROOT/graphify-out/GRAPH_REPORT.md"
    fi
    if [[ -f "$REPO_ROOT/graphify-out/graph.html" ]]; then
      sha256sum "$REPO_ROOT/graphify-out/graph.html"
    fi
  } > "$out/graphify-evidence.txt"
}

install_hooks() {
  [[ -d "$REPO_ROOT/.githooks" ]] || fail "missing .githooks directory"
  [[ -f "$REPO_ROOT/.githooks/pre-commit" ]] || fail "missing .githooks/pre-commit"
  [[ -f "$REPO_ROOT/.githooks/commit-msg" ]] || fail "missing .githooks/commit-msg"
  chmod u+x "$REPO_ROOT/tools/llm-workflow.sh" \
    "$REPO_ROOT/.githooks/pre-commit" \
    "$REPO_ROOT/.githooks/commit-msg"
  git -C "$REPO_ROOT" config core.fileMode false
  git -C "$REPO_ROOT" config core.hooksPath .githooks
  printf 'Git hooks activated: %s/.githooks\n' "$REPO_ROOT"
}

start_order() {
  local agent="" objective="" structural=0
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --agent)
        [[ $# -ge 2 ]] || fail "--agent requires a value"
        agent="$2"; shift 2 ;;
      --objective)
        [[ $# -ge 2 ]] || fail "--objective requires a value"
        objective="$2"; shift 2 ;;
      --structural)
        structural=1; shift ;;
      *) fail "unknown start argument: $1" ;;
    esac
  done

  [[ -n "$agent" ]] || agent="${LLM_AGENT:-unknown-llm}"
  [[ -n "$objective" ]] || fail "start requires --objective TEXT"
  [[ ! -s "$ACTIVE_FILE" ]] || fail "a work order is already active: $(cat "$ACTIVE_FILE")"
  assert_git_safe
  install_hooks >/dev/null

  mkdir -p "$STATE_ROOT"
  chmod 700 "$STATE_BASE" "$STATE_ROOT" 2>/dev/null || true

  local stamp slug order_dir
  stamp="$(date '+%Y%m%d-%H%M%S')"
  slug="$(slugify "$objective")"
  [[ -n "$slug" ]] || slug="work"
  order_dir="$STATE_ROOT/$stamp-$slug"
  local suffix=0
  while [[ -e "$order_dir" ]]; do
    suffix=$((suffix + 1))
    order_dir="$STATE_ROOT/$stamp-$slug-$suffix"
  done
  mkdir -p "$order_dir/backup" "$order_dir/evidence/checkpoints" "$order_dir/evidence/runs"

  capture_repo_state "$order_dir/backup"
  capture_untracked_archive "$order_dir/backup"
  git -C "$REPO_ROOT" bundle create "$order_dir/backup/repository.bundle" --all
  git -C "$REPO_ROOT" bundle verify "$order_dir/backup/repository.bundle" \
    > "$order_dir/backup/bundle-verify.txt" 2>&1
  capture_code_intelligence "$order_dir/evidence/baseline"

  printf '%s\n' "$agent" > "$order_dir/AGENT.txt"
  printf '%s\n' "$objective" > "$order_dir/OBJECTIVE.txt"
  printf '%s\n' "$structural" > "$order_dir/STRUCTURAL.txt"
  printf '%s\n' "$(git -C "$REPO_ROOT" rev-parse HEAD)" > "$order_dir/BASE-HEAD.txt"

  cat > "$order_dir/WORK-ORDER.md" <<ORDER
---
id: $(basename "$order_dir")
status: active
project: $repo_name
agent: $agent
opened_at: $(now_iso)
structural_change: $structural
base_branch: $(git -C "$REPO_ROOT" branch --show-current)
base_commit: $(git -C "$REPO_ROOT" rev-parse HEAD)
---

# Orden de trabajo

## Objetivo

$objective

## Respaldo automático previo

- Estado Git, patches binarios, ramas, worktrees e historial: backup/
- Bundle Git verificado: backup/repository.bundle
- Untracked recuperables: backup/untracked.tar.gz cuando existan
- Evidencia CodeGraph/Graphify: evidence/baseline/
- Manifiesto SHA-256: MANIFEST.sha256

## Investigación obligatoria

- [ ] Consultar CodeGraph antes de recorrer masivamente archivos, cuando esté disponible.
- [ ] Registrar símbolos, callers, callees, rutas e impacto con el comando note.
- [ ] Revisar manualmente los archivos relevantes señalados por el índice.
- [ ] Usar Graphify sólo si structural_change es 1 o si aporta una decisión arquitectónica.

## Límites de autorización

- No crear commits, hacer push, abrir/cerrar PR ni fusionar sin autorización expresa del usuario.
- No usar --no-verify ni desactivar las guardas del repositorio.
- No tratar CodeGraph o Graphify como sustitutos del diff, las pruebas o la compilación.

## Evidencia de ejecución

Usar bash tools/llm-workflow.sh run -- COMANDO ... para conservar cada prueba o compilación.

## Notas y decisiones

ORDER

  printf '%s\n' "$order_dir" > "$ACTIVE_FILE"
  write_manifest "$order_dir"

  printf 'WORK_ORDER=%s\n' "$order_dir"
  printf 'Next: record findings with `bash tools/llm-workflow.sh note "..."`.\n'
}

show_status() {
  if [[ -s "$ACTIVE_FILE" ]]; then
    printf 'ACTIVE=%s\n' "$(cat "$ACTIVE_FILE")"
    sed -n '1,14p' "$(cat "$ACTIVE_FILE")/WORK-ORDER.md"
  elif [[ -s "$LAST_FILE" ]]; then
    printf 'NO_ACTIVE_WORK_ORDER\nLAST=%s\n' "$(cat "$LAST_FILE")"
  else
    printf 'NO_ACTIVE_WORK_ORDER\n'
  fi
}

append_note() {
  [[ $# -gt 0 ]] || fail "note requires text"
  local dir
  dir="$(active_dir)"
  printf -- '- %s — %s\n' "$(now_iso)" "$*" >> "$dir/WORK-ORDER.md"
  write_manifest "$dir"
}

checkpoint() {
  local label="${1:-manual}"
  local dir stamp slug out
  dir="$(active_dir)"
  stamp="$(date '+%Y%m%d-%H%M%S')"
  slug="$(slugify "$label")"
  [[ -n "$slug" ]] || slug="checkpoint"
  out="$dir/evidence/checkpoints/$stamp-$slug"
  capture_repo_state "$out/git"
  capture_code_intelligence "$out/code-intelligence"
  printf -- '- %s — checkpoint `%s`\n' "$(now_iso)" "$label" >> "$dir/WORK-ORDER.md"
  write_manifest "$dir"
  printf 'CHECKPOINT=%s\n' "$out"
}

run_logged() {
  [[ "${1:-}" == "--" ]] || fail "run syntax: run -- COMMAND [ARG ...]"
  shift
  [[ $# -gt 0 ]] || fail "run requires a command"
  local dir stamp count log_file cmd_string rc
  dir="$(active_dir)"
  stamp="$(date '+%Y%m%d-%H%M%S')"
  count="$(find "$dir/evidence/runs" -maxdepth 1 -type f -name '*.log' | wc -l | tr -d ' ')"
  count=$((count + 1))
  log_file="$dir/evidence/runs/${stamp}-$(printf '%03d' "$count").log"
  printf -v cmd_string '%q ' "$@"

  {
    printf 'started_at=%s\n' "$(now_iso)"
    printf 'cwd=%s\n' "$REPO_ROOT"
    printf 'command=%s\n\n' "$cmd_string"
  } > "$log_file"

  set +e
  (
    cd "$REPO_ROOT"
    "$@"
  ) 2>&1 | tee -a "$log_file"
  rc=${PIPESTATUS[0]}
  set -e

  {
    printf '\nfinished_at=%s\n' "$(now_iso)"
    printf 'exit_code=%s\n' "$rc"
  } >> "$log_file"

  printf -- '- %s — comando `%s` → rc=%s; evidencia `%s`\n' \
    "$(now_iso)" "$cmd_string" "$rc" "${log_file#"$dir/"}" >> "$dir/WORK-ORDER.md"
  write_manifest "$dir"
  return "$rc"
}

refresh_intelligence() {
  local dir="$1"
  local log="$dir/evidence/intelligence-refresh.log"
  : > "$log"

  if command -v codegraph >/dev/null 2>&1 && [[ -d "$REPO_ROOT/.codegraph" ]]; then
    printf 'Refreshing CodeGraph...\n' | tee -a "$log"
    codegraph sync "$REPO_ROOT" 2>&1 | tee -a "$log"
  else
    printf 'CodeGraph refresh skipped: index or command unavailable.\n' | tee -a "$log"
  fi

  local structural
  structural="$(cat "$dir/STRUCTURAL.txt")"
  if [[ "$structural" == "1" ]] && command -v graphify >/dev/null 2>&1; then
    printf 'Refreshing Graphify for structural work...\n' | tee -a "$log"
    (
      cd "$REPO_ROOT"
      if [[ -f graphify-out/graph.json ]]; then
        if ! graphify extract . --code-only --update; then
          graphify extract . --code-only
        fi
      else
        graphify extract . --code-only
      fi
      graphify cluster-only "$REPO_ROOT"
    ) 2>&1 | tee -a "$log"
  else
    printf 'Graphify refresh skipped: work order is not structural or command unavailable.\n' | tee -a "$log"
  fi

  if [[ -f "$REPO_ROOT/tools/knowledge-graph.sh" ]]; then
    OBSIDIAN_VAULT="${OBSIDIAN_VAULT:-/storage/emulated/0/Documents/Engineering-KB}" \
      bash "$REPO_ROOT/tools/knowledge-graph.sh" obsidian 2>&1 | tee -a "$log" || true
  fi
}

finish_order() {
  local summary="${*:-Work completed; see recorded evidence.}"
  local dir base_head final_head branch
  dir="$(active_dir)"
  assert_git_safe
  checkpoint "pre-finish" >/dev/null
  refresh_intelligence "$dir"
  capture_repo_state "$dir/evidence/final/git"
  capture_code_intelligence "$dir/evidence/final/code-intelligence"

  base_head="$(cat "$dir/BASE-HEAD.txt")"
  final_head="$(git -C "$REPO_ROOT" rev-parse HEAD)"
  branch="$(git -C "$REPO_ROOT" branch --show-current)"

  cat >> "$dir/WORK-ORDER.md" <<ORDER

## Cierre automático

- Cerrada: $(now_iso)
- Resumen: $summary
- Rama final: $branch
- Commit base: $base_head
- Commit final: $final_head
- Estado final: evidence/final/git/status.txt
- Diff final: evidence/final/git/unstaged.patch y evidence/final/git/staged.patch
- Evidencia final de índices: evidence/final/code-intelligence/
- Commit/push/merge: no se infieren; deben constar en la evidencia o en una autorización expresa.
ORDER

  sed -i 's/^status: active$/status: closed/' "$dir/WORK-ORDER.md"
  printf '%s\n' "$dir" > "$LAST_FILE"
  rm -f "$ACTIVE_FILE"
  write_manifest "$dir"
  printf 'CLOSED_WORK_ORDER=%s\n' "$dir"
}

abort_order() {
  local reason="${*:-Aborted without a supplied reason.}"
  local dir
  dir="$(active_dir)"
  checkpoint "abort" >/dev/null
  cat >> "$dir/WORK-ORDER.md" <<ORDER

## Aborto

- Fecha: $(now_iso)
- Motivo: $reason
- Se preservaron el respaldo previo y el estado al abortar.
ORDER
  sed -i 's/^status: active$/status: aborted/' "$dir/WORK-ORDER.md"
  printf '%s\n' "$dir" > "$LAST_FILE"
  rm -f "$ACTIVE_FILE"
  write_manifest "$dir"
  printf 'ABORTED_WORK_ORDER=%s\n' "$dir"
}

verify_setup() {
  local missing=0 path
  for path in \
    AI_WORKFLOW.md \
    AGENTS.md \
    CLAUDE.md \
    GEMINI.md \
    .github/copilot-instructions.md \
    .cursor/rules/llm-workflow.mdc \
    .githooks/pre-commit \
    .githooks/commit-msg \
    tools/llm-workflow.sh; do
    if [[ ! -f "$REPO_ROOT/$path" ]]; then
      printf 'MISSING: %s\n' "$path"
      missing=1
    fi
  done
  bash -n "$REPO_ROOT/tools/llm-workflow.sh"
  bash -n "$REPO_ROOT/.githooks/pre-commit"
  bash -n "$REPO_ROOT/.githooks/commit-msg"
  local hooks
  hooks="$(git -C "$REPO_ROOT" config --get core.hooksPath || true)"
  printf 'core.hooksPath=%s\n' "${hooks:-NOT_CONFIGURED}"
  [[ "$missing" -eq 0 ]] || fail "workflow files are incomplete"
  printf 'Workflow files and shell syntax: OK\n'
}

main() {
  case "${1:-}" in
    install) shift; install_hooks "$@" ;;
    start) shift; start_order "$@" ;;
    status) shift; show_status "$@" ;;
    note) shift; append_note "$@" ;;
    run) shift; run_logged "$@" ;;
    checkpoint) shift; checkpoint "$@" ;;
    finish) shift; finish_order "$@" ;;
    abort) shift; abort_order "$@" ;;
    verify) shift; verify_setup "$@" ;;
    -h|--help|help|'') usage ;;
    *) usage >&2; fail "unknown command: $1" ;;
  esac
}

main "$@"
