#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

fail() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 1
}

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
REPO_ROOT="$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel 2>/dev/null || true)"
[[ -n "$REPO_ROOT" ]] || fail "must run inside a Git repository"
WORKFLOW="$REPO_ROOT/tools/llm-workflow.sh"
[[ -f "$WORKFLOW" ]] || fail "missing tools/llm-workflow.sh"
MASTER_GIT_DIR="$(git -C "$REPO_ROOT" rev-parse --absolute-git-dir)"
MASTER_ACTIVE="$MASTER_GIT_DIR/llm-work-current"
SWARM_CURRENT="$MASTER_GIT_DIR/swarm-current"
SWARM_LAST="$MASTER_GIT_DIR/swarm-last"

usage() {
  cat <<'USAGE'
Usage: bash tools/swarm-workflow.sh <command> [args]

Commands:
  start --objective TEXT [--structural] [--implementer NAME] [--reviewer NAME]
  status
  prompt implementer|reviewer
  spawn implementer|reviewer -- COMMAND [ARG ...]
  attach implementer|reviewer
  handoff
  review-note TEXT
  finish [SUMMARY]
  abort [REASON]
  cleanup
  verify

Design:
- one master llm-workflow order and backup;
- one implementation worktree;
- one independent review worktree created from a snapshot handoff;
- no copied SwarmForge runtime, constitution, daemon, Babashka dependency or downloader;
- no automatic commit, push or merge.
USAGE
}

now_iso() { date -Iseconds; }

active_state() {
  [[ -s "$SWARM_CURRENT" ]] || fail "no active swarm; run start first"
  local state
  state="$(cat "$SWARM_CURRENT")"
  [[ -d "$state" && -f "$state/SWARM.conf" ]] || fail "invalid swarm state: $state"
  printf '%s\n' "$state"
}

cfg() {
  local state="$1" key="$2"
  sed -n "s/^${key}=//p" "$state/SWARM.conf" | head -n1
}

write_cfg() {
  local state="$1" key="$2" value="$3"
  if grep -q "^${key}=" "$state/SWARM.conf" 2>/dev/null; then
    local tmp="$state/SWARM.conf.tmp"
    awk -v k="$key" -v v="$value" 'BEGIN{FS="="} $1==k{print k"="v; next} {print}' "$state/SWARM.conf" > "$tmp"
    mv "$tmp" "$state/SWARM.conf"
  else
    printf '%s=%s\n' "$key" "$value" >> "$state/SWARM.conf"
  fi
}

link_order_to_worktree() {
  local wt="$1" order="$2"
  local gd
  gd="$(git -C "$wt" rev-parse --absolute-git-dir)"
  printf '%s\n' "$order" > "$gd/llm-work-current"
}

unlink_order_from_worktree() {
  local wt="$1"
  [[ -d "$wt" ]] || return 0
  local gd
  gd="$(git -C "$wt" rev-parse --absolute-git-dir 2>/dev/null || true)"
  [[ -n "$gd" ]] && rm -f "$gd/llm-work-current"
}

