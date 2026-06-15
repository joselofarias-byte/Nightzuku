# NIGHTZUKU_RISH_VERIFICATION.md

> Verification date: 2026-06-15  
> Question: Is `rish` really targeting `moe.shizuku.privileged.api`? Is it a bug or intentional?  
> Method: direct source traversal, no code modification.

---

## 1. Verified: The Hardcoded Package Name Is Real

### Primary evidence — `ShizukuShellLoader.java`, line 57

```
File: shell/src/main/java/rikka/shizuku/shell/ShizukuShellLoader.java
```

```java
// Lines 52–59 — requestForBinder()
private static void requestForBinder() throws RemoteException {
    Bundle data = new Bundle();
    data.putBinder("binder", receiverBinder);

    Intent intent = new Intent("rikka.shizuku.intent.action.REQUEST_BINDER")
            .setPackage("moe.shizuku.privileged.api")          // ← LINE 57
            .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            .putExtra("data", data);
```

**The string `"moe.shizuku.privileged.api"` is hardcoded in the intent's `setPackage()` call.** This is the target the broadcast will be restricted to. Android will only deliver this intent to components registered inside the package named `moe.shizuku.privileged.api`.

---

## 2. What Nightzuku's Package Name Actually Is

### Evidence — `manager/build.gradle`, line 13

```groovy
// manager/build.gradle, line 13
defaultConfig {
    applicationId "kerneldroid.nightzuku"    // ← Nightzuku's actual package
```

### Evidence — `server/src/main/java/rikka/shizuku/server/ServerConstants.java`, line 8

```java
public static final String MANAGER_APPLICATION_ID = "kerneldroid.nightzuku";
```

### Evidence — `starter/src/main/java/moe/shizuku/starter/ServiceStarter.java`, line 97

```java
String packageName = "kerneldroid.nightzuku";    // ← binder delivery uses correct name
```

**Conclusion:** Nightzuku's runtime package identity is `kerneldroid.nightzuku`. The server (`ServerConstants`), the starter (`ServiceStarter`), and the build config all use this. The shell loader is the sole component that did not get updated.

---

## 3. The Complete Delivery Chain

### What `rish` sends

```
Intent action  : "rikka.shizuku.intent.action.REQUEST_BINDER"
setPackage()   : "moe.shizuku.privileged.api"     ← does not exist in a Nightzuku-only install
Flags          : FLAG_INCLUDE_STOPPED_PACKAGES
Extras         : Bundle containing receiverBinder (IBinder)
```

Because `setPackage()` is set, Android's `ActivityManagerService` restricts delivery strictly to components registered **inside that package**. No cross-package delivery occurs.

### What Nightzuku's manager expects to receive

From `manager/src/main/AndroidManifest.xml`, lines 175–185:

```xml
<receiver
    android:name=".receiver.ShizukuReceiver"
    android:directBootAware="true"
    android:enabled="true"
    android:exported="true"
    android:permission="moe.shizuku.manager.permission.API_V23"
    tools:ignore="ExportedReceiver">
    <intent-filter>
        <action android:name="rikka.shizuku.intent.action.REQUEST_BINDER" />
    </intent-filter>
</receiver>
```

`ShizukuReceiver` is registered inside package `kerneldroid.nightzuku` (the actual installed package). It has the right action, it is exported, and it is direct-boot-aware. **But the broadcast from `ShizukuShellLoader` will never reach it** because `setPackage("moe.shizuku.privileged.api")` prevents delivery to `kerneldroid.nightzuku`.

### The fallback path on Android 8.0/8.1 is also wrong

```java
// ShizukuShellLoader.java, lines 86–93
Intent activityIntent = Intent.createChooser(
        new Intent("rikka.shizuku.intent.action.REQUEST_BINDER")
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT)
                .putExtra("data", data),    // ← no setPackage here
        "Request binder from Shizuku"
);
```

This fallback does NOT set `setPackage()`, so it resolves via the intent filter to any installed app that declares the action. The manager registers `ShellRequestHandlerActivity` for it (manifest line 138). **The fallback path would work on Android 8.0/8.1**, but only via the chooser dialog — not silently as intended. For all other Android versions the main broadcast path is used, and it fails.

---

