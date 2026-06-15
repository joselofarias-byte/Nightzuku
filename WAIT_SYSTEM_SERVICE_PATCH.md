# WAIT_SYSTEM_SERVICE_PATCH.md

> Branch: `fix/wait-system-service-timeout`  
> Date: 2026-06-15  
> Author: agent  
> Scope: single method in one file

---

## 1. Problem Fixed

`ShizukuService.waitSystemService(String name)` polled `ServiceManager.getService(name)` in an unconditional `while` loop with a 1-second `Thread.sleep` between iterations and **no exit condition**. If a system service never appeared — due to ROM bugs, corrupt installs, unusual OEM init sequences, or crashed system processes — the server thread would spin forever, consuming a thread and holding the server process alive in a permanently broken state with no way to detect or recover from the hang.

The four calls in the constructor are sequential:

```java
waitSystemService("package");
waitSystemService(Context.ACTIVITY_SERVICE);   // "activity"
waitSystemService(Context.USER_SERVICE);        // "user"
waitSystemService(Context.APP_OPS_SERVICE);     // "appops"
```

A hang on any one of them prevented the server from ever becoming functional while also preventing the process from exiting, which in turn blocked any restart mechanism.

---

## 2. Exact File Changed

```
server/src/main/java/rikka/shizuku/server/ShizukuService.java
```

**Lines changed: 113–134** (after patch; 9 lines replaced with 22 lines net of comment and constant).

### Before

```java
private static void waitSystemService(String name) {
    while (ServiceManager.getService(name) == null) {
        try {
            LOGGER.i("service " + name + " is not started, wait 1s.");
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            LOGGER.w(e.getMessage(), e);
        }
    }
}
```

### After

```java
// ponytail: 60 s is generous for any legitimate slow-boot ROM.
// if a system service truly never starts the server must not hang forever.
private static final int WAIT_SERVICE_TIMEOUT_S = 60;

private static void waitSystemService(String name) {
    int waited = 0;
    while (ServiceManager.getService(name) == null) {
        if (waited >= WAIT_SERVICE_TIMEOUT_S) {
            LOGGER.e("service " + name + " did not start within "
                    + WAIT_SERVICE_TIMEOUT_S + "s, exiting.");
            System.exit(51); // 51 = system service wait timeout
        }
        try {
            LOGGER.i("service " + name + " is not started, wait 1s. ("
                    + waited + "/" + WAIT_SERVICE_TIMEOUT_S + ")");
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            LOGGER.w(e.getMessage(), e);
        }
        waited++;
    }
}
```

### What changed

| Element | Before | After |
|---|---|---|
| Timeout constant | None | `WAIT_SERVICE_TIMEOUT_S = 60` (static final, file-local) |
| Wait counter | None | `waited` (int, method-local) |
| Log on each wait | Fixed message | Message + `(waited/60)` progress counter |
| Timeout exit | Never | `LOGGER.e(...)` then `System.exit(51)` |
| Exit code | N/A | `51` — distinct from `50` (manager not found) |
| New imports | None | None |
| New files | None | None |
| Files touched | 0 | 1 |

---

## 3. Risk Assessment

### What cannot break

- **Normal startup path**: Services always start within 60 seconds on any healthy device. `waited` never reaches 60, the `if` block never executes, behavior is identical to before.
- **Call sites**: The four `waitSystemService(...)` calls in the constructor are unchanged.
- **All post-startup logic**: `getManagerApplicationInfo()`, `getConfigManager()`, `BinderSender.register()`, binder delivery — none touched.
- **No new imports** were added; no existing method signatures changed.

### What changes on an unhealthy device

A device where a system service genuinely never starts now exits with code 51 after 60 seconds instead of hanging forever. This is strictly better: the process exits cleanly, a restart mechanism can trigger, and logcat shows the exact service name and timeout.

### Timeout value justification

