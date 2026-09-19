# Nightzuku rish post-merge audit

Audited repository: `joselofarias-byte/Nightzuku`  
Audited commit (full SHA): `611aa4b085790ec6d79540b737517775a9491c76`  
Audit branch: `cursor/rish-postmerge-audit-5a11`  
Physical HONOR 200 ELI-NX9 / Android 16 / SDK 36 rish PASS at that commit is authoritative for the uid=2000 shell path.

This document is the single consolidated report for the post-PR #25 audit.

---

## 1. PR #25 integration

| Check | Result |
|---|---|
| `611aa4b085790ec6d79540b737517775a9491c76` is `origin/main` HEAD | **PASS** (`git merge-base --is-ancestor` and `git rev-parse origin/main`) |
| Commit message | `Fix rish on Android 16 and Termux forks` |
| Merge | Squash-merge of PR #25 (`https://github.com/joselofarias-byte/Nightzuku/pull/25`) at 2026-09-19T21:42:16Z |
| Revert / partial merge | **PASS** — four files landed intact; no later revert on main |
| Files in merge | `manager/src/main/assets/rish`, `manager/src/main/res/values/strings.xml`, `manager/src/main/res/values-es/strings.xml`, `shell/src/main/java/rikka/shizuku/shell/ShizukuShellLoader.java` |

PR #25 CI (pre-merge, head `f7808e52f0f585b4e78063701955add1851c9ce7`):

