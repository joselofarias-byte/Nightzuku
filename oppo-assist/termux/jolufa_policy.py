"""Política de JOLUFA: Tailscale, bloqueo y paquetes protegidos."""

from __future__ import annotations

import ipaddress
import re
from pathlib import Path

TAILSCALE_V4 = ipaddress.ip_network("100.64.0.0/10")
TAILSCALE_V6 = ipaddress.ip_network("fd7a:115c:a1e0::/48")
PKG = re.compile(r"^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$")
ROOT = Path(__file__).resolve().parents[1]
PROTECTED = ROOT / "shared" / "protected-packages.list"


class PolicyError(Exception):
    pass


def is_tailscale_literal(host: str) -> bool:
    try:
        ip = ipaddress.ip_address(host)
    except ValueError:
        return False
    if isinstance(ip, ipaddress.IPv4Address):
        if host != str(ip):
            return False
        return ip in TAILSCALE_V4
    if host.count(":") < 2:
        return False
    return ip in TAILSCALE_V6


def is_literal_ip(host: str) -> bool:
    if ":" in host:
        return bool(re.fullmatch(r"[0-9A-Fa-f:]+", host)) and host.count(":") >= 2
    return bool(re.fullmatch(r"\d{1,3}(\.\d{1,3}){3}", host))


def check_host(host: str, resolver=None) -> str:
    if is_literal_ip(host):
        if not is_tailscale_literal(host):
            raise PolicyError("host_fuera_de_tailscale")
        return host
    if (
        not re.fullmatch(r"[A-Za-z0-9.-]{1,253}", host)
        or host.startswith(".")
        or host.endswith(".")
        or ".." in host
    ):
        raise PolicyError("nombre_invalido")
    if resolver is None:
        import socket

        def resolver(name: str):
            infos = socket.getaddrinfo(name, None)
            return sorted({item[4][0].split("%", 1)[0] for item in infos})

    ips = list(resolver(host))
    if not ips or not all(is_tailscale_literal(ip) for ip in ips):
        raise PolicyError("nombre_no_es_solo_tailscale")
    return ips[0]


def check_port(port: int) -> int:
    if isinstance(port, bool) or not isinstance(port, int) or port < 1 or port > 65535:
        raise PolicyError("puerto_invalido")
    return port


def valid_package(name: str) -> bool:
    return bool(PKG.fullmatch(name)) and len(name) <= 200


def protected_packages(path: Path | None = None) -> set[str]:
    text = (path or PROTECTED).read_text(encoding="utf-8")
    found = set()
    for line in text.splitlines():
        item = line.split("#", 1)[0].strip()
        if item:
            found.add(item)
    return found


def uninstall_blocked(package: str, listed: set[str] | None = None) -> bool:
    names = protected_packages() if listed is None else listed
    if not valid_package(package):
        return True
    if package in names or package == "android" or package.startswith("com.android."):
        return True
    return False


def confidently_unlocked(dumpsys: str) -> bool:
    flags = []
    for key in ("mDreamingLockscreen", "isStatusBarKeyguard", "mShowingLockscreen"):
        match = re.search(rf"{key}=(true|false)", dumpsys)
        if match:
            flags.append(match.group(1) == "true")
    if not flags:
        return False
    return not any(flags)


def adb_serial(host: str, port: int, resolver=None) -> str:
    checked = check_host(host, resolver)
    check_port(port)
    if ":" in checked:
        return f"[{checked}]:{port}"
    return f"{checked}:{port}"
