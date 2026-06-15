# evercall-release-check

Use before EverCall release builds.

Required checks:
- App launches.
- Settings opens.
- Recording starts.
- Recording stops.
- Duration metadata correct.
- File exists after recording.
- File survives app restart.
- Export works.
- Storage permissions work.
- Language resources valid.
- ProGuard/R8 does not break runtime.
- Shizuku and non-Shizuku paths checked.
- Root and non-root paths checked.

Output:
- Pass/fail table.
- Blocking issues.
- Non-blocking issues.
- Release recommendation.