| Workflow | Run ID | Conclusion | Artifacts |
|---|---|---|---|
| Android PR build | [35470498774](https://github.com/joselofarias-byte/Nightzuku/actions/runs/35470498774) | SUCCESS | debug `10591834221` (`nightzuku-manager-debug-f7808e5`), release `10592298118` (`nightzuku-manager-release-signed-f7808e5`) |
| Android PR build | [35470496545](https://github.com/joselofarias-byte/Nightzuku/actions/runs/35470496545) | SUCCESS | debug `10592413538`, release `10592852867` |
| Verify Nightzuku server | [35470498780](https://github.com/joselofarias-byte/Nightzuku/actions/runs/35470498780) | SUCCESS | none |

Push-to-main CI did not run after the squash. `android-pr.yml` is PR/feature-branch only; `app.yml` is tag/`workflow_dispatch` only. That is expected, not a revert.

Nightzuku runtime identity on this commit:

- Application id: `com.joselofarias.nightzuku` (`manager/build.gradle`, `shell/build.gradle` `MANAGER_APPLICATION_ID`, `ServerConstants.MANAGER_APPLICATION_ID`)
- REQUEST_BINDER target: `BuildConfig.MANAGER_APPLICATION_ID` (Nightzuku), **not** upstream `moe.shizuku.privileged.api`

---

## 2. End-to-end rish flow (code evidence)

```
exported rish wrapper
  -> application/package identity detection
  -> rish_shizuku.dex / app_process
  -> ShizukuShellLoader.main
  -> REQUEST_BINDER broadcast to Nightzuku
  -> ShizukuReceiver / ShellBinderRequestHandler
  -> Shell.main
  -> Rish / RishTerminal / RishHost
  -> remote /system/bin/sh
```

### 2.1 Exported wrapper

`manager/src/main/assets/rish` is packaged as an asset. `ShellTutorialActivity` exports it together with `rish_shizuku.dex` through SAF, deleting same-named files first.

The wrapper:

1. Resolves `DEX` next to itself (`dirname "$0"/rish_shizuku.dex`).
2. Detects the terminal package (`RISH_APPLICATION_ID`, then `TERMUX_APP__PACKAGE_NAME`, then `PREFIX`, then `HOME`).
3. On Android 14+ (`SDK >= 34`) refuses a writable dex and tries `chmod 400`.
4. Unsets `LD_LIBRARY_PATH` so Termux/NewTermux libraries do not leak into `app_process`.
5. `exec /system/bin/app_process -Djava.class.path="$DEX" /system/bin --nice-name=rish rikka.shizuku.shell.ShizukuShellLoader "$@"`.

`RISH_DETECT_ONLY=1` prints the detected id and exits without needing the dex (tests / debug).

### 2.2 Application / package identity

Two layers, both fork-safe after this audit:

1. **Wrapper** (shell): dynamic detection only. No hardcoded `com.termux`.
2. **Loader** (`RishIdentity.resolveCallingPackage`): if `getPackagesForUid(uid)` returns exactly one package, that wins (official Termux or NewTermux). `RISH_APPLICATION_ID` is used only when the UID is empty or shared. Placeholder `PKG` is rejected.

### 2.3 `rish_shizuku.dex`

Built from the `:shell` APK (`shell/build.gradle` `copyDebugShellDex` / `copyReleaseShellDex`). Manager `preDebugBuild` / `preReleaseBuild` and `merge*Assets` depend on that copy. The dex entry is `rikka.shizuku.shell.ShizukuShellLoader`.

Stale exports: users who copied an older wrapper to `~/.nightzuku` must re-export and re-copy. The tutorial already overwrites same-named files in the chosen SAF tree.

### 2.4 REQUEST_BINDER

`ShizukuShellLoader.requestForBinder()`:

- Intent action `rikka.shizuku.intent.action.REQUEST_BINDER`
- Package `com.joselofarias.nightzuku`
- Flag `FLAG_INCLUDE_STOPPED_PACKAGES`
- Extra `data` carries the rish receiver `Binder`
- SDK >= 30: reflective `broadcastIntentWithFeature` via `BroadcastIntentArgs` (HONOR-validated path; **do not** replace with activity start)
- SDK < 30: `broadcastIntent`
- Android 8.0/8.1 only: `startActivityAsUser` fallback

Manager side (`AndroidManifest.xml`):

- Exported `ShizukuReceiver` for the same action
- Exported `ShellRequestHandlerActivity` (legacy / Android 8 chooser path)

`ShizukuReceiver` -> `ShellBinderRequestHandler.handleRequest()`:

- Reads the client binder from `data`
- Reads `Shizuku.getBinder()` (the Nightzuku server binder already held by the manager)
- Oneway transact code `1`: writes server binder + `applicationInfo.sourceDir`
- If the server binder is missing, still replies with a null binder so rish prints `Server is not running` instead of waiting 5s

### 2.5 Nightzuku manager -> Shell

`onBinderReceived` builds a `BaseDexClassLoader` from the manager APK `sourceDir` (native `librish.so` search path included) and invokes `moe.shizuku.manager.shell.Shell.main(args, callingPackage, binder, handler)`.

`Shell` (`manager/.../shell/Shell.java`):

1. `RishConfig.init(binder, ShizukuApiConstants.BINDER_DESCRIPTOR, 30000)`
2. `Shizuku.onBinderReceived(binder, packageName)` — attaches the terminal as a Shizuku client
3. Requires server version >= 12
4. `new Shell().start(args)` which is `Rish.start`

Permission: first use shows Nightzuku’s confirmation UI. Termux/NewTermux typically do **not** declare `moe.shizuku.manager.permission.API_V23`. Persisted consent is stored by UID in `ShizukuConfigManager`. `ShizukuService.checkCallerPermission` honors that persisted FLAG_ALLOWED for non-manifest clients. Runtime permission grant is skipped when the package does not request `API_V23`.

### 2.6 Rish / RishTerminal / RishHost -> `/system/bin/sh`

Pinned Shizuku-API submodule: `api @ a27f6e4151ba7b39965ca47edb2bf0aeed7102e5` (matches origin/master; no newer rish commits).

| Stage | File | Behavior |
|---|---|---|
| `Rish.start` | `api/rish/src/main/java/rikka/rish/Rish.java` | request permission, then start terminal |
| `RishTerminal` | `api/rish/.../RishTerminal.java` | copies `System.getenv()` into the createHost parcel, cwd, pipes/PTY |
| `Service.onTransact` | `api/server-shared/.../Service.java` | routes rish transaction codes to `RishService` |
| `RishService.createHost` | `api/rish/.../RishService.java` | `enforceCallingPermission("createHost")`; env stripped on ADB unless `RISH_PRESERVE_ENV=1`; kept on root unless `RISH_PRESERVE_ENV=0` |
| `RishHost` native | `api/rish/src/main/cpp/rikka_rish_RishHost.cpp` | `argv[0] = "/system/bin/sh"` then `execvp` / `execvpe` |

Remote process identity on the HONOR PASS: `uid=2000(shell)`, `gid=2000(shell)`, `u:r:shell:s0`. That is the ADB-started Nightzuku server, not Termux’s app UID.

---

## 3. Findings

Each item is `PASS`, `NEEDS_PHYSICAL_TEST`, or `BLOCKED`.

### PASS

| ID | Finding |
|---|---|
| F1 | PR #25 is a clean ancestor/HEAD of main at `611aa4b085790ec6d79540b737517775a9491c76`. |
| F2 | REQUEST_BINDER targets `com.joselofarias.nightzuku`, not `moe.shizuku.privileged.api`. |
| F3 | Android 16 binder path uses `broadcastIntentWithFeature` reflection. Physical HONOR PASS stands. This audit extracted the packing logic without changing the algorithm. |
| F4 | Official Termux (`com.termux`) identity is detected from `TERMUX_APP__PACKAGE_NAME`, `PREFIX`, `HOME`, or unique UID. |
| F5 | NewTermux (`com.newtermux.dev`) uses the same dynamic detectors. No NewTermux id is hardcoded. |
| F6 | Explicit `RISH_APPLICATION_ID` override wins in the wrapper. |
| F7 | Wrapper quoting of `$DEX`, `"$@"`, and `dirname "$0"` is correct. Nightzuku is stricter than upstream (upstream leaves `$DEX` unquoted). |
| F8 | No remaining `/data/data/com.termux` path assumption in the wrapper. |
| F9 | Exported dex write-protect on SDK 34+ is present. |
| F10 | `LD_LIBRARY_PATH` is unset before `app_process`. |
| F11 | Manifest exports both the receiver (Android 16 path) and the legacy activity. |
| F12 | Non-manifest clients (Termux/NewTermux) can keep persisted UID consent. |
| F13 | Shizuku-API rish native still execs `/system/bin/sh`. Matches the HONOR shell. |
| F14 | Upstream Shizuku still hardcodes `moe.shizuku.privileged.api` and `RISH_APPLICATION_ID=PKG`. Nightzuku must **not** take that. |
| F15 | Upstream Shizuku-API rish has no newer functional commits beyond the pinned submodule. |

### Defects fixed on this branch (were fail, now PASS at unit level)

| ID | Finding | Fix |
|---|---|---|
| D1 | Wrapper fallback `RISH_APPLICATION_ID="com.termux"` reintroduced a hardcoded terminal id. On NewTermux, if `TERMUX_APP__PACKAGE_NAME` / `PREFIX` / `HOME` were all missing and UID lookup was ambiguous, rish would impersonate official Termux. | Removed. Undetected identity stays unset; loader uses UID lookup. |
| D2 | PREFIX/HOME extraction accepted non-package leftovers (no-dot or slash-containing). | Reject unless the candidate contains a dot and no `/`. |
| D3 | Successful binder reply did not cancel the 5s timeout. Harmless while `Rish.waitFor()` blocks the main looper; dangerous during the first-run permission dialog (async). | `handler.removeCallbacks` on binder transact. |
| D4 | Manager returned `false` without a client reply when the server binder was missing, so rish waited 5s instead of printing `Server is not running`. | Always transact; null binder is the documented rish error. |

### NEEDS_PHYSICAL_TEST

These cannot be closed on this VM. They do **not** block merge of this audit PR, but NewTermux on HONOR 200 should run the copy-paste procedures in section 8.

| ID | Why physical |
|---|---|
| P1 | NewTermux (`com.newtermux.dev`) binder attach + first-run permission dialog on Android 16. |
| P2 | Re-export / replace stale `~/.nightzuku/rish` after this wrapper change. |
| P3 | Shared-UID or missing-env NewTermux install, if any exists on device. |
| P4 | `RISH_PRESERVE_ENV=1` on ADB (uid=2000) — Termux/NewTermux `PATH` points at app-private binaries that shell cannot execute. Expected ADB limitation, not a regression. |
| P5 | Process death of Nightzuku manager mid-session (native host kills the forked sh). |
| P6 | Work-profile Termux/NewTermux (`/data/user/10/...`) on HONOR. |

### BLOCKED

None. No device-blocking defect was found in the HONOR-validated uid=2000 path.

---

## 4. Hunt checklist

| Hunt item | Result |
|---|---|
| Remaining hardcoded `com.termux` as runtime identity | Removed from wrapper. Tutorial strings still *mention* Termux/NewTermux as examples (not used as runtime ids). |
| Hardcoded upstream manager ids used as runtime identity | **PASS** for rish. `moe.shizuku.manager.*` remains the **permission / binder extra** namespace (Shizuku protocol). `moe.shizuku.privileged.api` is not the REQUEST_BINDER package. |
| Android 16 / SDK 36 incompatibilities | Validated path preserved. Upstream still uses `broadcastIntent` only — Nightzuku must keep the reflection path. |
| `/data/data/com.termux` assumptions | None in rish. |
| `TERMUX_APP__PACKAGE_NAME` | First dynamic detector after explicit override. |
| PREFIX / HOME detection | Supports `/data/data/*` and `/data/user/<id>/*`. Validates package-shaped ids. |
| env / PATH propagation | ADB strips env unless `RISH_PRESERVE_ENV=1`. By design in upstream `RishService`. |
| Quoting / spaces | Wrapper is quoted. Remote argv is C-string blocks from `RishHost`. |
| Stale exported rish / dex | Tutorial overwrites SAF copies; `~/.nightzuku` requires a manual recopy (P2). |
| Binder acquisition races | Timeout now cancelled on reply. 5s still applies if no reply. |
| Timeout / error reporting | Null-binder reply added. Timeout text already names Nightzuku + manager id. |
| Permission edge cases | Non-manifest + persisted UID consent works. First grant still needs UI. |
| Lifecycle / process death | Client death kills forked sh (`RishHost.cpp`). Manager death: binder death recipient in `Shizuku.java`. |
| Package-renamed Termux forks | Dynamic detection + unique-UID lookup. No allowlist. |

---

## 5. Upstream comparison

Sources compared:

- https://github.com/RikkaApps/Shizuku `manager/src/main/assets/rish` and `ShizukuShellLoader.java` (current master)
- https://github.com/RikkaApps/Shizuku-API `rish/` at `a27f6e4` == `origin/master`

Useful upstream ideas **already present or intentionally different**:

| Upstream | Nightzuku | Action |
|---|---|---|
| `RISH_APPLICATION_ID=PKG` placeholder | Dynamic detection | Keep Nightzuku |
| Hardcoded `moe.shizuku.privileged.api` | `com.joselofarias.nightzuku` | Keep Nightzuku |
| `broadcastIntent` on all SDKs | `broadcastIntentWithFeature` on SDK 30+ | Keep Nightzuku (HONOR PASS). Do not overwrite. |
| Unquoted `$DEX` | Quoted | Keep Nightzuku |
| Android 14 writable-dex chmod | Present | Keep |
| Timeout blames battery optimization | Timeout blames binder delivery / server | Keep Nightzuku wording |
| `RISH_PRESERVE_ENV` | Unchanged in submodule | Keep |
| SIGWINCH / LTO in API rish | Already in pinned submodule | No bump needed |

No upstream rish change should be copied that would undo the Android 16 broadcast or Nightzuku package identity.

---

## 6. Changes made on this branch

Low-risk hardening only. The HONOR-validated broadcast packing and manager package target were not rewritten.

1. Remove hardcoded `com.termux` fallback from the exported wrapper.
2. Validate PREFIX/HOME/`TERMUX_APP__PACKAGE_NAME` candidates.
3. Add `RISH_DETECT_ONLY=1` for tests and debug.
4. Extract `RishIdentity` and `BroadcastIntentArgs` so the Android 16 packing and package resolution can be unit-tested.
5. Cancel the 5s binder timeout when a reply arrives.
6. Reply to rish when the Nightzuku server binder is missing.
7. Add bash + JUnit tests and CI steps.
8. Tutorial copy: no hardcoded terminal id; mention NewTermux detectors.

---

## 7. Files changed

- `manager/src/main/assets/rish`
- `manager/src/main/java/moe/shizuku/manager/shell/ShellBinderRequestHandler.kt`
- `manager/src/main/res/values/strings.xml`
- `manager/src/main/res/values-es/strings.xml`
- `shell/src/main/java/rikka/shizuku/shell/ShizukuShellLoader.java`
- `shell/src/main/java/rikka/shizuku/shell/RishIdentity.java`
- `shell/src/main/java/rikka/shizuku/shell/BroadcastIntentArgs.java`
- `shell/src/test/java/rikka/shizuku/shell/RishIdentityTest.java`
- `shell/src/test/java/rikka/shizuku/shell/BroadcastIntentArgsTest.java`
- `shell/build.gradle`
- `shell/proguard-rules.pro`
- `scripts/test_rish_identity.sh`
- `.github/workflows/android-pr.yml`
- `NIGHTZUKU_RISH_POSTMERGE_AUDIT.md`

---

## 8. Tests

Local / CI (no device):

```bash
scripts/test_rish_identity.sh
./gradlew :shell:testDebugUnitTest
./gradlew :manager:assembleDebug
```

Covered without a device:

- `com.termux` via `TERMUX_APP__PACKAGE_NAME`, PREFIX, HOME
- `com.newtermux.dev` via the same channels, including `/data/user/10` and `/data/user/11`
- explicit `RISH_APPLICATION_ID` override
- PREFIX/HOME rejection of non-package values
- no hardcoded fallback
- unique-UID vs shared-UID resolution
- Android 16-style `broadcastIntentWithFeature` argument packing (16-parameter hidden API layout)

Not coverable here: live binder, PTY, permission UI, SELinux, HONOR OEM broadcast delivery.

---

## 9. Workflow / artifact IDs

### PR #25 (already merged)

See section 1.

### This audit PR

Recorded after CI on `cursor/rish-postmerge-audit-5a11`:

| Workflow | Run ID | Conclusion | Artifact IDs |
|---|---|---|---|
| Android PR build | [35471576648](https://github.com/joselofarias-byte/Nightzuku/actions/runs/35471576648) | in progress at first report; see follow-up if superseded | *pending* |
| Verify Nightzuku server | [35471576681](https://github.com/joselofarias-byte/Nightzuku/actions/runs/35471576681) | SUCCESS | none (compile-only) |

Local build/test results are in section 9.1. GitHub APK artifact IDs are filled when the Android PR build finishes.

### 9.1 Local results

- `scripts/test_rish_identity.sh`: **PASS** (17 cases: Termux, NewTermux, override, PREFIX, HOME, rejection, no hardcoded ids)
- `javac` + JUnit `RishIdentityTest`: **PASS** (7 tests)
- `./gradlew :shell:testDebugUnitTest`: **PASS** — `RishIdentityTest` 7/7, `BroadcastIntentArgsTest` 4/4, 0 failures
- `./gradlew :manager:assembleDebug`: **PASS**
- Local debug APK: `out/apk/nightzuku-v13.6.0.r47.4609e9d-debug.apk` (and `manager/build/outputs/apk/debug/manager-debug.apk`)

---

## 10. Unresolved risks

1. Users still running a pre-#25 or pre-audit `~/.nightzuku/rish` keep the old hardcoded fallback until they recopy.
2. ADB rish does not get Termux/NewTermux `PATH` unless `RISH_PRESERVE_ENV=1`, and even then app-private binaries are usually inaccessible to uid=2000.
3. First-run permission still requires a visible Nightzuku confirmation. If the user never grants it, rish exits `Permission denied`.
4. `RishTerminal.requestExitCode` in the API submodule passes a null reply parcel (upstream). Exit code may show `-1` even when the shell succeeded. Not changed (submodule).
5. `STELLAR_VS_NIGHTZUKU.md` still claims rish targets `moe.shizuku.privileged.api`. That is stale relative to PR #25. Out of scope for this rish fix.
6. Manager process death while rish is up drops the binder; rish does not auto-reconnect.

---

## 11. NewTermux readiness

**Code-ready: yes.** The same wrapper and loader must work for `com.termux` and `com.newtermux.dev` without an allowlist.

| Path | Official Termux | NewTermux |
|---|---|---|
| `RISH_APPLICATION_ID` override | yes | yes |
| `TERMUX_APP__PACKAGE_NAME` | yes | yes if NewTermux exports the standard Termux env |
| `PREFIX=/data/data/<pkg>/files/usr` | yes | yes |
| `HOME=/data/data/<pkg>/files/home` | yes | yes |
| `/data/user/<id>/<pkg>/...` | yes | yes |
| Unique app UID | yes | yes (typical) |
| Hardcoded package | no | no |

**Physical HONOR validation is still required** before calling NewTermux production-ready. Unit tests cannot prove NewTermux sets `TERMUX_APP__PACKAGE_NAME` or that Android 16 delivers the NewTermux-originated broadcast.

---

## 12. HONOR 200 physical procedures

Device: HONOR 200 ELI-NX9, Android 16, SDK 36.  
Nightzuku package: `com.joselofarias.nightzuku`.  
Official Termux: `com.termux`.  
NewTermux: `com.newtermux.dev`.

Use an APK built from this branch (CI artifact or local `assembleDebug` / signed release). Re-export rish from Nightzuku **Use Nightzuku in Termux** so `~/.nightzuku` is not stale.

### 12.1 Shared setup (once)

In Nightzuku: start the server via ADB so it is the same uid=2000 path already PASSed.

Then in **each** terminal app:

```sh
termux-setup-storage
mkdir -p ~/.nightzuku
cp ~/storage/downloads/rish ~/.nightzuku/rish
cp ~/storage/downloads/rish_shizuku.dex ~/.nightzuku/rish_shizuku.dex
chmod +x ~/.nightzuku/rish
chmod 444 ~/.nightzuku/rish_shizuku.dex
ls -l ~/.nightzuku/rish ~/.nightzuku/rish_shizuku.dex
```

Required: both files exist, `rish` is executable, dex is not writable.

### 12.2 Detection only (no binder)

```sh
RISH_DETECT_ONLY=1 ~/.nightzuku/rish ; echo EXIT:$?
RISH_DEBUG=1 RISH_DETECT_ONLY=1 ~/.nightzuku/rish
```

Required output:

- Official Termux: a single line `com.termux` and `EXIT:0`
- NewTermux: a single line `com.newtermux.dev` and `EXIT:0`
- Must **not** print `com.termux` when run inside NewTermux

Override check (both apps):

```sh
RISH_APPLICATION_ID=com.explicit.override RISH_DETECT_ONLY=1 ~/.nightzuku/rish
```

Required: `com.explicit.override`

### 12.3 Live rish — official Termux

```sh
cd ~/.nightzuku
RISH_DEBUG=1 ./rish
```

On first run, grant Nightzuku permission if prompted. Then **inside rish**:

```sh
id
getenforce
id -Z
settings get global adb_enabled
pm path com.joselofarias.nightzuku
dumpsys package com.joselofarias.nightzuku | head
getprop ro.build.version.sdk
ls /data/local/tmp
echo RISH_APPLICATION_ID=$RISH_APPLICATION_ID
echo TERMUX_APP__PACKAGE_NAME=$TERMUX_APP__PACKAGE_NAME
```

Required output (must match the already-PASSed shell path):

- `uid=2000(shell) gid=2000(shell)`
- SELinux `u:r:shell:s0`
- SDK `36`
- `/data/local/tmp` listable
- `settings` / `pm` / `dumpsys` / `getprop` succeed
- No `Request timeout`
- Session stays up longer than 5 seconds (timeout-cancel check)
- `echo still-alive` after 6 seconds still works

### 12.4 Live rish — NewTermux

Repeat 12.3 inside `com.newtermux.dev`.

Required additional output:

- Detection (12.2) printed `com.newtermux.dev`
- Same uid=2000 / `u:r:shell:s0` / SDK 36 / no timeout
- First-run permission, if shown, names NewTermux (not Termux) as the requesting app
- After grant, a second `./rish` starts without a prompt if consent was persisted

### 12.5 Server-down error path

Stop Nightzuku server, then:

```sh
cd ~/.nightzuku && ./rish ; echo EXIT:$?
```

Required: `Server is not running` (not a 5s timeout) and non-zero `EXIT`.

### 12.6 Do not “fix” if Termux still matches the existing PASS

If official Termux still shows uid=2000 / no REQUEST_BINDER timeout, treat that as regression-free. Only treat NewTermux failures as new work.

---

## 13. Improve audit

- Detection lives in the exported wrapper (must stay self-contained; no extra sourced file).
- Java helpers are small and testable; broadcast packing is the same algorithm as PR #25.
- No new runtime dependencies. JUnit is test-only.
- Did not bump or rewrite the `api` submodule.
- Did not merge this PR, publish a release, or push to main.
