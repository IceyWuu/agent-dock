package com.agentsanywhere.app.feature.files

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DocumentDiskCacheTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun verifiedContentSurvivesRecreationAndFreshnessExpiresWithoutSlidingOnReads() {
        val cache = DocumentDiskCache(temporary.root)
        val content = temporary.newFile("original").apply { writeText("verified PDF fixture") }
        val sha = cache.checksum(content)
        val target = cache.target("device-scoped-id", sha)
        content.copyTo(target)
        cache.save("device-scoped-id", target, sha, 1000)
        val reopened = DocumentDiskCache(temporary.root).read("device-scoped-id")!!
        assertEquals(sha, reopened.sha256)
        assertTrue(reopened.isFresh(1001))
        assertFalse(reopened.isFresh(121000))
        assertFalse(reopened.isFresh(999))
        assertNull(cache.read("another-device"))
        target.writeText("truncated")
        assertNull(cache.read("device-scoped-id"))
    }

    @Test fun oldDownloadCanBeMigratedAndMissingOrMalformedMetadataDoesNotBecomeACacheHit() {
        val cache = DocumentDiskCache(temporary.root)
        val old = File(temporary.root, "id.document").apply { writeText("previously verified") }
        assertEquals(old, cache.read("id")!!.file)
        assertNull(cache.read("id")!!.sha256)
        assertFalse(cache.read("id")!!.isFresh(1))
        old.delete()
        File(temporary.root, "id.properties").writeText("sha256=../../other\nsize=1\ncheckedAt=999")
        assertNull(cache.read("id"))
    }
}
