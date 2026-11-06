# HONOR 200 / Android 16 - rish binder transport investigation

Date: 2026-09-21

## Scope

Physical-device validation on HONOR 200 (ELI-NX9, Android 16) using Nightzuku 13.6 and Termux.

## Observed behavior

- `shizuku_server` can survive wireless-ADB listener loss and port rotation.
- The server remained detached from the ADB session as uid 2000 (shell), PPid 1.
- ADB ports rotated and were rediscovered locally without Wi-Fi IP or mDNS.
- `rish` was intermittent: sometimes it obtained the shell binder, while other attempts blocked before the normal rish timeout message was emitted.

The last point indicated that the caller could block inside the ActivityManager broadcast transaction itself, rather than only waiting for the Nightzuku manager to return a binder.

## Root cause in the Android 16 reflection shim

The Android 16 path builds arguments for `IActivityManager.broadcastIntentWithFeature` by reflection.

The previous shim assigned `true` to the first boolean parameter. On current Android 16 the trailing boolean parameters are `serialized` and `sticky`; a normal rish binder request must be non-serialized and non-sticky.

That accidental `serialized=true` could turn the binder request into an ordered/serialized broadcast path and block the caller when the manager process was slow.

The same generic argument builder also used zero for the final user id instead of deriving the caller's Android user.

## Fix on this branch

Branch: `fix/rish-android16-binder-transport-20260921`

Changes:

1. Set all broadcast boolean parameters to `false`.
2. Detect app-op as the integer immediately before the options Bundle and use `-1`.
3. Set the final integer parameter to the current Android user id.
4. Dispatch the ActivityManager request on a daemon worker so the rish main looper cannot be blocked by the system-server transaction.
5. Retry once after 1.5 seconds if no binder has arrived.
6. Keep an 8-second final timeout.
7. Accept only the first valid binder reply.

## Validation still required

Build a Nightzuku APK from this branch, install/update it on the HONOR 200, export the new `rish` and `rish_shizuku.dex`, then run repeated tests:

- 20 consecutive `rish -c 'id'` calls.
- ADB listener rotation while `shizuku_server` keeps the same PID.
- Wi-Fi -> mobile data -> Wi-Fi transition.
- Confirm no `rish` call hangs past the internal timeout.
- Confirm Nightzuku and Termux package identity are still detected correctly.

Do not merge until CI passes and physical-device validation succeeds.
