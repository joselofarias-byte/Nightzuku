# HONOR 200 / Android 16 - local Wireless ADB autodiscovery

Date: 2026-09-21

## Why this exists

On the HONOR 200 (ELI-NX9, Android 16), the Wireless debugging listener port rotates. A previously valid endpoint can therefore stop accepting connections even though Wireless debugging remains enabled.

The physical Termux experiment also showed that neither of the usual discovery paths was reliable in this environment:

- `adb mdns services` did not expose the connect service;
- `service.adb.tls.port` was not readable from normal Termux;
- the same TLS port property was not available through the tested rish path.

The phone's Wi-Fi address did not matter for the successful recovery.

## Physical evidence

After the previous endpoint `192.168.1.7:40883` stopped accepting connections, a loopback-only scan of the Android ephemeral TCP range found these open ports:

`38629 39021 39921 40287 40777 43251 47169 49521`

ADB protocol verification rejected the first two candidates and accepted:

`localhost:39921`

The resulting ADB session reported model `ELI-NX9`.

The value `39921` is evidence only. It is dynamic and must never be hard-coded.

## Nightzuku integration

`AdbLocalWirelessDiscovery` turns that proven recovery path into an in-app fallback.

Order used by NightDog remains conservative:

1. persistent TCP if configured and reachable;
2. mDNS Wireless debugging endpoint;
3. readable system ADB TCP endpoint;
4. dynamic loopback Wireless debugging discovery;
5. wait/backoff and retry.

The dynamic fallback:

- scans only `127.0.0.1`, never the LAN;
- reads the kernel ephemeral range when available, otherwise uses `32768-60999`;
- first retries the last dynamic port that worked;
- probes candidates with an ADB `CNXN` header;
- accepts only valid ADB `CNXN`, `AUTH`, or `STLS` responses with a valid ADB magic field;
- caches the successful dynamic port for the next recovery attempt;
- runs from NightDog's IO coroutine and is used only when cheaper transports are unavailable.

## Relationship to Maximum persistence

This fallback is intentionally part of NightDog recovery, not a manual Lab-only action. Its purpose is to remove the recurring requirement to inspect or type the changing Wireless debugging port.

Together with the Maximum persistence UI and the Android 16 rish transport hardening, the intended no-root flow is:

Wireless debugging ON -> NightDog -> persistent/mDNS/system checks -> localhost dynamic discovery -> Starter -> Shizuku server -> Binder recovery.

## Remaining physical validation

After CI produces the integrated APK:

1. update Nightzuku on the HONOR 200;
2. start the service and confirm Binder is live;
3. rotate the Wireless debugging port without reading it;
4. confirm NightDog finds the new localhost ADB endpoint;
5. confirm the service recovers without pressing Start or entering a port;
6. repeat with Wi-Fi -> mobile data -> Wi-Fi;
7. run repeated rish calls and confirm none hangs past the internal timeout.
