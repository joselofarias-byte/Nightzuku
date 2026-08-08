# AxManager reuse audit — 2026-08-08

## Source

- Reviewed fork: `joselofarias-byte/AxManager`
- Upstream: `fahrez182/AxManager`
- Parent repository license: Apache License 2.0
- Upstream submodule: `fahrez182/Axeron-API`

The `api` directory in AxManager is a Git submodule. A root license for `Axeron-API` was not confirmed during this audit, so no literal code from that submodule is adopted here. Its script/binary classification logic is retained only as an architectural idea pending license clarification.

## Goal

Extract useful behavior without importing a second privileged-runtime architecture into Nightzuku. Existing Nightzuku work remains authoritative when it already solves the same problem.

## Reuse / duplication matrix

| AxManager idea | Nightzuku status | Decision |
| --- | --- | --- |
| Real wireless ADB mDNS endpoint | Already implemented by PR #3 | Do not duplicate |
| Persistent ADB TCP transport | Already implemented by PR #4 | Do not duplicate |
| Binder/process autorecovery | Already implemented by NightDog in PR #5/#6 | Do not duplicate |
| Persistent desired-running state + bounded retry | Already implemented by PR #6 | Do not duplicate |
| mDNS recovery when discovery itself stops/fails | Missing in `AdbMdns` | **Adopt**, independently implemented with bounded exponential retry |
| Avoid boot recovery in Android Safe Mode | Missing in `BootCompleteReceiver` | **Adopt** |
| Use the real mDNS address instead of assuming loopback during boot | Existing mDNS infrastructure available, but boot receiver still used `127.0.0.1` | **Adopt** by reusing Nightzuku's own endpoint cache |
| Pair/connect UI timeouts | Partially covered by NightDog and existing activation state | Defer; audit only if physical tests expose hangs |
| Nullable/failure-tolerant Binder APIs | Existing Nightzuku/Shizuku hardening overlaps substantially | No broad import; address concrete crashes only |
| Separate-process crash UI | Not currently required | Defer; would require privacy/sanitization design before exposing stack traces |
| Script-vs-binary detection before `dos2unix` | Idea resides in separately licensed/unconfirmed Axeron-API submodule | Idea only; no code copied |
| Plugin framework / unrooted modules | Large overlap/scope expansion | Do not adopt |
| WebUI | No current Nightzuku requirement | Do not adopt |
| libsu-based root layer | Existing root/ADB/Shizuku paths already cover the requirement | Do not adopt |
| App-management features | Overlaps dedicated app-management projects | Do not adopt |

## Adopted changes

### 1. Safe Mode boot guard

`BootCompleteReceiver` now returns before root/ADB recovery when `PackageManager.isSafeMode` is true. Safe Mode is an Android recovery state; recreating a privileged background service there would work against the user's attempt to diagnose or recover the device.

### 2. Real endpoint at boot

The boot receiver previously received an mDNS port but still constructed `AdbClient("127.0.0.1", port, ...)`. It now reads the endpoint already resolved and cached by Nightzuku's `AdbMdns`, falling back to loopback only if the endpoint is unexpectedly unavailable.

This deliberately reuses PR #3's endpoint model rather than importing AxManager's ADB client architecture.

### 3. Self-recovering mDNS discovery

`AdbMdns` now retries when Android reports that discovery could not start or when a running discovery stops unexpectedly.

Policy:

- retry only while the caller still considers the watcher running;
- one scheduled retry at a time;
- exponential delay: 2s, 4s, 8s, 16s, capped at 30s;
- successful discovery resets the retry counter;
- explicit `stop()` cancels pending retries, so a manual NightDog stop cannot resurrect discovery;
- existing endpoint invalidation on service loss is preserved.

This complements NightDog: NightDog already retries *service recovery*, while this change makes the underlying *discovery transport* recover from NSD lifecycle failures.

## Relevant AxManager changes reviewed

- `06c22e74f40c979704d2b844407aa4699247a486` — mDNS persistence and TLS connect auto-discovery.
- `79500fc447af7545068c684ca0ac5be994d75752` — pairing/connection distinction and activation timeouts.
- `154c77ee0739403655433c42f7c1f79b9742af96` — service resilience and coroutine activation flow.
- `658a03ec586009c6a534261628007d9919a15179` — Safe Mode boot guard.
- `63b68432333fbcde164e7abc5fc4c8dcd986fbca` — separate-process crash UI.
- `69dfbb22adea4abcbe5d31a8d65d79465e7da3f9` plus Axeron-API `6f7b1acb6b38aa7f2acaf1dcb49437f76ae13933` — script/binary classification idea.

## Validation gate

Before merge, the stacked Nightzuku branch must pass:

1. `:manager:assembleDebug` on GitHub Actions;
2. boot with normal Android mode;
3. boot into Safe Mode and confirm Nightzuku does not try privileged recovery;
4. wireless ADB with a non-loopback advertised endpoint;
5. force an mDNS discovery interruption/failure and confirm the watcher restarts without spawning duplicate discoveries;
6. existing NightDog physical recovery test;
7. manual stop must still prevent automatic recovery.

No merge is authorized by this document.
