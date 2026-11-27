#!/usr/bin/env python3
"""Private enrollment API. Publish /jremote with Tailscale Serve HTTPS."""
import base64
import hashlib
import hmac
import json
import os
import re
import secrets
import struct
import time
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path

OPERATOR = "remote-operator-01"
DEVICE = "remote-device-01"
USER_NAME = re.compile(r"[a-z][a-z0-9_.-]{2,31}")
PASSWORD_ROUNDS = 600_000
CURVE = b"ecdsa-sha2-nistp256"


def atomic(path, value):
    temp = path.with_suffix(".new")
    temp.write_text(value)
    os.replace(temp, path)


def public_key(value):
    fields = value.split()
    if len(fields) < 2 or fields[0] != CURVE.decode():
        raise ValueError()
    raw = base64.b64decode(fields[1], validate=True)
    curve = b"nistp256"
    prefix = struct.pack(">I", len(CURVE)) + CURVE + struct.pack(">I", len(curve)) + curve
    if len(raw) != len(prefix) + 69 or not raw.startswith(prefix + b"\0\0\0A\x04"):
        raise ValueError()
    return f"{fields[0]} {fields[1]}", hashlib.sha256(raw).hexdigest()


class Relay:
    def __init__(self, config, directory):
        self.config = config
        self.directory = directory
        self.path = directory / "state.json"
        self.state = json.loads(self.path.read_text()) if self.path.exists() else {"devices": {}, "operators": {}}
        self.accounts = self.state.setdefault("accounts", {})
        for username, entry in config["users"].items():
            self.accounts.setdefault(username, {**entry, "role": username, "enabled": True})
        for role, collection in ((DEVICE, "devices"), (OPERATOR, "operators")):
            for record in self.state[collection].values():
                record.setdefault("account", role)
        self.failures = {}
        self.commit()

    def commit(self):
        atomic(self.path, json.dumps(self.state, ensure_ascii=False))
        devices = self.state["devices"]
        for role, records, rule in (
            (DEVICE, devices.values(), 'permitlisten'),
            (OPERATOR, (rec for rec in self.state["operators"].values() if rec.get("port")), 'permitopen'),
        ):
            atomic(self.directory / role, "".join(
                f'restrict,port-forwarding,{rule}="127.0.0.1:{rec["port"]}" {rec["key"]}\n'
                for rec in records if rec.get("authorized", True)
                and self.accounts.get(rec["account"], {}).get("enabled")
            ))

    def settings(self, port):
        return {**self.config["ssh"], "relayPort": port}

    def list_devices(self):
        return [
            {"id": key, "name": rec["name"]}
            for key, rec in self.state["devices"].items()
            if rec.get("authorized", True) and self.accounts.get(rec["account"], {}).get("enabled")
        ]

    def login(self, data):
        username = data.get("username")
        password = data.get("password")
        if not isinstance(username, str) or not USER_NAME.fullmatch(username):
            return 401, {}
        if not isinstance(password, str) or not 1 <= len(password) <= 256:
            return 401, {}
        entry = self.accounts.get(username)
        if entry is None or not entry["enabled"]:
            return 401, {}
        if time.monotonic() < self.failures.get(username, 0):
            return 429, {}
        digest = hashlib.pbkdf2_hmac(
            "sha256", password.encode(), bytes.fromhex(entry["salt"]), PASSWORD_ROUNDS
        )
        if not hmac.compare_digest(digest, bytes.fromhex(entry["digest"])):
            self.failures[username] = time.monotonic() + 1
            return 401, {}
        role = entry["role"]
        if data.get("role", role) != role:
            return 401, {}
        key, identity = public_key(data["publicKey"])
        if role == DEVICE:
            name = data["name"].strip()
            if not 1 <= len(name) <= 60 or any(ord(char) < 32 for char in name):
                return 400, {}
            devices = self.state["devices"]
            if identity not in devices:
                occupied = {r["port"] for r in devices.values()}
                port = next((p for p in range(27000, 28000) if p not in occupied), None)
                if port is None:
                    return 409, {}
                devices[identity] = {"key": key, "port": port}
            devices[identity].update(name=name, account=username, authorized=True)
            self.commit()
            return 200, self.settings(devices[identity]["port"])
        operators = self.state["operators"]
        if identity not in operators and len(operators) >= 50:
            return 409, {}
        token = secrets.token_urlsafe(32)
        operators[identity] = {
            "key": key, "account": username, "token": hashlib.sha256(token.encode()).hexdigest()
        }
        self.commit()
        return 200, {"token": token, "admin": username == OPERATOR, "devices": self.list_devices()}

    def authenticated(self, path, token, data):
        if not token.startswith("Bearer "):
            return 401, {}
        digest = hashlib.sha256(token[7:].encode()).hexdigest()
        operator = next((rec for rec in self.state["operators"].values()
                         if hmac.compare_digest(digest, rec["token"])), None)
        if operator is None:
            return 401, {}
        if path.startswith("/accounts"):
            return self.manage_accounts(path, operator["account"], data)
        if path == "/devices":
            return 200, {"devices": self.list_devices()}
        device = self.state["devices"].get(data.get("id"))
        if (device is None or not device.get("authorized", True)
                or not self.accounts.get(device["account"], {}).get("enabled")):
            return 404, {}
        operator["port"] = device["port"]
        self.commit()
        return 200, self.settings(device["port"])


    def manage_accounts(self, path, principal, data):
        if principal != OPERATOR:
            return 403, {}
        if path == "/accounts":
            return 200, {"accounts": [
                {"username": name, "role": record["role"], "enabled": record["enabled"], "admin": name == OPERATOR}
                for name, record in sorted(self.accounts.items())
            ]}
        username = data.get("username")
        if not isinstance(username, str) or not USER_NAME.fullmatch(username):
            return 400, {}
        record = self.accounts.get(username)
        if path == "/accounts/status":
            enabled = data.get("enabled")
            if record is None or type(enabled) is not bool or username == OPERATOR:
                return 400, {}
            record["enabled"] = enabled
            if not enabled:
                self.revoke(username)
        else:
            password = data.get("password")
            if not isinstance(password, str) or not 12 <= len(password) <= 256:
                return 400, {}
            if path == "/accounts/create":
                role = data.get("role")
                if role not in (OPERATOR, DEVICE):
                    return 400, {}
                if record is not None or len(self.accounts) >= 1000:
                    return 409, {}
                record = {"role": role, "enabled": True}
                self.accounts[username] = record
            elif path == "/accounts/password":
                if record is None:
                    return 404, {}
                self.revoke(username)
            else:
                return 404, {}
            salt = secrets.token_bytes(16)
            record["salt"] = salt.hex()
            record["digest"] = hashlib.pbkdf2_hmac("sha256", password.encode(), salt, PASSWORD_ROUNDS).hex()
            self.failures.pop(username, None)
        self.commit()
        return 200, {}

    def revoke(self, username):
        operators = self.state["operators"]
        for key in [key for key, rec in operators.items() if rec["account"] == username]:
            del operators[key]
        ports = set()
        for record in self.state["devices"].values():
            if record["account"] == username:
                record["authorized"] = False
                ports.add(record["port"])
        for operator in operators.values():
            if operator.get("port") in ports:
                operator.pop("port")


class Handler(BaseHTTPRequestHandler):
    timeout = 5

    def do_POST(self):
        path = self.path.removeprefix("/jremote")
        payload = {}
        status = 400
        try:
            size = int(self.headers.get("Content-Length", 0))
            if 0 < size <= 4096:
                data = json.loads(self.rfile.read(size))
                if isinstance(data, dict):
                    if path == "/login":
                        status, payload = self.server.relay.login(data)
                    elif path in (
                        "/devices", "/connect", "/accounts", "/accounts/create", "/accounts/password", "/accounts/status"
                    ):
                        status, payload = self.server.relay.authenticated(
                            path, self.headers.get("Authorization", ""), data
                        )
                    else:
                        status = 404
        except (ValueError, KeyError, TypeError, AttributeError):
            pass
        body = json.dumps(payload).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *args):
        pass


if __name__ == "__main__":
    import sys
    os.umask(0o077)
    config = json.loads(Path(sys.argv[1]).read_text())
    server = HTTPServer(("127.0.0.1", 17666), Handler)
    server.relay = Relay(config, Path(sys.argv[2]))
    server.serve_forever()
