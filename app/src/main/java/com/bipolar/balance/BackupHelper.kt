package com.bipolar.balance

import android.content.Context
import androidx.lifecycle.LifecycleCoroutineScope
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.ByteArrayContent
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.api.services.drive.model.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Silent auto-backup helper.
 * Called after each DailyEntry save if a Google account is available.
 * Failures are silently swallowed – this is a best-effort background sync.
 */
object BackupHelper {

    private const val BACKUP_FILENAME = "barlevel_backup.json"

    /**
     * Attempts a silent upload to Google Drive's appDataFolder.
     * Does nothing if the user is not signed in.
     */
    fun tryAutoBackup(ctx: Context, scope: LifecycleCoroutineScope) {
        val account = GoogleSignIn.getLastSignedInAccount(ctx) ?: return

        val json = DataRepository.exportJson(ctx)

        scope.launch(Dispatchers.IO) {
            try {
                val credential = GoogleAccountCredential.usingOAuth2(
                    ctx, listOf(DriveScopes.DRIVE_APPDATA)
                ).also { it.selectedAccount = account.account }

                val drive = Drive.Builder(
                    NetHttpTransport(),
                    GsonFactory.getDefaultInstance(),
                    credential
                ).setApplicationName(ctx.getString(R.string.app_name)).build()

                // Delete old backup
                drive.files().list()
                    .setSpaces("appDataFolder")
                    .setQ("name='$BACKUP_FILENAME'")
                    .execute()
                    .files
                    .forEach { drive.files().delete(it.id).execute() }

                // Upload new backup
                val meta    = File().setName(BACKUP_FILENAME).setParents(listOf("appDataFolder"))
                val content = ByteArrayContent("application/json", json.toByteArray(Charsets.UTF_8))
                drive.files().create(meta, content).execute()

                // Persist timestamp
                withContext(Dispatchers.Main) {
                    DataRepository.saveLastBackupMs(ctx, System.currentTimeMillis())
                }
            } catch (_: Exception) {
                // Auto-backup is best-effort – never disturb the user
            }
        }
    }
}
