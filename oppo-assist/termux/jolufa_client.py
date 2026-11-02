"""Cliente de administración. Arma argv de adb y habla con el agente."""

from __future__ import annotations

import json
import os
import sys
import urllib.request
from datetime import date
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from jolufa_policy import (  # noqa: E402
    PolicyError,
    adb_serial,
    check_port,
    confidently_unlocked,
    uninstall_blocked,
    valid_package,
)


def load_config(resolver=None) -> dict:
    cfg = {
        "host": os.environ.get("JOLUFA_HOST", ""),
        "adb_port": os.environ.get("JOLUFA_ADB_PORT", ""),
        "agent_port": os.environ.get("JOLUFA_AGENT_PORT", "8765"),
        "token": os.environ.get("JOLUFA_TOKEN", ""),
        "adb_bin": os.environ.get("JOLUFA_ADB", "adb"),
    }
    path = Path(os.environ.get("JOLUFA_CONFIG", str(Path.home() / ".config" / "jolufa" / "config")))
    if path.is_file():
        for line in path.read_text(encoding="utf-8").splitlines():
            stripped = line.strip()
            if not stripped or stripped.startswith("#") or "=" not in stripped:
                continue
            key, value = stripped.split("=", 1)
            key, value = key.strip(), value.strip()
            if key == "host" and not os.environ.get("JOLUFA_HOST"):
                cfg["host"] = value
            elif key == "adb_port" and not os.environ.get("JOLUFA_ADB_PORT"):
                cfg["adb_port"] = value
            elif key == "agent_port" and not os.environ.get("JOLUFA_AGENT_PORT"):
                cfg["agent_port"] = value
            elif key == "token_file" and not os.environ.get("JOLUFA_TOKEN"):
                token_path = Path(value).expanduser()
                if token_path.is_file():
                    cfg["token"] = token_path.read_text(encoding="utf-8").strip()
    if cfg["host"] == "" or cfg["adb_port"] == "":
        raise PolicyError("falta_config")
    cfg["adb_port"] = check_port(int(cfg["adb_port"]))
    cfg["agent_port"] = check_port(int(cfg["agent_port"]))
    cfg["serial"] = adb_serial(cfg["host"], cfg["adb_port"], resolver)
    return cfg


def _coord(value: str) -> str:
    if not value.isdigit():
        raise PolicyError("coordenada_invalida")
    number = int(value)
    if number > 10000:
        raise PolicyError("coordenada_invalida")
    return str(number)


def _package(value: str) -> str:
    if not valid_package(value):
        raise PolicyError("paquete_invalido")
    return value


def _toybox(day: str, clock: str) -> str:
    if not __import__("re").fullmatch(r"\d{4}-\d{2}-\d{2}", day):
        raise PolicyError("fecha_invalida")
    if not __import__("re").fullmatch(r"\d{2}:\d{2}", clock):
        raise PolicyError("fecha_invalida")
    year, month, dom = (int(part) for part in day.split("-"))
    hour, minute = (int(part) for part in clock.split(":"))
    try:
        date(year, month, dom)
    except ValueError as error:
        raise PolicyError("fecha_invalida") from error
    if not (0 <= hour <= 23 and 0 <= minute <= 59 and 2024 <= year <= 2100):
        raise PolicyError("fecha_invalida")
    return f"{month:02d}{dom:02d}{hour:02d}{minute:02d}{year:04d}.00"


