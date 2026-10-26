package com.agentsanywhere.app.feature.update

import android.content.Context
import android.content.pm.PackageInstaller
import java.io.File

internal class AppUpdateStorage(context: Context) {
    val directory = File(context.cacheDir, "app-updates")
    private val preferences = context.getSharedPreferences("app-update-install-result", Context.MODE_PRIVATE)

    // Called once per ViewModel, before any new download. Staged sessions own a separate copy.
    fun clearOrphanedDownloads() {
        directory.listFiles()?.filter { it.isFile && it.extension == "apk" }?.forEach { it.delete() }
    }

    fun deleteDownload(file: File?) {
        runCatching {
            if (file != null && file.canonicalFile.parentFile == directory.canonicalFile) file.delete()
        }
    }

    fun saveFailure(failure: UpdateInstallFailure) {
        preferences.edit().putString("failure", failure.name).commit()
    }

    fun takeFailure(): UpdateInstallFailure? {
        val value = preferences.getString("failure", null) ?: return null
        preferences.edit().remove("failure").commit()
        return UpdateInstallFailure.entries.firstOrNull { it.name == value }
    }

    fun clearFailure() { preferences.edit().remove("failure").commit() }
}

enum class UpdateInstallFailure {
    Signature, Version, Conflict, Storage, Blocked, Incompatible, Cancelled, Invalid, Generic;
}

internal class UpdateInstallException(val reason: UpdateInstallFailure) : Exception(reason.name)

internal fun installFailureForStatus(status: Int): UpdateInstallFailure = when (status) {
    PackageInstaller.STATUS_FAILURE_CONFLICT -> UpdateInstallFailure.Conflict
    PackageInstaller.STATUS_FAILURE_STORAGE -> UpdateInstallFailure.Storage
    PackageInstaller.STATUS_FAILURE_BLOCKED -> UpdateInstallFailure.Blocked
    PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> UpdateInstallFailure.Incompatible
    PackageInstaller.STATUS_FAILURE_ABORTED -> UpdateInstallFailure.Cancelled
    PackageInstaller.STATUS_FAILURE_INVALID -> UpdateInstallFailure.Invalid
    else -> UpdateInstallFailure.Generic
}
