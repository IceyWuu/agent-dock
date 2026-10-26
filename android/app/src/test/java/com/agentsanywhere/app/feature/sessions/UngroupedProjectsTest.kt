package com.agentsanywhere.app.feature.sessions

import com.agentsanywhere.app.model.*
import org.junit.Assert.*
import org.junit.Test

class UngroupedProjectsTest {
    private fun project(id: String, path: String, name: String = workspaceProjectName(path)) = AgentProject(
        id, "user", "device-a", name, path, false, null, 1, null, "", "",
    )
    private fun session(id: String, projectId: String?, device: String = "device-a", archived: Boolean = false, pinned: Boolean = false) = AgentSession(
        id, device, projectId, "Laptop", id, "", null, "workspace", "codex", "Codex", SessionStatus.Idle, "", "", "",
        pinned, archived, false, 0, false, true, false, "2026-10-26T01:00:00Z", 0,
    )
    @Test fun temporaryChatDirectoriesAndGenericHomeFoldersAreUngrouped() {
        for (path in listOf("/Users/alice/Documents/Codex/2026-10-25/bang", "/Users/alice/Documents/Codex/new-chat",
            "/Users/alice/.codex/.chatgpt-projects/g-p-123abc", "/Users/alice", "/Users/alice/Documents",
            "C:\\Users\\alice\\Documents\\Codex\\2026-10-25\\new-chat", "/home/alice", "/home/alice/Downloads")) {
            assertTrue(path, project("p", path).isUngroupedWorkspace())
        }
        assertTrue(project("p", "/Users/alice/Documents/Codex/2026-10-25/new-chat", "new-chat (3)").isUngroupedWorkspace())
    }
    @Test fun realProjectsAndChosenNamesRemainSeparate() {
        for (path in listOf("/Users/alice/Projects/Example", "/Users/alice/Papers/My Paper", "/Volumes/Workspace/repo",
            "C:\\Users\\alice\\Projects\\Example")) assertFalse(path, project("p", path).isUngroupedWorkspace())
        val scratch = project("p", "/Users/alice/Documents/Codex/2026-10-25/bang")
        assertFalse(scratch.copy(manuallyCreated = true).isUngroupedWorkspace())
        assertFalse(scratch.copy(pinned = true).isUngroupedWorkspace())
        assertFalse(scratch.copy(name = "My chosen project").isUngroupedWorkspace())
    }
    @Test fun combinesLooseChatsWithoutChangingTheirProjectOrDevice() {
        val auto = project("auto", "/Users/alice/Documents/Codex/2026-10-25/bang")
        val normal = project("normal", "/Projects/Example")
        val a = session("loose-a", "auto")
        val b = session("loose-b", null, "device-b")
        val unknown = session("unknown-project", "missing")
        val result = ungroupedProjectSidebar(listOf(auto, normal), listOf(a, a, b, unknown, session("normal-chat", "normal")), ProjectSessionStatusFilter.Active)
        assertEquals(listOf(normal), result.projects)
        assertEquals(setOf("loose-a", "loose-b", "unknown-project"), result.sessions.map { it.id }.toSet())
        assertEquals(a, result.sessions.single { it.id == a.id })
        assertEquals(setOf("auto"), result.sourceProjectIds)
    }
    @Test fun deviceAgentArchiveAndPinnedFiltersStillApply() {
        val auto = project("auto", "/Users/alice/Documents/Codex/2026-10-25/bang")
        val active = session("active", "auto")
        val archived = session("archived", "auto", archived = true)
        val pinned = session("pinned", "auto", pinned = true)
        val otherDevice = session("other", null, "device-b")
        val items = listOf(active, archived, pinned, otherDevice)
        val filter = ProjectDeviceAgentFilter(connectorId = "device-a", runtime = "codex")
        assertEquals(listOf(active), ungroupedProjectSidebar(listOf(auto), items, ProjectSessionStatusFilter.Active, filter).sessions)
        assertEquals(listOf(archived), ungroupedProjectSidebar(listOf(auto), items, ProjectSessionStatusFilter.Archived, filter).sessions)
        val other = ungroupedProjectSidebar(listOf(auto), items, ProjectSessionStatusFilter.All, ProjectDeviceAgentFilter(connectorId = "device-b"))
        assertEquals(listOf(otherDevice), other.sessions)
        assertTrue(other.sourceProjectIds.isEmpty())
        assertTrue(ungroupedProjectSidebar(listOf(auto), items, ProjectSessionStatusFilter.All, ProjectDeviceAgentFilter(runtime = "claude")).sessions.isEmpty())
    }
}
