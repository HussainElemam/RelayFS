package io.relayfs.android.audit

import android.content.Context
import android.content.pm.ProviderInfo
import androidx.test.core.app.ApplicationProvider
import io.relayfs.android.core.database.AppDatabase
import io.relayfs.android.core.database.entity.FolderPairEntity
import io.relayfs.android.core.database.entity.SafMetadataEntity
import io.relayfs.android.core.database.entity.SyncBaselineEntity
import io.relayfs.android.core.model.*
import io.relayfs.android.core.provider.CloudProvider
import io.relayfs.android.core.provider.CloudProviderFactory
import io.relayfs.android.core.provider.pcloud.PCloudProvider
import io.relayfs.android.core.provider.pcloud.api.PCloudApiClient
import io.relayfs.android.core.provider.pcloud.api.PCloudItemMetadata
import io.relayfs.android.core.saf.CloudDocumentsProvider
import io.relayfs.android.core.security.TokenStorage
import io.relayfs.android.core.sync.engine.*
import io.relayfs.android.core.sync.hash.FastHasher
import io.relayfs.android.core.sync.io.AtomicFileWriter
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.TimeUnit

/** Regressions reproduced during the final data-safety audit. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RealDataSafetyAuditTest {
    @get:Rule val temp = TemporaryFolder()
    private lateinit var db: AppDatabase
    private lateinit var cloud: AuditCloud
    private lateinit var note: File
    private lateinit var baseline: BaselineItemSnapshot
    private val pairId = "audit-pair"

    @Before fun setup() = runBlocking {
        db = AppDatabase.createInMemory(ApplicationProvider.getApplicationContext<Context>())
        cloud = AuditCloud()
        note = temp.newFile("note.md").apply { writeText("original") }
        db.folderPairDao().insert(FolderPairEntity(pairId, "pcloud_default", "Audit", temp.root.path, "d10", "/Vault"))
        baseline = BaselineItemSnapshot("note.md", "f1", note.lastModified(), 1000L, note.length(), note.length(), FastHasher.hashFile(note), remoteContentHash = "provider-hash")
        db.syncBaselineDao().upsert(SyncBaselineEntity(pairId, "note.md", "f1", baseline.localModifiedTimeMillis, 1000L, note.length(), note.length(), baseline.contentHash, remoteContentHash = "provider-hash"))
    }

    @After fun teardown() {
        CloudDocumentsProvider.customDatabase = null
        CloudDocumentsProvider.customCloudProviderFactory = null
        CloudDocumentsProvider.customTokenStorage = null
        CloudDocumentsProvider.customCacheDir = null
        db.close()
    }

    private fun local() = LocalItemSnapshot("note.md", note, note.length(), note.lastModified(), FastHasher.hashFile(note))
    private fun remote(mtime: Long = 1000L) = RemoteItemSnapshot("note.md", "f1", 8L, mtime, if (mtime == 1000L) "provider-hash" else "changed-hash")
    private fun plan(l: LocalItemSnapshot?, r: RemoteItemSnapshot?) = ReconciliationPlanner.plan(
        pairId, temp.root, "d10", l?.let { mapOf("note.md" to it) } ?: emptyMap(),
        r?.let { mapOf("note.md" to it) } ?: emptyMap(), mapOf("note.md" to baseline)
    )
    private suspend fun execute(actions: List<SyncAction>) = SyncExecutionPipeline(db, cloud).execute(pairId, temp.root, "d10", actions)
    private fun factory(provider: CloudProvider) = object : CloudProviderFactory {
        override suspend fun getProvider(accountId: String) = provider
    }

    @Test fun missingEstablishedRemoteRootMustNotDeleteLocalData() = runBlocking {
        // The persisted d10 folder was renamed or removed. The old path is now absent.
        val failure = try { SyncCoordinator(db, factory(cloud)).syncFolderPair(pairId); null } catch (e: java.io.IOException) { e }
        assertNotNull(failure)
        assertTrue(note.exists())
        assertEquals(0, cloud.createdFolders)
    }

    @Test fun renamedEstablishedFolderKeepsItsIdentityAndFiles() = runBlocking {
        cloud.establishedAvailable = true
        cloud.content = "original"
        cloud.items = listOf(CloudItem("f1", "d10", "note.md", "/note.md", false, 8, 1000, "provider-hash"))
        val result = SyncCoordinator(db, factory(cloud)).syncFolderPair(pairId)
        assertTrue(result.isSuccess); assertEquals("original", note.readText())
        assertEquals("d10", db.folderPairDao().getById(pairId)?.remoteFolderId)
        assertEquals(0, cloud.createdFolders)
        assertTrue(cloud.listedFolders.all { it == "d10" })
    }

    @Test fun replacementAtOldPathCannotRebindEstablishedFolder() = runBlocking {
        cloud.items = listOf(CloudItem("d20", "d0", "Vault", "/Vault", true, 0, 1))
        try { SyncCoordinator(db, factory(cloud)).syncFolderPair(pairId) } catch (_: java.io.IOException) {}
        assertEquals("d10", db.folderPairDao().getById(pairId)?.remoteFolderId)
        assertEquals("original", note.readText()); assertEquals(0, cloud.createdFolders)
        assertTrue(cloud.listedFolders.isEmpty())
    }

    @Test fun localEditAfterPlanningMustSurviveDownload() = runBlocking {
        val actions = plan(local(), remote(2000L))
        assertTrue(actions.single() is SyncAction.Download)
        note.writeText("new local work after scan")
        cloud.version = "changed-hash"
        execute(actions)
        assertEquals("new local work after scan", note.readText())
    }

    @Test fun localEditAfterPlanningMustSurviveDeletion() = runBlocking {
        val actions = plan(local(), null)
        assertTrue(actions.single() is SyncAction.DeleteLocal)
        note.writeText("new local work after scan")
        execute(actions)
        assertTrue("A stale delete action erased a newer local edit", note.exists())
    }

    @Test fun remoteEditAfterPlanningMustSurviveDeletion() = runBlocking {
        val actions = plan(null, remote())
        assertTrue(actions.single() is SyncAction.DeleteRemote)
        cloud.content = "new remote work after scan"
        execute(actions)
        assertFalse("Remote version was never rechecked before deletion", cloud.deleted)
    }

    @Test fun localHashChangeMustOverrideEqualSizeAndTimestamp() {
        val changed = local().copy(contentHash = "different-content-same-length-and-mtime")
        assertEquals(EndpointDelta.MODIFIED, DeltaCalculator.computeLocalDelta(changed, baseline))
    }

    @Test fun remoteHashChangeMustOverrideEqualSizeAndTimestamp() {
        val remoteBaseline = baseline.copy(remoteContentHash = "old-provider-hash")
        val changed = remote().copy(contentHash = "new-provider-hash")
        assertEquals(EndpointDelta.MODIFIED, DeltaCalculator.computeRemoteDelta(changed, remoteBaseline))
    }

    @Test fun malformedSuccessfulListingMustNotMeanAllFilesDeleted() = runBlocking {
        // Root avoids path resolution; exercise the real JSON parser, provider, coordinator and pipeline.
        db.folderPairDao().update(db.folderPairDao().getById(pairId)!!.copy(remoteFolderId = "d0", remoteFolderPath = "/"))
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("{}"))
            val provider = PCloudProvider("pcloud_default", PCloudApiClient(server.url("/").toString(), { null }))
            assertTrue(provider.listFolder("d0", true).isFailure)
            assertTrue(note.exists())
        } finally { server.shutdown() }
    }

    @Test fun validPCloudUnsignedHashMustDeserialize() {
        // Value appears in pCloud's own uploadfile documentation.
        val client = PCloudApiClient(tokenProvider = { null })
        val decoded = runCatching { client.json.decodeFromString<PCloudItemMetadata>("""{"fileid":1729212,"hash":10681749967730527559,"name":"photo.jpg"}""") }
        assertTrue("Valid pCloud hash exceeds signed Long: ${decoded.exceptionOrNull()?.message}", decoded.isSuccess)
    }

    @Test fun smallUploadsMustDisablePartialRemoteCommits() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setBody("""{"result":0,"metadata":[{"fileid":1,"name":"note.md","size":8}]}"""))
            val client = PCloudApiClient(server.url("/").toString(), { null })
            client.uploadFileSinglePart(10L, note, "note.md").getOrThrow()
            val request = server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("1", request.requestUrl!!.queryParameter("nopartial"))
        } finally { server.shutdown() }
    }

    @Test fun downloadHostMustNotReceiveAccountBearerToken() = runBlocking {
        val cdn = MockWebServer()
        cdn.start()
        try {
            cdn.enqueue(MockResponse().setBody("download"))
            val client = PCloudApiClient(cdn.url("/").toString(), tokenProvider = { "audit-fake-secret" })
            client.downloadFileStream(cdn.url("/signed-file-link").toString(), File(temp.root, "download.md")).getOrThrow()
            val request = cdn.takeRequest(5, TimeUnit.SECONDS)!!
            assertNull("Bearer credential is attached to every download host", request.getHeader("Authorization"))
        } finally { cdn.shutdown() }
    }

    @Test fun occupiedConflictFilenameMustNeverBeOverwritten() = runBlocking {
        val branch = File(temp.root, ConflictResolver.generateConflictFilename(note.name, 1000L))
        branch.writeText("previous unique conflict edits")
        execute(listOf(SyncAction.ConflictBranch("note.md", local(), remote(2000L), branch.name, branch)))
        assertEquals("previous unique conflict edits", branch.readText())
    }

    @Test fun cancellationMustStopSubsequentDestructiveActions() = runBlocking {
        val victim = temp.newFile("second.md").apply { writeText("keep me") }
        cloud.cancelUpload = true
        var cancelled = false
        try { execute(listOf(SyncAction.Upload("note.md", local(), "d10"), SyncAction.DeleteLocal("second.md", victim))) } catch (_: CancellationException) { cancelled = true }
        assertTrue(cancelled)
        assertTrue("Pipeline swallowed cancellation and continued to delete another file", victim.exists())
    }

    @Test fun downloadSizeMismatchMustNotReplaceLiveFile() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val host = server.url("/").toString().removeSuffix("/")
            server.enqueue(MockResponse().setBody("""{"result":0,"metadata":{"fileid":1,"name":"note.md","size":100,"hash":1,"modified":"Thu, 01 Jan 1970 00:00:02 +0000"}}"""))
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"result":0,"hosts":["$host"],"path":"/download"}"""))
            server.enqueue(MockResponse().setBody("oops"))
            val provider = PCloudProvider("pcloud_default", PCloudApiClient(server.url("/").toString(), { null }))
            val result = SyncExecutionPipeline(db, provider).execute(pairId, temp.root, "d10",
                listOf(SyncAction.Download("note.md", remote(2000L).copy(sizeBytes = 100L, contentHash = "1"), note)))
            assertFalse("Real pCloud download accepted 4 bytes although snapshot advertised 100; live content=${note.readText()}", result.isSuccess)
        } finally { server.shutdown() }
    }

    private fun saf(cache: File): CloudDocumentsProvider {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        CloudDocumentsProvider.customDatabase = db
        CloudDocumentsProvider.customCloudProviderFactory = factory(cloud)
        CloudDocumentsProvider.customCacheDir = cache
        CloudDocumentsProvider.customTokenStorage = object : TokenStorage {
            override suspend fun saveToken(accountId: String, token: String) {}
            override suspend fun getToken(accountId: String) = "audit-token"
            override suspend fun deleteToken(accountId: String) {}
            override suspend fun hasToken(accountId: String) = true
        }
        return CloudDocumentsProvider().apply { attachInfo(ctx, ProviderInfo().apply {
            authority = "${ctx.packageName}.documents"
            exported = true
            grantUriPermissions = true
            readPermission = android.Manifest.permission.MANAGE_DOCUMENTS
            writePermission = android.Manifest.permission.MANAGE_DOCUMENTS
        }) }
    }

    @Test fun refreshedSafMetadataMustInvalidateOldReadContent() = runBlocking {
        val cache = temp.newFolder("saf_cache")
        val provider = saf(cache)
        db.safMetadataDao().insertOrUpdate(SafMetadataEntity("f1", "d0", "pcloud_default", "note.md", "text/plain", 8L, 1000L))
        cloud.content = "original"
        cloud.version = "original"
        provider.openDocument("f1", "r", null).close()
        cloud.content = "updated!"
        cloud.items = listOf(CloudItem("f1", "d0", "note.md", "/note.md", false, 8L, 2000L))
        provider.refreshRemoteFolder("d0", "${ApplicationProvider.getApplicationContext<Context>().packageName}.documents")
        provider.openDocument("f1", "r", null).close()
        assertEquals("updated!", File(cache, "read/f1").readText())
    }

    @Test fun completeSafRefreshMustRemoveDeletedChildren() = runBlocking {
        val provider = saf(temp.newFolder("saf_cache"))
        db.safMetadataDao().insertOrUpdate(SafMetadataEntity("f1", "d0", "pcloud_default", "deleted.md", "text/plain"))
        cloud.items = emptyList()
        provider.refreshRemoteFolder("d0", "${ApplicationProvider.getApplicationContext<Context>().packageName}.documents")
        assertTrue("Deleted cloud documents remain browsable", db.safMetadataDao().getChildren("d0").isEmpty())
    }

    private class AuditCloud : CloudProvider {
        override val providerType = ProviderType.PCLOUD
        override val accountId = "pcloud_default"
        var content = "remote!!"
        var deleted = false
        var cancelUpload = false
        var items = emptyList<CloudItem>()
        var version = "provider-hash"
        var createdFolders = 0
        var establishedAvailable = false
        val listedFolders = mutableListOf<String>()
        override suspend fun preserveFile(remoteId: String) = Result.success(Unit)
        override suspend fun uploadNewFile(parentFolderId: String, file: File, remoteName: String) = uploadFile(parentFolderId, file, remoteName, null)
        override suspend fun verifyConnection() = Result.failure<CloudAccount>(UnsupportedOperationException())
        override suspend fun listFolder(remoteFolderId: String, recursive: Boolean): Result<List<CloudItem>> { listedFolders += remoteFolderId; return Result.success(items) }
        override suspend fun getItemMetadata(remoteId: String): Result<CloudItem> = if (remoteId.startsWith("d")) { if (establishedAvailable) Result.success(CloudItem(remoteId, "d0", "RenamedVault", "/RenamedVault", true, 0, 1)) else Result.failure(java.io.FileNotFoundException("Missing established folder")) }
            else Result.success(items.firstOrNull { it.id == remoteId } ?: CloudItem(remoteId, "d10", "note.md", "/note.md", false, content.length.toLong(), 1000L,
                contentHash = if (content == "new remote work after scan") "after-scan-hash" else version))
        override fun supportsDeltaSync() = false
        override suspend fun getDeltaChanges(sinceToken: String?) = Result.failure<DeltaChanges>(UnsupportedOperationException())
        override suspend fun createFolder(parentFolderId: String, folderName: String): Result<CloudItem> { createdFolders++; return Result.success(CloudItem("d20", parentFolderId, folderName, "/$folderName", true, 0L, 1L)) }
        override suspend fun downloadFile(remoteId: String, destinationFile: File, progress: ((Long, Long) -> Unit)?): Result<Unit> {
            // Atomic replacement reproduces the active pCloud provider's behavior.
            AtomicFileWriter.writeText(destinationFile, content)
            return Result.success(Unit)
        }
        override suspend fun uploadFile(parentFolderId: String, file: File, remoteName: String, progress: ((Long, Long) -> Unit)?): Result<CloudItem> {
            if (cancelUpload) throw CancellationException("audit cancellation")
            return Result.success(CloudItem("f1", parentFolderId, remoteName, "/$remoteName", false, file.length(), 1000L))
        }
        override suspend fun moveItem(remoteId: String, newParentFolderId: String, newName: String) = Result.failure<CloudItem>(UnsupportedOperationException())
        override suspend fun deleteItem(remoteId: String, isDirectory: Boolean): Result<Unit> {
            deleted = true
            return Result.success(Unit)
        }
    }
}