- **60 seconds per service** is conservative. Normal Android boot completes all four services in under 30 seconds even on slow or heavily customized ROMs.
- The four services are waited sequentially; worst-case total wait before exit is 4 × 60 = 240 seconds. This is acceptable as a maximum, not an expected case.
- 60 seconds leaves ample margin for OEM splash screens, slow eMMC init, and heavy init.rc chains without triggering false timeouts on legitimate slow-boot hardware.

### Exit code 51

- `50` (`MANAGER_APP_NOT_FOUND`) is already defined in `ServerConstants.java`.
- `51` is defined inline in the patched method as a comment. It does not conflict with `50`.
- Using a distinct exit code allows `adb shell echo $?` to distinguish "timeout" from "manager not found" in crash investigations.
- The exit code is documented at the call site: `System.exit(51); // 51 = system service wait timeout`.

### Risk matrix

| Scenario | Risk | Severity | Notes |
|---|---|---|---|
| Normal boot, all services start in time | None | — | `waited` never reaches 60 |
| Slow ROM, services start after 30s | None | — | Well within 60s |
| System service never starts | Exits after 60s | Low | Was: infinite hang. Now: clean exit |
| InterruptedException during wait | Unchanged | — | Same `LOGGER.w` + continue |
| `waited` counter overflow | Impossible | — | `int`, max 60 iterations before exit |

**Overall risk: Very Low.** The fix only activates under a condition (`waited >= 60`) that was previously a hang.

---

## 4. Verification Commands

### Build verification

```bash
./gradlew :server:compileDebugJavaSources
```

Expected: `BUILD SUCCESSFUL`. No compile errors in `ShizukuService.java`.

```bash
./gradlew :manager:assembleDebug
```

Expected: `BUILD SUCCESSFUL`. Full APK with the patched server included.

### Logcat verification — normal path (no timeout triggered)

Install and start Nightzuku normally (ADB or root). Filter logcat:

```bash
adb logcat -s ShizukuServer
```

Expected output on slow start (example for "package" service):

```
I ShizukuServer: service package is not started, wait 1s. (0/60)
I ShizukuServer: service package is not started, wait 1s. (1/60)
I ShizukuServer: starting server...
```

Confirm: progress counter increments, server proceeds normally.

### Logcat verification — timeout path (simulated)

To confirm the timeout path compiles and exits cleanly, temporarily lower `WAIT_SERVICE_TIMEOUT_S` to `2` in a test build, install, and filter:

```bash
adb logcat -s ShizukuServer
```

Expected:

```
I ShizukuServer: service package is not started, wait 1s. (0/2)
I ShizukuServer: service package is not started, wait 1s. (1/2)
E ShizukuServer: service package did not start within 2s, exiting.
```

Then restore `WAIT_SERVICE_TIMEOUT_S = 60` before committing.

### Exit code verification

```bash
adb shell run-as root sh -c 'echo $?'
```

If the server exits via timeout, exit code will be `51`.

---

## 5. Why the Fix Is Minimal

AGENTS.md says: *"The preferred change is the smallest safe change that improves compatibility."*

This patch satisfies every Ponytail constraint:

- **One file, one method.** `ShizukuService.java` only. No other files touched.
- **No new imports.** `ServiceManager`, `Thread.sleep`, `LOGGER`, and `System.exit` were already present.
- **No new dependencies.** Standard Java `int` counter. No Android API added.
- **No refactoring.** The method signature, call sites, surrounding logic, and the rest of the constructor are identical.
- **Preserves the happy path exactly.** On any healthy device the `if (waited >= 60)` block is dead code.
- **The constant is file-local.** No need to touch `ServerConstants.java`; the value only matters to this one method.
- **The comment is minimal and functional.** Explains the tradeoff (why 60s, what to do if it triggers) per the ponytail documentation pattern.
- **Smaller alternatives were considered and rejected:**
  - A single-line `if` without logging: rejected — silent exits are undiagnosable (AGENTS.md: "Log enough for diagnostics").
  - Adding a `throws` declaration: rejected — changes the method contract and all four call sites.
  - Moving the constant to `ServerConstants.java`: rejected — unnecessary cross-file coupling for a value only used here.
