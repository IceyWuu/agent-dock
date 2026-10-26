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
        self.control_result = "success"

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
                elif request["method"].startswith("thread-follower-"):
                    await self.send({"type": "response", "requestId": request["requestId"],
                                     "resultType": self.control_result,
                                     "result": {"ok": True}})
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


def question_request():
    return {
        "id": 42,
        "method": "item/tool/requestUserInput",
        "params": {
            "threadId": "thread_existing",
            "turnId": "t",
            "itemId": "q",
            "questions": [
                {
                    "id": "language",
                    "question": "Choose a language",
                    "isOther": True,
                    "options": [
                        {"label": "Python", "description": "Simple"},
                        {"label": "Rust"},
                    ],
                }
            ],
        },
    }


@pytest.mark.parametrize("custom", [False, True])
def test_desktop_question_round_trip_and_computer_answer_resolution(tmp_path, custom):
    async def run():
        async with Router(tmp_path) as router:
            router.state["requests"] = [question_request()]
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
                await eventually(lambda: bridge.client_id is not None)
                await bridge.follow("thread_existing")
                await eventually(
                    lambda: any(
                        n.interaction_type == "input_request"
                        for n in host.notice_upserts
                    )
                )
                notice = next(
                    n
                    for n in host.notice_upserts
                    if n.interaction_type == "input_request"
                )
                assert notice.actions[0]["input"]["uiSchema"]["questions"][0][
                    "allowCustom"
                ]
                answer = {"customText": "Go"} if custom else {"optionIds": ["o_1"]}
                responded = await runtime.respond_interaction(
                    notice.session_id,
                    notice.notice_id,
                    "submit",
                    {
                        "requestId": notice.context["requestId"],
                        "answers": {"language": answer},
                    },
                )
                assert responded.ok
                delivered = next(
                    m
                    for m in router.messages
                    if m["method"] == "thread-follower-submit-user-input"
                )
                assert delivered["params"] == {
                    "conversationId": "thread_existing",
                    "requestId": 42,
                    "response": {
                        "answers": {
                            "language": {"answers": ["Go" if custom else "Rust"]}
                        }
                    },
                }
                assert (
                    delivered["targetClientId"] == "owner" and delivered["version"] == 1
                )
                with pytest.raises(RuntimeConflictError):
                    await client.respond(notice.context["requestId"], {})
                # An unchanged state cannot reopen the accepted question.
                await bridge._publish("thread_existing")
                assert host.notice_upserts[-1].status == "resolved"
                # A second request answered on the computer closes on the phone.
                second = question_request()
                second["id"] = 43
                await router.patch(
                    [{"op": "replace", "path": ["requests"], "value": [second]}]
                )
                await eventually(
                    lambda: any(
                        n.status == "open" and n.notice_id != notice.notice_id
                        for n in host.notice_upserts
                    )
                )
                await router.patch(
                    [{"op": "replace", "path": ["requests"], "value": []}]
                )
                await eventually(lambda: host.notice_upserts[-1].status == "resolved")
                assert native.low_level.thread_resume_params == []
            finally:
                await runtime.stop()

    asyncio.run(run())


@pytest.mark.parametrize(
    "method,follower,payload",
    [
        (
            "item/commandExecution/requestApproval",
            "thread-follower-command-approval-decision",
            {"decision": "accept"},
        ),
        (
            "item/fileChange/requestApproval",
            "thread-follower-file-approval-decision",
            {"decision": "decline"},
        ),
        (
            "item/permissions/requestApproval",
            "thread-follower-permissions-request-approval-response",
            {"permissions": {}, "scope": "turn"},
        ),
    ],
)
def test_desktop_approvals_use_owner_and_rejections_remain_retryable(
    tmp_path, method, follower, payload
):
    async def run():
        async with Router(tmp_path) as router:
            request = question_request()
            request["method"] = method
            router.state["requests"] = [request]
            bridge = DesktopSessionBridge({})
            bridge.endpoint = str(router.path)

            async def handler(_):
                pass

            await bridge.start(handler, lambda _: False)
            try:
                await eventually(lambda: bridge.client_id is not None)
                await bridge.follow("thread_existing")
                await eventually(
                    lambda: bool(bridge.interactions.get("thread_existing"))
                )
                key = next(iter(bridge.interactions["thread_existing"]))
                router.control_result = "error"
                with pytest.raises(RuntimeConflictError):
                    await bridge.respond(key, payload)
                assert key not in bridge.answered_interactions
                router.control_result = "success"
                await bridge.respond(key, payload)
                delivered = [m for m in router.messages if m["method"] == follower][-1]
                field = "decision" if "decision" in payload else "response"
                assert delivered["params"][field] == (
                    payload["decision"] if field == "decision" else payload
                )
                assert delivered["params"]["requestId"] == 42
            finally:
                await bridge.stop()

    asyncio.run(run())


