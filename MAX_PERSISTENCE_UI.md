# Maximum persistence UI

Branch: `feature/nightzuku-max-persistence-ui`
Base: `cursor/rish-postmerge-audit-5a11` (includes rish hardening)
applicationId: `com.joselofarias.nightzuku`

This is a usability closure on existing NightDog / persistent-TCP work from historical PRs #4/#5/#6. It does **not** reimplement transport selection or the watchdog.

## 1. Audit — what already worked before this UI

Traced on `cursor/rish-postmerge-audit-5a11` (`38a0024`) before the card was added.

### AdbTcpController

- `enable(host, port)` uses the **current authenticated Wireless debugging / mDNS endpoint** as the source, sends `tcpip:<port>`, then waits for the requested host:port to become socket-reachable.
- The Nightzuku preference is saved **only after** reachability succeeds. A failed switch does not overwrite a previous endpoint.
- `disable()` uses the stored persistent-TCP endpoint, sends official `usb:`, waits, and keeps the preference if the TCP port is still reachable.
- Default stored host/port in `ShizukuSettings` is already `127.0.0.1:5555`.

### AdbTransportResolver

- Normal startup priority: persisted TCP preference → current mDNS endpoint → caller-supplied host/port.
- `systemAdbTcpEndpoint()` reads `service.adb.tcp.port` via `getprop` and is **diagnostic / recovery-only**. It must not silently replace the user policy.
- `AdbMdns.getResolvedEndpoint()` already prefers the persistent-TCP preference for `TLS_CONNECT`.

### AdbMdns

- Discovers `_adb-tls-connect._tcp`, caches the real interface address, retries NSD failures with a 2–30s bound.
- Live discovered endpoint and persisted TCP are different things. A saved TCP preference can hide a live mDNS endpoint if callers use `getResolvedEndpoint()` blindly.

### NightDogRecovery.snapshot

Already a process-level `StateFlow` started from `ShizukuApplication.onCreate()`:

| Field | Meaning |
| --- | --- |
| `stage` | IDLE / CHECKING_BINDER / DISCOVERING_ADB / WAITING_FOR_ADB / STARTING_SERVICE / RUNNING / MANUALLY_STOPPED / ERROR |
| `desiredRunning` | Persisted in `nightdog_recovery` SharedPreferences |
| `binderAlive` | Last binder ping |
| `transport` / `endpoint` | Last selected transport |
| `failedAttempts` / `lastAttemptElapsedRealtime` | Backoff inputs |
| `serverPid` / `recoveryCount` | Best-effort PID and successful recoveries |
| `lastResult` | Last diagnostic line |

Existing behavior that must be reused, not cloned:

- Desired-running survives activity recreate and process recreate (prefs + `start()` in `Application`).
- Binder received/dead listeners request recovery.
- Poll every 4s; settle 1.5s; retry 8s → 16s → 32s → cap 60s.
- Manual stop (`prepareForManualStop`) cancels recovery and stays off.
- Recovery launches existing `StarterActivity` with host/port extras.

This UI **reads `NightDogRecovery.snapshot`**. It does not add a second watchdog.

### StarterActivity

- Root path and ADB path already exist.
- ViewModel previously called `AdbMdns.getResolvedEndpoint()`, which could replace an explicit NightDog host/port with the saved TCP preference when both were loopback.
- This branch honors an explicit caller host/port and only uses `AdbTransportResolver` when the caller did not pass one.

### BootCompleteReceiver

Already:

- Ignores non-boot actions and Android Safe Mode.
- Skips secondary users and an already-live binder.
- Root last-launch: one `libsu` start.
- ADB last-launch + `WRITE_SECURE_SETTINGS` on API 33+: one-shot enable of wireless debugging + 3s mDNS wait. Not a loop.

Gap for no-root HONOR: if persistent TCP is dead after reboot and the app has no `WRITE_SECURE_SETTINGS`, the receiver only logged “No support start on boot”. The UI could not tell the user that Wireless debugging must be turned on again.

### Settings / home UI before this branch

- Home status card already used NightDog artwork and a short recovery summary.
- Persistent TCP enable/disable and the server-kill recovery test lived in **Lab Features**, with hardcoded Spanish strings.
- Home Stop did not call `prepareForManualStop()` (Settings Stop did), so a home stop could be reversed by NightDog.

## 2. What this branch adds

- First-class **Maximum persistence** card on Home (and a Settings row → `MaximumPersistenceActivity`).
- Snapshot-driven fields: service state, Keep running, transport, endpoint, TCP health, PID, recovery count, last result / last failure, retry countdown.
- Actions on the existing controllers: Recover now, Enable/Test/Disable local TCP, Test recovery, open Developer options / Wireless debugging.
- Honest copy: “Local TCP recovery available” only after ADB authentication; otherwise “One-time Wireless debugging activation required”.
- Explicit limit: Nightzuku cannot recreate `uid=2000` after reboot if Developer options are fully disabled.
- Boot: one authenticated TCP probe. If it fails, set `adb_reactivation_required` and stop. No boot retry loop.
- Home Stop now persists desired-running = off.

## 3. HONOR 200 / Android 16 physical plan (no-root)

Do **not** repeat rish/Termux cases. Install the **signed release** APK from run `35476687249` artifact `10594811767` (`com.joselofarias.nightzuku`, SHA-256 `35aa8c240a1634d2d35765f9518b79b33260f53845b1e228625b820f65d942f3`). Do not use a later docs-only build.

Preconditions: Wireless debugging paired once; Keep running ON.

