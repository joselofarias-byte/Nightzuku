#!/usr/bin/env python3
"""An authenticated WebSocket transport for the dedicated, loopback-only SSH listener."""
import asyncio
import hashlib
import hmac
import json
import sys
from collections import Counter
from http import HTTPStatus
from pathlib import Path

from websockets.datastructures import MultipleValuesError
from websockets.exceptions import ConnectionClosed
from websockets.legacy.server import WebSocketServerProtocol, serve

STATE = Path("/var/lib/jremote-login/state.json")
SSH_HOST, SSH_PORT = "127.0.0.1", 2223
PORT = 17667
PATH = "/jremote/tunnel"
ACTIVE = Counter()


def authenticated(headers):
    try:
        token = headers.get("Authorization", "")
        role = headers.get("X-Jremote-Role", "")
    except (ValueError, MultipleValuesError):
        return None
    if not token.startswith("Bearer ") or len(token) != 50 or role not in ("operator", "device"):
        return None
    digest = hashlib.sha256(token[7:].encode()).hexdigest()
    try:
        state = json.loads(STATE.read_text())
    except (OSError, ValueError):
        return None
    accounts = state.get("accounts", {})
    records = state["operators"] if role == "operator" else state["devices"]
    for rec in records.values():
        if (hmac.compare_digest(digest, rec.get("token", ""))
                and rec.get("authorized", True) and rec.get("port")
                and accounts.get(rec["account"], {}).get("enabled")):
            return digest
    return None


async def accept(path, headers):
    if path != PATH:
        return HTTPStatus.NOT_FOUND, [], b""
    if not authenticated(headers):
        return HTTPStatus.UNAUTHORIZED, [], b""
    return None


async def receive(ws, writer):
    async for data in ws:
        if not isinstance(data, bytes):
            await ws.close(1003)
            return
        writer.write(data)
        await writer.drain()


async def send(ws, reader):
    while data := await reader.read(32_768):
        await ws.send(data)


async def monitor(ws, identity):
    while True:
        await asyncio.sleep(5)
        if authenticated(ws.request_headers) != identity:
            await ws.close(1008)
            return


async def tunnel(ws, _path):
    identity = authenticated(ws.request_headers)
    if not identity or ACTIVE[identity] >= 2 or sum(ACTIVE.values()) >= 64:
        await ws.close(1008)
        return
    ACTIVE[identity] += 1
    writer = None
    jobs = []
    try:
        reader, writer = await asyncio.wait_for(asyncio.open_connection(SSH_HOST, SSH_PORT), 5)
        jobs = [asyncio.create_task(fn) for fn in (receive(ws, writer), send(ws, reader), monitor(ws, identity))]
        await asyncio.wait(jobs, return_when=asyncio.FIRST_COMPLETED)
    except (OSError, TimeoutError, ConnectionClosed):
        pass
    finally:
        for job in jobs:
            job.cancel()
        await asyncio.gather(*jobs, return_exceptions=True)
        try:
            await ws.close()
        finally:
            if writer is not None:
                writer.close()
            ACTIVE[identity] -= 1
            if not ACTIVE[identity]:
                del ACTIVE[identity]


class RelayProtocol(WebSocketServerProtocol):
    async def handshake(self, *args, **kwargs):
        return await asyncio.wait_for(super().handshake(*args, **kwargs), 5)


async def main():
    async with serve(tunnel, "127.0.0.1", PORT, process_request=accept, create_protocol=RelayProtocol,
                     compression=None, max_size=65_536, max_queue=8, close_timeout=2):
        await asyncio.Future()


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit("Usage: relay-wss.py STATE_JSON")
    STATE = Path(sys.argv[1])
    asyncio.run(main())
