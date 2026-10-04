package io.relayfs.android.audit

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import io.relayfs.android.core.database.AppDatabase
import io.relayfs.android.core.database.entity.*
import io.relayfs.android.core.model.*
import io.relayfs.android.core.provider.*
import io.relayfs.android.core.saf.PendingEdits
import io.relayfs.android.core.security.*
import io.relayfs.android.core.provider.pcloud.api.PCloudUserInfoResponse
import io.relayfs.android.core.sync.engine.*
import io.relayfs.android.core.sync.hash.FastHasher
import io.relayfs.android.core.work.CloudUploadWorker
import io.relayfs.android.ui.diff.DiffAlgorithm
import io.relayfs.android.core.watcher.filter.PathFilter
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HardeningRegressionTest {
    @get:Rule val temp = TemporaryFolder()
    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var cloud: SafetyCloud
    private lateinit var storage: MemoryStorage
    private val pair = "safety"
    @Before fun setup() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        db = AppDatabase.createInMemory(context)
        cloud = SafetyCloud()
        storage = MemoryStorage()
        db.folderPairDao().insert(FolderPairEntity(pair, "pcloud_default", "Safety", temp.root.path, "d10", "/Vault"))
    }
    @After fun teardown() {
        CloudUploadWorker.customDatabase = null
        CloudUploadWorker.customCloudProviderFactory = null
        CloudUploadWorker.customTokenStorage = null
        PendingEdits.openDescriptors.clear()
        PendingEdits.directory(context).deleteRecursively()
        db.close()
    }
    private fun local(name: String, text: String = "local work"): LocalItemSnapshot {
        val file = File(temp.root, name).apply { parentFile?.mkdirs(); writeText(text) }
        return FileSafety.snapshot(file, name)!!
    }
    private fun remote(name: String = "note.md", text: String = "cloud work", hash: String = "revision1"): RemoteItemSnapshot {
        val item = CloudItem("f1", "d10", name, "/$name", false, text.length.toLong(), 1000L, hash)
        cloud.metadata = item; cloud.content = text
        return RemoteItemSnapshot(name, item.id, item.sizeBytes, item.modifiedTimeMillis, hash)
    }
    private suspend fun execute(vararg actions: SyncAction) = SyncExecutionPipeline(db, cloud).execute(pair, temp.root, "d10", actions.toList())
    private fun factory() = object : CloudProviderFactory { override suspend fun getProvider(accountId: String) = cloud }

    @Test fun failedMovedFileDownloadLeavesOldLocalCopyAndBaseline() = runBlocking {
        val old = local("archive/old.md")
        val dest = remote("projects/new.md")
        db.syncBaselineDao().upsert(SyncBaselineEntity(pair, old.relativePath, "f1", old.modifiedTimeMillis, 1000, old.sizeBytes, dest.sizeBytes, old.contentHash))
        cloud.downloadError = IOException("offline")
        val result = execute(SyncAction.DeleteLocal(old.relativePath, old.localFile, old), SyncAction.Download(dest.relativePath, dest, File(temp.root, dest.relativePath)))
        assertFalse(result.isSuccess); assertEquals("local work", old.localFile.readText())
        assertNotNull(db.syncBaselineDao().getBaseline(pair, old.relativePath))
        assertEquals(0, result.deletedLocalCount)
    }
    @Test fun failedMovedFileUploadLeavesOldRemoteCopy() = runBlocking {
        val old = remote("archive/old.md")
        val moved = local("projects/new.md")
        cloud.uploadError = IOException("offline")
        val result = execute(SyncAction.DeleteRemote(old.relativePath, "f1", false, old), SyncAction.Upload(moved.relativePath, moved, "d10"))
        assertFalse(result.isSuccess); assertFalse(cloud.deleted)
    }
    @Test fun successfulMovedFileDownloadPrecedesDeletionAndRetainsRecovery() = runBlocking {
        val old = local("archive/old.md")
        val moved = remote("projects/new.md")
        val result = execute(SyncAction.DeleteLocal(old.relativePath, old.localFile, old), SyncAction.Download(moved.relativePath, moved, File(temp.root, moved.relativePath)))
        assertTrue(result.errorMessages.joinToString(), result.isSuccess)
        assertEquals("cloud work", File(temp.root, moved.relativePath).readText())
        assertFalse(old.localFile.exists())
        assertTrue(File(temp.root, ".relayfs-recovery").walk().filter { it.isFile && !it.name.endsWith(".path") }.any { it.readText() == "local work" })
    }
    @Test fun preserveFailureBlocksRemoteOverwriteAndDelete() = runBlocking {
        val r = remote(); val l = local("note.md")
        cloud.backupError = IOException("quota exhausted")
        assertFalse(execute(SyncAction.Upload(l.relativePath, l, "d10", r)).isSuccess)
        assertFalse(execute(SyncAction.DeleteRemote(r.relativePath, "f1", false, r)).isSuccess)
        assertTrue(cloud.uploads.isEmpty()); assertFalse(cloud.deleted)
    }
    @Test fun remoteChangedWhileCreatingBackupBlocksOverwrite() = runBlocking {
        val r = remote(); val l = local("note.md")
        cloud.afterBackup = { cloud.metadata = cloud.metadata!!.copy(contentHash = "revision2") }
        assertFalse(execute(SyncAction.Upload(l.relativePath, l, "d10", r)).isSuccess)
        assertTrue(cloud.uploads.isEmpty()); assertEquals("local work", l.localFile.readText())
    }
    @Test fun remoteChangedDuringDownloadCannotReplaceOriginal() = runBlocking {
        val r = remote(); val l = local("note.md")
        cloud.afterDownload = { cloud.metadata = cloud.metadata!!.copy(contentHash = "revision2") }
        assertFalse(execute(SyncAction.Download(r.relativePath, r, l.localFile, l)).isSuccess)
        assertEquals("local work", l.localFile.readText())
    }
    @Test fun localChangedDuringDownloadRemainsWithDownloadedRecoveryCopy() = runBlocking {
        val r = remote(); val l = local("note.md")
        cloud.afterDownload = { l.localFile.writeText("latest local edit") }
        assertFalse(execute(SyncAction.Download(r.relativePath, r, l.localFile, l)).isSuccess)
        assertEquals("latest local edit", l.localFile.readText())
        assertTrue(File(temp.root, ".relayfs-recovery").walk().filter { it.isFile && !it.name.endsWith(".path") }.any { it.readText() == "cloud work" })
    }
    @Test fun newlyAppearedLocalAndRemoteFilesAreNeverOverwritten() = runBlocking {
        val r = remote(); val l = local("note.md")
        assertFalse(execute(SyncAction.Download(r.relativePath, r, l.localFile)).isSuccess)
        assertFalse(execute(SyncAction.Upload(l.relativePath, l, "d10")).isSuccess)
        assertEquals("local work", l.localFile.readText()); assertTrue(cloud.uploads.isEmpty())
    }
    @Test fun unversionedDeletionFailsClosed() = runBlocking {
        val l = local("note.md"); remote()
        assertFalse(execute(SyncAction.DeleteLocal(l.relativePath, l.localFile)).isSuccess)
        assertFalse(execute(SyncAction.DeleteRemote("note.md", "f1", false)).isSuccess)
        assertTrue(l.localFile.exists()); assertFalse(cloud.deleted)
    }
    @Test fun massDeletionBlocksEntirePassBeforeTransferOrDelete() = runBlocking {
        repeat(10) { db.syncBaselineDao().upsert(SyncBaselineEntity(pair, "$it.md", "f$it", 0, 0, 0, 0, null)) }
        val files = (0..5).map { local("$it.md") }
        val result = SyncExecutionPipeline(db, cloud).execute(pair, temp.root, "d10", files.map { SyncAction.DeleteLocal(it.relativePath, it.localFile, it) })
        assertFalse(result.isSuccess); assertTrue(files.all { it.localFile.exists() })
        assertEquals(10, db.syncBaselineDao().getCountForFolderPair(pair))
        assertTrue(FileSafety.deletionLimitExceeded(21, 1000))
        assertFalse(FileSafety.deletionLimitExceeded(5, 10))
    }
    @Test fun malformedAndDuplicateCloudPathsFailEntireSnapshot() {
        val good = CloudItem("f1", "d10", "note.md", "/note.md", false, 1, 1)
        assertThrows(IllegalArgumentException::class.java) { SyncCoordinator.mapRemoteItems(listOf(good, good.copy(path = "/../escape")), PathFilter()) }
        assertThrows(IllegalArgumentException::class.java) { SyncCoordinator.mapRemoteItems(listOf(good, good.copy(id = "f2")), PathFilter()) }
    }
    @Test fun pathTraversalAndSymlinkEscapesAreRejectedBeforeWriting() = runBlocking {
        val outside = temp.newFolder("outside")
        val root = temp.newFolder("root")
        java.nio.file.Files.createSymbolicLink(File(root, "link").toPath(), outside.toPath())
        assertThrows(IOException::class.java) { SyncSafety.containedFile(root, "link/private.md") }
        assertThrows(IllegalArgumentException::class.java) { SyncSafety.containedFile(root, "../private.md") }
        assertTrue(outside.listFiles()!!.isEmpty())
    }
    @Test fun obsidianToggleActuallyControlsExclusionsButAlwaysProtectsRecovery() {
        assertTrue(PathFilter(obsidianMode = true).shouldExclude(".obsidian/workspace.json"))
        assertFalse(PathFilter(obsidianMode = false).shouldExclude(".obsidian/workspace.json"))
        assertTrue(PathFilter(obsidianMode = false).shouldExclude(".relayfs-recovery/saved.md"))
    }
    @Test fun opaqueCloudHashCannotBeMistakenForLocalChecksum() {
        val l = local("note.md"); val r = remote().copy(contentHash = l.contentHash)
        val actions = ReconciliationPlanner.plan(pair, temp.root, "d10", mapOf(l.relativePath to l), mapOf(r.relativePath to r), emptyMap())
        assertTrue(actions.single() is SyncAction.ConflictBranch)
    }
    @Test fun identicalBytesUseIndependentEndpointHashesWithoutCreatingConflict() = runBlocking {
        val l = local("note.md", "same bytes"); val r = remote(text = "same bytes")
        val branch = File(temp.root, "note (conflict).md")
        val result = execute(SyncAction.ConflictBranch("note.md", l, r, branch.name, branch))
        assertTrue(result.isSuccess); assertFalse(branch.exists())
        val baseline = db.syncBaselineDao().getBaseline(pair, "note.md")!!
        assertEquals(l.contentHash, baseline.contentHash); assertEquals(r.contentHash, baseline.remoteContentHash)
    }
    @Test fun localEditDuringFalseConflictValidationIsNotRecordedAsAlreadyUploaded() = runBlocking {
        val l = local("note.md", "same bytes"); val r = remote(text = "same bytes")
        cloud.afterStat = { l.localFile.writeText("newer edit") }
        val result = execute(SyncAction.ResolveFalseConflict("note.md", l, r))
        assertTrue(result.isSuccess)
        val baseline = SyncCoordinator.mapBaselineItems(db.syncBaselineDao().getAllForFolderPair(pair))["note.md"]!!
        assertEquals(l.contentHash, baseline.contentHash)
        assertEquals(EndpointDelta.MODIFIED, DeltaCalculator.computeLocalDelta(FileSafety.snapshot(l.localFile, "note.md"), baseline))
    }

    @Test fun cancellationStopsPipelineAndReleasesWriterLock() = runBlocking {
        val l = local("note.md"); val victim = local("victim.md")
        cloud.uploadError = CancellationException("cancel")
        var cancelled = false
        try { execute(SyncAction.Upload(l.relativePath, l, "d10"), SyncAction.DeleteLocal(victim.relativePath, victim.localFile, victim)) }
        catch (_: CancellationException) { cancelled = true }
        assertTrue(cancelled); assertTrue(victim.localFile.exists())
        withTimeout(1000) { SyncSafety.exclusive { assertTrue(true) } }
    }
    @Test fun interruptedConflictResolutionRestoresBothCopiesBeforeSyncScan() = runBlocking {
        val l = local("note.md", "local original"); val branch = local("branch.md", "cloud original")
        val id = "resolution"
        val conflict = SyncConflictEntity(id, pair, "note.md", l.localFile.path, branch.localFile.path, localModifiedTimeMillis = 1, remoteModifiedTimeMillis = 2)
        db.syncConflictDao().insert(conflict)
        l.localFile.renameTo(File(temp.root, ".note.md.$id.original"))
        l.localFile.writeText("partially installed cloud version")
        branch.localFile.renameTo(File(temp.root, ".branch.md.$id.resolved"))
        SyncSafety.exclusive { ConflictResolutionManager(db).recoverInterrupted(pair) }
        assertEquals("local original", l.localFile.readText()); assertEquals("cloud original", branch.localFile.readText())
        assertEquals(ConflictStatus.ACTIVE, db.syncConflictDao().getConflict(id)?.status)
    }
    @Test fun deletedBranchCanBeResolvedAndKeepCloudStoresLocalChecksum() = runBlocking {
        val l = local("note.md"); val branch = local("branch.md", "cloud original")
        val c = SyncConflictEntity("resolve", pair, "note.md", l.localFile.path, branch.localFile.path, remoteFileId = "f1", localModifiedTimeMillis = 1, remoteModifiedTimeMillis = 2, remoteContentHash = "provider-revision")
        db.syncConflictDao().insert(c)
        ConflictResolutionManager(db).keepCloud(c)
        val b = db.syncBaselineDao().getBaseline(pair, "note.md")!!
        assertEquals(FastHasher.hashFile(l.localFile), b.contentHash); assertEquals("provider-revision", b.remoteContentHash)
        val missing = c.copy(id = "missing", conflictFilePath = File(temp.root, "missing.md").path)
        db.syncConflictDao().insert(missing); ConflictResolutionManager(db).keepLocal(missing)
        assertEquals(ConflictStatus.RESOLVED_KEEP_LOCAL, db.syncConflictDao().getConflict(missing.id)?.status)
    }
    @Test fun editsToCloudConflictCopyAreUploadedAfterKeepCloudInsteadOfSilentlyAcknowledged() = runBlocking {
        val l = local("note.md"); val branch = local("branch.md", "cloud original")
        db.syncBaselineDao().upsert(SyncBaselineEntity(pair, "branch.md", null, branch.modifiedTimeMillis, 1000,
            branch.sizeBytes, branch.sizeBytes, branch.contentHash, remoteContentHash = "revision1"))
        branch.localFile.writeText("user edited the cloud branch")
        val c = SyncConflictEntity("edited-branch", pair, "note.md", l.localFile.path, branch.localFile.path,
            remoteFileId = "f1", localModifiedTimeMillis = 1, remoteModifiedTimeMillis = 1000, remoteContentHash = "revision1")
        db.syncConflictDao().insert(c)
        ConflictResolutionManager(db).keepCloud(c)
        val baseline = SyncCoordinator.mapBaselineItems(db.syncBaselineDao().getAllForFolderPair(pair))["note.md"]!!
        assertEquals("user edited the cloud branch", l.localFile.readText())
        assertEquals(EndpointDelta.MODIFIED, DeltaCalculator.computeLocalDelta(FileSafety.snapshot(l.localFile, "note.md"), baseline))
        assertNull(db.syncBaselineDao().getBaseline(pair, "branch.md"))
    }

    @Test fun pendingEditsSurviveCacheEvictionAndInterruptedClose() = runBlocking {
        val original = local("note.md")
        val meta = SafMetadataEntity("f1", "d10", "pcloud_default", "note.md", "text/plain", original.sizeBytes, 1000, contentHash = "revision1")
        val row = PendingEdits.begin(context, db, meta, storage.identity, original.localFile)
        File(row.localPath).writeText("unsent user edit")
        context.cacheDir.deleteRecursively()
        PendingEdits.recover(context, db)
        assertEquals("ERROR", db.pendingUploadDao().get(row.id)?.state)
        assertEquals("unsent user edit", File(row.localPath).readText())
        assertFalse(File(row.localPath).path.startsWith(context.cacheDir.path))
    }
    @Test fun openPendingDescriptorIsNotRecoveredAsIncomplete() = runBlocking {
        val row = pending()
        PendingEdits.openDescriptors.add(row.id)
        PendingEdits.recover(context, db)
        assertEquals("OPEN", db.pendingUploadDao().get(row.id)?.state)
        PendingEdits.closed(context, db, row, "editor reported write failure")
        assertEquals("ERROR", db.pendingUploadDao().get(row.id)?.state)
        assertTrue(File(row.localPath).exists())
    }
    private suspend fun pending(): PendingUploadEntity {
        remote()
        val l = local("note.md", "pending edit")
        return PendingEdits.begin(context, db, SafMetadataEntity("f1", "d10", "pcloud_default", "note.md", "text/plain", cloud.metadata!!.sizeBytes, 1000, contentHash = "revision1"), storage.identity, l.localFile)
    }
    private suspend fun upload(row: PendingUploadEntity): ListenableWorker.Result {
        db.pendingUploadDao().setState(row.id, "READY")
        CloudUploadWorker.customDatabase = db; CloudUploadWorker.customCloudProviderFactory = factory(); CloudUploadWorker.customTokenStorage = storage
        return TestListenableWorkerBuilder<CloudUploadWorker>(context).setInputData(workDataOf(CloudUploadWorker.KEY_PENDING_ID to row.id)).build().doWork()
    }
    @Test fun pendingUploadBranchesIfCloudWasChangedOrDeleted() = runBlocking {
        for (deleted in listOf(false, true)) {
            val row = pending()
            cloud.metadata = if (deleted) null else cloud.metadata!!.copy(contentHash = "revision2")
            assertTrue(upload(row) is ListenableWorker.Result.Success)
            assertTrue(cloud.uploads.last().contains("conflict"))
            assertFalse(cloud.deleted)
        }
    }
    @Test fun pendingUploadFailureOrIdentityMismatchRetainsLocalEdit() = runBlocking {
        val row = pending()
        storage.identity = "https://eapi.pcloud.com#different-user"
        assertTrue(upload(row) is ListenableWorker.Result.Failure)
        assertEquals("ERROR", db.pendingUploadDao().get(row.id)?.state)
        assertEquals("pending edit", File(row.localPath).readText()); assertTrue(cloud.uploads.isEmpty())
        storage.identity = row.accountIdentity!!; cloud.uploadError = IOException("offline")
        assertTrue(upload(row) is ListenableWorker.Result.Retry)
        assertEquals("READY", db.pendingUploadDao().get(row.id)?.state); assertTrue(File(row.localPath).exists())
    }
    @Test fun verifiedDifferentAccountOrDatacenterCannotReuseFolderPairs() = runBlocking {
        val manager = AccountSessionManager(context, db, storage)
        for ((endpoint, id) in listOf("https://api.pcloud.com" to 2L, "https://eapi.pcloud.com" to 1L)) {
            var rejected = false
            try { manager.acceptVerified("new-token", endpoint, PCloudUserInfoResponse(result = 0, userid = id)) } catch (_: IOException) { rejected = true }
            assertTrue(rejected); assertEquals("token", storage.token)
        }
        manager.acceptVerified("renewed", "https://api.pcloud.com", PCloudUserInfoResponse(result = 0, userid = 1))
        assertEquals("renewed", storage.token)
    }
    @Test fun unverifiedLegacyIdentityCannotRebindExistingData() = runBlocking {
        storage.identity = null
        assertNull(DefaultCloudProviderFactory(storage).getProvider("pcloud_default"))
        var rejected = false
        try { AccountSessionManager(context, db, storage).acceptVerified("new", "https://api.pcloud.com", PCloudUserInfoResponse(result = 0, userid = 1)) } catch (_: IOException) { rejected = true }
        assertTrue(rejected); assertEquals("token", storage.token)
    }
    @Test fun diffIsBoundedByBytesLinesAndMatrixCells() {
        assertTrue(DiffAlgorithm.computeDiff("x".repeat(DiffAlgorithm.MAX_TEXT_BYTES + 1), "").tooLarge)
        assertTrue(DiffAlgorithm.computeDiff("x\n".repeat(10000), "x").tooLarge)
        assertTrue(DiffAlgorithm.computeDiff("x\n".repeat(1500), "y\n".repeat(1500)).tooLarge)
        assertFalse(DiffAlgorithm.computeDiff("old", "new").tooLarge)
    }
    @Test fun legacyBaselineRequiresCloudRevisionRevalidationAndLongNamesStaySafe() {
        val l = local("note.md"); val r = remote()
        val baseline = BaselineItemSnapshot("note.md", "f1", l.modifiedTimeMillis, r.modifiedTimeMillis, l.sizeBytes, r.sizeBytes, l.contentHash)
        assertEquals(EndpointDelta.MODIFIED, DeltaCalculator.computeRemoteDelta(r, baseline))
        val names = (1..100).map { ConflictResolver.generateConflictFilename("筆".repeat(75) + ".md", 1000) }
        assertEquals(100, names.toSet().size)
        assertTrue(names.all { it.toByteArray(Charsets.UTF_8).size <= 255 && it.endsWith(".md") })
    }

    @Test fun recoveryDirectorySymlinkCannotMoveUserDataOutsideThePair() {
        val root = temp.newFolder("pair-root")
        val outside = temp.newFolder("other-files")
        val file = File(root, "note.md").apply { writeText("valuable") }
        java.nio.file.Files.createSymbolicLink(File(root, ".relayfs-recovery").toPath(), outside.toPath())
        assertThrows(IOException::class.java) { FileSafety.quarantine(root, file) }
        assertEquals("valuable", file.readText()); assertTrue(outside.listFiles()!!.isEmpty())
    }

    @Test fun renamedCloudAliasesAndAncestralRootsCannotSyncOverlappingPairs() = runBlocking {
        local("note.md")
        val otherRoot = temp.newFolder("other-pair")
        val other = FolderPairEntity("other", "pcloud_default", "Other", otherRoot.path, "d10", "/DifferentOldName")
        db.folderPairDao().insert(other)
        val coordinator = SyncCoordinator(db, factory())
        assertFalse(coordinator.syncFolderPair(pair).isSuccess)
        assertTrue(cloud.uploads.isEmpty())
        db.folderPairDao().update(other.copy(remoteFolderId = "d20"))
        cloud.folderParents["d20"] = "d10"
        assertFalse(coordinator.syncFolderPair(pair).isSuccess)
        assertTrue(cloud.uploads.isEmpty())
        // The check must also work when the current pair is the child.
        assertFalse(coordinator.syncFolderPair("other").isSuccess)
    }

    @Test fun corruptIndividualCredentialValuesFailClosedWithoutErasingTheStore() = runBlocking {
        val prefs = context.getSharedPreferences("corrupt-entry", Context.MODE_PRIVATE)
        prefs.edit().putString("ciphertext", "preserve").commit()
        val failing = object : android.content.SharedPreferences by prefs {
            override fun getString(key: String?, defValue: String?): String? { throw SecurityException("bad authentication tag") }
        }
        val tokens = EncryptedSharedPrefsTokenStorage(failing)
        assertNull(tokens.getToken("pcloud_default")); assertNull(tokens.getIdentity("pcloud_default"))
        assertNull(tokens.getEndpoint("pcloud_default")); assertFalse(tokens.hasToken("pcloud_default"))
        assertEquals("preserve", prefs.getString("ciphertext", null))
    }

    @Test fun keepCloudSupportsMaximumLengthUnicodeFilenamesAndRetainsLocalBytes() = runBlocking {
        val name = "筆".repeat(83) + ".md"
        val l = local(name, "local work")
        val branch = File(temp.root, "cloud-branch.md").apply { writeText("cloud work") }
        val conflict = SyncConflictEntity(id = java.util.UUID.randomUUID().toString(), folderPairId = pair, relativePath = name, localModifiedTimeMillis = l.modifiedTimeMillis,
            localFilePath = l.localFile.path, conflictFilePath = branch.path,
            remoteModifiedTimeMillis = 1000, remoteFileId = "f1", remoteContentHash = "cloud-revision")
        db.syncConflictDao().insert(conflict)
        ConflictResolutionManager(db).keepCloud(conflict)
        assertEquals("cloud work", l.localFile.readText())
        assertFalse(branch.exists())
        assertTrue(File(temp.root, ".relayfs-recovery").walk().filter { it.isFile && !it.name.endsWith(".path") }.any { it.readText() == "local work" })
        assertTrue(PathFilter().shouldExclude(".relayfs." + "a".repeat(64) + ".original"))
    }

    @Test fun keystoreFailureRecoversWithoutDeletingOriginalPreferences() {
        val old = context.getSharedPreferences("old", Context.MODE_PRIVATE)
        old.edit().putString("encrypted-bytes", "preserve-me").commit()
        val fresh = context.getSharedPreferences("fresh", Context.MODE_PRIVATE)
        assertSame(fresh, EncryptedSharedPrefsTokenStorage.recoverPreferences({ throw java.security.GeneralSecurityException("bad tag") }, { fresh }))
        assertEquals("preserve-me", old.getString("encrypted-bytes", null))
        assertSame(fresh, EncryptedSharedPrefsTokenStorage.recoverPreferences({ throw IOException("bad file") }, { fresh }))
    }
}

