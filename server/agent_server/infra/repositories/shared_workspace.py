from __future__ import annotations

import secrets

from sqlalchemy import insert, select, text, update

from agent_server.core.auth import hash_password
from agent_server.core.utc import utc_now
from agent_server.infra.db.schema import (
    connectors,
    instance_settings,
    projects,
    session_shares,
    sessions,
    users,
)


class SharedWorkspaceRepositoryMixin:
    async def prepare_shared_workspace(self) -> str:
        """Opt-in ownership consolidation. Retains accounts, messages and credentials."""
        key = "shared_workspace_user_id"
        async with self._engine.begin() as conn:
            # Serialize simultaneous workers/restarts before choosing an owner or moving data.
            if self.backend == "postgres":
                await conn.execute(text("SELECT pg_advisory_xact_lock(731919224)"))
            owner = (
                await conn.execute(
                    select(instance_settings.c.value).where(
                        instance_settings.c.key == key
                    )
                )
            ).scalar_one_or_none()
            rows = (
                (
                    await conn.execute(
                        select(users).order_by(users.c.created_at, users.c.id)
                    )
                )
                .mappings()
                .all()
            )
            if owner:
                user = next((u for u in rows if u["id"] == owner), None)
                if user is None or user["disabled"]:
                    raise ValueError("shared workspace user is missing or disabled")
            else:
                user = next(
                    (u for u in rows if u["role"] == "admin" and not u["disabled"]),
                    None,
                )
                owner = user["id"] if user else "shared-" + secrets.token_hex(8)
                now = utc_now()
                if user is None:
                    await conn.execute(
                        insert(users).values(
                            id=owner,
                            password_hash=hash_password(secrets.token_urlsafe(48)),
                            role="admin",
                            disabled=0,
                            display_name="Shared workspace",
                            created_at=now,
                            updated_at=now,
                        )
                    )
                await conn.execute(
                    insert(instance_settings).values(
                        key=key, value=owner, updated_at=now
                    )
                )
            # Resolve project uniqueness before moving ownership. No message data is rewritten.
            all_projects = (
                (
                    await conn.execute(
                        select(projects).order_by(projects.c.created_at, projects.c.id)
                    )
                )
                .mappings()
                .all()
            )
            owned = [p for p in all_projects if p["user_id"] == owner]
            names = {p["name"] for p in owned}
            workspaces = {
                (p["connector_id"], p["workspace_key"]): p["id"] for p in owned
            }
            for project in all_projects:
                if project["user_id"] == owner:
                    continue
                workspace = (project["connector_id"], project["workspace_key"])
                duplicate = workspaces.get(workspace)
                if duplicate:
                    await conn.execute(
                        update(sessions)
                        .where(sessions.c.project_id == project["id"])
                        .values(project_id=duplicate)
                    )
                    # Keep the redundant project and its old owner for reversible recovery.
                    continue
                name, suffix = project["name"], 2
                while name in names:
                    name = f"{project['name']} ({suffix})"
                    suffix += 1
                await conn.execute(
                    update(projects)
                    .where(projects.c.id == project["id"])
                    .values(user_id=owner, name=name)
                )
                names.add(name)
                workspaces[workspace] = project["id"]
            for table in (connectors, session_shares):
                await conn.execute(
                    update(table).where(table.c.user_id != owner).values(user_id=owner)
                )
        return owner
