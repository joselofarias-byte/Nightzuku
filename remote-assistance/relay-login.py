#!/usr/bin/env python3
"""Private enrollment API. Publish /jremote with Tailscale Serve HTTPS."""
import base64
import hashlib
import hmac
import json
import os
import secrets
import struct
import time
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path

OPERATOR = "remote-operator-01"
DEVICE = "remote-device-01"
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
                for rec in records
            ))

    def settings(self, port):
        return {**self.config["ssh"], "relayPort": port}

    def list_devices(self):
        return [{"id": key, "name": rec["name"]} for key, rec in self.state["devices"].items()]

    def login(self, data):
        role = data.get("username")
        password = data.get("password")
        if role not in (OPERATOR, DEVICE) or not isinstance(password, str) or len(password) > 256:
            return 401, {}
        until = self.failures.get(role, 0)
        if time.monotonic() < until:
            return 429, {}
        entry = self.config["users"][role]
        digest = hashlib.pbkdf2_hmac("sha256", password.encode(), bytes.fromhex(entry["salt"]), 600_000)
        if not hmac.compare_digest(digest, bytes.fromhex(entry["digest"])):
            self.failures[role] = time.monotonic() + 1
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
            devices[identity]["name"] = name
            self.commit()
            return 200, self.settings(devices[identity]["port"])
        operators = self.state["operators"]
        if identity not in operators and len(operators) >= 50:
            return 409, {}
        token = secrets.token_urlsafe(32)
        operators[identity] = {"key": key, "token": hashlib.sha256(token.encode()).hexdigest()}
        self.commit()
        return 200, {"token": token, "devices": self.list_devices()}

    def authenticated(self, path, token, data):
        if not token.startswith("Bearer "):
            return 401, {}
        digest = hashlib.sha256(token[7:].encode()).hexdigest()
        operator = next((rec for rec in self.state["operators"].values()
                         if hmac.compare_digest(digest, rec["token"])), None)
        if operator is None:
            return 401, {}
        if path == "/devices":
            return 200, {"devices": self.list_devices()}
        device = self.state["devices"].get(data.get("id"))
        if device is None:
            return 404, {}
        operator["port"] = device["port"]
        self.commit()
        return 200, self.settings(device["port"])


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
                    elif path in ("/devices", "/connect"):
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