def build(kind: str, args: list[str], cfg: dict, confirmed: bool = False) -> list[str]:
    adb = cfg["adb_bin"]
    serial = cfg["serial"]

    def base(*parts: str) -> list[str]:
        return [adb, "-s", serial, *parts]

    if kind == "connect":
        cmd = [adb, "connect", serial]
    elif kind == "get-state":
        cmd = base("get-state")
    elif kind == "dumpsys-window":
        cmd = base("shell", "dumpsys", "window")
    elif kind == "screencap":
        cmd = base("exec-out", "screencap", "-p")
    elif kind == "scrcpy":
        cmd = [
            "scrcpy",
            "--serial",
            serial,
            "--stay-awake",
            "--keyboard=uhid",
            "--mouse=uhid",
            "--no-audio",
            "--window-title",
            "JOLUFA OPPO",
        ]
    elif kind == "tap":
        cmd = base("shell", "input", "tap", _coord(args[0]), _coord(args[1]))
    elif kind == "swipe":
        cmd = base(
            "shell",
            "input",
            "swipe",
            _coord(args[0]),
            _coord(args[1]),
            _coord(args[2]),
            _coord(args[3]),
            "300",
        )
    elif kind == "text":
        raw = args[0]
        if len(raw) > 200 or any(ch in raw for ch in "\n\r\t;&|`$<>\\"):
            raise PolicyError("texto_invalido")
        cmd = base("shell", "input", "text", raw.replace(" ", "%s"))
    elif kind == "pm-list":
        cmd = base("shell", "pm", "list", "packages", "-3")
    elif kind == "am-start":
        cmd = base(
            "shell",
            "am",
            "start",
            "-a",
            "android.intent.action.MAIN",
            "-c",
            "android.intent.category.LAUNCHER",
            "-p",
            _package(args[0]),
        )
    elif kind == "force-stop":
        cmd = base("shell", "am", "force-stop", _package(args[0]))
    elif kind == "app-details":
        pkg = _package(args[0])
        cmd = base(
            "shell",
            "am",
            "start",
            "-a",
            "android.settings.APPLICATION_DETAILS_SETTINGS",
            "-d",
            f"package:{pkg}",
        )
    elif kind == "uninstall":
        if not confirmed:
            raise PolicyError("falta_confirmacion")
        pkg = _package(args[0])
        if uninstall_blocked(pkg):
            raise PolicyError("paquete_protegido")
        cmd = base("shell", "pm", "uninstall", "--user", "0", pkg)
    elif kind == "reboot":
        if not confirmed:
            raise PolicyError("falta_confirmacion")
        cmd = base("reboot")
    elif kind == "auto-time":
        if args[0] not in ("on", "off"):
            raise PolicyError("valor_invalido")
        cmd = base("shell", "settings", "put", "global", "auto_time", "1" if args[0] == "on" else "0")
    elif kind == "get-auto-time":
        cmd = base("shell", "settings", "get", "global", "auto_time")
    elif kind == "set-date":
        cmd = base("shell", "toybox", "date", _toybox(args[0], args[1]))
    elif kind == "get-date":
        cmd = base("shell", "toybox", "date", "+%Y-%m-%dT%H:%M")
    elif kind == "wifi-adb-off":
        cmd = base("shell", "settings", "put", "global", "adb_wifi_enabled", "0")
    elif kind == "get-wifi-adb":
        cmd = base("shell", "settings", "get", "global", "adb_wifi_enabled")
    else:
        raise PolicyError("comando_desconocido")
    assert_safe(cmd)
    return cmd


def assert_safe(cmd: list[str]) -> None:
    blob = " ".join(cmd).lower()
    for word in ("locksettings", "tcpip", "0.0.0.0", "wipe"):
        if word in blob:
            raise PolicyError("comando_prohibido")


def rpc(cfg: dict, payload: dict) -> dict:
    token = cfg.get("token", "")
    if len(token) < 32:
        raise PolicyError("falta_token")
    host = cfg["serial"].rsplit(":", 1)[0]
    if host.startswith("[") and host.endswith("]"):
        netloc = host
    else:
        netloc = host
    url = f"http://{netloc}:{cfg['agent_port']}/v1/rpc"
    data = json.dumps(payload).encode("utf-8")
    request = urllib.request.Request(url, data=data, method="POST")
    request.add_header("Authorization", f"Bearer {token}")
    request.add_header("Content-Type", "application/json")
    with urllib.request.urlopen(request, timeout=10) as response:
        return json.loads(response.read().decode("utf-8"))


def recovery_text() -> str:
    return (
        "Se reintenta adb connect al host Tailscale y al puerto que anotaste.\n"
        "No se vuelve a encender la depuración inalámbrica.\n"
        "No se recupera scrcpy, ni el bloqueo, ni un puerto que haya cambiado.\n"
        "Eso no está demostrado después de un reinicio."
    )


def main(argv: list[str]) -> int:
    if not argv or argv[0] in ("-h", "ayuda"):
        print("jolufa_client.py build|keyguard|rpc|recovery-text")
        return 0
    try:
        if argv[0] == "keyguard":
            return 0 if confidently_unlocked(sys.stdin.read()) else 3
        if argv[0] == "recovery-text":
            print(recovery_text())
            return 0
        if argv[0] == "build":
            kind = argv[1]
            rest = [part for part in argv[2:] if part != "--confirmo"]
            confirmed = "--confirmo" in argv[2:]
            cmd = build(kind, rest, load_config(), confirmed)
            sys.stdout.buffer.write(b"\0".join(part.encode("utf-8") for part in cmd) + b"\0")
            return 0
        if argv[0] == "rpc":
            op = argv[1]
            payload = {"op": op}
            for item in argv[2:]:
                key, value = item.split("=", 1)
                payload[key] = value
            print(json.dumps(rpc(load_config(), payload), ensure_ascii=False, indent=2))
            return 0
    except PolicyError as error:
        print(str(error), file=sys.stderr)
        return 2
    print("comando_desconocido", file=sys.stderr)
    return 2


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
