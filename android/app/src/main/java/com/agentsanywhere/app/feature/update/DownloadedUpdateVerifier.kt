package com.agentsanywhere.app.feature.update

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.agentsanywhere.app.BuildConfig
import com.agentsanywhere.app.api.AndroidAppRelease
import java.io.File
import java.security.MessageDigest

internal fun verifyUpdateBytes(apk: File, release: AndroidAppRelease) {
    require(apk.length() == release.sizeBytes) { "Update size does not match the published release." }
    val digest = MessageDigest.getInstance("SHA-256")
    apk.inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    val actual = digest.digest().joinToString("") { "%02x".format(it) }
    require(actual == release.sha256) { "Update checksum does not match the published release." }
}

@Suppress("DEPRECATION")
internal fun verifyUpdatePackage(context: Context, apk: File, release: AndroidAppRelease) {
    verifyUpdateBytes(apk, release)
    val manager = context.packageManager
    val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
    val archive = requireNotNull(manager.getPackageArchiveInfo(apk.path, flags)) { "The download is not an Android package." }
    val installed = manager.getPackageInfo(context.packageName, flags)
    val archiveCode = if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode else archive.versionCode.toLong()
    if (archive.packageName != context.packageName || archive.versionName != release.versionName || archiveCode != release.versionCode) {
        throw UpdateInstallException(UpdateInstallFailure.Invalid)
    }
    val archiveSigners = if (Build.VERSION.SDK_INT >= 28) archive.signingInfo?.apkContentsSigners else archive.signatures
    val installedSigners = if (Build.VERSION.SDK_INT >= 28) installed.signingInfo?.apkContentsSigners else installed.signatures
    if (archiveSigners.isNullOrEmpty() || installedSigners.isNullOrEmpty() ||
        archiveSigners.toSet() != installedSigners.toSet()) {
        throw UpdateInstallException(UpdateInstallFailure.Signature)
    }
    if (archiveCode <= BuildConfig.VERSION_CODE) throw UpdateInstallException(UpdateInstallFailure.Version)
}
