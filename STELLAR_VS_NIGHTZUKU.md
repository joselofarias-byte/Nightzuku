# STELLAR_VS_NIGHTZUKU.md

> Comparison date: 2026-06-15
> Basis: full source traversal of Nightzuku + Stellar architecture knowledge from AGENTS.md
> Audit is read-only. No code was modified.

---

## Overview

**Stellar** and **Nightzuku** share a common ancestor — the Shizuku privileged IPC framework — but they serve fundamentally different purposes and operate at different privilege levels.

| Attribute | Stellar | Nightzuku |
|---|---|---|
| Purpose | Root emulation engine | Shizuku-compatible IPC broker fork |
| Base | Shizuku server + native root emulation layer | Shizuku server (upstream fork) |
| Privilege model | Emulates UID 0 behavior from shell UID 2000 | Brokers elevated IPC at whatever UID the server runs |
| Package identity | Internal / device-resident | `kerneldroid.nightzuku` |
| Client API | Shizuku-compatible + Stellar-specific extensions | Shizuku-compatible (`moe.shizuku.manager.permission.API_V23`) |
| Form factor targets | Phone (primary) | Phone + Wear OS + Android TV |
| Module system | None (shell command routing) | ADB ZIP modules with WebUI, scripts, JS bridge |
| Android 17 compat | Partial (depends on version) | Full via `Android17Compat.java` |

---

## 1. Startup Differences

### 1.1 Stellar Startup

| Mechanism | Stellar |
|---|---|
| Root emulation init | Native layer starts before JVM; UID 2000 shell acquires capabilities via kernel emulation |
| Binder service attach | Attaches to system_server binder at service start |
| Startup trigger | Shizuku server launch → Stellar module hook → capability acquisition |
| Boot persistence | Managed by Stellar service restart logic; reconnects on Binder death |
| Startup fallback paths | Shell (UID 2000) → Root (UID 0) → Stellar emulation layer |

### 1.2 Nightzuku Startup

| Mechanism | Nightzuku |
|---|---|
| Root init | None — delegates entirely to libsu or ADB |
| Server start | `libshizuku.so` native binary via `app_process` |
| Startup trigger | User/root shell → `Starter.internalCommand` |
| Boot persistence | `BootCompleteReceiver` (root or ADB auto-start) |
| Startup fallback paths | Root → ADB wireless (Android 13+) → Manual ADB → Connectors |

### 1.3 Startup Comparison Table

| Dimension | Stellar | Nightzuku | Gap |
|---|---|---|---|
| Service timeout guard | Yes (reconnect with backoff) | No (`waitSystemService` loops forever) | **Nightzuku missing** |
| Boot persistence | Stellar restart manager | `BootCompleteReceiver` (root + ADB) | Functional parity, different approach |
| ADB wireless auto-start | Not applicable | Yes (Android 13+, 3s mDNS window) | **Nightzuku unique** |
| Third-party activator API | No | Yes (Connectors, Lab feature) | **Nightzuku unique** |
| 32-bit process support | Stellar-managed | `app_process32` fallback via `use32Bits` | Parity |
| Debug JDWP support | Stellar-managed | SDK-version-gated JDWP args in `ServiceStarter` | Parity |
| Multi-user startup | Stellar handles multi-user natively | Nightzuku iterates `UserManagerApis.getUserIdsNoThrow()` | Parity |

---

## 2. Shell Execution Differences

### 2.1 Stellar Shell Execution

- Stellar intercepts shell commands at the binder/IPC level and routes them with emulated UID 0 context.
- Commands execute with Stellar's emulated root privilege, including SELinux context modifications.
- The `rish` shell in Stellar context receives an IPC binder with root-equivalent privilege.
- No separate "module" concept — privileged shell is the execution model.

### 2.2 Nightzuku Shell Execution

- `rish` / `ShizukuShellLoader` bridges via broadcast to get the Shizuku binder, then calls `Shell.main()`.
- Actual shell privilege is whatever the server was started with (shell UID 2000 for ADB, UID 0 for root).
- ADB Modules add a parallel shell execution path via `IShizukuService.newProcess()`.
- WebUI JS bridge further extends shell access to web interfaces.

