# Diagnostic Spanish Polish Patch

This patch completes the localization of the home screen diagnostics card (translating "Service", "running/stopped", "Server uid", "Server API", "ADB permission", "full/limited", and "Authorized apps" into Spanish). It also simplifies the Termux tutorial by removing the obsolete note about manual package replacement.

## 1. Root Cause
- **Diagnostics Card:** Several diagnostic headers and statuses were hardcoded in English inside the `buildDiagnostics` function of both `HomeActivity.kt` and `TVHomeScreen.kt` rather than using localized Android resource lookups.
- **Termux Tutorial:** An obsolete Termux description note was shown for Step 2 despite the fact that manual package replacement (`sed`/`PKG`) instructions had already been completely removed.

## 2. Files Changed
- `manager/src/main/res/values/strings.xml`
  - Defined English string resources for service status, server UID, server API version, ADB permission status, and authorized application counts.
- `manager/src/main/res/values-es/strings.xml`
  - Defined Spanish translations for these new diagnostics labels and values.
- `manager/src/main/java/moe/shizuku/manager/home/HomeActivity.kt`
  - Fully updated `buildDiagnostics` to look up localized strings for all diagnostics labels and values.
- `manager/src/main/java/moe/shizuku/manager/home/TVHomeScreen.kt`
  - Fully updated `buildDiagnostics` to match.
- `manager/src/main/java/moe/shizuku/manager/shell/ShellTutorialActivity.kt`
  - Removed `body` parameter from Step 2 of the Termux tutorial `StepRow` so that the obsolete package replacement text is no longer rendered.
- `manager/src/main/java/moe/shizuku/manager/shell/TvShellTutorialScreen.kt`
  - Removed the trailing content block displaying the description for Step 2.

## 3. Before/After Output

### Spanish Diagnostics Output
- **Before**:
  ```
  Service: running
  Server uid: 2000
  Server API: 13.6
  SELinux: u:r:shell:s0
  ADB permission: full
  Authorized apps: 67
  Red local: dispositivos Wi-Fi cercanos: concedido
  ```
- **After**:
  ```
  Servicio: en ejecución
  UID del servidor: 2000
  API del servidor: 13.6
  SELinux: u:r:shell:s0
  Permiso ADB: completo
  Aplicaciones autorizadas: 67
  Red local: dispositivos Wi-Fi cercanos: concedido
  ```

### Termux Step 2 Description
- **Before**:
  - Displays `"For Termux, no manual package replacement is needed."` / `"Para Termux, no es necesario reemplazar el paquete manualmente."`
- **After**:
  - Empty (only shows Step 2 title and command block with copy option).

## 4. Verification Steps
1. Compile the debug build using:
   ```powershell
   $env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
   .\gradlew :manager:assembleDebug
   ```
2. Verify code styling using:
   ```powershell
   git diff --check
   ```
3. Test Spanish diagnostics screen:
   - Verify all headers (Servicio, UID del servidor, API del servidor, Permiso ADB, Aplicaciones autorizadas, Red local) and status outcomes are fully in Spanish.