private class MemoryStorage : TokenStorage {
    var token: String? = "token"
    var identity: String? = "https://api.pcloud.com#1"
    override suspend fun getIdentity(accountId: String) = identity
    override suspend fun saveIdentity(accountId: String, identity: String) { this.identity = identity }
    override suspend fun saveToken(accountId: String, token: String) { this.token = token }
    override suspend fun getToken(accountId: String) = token
    override suspend fun deleteToken(accountId: String) { token = null }
    override suspend fun hasToken(accountId: String) = token != null
}

private class SafetyCloud : CloudProvider {
    override val providerType = ProviderType.PCLOUD
    override val accountId = "pcloud_default"
    val folderParents = mutableMapOf<String, String>()
    var metadata: CloudItem? = null
    var content = ""
    var deleted = false
    var downloadError: Exception? = null
    var uploadError: Exception? = null
    var backupError: Exception? = null
    var afterStat: (() -> Unit)? = null
    var afterDownload: (() -> Unit)? = null
    var afterBackup: (() -> Unit)? = null
    val uploads = mutableListOf<String>()
    override suspend fun getItemMetadata(remoteId: String): Result<CloudItem> = if (remoteId.startsWith("d")) Result.success(CloudItem(remoteId, folderParents[remoteId] ?: "d0", "Vault", "/Vault", true, 0, 1)) else metadata?.let { afterStat?.invoke(); Result.success(it) } ?: Result.failure(java.io.FileNotFoundException())
    override suspend fun listFolder(remoteFolderId: String, recursive: Boolean) = Result.success(metadata?.takeIf { it.parentId == remoteFolderId }?.let { listOf(it) } ?: emptyList())
    override suspend fun preserveFile(remoteId: String): Result<Unit> { backupError?.let { throw it }; afterBackup?.invoke(); return Result.success(Unit) }
    override suspend fun downloadFile(remoteId: String, destinationFile: File, progress: ((Long, Long) -> Unit)?): Result<Unit> {
        downloadError?.let { return Result.failure(it) }; destinationFile.writeText(content); afterDownload?.invoke(); return Result.success(Unit)
    }
    override suspend fun uploadFile(parentFolderId: String, file: File, remoteName: String, progress: ((Long, Long) -> Unit)?): Result<CloudItem> {
        uploadError?.let { throw it }; uploads += remoteName
        return Result.success(CloudItem("f2", parentFolderId, remoteName, "/$remoteName", false, file.length(), 1000, "uploaded-revision"))
    }
    override suspend fun uploadNewFile(parentFolderId: String, file: File, remoteName: String) = uploadFile(parentFolderId, file, remoteName, null)
    override suspend fun createFolder(parentFolderId: String, folderName: String) = Result.success(CloudItem("d20", parentFolderId, folderName, "/$folderName", true, 0, 1))
    override suspend fun deleteItem(remoteId: String, isDirectory: Boolean): Result<Unit> { deleted = true; return Result.success(Unit) }
    override suspend fun moveItem(remoteId: String, newParentFolderId: String, newName: String) = Result.failure<CloudItem>(UnsupportedOperationException())
    override suspend fun verifyConnection() = Result.failure<CloudAccount>(UnsupportedOperationException())
    override fun supportsDeltaSync() = false
    override suspend fun getDeltaChanges(sinceToken: String?) = Result.failure<DeltaChanges>(UnsupportedOperationException())
}
