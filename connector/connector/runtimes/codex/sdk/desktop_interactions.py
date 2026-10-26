"""Normalize the installed desktop's pending requests onto existing AA forms."""

from __future__ import annotations

import hashlib
import json
from dataclasses import dataclass
from typing import Any

from connector.runtime_protocol import RuntimeUnsupportedError
from connector.runtimes.codex.domain.approvals import CODEX_APPROVAL_REQUEST_METHODS
from connector.runtimes.codex.domain.input_requests import (
    CODEX_REQUEST_USER_INPUT,
    codex_input_request,
)

REPLY_START = "<send_user_message_question_reply>"
REPLY_END = "</send_user_message_question_reply>"
FOLLOWER_METHODS = {
    "item/commandExecution/requestApproval": "thread-follower-command-approval-decision",
    "item/fileChange/requestApproval": "thread-follower-file-approval-decision",
    "item/permissions/requestApproval": "thread-follower-permissions-request-approval-response",
    CODEX_REQUEST_USER_INPUT: "thread-follower-submit-user-input",
}


@dataclass(frozen=True)
class DesktopInteraction:
    thread_id: str
    owner: str
    native_id: str | int
    method: str
    params: dict[str, Any]
    asynchronous: bool = False

    @property
    def request_id(self) -> str:
        identity = json.dumps([self.thread_id, self.owner, self.method, self.native_id])
        return "desktop:" + hashlib.sha256(identity.encode()).hexdigest()

    def event(self) -> dict:
        return {"id": self.request_id, "method": self.method, "params": self.params}


def pending_interactions(
    thread_id: str, owner: str, state: dict, turns: list[dict]
) -> dict[str, DesktopInteraction]:
    result = {}
    for raw in state.get("requests", []):
        if not isinstance(raw, dict) or raw.get("method") not in FOLLOWER_METHODS:
            continue
        native_id, params = raw.get("id"), raw.get("params")
        if type(native_id) not in (str, int) or not isinstance(params, dict):
            continue
        if params.get("threadId") != thread_id:
            continue
        method = raw["method"]
        if method == CODEX_REQUEST_USER_INPUT:
            try:
                codex_input_request(params)
            except (ValueError, TypeError, RuntimeUnsupportedError):
                continue
        elif method not in CODEX_APPROVAL_REQUEST_METHODS:
            continue
        interaction = DesktopInteraction(
            thread_id, owner, native_id, method, dict(params)
        )
        result[interaction.request_id] = interaction

    # Async desktop questions are agent-message metadata, not app-server requests.
    # Match the desktop reply envelope, including answers sent from the computer.
    answered = set()
    for turn in turns:
        for item in turn.get("items", []):
            if item.get("type") not in {"userMessage", "steeringUserMessage"}:
                continue
            if (
                item.get("type") == "steeringUserMessage"
                and item.get("status") != "accepted"
            ):
                continue
            content = item.get("content", item.get("input", []))
            if not isinstance(content, list) or len(content) != 1:
                continue
            text = content[0].get("text") if isinstance(content[0], dict) else None
            if not isinstance(text, str):
                continue
            text = text.strip()
            if not text.startswith(REPLY_START) or not text.endswith(REPLY_END):
                continue
            try:
                replies = json.loads(text[len(REPLY_START) : -len(REPLY_END)])
                if isinstance(replies, dict):
                    replies = [replies]
                answered.update(
                    r["questionItemId"]
                    for r in replies
                    if isinstance(r, dict) and isinstance(r.get("questionItemId"), str)
                )
            except (ValueError, TypeError):
                continue
    for turn in turns[-1:]:
        if turn.get("status") not in {"inProgress", "running", "started", "completed"}:
            continue
        for item in turn.get("items", []):
            if item.get("type") != "agentMessage" or not isinstance(
                item.get("id"), str
            ):
                continue
            for index, question in enumerate(item.get("questions") or []):
                if not isinstance(question, dict) or not isinstance(
                    question.get("title"), str
                ):
                    continue
                question_id = json.dumps(
                    ["request_user_input_async", item["id"], index],
                    separators=(",", ":"),
                    ensure_ascii=False,
                )
                if question_id in answered:
                    continue
                options = question.get("options") or []
                if not isinstance(options, list) or not all(
                    isinstance(o, str) for o in options
                ):
                    continue
                params = {
                    "threadId": thread_id,
                    "turnId": turn.get("id"),
                    "isBlocking": False,
                    "_desktopAsync": True,
                    "questions": [
                        {
                            "id": question_id,
                            "question": question["title"],
                            "isOther": True,
                            "options": [{"label": o} for o in options],
                        }
                    ],
                }
                try:
                    codex_input_request(params)
                except (ValueError, TypeError):
                    continue
                interaction = DesktopInteraction(
                    thread_id,
                    owner,
                    question_id,
                    CODEX_REQUEST_USER_INPUT,
                    params,
                    True,
                )
                result[interaction.request_id] = interaction
    return result


def async_reply(interaction: DesktopInteraction, payload: dict) -> str:
    answers = payload.get("answers", {})
    replies = [
        {
            "questionItemId": question["id"],
            "question": question["question"],
            "answer": "\n".join(answers[question["id"]]["answers"]),
        }
        for question in interaction.params["questions"]
        if question["id"] in answers
    ]
    if not replies:
        raise ValueError("请填写答案后提交；异步问题不支持空答案。")
    return (
        REPLY_START + "\n" + json.dumps(replies, ensure_ascii=False) + "\n" + REPLY_END
    )
