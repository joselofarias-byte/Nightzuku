# CLAUDE.md

Follow `AGENTS.md` as the source of truth.

Default behavior for Claude Code:
- Start in Ponytail mode.
- Prefer the smallest safe patch.
- Do not create new abstractions unless necessary.
- Identify root cause before editing.
- Verify with build/tests/logs when possible.
- Run an improve-style audit before finishing.
- For Stellar/Nightzuku, also check shell behavior, SELinux, root emulation and UID assumptions.

Never compromise recording reliability, data integrity, permissions, SAF/MediaStore, Shizuku binder lifecycle, root fallback, root emulation compatibility, shell command safety or Android SDK compatibility.
