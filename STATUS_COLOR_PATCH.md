# STATUS_COLOR_PATCH.md

## 1. Files Changed

The following UI home screen files were modified to introduce dynamic connection/service state colors:
- **[HomeActivity.kt](file:///c:/Users/Dell/Nightzuku/manager/src/main/java/moe/shizuku/manager/home/HomeActivity.kt)** (Phone Home Screen):
  - Updated `HomeCard` to accept optional `iconContainerColor` and `iconContentColor` properties.
  - Updated `StatusCard` to map `serviceResource` loading, error, success, and disconnected states to custom color pairs.
- **[TVHomeScreen.kt](file:///c:/Users/Dell/Nightzuku/manager/src/main/java/moe/shizuku/manager/home/TVHomeScreen.kt)** (TV Home Screen):
  - Updated `TvHomeCard` to accept optional `iconContainerColor` and `iconContentColor` properties.
  - Updated `TvStatusCard` to map status/resource states to custom color pairs and pass them to `TvHomeCard`.
- **[WearHomeScreen.kt](file:///c:/Users/Dell/Nightzuku/manager/src/main/java/moe/shizuku/manager/home/WearHomeScreen.kt)** (Wear OS Home Screen):
  - Updated the status `WearCard` using `WearCardDefaults.cardColors(...)` to map states to custom background and text color pairs.

---

## 2. State-to-Color Mapping

The service and connection states are mapped to the following color palettes for consistency across light and dark themes:

| Service / Connection State | Color Theme | Dark Theme Colors | Light Theme Colors |
| :--- | :--- | :--- | :--- |
| **Connected / Running / Success** | Green | Container: `0xFF0F3816`<br>Content: `0xFF8CE090` | Container: `0xFFC7F3C9`<br>Content: `0xFF0F521A` |
| **Starting / Waiting / Pending** | Amber/Yellow | Container: `0xFF4D3800`<br>Content: `0xFFFFD54F` | Container: `0xFFFFF0C2`<br>Content: `0xFF6B4B00` |
| **Disconnected / Stopped / Unavailable** | Neutral Gray | Container: `0xFF333333`<br>Content: `0xFFB0B0B0` | Container: `0xFFE0E0E0`<br>Content: `0xFF555555` |
| **Error / Failed / Denied** | Red | Container: `0xFF5A1D1D`<br>Content: `0xFFFFB4AB` | Container: `0xFFFFDAD6`<br>Content: `0xFF410002` |

*Note: For the phone and TV home screens, Material 3 error container/on-error-container color tokens are used for the error state to perfectly match system branding.*

---

## 3. Why the Change is UI-only

- **No Logic Modification**: The patch does not alter `HomeViewModel` loading logic, `Shizuku` API wrapper checks, background startup services, or permission managers.
- **Purely Presentation Layer**: The changes are fully contained within Compose `@Composable` functions (presentation layer). They read the already computed `serviceResource` and `ServiceStatus` objects and only conditionally adjust local color variables used to style the UI elements.
- **Preserved Accessibility**: The text descriptions and status labels (e.g. "Shizuku is running", "Shizuku is not running") are completely untouched. Color is used strictly to enhance visual clarity, not as the sole indicator of state.

---

## 4. Verification Steps

### Automated Compilation check
Confirm that the project builds successfully for all targets (Phone, TV, and Wear):
```powershell
powershell -Command '$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"; .\gradlew.bat assembleDebug'
```

### Manual Verification
1. Install the built manager application on Phone, TV, or Wear devices/emulators.
2. Verify visual status indicator colors under different states:
   - **Loading/Waiting**: While connecting to the server, ensure the status card shows an Amber/Yellow background/tint.
   - **Service Stopped**: When the server is not started, ensure the status card displays a Neutral Gray.
   - **Service Running**: Once started, ensure the status card transitions to a Green color scheme.
   - **Error**: Simulating a binder call failure or status loading failure should highlight the status card in Red.

---

## 5. Regression Risk

- **Very Low / Zero**:
  - The signature updates for common card components (`HomeCard` and `TvHomeCard`) use default arguments. This guarantees that existing uses of these components in other parts of the application (like application management, settings, tutorial layouts) remain completely unaffected.
  - Standard, safe Color constants are used to guarantee styling works across all Android versions.
