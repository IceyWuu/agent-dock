from __future__ import annotations

import asyncio

import pytest
from conftest import make_test_client
from sqlalchemy import select, update

from agent_server.infra.db.schema import connectors, projects, users


def test_shared_login_is_closed_by_default(tmp_path):
    client = make_test_client(tmp_path / "private.sqlite3")
    assert client.get("/auth/config").json()["sharedWorkspaceEnabled"] is False
    assert client.post("/auth/shared-session").status_code == 404
    assert client.get("/connectors").status_code == 401


def test_shared_startup_without_accounts_skips_bootstrap(tmp_path, monkeypatch):
    monkeypatch.setenv("AGENT_SERVER_SHARED_WORKSPACE", "true")
    with make_test_client(tmp_path / "shared.sqlite3") as client:
        config = client.get("/auth/config").json()
        assert config["sharedWorkspaceEnabled"] is True
        assert config["needsBootstrap"] is False
        first = client.post("/auth/shared-session").json()
        second = client.post("/auth/shared-session").json()
        assert first["userId"] == second["userId"]
        assert first["role"] == "admin"
        headers = {"Authorization": f"Bearer {first['accessToken']}"}
        assert client.get("/auth/me", headers=headers).status_code == 200
        assert client.get("/connectors", headers=headers).status_code == 200
        # Sharing user access never bypasses device credentials.
        assert client.post(
            "/connector/auth", json={"connectorId": "unknown", "token": "invalid"}
        ).status_code in (401, 422)


def test_merge_keeps_devices_sessions_credentials_and_renames_projects(tmp_path):
    client = make_test_client(tmp_path / "merge.sqlite3")
    db = client.app.state.store

    async def verify():
        await db.create_user(user_id="owner", password="secret", role="admin")
        await db.create_user(user_id="friend", password="secret")
        one, _, _ = await db.create_connector(name="Mac A", user_id="owner")
        two, _, _ = await db.create_connector(name="Mac B", user_id="friend")
        p1 = await db.create_project(
            user_id="owner",
            connector_id=one.id,
            name="Workspace",
            workspace_path="/repo",
        )
        p2 = await db.create_project(
            user_id="friend",
            connector_id=two.id,
            name="Workspace",
            workspace_path="/repo",
        )
        session = await db.create_session(
            connector_id=two.id,
            project_id=p2.id,
            user_id="friend",
            runtime="codex",
            external_session_id="test",
            title="Original",
            cwd="/repo",
        )
        async with db.engine.connect() as conn:
            before = (
                await conn.execute(select(connectors.c.id, connectors.c.token_hash))
            ).all()
        owner = await db.prepare_shared_workspace()
        assert owner == "owner"
        assert await db.prepare_shared_workspace() == owner
        async with db.engine.connect() as conn:
            after = (
                await conn.execute(select(connectors.c.id, connectors.c.token_hash))
            ).all()
            visible = (
                (
                    await conn.execute(
                        select(projects).where(projects.c.user_id == owner)
                    )
                )
                .mappings()
                .all()
            )
        assert before == after
        assert {p["name"] for p in visible} == {"Workspace", "Workspace (2)"}
        assert {p["id"] for p in visible} == {p1.id, p2.id}
        assert (await db.get_session(session.id, user_id=owner)).title == "Original"
        assert await db.count_users() == 2
        # Never silently choose another owner after a shared account is disabled.
        async with db.engine.begin() as conn:
            await conn.execute(
                update(users).where(users.c.id == owner).values(disabled=1)
            )
        with pytest.raises(ValueError, match="missing or disabled"):
            await db.prepare_shared_workspace()

    asyncio.run(verify())


def test_shared_session_rejects_disabled_owner(tmp_path):
    client = make_test_client(tmp_path / "disabled.sqlite3")
    db = client.app.state.store
    owner = asyncio.run(db.prepare_shared_workspace())
    client.app.state.shared_workspace_user_id = owner

    async def disable():
        async with db.engine.begin() as conn:
            await conn.execute(
                update(users).where(users.c.id == owner).values(disabled=1)
            )

    asyncio.run(disable())
    assert client.post("/auth/shared-session").status_code == 403
