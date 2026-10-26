package com.agentsanywhere.app.feature.files

import java.io.File
import java.security.MessageDigest
import java.util.Properties

internal data class CachedDocument(val file: File, val sha256: String?, val checkedAt: Long) {
    fun isFresh(now: Long): Boolean = checkedAt > 0 && now >= checkedAt && now - checkedAt < 120_000L
}

/** Metadata is published after a complete, verified, immutable content file. */
internal class DocumentDiskCache(private val directory: File) {
    fun read(identity: String): CachedDocument? {
        val metadata = File(directory, "$identity.properties")
        val saved = runCatching {
            if (!metadata.isFile) return@runCatching null
            val values = Properties().apply { metadata.inputStream().use { load(it) } }
            val sha = values.getProperty("sha256") ?: return@runCatching null
            if (!sha.matches(Regex("[0-9a-f]{64}"))) return@runCatching null
            val file = File(directory, "$identity-$sha.document")
            val size = values.getProperty("size")?.toLongOrNull() ?: return@runCatching null
            if (!file.isFile || file.length() != size) return@runCatching null
            CachedDocument(file, sha, values.getProperty("checkedAt")?.toLongOrNull() ?: 0)
        }.getOrNull()
        if (saved != null) return saved
        // Upgrade the already verified files downloaded by Android 2.1.6.
        return File(directory, "$identity.document").takeIf { it.isFile }
            ?.let { CachedDocument(it, null, 0) }
    }

    fun target(identity: String, sha: String): File = File(directory, "$identity-$sha.document")

    fun save(identity: String, file: File, sha: String, checkedAt: Long) {
        val values = Properties().apply {
            setProperty("sha256", sha)
            setProperty("size", file.length().toString())
            setProperty("checkedAt", checkedAt.toString())
        }
        val temporary = File.createTempFile("metadata-", ".part", directory)
        try {
            temporary.outputStream().use { values.store(it, null) }
            check(temporary.renameTo(File(directory, "$identity.properties"))) { "Could not save document metadata." }
        } finally { temporary.delete() }
    }

    fun checksum(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val bytes = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(bytes)
                if (count < 0) break
                digest.update(bytes, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

enum class DocumentLoadStage { Checking, Downloading }
data class DocumentLoadProgress(val stage: DocumentLoadStage, val received: Long = 0, val total: Long = 0)
