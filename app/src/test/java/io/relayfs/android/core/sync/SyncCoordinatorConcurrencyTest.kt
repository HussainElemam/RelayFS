package io.relayfs.android.core.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.relayfs.android.core.database.AppDatabase
import io.relayfs.android.core.database.entity.FolderPairEntity
import io.relayfs.android.core.model.CloudAccount
import io.relayfs.android.core.model.CloudItem
import io.relayfs.android.core.model.DeltaChanges
import io.relayfs.android.core.model.ProviderType
import io.relayfs.android.core.provider.CloudProvider
import io.relayfs.android.core.provider.CloudProviderFactory
import io.relayfs.android.core.sync.engine.SyncCoordinator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncCoordinatorConcurrencyTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var database: AppDatabase
    private lateinit var provider: ConcurrentTrackingProvider
    private val pairId = "serialized-pair"

    @Before
    fun setup() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = AppDatabase.createInMemory(context)
        provider = ConcurrentTrackingProvider()
        database.folderPairDao().insert(
            FolderPairEntity(
                id = pairId,
                accountId = "account",
                name = "Serialized Pair",
                localPath = tempFolder.root.absolutePath,
                remoteFolderId = "d10",
                remoteFolderPath = "/Vault"
            )
        )
    }

    @After
    fun tearDown() {
        if (::database.isInitialized) database.close()
    }

    @Test
    fun syncPassesForSamePairAreSerializedAcrossCoordinatorInstances() = runBlocking {
        val factory = object : CloudProviderFactory {
            override suspend fun getProvider(accountId: String): CloudProvider = provider
        }
        val firstCoordinator = SyncCoordinator(database, factory)
        val secondCoordinator = SyncCoordinator(database, factory)

        val first = async(Dispatchers.Default) { firstCoordinator.syncFolderPair(pairId) }
        provider.firstCallEntered.await()
        val second = async(Dispatchers.Default) { secondCoordinator.syncFolderPair(pairId) }
        delay(50)
        provider.releaseCalls.complete(Unit)

        awaitAll(first, second)

        assertEquals(2, provider.listCallCount.get())
        assertEquals(1, provider.maxConcurrentCalls.get())
    }

    @Test
    fun missingLocalRootFailsWithoutCreatingItOrContactingCloud() = runBlocking {
        val missingRoot = File(tempFolder.root, "disconnected-storage")
        val missingPairId = "missing-root-pair"
        database.folderPairDao().insert(
            FolderPairEntity(
                id = missingPairId,
                accountId = "account",
                name = "Missing Root",
                localPath = missingRoot.absolutePath,
                remoteFolderId = "d10",
                remoteFolderPath = "/Vault"
            )
        )
        val factory = object : CloudProviderFactory {
            override suspend fun getProvider(accountId: String): CloudProvider = provider
        }

        val result = SyncCoordinator(database, factory).syncFolderPair(missingPairId)

        assertFalse(result.isSuccess)
        assertFalse(missingRoot.exists())
        assertEquals(0, provider.listCallCount.get())
    }

    private class ConcurrentTrackingProvider : CloudProvider {
        override val providerType = ProviderType.PCLOUD
        override val accountId = "account"
        val firstCallEntered = CompletableDeferred<Unit>()
        val releaseCalls = CompletableDeferred<Unit>()
        val listCallCount = AtomicInteger()
        val maxConcurrentCalls = AtomicInteger()
        private val activeCalls = AtomicInteger()

        override suspend fun verifyConnection() = Result.success(
            CloudAccount(accountId, providerType, "test@example.com", "test", "https://api.pcloud.com")
        )

        override suspend fun listFolder(
            remoteFolderId: String,
            recursive: Boolean
        ): Result<List<CloudItem>> {
            listCallCount.incrementAndGet()
            val active = activeCalls.incrementAndGet()
            maxConcurrentCalls.updateAndGet { current -> maxOf(current, active) }
            firstCallEntered.complete(Unit)
            releaseCalls.await()
            activeCalls.decrementAndGet()
            return Result.success(emptyList())
        }

        override suspend fun getItemMetadata(remoteId: String) =
            Result.success(CloudItem(remoteId, "d0", "Vault", "/Vault", true, 0L, 1000L))

        override fun supportsDeltaSync() = false

        override suspend fun getDeltaChanges(sinceToken: String?) =
            Result.success(DeltaChanges("0", emptyList(), false))

        override suspend fun downloadFile(
            remoteId: String,
            destinationFile: File,
            progress: ((Long, Long) -> Unit)?
        ) = Result.failure<Unit>(UnsupportedOperationException())

        override suspend fun uploadFile(
            parentFolderId: String,
            file: File,
            remoteName: String,
            progress: ((Long, Long) -> Unit)?
        ) = Result.failure<CloudItem>(UnsupportedOperationException())

        override suspend fun createFolder(parentFolderId: String, folderName: String) =
            Result.failure<CloudItem>(UnsupportedOperationException())

        override suspend fun moveItem(
            remoteId: String,
            newParentFolderId: String,
            newName: String
        ) = Result.failure<CloudItem>(UnsupportedOperationException())

        override suspend fun deleteItem(remoteId: String, isDirectory: Boolean) =
            Result.failure<Unit>(UnsupportedOperationException())
    }
}