def test_async_questions_use_desktop_envelope_and_expire_after_computer_answer():
    from connector.runtimes.codex.sdk.desktop_interactions import pending_interactions

    async def run():
        bridge = DesktopSessionBridge({})
        state = snapshot()
        turn = desktop_turns(state)[0]
        turn["items"][1]["questions"] = [
            {"title": "Which plan?", "options": ["A", "B"]},
            {"title": "Any notes?"},
        ]
        bridge.states["thread_existing"] = "owner", 1, state
        events, deliveries = [], []

        async def handler(event):
            events.append(event)

        async def steer(request):
            deliveries.append(request)

        bridge.handler = handler
        bridge.steer = steer
        await bridge._publish("thread_existing")
        questions = [e for e in events if e["method"] == "item/tool/requestUserInput"]
        assert len(questions) == 2 and all(
            not e["params"]["isBlocking"] for e in questions
        )
        assert all(
            "itemId" not in e["params"] for e in questions
        )  # Standalone phone cards, including multiple questions.
        first = questions[0]
        qid = first["params"]["questions"][0]["id"]
        await bridge.respond(
            first["id"], {"answers": {qid: {"answers": ["Custom plan"]}}}
        )
        text = deliveries[0].content
        replies = json.loads(text.split("\n")[1])
        assert replies == [
            {
                "questionItemId": '["request_user_input_async","a",0]',
                "question": "Which plan?",
                "answer": "Custom plan",
            }
        ]
        turn["items"].append(
            {
                "id": "reply",
                "type": "steeringUserMessage",
                "status": "accepted",
                "input": [{"type": "text", "text": text}],
            }
        )
        remaining = pending_interactions(
            "thread_existing", "owner", state, desktop_turns(state)
        )
        assert len(remaining) == 1
        await bridge._publish("thread_existing")
        assert any(
            e["method"] == "serverRequest/resolved"
            and e["params"]["requestId"] == first["id"]
            for e in events
        )
        with pytest.raises(RuntimeConflictError):
            await bridge.respond(first["id"], {})

    asyncio.run(run())


def test_desktop_response_rejects_changed_owner_and_missing_snapshot():
    async def run():
        bridge = DesktopSessionBridge({})
        state = snapshot()
        state["requests"] = [question_request()]
        bridge.states["thread_existing"] = "owner", 1, state

        async def handler(_):
            pass

        bridge.handler = handler
        await bridge._publish("thread_existing")
        key = next(iter(bridge.interactions["thread_existing"]))
        bridge.states["thread_existing"] = "different-owner", 1, state
        with pytest.raises(RuntimeConflictError):
            await bridge.respond(key, {})
        bridge.states.clear()
        with pytest.raises(RuntimeConflictError):
            await bridge.respond(key, {})

    asyncio.run(run())


def test_completed_async_question_remains_answerable_and_queues_in_original_thread():
    async def run():
        bridge = DesktopSessionBridge({})
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
            state = snapshot()
            turn = desktop_turns(state)[0]
            turn["items"][1]["questions"] = [{"title": "Any notes?"}]
            bridge.states["thread_existing"] = "owner", 1, state
            await bridge._publish("thread_existing")
            notice = next(
                n for n in host.notice_upserts if n.interaction_type == "input_request"
            )
            turn["status"] = "completed"
            # desktop_turns returns shallow copies; update the actual entity status.
            state["turnHistory"]["history"]["entitiesByKey"]["t"]["status"] = (
                "completed"
            )
            await bridge._publish("thread_existing")
            assert not any(
                n.notice_id == notice.notice_id and n.status == "closed"
                for n in host.notice_upserts
            )
            assert len(notice.actions) == 1
            deliveries = []

            async def queue(request):
                deliveries.append(request)

            bridge.queue = queue
            qid = notice.context["requestParams"]["questions"][0]["id"]
            result = await runtime.respond_interaction(
                notice.session_id,
                notice.notice_id,
                "submit",
                {
                    "requestId": notice.context["requestId"],
                    "answers": {qid: {"customText": "Later answer"}},
                },
            )
            assert result.ok and len(deliveries) == 1
            assert deliveries[0].thread_id == "thread_existing"
            assert "Later answer" in deliveries[0].content
            assert host.state_updates[-1]["status"] == "idle"
        finally:
            await runtime.stop()

    asyncio.run(run())


def test_private_or_foreign_questions_are_not_published_as_ordinary_forms():
    from connector.runtimes.codex.sdk.desktop_interactions import pending_interactions

    state = snapshot()
    secret = question_request()
    secret["params"]["questions"][0]["isSecret"] = True
    foreign = question_request()
    foreign["params"]["threadId"] = "other-thread"
    state["requests"] = [secret, foreign]
    assert (
        pending_interactions("thread_existing", "owner", state, desktop_turns(state))
        == {}
    )
