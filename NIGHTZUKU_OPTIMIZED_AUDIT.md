# NIGHTZUKU_OPTIMIZED_AUDIT.md

> Audit date: 2026-06-15  
> Scope: startup, shell execution, Shizuku compatibility, root emulation, binder delivery  
> Basis: full source traversal of `server/`, `shell/`, `starter/`, `manager/`, `common/`, `docs/`  
> Audit is read-only. No code was modified.

---

## 1. Project Identity

| Field | Value |
|---|---|
| Package name | `kerneldroid.nightzuku` |
| Upstream base | Shizuku v13.6.0 (RikkaApps) |
| Fork maintainer | kerneldroid |
| Server class | `rikka.shizuku.server.ShizukuService` |
| Manager class | `moe.shizuku.manager` |
| Config path | `/data/user_de/0/com.android.shell/shizuku.json` |

---

## 2. Startup Mechanism

### 2.1 Entry Points

Three startup paths exist, all converging on `ShizukuService.main()`:

#### Path A — Root startup
- Triggered from `BootCompleteReceiver.rootStart()` or manual `StarterActivity`.
- Uses `com.topjohnwu.superuser.Shell` (libsu) to execute `Starter.internalCommand`.
- `Starter.internalCommand` = `<nativeLibDir>/libshizuku.so --apk=<sourceDir>`.
- libsu is called with `Shell.FLAG_REDIRECT_STDERR` set at application init.
- No retry, no timeout guard in `rootStart()` — if `Shell.getShell().isRoot` is false, it simply closes the cached shell and returns.

#### Path B — ADB auto-start on boot (Android 13+)
- Triggered from `BootCompleteReceiver.adbStart()` only when:
  1. API ≥ 33 (TIRAMISU).
  2. `WRITE_SECURE_SETTINGS` is granted.
  3. Last launch mode was `ADB`.
- Writes `adb_wifi_enabled=1`, `ADB_ENABLED=1`, `adb_allowed_connection_time=0` into `Settings.Global`.
- Uses `AdbMdns` to resolve a local wireless ADB port via mDNS (`TLS_CONNECT`).
- Connects via `AdbClient` with a stored `AdbKey` (from `ShizukuSettings.getPreferences()`).
- Calls `shellCommand(Starter.internalCommand, null)`.
- Awaits up to 3 seconds for the mDNS port to appear.
- **Risk**: mDNS resolution is timing-dependent; 3s may be insufficient on slow devices.

#### Path C — Manual ADB command
- Shown as `Starter.adbCommand` = `adb shell <nativeLibDir>/libshizuku.so`.
- User executes this manually.

#### Path D — Nightzuku Connectors (Lab feature)
- Third-party "Activator" APKs query `content://kerneldroid.nightzuku.connector`.
- `ShizukuConnectorProvider` returns `Starter.internalCommand` when `ModuleSettings.isConnectorEnabled()` is true.
- **Disabled by default.** User must opt-in via Lab Features with an explicit safety warning.

### 2.2 Server Initialization (`ShizukuService` constructor)

```
ShizukuService()
  waitSystemService("package")
  waitSystemService(ACTIVITY_SERVICE)
  waitSystemService(USER_SERVICE)
  waitSystemService(APP_OPS_SERVICE)
  getManagerApplicationInfo()       // exit(50) if null
  getConfigManager()                // loads /data/user_de/0/com.android.shell/shizuku.json
  getClientManager()
  ApkChangedObservers.start(ai.sourceDir)  // watch for manager APK removal
  BinderSender.register(this)       // registers ProcessObserver + UidObserver
  mainHandler.post { sendBinderToClient(); sendBinderToManager() }
```

**Findings:**
- The `waitSystemService()` loop is a naive 1-second-interval busy-wait with no maximum timeout. If a service never starts, the server loops forever.
- `System.exit(50)` on manager-not-found is the only hard exit besides service exit. Exit code 50 is `MANAGER_APP_NOT_FOUND`.
- `DdmHandleAppName.setAppName("shizuku_server", 0)` is called, but the process name visible in adb is still controlled by the shell's `--nice-name` argument.
- RishConfig library path is set from `System.getProperty("shizuku.library.path")` — this is injected by the native starter.