### 2.3 Shell Execution Comparison Table

| Dimension | Stellar | Nightzuku | Gap |
|---|---|---|---|
| Shell privilege level | Emulated UID 0 from UID 2000 | Actual server UID (0 or 2000) | **Stellar advantage** |
| SELinux context | Stellar modifies/emulates | Server's actual context (reported verbatim) | **Stellar advantage** |
| `rish` broadcast target | `<stellar-pkg>` (correct) | `moe.shizuku.privileged.api` (upstream hardcode — **bug**) | **Nightzuku defect** |
| Module shell execution | No module system | `action.sh` / `service.sh` via `IShizukuService.newProcess()` | **Nightzuku unique** |
| WebUI-to-shell bridge | No | `ModuleJsBridge.exec()` via WebView `@JavascriptInterface` | **Nightzuku unique** |
| ReCommand approval gate | No | Yes, per-command dialog for untrusted WebUI modules | **Nightzuku unique** |
| Timeout enforcement | Stellar-specific | 120s default, 1–600s configurable in WebUI bridge | Nightzuku more granular |
| stdin injection | Not applicable | Supported via `execWithOptions.stdin` (≤64KB) | **Nightzuku unique** |
| Custom env vars | Not applicable | Up to 32 vars, validated key format | **Nightzuku unique** |

---

## 3. Shizuku Compatibility Differences

### 3.1 Stellar Shizuku Compatibility

- Stellar is built **on top of** the Shizuku protocol — it presents a Shizuku-compatible service to clients.
- Stellar extends the Shizuku binder with additional transactions for root emulation capabilities.
- Stellar handles Binder lifecycle (death, reconnect) with service restart logic and exponential backoff.
- Android SDK compatibility is managed through Stellar's internal hidden API access strategy.

### 3.2 Nightzuku Shizuku Compatibility

- Nightzuku **is** a direct Shizuku server fork — it is the canonical Shizuku protocol implementation.
- Android 17 compatibility is Nightzuku's signature addition: `Android17Compat.java` wraps all system service calls with dynamic method resolution and `deviceId` injection.
- Manager-side compat is duplicated in `ShizukuSystemApis.kt` — a parallel Kotlin implementation of the same reflection strategy.

### 3.3 Shizuku Compatibility Comparison Table

| Dimension | Stellar | Nightzuku | Gap |
|---|---|---|---|
| Android 17 IPackageManager compat | Partial / version-dependent | Full via `Android17Compat.java` (dynamic deviceId injection) | **Nightzuku advantage** |
| Android 17 IPermissionManager compat | Partial | Full (grant/revoke/check all covered) | **Nightzuku advantage** |
| IContentProvider.call() versioning | Stellar-managed | `IContentProviderUtils.callCompat()` covers API 28–31+ | Parity |
| Binder death handling | Reconnect with backoff | APK change observer only (no binder reconnect from client side) | **Stellar advantage** |
| Work profile (managed profile) support | Yes | Yes (isWorkProfileUser check in showPermissionConfirmation) | Parity |
| Pre-v11 Shizuku client support | Not applicable | Yes (AuthorizationManager.getPackages() handles isPreV11) | Nightzuku maintains legacy |
| Client permission runtime grant/revoke | Stellar-extended | Standard Shizuku: `Android17Compat.grantRuntimePermission` | Parity |
| Multi-device (DEVICE_ID) awareness | Stellar-managed | Hardcoded `DEVICE_ID_DEFAULT = 0` | **Stellar advantage** |
| Binder descriptor | Stellar-specific | `moe.shizuku.server.IShizukuService` (upstream) | Nightzuku: upstream compatible |

---

## 4. Root Emulation Differences

This is the most significant architectural divergence between Stellar and Nightzuku.

### 4.1 Stellar Root Emulation

