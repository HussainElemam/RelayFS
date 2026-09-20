package io.relayfs.android.core.work

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

/**
 * Worker responsible for uploading modified or newly created files from the
 * Storage Access Framework (SAF) DocumentsProvider to remote cloud storage.
 */
class CloudUploadWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val documentId = inputData.getString(KEY_DOCUMENT_ID) ?: return Result.failure()
        val localFilePath = inputData.getString(KEY_LOCAL_FILE_PATH) ?: return Result.failure()
        val localFile = File(localFilePath)
        if (!localFile.exists()) {
            return Result.failure()
        }

        val accountId = inputData.getString(KEY_ACCOUNT_ID) ?: AccountsViewModel.DEFAULT_ACCOUNT_ID
        val database = customDatabase ?: AppDatabase.build(applicationContext)
        val providerFactory = customCloudProviderFactory ?: DefaultCloudProviderFactory(
            EncryptedSharedPrefsTokenStorage(applicationContext)
        )

        val cloudProvider = providerFactory.getProvider(accountId)
            ?: return Result.failure()

        val safDao = database.safMetadataDao()
        val cachedEntity = safDao.getById(documentId)

        val parentFolderId = inputData.getString(KEY_PARENT_FOLDER_ID)
            ?: cachedEntity?.parentDocumentId
            ?: "d0"
        val remoteName = inputData.getString(KEY_REMOTE_NAME)
            ?: cachedEntity?.displayName
            ?: localFile.name

        return try {
            val uploadResult = cloudProvider.uploadFile(
                parentFolderId = parentFolderId,
                file = localFile,
                remoteName = remoteName
            )

            if (uploadResult.isSuccess) {
                val cloudItem = uploadResult.getOrThrow()
                val updatedEntity = (cachedEntity ?: SafMetadataEntity(
                    documentId = cloudItem.id,
                    parentDocumentId = parentFolderId,
                    accountId = accountId,
                    displayName = cloudItem.name,
                    mimeType = cloudItem.mimeType ?: "application/octet-stream",
                    isDirectory = false
                )).copy(
                    documentId = cloudItem.id,
                    displayName = cloudItem.name,
                    sizeBytes = cloudItem.sizeBytes,
                    lastModified = cloudItem.modifiedTimeMillis,
                    cachedAt = System.currentTimeMillis()
                )

                safDao.insertOrUpdate(updatedEntity)

                // Dispatch notification to DocumentsUI
                val authority = "${applicationContext.packageName}.documents"
                val docUri = DocumentsContract.buildDocumentUri(authority, updatedEntity.documentId)
                val parentUri = DocumentsContract.buildChildDocumentsUri(authority, parentFolderId)
                applicationContext.contentResolver.notifyChange(docUri, null)
                applicationContext.contentResolver.notifyChange(parentUri, null)

                runCatching { deleteSafStagingFile(localFile) }

                Result.success()
            } else {
                val error = uploadResult.exceptionOrNull()
                if (error is IOException) {
                    Result.retry()
                } else {
                    Result.failure()
                }
            }
        } catch (e: Exception) {
            if (e is IOException) {
                Result.retry()
            } else {
                Result.failure()
            }
        }
    }

    private fun deleteSafStagingFile(file: File) {
        val stagingRoot = File(applicationContext.cacheDir, "saf_cache/staging").canonicalFile
        val candidate = file.canonicalFile
        if (candidate.parentFile == stagingRoot) {
            candidate.delete()
        }
    }

    companion object {
        const val KEY_DOCUMENT_ID = "key_document_id"
        const val KEY_LOCAL_FILE_PATH = "key_local_file_path"
        const val KEY_PARENT_FOLDER_ID = "key_parent_folder_id"
        const val KEY_REMOTE_NAME = "key_remote_name"
        const val KEY_ACCOUNT_ID = "key_account_id"

        @Volatile
        var customDatabase: AppDatabase? = null

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
