# RISH_TERMUX_EXPORT_PATCH.md

This patch simplifies the rish export flow for Nightzuku users on Termux by making `com.termux` the default application ID instead of the placeholder `PKG`.

## 1. File Changed

- **[manager/src/main/assets/rish](file:///c:/Users/Dell/Nightzuku/manager/src/main/assets/rish)**
  - Replaced:
    ```sh
    # Replace "PKG" with the application id of your terminal app
    [ -z "$RISH_APPLICATION_ID" ] && export RISH_APPLICATION_ID="PKG"
    ```
  - With:
    ```sh
    # Replace "com.termux" with the application id of your terminal app
    [ -z "$RISH_APPLICATION_ID" ] && export RISH_APPLICATION_ID="com.termux"
    ```

## 2. Why the Change Is Safe

- **Asset-only modification**: The change is confined to the shell script asset `rish`. No executable dex or binary formats (e.g., `rish_shizuku.dex`) are touched.
- **Environment Variable Fallback**: The logic `[ -z "$RISH_APPLICATION_ID" ]` ensures that if a user sets the `RISH_APPLICATION_ID` environment variable manually (e.g., in their shell configuration), that value takes precedence.
- **Valid Package String**: `com.termux` is a syntactically valid package name. If the script is run directly, `ShizukuShellLoader` receives `com.termux` instead of the placeholder `"PKG"`. This bypasses the placeholder block check in `ShizukuShellLoader.java`:
  ```java
  if (TextUtils.isEmpty(packageName) || "PKG".equals(packageName)) {
      abort("RISH_APPLICATION_ID is not set, set this environment variable to the id of current application (package name)");
  }
  ```
  Since `com.termux` is not equal to `"PKG"`, it will successfully attempt to request the binder context, allowing Termux to run the script out-of-the-box.

## 3. How to Verify Export

1. **Build and install** the Nightzuku Manager app on an Android device or emulator.
2. **Open the Nightzuku application** and navigate to the **Terminal** tutorial/help section.
3. Click the **"Export files"** button to export `rish` and `rish_shizuku.dex` to a local directory (e.g., a shared folder or Termux's home directory).
4. **Open the exported `rish` script** using any text editor or viewer (e.g., `cat rish` in a terminal/adb shell).
5. **Verify that the line contains**:
   `[ -z "$RISH_APPLICATION_ID" ] && export RISH_APPLICATION_ID="com.termux"`
   and that the comment reads:
   `# Replace "com.termux" with the application id of your terminal app`
6. Run `sh rish` from within Termux and ensure it resolves permissions and connects to the Nightzuku service without manual text modifications.

## 4. Regression Risk

- **Zero Regression Risk**:
  - The script's behaviour for other terminal applications remains fully customizable. If a user wishes to target a different terminal package (e.g., `com.termux.dev` or another shell app), they can either edit the `rish` script to replace `com.termux` or export `RISH_APPLICATION_ID="your.package"` in their shell environment.
  - The fallback mechanism is unaffected.
