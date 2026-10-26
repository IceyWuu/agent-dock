from __future__ import annotations

import asyncio
import copy
import json
import os
import struct
import subprocess
import tempfile
from pathlib import Path

import pytest
from connector.runtime_protocol import RuntimeConfig, RuntimeConflictError
from connector.runtimes.codex.runtime import CodexRuntime
from connector.runtimes.codex.sdk.client import CodexSdkClient
from connector.runtimes.codex.sdk.desktop_bridge import (
    DesktopSessionBridge,
    apply_patches,
    desktop_endpoint,
    desktop_environment,
    desktop_turns,
)
from connector.runtimes.codex.sdk.runtime_client import CodexStartTurnRequest
from test_codex_runtime import FakeHost
from test_codex_sdk_client import _FakeLowLevelAsyncCodex, _FakeLowLevelSdkModule


def snapshot(text="partial", status="inProgress"):
    return {
        "id": "thread_existing",
        "turnHistory": {
            "kind": "canonical",
            "history": {
                "entitiesByKey": {
                    "t": {
                        "turnId": "t",
                        "status": status,
                        "items": [
                            {
                                "id": "u",
                                "type": "userMessage",
                                "content": [{"type": "text", "text": "hello"}],
                            },
                            {"id": "a", "type": "agentMessage", "text": text},
                        ],
                    }
                },
                "islands": [{"entries": [{"key": "t", "value": "t"}]}],
            },
        },
    }


class Router:
    def __init__(self, path):
        self.directory = tempfile.TemporaryDirectory(prefix="aa-codex-ipc-", dir="/tmp")
        self.path, self.messages, self.writer = (
            Path(self.directory.name) / "ipc.sock",
            [],
            None,
        )
        self.revision = 1
        self.state = snapshot()

    async def __aenter__(self):
        self.server = await asyncio.start_unix_server(self.handle, str(self.path))
        return self

    async def __aexit__(self, *_):
        if self.writer:
            self.writer.close()
            await self.writer.wait_closed()
        self.server.close()
        await self.server.wait_closed()
        self.directory.cleanup()

    async def send(self, value):
        payload = json.dumps(value).encode()
        self.writer.write(struct.pack("<I", len(payload)) + payload)
        await self.writer.drain()

    async def state_message(self, change):
        await self.send(
            {
                "type": "broadcast",
                "method": "thread-stream-state-changed",
                "version": 11,
                "sourceClientId": "owner",
                "params": {"conversationId": "thread_existing", "change": change},
            }
        )

    async def handle(self, reader, writer):
        self.writer = writer
        try:
            while True:
                size = struct.unpack("<I", await reader.readexactly(4))[0]
                request = json.loads(await reader.readexactly(size))
                self.messages.append(request)
                if request["method"] == "initialize":
                    await self.send(
                        {
                            "type": "response",
                            "requestId": "initialize",
                            "resultType": "success",
                            "result": {"clientId": "follower"},
                        }
                    )
                elif (
                    request["method"] == "thread-stream-following-changed"
                    and request["params"]["following"]
                ):
                    await self.state_message(
                        {
                            "type": "snapshot",
                            "revision": self.revision,
                            "conversationState": self.state,
                        }
                    )
        except (asyncio.IncompleteReadError, ConnectionError):
            pass
        finally:
            writer.close()

    async def patch(self, patches, *, base=None):
        revision = self.revision
        self.revision += 1
        self.state = apply_patches(self.state, patches)
        await self.state_message(
            {
                "type": "patches",
                "baseRevision": revision if base is None else base,
                "revision": self.revision,
                "patches": patches,
            }
        )


async def eventually(predicate):
    async with asyncio.timeout(4):
        while not predicate():
            await asyncio.sleep(0.01)


