package io.relayfs.android.core.security

import android.content.Context
import androidx.work.WorkManager
import io.relayfs.android.core.database.AppDatabase
import io.relayfs.android.core.provider.pcloud.api.PCloudApiClient
import io.relayfs.android.core.provider.pcloud.api.PCloudUserInfoResponse
import io.relayfs.android.core.sync.engine.SyncSafety
import io.relayfs.android.core.work.CloudUploadWorker
import io.relayfs.android.core.work.SyncWorker
import io.relayfs.android.ui.accounts.AccountsViewModel
import java.io.File
import java.io.IOException
import kotlinx.coroutines.flow.first

class AccountSessionManager(context: Context, private val database: AppDatabase,
    private val storage: TokenStorage) {
    private val context = context.applicationContext
    suspend fun connect(token: String, location: Int) = SyncSafety.exclusive {
        require(location == 1 || location == 2) { "Unknown pCloud datacenter" }
        val endpoint = PCloudApiClient.getBaseUrlForLocation(location)
        val response = PCloudApiClient(endpoint, { token }).apiService.getUserInfo()
        val info = response.body()
        if (!response.isSuccessful || info == null || info.result != 0 || info.userid <= 0) throw IOException("Could not verify cloud account")
        acceptVerifiedLocked(token, endpoint, info)
    }

    /** Called only with identity obtained from the authenticated userinfo endpoint. */
    suspend fun acceptVerified(token: String, endpoint: String, info: PCloudUserInfoResponse) = SyncSafety.exclusive {
        acceptVerifiedLocked(token, endpoint, info)
    }

    private suspend fun acceptVerifiedLocked(token: String, endpoint: String, info: PCloudUserInfoResponse) {
        require(info.result == 0 && info.userid > 0) { "Unverified cloud identity" }
        val account = AccountsViewModel.DEFAULT_ACCOUNT_ID
        val identity = "$endpoint#${info.userid}"
        val old = storage.getIdentity(account)
        val pairs = database.folderPairDao().getAllFlow().first()
        val pending = database.pendingUploadDao().getAll()
        if (old != identity && (pairs.isNotEmpty() || pending.isNotEmpty())) {
            throw IOException("Account identity changed or could not be verified. Remove existing folder pairs and export pending edits before connecting a different account.")
        }
        if (old != identity) {
            database.safMetadataDao().clearAll()
            File(context.cacheDir, "saf_cache").deleteRecursively()
            context.revokeUriPermission(android.net.Uri.parse("content://${context.packageName}.documents"), android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            context.contentResolver.persistedUriPermissions.forEach { permission ->
                if (permission.uri.authority == "${context.packageName}.documents") {
                    runCatching { context.revokeUriPermission(permission.uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
                }
            }
        }
        storage.saveSession(account, token, endpoint, identity)
        val scheduler = io.relayfs.android.core.work.scheduler.SyncScheduler(context)
        pairs.filter { it.isEnabled }.forEach { scheduler.schedulePeriodicSync(it) }
        context.contentResolver.notifyChange(android.provider.DocumentsContract.buildRootsUri("${context.packageName}.documents"), null)
    }

    suspend fun disconnect() {
        context.stopService(android.content.Intent(context, io.relayfs.android.core.service.SyncForegroundService::class.java))
        WorkManager.getInstance(context).cancelAllWorkByTag(SyncWorker.TAG_SYNC)
        WorkManager.getInstance(context).cancelAllWorkByTag(CloudUploadWorker.TAG_UPLOAD)
        SyncSafety.exclusive { storage.deleteToken(AccountsViewModel.DEFAULT_ACCOUNT_ID) }
        context.contentResolver.notifyChange(android.provider.DocumentsContract.buildRootsUri("${context.packageName}.documents"), null)
    }
}
