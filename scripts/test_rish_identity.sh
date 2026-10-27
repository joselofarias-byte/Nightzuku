#!/usr/bin/env bash
# Tests Nightzuku rish terminal identity detection without a device.
# The wrapper must stay fork-safe: no hardcoded terminal package ids.
set -Eeuo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
RISH="$ROOT/manager/src/main/assets/rish"
failures=0

assert_eq() {
  local name="$1" expected="$2" actual="$3"
  if [ "$expected" = "$actual" ]; then
    printf 'PASS  %s\n' "$name"
  else
    printf 'FAIL  %s\n  expected: %s\n  actual:   %s\n' "$name" "$expected" "$actual"
    failures=$((failures + 1))
  fi
}

assert_fail() {
  local name="$1" status="$2"
  if [ "$status" -ne 0 ]; then
    printf 'PASS  %s\n' "$name"
  else
    printf 'FAIL  %s (expected non-zero exit)\n' "$name"
    failures=$((failures + 1))
  fi
}

detect() {
  env -i PATH="$PATH" HOME="${HOME:-/tmp}" RISH_DETECT_ONLY=1 "$@" sh "$RISH"
}

# explicit override wins over Termux and NewTermux environment hints
got="$(detect \
  RISH_APPLICATION_ID=com.explicit.override \
  TERMUX_APP__PACKAGE_NAME=com.termux \
  PREFIX=/data/data/com.newtermux.dev/files/usr \
  HOME=/data/data/com.newtermux.dev/files/home)"
assert_eq "explicit RISH_APPLICATION_ID override" "com.explicit.override" "$got"

got="$(detect TERMUX_APP__PACKAGE_NAME=com.termux PREFIX=/data/data/com.newtermux.dev/files/usr)"
assert_eq "TERMUX_APP__PACKAGE_NAME official Termux" "com.termux" "$got"

got="$(detect TERMUX_APP__PACKAGE_NAME=com.newtermux.dev PREFIX=/data/data/com.termux/files/usr)"
assert_eq "TERMUX_APP__PACKAGE_NAME NewTermux" "com.newtermux.dev" "$got"

got="$(detect PREFIX=/data/data/com.termux/files/usr)"
assert_eq "PREFIX fallback official Termux" "com.termux" "$got"

got="$(detect PREFIX=/data/data/com.newtermux.dev/files/usr)"
assert_eq "PREFIX fallback NewTermux" "com.newtermux.dev" "$got"

got="$(detect PREFIX=/data/user/0/com.termux/files/usr)"
assert_eq "PREFIX multi-user official Termux" "com.termux" "$got"

got="$(detect PREFIX=/data/user/10/com.newtermux.dev/files/usr)"
assert_eq "PREFIX work-profile NewTermux" "com.newtermux.dev" "$got"

got="$(detect HOME=/data/data/com.termux/files/home)"
assert_eq "HOME fallback official Termux" "com.termux" "$got"

got="$(detect HOME=/data/data/com.newtermux.dev/files/home)"
assert_eq "HOME fallback NewTermux" "com.newtermux.dev" "$got"

got="$(detect HOME=/data/user/11/com.newtermux.dev/files/home)"
assert_eq "HOME multi-user NewTermux" "com.newtermux.dev" "$got"

if grep -n 'RISH_APPLICATION_ID="com.termux"' "$RISH" >/dev/null; then
  printf 'FAIL  rish must not hardcode com.termux as a fallback\n'
  failures=$((failures + 1))
else
  printf 'PASS  no hardcoded com.termux fallback\n'
fi

if grep -nE 'RISH_APPLICATION_ID="(com\.newtermux\.dev|moe\.shizuku\.privileged\.api|moe\.shizuku\.manager)"' "$RISH" >/dev/null; then
  printf 'FAIL  rish must not hardcode other package identities\n'
  failures=$((failures + 1))
else
  printf 'PASS  no hardcoded NewTermux or upstream Shizuku manager ids\n'
fi

if grep -n '/data/data/com.termux' "$RISH" >/dev/null; then
  printf 'FAIL  rish must not assume /data/data/com.termux\n'
  failures=$((failures + 1))
else
  printf 'PASS  no /data/data/com.termux assumption\n'
fi

set +e
detect PREFIX=/data/data/notapackage/files/usr >/tmp/rish-detect.out 2>/tmp/rish-detect.err
status=$?
set -e
assert_fail "reject PREFIX without a package-style id" "$status"

set +e
detect HOME=/sdcard/home >/tmp/rish-detect.out 2>/tmp/rish-detect.err
status=$?
set -e
assert_fail "reject non-Termux HOME" "$status"

set +e
detect TERMUX_APP__PACKAGE_NAME=notapackage >/tmp/rish-detect.out 2>/tmp/rish-detect.err
status=$?
set -e
assert_fail "reject TERMUX_APP__PACKAGE_NAME without a dot" "$status"

set +e
detect >/tmp/rish-detect.out 2>/tmp/rish-detect.err
status=$?
set -e
assert_fail "undetectable environment does not invent a package" "$status"

if [ "$failures" -ne 0 ]; then
  printf '\n%d rish identity test(s) failed\n' "$failures"
  exit 1
fi

printf '\nAll rish identity tests passed\n'