### 2.3 Config Storage

- Config file: `/data/user_de/0/com.android.shell/shizuku.json` (DE storage, survives reboot but not factory reset).
- Uses `AtomicFile` for crash-safe writes.
- Debounced writes via `HandlerKt.getWorkerHandler().postDelayed(..., 10_000ms)`.
- On startup, dead UIDs (no packages) are pruned. Stale package-name associations are cleared.
- Runtime permissions (`moe.shizuku.manager.permission.API_V23`) are synced from `PermissionManagerApis` on load.

---

## 3. Shell Execution

### 3.1 Shell Binder Request Flow (`rish`)

`ShizukuShellLoader` (in the `:shell` module) implements the `rish` entrypoint:

1. Determine calling package via `PackageManagerApis.getPackagesForUidNoThrow(Os.getuid())`.
   - If multiple packages share the UID, falls back to `RISH_APPLICATION_ID` environment variable.
2. Sends broadcast `rikka.shizuku.intent.action.REQUEST_BINDER` to package `moe.shizuku.privileged.api` (upstream hardcoded name — **NOT updated to `kerneldroid.nightzuku`**).
3. **Issue:** The broadcast target package is `moe.shizuku.privileged.api`, which is the old Shizuku package name. If only Nightzuku is installed, this broadcast will fail to reach the manager.
4. Falls back on Android 8.0/8.1 to `startActivityAsUser` (chooser intent).
5. 5-second timeout: if no binder received, prints timeout message and exits.

The manager handles this in `ShizukuReceiver`:
- Listens for `rikka.shizuku.intent.action.REQUEST_BINDER`.
- Delegates to `ShellBinderRequestHandler.handleRequest()`.
- `ShellBinderRequestHandler` fetches `Shizuku.getBinder()` and transacts the caller's binder (code=1) with the Shizuku binder + `applicationInfo.sourceDir`.

`ShizukuShellLoader.onBinderReceived()`:
- Extracts `sourceDir` from the transaction.
- Builds a `BaseDexClassLoader` pointing at Nightzuku's APK.
- Reflectively invokes `moe.shizuku.manager.shell.Shell.main(args, packageName, binder, handler)`.

`Shell.java` (in `:manager`):
- Extends `rikka.rish.Rish`.
- Initializes `RishConfig` with the binder, descriptor, and 30s timeout.
- Calls `Shizuku.onBinderReceived()` then waits for `addBinderReceivedListenerSticky`.
- Requires server API version ≥ 12.
- Handles permission request inline (grant/rationale/denied path).

### 3.2 Module Shell Execution (ADB Modules)

Modules execute via `IShizukuService.newProcess()`:
- Passes `["sh", script.absolutePath]` or `["sh", "-c", command]`.
- Environment: `MODDIR`, `ASH_STANDALONE=1`, `SHIZUKU_MODULE_ID`, `SHIZUKU_MODULE_MODE`, `SHIZUKU_MODULE_TRUSTED`, `SHIZUKU_MODULE_BACKGROUND`.
- stdout/stderr read via `ParcelFileDescriptor.AutoCloseInputStream`.
- Timeout via `remote.waitForTimeout(120, SECONDS)` — uses reflection on the `IUserService` proxy.
- No stdin pipe kept open for scripts (immediately closed with `AutoCloseOutputStream`).

### 3.3 WebUI Shell Bridge (`ModuleJsBridge`)

- Exposed as `window.Shizuku` in WebView.
- `exec(command)` runs `sh -c <command>` via `IShizukuService.newProcess()`.
- `execWithOptions(command, optionsJson)` accepts: `timeoutSeconds` (1–600s), `stdin` (≤64KB), `cwd`, `env` (≤32 vars, each key `[A-Za-z_][A-Za-z0-9_]*`).
- Command approval gate: if `recommandForWebUi()` is true and module is not trusted, shows `ReCommandDialog`.
- `download(url, path)` HTTPS-only, ≤20MB, ≤5 redirects, cannot overwrite `index.html`.

