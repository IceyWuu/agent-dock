"""Optional follower of the existing Codex desktop owner; never acquires its writer.

The desktop IPC is a versioned, internal compatibility surface. Unsupported
versions fail closed and leave ordinary SDK sessions available.
"""

from __future__ import annotations

import asyncio
import copy
import json
import os
import shutil
import stat
import struct
import sys
import time
from collections.abc import Mapping
from pathlib import Path
from typing import Any
from uuid import uuid4

import psutil

from connector.logging import logger
from connector.runtime_protocol import RuntimeConflictError
from connector.runtimes.codex.sdk.binary import (
    codex_launch_command,
    read_login_shell_path,
)
from connector.runtimes.codex.sdk.desktop_interactions import (
    FOLLOWER_METHODS,
    DesktopInteraction,
    async_reply,
    pending_interactions,
)
from connector.runtimes.codex.sdk.runtime_client import (
    CodexStartTurnRequest,
    CodexSteerTurnRequest,
)

MAX_FRAME = 32 * 1024 * 1024


def desktop_environment(values: dict[str, Any]) -> dict[str, str]:
    env = dict(os.environ)
    for key, value in (values.get("environment") or {}).items():
        if value is None:
            env.pop(key, None)
        else:
            env[key] = str(value)
    if values.get("codexHome"):
        env["CODEX_HOME"] = str(Path(values["codexHome"]).expanduser())
    return env


def desktop_endpoint(env: dict[str, str], platform: str = sys.platform) -> str:
    if platform == "win32":
        return r"\\.\pipe\codex-ipc"
    return str(
        Path(env.get("CODEX_HOME", str(Path.home() / ".codex"))).expanduser()
        / "ipc/ipc.sock"
    )


def apply_patches(state: dict, patches: list) -> dict:
    """Commit a revision atomically; never publish a partly applied patch batch."""
    result = state.copy()
    for patch in patches:
        path, op = patch.get("path"), patch.get("op")
        if (
            not isinstance(path, list)
            or not path
            or op not in {"add", "replace", "remove"}
        ):
            raise ValueError("Unsupported desktop patch")
        parent = result
        for part in path[:-1]:
            child = parent[part]
            if not isinstance(child, (dict, list)):
                raise TypeError("Invalid desktop patch container")
            parent[part] = child.copy()
            parent = parent[part]
        key = path[-1]
        if isinstance(parent, list):
            if type(key) is not int or key < 0 or key > len(parent):
                raise ValueError("Invalid desktop patch index")
            if op == "add":
                parent.insert(key, copy.deepcopy(patch["value"]))
            elif op == "remove":
                parent.pop(key)
            else:
                parent[key] = copy.deepcopy(patch["value"])
        elif isinstance(parent, dict) and isinstance(key, str):
            if op != "add" and key not in parent:
                raise ValueError("Missing desktop patch key")
            if op == "remove":
                del parent[key]
            else:
                parent[key] = copy.deepcopy(patch["value"])
        else:
            raise TypeError("Invalid desktop patch path")
    return result


def desktop_turns(state: dict) -> list[dict]:
    history = state.get("turnHistory", {}).get("history", {})
    entities = history.get("entitiesByKey", {})
    ordered, seen = [], set()
    for island in history.get("islands", []):
        for entry in island.get("entries", []):
            key = (
                entry.get("value")
                if entry.get("value") in entities
                else entry.get("key")
            )
            if key in entities and key not in seen:
                ordered.append(entities[key])
                seen.add(key)
    ordered.extend(turn for key, turn in entities.items() if key not in seen)
    if not ordered:
        ordered = state.get("turns", [])
    return [
        {**turn, "id": turn.get("turnId", turn.get("id"))}
        for turn in ordered
        if isinstance(turn, dict)
    ]


