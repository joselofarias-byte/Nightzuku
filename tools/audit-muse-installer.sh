#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

URL="${MUSE_INSTALLER_URL:-https://dev.meta.ai/install.sh}"
OUT_ROOT="${MUSE_AUDIT_ROOT:-$HOME/.local/state/muse-code-installer-audits}"
stamp="$(date '+%Y%m%d-%H%M%S')"
out="$OUT_ROOT/$stamp"
mkdir -p "$out"
chmod 700 "$OUT_ROOT" "$out" 2>/dev/null || true

fail() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 1
}

command -v curl >/dev/null 2>&1 || fail "curl is required"
command -v sha256sum >/dev/null 2>&1 || fail "sha256sum is required"

installer="$out/install.sh"
report="$out/REPORT.txt"

curl --fail --location --silent --show-error \
  --proto '=https' --tlsv1.2 \
  "$URL" --output "$installer"
[[ -s "$installer" ]] || fail "downloaded installer is empty"
chmod 600 "$installer"

bash -n "$installer"
sha256sum "$installer" > "$out/install.sh.sha256"

{
  printf 'MUSE CODE INSTALLER AUDIT\n'
  printf 'audited_at=%s\n' "$(date -Iseconds)"
  printf 'url=%s\n' "$URL"
  printf 'host_arch=%s\n' "$(uname -m)"
  printf 'host_os=%s\n' "$(uname -s)"
  printf 'installer_sha256='
  cut -d' ' -f1 "$out/install.sh.sha256"
  printf 'bash_syntax=OK\n'
  printf 'installer_executed=NO\n'
} > "$report"

grep -Eo 'https://[^"'"'"'[:space:])}]+' "$installer" | LC_ALL=C sort -u \
  > "$out/download-urls.txt" || true
grep -Ein 'aarch64|arm64|x86_64|amd64|uname|architecture|arch=' "$installer" \
  > "$out/architecture-lines.txt" || true
grep -Ein 'sudo|chmod|chown|rm[[:space:]]+-r|mv[[:space:]]|cp[[:space:]]|tar[[:space:]]|curl[[:space:]]|wget[[:space:]]|PATH|profile|bashrc|zshrc' "$installer" \
  > "$out/mutation-lines.txt" || true

arch="$(uname -m)"
case "$arch" in
  aarch64|arm64)
    if grep -Eqi 'aarch64|arm64' "$installer"; then
      printf 'arm64_reference=FOUND\n' >> "$report"
      verdict="REVIEW_REQUIRED"
    else
      printf 'arm64_reference=NOT_FOUND\n' >> "$report"
      verdict="BLOCK_INSTALL_UNSUPPORTED_ARCH_NOT_DEMONSTRATED"
    fi
    ;;
  x86_64|amd64)
    verdict="REVIEW_REQUIRED"
    ;;
  *)
    verdict="BLOCK_INSTALL_UNKNOWN_ARCH"
    ;;
esac

printf 'verdict=%s\n' "$verdict" >> "$report"
(
  cd "$out"
  sha256sum REPORT.txt download-urls.txt architecture-lines.txt mutation-lines.txt install.sh install.sh.sha256 \
    > MANIFEST.sha256
)

cat "$report"
printf 'AUDIT_DIR=%s\n' "$out"
printf 'The installer was downloaded and inspected but NOT executed.\n'

[[ "$verdict" == "REVIEW_REQUIRED" ]] || exit 3