## 4. Is This a Bug or Intentional Compatibility Behavior?

### Evidence for intentional compatibility

There is no evidence that this is intentional. Checking the repository for deliberate dual-package support:

- `README.md`, line 8: *"you MUST UNINSTALL any older official Shizuku Manager app before installing Nightzuku"* — the README **explicitly rules out** having both packages coexist.
- `README.md`, line 121: *"You may not use `moe.shizuku.privileged.api` as an application ID in derived works"* — the project explicitly rejects that identity.

If the intent were to maintain backward compatibility with the upstream Shizuku package (i.e., allow `rish` to work if either is installed), the README would not prohibit coexistence. There is no code comment, no conditional, and no configuration switch around line 57.

### Evidence confirming it is an omission/bug

All other package-identity-sensitive strings were updated to `kerneldroid.nightzuku`:

| Location | Old string | Updated? |
|---|---|---|
| `ServerConstants.MANAGER_APPLICATION_ID` | `moe.shizuku.privileged.api` | ✅ `kerneldroid.nightzuku` |
| `ServiceStarter.sendBinder()` packageName | `moe.shizuku.privileged.api` | ✅ `kerneldroid.nightzuku` |
| `build.gradle` applicationId | `moe.shizuku.privileged.api` | ✅ `kerneldroid.nightzuku` |
| `AndroidManifest.xml` authorities `${applicationId}.shizuku` | `moe.shizuku.privileged.api.shizuku` | ✅ `kerneldroid.nightzuku.shizuku` |
| `ShizukuShellLoader.java` line 57 `setPackage()` | `moe.shizuku.privileged.api` | ❌ **NOT updated** |

**Verdict: This is a refactoring omission.** Every other identity-sensitive string was updated. `ShizukuShellLoader.java` line 57 was missed.

---

## 5. Runtime Impact

### Scenario A: Only Nightzuku installed (standard case)

```
rish broadcasts → setPackage("moe.shizuku.privileged.api")
Android AMS resolves package → package not found
Broadcast is dropped silently
receiverBinder never gets a transaction
5-second timeout fires
rish prints: "Request timeout. The connection between the current app (...) and
             Shizuku app may be blocked by your system."
rish exits with code 1
```

**Shell execution is completely broken.**

### Scenario B: Upstream Shizuku (`moe.shizuku.privileged.api`) also installed

```
rish broadcasts → setPackage("moe.shizuku.privileged.api")
Upstream Shizuku's receiver gets the broadcast
Upstream Shizuku returns its own binder (not Nightzuku's binder)
Shell.main() receives the upstream Shizuku binder
RishConfig initialized with the upstream binder
rish runs against the upstream Shizuku server, not Nightzuku
```

In this scenario `rish` appears to work, but it is using upstream Shizuku's server — not Nightzuku's. This explains why the bug may not have been noticed: the README warns users to uninstall upstream Shizuku, but if they didn't, `rish` silently worked anyway via the wrong server.

### Scenario C: Android 8.0/8.1 only

The `broadcastIntent` call fails, and the code falls back to `startActivityAsUser` with a chooser intent (no `setPackage` set). This fires an Activity chooser, which would match `ShellRequestHandlerActivity` in Nightzuku's manifest. **On Android 8.0/8.1 only, `rish` would accidentally work** via the activity chooser, albeit showing a dialog instead of working silently.

---

## 6. Exact Patch

The fix is a single-string substitution in one method. **Do not apply — evidence only per instructions.**

```diff
--- a/shell/src/main/java/rikka/shizuku/shell/ShizukuShellLoader.java
+++ b/shell/src/main/java/rikka/shizuku/shell/ShizukuShellLoader.java
@@ -54,7 +54,7 @@ public class ShizukuShellLoader {

         Intent intent = new Intent("rikka.shizuku.intent.action.REQUEST_BINDER")
-                .setPackage("moe.shizuku.privileged.api")
+                .setPackage("kerneldroid.nightzuku")
                 .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                 .putExtra("data", data);
```

