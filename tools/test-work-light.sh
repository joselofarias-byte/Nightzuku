#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
SOURCE_ROOT="$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

REPO="$TMP/repo"
STATE="$TMP/state"
mkdir -p "$REPO/tools" "$REPO/.githooks"

cp "$SOURCE_ROOT/tools/work.sh" "$REPO/tools/work.sh"
cp "$SOURCE_ROOT/tools/llm-workflow.sh" "$REPO/tools/llm-workflow.sh"
cp "$SOURCE_ROOT/.githooks/pre-commit" "$REPO/.githooks/pre-commit"
cp "$SOURCE_ROOT/.githooks/commit-msg" "$REPO/.githooks/commit-msg"

cat > "$REPO/tools/system-docs.sh" <<'STUB'
#!/usr/bin/env bash
set -Eeuo pipefail
case "${1:-}" in
  summary|doctor|publish|history) exit 0 ;;
  snapshot) [[ -n "${2:-}" ]] && { mkdir -p "$(dirname "$2")"; printf 'stub\n' > "$2"; }; exit 0 ;;
  record) exit 0 ;;
  *) exit 0 ;;
esac
STUB

cat > "$REPO/tools/context-pack.sh" <<'STUB'
#!/usr/bin/env bash
set -Eeuo pipefail
printf 'stub context pack\n'
STUB
chmod +x "$REPO/tools/"*.sh "$REPO/.githooks/"*
printf 'base\n' > "$REPO/README.md"

(
  cd "$REPO"
  git init -q
  git config user.name 'Work Light Test'
  git config user.email 'work-light@example.invalid'
  git add .
  git commit -q -m 'test: initialize fixture'

  LLM_WORK_ROOT="$STATE" bash tools/work.sh start --light \
    --agent synthetic-test \
    --objective 'validate light read-only mode' > "$TMP/start.out"

  active="$(git rev-parse --absolute-git-dir)/llm-work-current"
  test -s "$active"
  order="$(cat "$active")"
  grep -q '^light$' "$order/MODE.txt"
  grep -q '^mode: light$' "$order/WORK-ORDER.md"
  test ! -e "$order/backup/repository.bundle"
  test ! -e "$order/backup/untracked.tar.gz"

  printf 'forbidden change\n' >> README.md
  git add README.md
  if git commit -q -m 'test: light commit must be blocked' >/dev/null 2>&1; then
    echo 'ERROR: commit succeeded inside light order.' >&2
    exit 1
  fi
  git reset -q --hard HEAD

  LLM_WORK_ROOT="$STATE" bash tools/work.sh finish \
    'Synthetic light order completed.' > "$TMP/finish.out"
  test ! -s "$active"
  last="$(git rev-parse --absolute-git-dir)/llm-work-last"
  test -s "$last"
  order="$(cat "$last")"
  grep -q '^status: closed$' "$order/WORK-ORDER.md"
  test -s "$order/MANIFEST.sha256"
  (cd "$order" && sha256sum -c MANIFEST.sha256 >/dev/null)

  LLM_WORK_ROOT="$STATE" bash tools/work.sh start --light \
    --agent synthetic-test \
    --objective 'validate dirty finish guard' >/dev/null
  printf 'dirty\n' >> README.md
  if LLM_WORK_ROOT="$STATE" bash tools/work.sh finish 'must fail' >/dev/null 2>&1; then
    echo 'ERROR: dirty light order closed successfully.' >&2
    exit 1
  fi
  LLM_WORK_ROOT="$STATE" bash tools/work.sh abort 'expected dirty guard test' >/dev/null
)

printf 'Work light synthetic test: OK\n'
