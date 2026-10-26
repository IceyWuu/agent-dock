package com.agentsanywhere.app.feature.sessions

import com.agentsanywhere.app.model.AgentProject
import com.agentsanywhere.app.model.AgentSession

/** Only managed scratch workspaces and generic home folders lose their generated project row.
 * A chosen name, explicit project or pinned project remains a regular project.
 */
fun AgentProject.isUngroupedWorkspace(): Boolean {
    if (manuallyCreated || pinned) return false
    val path = workspacePath.trim().replace('\\', '/').trimEnd('/')
    val generatedName = workspaceProjectName(path)
    if (name != generatedName && !Regex("${Regex.escape(generatedName)} \\([0-9]+\\)").matches(name)) return false
    return isUnscopedWorkspacePath(path)
}

private fun isUnscopedWorkspacePath(path: String): Boolean {
    val parts = path.split('/').filter(String::isNotEmpty)
    // Codex creates one directory per projectless chat under Documents/Codex.
    if (parts.windowed(2).any { it[0].equals("Documents", true) && it[1].equals("Codex", true) }) return true
    if (parts.windowed(2).any { it[0] == ".codex" && it[1] == ".chatgpt-projects" }) return true
    val homeLength = when {
        parts.size >= 2 && parts[0] in setOf("Users", "home") -> 2
        parts.size >= 3 && Regex("[A-Za-z]:").matches(parts[0]) && parts[1].equals("Users", true) -> 3
        else -> return path in setOf("/", "/tmp", "/private/tmp")
    }
    return parts.size == homeLength ||
        (parts.size == homeLength + 1 && parts.last().lowercase() in setOf("documents", "desktop", "downloads"))
}

data class UngroupedProjectSidebar(
    val projects: List<AgentProject>,
    val sourceProjectIds: Set<String>,
    val sessions: List<AgentSession>,
)

fun ungroupedProjectSidebar(
    projects: List<AgentProject>,
    sessions: List<AgentSession>,
    status: ProjectSessionStatusFilter,
    filter: ProjectDeviceAgentFilter = ProjectDeviceAgentFilter(),
): UngroupedProjectSidebar {
    val ungroupedIds = projects.filter(AgentProject::isUngroupedWorkspace).mapTo(mutableSetOf()) { it.id }
    val knownIds = projects.mapTo(mutableSetOf()) { it.id }
    val relevantIds = projects.filter { it.id in ungroupedIds && (filter.connectorId == null || it.connectorId == filter.connectorId) }
        .mapTo(mutableSetOf()) { it.id }
    return UngroupedProjectSidebar(
        projects = projects.filterNot { it.id in ungroupedIds },
        sourceProjectIds = relevantIds,
        sessions = sessions.filter {
            (it.projectId.isNullOrBlank() || it.projectId !in knownIds || it.projectId in ungroupedIds) &&
                projectSessionMatchesStatus(it, status) && filter.matches(it)
        }.distinctBy { it.id }.sortedWith(if (status == ProjectSessionStatusFilter.Archived) archivedSessionComparator() else sessionListComparator()),
    )
}