make_role_prompt() {
  local state="$1" role="$2" backend="$3" wt="$4" objective="$5"
  local file="$state/roles/${role}.md"
  mkdir -p "$state/roles"
  if [[ "$role" == "implementer" ]]; then
    cat > "$file" <<PROMPT
# Rol: implementador

Backend sugerido: $backend
Objetivo maestro: $objective
Worktree: $wt

Reglas obligatorias:

1. Leer AGENTS.md y AI_WORKFLOW.md antes de trabajar.
2. Esta sesión pertenece a la orden maestra indicada por SWARM_MASTER_ORDER; no abrir otra orden.
3. Usar CodeGraph antes de búsquedas amplias cuando esté disponible.
4. Registrar hallazgos con: bash tools/llm-workflow.sh note "..."
5. Ejecutar pruebas/builds con: bash tools/llm-workflow.sh run -- <comando> [argumentos]
6. Hacer el cambio mínimo y seguro dentro de este worktree.
7. No hacer commit, push, merge ni abrir/cerrar PR sin autorización expresa.
8. Al terminar, no copiar cambios manualmente al checkout maestro. El orquestador hará un handoff reproducible.
PROMPT
  else
    cat > "$file" <<PROMPT
# Rol: revisor independiente

Backend sugerido: $backend
Objetivo maestro: $objective
Worktree: $wt

Reglas obligatorias:

1. Leer AGENTS.md y AI_WORKFLOW.md antes de revisar.
2. Este worktree contiene una fotografía reproducible del handoff del implementador.
3. Revisar corrección, seguridad, estabilidad, simplicidad, pruebas y alcance.
4. Usar CodeGraph cuando ayude a confirmar impacto o dependencias.
5. No modificar archivos de aplicación: este rol es de revisión independiente.
6. Registrar observaciones con: bash tools/swarm-workflow.sh review-note "..."
7. Puede ejecutar pruebas mediante: bash tools/llm-workflow.sh run -- <comando> [argumentos]
8. No hacer commit, push, merge ni abrir/cerrar PR.
PROMPT
  fi
}

