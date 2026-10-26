package com.agentsanywhere.app.api

import org.json.JSONObject

class FilesApi(
    private val client: ApiClient = ApiClient(),
) {
    fun listFiles(
        serverUrl: String,
        authorizationToken: String,
        deviceId: String,
        root: String,
        path: String = ".",
    ): RemoteDirectory {
        val body = JSONObject().apply {
            put("root", root)
            put("path", path)
        }
        val response = client.postJson(
            serverUrl = serverUrl,
            path = "/connectors/${deviceId.urlEncode()}/fs/list",
            body = body,
            authorizationToken = authorizationToken,
        )
        val result = response.optJSONObject("result") ?: JSONObject()
        return RemoteDirectory(
            path = result.optString("path", root).ifBlank { root },
            entries = result.optJSONArray("entries").toObjectList { toRemoteDirectoryEntry() },
            truncated = result.optBoolean("truncated", false),
            targetType = result.optString("targetType", "directory"),
        )
    }

    fun readTextFile(
        serverUrl: String,
        authorizationToken: String,
        deviceId: String,
        root: String,
        path: String,
        maxBytes: Int = 1_048_576,
    ): RemoteTextFile {
        val body = JSONObject().apply {
            put("path", path)
            put("maxBytes", maxBytes)
        }
        return client.postJson(
            serverUrl = serverUrl,
            path = "/connectors/${deviceId.urlEncode()}/fs/readText?root=${root.urlEncode()}",
            body = body,
            authorizationToken = authorizationToken,
        ).toRemoteTextFile()
    }

    /** Reuses the existing streamed filesystem transfer; never materializes PDFs as base64. */
    fun downloadFile(serverUrl: String, authorizationToken: String, deviceId: String, root: String, path: String, target: java.io.File) {
        val prepared = client.postJson(
            serverUrl, "/connectors/${deviceId.urlEncode()}/fs/read?root=${root.urlEncode()}",
            JSONObject().put("path", path), authorizationToken,
        ).getJSONObject("result")
        val size = prepared.getLong("size")
        require(size in 0..256L * 1024 * 1024) { "File exceeds the 256 MB reading limit." }
        val origin = java.net.URI(requireNotNull(normalizeServerOrigin(serverUrl)))
        val url = origin.resolve(prepared.getString("downloadUrl"))
        require(url.scheme == origin.scheme && url.host == origin.host && url.port == origin.port && url.userInfo == null)
        val request = okhttp3.Request.Builder().url(url.toString())
            .header("Authorization", "Bearer $authorizationToken").build()
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        DOWNLOAD_CLIENT.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw ApiException("File download failed.", statusCode = response.code)
            val body = requireNotNull(response.body)
            var written = 0L
            target.outputStream().use { output ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException()
                        val count = input.read(buffer)
                        if (count < 0) break
                        written += count
                        require(written <= size) { "File download size mismatch." }
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                    }
                }
            }
            require(written == size) { "File download is incomplete." }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        require(actual == prepared.getString("sha256").lowercase()) { "File checksum mismatch." }
    }

    companion object {
        private val DOWNLOAD_CLIENT = okhttp3.OkHttpClient.Builder()
            .followRedirects(false).followSslRedirects(false)
            .readTimeout(90, java.util.concurrent.TimeUnit.SECONDS).build()
    }

    private fun JSONObject.toRemoteDirectoryEntry(): RemoteDirectoryEntry {
        return RemoteDirectoryEntry(
            name = optString("name", "Untitled").ifBlank { "Untitled" },
            path = optString("path", ""),
            type = optString("type", "other"),
            size = if (has("size") && !isNull("size")) optLong("size") else null,
        )
    }

    private fun JSONObject.toRemoteTextFile(): RemoteTextFile {
        return RemoteTextFile(
            path = optString("path", ""),
            name = optString("name", "Untitled").ifBlank { "Untitled" },
            size = optLong("size", 0L),
            sha256 = optString("sha256", ""),
            encoding = optString("encoding", "utf8"),
            content = optString("content", ""),
            truncated = optBoolean("truncated", false),
            binary = optBoolean("binary", false),
            serverTime = optString("serverTime", ""),
        )
    }
}
