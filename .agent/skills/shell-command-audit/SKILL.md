# shell-command-audit

Use when touching shell execution, rish, sh, su or command wrappers.

Checklist:
- Command availability.
- Argument escaping.
- No unsafe string concatenation with user input.
- Timeout exists.
- stderr captured.
- stdout captured when useful.
- Exit code checked.
- Failure is visible to caller.
- No destructive command without explicit guard.
- BusyBox/toolbox/toybox differences considered.
- Android 13-16 compatibility.
- OEM restrictions considered.
- Logs useful but not sensitive.

Special cases:
- rish execution.
- su execution.
- sh execution.
- pm/appops/settings commands.
- Binder-related commands.
- Root emulation commands.

Output:
- Risk level.
- Failure modes.
- Minimal safe fix.
- Verification command or test path.
