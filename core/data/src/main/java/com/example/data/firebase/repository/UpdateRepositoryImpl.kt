package com.example.data.firebase.repository

import android.content.Context
import com.example.domain.repository.UpdateInfo
import com.example.domain.repository.UpdateRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Update checker for the Karaoke fork.
 *
 * The upstream app uses Firebase Remote Config. The fork intentionally does not
 * bundle the upstream Firebase configuration, so touching FirebaseRemoteConfig
 * during Hilt graph creation crashes before MainScreen can start.
 *
 * Keep update checks local for now. This preserves the UpdateRepository contract
 * without requiring the upstream project's private Firebase configuration.
 */
@Singleton
class UpdateRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : UpdateRepository {

    override suspend fun checkForUpdate(): UpdateInfo {
        val currentVersion = getCurrentVersion()
        return UpdateInfo(
            currentVersion = currentVersion,
            latestVersion = currentVersion,
            isUpdateAvailable = false,
            updateUrl = "https://github.com/poomkart/Transpose",
            releaseNotesEn = "Transpose Karaoke fork",
            releaseNotesKo = "Transpose Karaoke fork"
        )
    }

    private fun getCurrentVersion(): String {
        return try {
            context.packageManager
                .getPackageInfo(context.packageName, 0)
                .versionName
                ?: "1.0.0"
        } catch (_: Exception) {
            "1.0.0"
        }
    }
}