def test_endpoint_and_home_are_portable_and_environment_removal_is_preserved(tmp_path):
    env = desktop_environment(
        {"codexHome": str(tmp_path), "environment": {"PATH": None, "CUSTOM": "value"}}
    )
    assert "PATH" not in env
    assert desktop_endpoint(env, "linux") == str(tmp_path / "ipc/ipc.sock")
    assert desktop_endpoint(env, "win32") == r"\\.\pipe\codex-ipc"
    assert str(Path.home()) not in desktop_endpoint(env, "linux")


def test_patch_batch_is_atomic_and_never_mutates_old_snapshots():
    original = snapshot()
    saved = copy.deepcopy(original)
    path = ["turnHistory", "history", "entitiesByKey", "t", "items", 1, "text"]
    changed = apply_patches(
        original, [{"op": "replace", "path": path, "value": "next"}]
    )
    assert desktop_turns(changed)[0]["items"][1]["text"] == "next"
    assert original == saved
    with pytest.raises((ValueError, KeyError, IndexError)):
        apply_patches(
            original,
            [
                {"op": "replace", "path": path, "value": "next"},
                {"op": "remove", "path": ["missing"]},
            ],
        )
    assert original == saved


def test_live_partial_text_reaches_runtime_host_before_turn_completion(tmp_path):
    async def run():
        async with Router(tmp_path / "ipc.sock") as router:
            bridge = DesktopSessionBridge({})
            bridge.endpoint = str(router.path)
            native = _FakeLowLevelAsyncCodex()
            client = CodexSdkClient(
                native, sdk=_FakeLowLevelSdkModule(), desktop_bridge=bridge
            )
            host = FakeHost()
            runtime = CodexRuntime(
                RuntimeConfig(runtime="codex", revision=1, values={}), host, client
            )
            await runtime.start()
            try:
                await eventually(lambda: bridge.client_id == "follower")
                assert await bridge.follow("thread_existing")
                await eventually(
                    lambda: any(
                        i.content.get("text") == "partial"
                        for i in host.timeline_item_upserts
                    )
                )
                assert not host.turn_ends
                partial = [
                    i
                    for i in host.timeline_item_upserts
                    if i.content.get("text") == "partial"
                ][-1]
                path = [
                    "turnHistory",
                    "history",
                    "entitiesByKey",
                    "t",
                    "items",
                    1,
                    "text",
                ]
                await router.patch(
                    [{"op": "replace", "path": path, "value": "partial plus live"}]
                )
                await eventually(
                    lambda: any(
                        i.content.get("text") == "partial plus live"
                        for i in host.timeline_item_upserts
                    )
                )
                latest = [
                    i
                    for i in host.timeline_item_upserts
                    if i.content.get("text") == "partial plus live"
                ][-1]
                assert latest.id == partial.id
                assert not host.turn_ends
                await router.patch(
                    [
                        {
                            "op": "replace",
                            "path": [
                                "turnHistory",
                                "history",
                                "entitiesByKey",
                                "t",
                                "status",
                            ],
                            "value": "completed",
                        }
                    ]
                )
                await eventually(lambda: bool(host.turn_ends))
                assert len(host.turn_ends) == 1
                assert host.state_updates[-1]["status"] == "idle"
                assert native.low_level.turn_start_inputs == []
            finally:
                await runtime.stop()

    asyncio.run(run())


def test_revision_gap_requests_fresh_snapshot_without_publishing_partial_batch(
    tmp_path,
):
    async def run():
        async with Router(tmp_path / "ipc.sock") as router:
            bridge = DesktopSessionBridge({})
            bridge.endpoint = str(router.path)
            received = []

            async def handler(event):
                received.append(event)

            await bridge.start(handler, lambda _: False)
            try:
                await eventually(lambda: bridge.client_id is not None)
                await bridge.follow("thread_existing")
                before = len(router.messages)
                await router.patch(
                    [{"op": "add", "path": ["title"], "value": "new"}], base=999
                )
                await eventually(lambda: len(router.messages) > before)
                assert (
                    router.messages[-1]["method"] == "thread-stream-following-changed"
                )
                await eventually(
                    lambda: bridge.states.get("thread_existing", (None, None))[1] == 2
                )
            finally:
                await bridge.stop()

    asyncio.run(run())