start_swarm() {
  local objective="" structural=0 implementer="codex" reviewer="claude"
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --objective) [[ $# -ge 2 ]] || fail "--objective requires value"; objective="$2"; shift 2 ;;
      --structural) structural=1; shift ;;
      --implementer) [[ $# -ge 2 ]] || fail "--implementer requires value"; implementer="$2"; shift 2 ;;
      --reviewer) [[ $# -ge 2 ]] || fail "--reviewer requires value"; reviewer="$2"; shift 2 ;;
      *) fail "unknown start argument: $1" ;;
    esac
  done
  [[ -n "$objective" ]] || fail "start requires --objective TEXT"
  [[ ! -s "$SWARM_CURRENT" ]] || fail "a swarm is already active: $(cat "$SWARM_CURRENT")"
  [[ ! -s "$MASTER_ACTIVE" ]] || fail "a normal work order is already active; finish or abort it before starting a swarm"

  local -a start_args=(start --agent swarm-orchestrator --objective "$objective")
  [[ "$structural" == "1" ]] && start_args+=(--structural)
  bash "$WORKFLOW" "${start_args[@]}"

  local order id state base_head base_branch worktree_root impl_wt impl_branch
  order="$(cat "$MASTER_ACTIVE")"
  id="$(basename "$order" | cut -c1-56)"
  state="$order/swarm"
  base_head="$(git -C "$REPO_ROOT" rev-parse HEAD)"
  base_branch="$(git -C "$REPO_ROOT" branch --show-current)"
  worktree_root="$REPO_ROOT/.worktrees/swarm/$id"
  impl_wt="$worktree_root/implementer"
  impl_branch="llm-swarm/${id}-implementer"

  mkdir -p "$state/roles" "$state/snapshots" "$worktree_root"
  git -C "$REPO_ROOT" worktree add -b "$impl_branch" "$impl_wt" "$base_head"
  link_order_to_worktree "$impl_wt" "$order"

  cat > "$state/SWARM.conf" <<CONF
id=$id
status=active
opened_at=$(now_iso)
objective=$objective
structural=$structural
base_head=$base_head
base_branch=$base_branch
implementer_backend=$implementer
reviewer_backend=$reviewer
implementer_worktree=$impl_wt
implementer_branch=$impl_branch
reviewer_worktree=$worktree_root/reviewer
reviewer_branch=llm-swarm/${id}-reviewer
CONF
  make_role_prompt "$state" implementer "$implementer" "$impl_wt" "$objective"
  printf '%s\n' "$state" > "$SWARM_CURRENT"
  bash "$WORKFLOW" note "Swarm two-role iniciado. Se reutiliza una única orden/backup; implementador=$implementer, revisor=$reviewer. No se copió runtime externo de SwarmForge."
  printf 'SWARM=%s\nIMPLEMENTER_WORKTREE=%s\n' "$state" "$impl_wt"
  printf 'Next: bash tools/swarm-workflow.sh prompt implementer\n'
}

show_status() {
  if [[ ! -s "$SWARM_CURRENT" ]]; then
    printf 'NO_ACTIVE_SWARM\n'
    [[ -s "$SWARM_LAST" ]] && printf 'LAST=%s\n' "$(cat "$SWARM_LAST")"
    return
  fi
  local state
  state="$(active_state)"
  cat "$state/SWARM.conf"
  printf 'master_order=%s\n' "$(dirname "$state")"
}

show_prompt() {
  local role="${1:-}"
  [[ "$role" == "implementer" || "$role" == "reviewer" ]] || fail "prompt requires implementer or reviewer"
  local state file
  state="$(active_state)"
  file="$state/roles/$role.md"
  [[ -f "$file" ]] || fail "$role prompt is not available yet"
  cat "$file"
}

spawn_role() {
  local role="${1:-}"
  [[ "$role" == "implementer" || "$role" == "reviewer" ]] || fail "spawn requires implementer or reviewer"
  shift || true
  [[ "${1:-}" == "--" ]] || fail "spawn syntax: spawn ROLE -- COMMAND [ARG ...]"
  shift
  [[ $# -gt 0 ]] || fail "spawn requires a command"
  command -v tmux >/dev/null 2>&1 || fail "tmux is not installed"

  local state wt prompt id session launcher order
  state="$(active_state)"
  wt="$(cfg "$state" "${role}_worktree")"
  [[ -d "$wt" ]] || fail "$role worktree does not exist yet"
  prompt="$state/roles/$role.md"
  [[ -f "$prompt" ]] || fail "$role prompt does not exist"
  id="$(cfg "$state" id)"
  session="swarm-${id:0:20}-$role"
  order="$(dirname "$state")"
  launcher="$state/roles/${role}-launch.sh"

  {
    printf '#!/usr/bin/env bash\nset -Eeuo pipefail\n'
    printf 'export SWARM_ROLE=%q\n' "$role"
    printf 'export SWARM_ROLE_PROMPT=%q\n' "$prompt"
    printf 'export SWARM_MASTER_ORDER=%q\n' "$order"
    printf 'cd %q\n' "$wt"
    printf 'cat %q\n' "$prompt"
    printf 'printf "\\nSWARM_ROLE_PROMPT=%%s\\n" "$SWARM_ROLE_PROMPT"\n'
    printf 'exec'
    printf ' %q' "$@"
    printf '\n'
  } > "$launcher"
  chmod 700 "$launcher"

  tmux has-session -t "$session" 2>/dev/null && fail "tmux session already exists: $session"
  tmux new-session -d -s "$session" -c "$wt" "$launcher"
  write_cfg "$state" "${role}_session" "$session"
  bash "$WORKFLOW" note "Sesión tmux iniciada para rol=$role, comando=$(printf '%q ' "$@")."
  printf 'SESSION=%s\nAttach: tmux attach -t %s\n' "$session" "$session"
}

attach_role() {
  local role="${1:-}"
  [[ "$role" == "implementer" || "$role" == "reviewer" ]] || fail "attach requires implementer or reviewer"
  command -v tmux >/dev/null 2>&1 || fail "tmux is not installed"
  local state session
  state="$(active_state)"
  session="$(cfg "$state" "${role}_session")"
  [[ -n "$session" ]] || fail "no tmux session registered for $role"
  exec tmux attach -t "$session"
}

snapshot_implementer() {
  local state="$1" impl_wt="$2" out="$3"
  mkdir -p "$out"
  git -C "$impl_wt" status --short --branch > "$out/status.txt"
  git -C "$impl_wt" diff --cached --binary > "$out/staged.patch"
  git -C "$impl_wt" diff --binary > "$out/unstaged.patch"
  git -C "$impl_wt" ls-files --others --exclude-standard -z > "$out/untracked.list0"
  tr '\0' '\n' < "$out/untracked.list0" > "$out/untracked.txt"
  if [[ -s "$out/untracked.list0" ]]; then
    (cd "$impl_wt" && tar --null --files-from="$out/untracked.list0" -czf "$out/untracked.tar.gz")
  fi
  (cd "$out" && find . -type f ! -name MANIFEST.sha256 -print0 | LC_ALL=C sort -z | xargs -0 sha256sum > MANIFEST.sha256)
}

handoff() {
  local state order impl_wt reviewer_wt reviewer_branch base_head objective reviewer_backend stamp snap
  state="$(active_state)"
  order="$(dirname "$state")"
  impl_wt="$(cfg "$state" implementer_worktree)"
  reviewer_wt="$(cfg "$state" reviewer_worktree)"
  reviewer_branch="$(cfg "$state" reviewer_branch)"
  base_head="$(cfg "$state" base_head)"
  objective="$(cfg "$state" objective)"
  reviewer_backend="$(cfg "$state" reviewer_backend)"
  [[ -d "$impl_wt" ]] || fail "implementer worktree missing"
  [[ ! -e "$reviewer_wt" ]] || fail "reviewer worktree already exists; one handoff per swarm is supported"

  stamp="$(date '+%Y%m%d-%H%M%S')"
  snap="$state/snapshots/$stamp-implementer-to-reviewer"
  snapshot_implementer "$state" "$impl_wt" "$snap"

  git -C "$REPO_ROOT" worktree add -b "$reviewer_branch" "$reviewer_wt" "$base_head"
  [[ ! -s "$snap/staged.patch" ]] || git -C "$reviewer_wt" apply --binary "$snap/staged.patch"
  [[ ! -s "$snap/unstaged.patch" ]] || git -C "$reviewer_wt" apply --binary "$snap/unstaged.patch"
  [[ ! -f "$snap/untracked.tar.gz" ]] || tar -xzf "$snap/untracked.tar.gz" -C "$reviewer_wt"
  link_order_to_worktree "$reviewer_wt" "$order"
  make_role_prompt "$state" reviewer "$reviewer_backend" "$reviewer_wt" "$objective"
  write_cfg "$state" handoff_snapshot "$snap"
  write_cfg "$state" handoff_at "$(now_iso)"
  bash "$WORKFLOW" note "Handoff implementador→revisor creado sin commit: snapshot=$snap. El revisor parte del mismo base HEAD y recibe patch binario + untracked."
  printf 'HANDOFF=%s\nREVIEWER_WORKTREE=%s\n' "$snap" "$reviewer_wt"
  printf 'Next: bash tools/swarm-workflow.sh prompt reviewer\n'
}

review_note() {
  [[ $# -gt 0 ]] || fail "review-note requires text"
  local state
  state="$(active_state)"
  printf -- '- %s — %s\n' "$(now_iso)" "$*" >> "$state/REVIEW.md"
  bash "$WORKFLOW" note "REVIEW: $*"
}

stop_sessions() {
  local state="$1" role session
  command -v tmux >/dev/null 2>&1 || return 0
  for role in implementer reviewer; do
    session="$(cfg "$state" "${role}_session")"
    [[ -n "$session" ]] || continue
    tmux has-session -t "$session" 2>/dev/null && tmux kill-session -t "$session" || true
  done
}

close_swarm() {
  local mode="$1"; shift
  local summary="${*:-Swarm work completed.}"
  local state impl_wt reviewer_wt
  state="$(active_state)"
  impl_wt="$(cfg "$state" implementer_worktree)"
  reviewer_wt="$(cfg "$state" reviewer_worktree)"

  stop_sessions "$state"
  [[ -d "$impl_wt" ]] && snapshot_implementer "$state" "$impl_wt" "$state/snapshots/final-implementer"
  if [[ -d "$reviewer_wt" ]]; then
    mkdir -p "$state/snapshots/final-reviewer"
    git -C "$reviewer_wt" status --short --branch > "$state/snapshots/final-reviewer/status.txt"
    git -C "$reviewer_wt" diff --binary > "$state/snapshots/final-reviewer/diff.patch"
  fi
  unlink_order_from_worktree "$impl_wt"
  unlink_order_from_worktree "$reviewer_wt"
  write_cfg "$state" status "$mode"
  write_cfg "$state" closed_at "$(now_iso)"
  printf '%s\n' "$state" > "$SWARM_LAST"
  rm -f "$SWARM_CURRENT"

  if [[ "$mode" == "closed" ]]; then
    bash "$WORKFLOW" note "Swarm cerrado. $summary"
    bash "$WORKFLOW" finish "$summary"
  else
    bash "$WORKFLOW" note "Swarm abortado. $summary"
    bash "$WORKFLOW" abort "$summary"
  fi
  printf 'SWARM_%s=%s\n' "$(printf '%s' "$mode" | tr '[:lower:]' '[:upper:]')" "$state"
  printf 'Worktrees preserved for inspection. Run cleanup only after preserving/committing authorized changes.\n'
}

cleanup_swarm() {
  [[ ! -s "$SWARM_CURRENT" ]] || fail "cannot cleanup an active swarm"
  [[ -s "$SWARM_LAST" ]] || fail "no previous swarm"
  local state impl_wt reviewer_wt impl_branch reviewer_branch
  state="$(cat "$SWARM_LAST")"
  [[ -f "$state/SWARM.conf" ]] || fail "last swarm state missing"
  impl_wt="$(cfg "$state" implementer_worktree)"
  reviewer_wt="$(cfg "$state" reviewer_worktree)"
  impl_branch="$(cfg "$state" implementer_branch)"
  reviewer_branch="$(cfg "$state" reviewer_branch)"

  for wt in "$impl_wt" "$reviewer_wt"; do
    [[ -d "$wt" ]] || continue
    [[ -z "$(git -C "$wt" status --porcelain=v1)" ]] || fail "refusing cleanup: worktree has uncommitted changes: $wt"
    git -C "$REPO_ROOT" worktree remove "$wt"
  done
  git -C "$REPO_ROOT" branch -D "$impl_branch" 2>/dev/null || true
  git -C "$REPO_ROOT" branch -D "$reviewer_branch" 2>/dev/null || true
  git -C "$REPO_ROOT" worktree prune
  printf 'CLEANUP_OK\n'
}

verify_setup() {
  bash -n "$REPO_ROOT/tools/swarm-workflow.sh"
  bash -n "$REPO_ROOT/tools/llm-workflow.sh"
  command -v git >/dev/null 2>&1 || fail "git missing"
  if command -v tmux >/dev/null 2>&1; then
    printf 'tmux=AVAILABLE\n'
  else
    printf 'tmux=NOT_INSTALLED (spawn/attach unavailable; lifecycle still works)\n'
  fi
  printf 'external_swarmforge_runtime=NOT_VENDORED\n'
  printf 'babashka_dependency=NONE\n'
  printf 'backup_engine=tools/llm-workflow.sh\n'
  printf 'SWARM_WORKFLOW_OK\n'
}

main() {
  case "${1:-}" in
    start) shift; start_swarm "$@" ;;
    status) shift; show_status "$@" ;;
    prompt) shift; show_prompt "$@" ;;
    spawn) shift; spawn_role "$@" ;;
    attach) shift; attach_role "$@" ;;
    handoff) shift; handoff "$@" ;;
    review-note) shift; review_note "$@" ;;
    finish) shift; close_swarm closed "$@" ;;
    abort) shift; close_swarm aborted "$@" ;;
    cleanup) shift; cleanup_swarm "$@" ;;
    verify) shift; verify_setup "$@" ;;
    -h|--help|help|'') usage ;;
    *) usage >&2; fail "unknown command: $1" ;;
  esac
}

main "$@"
