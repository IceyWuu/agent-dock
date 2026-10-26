package com.agentsanywhere.app.feature.files

import com.agentsanywhere.app.api.ApiException
import com.agentsanywhere.app.api.FilesApi
import com.agentsanywhere.app.feature.auth.AuthSessionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class FilesController(
    private val filesApi: FilesApi,
    private val sessionStore: AuthSessionStore,
    private val cacheDirectory: java.io.File,
) {
    private val documentLocks = java.util.concurrent.ConcurrentHashMap<String, Mutex>()
    suspend fun listFiles(
        connectorId: String,
        root: String,
        path: String = ".",
    ): Result<FilesDirectory> {
        return withContext(Dispatchers.IO) {
            runCatching {
                val auth = authSession()
                val directory = filesApi.listFiles(
                    serverUrl = auth.serverUrl,
                    authorizationToken = auth.accessToken,
                    deviceId = connectorId,
                    root = root,
                    path = path,
                )
                FilesDirectory(
                    path = directory.path,
                    entries = directory.entries
                        .filter { it.type == "directory" || it.type == "file" }
                        .map {
                            FileEntry(
                                name = it.name,
                                path = it.path,
                                isDirectory = it.type == "directory",
                                size = it.size,
                            )
                        }
                        .sortedWith(compareBy<FileEntry> { !it.isDirectory }.thenBy { it.name.lowercase() }),
                )
            }.recoverCatching { error ->
                if (error is ApiException) throw error
                throw IllegalStateException(error.message ?: "Could not load files.", error)
            }
        }
    }

    suspend fun readTextFile(
        connectorId: String,
        root: String,
        path: String,
    ): Result<TextFile> {
        return withContext(Dispatchers.IO) {
            runCatching {
                val auth = authSession()
                val file = filesApi.readTextFile(
                    serverUrl = auth.serverUrl,
                    authorizationToken = auth.accessToken,
                    deviceId = connectorId,
                    root = root,
                    path = path,
                )
                TextFile(
                    path = file.path,
                    name = file.name,
                    size = file.size,
                    sha256 = file.sha256,
                    encoding = file.encoding,
                    content = file.content,
                    truncated = file.truncated,
                    binary = file.binary,
                )
            }.recoverCatching { error ->
                if (error is ApiException) throw error
                throw IllegalStateException(error.message ?: "Could not open file.", error)
            }
        }
    }

    suspend fun readDocument(connectorId: String, root: String, path: String, forceRefresh: Boolean = false,
        onProgress: (DocumentLoadProgress) -> Unit = {}): Result<WorkspaceDocument> =
        withContext(Dispatchers.IO) {
            runCatching {
                val auth = authSession()
                val identity = documentIdentity(auth.serverUrl, sessionStore.readUserId(), connectorId, resolveWorkspaceLink("${root.trimEnd('/')}/__document__", path))
                documentLocks.getOrPut(identity) { Mutex() }.withLock {
                val started = android.os.SystemClock.elapsedRealtime()
                val directory = java.io.File(cacheDirectory, "workspace-documents").apply { mkdirs() }
                val cache = DocumentDiskCache(directory)
                val cached = cache.read(identity)
                val name = path.replace('\\', '/').substringAfterLast('/')
                if (!forceRefresh && workspaceFileKind(name) == WorkspaceFileKind.Pdf && cached?.isFresh(System.currentTimeMillis()) == true) {
                    cached.file.setLastModified(System.currentTimeMillis())
                    android.util.Log.i("DocumentTiming", "cache=fresh elapsedMs=${android.os.SystemClock.elapsedRealtime() - started}")
                    return@withLock WorkspaceDocument(identity, name, cached.file)
                }
                onProgress(DocumentLoadProgress(DocumentLoadStage.Checking))
                val prepared = kotlinx.coroutines.runInterruptible {
                    filesApi.prepareDownload(auth.serverUrl, auth.accessToken, connectorId, root, path)
                }
                android.util.Log.i("DocumentTiming", "phase=prepare elapsedMs=${android.os.SystemClock.elapsedRealtime() - started} bytes=${prepared.size}")
                val cachedSha = cached?.let { it.sha256 ?: cache.checksum(it.file) }
                val target = cache.target(identity, prepared.sha256)
                if (cached != null && cached.file.length() == prepared.size && cachedSha == prepared.sha256) {
                    if (cached.file != target) check(cached.file.renameTo(target)) { "Could not migrate document cache." }
                    cache.save(identity, target, prepared.sha256, System.currentTimeMillis())
                    target.setLastModified(System.currentTimeMillis())
                    android.util.Log.i("DocumentTiming", "cache=validated elapsedMs=${android.os.SystemClock.elapsedRealtime() - started}")
                    return@withLock WorkspaceDocument(identity, name, target)
                }
                val temporary = java.io.File.createTempFile("document-", ".part", directory)
                try {
                    val downloadStarted = android.os.SystemClock.elapsedRealtime()
                    kotlinx.coroutines.runInterruptible {
                        filesApi.downloadPrepared(auth.accessToken, prepared, temporary) { received, total ->
                            onProgress(DocumentLoadProgress(DocumentLoadStage.Downloading, received, total))
                        }
                    }
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    // Never overwrite a content file that an existing PDF reader has open.
                    if (!target.isFile || target.length() != prepared.size) check(temporary.renameTo(target)) { "Could not cache document." }
                    cache.save(identity, target, prepared.sha256, System.currentTimeMillis())
                    WorkspaceDocumentCache.prune(directory, target)
                    android.util.Log.i("DocumentTiming", "phase=download elapsedMs=${android.os.SystemClock.elapsedRealtime() - downloadStarted} bytes=${prepared.size}")
                    WorkspaceDocument(identity, name, target)
                } finally { temporary.delete() }
                }
            }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
        }

    private fun authSession(): ApiAuth {
        val serverUrl = sessionStore.readServerUrl()
        val accessToken = sessionStore.readAccessToken()
        if (serverUrl.isBlank() || accessToken.isBlank()) {
            throw IllegalStateException("Sign in again to browse files.")
        }
        return ApiAuth(serverUrl = serverUrl, accessToken = accessToken)
    }

    private data class ApiAuth(
        val serverUrl: String,
        val accessToken: String,
    )
}