| Feature | Stellar |
|---|---|
| UID emulation | Emulates UID 0 behavior from UID 2000 shell context |
| Capability acquisition | Native layer acquires specific Linux capabilities without true UID 0 |
| SELinux | Context-aware; attempts to match UID 0's SELinux domain |
| Kernel syscall routing | Routes privileged syscalls through Stellar's emulation kernel |
| App compatibility | Apps using `isRoot()` checks may or may not detect Stellar depending on emulation depth |
| Detection surface | Stellar emulation has a different detection profile than Magisk/KSU/APatch |
| Magisk/KSU/APatch fallback | Yes — degrades gracefully if real root is present |

### 4.2 Nightzuku Root Emulation

Nightzuku does **not** have a root emulation layer. This is by design:
- Nightzuku is a privilege **broker**, not a privilege **emulator**.
- When started via ADB, the server runs as UID 2000 (shell). When started via root, it runs as UID 0.
- No UID spoofing, no capability acquisition, no SELinux context manipulation.
- Clients learn the server's true UID/SELinux context via `bindApplication` reply.

### 4.3 Root Emulation Comparison Table

| Dimension | Stellar | Nightzuku | Gap |
|---|---|---|---|
| UID 0 emulation | Yes | No | **Stellar unique** |
| SELinux context emulation | Yes | No (reports actual context) | **Stellar unique** |
| Linux capability acquisition | Yes (via emulation layer) | No | **Stellar unique** |
| `su`-compatible shell output | Yes (emulated) | No | **Stellar unique** |
| Works without real root | Yes (core purpose) | No for root-required ops | **Stellar unique** |
| Root detection bypass | Stellar-specific surface | Not applicable | **Stellar unique** |
| Compatibility with Magisk | Fallback path | Independent (root start uses libsu) | Different model |
| Compatibility with KSU | Fallback path | Independent | Different model |
| Compatibility with APatch | Fallback path | Independent | Different model |
| Self-grant `WRITE_SECURE_SETTINGS` | Via emulated privilege | Yes (explicit grant in `attachApplication`) | Both handle this |

---

## 5. Binder Delivery Differences

### 5.1 Stellar Binder Delivery

- Stellar maintains a persistent binder service with reconnection logic.
- Service death triggers automatic reconnect with exponential backoff (per AGENTS.md guidance).
- Binder delivery to new clients is event-driven from Stellar's process observation.
- Binder lifecycle is tightly coupled to Stellar's root emulation lifecycle.

### 5.2 Nightzuku Binder Delivery

- **Initial delivery**: On server start, `sendBinderToClient()` scans all users for packages requesting `API_V23` and sends the binder via ContentProvider.
- **Reactive delivery**: `BinderSender` registers both `ProcessObserver` and `UidObserver` (API ≥ 26).
  - `ProcessObserver`: per-PID deduplication, triggered by foreground activity changes and process state changes.
  - `UidObserver`: per-UID deduplication, triggered by active/cached/idle transitions.
- **Power exemption**: Each delivery adds the target package to the device idle temp whitelist for 30 seconds.
- **Retry**: One retry on dead provider (after `forceStopPackage` + 1s sleep).
- **No reconnect**: There is no client-initiated or server-initiated binder reconnect after the initial delivery. If the server restarts, a new boot sequence must occur.

### 5.3 Binder Delivery Comparison Table

| Dimension | Stellar | Nightzuku | Gap |
|---|---|---|---|
| Binder reconnect after server death | Yes (automatic, with backoff) | No (requires re-start) | **Stellar advantage** |
| Process observation | Stellar-managed | `ProcessObserver` (foreground + state change) | Parity |
| UID observation | Stellar-managed | `UidObserver` (API ≥ 26, active/cached/idle/gone) | Parity |
| Power save whitelist | Stellar-managed | `DeviceIdleControllerApis.addPowerSaveTempWhitelistApp(30s)` | Nightzuku explicit |
| Provider dead retry | Stellar-managed | Once: `forceStopPackage` + 1s sleep + retry | Nightzuku: single retry |
| Multi-user binder delivery | Yes | Yes (iterates `getUserIdsNoThrow`) | Parity |
| User service binder handshake | Stellar: internal | `ShizukuManagerProvider.call("sendUserService")` with 5s latch | Nightzuku: upstream protocol |
| APK change tracking | No | Yes (`ApkChangedObservers` / `FileObserver` on `base.apk` DELETE) | **Nightzuku unique** |
| Manager binder (separate from client) | No explicit separation | Yes — `sendBinderToManager()` distinct from `sendBinderToClient()` | Nightzuku: explicit |

