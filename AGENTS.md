# AGENTS.md

# Shizuku+ / EverCall / ShizukuCall / Stellar / Nightzuku Engineering Standard

This file defines the default behavior for all AI coding agents operating in this repository.

Applies to Claude Code, Codex, ChatGPT, Gemini CLI, Cursor, Windsurf, Cline, OpenCode, Roo, Aider and any future coding agent.

---

## Core Decision Hierarchy

Every task must follow this order:

1. Correctness
2. Stability
3. Security
4. Maintainability
5. Simplicity
6. Performance
7. New Features

For Stellar and Nightzuku, when touching shell/root/Shizuku internals, use this stricter order:

1. Security
2. Stability
3. Shell compatibility
4. Shizuku compatibility
5. Root emulation compatibility
6. Maintainability
7. Simplicity
8. New Features

Never sacrifice higher priorities to improve lower priorities.

---

## Default Workflow

### 1. Ponytail First

Before writing code:

- Prefer the smallest viable change.
- Avoid speculative abstractions.
- Avoid unnecessary wrappers.
- Avoid unnecessary dependencies.
- Prefer Android/Kotlin/Java standard capabilities first.
- Prefer removing code over adding code when possible.
- Follow YAGNI principles.

### 2. Agent Skills Execution

For implementation work:

- Understand the root cause.
- Avoid symptom-only fixes.
- Produce a minimal plan.
- Implement incrementally.
- Verify with evidence.
- Document assumptions and limitations.

Required evidence whenever possible:

- Successful build.
- Test results.
- Runtime verification.
- Log output.
- Screenshots or recordings.
- Reproduction and validation steps.

### 3. Improve Audit

Before considering work complete, review for:

- Simplicity.
- Maintainability.
- Dead code.
- Duplicate logic.
- Architectural risks.
- Missing edge cases.
- Performance issues.
- Reliability concerns.

---

## Android-Specific Rules

Target environment:

- Android SDK 29+
- Android 13, 14, 15 and 16 compatibility
- Scoped Storage
- MediaStore
- SAF
- Foreground Services

Always evaluate:

- Battery restrictions.
- OEM restrictions.
- Background execution policies.
- Runtime permission changes.
- Process death.
- Configuration changes.

Prefer official Android APIs before custom implementations.

---

## Shizuku Rules

Shizuku is a critical subsystem.

Never introduce changes without considering:

- Binder lifecycle.
- Service reconnection.
- Permission state changes.
- Shizuku startup delays.
- Multi-user behavior.
- Device-specific quirks.
- Shell UID 2000 behavior.
- SELinux context differences.

Prefer proven solutions over architectural experimentation.

---

## Root and Root Emulation Rules

Root functionality is optional but critical.

Never assume:

- Magisk exists.
- KernelSU exists.
- APatch exists.
- Root is granted.
- UID 0 is available.
- Shell UID 2000 can do everything root can do.
- Shell commands behave identically across devices.

Always:

- Handle failures gracefully.
- Detect capability before use.
- Preserve non-root fallback paths.
- Preserve Shizuku fallback paths.
- Preserve root fallback paths.
- Avoid destructive commands.
- Avoid silent privilege escalation.

When touching root emulation, explicitly compare behavior across:

- Real root
- Magisk
- KernelSU
- APatch
- Shizuku shell
- Stellar root emulation
- Nightzuku root emulation

---

## Shell Command Rules

When executing shell commands:

- Always check exit code.
- Always capture stderr.
- Always use timeouts.
- Never assume command availability.
- Never assume GNU tool behavior.
- Avoid command chains when structured execution is safer.
- Sanitize user-provided input.
- Prefer explicit arguments over string concatenation.
- Log enough for diagnostics without exposing sensitive data.

---

## Termux Rules

When writing or updating Termux-related instruction text:
- Do not tell the user to change `pkg` manually.
- Always provide the complete copy command directly for the correct Termux path.
- Keep this as a workflow guidance only; do not change runtime logic.

---

## EverCall Recorder Rules

Highest priorities:

1. Recording reliability.
2. Data integrity.
3. Storage reliability.

Never risk:

- Lost recordings.
- Corrupted files.
- Missing metadata.
- Broken exports.

Any change touching the recording pipeline requires additional validation.

---

## Stellar / Nightzuku Rules

These projects are sensitive because they affect privileged app behavior.

Before changing internals, evaluate:

- Compatibility with apps already working.
- Shell command behavior.
- Binder transaction behavior.
- Service startup and attach timing.
- Permission grants and revocation.
- Emulated root limitations.
- SELinux limitations.
- OEM-specific restrictions.
- Android SDK 36 behavior.

Never break a stable path just to generalize architecture.

The preferred change is the smallest safe change that improves compatibility.

---

## Non-Negotiable Areas

Never simplify away:

- Security.
- Permission validation.
- Storage reliability.
- SAF and MediaStore integration.
- Recording reliability.
- Shizuku/root/binder behavior.
- Android SDK compatibility.
- Critical error handling.
- Data integrity.
- Shell error handling.
- Root capability detection.

---

## Dependency Policy

Before adding a dependency:

1. Android SDK?
2. Kotlin standard library?
3. Java standard library?
4. Existing dependency?
5. Small local implementation?

Only then add a new dependency.

Every dependency must justify size, maintenance cost, security exposure and long-term benefit.

---

## Documentation Rule

When making non-obvious decisions, use:

```kotlin
// ponytail: simple retry strategy.
// sufficient for current binder reconnect frequency.
// replace with exponential backoff if reconnect storms appear.
```

Explain why the shortcut exists, current limitation and future upgrade path.

---

## Release Gate

Before release verify:

- Build passes.
- App launches.
- Settings open correctly.
- Recording works, when applicable.
- Storage works, when applicable.
- Export works, when applicable.
- Shizuku works.
- Non-Shizuku fallback works.
- Root fallback works.
- Root emulation works, when applicable.
- Shell command failures are handled.
- Permissions flow works.

---

## Conflict Resolution

If instructions conflict:

1. Stability wins.
2. Security wins.
3. Simplicity wins.
4. New code loses.

Default assumption: the best fix is usually the smallest safe fix.
