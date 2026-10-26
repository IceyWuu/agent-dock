package com.agentsanywhere.app.api

import com.agentsanywhere.app.BuildConfig
import java.net.URI
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

data class AndroidAppRelease(
    val versionName: String,
    val downloadUrl: String,
    val versionCode: Long,
    val sha256: String,
    val sizeBytes: Long,
)

/** Android releases advance independently of the backend's health version. */
class AppUpdatesApi(private val client: OkHttpClient = OkHttpClient()) {
    fun check(serverUrl: String, currentVersionName: String, currentVersionCode: Long = BuildConfig.VERSION_CODE.toLong()): AndroidAppRelease? {
        val origin = requireNotNull(normalizeServerOrigin(serverUrl))
        val request = Request.Builder().url("$origin/updates/android/latest.json")
            .header("Cache-Control", "no-cache").get().build()
        return client.newCall(request).execute().use { response ->
            if (response.code == 404) return null // This server has not enabled Android releases.
            if (!response.isSuccessful) throw ApiException("Update check failed with status ${response.code}.")
            parseAndroidRelease(JSONObject(response.body?.string().orEmpty()), origin, currentVersionName, currentVersionCode)
        }
    }
}

internal fun parseAndroidRelease(payload: JSONObject, origin: String, currentName: String, currentCode: Long): AndroidAppRelease? {
    require(payload.getInt("schemaVersion") == 1) { "Unsupported update manifest." }
    require(payload.getString("applicationId") == BuildConfig.APPLICATION_ID) { "Update is for a different application." }
    val name = payload.getString("versionName")
    val code = payload.getLong("versionCode")
    val comparison = requireNotNull(compareUpdateVersions(name, currentName)) { "Invalid update version." }
    if (code <= currentCode || comparison < 0) return null
    val checksum = payload.getString("sha256").lowercase()
    val size = payload.getLong("sizeBytes")
    require(checksum.matches(Regex("[a-f0-9]{64}")) && size > 0) { "Invalid update integrity information." }
    val base = URI(origin)
    val url = base.resolve(payload.getString("downloadUrl"))
    require(url.scheme == "https" && url.userInfo == null && url.rawFragment == null &&
        url.host == base.host && url.port == base.port && url.path.startsWith("/updates/android/") && url.path.endsWith(".apk")) {
        "Update download must use this server's HTTPS release directory."
    }
    return AndroidAppRelease(name, url.toString(), code, checksum, size)
}