| Case | Action | Expected |
| --- | --- | --- |
| **A — arm TCP** | Enable Wireless debugging. Tap **Enable local TCP** (default `127.0.0.1:5555` unless a saved port exists). Tap **Test local TCP**. | TCP state becomes Enabled+reachable and **ADB authenticated · usable for restart**. Banner: Local TCP recovery available. |
| **B — Wireless debugging off, kill server** | After A succeeds, turn Wireless debugging off. Tap **Test recovery**. | If local TCP is still authenticated, NightDog recovers via persistent TCP. Card shows measured seconds + new PID. If TCP died with Wireless debugging, banner becomes activation required; no tight loop. |
| **C — Developer options off** | Disable Developer options fully. Kill the server or reboot. | Card must **not** claim recovery is available. Banner / reboot limit: Nightzuku cannot recreate uid=2000 with Developer options off. Recover now waits/retries 8–60s only. |
| **D — no Wi-Fi** | Disconnect Wi-Fi after TCP is armed. | Persistent `127.0.0.1` TCP can still recover. mDNS Wireless debugging must not be claimed usable. |
| **E — reboot** | Reboot with Keep running ON and TCP previously enabled. Open Nightzuku (no extra boot loops). | If local TCP is still alive: service can come back; reactivation flag clear. If not: **One-time Wireless debugging activation required**. Never an aggressive boot retry storm. |

Pass rule: every claim on the card matches a real probe (configured vs socket vs authenticated vs usable).

## 4. CI evidence

Code HEAD recorded here: `92f5446affc552969052c3a3807c451ddda635bd`  
(`46199b0` UI + `92f5446` backoff unit-test arithmetic fix).  
applicationId / aapt package: **`com.joselofarias.nightzuku`**  
versionName: `13.6.0.r47.92f5446` · versionCode `47` · target/compileSdk 36.

`Verify Nightzuku server` did **not** run: that workflow only triggers on PRs targeting `main`. This PR base is `cursor/rish-postmerge-audit-5a11`.

### 4.1 Workflow runs

| Workflow | Run ID | Event | Job | Job ID | HEAD | Conclusion |
| --- | --- | --- | --- | --- | --- | --- |
| Android PR build | [35476687249](https://github.com/joselofarias-byte/Nightzuku/actions/runs/35476687249) | push | build | 105987022778 | `92f5446` | **SUCCESS** |
| Android PR build | [35476688743](https://github.com/joselofarias-byte/Nightzuku/actions/runs/35476688743) | pull_request | build | 105987026393 | `92f5446` | **SUCCESS** |
| Android PR build | [35476258277](https://github.com/joselofarias-byte/Nightzuku/actions/runs/35476258277) | push | build | 105985911372 | `46199b0` | **FAILURE** (`:manager:testDebugUnitTest` expected 8s for attempt 2; fixed in `92f5446`) |
| Android PR build | [35476266199](https://github.com/joselofarias-byte/Nightzuku/actions/runs/35476266199) | pull_request | build | 105985932140 | `46199b0` | **FAILURE** (same test) |

HEAD `35476687249` / `35476688743` step conclusions (all SUCCESS): rish identity script, `:shell:testDebugUnitTest`, `:manager:testDebugUnitTest`, `assembleDebug`, `assembleRelease`, APK SHA/version verify, artifact upload.

### 4.2 Artifacts (HEAD `92f5446`)

GitHub `digest` is the artifact **zip**. APK SHA-256 is of the extracted file. Push and PR APKs were byte-identical.

| Run | Kind | Artifact ID | Name | Zip bytes | Zip digest | APK bytes | APK SHA-256 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 35476687249 | debug | 10594811766 | nightzuku-manager-debug-92f5446 | 30148765 | `sha256:4ac7c7616ed4a2a35d44d4ff8082450f2aef7a483d84b6a070366ffb98e5a39f` | 32201804 | `03184683a825d197f949756f216276d31671ddc6484bd818663e69bd0f296bb9` |
| 35476687249 | release | 10594811767 | nightzuku-manager-release-signed-92f5446 | 2879408 | `sha256:d0d34fb40f146d9e4dd2fb6f35031a22fd4d4310c1a18ace74c1bdea5610b28c` | 3736399 | `35aa8c240a1634d2d35765f9518b79b33260f53845b1e228625b820f65d942f3` |
| 35476688743 | debug | 10595100342 | nightzuku-manager-debug-92f5446 | 30148765 | `sha256:3945306f58ba053c5c096bfba72ac4c35fa11a28c5cdad76aa7edd308ca0df92` | 32201804 | `03184683a825d197f949756f216276d31671ddc6484bd818663e69bd0f296bb9` |
| 35476688743 | release | 10594856621 | nightzuku-manager-release-signed-92f5446 | 2879408 | `sha256:add71219348fd37c95872931f68cf0ae036d116586a2d8305ef7ab3da577b3fc` | 3736399 | `35aa8c240a1634d2d35765f9518b79b33260f53845b1e228625b820f65d942f3` |

Failed runs `35476258277` / `35476266199` uploaded **no** artifacts.

### 4.3 aapt / signing (release, CI `release-badging.txt`)

```
package: name='com.joselofarias.nightzuku' versionCode='47' versionName='13.6.0.r47.92f5446'
```

v2 signer CN=Nightzuku Fork, cert SHA-256 `a0aa7a8eecbc38a22f81ffe4e713914c1a810e11ab8e8f507b03b3d54df9b496`.  
`build-head-sha.txt` = `92f5446affc552969052c3a3807c451ddda635bd`.
