package com.agentsanywhere.app.feature.files

import android.content.Context
import java.io.File
import java.security.MessageDigest

/** A stable, device- and server-scoped identity, shared by chat links and the file browser. */
data class WorkspaceDocument(val identity: String, val name: String, val file: File)

enum class WorkspaceFileKind { All, Markdown, Pdf, Image, Other }

fun workspaceFileKind(name: String): WorkspaceFileKind = when (name.substringAfterLast('.', "").lowercase()) {
    "md", "markdown", "mdown" -> WorkspaceFileKind.Markdown
    "pdf" -> WorkspaceFileKind.Pdf
    "png", "jpg", "jpeg", "webp", "gif", "bmp" -> WorkspaceFileKind.Image
    else -> WorkspaceFileKind.Other
}

fun filterWorkspaceFiles(entries: List<FileEntry>, filter: WorkspaceFileKind, query: String): List<FileEntry> =
    entries.filter { entry ->
        (entry.isDirectory || filter == WorkspaceFileKind.All || workspaceFileKind(entry.name) == filter) &&
            (query.isBlank() || entry.name.contains(query.trim(), ignoreCase = true))
    }

internal fun documentIdentity(vararg parts: String): String = MessageDigest.getInstance("SHA-256")
    .digest(org.json.JSONArray(parts.toList()).toString().toByteArray())
    .joinToString("") { "%02x".format(it) }

class DocumentReadingStore(context: Context) {
    private val preferences = context.getSharedPreferences("document-reading", Context.MODE_PRIVATE)
    fun position(identity: String, mode: String): Int = (preferences.all["$identity:position:$mode"] as? Int ?: 0).coerceAtLeast(0)
    fun savePosition(identity: String, mode: String, position: Int) {
        preferences.edit().putInt("$identity:position:$mode", position.coerceAtLeast(0)).apply()
    }
    fun sourceMode(identity: String): Boolean = (preferences.all["$identity:source-mode"] as? Boolean ?: false)
    fun saveSourceMode(identity: String, source: Boolean) {
        preferences.edit().putBoolean("$identity:source-mode", source).apply()
    }
}

fun resolveWorkspaceLink(currentPath: String, rawLink: String): String {
    val decoded = runCatching { java.net.URI(rawLink).path }.getOrNull()?.takeIf { it.isNotBlank() } ?: rawLink.substringBefore('#')
    val path = decoded.replace('\\', '/')
    if (path.startsWith('/') || Regex("^[A-Za-z]:/").containsMatchIn(path)) return path
    val parent = currentPath.replace('\\', '/').substringBeforeLast('/', "")
    val parts = (if (parent.isBlank()) path else "$parent/$path").split('/')
    val normalized = mutableListOf<String>()
    parts.forEach { when (it) { "." -> Unit; ".." -> if (normalized.isNotEmpty() && normalized.last().isNotEmpty()) normalized.removeAt(normalized.lastIndex); else -> normalized.add(it) } }
    return normalized.joinToString("/")
}

internal object WorkspaceDocumentCache {
    private val inUse = mutableMapOf<String, Int>()
    @Synchronized fun pin(file: File) { inUse[file.path] = (inUse[file.path] ?: 0) + 1 }
    @Synchronized fun unpin(file: File) {
        val count = (inUse[file.path] ?: 1) - 1
        if (count <= 0) inUse.remove(file.path) else inUse[file.path] = count
    }
    @Synchronized fun prune(directory: File, keep: File) {
        val files = directory.listFiles()?.filter { it.name.endsWith(".document") }?.sortedByDescending { it.lastModified() }.orEmpty()
        var bytes = files.sumOf { it.length() }
        files.asReversed().forEach { file ->
            if (file != keep && file.path !in inUse && (bytes > 256L * 1024 * 1024 || files.indexOf(file) >= 20)) {
                val length = file.length()
                if (file.delete()) bytes -= length
            }
        }
    }
}
