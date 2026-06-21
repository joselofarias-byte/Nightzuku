# Status Action Button Patch

This patch resolves the home screen status card action button bug where "Stop" was unconditionally displayed even when the service was stopped. It ensures the action button dynamically displays "Stop" (when running) or "Start" (when stopped and root/wireless ADB is available).

## 1. Root Cause
The status card actions (on both Phone/TV) and the TopAppBar DropdownMenu (on Phone) did not properly adapt to the service running state `ServiceStatus.isRunning`. "Stop" was either unconditionally shown or not properly hidden, and the "Start" actions (root or wireless ADB) were not offered as context-appropriate action buttons within the status card itself when the service was stopped.

## 2. Files Changed
- `manager/src/main/java/moe/shizuku/manager/home/HomeActivity.kt`
  - Updated `StatusCard` to show "Stop" only if `ServiceStatus.isRunning` is true, and "Start" (utilizing root or wireless ADB) if the service is not running and the method is available.
  - Updated the top bar `DropdownMenu` to only show the "Stop" option when the service is running, and show "Start" (root/wireless ADB) when stopped and available.
- `manager/src/main/java/moe/shizuku/manager/home/TVHomeScreen.kt`
  - Updated `TvStatusCard` to display the "Stop" action button only when `ServiceStatus.isRunning` is true, and the "Start" button (root or wireless ADB) when the service is stopped and available.
  - Aligned the left navigation column's start/stop actions to match the running status of the service.

## 3. Before/After Behavior
- **Before**:
  - The status card/actions offered "Stop" when the service was already stopped.
  - The phone TopAppBar DropdownMenu always showed "Stop" even when the service was stopped.
- **After**:
  - When the service is running, the action button/dropdown menu item shows "Stop" (Spanish: "Detener").
  - When the service is stopped, the action button/dropdown menu item shows "Start" (Spanish: "Iniciar") using the appropriate method (Root/Wireless ADB) if available.
  - If the service is stopped and no startup method is available, no start action is shown.
  - All loading/error states and background colors are fully preserved.

## 4. Verification Steps
1. Build the debug APK using:
   ```powershell
   $env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
   .\gradlew :manager:assembleDebug
   ```
2. Launch the application.
3. **Test Stopped State**:
   - Ensure the home status card and phone top bar dropdown menu show "Start" (Iniciar) if root/wireless ADB is available.
   - Verify that "Stop" is NOT shown.
4. **Test Running State**:
   - Start the service.
   - Verify that the home status card and phone top bar dropdown menu switch to showing "Stop" (Detener).