class DesktopSessionBridge:
    def __init__(self, values: dict[str, Any]) -> None:
        self.env = desktop_environment(values)
        self.endpoint = desktop_endpoint(self.env)
        self.executable = self.env.get("AA_CODEX_DESKTOP_BIN")
        self.reader = self.writer = None
        self.client_id: str | None = None
        self.pending: dict[str, asyncio.Future] = {}
        self.states: dict[str, tuple[str, int, dict]] = {}
        self.following: dict[str, float] = {}
        self.ready: dict[str, asyncio.Event] = {}
        self.published: dict[str, tuple[str, dict]] = {}
        self.dirty: set[str] = set()
        self.interactions: dict[str, dict[str, DesktopInteraction]] = {}
        self.interaction_locks: dict[str, asyncio.Lock] = {}
        self.answered_interactions: set[str] = set()
        self.owned = lambda _: False
        self.handler = None
        self.tasks: list[asyncio.Task] = []
        self.write_lock = asyncio.Lock()
        self._queue_executable: str | None = None
        self._queue_checked = False

    async def start(self, handler, owned) -> None:
        self.handler, self.owned = handler, owned
        self.tasks = [
            asyncio.create_task(self._maintain()),
            asyncio.create_task(self._publish_loop()),
        ]

    async def stop(self) -> None:
        for task in self.tasks:
            task.cancel()
        await asyncio.gather(*self.tasks, return_exceptions=True)
        self.tasks.clear()
        if self.writer:
            self.writer.close()
        self.writer = None
        self.states.clear()
        self.interactions.clear()
        self.answered_interactions.clear()

    async def _connect(self) -> None:
        if self.writer and not self.writer.is_closing():
            return
        if sys.platform == "win32":
            reader = asyncio.StreamReader()
            protocol = asyncio.StreamReaderProtocol(reader)
            loop = asyncio.get_running_loop()
            transport, _ = await loop.create_pipe_connection(
                lambda: protocol, self.endpoint
            )
            self.reader, self.writer = (
                reader,
                asyncio.StreamWriter(transport, protocol, reader, loop),
            )
        else:
            info = Path(self.endpoint).stat()
            if not stat.S_ISSOCK(info.st_mode) or info.st_uid != os.getuid():
                raise OSError("Desktop endpoint is not an owned socket")
            self.reader, self.writer = await asyncio.open_unix_connection(
                self.endpoint, limit=MAX_FRAME
            )
        await self._write(
            {
                "type": "request",
                "requestId": "initialize",
                "sourceClientId": "initializing-client",
                "method": "initialize",
                "params": {"clientType": "agents-anywhere-connector"},
                "version": 0,
            }
        )
        # Initialization is consumed by the same reader as every later frame.
        while True:
            message = await asyncio.wait_for(self._read(), 5)
            if message.get("requestId") == "initialize":
                self.client_id = message.get("result", {}).get("clientId")
                if not self.client_id:
                    raise ConnectionError("Desktop initialization rejected")
                break
        for thread_id in self.following:
            await self._follow(thread_id)

    async def _read(self) -> dict:
        size = struct.unpack("<I", await self.reader.readexactly(4))[0]
        if not 0 < size <= MAX_FRAME:
            raise ValueError("Invalid desktop frame length")
        message = json.loads(await self.reader.readexactly(size))
        if not isinstance(message, dict):
            raise TypeError("Invalid desktop frame")
        return message

    async def _write(self, message: dict) -> None:
        payload = json.dumps(message, separators=(",", ":")).encode()
        if len(payload) > MAX_FRAME:
            raise ValueError("Desktop frame too large")
        async with self.write_lock:
            self.writer.write(struct.pack("<I", len(payload)) + payload)
            await self.writer.drain()

    async def _follow(self, thread_id: str, following: bool = True) -> None:
        await self._write(
            {
                "type": "broadcast",
                "method": "thread-stream-following-changed",
                "version": 1,
                "sourceClientId": self.client_id,
                "params": {
                    "conversationId": thread_id,
                    "hostId": "local",
                    "following": following,
                },
            }
        )

    async def follow(self, thread_id: str, timeout: float = 0.5) -> dict | None:
        first = thread_id not in self.following
        if first and len(self.following) >= 128:
            oldest = min(self.following, key=self.following.get)
            if self.client_id and self.writer:
                await self._follow(oldest, False)
            self.following.pop(oldest, None)
            self.ready.pop(oldest, None)
            self.states.pop(oldest, None)
            self.published.pop(oldest, None)
            self.dirty.discard(oldest)
            for key in self.interactions.pop(oldest, {}):
                self.answered_interactions.discard(key)
            self.interaction_locks.pop(oldest, None)
        self.following[thread_id] = time.monotonic()
        event = self.ready.setdefault(thread_id, asyncio.Event())
        if first and self.client_id and self.writer:
            try:
                await self._follow(thread_id)
            except (OSError, ConnectionError):
                pass
        if thread_id not in self.states:
            try:
                await asyncio.wait_for(event.wait(), timeout)
            except TimeoutError:
                return None
        current = self.states.get(thread_id)
        return current[2] if current else None

    async def _maintain(self) -> None:
        while True:
            try:
                await self._connect()
                while True:
                    message = await self._read()
                    await self._receive(message)
            except (
                OSError,
                ConnectionError,
                ValueError,
                KeyError,
                TypeError,
                asyncio.IncompleteReadError,
                TimeoutError,
            ):
                if self.writer:
                    self.writer.close()
                self.writer = None
                self.client_id = None
                self.states.clear()
                for event in self.ready.values():
                    event.clear()
                for future in self.pending.values():
                    if not future.done():
                        future.set_exception(ConnectionError("Desktop disconnected"))
                await asyncio.sleep(2)

    async def _receive(self, message: dict) -> None:
        if message.get("type") == "client-discovery-request":
            await self._write(
                {
                    "type": "client-discovery-response",
                    "requestId": message["requestId"],
                    "response": {"canHandle": False},
                }
            )
            return
        if message.get("type") == "response":
            future = self.pending.get(message.get("requestId"))
            if future and not future.done():
                future.set_result(message)
            return
        method, params = message.get("method"), message.get("params", {})
        if not isinstance(params, dict):
            return
        thread_id = params.get("conversationId")
        if (
            method == "thread-stream-following-status-requested"
            and thread_id in self.following
        ):
            await self._follow(thread_id)
        if method == "ipc-connection-reset":
            raise ConnectionError("Desktop reset")
        if (
            method != "thread-stream-state-changed"
            or thread_id not in self.following
            or self.owned(thread_id)
        ):
            return
        if params.get("hostId", "local") != "local":
            return
        # Do not silently interpret a future incompatible state schema.
        if message.get("version") != 11:
            self.states.pop(thread_id, None)
            self.ready[thread_id].clear()
            return
        change, owner = params.get("change", {}), message.get("sourceClientId")
        if not isinstance(change, dict):
            return
        current = self.states.get(thread_id)
        try:
            revision = change["revision"]
            if type(revision) is not int or revision < 0:
                raise ValueError("Invalid desktop revision")
            if change.get("type") == "snapshot":
                state = change["conversationState"]
                if not isinstance(state, dict) or state.get("id") != thread_id:
                    raise ValueError("Desktop thread identity mismatch")
            else:
                if (
                    not current
                    or current[0] != owner
                    or change.get("baseRevision") != current[1]
                    or revision != current[1] + 1
                ):
                    raise ValueError("Desktop revision gap")
                state = apply_patches(current[2], change["patches"])
                if state.get("id") != thread_id:
                    raise ValueError("Desktop patch changed thread identity")
            self.states[thread_id] = owner, revision, state
            self.ready[thread_id].set()
            self.dirty.add(thread_id)
        except (ValueError, KeyError, IndexError, TypeError):
            self.states.pop(thread_id, None)
            self.ready[thread_id].clear()
            await self._follow(thread_id)

    async def _publish_loop(self) -> None:
        # Coalesce full-text replacements while never blocking the IPC reader on HTTP.
        while True:
            await asyncio.sleep(0.1)
            for thread_id in tuple(self.dirty):
                self.dirty.discard(thread_id)
                try:
                    await self._publish(thread_id)
                except Exception as exc:  # noqa: BLE001 — preserve the follower on transient host errors
                    logger.warning(
                        "codex desktop live projection failed error_type={}",
                        type(exc).__name__,
                    )
                    self.dirty.add(thread_id)

    async def _publish(self, thread_id: str) -> None:
        current = self.states.get(thread_id)
        if not current or self.owned(thread_id) or not self.handler:
            return
        turns = desktop_turns(current[2])
        if not turns:
            await self._publish_interactions(thread_id, current)
            return
        previous = self.published.get(thread_id)
        if previous and previous[0] != turns[-1].get("id"):
            ended = next(
                (turn for turn in turns if turn.get("id") == previous[0]), None
            )
            if ended and ended.get("status") not in {
                "inProgress",
                "running",
                "started",
            }:
                await self._publish_turn(thread_id, ended)
        await self._publish_turn(thread_id, turns[-1])
        await self._publish_interactions(thread_id, current)

    async def _publish_interactions(self, thread_id: str, current: tuple) -> None:
        latest = pending_interactions(thread_id, current[0], current[2], desktop_turns(current[2]))
        old = self.interactions.setdefault(thread_id, {})
        for key in tuple(old):
            if key not in latest:
                await self.handler(
                    {
                        "method": "serverRequest/resolved",
                        "params": {
                            "threadId": thread_id,
                            "requestId": key,
                        },
                    }
                )
                old.pop(key, None)
                self.answered_interactions.discard(key)
        for key, request in latest.items():
            if old.get(key) != request and key not in self.answered_interactions:
                await self.handler(request.event())
                old[key] = request

    async def respond(self, request_id: str, result: Mapping[str, Any]) -> None:
        request = next(
            (
                items[request_id]
                for items in self.interactions.values()
                if request_id in items
            ),
            None,
        )
        if request is None:
            raise RuntimeConflictError("此问题已失效，请刷新对话。")
        lock = self.interaction_locks.setdefault(request.thread_id, asyncio.Lock())
        async with lock:
            state = await self.follow(request.thread_id, timeout=1)
            current = self.states.get(request.thread_id)
            latest = (
                pending_interactions(
                    request.thread_id, current[0], state, desktop_turns(state)
                )
                if state is not None and current
                else {}
            )
            if (
                latest.get(request_id) != request
                or self.owned(request.thread_id)
                or request_id in self.answered_interactions
            ):
                raise RuntimeConflictError(
                    "此问题已在电脑端处理或发生变化，请刷新对话。"
                )
            payload = dict(result)
            if request.asynchronous:
                content = async_reply(request, payload)
                turns = desktop_turns(state)
                active = next(
                    (
                        turn
                        for turn in reversed(turns)
                        if turn.get("status") in {"inProgress", "running", "started"}
                    ),
                    None,
                )
                if active:
                    await self.steer(
                        CodexSteerTurnRequest(request.thread_id, active["id"], content)
                    )
                else:
                    await self.queue(CodexStartTurnRequest(request.thread_id, content))
            else:
                field = (
                    "decision"
                    if request.method
                    in {
                        "item/commandExecution/requestApproval",
                        "item/fileChange/requestApproval",
                    }
                    else "response"
                )
                await self.control(
                    FOLLOWER_METHODS[request.method],
                    {
                        "conversationId": request.thread_id,
                        "requestId": request.native_id,
                        field: payload["decision"] if field == "decision" else payload,
                    },
                    version=1,
                )
            # Prevent a second submission while the owner's next snapshot arrives.
            if request_id in self.interactions.get(request.thread_id, {}):
                self.answered_interactions.add(request_id)

    async def _publish_turn(self, thread_id: str, turn: dict) -> None:
        turn_id = turn.get("id")
        if not turn_id:
            return
        previous = self.published.get(thread_id)
        active = turn.get("status") in {"inProgress", "running", "started"}
        # Only live turns (and their terminal update) enter the notification path.
        if not active and (not previous or previous[0] != turn_id):
            return
        old = previous[1] if previous and previous[0] == turn_id else {}

        async def emit(method, **params):
            await self.handler(
                {
                    "method": method,
                    "params": {"threadId": thread_id, "turnId": turn_id, **params},
                }
            )

        if not old:
            await emit(
                "turn/started", turn={"id": turn_id, "status": turn.get("status")}
            )
        old_items = {
            item["id"]: item for item in old.get("items", []) if item.get("id")
        }
        completed = set(turn.get("completedItemIds", []))
        for item in turn.get("items", []):
            if not item.get("id"):
                continue
            done = (
                not active
                or item["id"] in completed
                or item.get("type") == "userMessage"
            )
            raw = {**item, "status": "completed" if done else "inProgress"}
            if old_items.get(item["id"]) != raw:
                await emit("item/completed" if done else "item/started", item=raw)
        if not active and old.get("status") in {"inProgress", "running", "started"}:
            await emit(
                "turn/failed"
                if turn.get("status") == "failed"
                else "turn/interrupted"
                if turn.get("status") == "interrupted"
                else "turn/completed",
                turn={
                    "id": turn_id,
                    "status": turn.get("status"),
                    "error": turn.get("error"),
                },
            )
        self.published[thread_id] = (
            turn_id,
            {
                **turn,
                "items": [
                    {
                        **item,
                        "status": "completed"
                        if not active
                        or item.get("id") in completed
                        or item.get("type") == "userMessage"
                        else "inProgress",
                    }
                    for item in turn.get("items", [])
                ],
            },
        )

    def overlay(
        self, thread_id: str, turns: list[dict], include_new: bool = True
    ) -> list[dict]:
        current = self.states.get(thread_id)
        if not current or self.owned(thread_id):
            return turns
        live = {turn["id"]: turn for turn in desktop_turns(current[2])}
        result = [live.pop(turn.get("id"), turn) for turn in turns]
        tail = desktop_turns(current[2])[-1:]
        if include_new and tail and tail[0]["id"] in live:
            turn = tail[0]
            if (
                turn.get("status") in {"inProgress", "running", "started"}
                or self.published.get(thread_id, (None,))[0] == turn["id"]
            ):
                result.append(turn)
        return result

    async def steer(self, request) -> None:
        await self.control(
            "thread-follower-steer-turn",
            {
                "conversationId": request.thread_id,
                "clientUserMessageId": request.client_message_id or str(uuid4()),
                "input": [{"type": "text", "text": request.content}],
            },
            version=1,
        )

    async def interrupt(self, request) -> None:
        await self.control(
            "thread-follower-interrupt-turn",
            {"conversationId": request.thread_id, "expectedTurnId": request.turn_id},
            version=4,
        )

    async def control(self, method: str, params: dict, version: int) -> Any:
        if (
            not await self.follow(params["conversationId"], timeout=1)
            or not self.client_id
        ):
            raise RuntimeConflictError("原 Codex 客户端暂时无法连接，请稍后重试。")
        request_id = str(uuid4())
        future = asyncio.get_running_loop().create_future()
        self.pending[request_id] = future
        try:
            await self._write(
                {
                    "type": "request",
                    "requestId": request_id,
                    "sourceClientId": self.client_id,
                    "method": method,
                    "params": params,
                    "version": version,
                    "targetClientId": self.states[params["conversationId"]][0],
                    "timeoutMs": 15000,
                }
            )
            response = await asyncio.wait_for(future, 16)
            if response.get("resultType") != "success":
                raise RuntimeConflictError("Codex 未接受此操作，请刷新后重试。")
            return response.get("result")
        finally:
            self.pending.pop(request_id, None)

    async def _find_queue_executable(self) -> str | None:
        if self._queue_checked:
            return self._queue_executable
        shell_path = await asyncio.to_thread(read_login_shell_path)
        candidates = [
            self.executable,
            shutil.which("codex", path=self.env.get("PATH")),
            shutil.which("codex", path=shell_path.path) if shell_path.path else None,
        ]
        # Discover the running owner on all platforms, including Windows Store
        # and non-default app installations that are absent from the GUI PATH.
        username = psutil.Process().username()
        for process in psutil.process_iter(["name", "exe", "username"]):
            if (process.info.get("name") or "").lower() in {"codex", "codex.exe"} and process.info.get("username") == username:
                candidates.append(process.info.get("exe"))
        if sys.platform == "darwin":
            for root in (Path("/Applications"), Path.home() / "Applications"):
                for name in ("Codex.app", "ChatGPT.app"):
                    candidates.append(
                        str(
                            root
                            / name
                            / "Contents/Resources/codex-cli/CodexCLI.app/Contents/MacOS/codex"
                        )
                    )
                    candidates.append(str(root / name / "Contents/Resources/codex"))
        for candidate in dict.fromkeys(path for path in candidates if path):
            try:
                process = await asyncio.create_subprocess_exec(
                    *codex_launch_command(candidate, ["queue", "--help"], self.env),
                    env=self.env,
                    stdout=asyncio.subprocess.PIPE,
                    stderr=asyncio.subprocess.DEVNULL,
                )
                try:
                    output, _ = await asyncio.wait_for(process.communicate(), 5)
                except BaseException:
                    process.kill()
                    await process.wait()
                    raise
                if (
                    process.returncode == 0
                    and b"--thread" in output
                    and b"--message" in output
                ):
                    self._queue_executable = candidate
                    break
            except (OSError, TimeoutError):
                continue
        self._queue_checked = True
        return self._queue_executable

    async def queue(self, request) -> None:
        executable = await self._find_queue_executable()
        if not executable:
            state = await self.follow(request.thread_id, timeout=1)
            turns = desktop_turns(state) if state is not None else []
            if state is None or any(
                turn.get("status") in {"inProgress", "running", "started", "pending"}
                for turn in turns
            ):
                raise RuntimeConflictError(
                    "当前 Codex 客户端不支持排队投递，请更新 Codex 或配置 AA_CODEX_DESKTOP_BIN。消息未发送。"
                )
            inputs = [{"type": "text", "text": request.content}]
            for attachment in request.attachments:
                if attachment.is_image:
                    inputs.append({"type": "localImage", "path": attachment.path})
                else:
                    inputs.append({"type": "text", "text": attachment.reference_note()})
            await self.control(
                "thread-follower-start-turn",
                {
                    "conversationId": request.thread_id,
                    "turnStartParams": {
                        "input": inputs,
                        "clientUserMessageId": request.client_message_id
                        or str(uuid4()),
                    },
                },
                version=1,
            )
            return
        content = request.content
        args = [
            executable,
            "queue",
            "--thread",
            request.thread_id,
            "--message",
            content,
        ]
        for attachment in request.attachments:
            if attachment.is_image:
                args.extend(["--image", attachment.path])
            else:
                content += "\n" + attachment.reference_note()
        args[5] = content
        process = await asyncio.create_subprocess_exec(
            *codex_launch_command(args[0], args[1:], self.env),
            env=self.env,
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE,
        )
        try:
            await asyncio.wait_for(process.communicate(), 30)
        except asyncio.CancelledError:
            process.kill()
            await process.wait()
            raise
        except TimeoutError:
            process.kill()
            await process.wait()
            # Never automatically resend an operation whose delivery is uncertain.
            raise RuntimeConflictError(
                "投递确认超时，消息可能已进入 Codex 队列，请检查对话后再重试。"
            ) from None
        if process.returncode != 0:
            raise RuntimeConflictError(
                "Codex 未接受跨端消息，请确认原客户端仍在运行且支持 queue。消息未确认送达。"
            )
