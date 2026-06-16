# RISH_RECEIVER_PERMISSION_PATCH.md

## Overview

Exported `rish` now defaults to `com.termux` and `ShizukuShellLoader` targets `kerneldroid.nightzuku`. However, the broadcast connection from `rish` timed out. Inspection of the codebase showed that `ShizukuReceiver` (which handles the broadcast action `rikka.shizuku.intent.action.REQUEST_BINDER`) was guarded in the manifest by the permission `moe.shizuku.manager.permission.API_V23`.

Because Termux does not request or hold this permission in its manifest, the Android OS blocks delivery of the `REQUEST_BINDER` broadcast from the Termux process.

This patch removes the `android:permission` attribute from `ShizukuReceiver` inside `AndroidManifest.xml` to allow successful broadcast delivery.

---

## Proposed Changes

### [AndroidManifest.xml](file:///c:/Users/Dell/Nightzuku/manager/src/main/AndroidManifest.xml)

Removed `android:permission="moe.shizuku.manager.permission.API_V23"` from `.receiver.ShizukuReceiver`:

```diff
         <receiver
             android:name=".receiver.ShizukuReceiver"
             android:directBootAware="true"
             android:enabled="true"
             android:exported="true"
-            android:permission="moe.shizuku.manager.permission.API_V23"
             tools:ignore="ExportedReceiver">
             <intent-filter>
                 <action android:name="rikka.shizuku.intent.action.REQUEST_BINDER" />
             </intent-filter>
         </receiver>
```

---

## Security Risk Analysis

### Identified Risk
By removing the permission requirement from the broadcast receiver, any third-party application on the device is now capable of sending a `rikka.shizuku.intent.action.REQUEST_BINDER` broadcast and receiving the Shizuku server binder (`shizukuBinder`) in return.

### Mitigation & Evaluation
1. **Binder Transaction Level Checks**: Receiving the `shizukuBinder` does not grant automatic shell or root access. Every API call/transaction method on the binder (defined in `IShizukuService`) goes through a strict authorization check via `checkCallerPermission` and `checkCallerManagerPermission` in `ShizukuService`.
2. **Permission Validation**:
   - The service checks the client's calling UID (via `Binder.getCallingUid()`).
   - If the calling app's UID is not already authorized in the Shizuku Manager config database, calling any API method (such as `execute` or `transact`) will throw a `SecurityException`.
   - The app must first invoke `requestPermission()`, which triggers the user-facing permission request dialog in the Shizuku Manager. The user must explicitly tap "Allow" for the app to gain access.
3. **Alignment with ContentProvider Model**: This design aligns with `ShizukuManagerProvider`, which is also exported and handles connection requests dynamically, relying on the binder's internal security checks rather than static manifest permissions.

**Conclusion**: The security risk is **Very Low**. The primary security boundaries (UID verification and user authorization dialogs) remain fully intact at the binder transaction level.

---

## Verification Plan

### Automated Verification
- Run a local Gradle build to verify compilation:
  ```powershell
  .\gradlew.bat assembleDebug
  ```

### Manual Verification
1. Build and install the patched Nightzuku app on an Android device/emulator.
2. Open the Nightzuku application and start the Shizuku server.
3. Export the `rish` files (the patched `rish` asset has `com.termux` package as the default fallback).
4. Run `rish` inside Termux.
5. Verify that the broadcast is successfully delivered, prompting the Shizuku Manager authorization dialog if run for the first time, and successfully launching the interactive shell once approved.
