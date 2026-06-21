# RISH Tutorial Copy/Run Patch

This patch polishes the Termux rish tutorial setup and run commands, adds a copy button for the final execution command, and moves non-Termux notes to description labels.

## Files Modified
- `manager/src/main/res/values/strings.xml`
  - Refined English terminal tutorial command and description strings.
- `manager/src/main/res/values-es/strings.xml`
  - Refined Spanish terminal tutorial command and description strings.
- `manager/src/main/java/moe/shizuku/manager/shell/ShellTutorialActivity.kt`
  - Wrapped Step 3's MonospaceLog in a Column and added a copy button, similar to Step 2.

## Verification Steps
1. Navigate to the Terminal apps tutorial screen.
2. Verify Step 2 setup commands no longer include `~/.nightzuku/rish` at the end.
3. Verify Step 2 has a copy action.
4. Verify Step 3 title says "Run in Termux:" / "Ejecuta en Termux:".
5. Verify Step 3 command block displays `cd ~/.nightzuku` and `./rish`.
6. Verify Step 3 has a copy action button.
7. Verify Step 3 description notes: "For another terminal app, set RISH_APPLICATION_ID manually before running rish."