---

## 4. Shizuku Compatibility Layer

### 4.1 Android 17 / API 37 Compatibility (`Android17Compat`)

Nightzuku's primary compatibility innovation over upstream Shizuku is `Android17Compat.java`.

**Background:** Android 17 (Cinnamon Bun) changed `IPackageManager` and `IPermissionManager` hidden API signatures to add a `deviceId` parameter for Virtual Device support.

**Strategy:** `Android17Compat` wraps all system service calls behind a try/catch on `NoSuchMethodError`:
1. First attempts `PackageManagerApis.*` / `PermissionManagerApis.*` (the hidden-compat library).
2. On `NoSuchMethodError`, falls through to dynamic reflection.
3. Dynamic reflection uses `findMethod()` — scans all methods by name + prefix parameter types, selecting the longest match (most parameters).
4. `invokeMethod()` detects if the resolved method has `paramCount = prefixArgs.length + 1` (extra `deviceId` slot) and injects `DEVICE_ID_DEFAULT (0)` at the correct position.

**Affected APIs covered:**
- `getInstalledPackages(long flags, int userId)` — extra deviceId slot injected.
- `getPackageInfo(String, long, int)` — same.
- `getApplicationInfo(String, long, int)` — same.
- `checkPermission(String, String, int)` — both 3-arg and UID overloads.
- `grantRuntimePermission(String, String, int)` — deviceId injection.
- `revokeRuntimePermission(String, String, int)` — detects 5-arg form (with caller string) and handles separately.

**Caching:** All resolved `Method` objects and proxy objects are `volatile` instance fields, using double-checked locking. This is correct for Java's memory model (≥ Java 5) but there is a minor risk: if the class loader is replaced (unlikely in practice), stale Method references persist.

**Gap:** There is no validation that `DEVICE_ID_DEFAULT = 0` is the correct device ID for the runtime context. If the server runs in a secondary virtual device context, this assumption may be incorrect.

### 4.2 `ShizukuSystemApis` (Manager-side compat)

A parallel but distinct implementation exists in the manager process (`ShizukuSystemApis.kt`):
- Uses `ShizukuBinderWrapper` to wrap system service binders through the Shizuku proxy.
- Same method scanning strategy as `Android17Compat`, but in Kotlin.
- `invokeCompat()` is analogous to `Android17Compat.invokeMethod()`.
- **Code duplication:** The logic in `ShizukuSystemApis.kt` closely mirrors `Android17Compat.java`. These two are not unified.

### 4.3 IContentProvider Compatibility (`IContentProviderUtils`)

Handles `IContentProvider.call()` signature changes across Android versions:
- API ≥ 31 (S): Uses `AttributionSource`.
- API ≥ 30: Adds `featureId` string.
- API ≥ 29: Adds `authority` string.
- API < 29: Two-arg form.

This is used by `ShizukuService.sendBinderToUserApp()` and `ServiceStarter.sendBinder()`.

---

## 5. Root Emulation

### 5.1 Root Path

Nightzuku does **not** implement its own root emulation. It relies on:
- `com.topjohnwu.superuser` (libsu) for root shell execution at startup.
- The server process itself runs as shell UID 2000 (ADB) or UID 0 (root), depending on how it was started.
- There is **no UID-spoofing layer**, **no SELinux context change**, and **no process privilege escalation** performed by Nightzuku itself.

### 5.2 UID Reported to Clients

`ShizukuService.attachApplication()` sends to clients:
```java
reply.putInt(BIND_APPLICATION_SERVER_UID, OsUtils.getUid());
reply.putString(BIND_APPLICATION_SERVER_SECONTEXT, OsUtils.getSELinuxContext());
```
- The server reports its actual UID and SELinux context — whatever was granted by the start method.
- No emulation layer masks or overrides these values.

### 5.3 Permission Grant at Startup (Manager)

