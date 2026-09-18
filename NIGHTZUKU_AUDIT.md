# Nightzuku audit

**Date:** 2026-09-18  
**Auditor:** Cloud agent on `cursor/nightzuku-audit-and-hardening-faf3`  
**Base:** `main` @ `a23e9ec` (`Restore full NightDog integration and runtime identity`)  
**Target device (hardware-only):** HONOR 200 (ELI-NX9), Android 16 / MagicOS, **no root**, Shizuku/wireless ADB  
**Cloud VM:** Linux + JDK 21; Android SDK 36.1 / NDK 29 / CMake 3.31 installed for this run  

This document is evidence-based. Hypotheses are labeled. Hardware-only items are copy-paste procedures, not guesses.

---

## Architecture

Nightzuku is a **Shizuku-compatible privileged IPC broker**, not a root emulator.

| Layer | Module | Role |
| --- | --- | --- |
| Manager UI | `:manager` (`moe.shizuku.manager`, applicationId `com.joselofarias.nightzuku`) | Pairing, start/stop, app authorization, ADB modules/WebUI, NightDog status |
| Server | `:server` (`rikka.shizuku.server.ShizukuService`) | Binder service started as `shizuku_server` via `app_process` |
| Native starter | `:manager` JNI `libshizuku.so` + `:starter` | Locates APK, execs server |
| Shell/rish | `:shell` + Shizuku-API `rish` | Broadcasts `rikka.shizuku.intent.action.REQUEST_BINDER` to the manager package |
| Client API | Git submodule `api` → [RikkaApps/Shizuku-API](https://github.com/RikkaApps/Shizuku-API) @ `a27f6e4` | AIDL, provider, shared, rish |
| Root (optional) | libsu | Only if a real root shell exists; Honor 200 path is ADB/Shizuku |
| Watchdog | `NightDogRecovery` | Process-level binder poll + wireless ADB restart |

Startup paths (non-root Honor 200):

1. User pairs wireless debugging (mDNS `_adb-tls-pairing._tcp`) via `AdbPairingService` FGS `connectedDevice`.
2. User or NightDog connects to `_adb-tls-connect._tcp` / persistent TCP / `service.adb.tcp.port`.
3. `AdbClient` runs `Starter.internalCommand` (`libshizuku.so --apk=<sourceDir>`).
4. Server waits up to 60s for `package` / activity / user / appops, then sends binder to `${applicationId}.shizuku`.
5. Clients attach with `moe.shizuku.manager.permission.API_V23`.

Form factors: phone (primary), Wear, Android TV. Module system is Nightzuku-specific (ZIP modules, WebUI JS bridge).

---

## Upstream relationship

- Ancestor: RikkaApps Shizuku manager + server, plus this fork’s NightDog, ADB modules, Wear/TV UI, Android 17 hidden-API compat (`Android17Compat`).
- Fork identity (runtime): `com.joselofarias.nightzuku` (`FORK_IDENTITY.md`). Source namespaces remain `moe.shizuku.*` / `rikka.shizuku.*` by design.
- Binder extra key `moe.shizuku.privileged.api.intent.extra.BINDER` is **wire-format compatibility**, not the applicationId.
- Open PR **#18** (`feature/nightdog-watchdog-and-hardening`) overlaps PR **#23** (already merged). Do **not** merge #18 wholesale: its boot path still assumes `127.0.0.1`, and its `NightDogManager` duplicates main’s `NightDogRecovery`. Valuable #18 pieces (ZIP limits, WebView JS strip, path containment, install rollback) are ported here instead.
- `STELLAR_VS_NIGHTZUKU.md` (2026-06-15) is a historical comparison. Several of its “Nightzuku defects” are **already fixed on main** (rish package target, `waitSystemService` timeout, package id). A stale banner was added; current findings live here.

Inherited upstream behavior that still makes sense:

- Signature-protected manager permission + dangerous `API_V23`.
- Exported `REQUEST_BINDER` receiver (rish/shell handoff).
- `forceStopPackage` retry when the manager provider is dead (disruptive but known Shizuku recovery).

Inherited upstream / fork leftovers that no longer make sense:

- Dead `runCompatTest()` that granted then **revoked** `WRITE_SECURE_SETTINGS` (removed; it was never called but was a landmine).
- Docs and `test_activator.sh` still teaching `kerneldroid.nightzuku` / `moe.shizuku.privileged.api` (fixed).
- Launching `StarterActivity` from `Application` for watchdog recovery (Android 10+ BAL; replaced with in-process ADB start).

---

## Findings

### Confirmed bugs (fixed on this branch)

| ID | Severity | Root cause | Fix |
| --- | --- | --- | --- |
| C1 | **High** | Home **Stop** called `Shizuku.exit()` without `NightDogRecovery.prepareForManualStop()`. Watchdog treated the death as a crash and restarted the service. | Home stop now persists `desired_running=false` before exit. |
| C2 | **High** | `BOOT_COMPLETED` ignored `desired_running`. A manual stop did not survive reboot. | Boot receiver returns if NightDog desired state is stopped. |
| C3 | **High** | NightDog recovered by `startActivity(StarterActivity)` from `Application` / IO coroutine. Android 10+ background activity launch is blocked; Honor/MagicOS is aggressive. Failure was caught as `Stage.ERROR` and recovery stalled. | NightDog now starts the server with `AdbClient` + `Starter.internalCommand` (same mechanism as boot). |
| C4 | **High** | Trusted module WebUI with `webNetwork` kept `@JavascriptInterface("Shizuku")` after HTTPS navigation. Remote pages could call `exec()`. | Strip the JS bridge on non-`file://` navigation (`shouldOverrideUrlLoading` + `onPageStarted`). |
| C5 | **High** | `module.prop` `webui`/`banner`/`action` paths used `directory.resolve(path)` with no canonical containment. A module could point scripts/WebUI outside its tree. | Shared `ModulePathPolicy.isInside`; `findFirstExisting` filters escaped paths. |
| C6 | **Medium** | Module ZIP copied with no download size cap; extract size checked only after a full entry write. | 50 MiB stream cap on the ZIP; extract budget counted while copying. |
| C7 | **Medium** | Failed install after `target.deleteRecursively()` left the previous module gone. | Atomic rename with backup; restore backup if the new tree is unreadable; delete backup only after success. Reinstall revokes trust. |
| C8 | **Medium** | `File.toPath()` in WebView path check requires API 26; minSdk is 25. Failed closed via `runCatching` (broke local WebUI on 7.1). | Canonical-path string check (API 25-safe). Honor 200 is API 36, but minSdk still 25. |
| C9 | **Medium** | Boot ADB start used a **3s** mDNS window and **always** `countDown()` after the first callback, including failures. Slow MagicOS wireless debugging missed auto-start. | 12s window; count down only on binder up; ADB work hopped off the main/Nsd thread. |
| C10 | **Medium** | `OnBinderReceivedListener` wrote `desired_running=true`. Sticky redelivery or an external start could undo a manual stop. | Desired state changes only from explicit start/stop UI (home, pairing, lab) and prefs. |
| C11 | **Low** | Dead `runCompatTest()` in production server. | Removed. |
| C12 | **Low** | Pairing FGS used `GlobalScope`. | Service-scoped `CoroutineScope`; cancelled in `onDestroy`. |
| C13 | **Low** | Connector URI, rish package, activator script, TV/module docs still said `kerneldroid.nightzuku` / upstream package. Copy-paste on Honor would miss the provider. | Docs + `test_activator.sh` updated to `com.joselofarias.nightzuku`. |

### Suspected bugs (not changed without device evidence)

| ID | Severity | Hypothesis | Why not fixed here |
| --- | --- | --- | --- |
| S1 | Medium | NightDog is process-level. If MagicOS kills the manager, recovery waits until the next launch or boot. | By design without a persistent FGS. Adding an always-on FGS has battery/OEM cost. Needs Honor data. |
| S2 | Medium | Permanent TLS-connect mDNS while the process is alive. | Stability over battery for wireless ADB discovery. Measure with experiment D4. |
| S3 | Medium | Manager authorization is UID/appId only (upstream). Shared-UID impersonation is theoretical. | Changing this can break clients. Needs a dedicated security design. |
| S4 | Low–Med | `forceStopPackage` when the manager provider is dead (upstream retry). Can kill the UI during recovery. | Risky to change without Honor traces of provider-dead loops. |
| S5 | Low | `ACCESS_LOCAL_NETWORK` is declared; runtime prompt on API 36 uses `NEARBY_WIFI_DEVICES`. Android 16 local-network restriction is **opt-in** (`RESTRICT_LOCAL_NETWORK` compat). Honor 16 may or may not enforce it. | Experiment D3. Do not guess OEM behavior. |
| S6 | Low | Dual recovery at boot: Application starts NightDog **and** boot receiver starts ADB. Two `AdbClient` connects could contend. | Boot now shares `startServerOverAdb` with a lock; still worth watching on device. |
| S7 | Low | Connector provider returns `Starter.internalCommand` to any app holding `API_V23` when Lab is enabled. | Documented lab risk; default off. |

### Android 16 compatibility findings

- `compileSdk` / `targetSdk` **36** (`36.1` platform), `minSdk` 25, Java 21, AGP `9.3.0-alpha03`, NDK `29.0.14206865`.
- Manifest already has `FOREGROUND_SERVICE_CONNECTED_DEVICE`, `POST_NOTIFICATIONS`, `NEARBY_WIFI_DEVICES` (`neverForLocation`), `ACCESS_LOCAL_NETWORK`, `USE_LOOPBACK_INTERFACE`.
- Pairing FGS uses `connectedDevice` and catches `ForegroundServiceStartNotAllowedException` (Android 12+).
- Local network: [AOSP docs](https://developer.android.com/privacy-and-security/local-network-permission) — Android 16 is a testing/opt-in phase using `NEARBY_WIFI_DEVICES`; Android 17 makes `ACCESS_LOCAL_NETWORK` mandatory for NsdManager/mDNS/raw local sockets. Current runtime split (API 36 → nearby wifi, API 37+ → `ACCESS_LOCAL_NETWORK`) matches that document.
- `enableOnBackInvokedCallback=true`, `directBootAware` on boot/receiver/provider, `autoRevokePermissions=allowed`.
- `Android17Compat` injects `deviceId=0` when hidden APIs grow a parameter. Honor 200 is Android 16, not 17; the fallback is unused unless hidden-compat throws `NoSuchMethodError`.
- Background activity starts: confirmed problem for NightDog (C3), fixed.
- 16 KB page size / native: NDK 29 + `useLegacyPackaging true`. Honor 200 page size not verified here.

### Shizuku findings

- Server identity matches manager: `ServerConstants.MANAGER_APPLICATION_ID`, `starter.cpp` `PACKAGE_NAME`, `ServiceStarter` packageName, shell `BuildConfig.MANAGER_APPLICATION_ID` are all `com.joselofarias.nightzuku` on main. The incomplete-rebrand bug described in PR #18 is **already fixed on main**.
- `ShizukuShellLoader` uses `setPackage(BuildConfig.MANAGER_APPLICATION_ID)`. STELLAR R1 (“rish broken”) is **false on current main**.
- `waitSystemService` exits 51 after 60s. STELLAR R2 is **false on current main**.
- Binder send uses ContentProvider `${package}.shizuku` + extra key `moe.shizuku.privileged.api.intent.extra.BINDER`.
- Permission grants are migrated at server start (`migratePermissionGrants`) — needed after package-id changes.
- NightDog binder listeners are process-scoped; sticky received listener no longer flips desired state (C10).
- Boot auto-start still requires `WRITE_SECURE_SETTINGS` (granted by the server to the manager on attach) and last launch mode ADB. Without that permission, Honor boot start cannot toggle `adb_wifi_enabled`.

### Security findings

- **Fixed:** WebUI JS bridge leak to HTTPS (C4); module.prop path traversal (C5); zip-bomb/download DoS (C6); trust not revoked on module replace (C7).
- **Accepted (Shizuku model):** exported `ShizukuReceiver` without permission (binder only useful if server is up; clients still need authorization); exported request activities guarded by `INTERACT_ACROSS_USERS_FULL`.
- **Lab-only:** connector provider behind `API_V23` + user toggle.
- **Not changed:** UID-only manager check (S3).
- `allowBackup=false`; data extraction rules present.

---

## Tests performed

Recorded after the cloud build/test pass on this branch.

| Check | Result |
| --- | --- |
| `./gradlew :manager:testDebugUnitTest` (new `ModulePathPolicyTest`) | *pending — filled after CI/local Gradle* |
| `./gradlew :manager:assembleDebug` | *pending* |
| `./gradlew :server:compileDebugJavaWithJavac` | *pending* |
| `./gradlew :manager:lintDebug` | *pending; lint is not in CI* |
| Instrumented / device tests | **None in repo**; Honor 200 procedures below |
| Static analysis besides lint | No detekt/ktlint config in-repo |

---

## Fixes implemented

On `cursor/nightzuku-audit-and-hardening-faf3` (unmerged):

- `NightDogRecovery`: ADB in-process start; desired-state only from explicit UI; shared `startServerOverAdb`.
- `HomeActivity`: stop disables watchdog; start buttons call `requestManualStart`.
- `BootCompleteReceiver`: honor manual stop; 12s mDNS; success-only latch; IO hop + start lock.
- `AdbModuleManager` + `ModulePathPolicy`: ZIP/extract limits, backup/rollback, path containment.
- `ModuleWebViewActivity`: JS bridge strip; API 25-safe path check.
- `AdbPairingService`: structured coroutine scope.
- `ShizukuService`: remove `runCompatTest`.
- Unit tests for path policy and copy limits.
- Docs/activator script package identity.
- `.gitignore` exception so `NIGHTZUKU_AUDIT.md` is tracked.

---

## Remaining work

1. Honor 200 experiments D1–D6 (below). Do not ship a release until D1/D2 pass.
2. Decide whether PR #18 should be closed as superseded once this PR lands (do not merge #18).
3. Optional later: persistent FGS watchdog (S1) — only if D1 shows MagicOS kills the process while wireless ADB stays up.
4. Optional later: signature check for manager callers (S3).
5. `app.yml` still looks for `out/apk/shizuku-v*-release.apk`; manager copy task writes `nightzuku-v…`. Tag-release workflow is stale. **Not changed here** (could publish/break release automation). Flagged only.
6. No in-repo instrumented tests; adding Robolectric for AdbClient would be a new stack.

---

## Device-only experiments

**Device:** HONOR 200 ELI-NX9, Android 16 MagicOS, **no root**.  
**App id:** `com.joselofarias.nightzuku`  
Return the full command output (or a screenshot of the Nightzuku home status card) for each experiment.

Install a debug APK from this PR’s CI artifact (or a local `:manager:assembleDebug`) **before** running these. Wireless debugging must already be paired once.

### D1 — Home Stop must stay stopped (C1 + C10)

In Nightzuku: start the service until home shows running. Tap **Stop** and confirm.

Then on a PC with the phone authorized:

```bash
adb shell dumpsys package com.joselofarias.nightzuku | grep -A2 requestedPermissions | head
adb logcat -d -s Shizuku:D NightDog:D AdbMdns:V AdbClient:D | tail -n 80
adb shell "run-as com.joselofarias.nightzuku cat shared_prefs/nightdog_recovery.xml"
```

**Expected:** `desired_running` is `false`. Home stays “manually stopped”. Service does **not** come back within 30s. Return the xml and whether the home card stayed stopped.

### D2 — Stop survives reboot (C2)

After D1 (service stopped):

```bash
adb reboot
```

Wait for the launcher. Do **not** open Nightzuku yet. Then:

```bash
adb wait-for-device
adb shell getprop sys.boot_completed
adb logcat -d | grep -E "Skip start on boot; service was stopped manually|BootCompleteReceiver|NightDog" | tail -n 40
adb shell "run-as com.joselofarias.nightzuku cat shared_prefs/nightdog_recovery.xml"
```

Open Nightzuku. **Expected:** still stopped; log contains `Skip start on boot; service was stopped manually`. Return those lines.

### D3 — Android 16 local-network / mDNS (S5)

```bash
adb shell cmd appops get com.joselofarias.nightzuku
adb shell dumpsys package com.joselofarias.nightzuku | grep -E "NEARBY_WIFI_DEVICES|ACCESS_LOCAL_NETWORK"
adb shell am compat enable RESTRICT_LOCAL_NETWORK com.joselofarias.nightzuku
adb reboot
```

After reboot, grant Nearby devices if prompted, then try wireless start from Home.

```bash
adb logcat -d -s AdbMdns:V AdbClient:D | tail -n 100
```

**Expected to report:** whether mDNS still finds `_adb-tls-connect._tcp` with the compat flag on; whether Nightzuku prompted for Nearby devices. Reset afterwards:

```bash
adb shell am compat disable RESTRICT_LOCAL_NETWORK com.joselofarias.nightzuku
```

### D4 — NightDog recovery without UI (C3, S1, S2)

Start Nightzuku service. Leave the app (home button). From PC, kill only the **server** if possible, or wait until MagicOS reports it dead. Do not force-stop the manager.

```bash
adb shell pidof shizuku_server
adb logcat -c
# If the server pid is visible:
adb shell kill $(adb shell pidof shizuku_server)
sleep 15
adb shell pidof shizuku_server
adb logcat -d | grep -E "NightDog|Starter|AdbClient|shizuku_starter" | tail -n 80
```

**Expected:** a new `shizuku_server` pid without the Starter UI appearing. If MagicOS also killed `com.joselofarias.nightzuku`, say so — that confirms S1.

### D5 — Boot auto-start when desired running (C9)

In Nightzuku: start the service successfully (so last launch mode is ADB). Confirm `WRITE_SECURE_SETTINGS` was granted (server attach does this). Reboot **without** stopping:

```bash
adb shell dumpsys package com.joselofarias.nightzuku | grep WRITE_SECURE_SETTINGS
adb reboot
adb wait-for-device
sleep 20
adb shell pidof shizuku_server
adb logcat -d | grep -E "ADB start on boot|mDNS|shizuku_starter|Skip start on boot" | tail -n 60
```

**Expected:** `shizuku_server` pid present without opening the app, or a clear log why mDNS did not finish in 12s. Return timestamps vs boot.

### D6 — Connector URI (doc fix)

Enable **Lab Features → Nightzuku Connectors**. From Termux (copy this whole block):

```bash
content query --uri content://com.joselofarias.nightzuku.connector
```

From PC:

```bash
adb shell content query --uri content://com.joselofarias.nightzuku.connector
```

**Expected:** one row `command=` pointing at `libshizuku.so`. The old URI `content://kerneldroid.nightzuku.connector` must return nothing.

Disable the lab toggle when done.

---

## Notes on PR #18

Leave https://github.com/joselofarias-byte/Nightzuku/pull/18 open until a human decides. It should **not** be merged onto current main: NightDog on main is a different class (`NightDogRecovery`), boot already uses real mDNS hosts, and #18 would regress boot to loopback. This branch cherry-picks only the hardening that main still lacked.