---

## 6. Features Present in Stellar but Absent in Nightzuku

| Feature | Stellar | Notes |
|---|---|---|
| Root emulation layer | ✅ | Core Stellar capability |
| UID 0 emulation | ✅ | Via capability acquisition |
| SELinux context emulation | ✅ | Domain-aware privilege |
| Binder auto-reconnect (server death) | ✅ | Exponential backoff |
| Kernel syscall routing | ✅ | Platform-dependent |
| Shell compatibility under emulated root | ✅ | Broad app compatibility |
| Detection bypass surface | ✅ | Different from Magisk/KSU |
| Magisk/KSU/APatch explicit fallback | ✅ | Capability detection then degrade |
| Multi-device (DEVICE_ID) dynamic handling | ✅ | Stellar-managed |
| Long-running service supervision | ✅ | Server manages service lifecycle |

---

## 7. Features Present in Nightzuku but Absent in Stellar

| Feature | Nightzuku | Notes |
|---|---|---|
| Android 17 dynamic API compat (`Android17Compat`) | ✅ | `deviceId` injection, method resolution |
| ADB Modules system | ✅ | ZIP install, `action.sh`, `service.sh`, WebUI |
| Module JS-to-shell bridge (`ModuleJsBridge`) | ✅ | `window.Shizuku.exec()` from WebView |
| ReCommand approval gate | ✅ | Per-command dialog for untrusted modules |
| Module trust levels (Safe/Custom/Full/Trusted) | ✅ | Granular per-module permission model |
| Nightzuku Connectors (Activator API) | ✅ | ContentProvider for LPE activators |
| ADB wireless auto-start on boot | ✅ | Android 13+, mDNS, `AdbClient` |
| Wear OS native UI | ✅ | Full `WearHomeScreen`, `WearModules`, etc. |
| Android TV native UI | ✅ | `TVHomeScreen`, `TvModules`, Leanback |
| Material 3 Expressive Compose UI | ✅ | `ShizukuExpressiveTheme`, custom tokens |
| Module WebUI HTTPS asset downloader | ✅ | `download()`, 20MB limit, redirect follow |
| Module last-run log persistence | ✅ | `action-last.log`, `service-last.log` |
| Per-session service run guard | ✅ | `servicesStartedForBinder` flag |
| Pre-v11 Shizuku legacy client support | ✅ | `AuthorizationManager.isPreV11()` path |
| APK change observer for user services | ✅ | `ApkChangedObservers` via `FileObserver` |

---

## 8. Migration Recommendations

The following recommendations address gaps in Nightzuku relative to Stellar's stronger aspects, and gaps in both relative to production hardening.

### 8.1 Critical — Fix Before Release

| # | Recommendation | Target | Rationale |
|---|---|---|---|
| R1 | Fix `ShizukuShellLoader` broadcast target from `moe.shizuku.privileged.api` to `kerneldroid.nightzuku` | `ShizukuShellLoader.java:57` | `rish` is completely broken for standalone Nightzuku installs |
| R2 | Add a maximum iteration cap or timeout to `waitSystemService()` | `ShizukuService.java:113` | Prevents infinite loops on unusual ROMs |
| R3 | Add signature verification alongside UID check in `checkCallerManagerPermission` | `ShizukuService.java:188` | Shared-UID impersonation risk |

### 8.2 High — Implement for Stability