When the manager app attaches (`isManager == true`):
```java
Android17Compat.grantRuntimePermission(
    MANAGER_APPLICATION_ID, WRITE_SECURE_SETTINGS,
    UserHandleCompat.getUserId(callingUid));
```
This is an automatic self-grant — the server grants `WRITE_SECURE_SETTINGS` to itself (the manager) using its own elevated UID. This is the closest mechanism to root emulation in Nightzuku: leveraging the server's privilege to grant permissions to the manager.

### 5.4 `checkCallerManagerPermission`

```java
return UserHandleCompat.getAppId(callingUid) == managerAppId;
```
Manager authentication is purely UID-based. There is no signature verification. An app that can spoof the manager UID (e.g., under shared UID) would pass this check.

---

## 6. Binder Delivery

### 6.1 Server-to-Client Binder Delivery (`sendBinderToUserApp`)

On server startup, binders are sent to all qualifying apps via `ContentProvider.call()`:

1. `DeviceIdleControllerApis.addPowerSaveTempWhitelistApp(packageName, 30_000ms)` — prevent app from being killed before binder arrives.
2. Resolve content provider authority `<packageName>.shizuku` via `ActivityManagerApis.getContentProviderExternal()`.
3. If provider is null: log error and return.
4. If provider is dead: retry once after `forceStopPackage` + 1000ms sleep.
5. Call `IContentProviderUtils.callCompat(provider, null, name, "sendBinder", null, extra)` where extra contains a `BinderContainer` wrapping the service binder.
6. Release provider via `removeContentProviderExternal`.

**Gap:** The retry logic (dead provider) calls `forceStopPackage` on the target. This is destructive and may interrupt app state. It only retries once.

### 6.2 Proactive Binder Delivery (`BinderSender`)

`BinderSender` is registered after server init and observes process/UID lifecycle:

- **`ProcessObserver`**: Hooks `onForegroundActivitiesChanged` and `onProcessStateChanged`. Sends binder when a new PID first appears in the foreground. Deduplicates on `startedPids` (cleared on `onProcessDied`).
- **`UidObserver`** (API ≥ 26): Hooks `onUidActive`, `onUidCachedChanged(cached=false)`, `onUidIdle`. Deduplicates on `startedUids`. Cleared on `onUidGone`.

For each qualifying package under the UID:
- If package requests `PERMISSION_MANAGER` and it is granted: sends binder to manager.
- If package requests `API_V23`: sends binder to user app.

**Finding:** `UidObserver.onUidIdle` triggers `uidStarts(uid)` and sends the binder even when the UID is going idle. This appears intentional (re-send on transition) but will attempt delivery to an app that may be cached/freezing.

### 6.3 User Service Binder Delivery (`ServiceStarter`)

User services are started via `ShizukuUserServiceManager.getUserServiceStartCmd()`:
```
(CLASSPATH=<managerApk> /system/bin/app_process [debugArgs] /system/bin \
  --nice-name=<pkg>:<suffix> moe.shizuku.starter.ServiceStarter \
  --token=<token> --package=<pkg> --class=<class> --uid=<uid>)&
```
- `use32Bits` tries `/system/bin/app_process32` first.
- User service sends its binder back to Nightzuku via the `<packageName>.shizuku` ContentProvider, calling `sendUserService`.
- `ShizukuManagerProvider.call("sendUserService")` waits up to 5 seconds for `Shizuku.onBinderReceived` on a `workerHandler`.
- APK change observers track the user service's APK for hot-reload on update.

---

## 7. Key Findings and Risks

### 7.1 Critical

| # | Finding | Risk | Location |
|---|---|---|---|
| C1 | `ShizukuShellLoader.requestForBinder()` sends broadcast to `moe.shizuku.privileged.api`, NOT `kerneldroid.nightzuku` | `rish` shell will not work when only Nightzuku is installed | `ShizukuShellLoader.java:57` |
| C2 | `waitSystemService()` has no timeout | Server may loop forever on unusual ROM/device | `ShizukuService.java:113` |
| C3 | Manager auth is UID-only, no signature check | Shared-UID exploit could impersonate manager | `ShizukuService.java:189` |

