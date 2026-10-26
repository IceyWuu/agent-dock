package com.agentsanywhere.app.api

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AppUpdatesTest {
    private fun manifest() = JSONObject("""{"schemaVersion":1,"applicationId":"com.agentsanywhere.app","versionName":"2.1.2","versionCode":14,"downloadUrl":"/updates/android/agents-anywhere-2.1.2-14.apk","sha256":"${"a".repeat(64)}","sizeBytes":12345}""")
    private fun parse(json: JSONObject = manifest(), name: String = "2.1.1", code: Long = 13) = parseAndroidRelease(json, "https://updates.example.com", name, code)
    @Test fun independentAndroidReleaseResolvesOnConnectedServer() {
        val release = requireNotNull(parse())
        assertEquals("https://updates.example.com/updates/android/agents-anywhere-2.1.2-14.apk", release.downloadUrl)
        assertEquals(14, release.versionCode)
    }
    @Test fun installedAndOlderBuildsDoNotOfferUpdate() {
        assertNull(parse(name = "2.1.2", code = 14))
        assertNull(parse(name = "2.1.3", code = 15))
        assertNull(parse(manifest().put("versionName", "2.1.0")))
    }
    @Test fun rejectsWrongAppUntrustedUrlAndMissingIntegrity() {
        for (json in listOf(
            manifest().put("applicationId", "another.app"), manifest().put("schemaVersion", 2),
            manifest().put("downloadUrl", "https://another.example/app.apk"),
            manifest().put("downloadUrl", "http://updates.example.com/updates/android/app.apk"),
            manifest().put("downloadUrl", "https://user:secret@updates.example.com/updates/android/app.apk"),
            manifest().put("sha256", "bad"), manifest().put("sizeBytes", 0),
        )) assertTrue(json.toString(), runCatching { parse(json) }.isFailure)
    }
}