def test_queue_uses_argument_vector_and_real_owner_without_sdk_writer(tmp_path):
    async def run():
        output = tmp_path / "received.json"
        executable = tmp_path / "codex"
        executable.write_text(
            "#!/usr/bin/env python3\nimport sys,json\nfrom pathlib import Path\n"
            "if '--help' in sys.argv: print('--thread --message');sys.exit(0)\n"
            f"Path({str(output)!r}).write_text(json.dumps(sys.argv[1:]))\n"
        )
        executable.chmod(0o700)
        async with Router(tmp_path / "ipc.sock") as router:
            bridge = DesktopSessionBridge(
                {"environment": {"AA_CODEX_DESKTOP_BIN": str(executable)}}
            )
            bridge.endpoint = str(router.path)
            native = _FakeLowLevelAsyncCodex()
            client = CodexSdkClient(
                native, sdk=_FakeLowLevelSdkModule(), desktop_bridge=bridge
            )

            async def handler(_):
                pass

            await client.start(handler)
            try:
                await eventually(lambda: bridge.client_id is not None)
                request = CodexStartTurnRequest(
                    thread_id="thread_existing",
                    content='hello $(false) `false` "world"\nnext',
                )
                result = await client.start_turn(request)
                assert result.turn_id is None and result.payload["queued"] is True
                assert json.loads(output.read_text()) == [
                    "queue",
                    "--thread",
                    "thread_existing",
                    "--message",
                    request.content,
                ]
                assert native.low_level.thread_resume_params == []
                assert native.low_level.turn_start_inputs == []
                assert "thread_existing" not in client._loaded_thread_ids
            finally:
                await client.stop()

    asyncio.run(run())


def test_history_overlay_preserves_unloaded_history_and_new_live_tail():
    bridge = DesktopSessionBridge({})
    bridge.states["thread_existing"] = "owner", 1, snapshot()
    old = {"id": "old", "items": []}
    assert [t["id"] for t in bridge.overlay("thread_existing", [old])] == ["old", "t"]
    bridge.owned = lambda _: True
    assert bridge.overlay("thread_existing", [old]) == [old]


def test_queue_rejection_is_not_reported_as_delivery_success(tmp_path):
    async def run():
        executable = tmp_path / "codex"
        executable.write_text(
            "#!/usr/bin/env python3\nimport sys\nif '--help' in sys.argv: print('--thread --message');sys.exit(0)\nsys.exit(2)\n"
        )
        executable.chmod(0o700)
        bridge = DesktopSessionBridge(
            {"environment": {"AA_CODEX_DESKTOP_BIN": str(executable)}}
        )
        with pytest.raises(RuntimeConflictError, match="未确认送达"):
            await bridge.queue(CodexStartTurnRequest("thread_existing", "hello"))

    asyncio.run(run())


def test_idle_writer_release_never_unloads_a_newly_running_turn():
    async def run():
        native = _FakeLowLevelAsyncCodex()
        client = CodexSdkClient(
            native,
            sdk=_FakeLowLevelSdkModule(),
            desktop_bridge=DesktopSessionBridge({}),
        )
        calls = []

        async def request(method, params, *, response_model):
            calls.append(method)
            return response_model.model_validate({"status": "unsubscribed"})

        native.low_level.request = request
        client._loaded_thread_ids.add("thread")
        client._announced_turns.add(("thread", "new-turn"))
        await client._release_idle_thread("thread")
        assert calls == [] and "thread" in client._loaded_thread_ids
        client._announced_turns.clear()
        await client._release_idle_thread("thread")
        assert calls == ["thread/unsubscribe"]
        assert "thread" not in client._loaded_thread_ids

    asyncio.run(run())


