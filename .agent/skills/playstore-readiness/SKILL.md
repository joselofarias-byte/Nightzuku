# playstore-readiness

Use before Play Store or public distribution.

Checklist:
- Permissions are justified.
- Foreground Service declarations are correct.
- Privacy-sensitive behavior documented.
- Storage behavior is compliant.
- No debug-only code.
- No accidental logging of sensitive data.
- Release signing path ready.
- R8/ProGuard checked.
- App labels and localization checked.
- Crash-prone flows manually tested.

Output:
- Store blockers.
- Technical blockers.
- Recommended release notes.