### 7.2 High

| # | Finding | Risk | Location |
|---|---|---|---|
| H1 | ADB auto-start mDNS resolution has 3s timeout only | May fail on slow-boot devices before ADB daemon ready | `BootCompleteReceiver.kt:84` |
| H2 | `forceStopPackage` on retry in `sendBinderToUserApp` | Destructive side-effect; only one retry attempt | `ShizukuService.java:574` |
| H3 | `Android17Compat` and `ShizukuSystemApis` duplicate reflection logic | Divergence risk; fixes applied in one won't apply to the other | Both files |
| H4 | `DEVICE_ID_DEFAULT = 0` hardcoded for Android 17 virtual device APIs | May be incorrect in virtual device contexts | `Android17Compat.java:20` |

### 7.3 Medium

| # | Finding | Risk | Location |
|---|---|---|---|
| M1 | No root emulation layer — reports actual server UID/SEContext | Client apps cannot use Nightzuku as a root emulator | Architecture-wide |
| M2 | `UidObserver.onUidIdle` sends binder to idle UIDs | Wasted delivery attempts; could wake processes unnecessarily | `BinderSender.kt:97-99` |
| M3 | 10-second write debounce on config | Permission changes may be lost on hard crash within debounce window | `ShizukuConfigManager.java:40` |
| M4 | `ShizukuConnectors` exposes `internalCommand` string to any querying app | Must trust every installed Activator APK | `ShizukuConnectorProvider.kt` |
| M5 | `runCompatTest()` exists in `ShizukuService` but is never called | Dead test code; should be removed or gated | `ShizukuService.java:70` |

### 7.4 Low / Informational

| # | Finding | Risk | Location |
|---|---|---|---|
| L1 | `sendBinderToManger` (typo) deprecated alias exists | Minor dead code | `ShizukuService.java:543` |
| L2 | `SHIZUKU_MODULE_TRUSTED` env var exposed to module scripts | Module can detect its trust status and behave differently | `AdbModuleManager.kt:149` |
| L3 | Config stored under `com.android.shell` namespace | Shell user shared storage; theoretically accessible to other shell processes | `ShizukuConfigManager.java:42` |

---

## 8. Features Unique to Nightzuku (vs. upstream Shizuku)

1. **Android 17 / API 37 compatibility** — `Android17Compat.java` with dynamic method resolution and deviceId injection.
2. **ADB Modules system** — ZIP-based module runner with `action.sh`, `service.sh`, WebUI, JS-to-shell bridge, ReCommand approval gate, trust levels, and per-module logs.
3. **Nightzuku Connectors** — `ShizukuConnectorProvider` ContentProvider for third-party Activator APKs (Lab feature).
4. **Multi-form-factor UI** — Jetpack Compose with Material 3 Expressive on Phone, native Wear OS, native Android TV.
5. **Material 3 Expressive design system** — `ShizukuExpressive.kt`, full Compose migration.
6. **ADB auto-start on boot via wireless ADB** (Android 13+).
7. **Module WebUI HTTPS asset downloader** with redirect following, size limits, and no-overwrite guard on `index.html`.
8. **`ModuleCommandReview` (ReCommand)** — per-command approval dialog for untrusted module WebUI commands.

---

## 9. Summary Assessment

Nightzuku is a solid Shizuku fork with meaningful additions: Android 17 compatibility is the most technically significant, and the ADB modules system adds a unique capability not found in upstream Shizuku. The Connectors API is architecturally interesting but requires careful threat modeling.

The most critical unfixed issue is **C1**: the `rish` shell loader still broadcasts to `moe.shizuku.privileged.api` rather than `kerneldroid.nightzuku`, which means `rish`/shell bridging is broken for pure Nightzuku installs unless upstream Shizuku is also present.

The absent root emulation layer means Nightzuku is **not** a drop-in replacement for Stellar's root emulation — it provides elevated-privilege IPC brokering, not UID 0 process injection or syscall routing.
