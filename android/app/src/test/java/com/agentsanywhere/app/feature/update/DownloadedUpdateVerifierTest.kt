package com.agentsanywhere.app.feature.update

import com.agentsanywhere.app.api.AndroidAppRelease
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest

class DownloadedUpdateVerifierTest {
    @Test fun truncatedOrModifiedDownloadCannotProceed() {
        val file = File.createTempFile("update-integrity", ".apk")
        try {
            val original = "A complete update".toByteArray()
            file.writeBytes(original)
            val hash = MessageDigest.getInstance("SHA-256").digest(original).joinToString("") { "%02x".format(it) }
            val release = AndroidAppRelease("2.1.2", "https://example.com/update.apk", 14, hash, original.size.toLong())
            verifyUpdateBytes(file, release)
            file.writeBytes(original.copyOf(original.size - 1))
            assertTrue(runCatching { verifyUpdateBytes(file, release) }.isFailure)
            file.writeBytes(ByteArray(original.size))
            assertTrue(runCatching { verifyUpdateBytes(file, release) }.isFailure)
        } finally { file.delete() }
    }
}
