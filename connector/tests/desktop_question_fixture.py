"""Opt-in emulator fixture: real AA forms/response routing, isolated desktop IPC owner.

Run from connector/: .venv/bin/python tests/desktop_question_fixture.py
adb reverse tcp:18867 tcp:18867; instrumentation argument desktopQuestionFixture=true.
No model calls, production server, or user sessions are involved.
"""

from __future__ import annotations

import asyncio
import json
from urllib.parse import parse_qs, urlparse

from connector.runtime_protocol import RuntimeConfig
from connector.runtimes.codex.runtime import CodexRuntime
from connector.runtimes.codex.sdk.client import CodexSdkClient
from connector.runtimes.codex.sdk.desktop_bridge import DesktopSessionBridge
from connector.server.runtime_rpc_payloads import session_notice_payload
from test_codex_desktop_bridge import Router, eventually, question_request, snapshot
from test_codex_runtime import FakeHost
from test_codex_sdk_client import _FakeLowLevelAsyncCodex, _FakeLowLevelSdkModule


class Fixture:
    runtime = router = None

    async def reset(self, mode):
        if self.runtime:
            await self.runtime.stop()
            await self.router.__aexit__()
        self.router = await Router(None).__aenter__()
        self.router.state = snapshot()
        if mode == "async":
            self.router.state["turnHistory"]["history"]["entitiesByKey"]["t"]["items"][
                1
            ]["questions"] = [
                {"title": "Choose a language", "options": ["Python", "Rust"]}
            ]
        else:
            self.router.state["requests"] = [question_request()]
        self.host = FakeHost()
        self.bridge = DesktopSessionBridge({})
        self.bridge.endpoint = str(self.router.path)
        client = CodexSdkClient(
            _FakeLowLevelAsyncCodex(),
            sdk=_FakeLowLevelSdkModule(),
            desktop_bridge=self.bridge,
        )
        self.runtime = CodexRuntime(
            RuntimeConfig(runtime="codex", revision=1, values={}), self.host, client
        )
        await self.runtime.start()
        await eventually(lambda: self.bridge.client_id is not None)
        await self.bridge.follow("thread_existing")
        await eventually(
            lambda: any(
                n.interaction_type == "input_request" for n in self.host.notice_upserts
            )
        )
        self.notice = next(
            n for n in self.host.notice_upserts if n.interaction_type == "input_request"
        )
        return {"ok": True}

    async def handle(self, reader, writer):
        try:
            first = (await reader.readline()).decode().split()
            method, target = first[0], first[1]
            length = 0
            while (line := await reader.readline()) not in (b"\r\n", b"\n", b""):
                if line.lower().startswith(b"content-length:"):
                    length = int(line.split(b":", 1)[1])
            body = json.loads(await reader.readexactly(length)) if length else {}
            parsed = urlparse(target)
            if parsed.path == "/fixture/reset":
                result = await self.reset(
                    parse_qs(parsed.query).get("mode", ["native"])[0]
                )
            elif parsed.path.endswith("/respond") and method == "POST":
                notice = self.notice
                await self.runtime.respond_interaction(
                    notice.session_id,
                    notice.notice_id,
                    body["actionId"],
                    {**notice.context, **body.get("input", {})},
                )
                result = {"ok": True, "result": {"resolved": True}}
            elif parsed.path.endswith("/runtime/notices"):
                latest = {n.notice_id: n for n in self.host.notice_upserts}
                result = {
                    "notices": [session_notice_payload(n) for n in latest.values()]
                }
            elif parsed.path == "/fixture/delivery":
                result = {
                    "messages": [
                        m
                        for m in self.router.messages
                        if m["method"].startswith("thread-follower-")
                    ]
                }
            else:
                raise ValueError("Unknown fixture endpoint")
            status = "200 OK"
        except Exception as exc:  # noqa: BLE001 — return fixture failures to the emulator
            result, status = (
                {"error": type(exc).__name__, "message": str(exc)},
                "500 Internal Server Error",
            )
        data = json.dumps(result).encode()
        writer.write(
            f"HTTP/1.1 {status}\r\nContent-Type: application/json\r\nContent-Length: {len(data)}\r\nConnection: close\r\n\r\n".encode()
            + data
        )
        await writer.drain()
        writer.close()
        await writer.wait_closed()


async def main():
    fixture = Fixture()
    server = await asyncio.start_server(fixture.handle, "127.0.0.1", 18867)
    print("Isolated desktop question fixture on 127.0.0.1:18867", flush=True)
    try:
        async with server:
            await server.serve_forever()
    finally:
        if fixture.runtime:
            await fixture.runtime.stop()
            await fixture.router.__aexit__()


if __name__ == "__main__":
    asyncio.run(main())