| # | Recommendation | Target | Rationale |
|---|---|---|---|
| R4 | Add binder reconnect logic (with capped backoff) for server death recovery | Architecture | Stellar has this; Nightzuku requires full restart |
| R5 | Extend ADB auto-start mDNS timeout from 3s to at least 8s with retry | `BootCompleteReceiver.kt:84` | Slow-boot devices miss the window |
| R6 | Unify `Android17Compat.java` and `ShizukuSystemApis.kt` into a single compatibility module | Both files | Divergence means fixes in one miss the other |
| R7 | Add `DEVICE_ID_DEFAULT` runtime validation or make it configurable | `Android17Compat.java:20` | Virtual device contexts may require a non-zero device ID |

### 8.3 Medium — Improve Reliability

| # | Recommendation | Target | Rationale |
|---|---|---|---|
| R8 | Replace `forceStopPackage` on provider-dead retry with a softer recovery (wait + re-acquire) | `ShizukuService.java:574` | `forceStopPackage` is destructive; prefer wait-only |
| R9 | Remove or gate `runCompatTest()` with a build flag | `ShizukuService.java:70` | Dead code in production server |
| R10 | Remove `sendBinderToManger` typo alias | `ShizukuService.java:543` | Dead code |
| R11 | Add `UidObserver.onUidIdle` delivery guard (check if process is actually resuming, not just idling) | `BinderSender.kt:97` | Spurious deliveries to idle UIDs |
| R12 | Reduce config write debounce or add explicit flush on controlled exit | `ShizukuConfigManager.java:40` | Permission changes lost on crash within 10s window |

### 8.4 Architecture — Strategic Decisions

| # | Recommendation | Rationale |
|---|---|---|
| R13 | Evaluate adding Stellar's root emulation layer to Nightzuku as an opt-in mode | Would allow Nightzuku to serve apps that perform `isRoot()` checks, without requiring true root |
| R14 | Evaluate moving Nightzuku Connectors from Lab to a stable opt-in with package-signature allowlist | Current implementation trusts any installed app that queries the provider |
| R15 | Consider migrating module service supervision to a persistent foreground service | Current run-once-per-binder-session model cannot restart crashed services |
| R16 | Evaluate adding Magisk/KSU/APatch detection and capability probing at server start (per AGENTS.md) | Enables graceful degradation paths and proper capability reporting to clients |

---

## 9. Summary Decision Matrix

| Capability | Stellar | Nightzuku | Recommended Action |
|---|---|---|---|
| Root emulation (UID 0) | ✅ | ❌ | Port or integrate Stellar's emulation layer as optional Nightzuku mode |
| Android 17 API compat | Partial | ✅ | Backport Nightzuku's `Android17Compat` to Stellar |
| Binder reconnect | ✅ | ❌ | Add to Nightzuku (R4) |
| `rish` shell bridging | ✅ | ❌ (bug) | Fix R1 immediately |
| ADB boot auto-start | ❌ | ✅ | Evaluate for Stellar where applicable |
| ADB Modules | ❌ | ✅ | Stellar-specific opportunity if shell execution model permits |
| WebUI JS bridge | ❌ | ✅ | Stellar-specific opportunity |
| Multi-form-factor UI | Phone | Phone + Wear + TV | Nightzuku reference for Stellar UI expansion |
| Service supervision | ✅ | ❌ | Implement in Nightzuku (R15) |
| Startup timeout guard | ✅ | ❌ | Fix R2 |
| Manager signature verification | Unknown | ❌ | Fix R3 in both |

---

## 10. Conclusion

Stellar and Nightzuku are **complementary, not competing** in their current forms:

- **Stellar** is the privilege escalation engine — it creates the elevated context that both systems ultimately rely on.
- **Nightzuku** is the IPC brokering and developer-facing surface — it exposes that elevated context to client apps and provides the module/WebUI ecosystem.

The most impactful immediate actions for Nightzuku are:
1. **Fix R1** (rish broadcast target) — blocks the core shell use case.
2. **Implement R4** (binder reconnect) — brings reliability up to Stellar's standard.
3. **Fix R2** (startup timeout) — prevents hung server processes on unusual devices.

The most impactful strategic decisions are:
- Whether Nightzuku should adopt Stellar's root emulation layer (R13).
- Whether Nightzuku Connectors should have a stricter allowlist model (R14).
