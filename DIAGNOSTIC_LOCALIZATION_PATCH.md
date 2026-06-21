# Diagnostic Localization Patch

This patch resolves the lack of localization in the Home screen diagnostics card, specifically localizing the "Local network" label, the "NEARBY_WIFI_DEVICES" permission label, and the permission status outcomes (granted, missing, denied, not required). It also updates the main `.gitignore` file to ignore local build, status, search, and diff log text files.

## 1. Root Cause
The `buildDiagnostics` function (implemented independently in both `HomeActivity.kt` and `TVHomeScreen.kt`) constructed the local network diagnostic string using hardcoded English labels (`"Local network"`, `"granted"`, `"missing"`, `"not required"`) and the raw permission identifier (`"NEARBY_WIFI_DEVICES"`) instead of resolving them through localized string resources.

## 2. Files Changed
- `manager/src/main/res/values/strings.xml`
  - Defined English string resources: `diagnostic_local_network`, `diagnostic_permission_granted`, `diagnostic_permission_missing`, `diagnostic_permission_denied`, `diagnostic_permission_not_required`.
- `manager/src/main/res/values-es/strings.xml`
  - Defined Spanish translations for the new diagnostic strings.
- `manager/src/main/java/moe/shizuku/manager/home/HomeActivity.kt`
  - Updated `buildDiagnostics` to look up localized strings for the local network title, permission labels, and status states.
- `manager/src/main/java/moe/shizuku/manager/home/TVHomeScreen.kt`
  - Updated `buildDiagnostics` to match the localized lookups.
- `.gitignore`
  - Added patterns to ignore temporary log files.

## 3. Before/After Diagnostic Output
### English
- **Before**:
  ```
  Local network: NEARBY_WIFI_DEVICES: granted
  ```
- **After**:
  ```
  Local network: Nearby Wi-Fi devices: granted
  ```

### Spanish (Español)
- **Before**:
  ```
  Local network: NEARBY_WIFI_DEVICES: granted
  ```
- **After**:
  ```
  Red local: dispositivos Wi-Fi cercanos: concedido
  ```

## 4. Verification Steps
1. Build the debug APK using:
   ```powershell
   $env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
   .\gradlew :manager:assembleDebug
   ```
2. Verify formatting and diff checks using:
   ```powershell
   git diff --check
   ```
3. Run the app, go to Diagnostics, and check that:
   - When in Spanish, the local network diagnostics display:
     `Red local: dispositivos Wi-Fi cercanos: concedido` (or state-appropriate localized labels).
