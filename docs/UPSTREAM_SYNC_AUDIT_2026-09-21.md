# Upstream sync audit — kerneldroid/Nightzuku

Date: 2026-09-21

## Scope

Audit of `joselofarias-byte/Nightzuku:main` against upstream `kerneldroid/Nightzuku:main`.

Observed in GitHub UI / API:
- fork status: public fork of `kerneldroid/Nightzuku`
- fork main: 157 commits ahead / 71 commits behind
- fork main protection: disabled
- fork main at audit start: `611aa4b085790ec6d79540b737517775a9491c76`
- upstream main latest: `60a8feb65d1a9c95692624222ef26afb3063b9d3` (2026-07-20)
- API compare across the two histories reports no common ancestor. Therefore do **not** use a blind "Sync fork" or wholesale merge.

The correct strategy is selective porting, preserving the fork's Android 16 rish/NightDog/persistence work.

## Current fork work that must be preserved

The fork contains substantial independent work not present upstream, including:
- `manager/.../shizuku/NightDogRecovery.kt`
- `AdbTcpController.kt`, `AdbTransportResolver.kt`, `AdbRecoveryTestController.kt`
- fork status/recovery UI and NightDog status artwork
- Android 16 rish hardening and dynamic terminal identity work
- dedicated PR CI / server verification workflows
- fork identity, release, audit and recovery documentation

Open draft PR #29 is the active integration line for maximum persistence + Android 16 rish transport hardening. Do not mix upstream porting into that PR until it is physically validated.

## Upstream items already present or superseded

### Security manifest hardening — already present

Upstream `854e56dc39f6` hardened:
- `android:allowBackup=false`
- `ModuleWebViewActivity android:exported=false`
- privileged permission protection around binder entrypoints

The current fork already contains these protections.

### Module script hardening — already present

Upstream catalog redesign `51decffc97a7` added:
- 256 KiB module script limit
- read script content then execute with `sh -c`
- safer execution working directory

The current fork already has `MAX_SCRIPT_BYTES = 256 * 1024` and executes module scripts through `sh -c`.

### Android 16 rish timeout workaround — superseded

Upstream `cb30302c8567` used an Android 16 `startActivityAsUser` fallback.

The fork has newer Android 16 rish work and current PR #29 hardens the binder transport specifically around:
- unordered/non-sticky binder request broadcast semantics
- real Android user id
- app-op parameter placement
- off-main-looper request dispatch
- retry and bounded timeout behavior

Do not cherry-pick `cb30302c8567` over the fork implementation.

### NightDog — concept overlaps, implementation diverges

Upstream commits `d3da6eead803` and `2a0b83b31b57` use a dedicated `nightdog` module/watchdog.

The fork has its own recovery-oriented NightDog implementation integrated with persistent ADB/TCP/boot recovery. Treat upstream NightDog only as a reference source for individual ideas; do not wholesale replace the fork implementation.

## High-value upstream candidates not currently present

### 1. Android 17 installed-packages compatibility — HIGH PRIORITY

Commit:
- `6e92830e34ca109e9029f1dbdf3c819c64402b8f`
  - "fix(server): fix Android 17 getInstalledPackages returning empty list"

New upstream file missing from the fork:
- `common/src/main/java/rikka/shizuku/common/util/InstalledPackagesCompat.java`

It also updates server/config/manager call sites for Android 17 return-type/signature behavior.

Recommendation:
- port this as an isolated compatibility PR
- adapt it to the fork's current server code
- add unit/compile checks
- do not combine with catalog/TAPI

### 2. TAPI universal Termux bridge — HIGH VALUE, MEDIUM/HIGH RISK

Relevant commits:
- `2a0b83b31b57` — Termux API bridge + NightDog integration
- `8aca8158061d` — universal bridge Android 7–17 + separate TAPI toggle
- `47bf92b4693a` — ProGuard keep + `rish_shizuku.dex` fix

Upstream paths absent from fork:
- `manager/src/main/assets/tapi`
- `shell/src/main/assets/tapi`
- `shell/src/main/java/rikka/shizuku/shell/TapiManager.java`

Recommendation:
- port only after PR #29 is validated
- preserve dynamic Termux/NewTermux identity rather than upstream hardcoded `RISH_APPLICATION_ID=com.termux`
- reuse the fork's current Android 16 binder transport
- treat TAPI as a new feature branch, not an upstream merge

### 3. Online ADB module catalog/discovery/update — HIGH VALUE, LARGE CHANGE

Core commits:
- `e5e3902482592dc68bd1bbf1526ce42deeca4dfe`
  - online ADB module catalog + GitHub discovery/update stack
- `51decffc97a7d3c851906d39331b7f3fc1a2cace`
  - catalog UI redesign, module details, danger detection

Major upstream paths absent from fork:
- `manager/.../module/catalog/*`
- `manager/.../module/discovery/*`
- `manager/.../module/update/*`
- `docs/github-catalog.md`

Recommendation:
- port as a separate multi-step feature PR after compatibility work
- retain fork module security constraints
- independently review GitHub token storage, download validation, ZIP extraction, source-build/install path, danger detection and network trust boundaries
- do not copy the whole upstream tree blindly

### 4. Wear OS pairing fixes/docs — MEDIUM PRIORITY

Relevant upstream work:
- `84d9f94ceb08` — Wear OS 5 pairing shadowing / standby freeze
- `a399a81ff4ce` — pairing state hotfix
- `docs/wearos-pairing.md` missing from fork

Recommendation:
- compare against current Wear pairing behavior before porting
- low coupling compared with catalog/TAPI, suitable for a small dedicated PR

## Older upstream work likely already inherited or partially present

The fork tree already contains:
- ADB modules
- module WebUI and JS bridge
- module trust/settings infrastructure
- connectors
- Wear/TV screens
- Android 17 compatibility utilities in other locations
- extensive manager UI modernization

Therefore older upstream commits around initial module support, WebUI creation, connectors and basic Compose migration should be treated as historical context, not candidates for blind cherry-pick.

## Proposed integration order

1. Finish and physically validate PR #29.
2. Port Android 17 installed-packages compatibility as a small isolated PR.
3. Port TAPI as a fork-native implementation using dynamic terminal identity and current rish transport.
4. Audit/port Wear pairing fixes if still applicable.
5. Port catalog/discovery/update in stages with explicit security review.
6. Re-run full Android build + server verification + HONOR 200 physical tests after each stage.

## Main branch protection

Current GitHub branch metadata reports `protected=false`.

Recommended minimal rule for `main`:
- block force pushes
- block branch deletion
- require pull request before merge
- require current Nightzuku Android PR build/status checks
- keep administrator bypass available initially until CI/check names are stable

The connected GitHub API available in this session can read branch protection/rulesets but does not expose an administration write action for changing branch protection. No protection setting was changed by this audit.

## Decision

Do **not** press "Sync fork" and do **not** merge the 71 upstream commits wholesale.

Use selective, testable ports. The two strongest immediate upstream candidates are:
1. Android 17 installed-packages compatibility
2. TAPI universal Termux bridge adapted to the fork's NewTermux-aware rish transport

Catalog/discovery/update is valuable but should follow as a separately reviewed feature line.
