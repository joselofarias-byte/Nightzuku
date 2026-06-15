# root-emulation-audit

Use for Stellar, Nightzuku or any root emulation change.

Checklist:
- Distinguish real UID 0 from shell UID 2000.
- Distinguish permission grants from actual privileged capability.
- Check Binder permission assumptions.
- Check SELinux context assumptions.
- Check app compatibility with emulated privileges.
- Verify behavior when app expects real root.
- Verify behavior when app only needs shell-level privileges.
- Preserve Shizuku fallback.
- Preserve real-root fallback.
- Preserve no-root fallback.
- Avoid pretending unsupported capabilities are supported.

Compare behavior across:
- Real root
- Magisk
- KernelSU
- APatch
- Shizuku
- Stellar root emulation
- Nightzuku root emulation

Output:
- Capability matrix.
- Known limitations.
- App compatibility impact.
- Minimal safe fix.
- Validation steps.