**Location:** [`ShizukuShellLoader.java`](file:///c:/Users/Dell/Nightzuku/shell/src/main/java/rikka/shizuku/shell/ShizukuShellLoader.java#L56-L59), line 57.  
**Change:** Replace `"moe.shizuku.privileged.api"` → `"kerneldroid.nightzuku"`.  
**Only one line changes.**

---

## 7. Regression Risk Assessment

### Scope of change

The patch touches a single string literal in a single method in the `:shell` module. It does not touch:
- Server startup logic.
- Binder delivery.
- ContentProvider.
- Permission management.
- Any other module.

### Risk factors

| Factor | Assessment |
|---|---|
| Change size | Minimal — 1 string, 1 line |
| Blast radius | Limited to the `rish` shell path only |
| Side effects on Nightzuku-only install | None — the old string matched nothing, the new string matches `ShizukuReceiver` |
| Side effects if upstream Shizuku is installed | `rish` will now route to Nightzuku's receiver instead of upstream Shizuku's. This is the correct behavior and was the expected state per the README |
| Android version coverage | `ShizukuReceiver` is registered `exported=true`, `directBootAware=true` — no version exclusion |
| `ShizukuReceiver` permission guard | `android:permission="moe.shizuku.manager.permission.API_V23"` — the broadcast sender must hold this permission. Shell UID 2000 (ADB) and UID 0 (root) are exempt from permission checks for broadcasts, so this guard will not block delivery |
| `FLAG_INCLUDE_STOPPED_PACKAGES` | Already present — ensures delivery even if the manager is force-stopped |
| Android 8.0/8.1 fallback path | Not changed — the fallback intent has no `setPackage()` and is unaffected |

### Risk matrix

| Risk | Likelihood | Severity | Notes |
|---|---|---|---|
| `rish` breaks on Nightzuku-only install after patch | Very Low | High | Only if `ShizukuReceiver` has a registration bug, which is verified correct |
| `rish` breaks when upstream Shizuku is present | None | — | Upstream Shizuku no longer intercepts; intended behavior |
| Manager crash on receiving broadcast | Very Low | Medium | `ShellBinderRequestHandler` handles null binder gracefully (`Shizuku.getBinder() == null` logs warning, transacts null) |
| Permission rejection of broadcast from shell | None | — | Shell UID exempt from `android:permission` guards on broadcast delivery |

**Overall regression risk: LOW.**

The fix is a refactoring correction with no logic change. The surrounding broadcast infrastructure — intent action, flags, extras, the receiver declaration, the handler — is already correct. Only the target package string is wrong.

---

## 8. Secondary Occurrences — Do They Need Patching?

The grep for `moe.shizuku.privileged.api` also returned:

| File | Line | String | Needs patch? |
|---|---|---|---|
| `ShizukuManagerProvider.kt:18` | `EXTRA_BINDER = "moe.shizuku.privileged.api.intent.extra.BINDER"` | ❌ No — this is a **bundle key string** (arbitrary label), not a package reference. Both sides must match: `ServiceStarter.java:24` uses the same key for user service binder delivery, and `ShizukuService.java:587` uses it for binder pushes. All three must stay identical |
| `ServiceStarter.java:24` | `EXTRA_BINDER = "moe.shizuku.privileged.api.intent.extra.BINDER"` | ❌ No — bundle key, see above |
| `ShizukuService.java:587` | `extra.putParcelable("moe.shizuku.privileged.api.intent.extra.BINDER", ...)` | ❌ No — bundle key, see above |

**Only `ShizukuShellLoader.java:57` requires a package identity fix.** The other occurrences of `moe.shizuku.privileged.api` are bundle key strings, not package routing targets. Changing them would break the binder delivery protocol between the server and all clients.

---

## 9. Summary

| Question | Answer |
|---|---|
| Is `rish` using `moe.shizuku.privileged.api`? | **Yes.** Confirmed at `ShizukuShellLoader.java:57` |
| Is it a bug or intentional? | **Bug.** Refactoring omission — every other package identity string was updated |
| Exact file and line | `shell/src/main/java/rikka/shizuku/shell/ShizukuShellLoader.java`, **line 57** |
| Correct patch | Replace `"moe.shizuku.privileged.api"` with `"kerneldroid.nightzuku"` in `setPackage()` — one line |
| Regression risk | **Low.** Single string change, no logic impact, verified receiver registration is correct |
| Impact if not fixed | `rish` shell is completely broken on all standalone Nightzuku installs on Android 9+ |
