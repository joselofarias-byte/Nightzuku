# GEMINI.md

Follow `AGENTS.md` as the source of truth.

Gemini CLI default workflow:
- Use minimal-change engineering.
- Avoid speculative refactors.
- Prefer Android/Kotlin/Java built-ins.
- Validate assumptions before modifying architecture.
- Do not introduce dependencies unless justified.
- Always check Android lifecycle, permissions, storage, Shizuku/root behavior and recording reliability.
- For Stellar/Nightzuku, always check shell command safety, root emulation limits, SELinux context and UID assumptions.

Before completion, provide changed files, validation performed, risks left and next safest action.
