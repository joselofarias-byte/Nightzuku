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

Do **not** repeat rish/Termux cases. Use the signed release APK (`com.joselofarias.nightzuku`).

Preconditions: Wireless debugging paired once; Keep running ON.

| Case | Action | Expected |
| --- | --- | --- |
| **A — arm TCP** | Enable Wireless debugging. Tap **Enable local TCP** (default `127.0.0.1:5555` unless a saved port exists). Tap **Test local TCP**. | TCP state becomes Enabled+reachable and **ADB authenticated · usable for restart**. Banner: Local TCP recovery available. |
| **B — Wireless debugging off, kill server** | After A succeeds, turn Wireless debugging off. Tap **Test recovery**. | If local TCP is still authenticated, NightDog recovers via persistent TCP. Card shows measured seconds + new PID. If TCP died with Wireless debugging, banner becomes activation required; no tight loop. |
| **C — Developer options off** | Disable Developer options fully. Kill the server or reboot. | Card must **not** claim recovery is available. Banner / reboot limit: Nightzuku cannot recreate uid=2000 with Developer options off. Recover now waits/retries 8–60s only. |
| **D — no Wi-Fi** | Disconnect Wi-Fi after TCP is armed. | Persistent `127.0.0.1` TCP can still recover. mDNS Wireless debugging must not be claimed usable. |
| **E — reboot** | Reboot with Keep running ON and TCP previously enabled. Open Nightzuku (no extra boot loops). | If local TCP is still alive: service can come back; reactivation flag clear. If not: **One-time Wireless debugging activation required**. Never an aggressive boot retry storm. |

Pass rule: every claim on the card matches a real probe (configured vs socket vs authenticated vs usable).
