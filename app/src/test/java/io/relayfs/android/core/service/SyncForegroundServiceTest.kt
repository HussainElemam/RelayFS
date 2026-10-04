package io.relayfs.android.core.service

import android.app.NotificationManager
import android.content.Context
import android.content.ComponentName
import android.content.Intent
import androidx.room.MultiInstanceInvalidationService
import androidx.test.core.app.ApplicationProvider
import io.relayfs.android.core.model.CloudAccount
import io.relayfs.android.core.model.CloudItem
import io.relayfs.android.core.model.DeltaChanges
import io.relayfs.android.core.model.ProviderType
import io.relayfs.android.core.provider.CloudProvider
import io.relayfs.android.core.provider.CloudProviderFactory
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncForegroundServiceTest {

    private lateinit var context: Context
    private lateinit var invalidationService: ServiceController<MultiInstanceInvalidationService>

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        // Supply Room's actual binder because Robolectric does not create bound services.
        invalidationService = Robolectric.buildService(MultiInstanceInvalidationService::class.java).create()
        val serviceIntent = Intent(context, MultiInstanceInvalidationService::class.java)
        shadowOf(context as android.app.Application).setComponentNameAndServiceForBindServiceForIntent(
            serviceIntent,
            ComponentName(context, MultiInstanceInvalidationService::class.java),
            invalidationService.get().onBind(serviceIntent)
        )
        SyncForegroundService.customCloudProviderFactory = object : CloudProviderFactory {
            override suspend fun getProvider(accountId: String): CloudProvider = FakeServiceCloudProvider()
        }
    }

    @After
    fun tearDown() {
        SyncForegroundService.customCloudProviderFactory = null
        invalidationService.destroy()
    }

    @Test
    fun testServiceLifecycleAndStartSync() {
        val serviceController = Robolectric.buildService(SyncForegroundService::class.java)
        val service = serviceController.create().get()
        assertNotNull(service)

        val intent = Intent(context, SyncForegroundService::class.java).apply {
            action = SyncForegroundService.ACTION_START_SYNC
            putExtra(SyncForegroundService.EXTRA_FOLDER_PAIR_ID, "test-pair")
        }

        service.onStartCommand(intent, 0, 1)

        // Verify notification channels are registered
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        assertNotNull(notificationManager.getNotificationChannel(SyncNotificationManager.CHANNEL_SYNC_STATUS))

        serviceController.destroy()
    }

    @Test
    fun testStartHelperMethods() {
        // Verify static launcher methods enqueue proper intents
        SyncForegroundService.startImmediateSync(context, "pair-abc")
        val nextServiceIntent = shadowOf(context as android.app.Application).nextStartedService
        assertNotNull(nextServiceIntent)
        assertTrue(nextServiceIntent.action == SyncForegroundService.ACTION_START_SYNC)
        assertTrue(nextServiceIntent.getStringExtra(SyncForegroundService.EXTRA_FOLDER_PAIR_ID) == "pair-abc")
    }

    private class FakeServiceCloudProvider : CloudProvider {
        override val accountId: String = "acc"
        override val providerType: ProviderType = ProviderType.PCLOUD
        override suspend fun verifyConnection(): Result<CloudAccount> = Result.success(
            CloudAccount("acc", ProviderType.PCLOUD, "a@b.com", "U", "url", 0L)
        )
        override suspend fun listFolder(remoteFolderId: String, recursive: Boolean): Result<List<CloudItem>> =
            Result.success(emptyList())
        override suspend fun getItemMetadata(remoteId: String): Result<CloudItem> = Result.failure(Exception())
        override fun supportsDeltaSync(): Boolean = false
        override suspend fun getDeltaChanges(sinceToken: String?): Result<DeltaChanges> =
            Result.success(DeltaChanges("1", emptyList(), false))
        override suspend fun downloadFile(
            remoteId: String,
            destinationFile: File,
            progress: ((bytesRead: Long, totalBytes: Long) -> Unit)?
        ): Result<Unit> = Result.success(Unit)
        override suspend fun uploadFile(
            parentFolderId: String,
            file: File,
            remoteName: String,
            progress: ((bytesWritten: Long, totalBytes: Long) -> Unit)?
        ): Result<CloudItem> = Result.failure(Exception())
        override suspend fun createFolder(parentFolderId: String, folderName: String): Result<CloudItem> =
            Result.failure(Exception())
        override suspend fun deleteItem(remoteId: String, isDirectory: Boolean): Result<Unit> = Result.success(Unit)
        override suspend fun moveItem(remoteId: String, newParentFolderId: String, newName: String): Result<CloudItem> =
            Result.failure(Exception())
    }
}
