# RISH Tutorial & Settings UI Polish Patch

This patch addresses obsolete instructions in the Termux rish tutorial, fixes language display summary behavior, and improves switch styling consistency.

## Reason for Changes

1. **Obsolete sed command in Termux Tutorial:** Modern exported `rish` files default to using `com.termux` as their application ID. Requiring a manual `sed` package replacement was confusing, error-prone, and obsolete.
2. **Obsolete sed note:** Simplifies the steps by removing sed instructions for Termux and clarifying that no manual package replacement is needed for Termux, while keeping the instruction only as an advanced note for other terminal apps.
3. **Settings language summary bug:** The settings language row always displayed "Follow system" even after selecting another language because it read from `?.summary` of the selected language option, which resolves to `null` when selected. Switching it to read `?.title` displays the correct selected language.
4. **Disabled Switch Color Inconsistency:** When switches were disabled (e.g. in settings or permissions), they reverted to the default Material 3 color theme instead of matching the customized green/pink brand colors. Adding explicit disabled colors (using 38% alpha) fixes this.

---

## Files Modified

- **`manager/src/main/res/values/strings.xml`**
  - Updated Termux command block and added tutorial descriptions.
- **`manager/src/main/res/values-es/strings.xml`**
  - Updated Spanish equivalents of Termux command blocks and tutorial descriptions.
- **`manager/src/main/java/moe/shizuku/manager/shell/ShellTutorialActivity.kt`**
  - Injected description strings (`terminal_tutorial_2_description` and `terminal_tutorial_3_description`) into the Compose `StepRow` calls.
- **`manager/src/main/java/moe/shizuku/manager/shell/TvShellTutorialScreen.kt`**
  - Appended step descriptions under the TV cards using the `content` lambda parameter.
- **`manager/src/main/java/moe/shizuku/manager/settings/SettingsActivity.kt`**
  - Changed `languageSummary` assignment to read `?.title` instead of `?.summary`.
- **`manager/src/main/java/moe/shizuku/manager/ui/compose/ShizukuExpressive.kt`**
  - Added disabled checked/unchecked states colors in `ExpressiveSwitch`.
- **`manager/src/main/java/moe/shizuku/manager/management/ApplicationManagementActivity.kt`**
  - Added disabled checked/unchecked states colors in `AppPermissionSwitch`.

---

## Verification Steps

Run the following commands:
```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
.\gradlew :manager:assembleDebug *> ".\rish-tutorial-settings-polish-build.txt"
git diff --check *> ".\rish-tutorial-settings-polish-diff-check.txt"
git status --short *> ".\rish-tutorial-settings-polish-status.txt"
```
Check that the build finishes with `BUILD SUCCESSFUL`, that no trailing whitespace exists, and that git status reports only the modified files listed above.
