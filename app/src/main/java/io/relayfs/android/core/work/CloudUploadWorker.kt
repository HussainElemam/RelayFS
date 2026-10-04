package io.relayfs.android.core.work

import androidx.room.withTransaction
import android.content.Context
import android.provider.DocumentsContract
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.relayfs.android.core.database.AppDatabase
import io.relayfs.android.core.database.entity.SafMetadataEntity
import io.relayfs.android.core.provider.CloudProviderFactory
import io.relayfs.android.core.provider.DefaultCloudProviderFactory
import io.relayfs.android.core.security.EncryptedSharedPrefsTokenStorage
import io.relayfs.android.ui.accounts.AccountsViewModel
import java.io.File
import java.io.IOException
import kotlinx.coroutines.ensureActive

/**
 * Worker responsible for uploading modified or newly created files from the
 * Storage Access Framework (SAF) DocumentsProvider to remote cloud storage.
 */
class CloudUploadWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = io.relayfs.android.core.sync.engine.SyncSafety.exclusive {
        val database = customDatabase ?: AppDatabase.build(applicationContext)
        try {
            val dao = database.pendingUploadDao()
            val rowId = inputData.getString(KEY_PENDING_ID)
            var row = rowId?.let { dao.get(it) }
            if (row == null && rowId == null) {
                // Upgrade legacy queued jobs into the durable journal before attempting any network write.
                val doc = inputData.getString(KEY_DOCUMENT_ID) ?: return@exclusive Result.failure()
                val path = inputData.getString(KEY_LOCAL_FILE_PATH) ?: return@exclusive Result.failure()
                val file = File(path)
                if (!file.exists()) return@exclusive Result.failure()
                val meta = database.safMetadataDao().getById(doc) ?: return@exclusive Result.failure()
                row = io.relayfs.android.core.saf.PendingEdits.begin(applicationContext, database, meta, null, file)
                dao.setState(row.id, "READY")
                row = row.copy(state = "READY")
            }
            val edit = row ?: return@exclusive Result.success()
            if (edit.state != "READY") return@exclusive Result.failure()
            val file = File(edit.localPath)
            if (!file.isFile) {
                dao.setState(edit.id, "ERROR", "Pending edit is missing. Restore or export another copy.")
                return@exclusive Result.failure()
            }
            val storage = customTokenStorage ?: EncryptedSharedPrefsTokenStorage.create(applicationContext)
            val factory = customCloudProviderFactory ?: DefaultCloudProviderFactory(storage)
            val provider = factory.getProvider(edit.accountId)
            if (provider == null) {
                dao.setState(edit.id, "ERROR", "Reconnect the same cloud account, then retry. Your edit is retained.")
                return@exclusive Result.failure()
            }
            if ((customCloudProviderFactory == null || customTokenStorage != null) && (edit.accountIdentity == null || edit.accountIdentity != storage.getIdentity(edit.accountId))) {
                dao.setState(edit.id, "ERROR", "Account identity does not match this edit. Export it to recover your work.")
                return@exclusive Result.failure()
            }
            val expected = io.relayfs.android.core.sync.engine.RemoteItemSnapshot(edit.remoteName, edit.documentId,
                edit.expectedSize, edit.expectedModified, edit.expectedHash)
            val current = provider.getItemMetadata(edit.documentId)
            val absent = current.exceptionOrNull() is java.io.FileNotFoundException
            if (current.isFailure && !absent) current.getOrThrow()
            var changed = absent || !io.relayfs.android.core.sync.engine.FileSafety.remoteMatches(current.getOrThrow(), expected)
            if (!changed) {
                provider.preserveFile(edit.documentId).getOrThrow()
                val afterBackup = provider.getItemMetadata(edit.documentId).getOrThrow()
                changed = !io.relayfs.android.core.sync.engine.FileSafety.remoteMatches(afterBackup, expected)
            }
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val name = if (changed) io.relayfs.android.core.sync.engine.ConflictResolver.generateConflictFilename(edit.remoteName) else edit.remoteName
            val item = (if (changed) provider.uploadNewFile(edit.parentFolderId, file, name)
                else provider.uploadFile(edit.parentFolderId, file, name)).getOrThrow()
            if (item.sizeBytes != file.length()) throw IOException("Cloud upload size mismatch; local edit retained")
            database.withTransaction {
            database.safMetadataDao().insertOrUpdate(
                io.relayfs.android.core.saf.CloudDocumentsProvider.run {
                    item.toSafEntity(edit.parentFolderId, edit.accountId)
                })
            database.pendingUploadDao().delete(edit.id)
            }
            file.delete()
            File(applicationContext.cacheDir, "saf_cache/read/${edit.documentId}").delete()
            val authority = "${applicationContext.packageName}.documents"
            applicationContext.contentResolver.notifyChange(DocumentsContract.buildChildDocumentsUri(authority, edit.parentFolderId), null)
            Result.success()
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) {
            val id = inputData.getString(KEY_PENDING_ID)
            if (id != null) database.pendingUploadDao().setState(id, if (e is IOException) "READY" else "ERROR", e.message)
            if (e is IOException) Result.retry() else Result.failure()
        } finally { if (customDatabase == null) database.close() }
    }

    companion object {
        const val KEY_PENDING_ID = "pending_id"
        const val TAG_UPLOAD = "relayfs_saf_upload"
        fun enqueuePending(context: Context, pendingId: String, accountId: String, documentId: String) {
            val request = OneTimeWorkRequestBuilder<CloudUploadWorker>()
                .setInputData(workDataOf(KEY_PENDING_ID to pendingId))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .addTag(TAG_UPLOAD).build()
            WorkManager.getInstance(context).enqueueUniqueWork("saf_pending_$pendingId", ExistingWorkPolicy.KEEP, request)
        }

        const val KEY_DOCUMENT_ID = "key_document_id"
        const val KEY_LOCAL_FILE_PATH = "key_local_file_path"
        const val KEY_PARENT_FOLDER_ID = "key_parent_folder_id"
        const val KEY_REMOTE_NAME = "key_remote_name"
        const val KEY_ACCOUNT_ID = "key_account_id"

        @Volatile
        var customDatabase: AppDatabase? = null

        @Volatile
        var customTokenStorage: io.relayfs.android.core.security.TokenStorage? = null

        @Volatile
        var customCloudProviderFactory: CloudProviderFactory? = null

        fun enqueueUpload(
            context: Context,
            documentId: String,
            file: File,
            parentFolderId: String? = null,
            remoteName: String? = null,
            accountId: String = AccountsViewModel.DEFAULT_ACCOUNT_ID
        ) {
            val workRequest = OneTimeWorkRequestBuilder<CloudUploadWorker>()
                .setInputData(
                    workDataOf(
                        KEY_DOCUMENT_ID to documentId,
                        KEY_LOCAL_FILE_PATH to file.absolutePath,
                        KEY_PARENT_FOLDER_ID to (parentFolderId ?: "d0"),
                        KEY_REMOTE_NAME to (remoteName ?: file.name),
                        KEY_ACCOUNT_ID to accountId
                    )
                )
                .addTag(TAG_UPLOAD)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                "saf_upload_${accountId}_$documentId",
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                workRequest
            )
        }
    }
}