def test_idle_desktop_without_queue_cli_uses_owner_request_and_busy_desktop_does_not(
    monkeypatch,
):
    async def run():
        bridge = DesktopSessionBridge({})
        bridge._queue_checked = True
        calls = []

        async def follow(*_, **__):
            return snapshot(status="completed")

        async def control(method, params, version):
            calls.append((method, params, version))

        monkeypatch.setattr(bridge, "follow", follow)
        monkeypatch.setattr(bridge, "control", control)
        await bridge.queue(
            CodexStartTurnRequest(
                "thread_existing", "hello", client_message_id="client-message"
            )
        )
        assert calls[0][0] == "thread-follower-start-turn"
        assert calls[0][1]["turnStartParams"]["clientUserMessageId"] == "client-message"

        async def busy(*_, **__):
            return snapshot()

        monkeypatch.setattr(bridge, "follow", busy)
        with pytest.raises(RuntimeConflictError, match="消息未发送"):
            await bridge.queue(CodexStartTurnRequest("thread_existing", "hello"))
        assert len(calls) == 1

    asyncio.run(run())


def test_future_desktop_protocol_discards_stale_state_instead_of_reusing_it():
    async def run():
        bridge = DesktopSessionBridge({})
        bridge.states["thread_existing"] = "owner", 1, snapshot()
        bridge.following["thread_existing"] = 0
        bridge.ready["thread_existing"] = asyncio.Event()
        bridge.ready["thread_existing"].set()
        await bridge._receive(
            {
                "type": "broadcast",
                "method": "thread-stream-state-changed",
                "version": 12,
                "params": {"conversationId": "thread_existing", "change": {}},
            }
        )
        assert "thread_existing" not in bridge.states
        assert not bridge.ready["thread_existing"].is_set()

    asyncio.run(run())


def test_coalescing_finishes_previous_turn_before_announcing_new_turn():
    async def run():
        bridge = DesktopSessionBridge({})
        events = []

        async def handler(event):
            events.append(event)

        bridge.handler = handler
        bridge.states["thread_existing"] = "owner", 1, snapshot()
        await bridge._publish("thread_existing")
        state = snapshot(status="completed")
        history = state["turnHistory"]["history"]
        history["entitiesByKey"]["new"] = {
            "turnId": "new",
            "status": "inProgress",
            "items": [],
        }
        history["islands"][0]["entries"].append({"key": "new", "value": "new"})
        bridge.states["thread_existing"] = "owner", 2, state
        await bridge._publish("thread_existing")
        lifecycle = [
            (e["method"], e["params"]["turnId"])
            for e in events
            if e["method"].startswith("turn/")
        ]
        assert lifecycle == [
            ("turn/started", "t"),
            ("turn/completed", "t"),
            ("turn/started", "new"),
        ]

    asyncio.run(run())


def test_real_native_queue_accepts_isolated_session_without_model_call(tmp_path):
    executable = os.environ.get("AA_NATIVE_CODEX_QUEUE_BIN")
    if not executable:
        pytest.skip("Opt-in installed Codex queue verification")
    thread_id = "00000000-0000-0000-0000-000000000001"
    timestamp = "2026-10-26T00:00:00.000Z"
    folder = tmp_path / "sessions/2026/10/26"
    folder.mkdir(parents=True)
    (folder / f"rollout-2026-10-26T00-00-00-{thread_id}.jsonl").write_text(
        json.dumps(
            {
                "timestamp": timestamp,
                "type": "session_meta",
                "payload": {
                    "id": thread_id,
                    "timestamp": timestamp,
                    "cwd": str(tmp_path),
                    "originator": "codex_cli_rs",
                    "cli_version": "0.144.4",
                    "source": "cli",
                    "model_provider": "openai",
                },
            }
        )
        + "\n"
    )
    result = subprocess.run(
        [
            executable,
            "queue",
            "--thread",
            thread_id,
            "--message",
            "isolated queue test",
        ],
        env={**os.environ, "CODEX_HOME": str(tmp_path)},
        capture_output=True,
        text=True,
        timeout=20,
        check=False,
    )
    assert result.returncode == 0, result.stderr
    assert thread_id in result.stdout and "Queued message" in result.stdout
