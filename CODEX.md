# CODEX.md

Follow `AGENTS.md` as the source of truth.

Codex default workflow:
1. Inspect before editing.
2. Prefer the smallest safe diff.
3. Do not rewrite architecture unless explicitly requested.
4. Preserve existing behavior unless the bug requires changing it.
5. Validate with build/tests where possible.
6. Explain the patch and risk profile.

Critical areas:
- Settings launch flow
- Android permissions
- SAF/MediaStore
- Foreground services
- Call recording pipeline
- Shizuku service/binder lifecycle
- Root fallback behavior
- Root emulation behavior
- Shell command execution
- SELinux and UID assumptions
