package io.relayfs.android.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import io.relayfs.android.core.database.AppDatabase
import io.relayfs.android.core.work.scheduler.SyncScheduler
import io.relayfs.android.ui.pair.FolderPairWizardViewModel
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FolderPairWizardViewModelTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var syncScheduler: SyncScheduler
    private lateinit var viewModel: FolderPairWizardViewModel

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        val config = Configuration.Builder()
            .setExecutor(SynchronousExecutor())
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
        val workManager = WorkManager.getInstance(context)

        database = AppDatabase.createInMemory(context)
        syncScheduler = SyncScheduler(context, workManager)
        viewModel = FolderPairWizardViewModel(database, syncScheduler)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun testObsidianVaultAutomaticDetection() {
        // Create an Obsidian vault with .obsidian subfolder
        val vaultDir = tempFolder.newFolder("MyObsidianVault")
        File(vaultDir, ".obsidian").mkdir()

        viewModel.updateLocalPath(vaultDir.absolutePath)

        val state = viewModel.uiState.value
        assertTrue("Obsidian vault should be automatically detected", state.isObsidianDetected)
        assertTrue("Obsidian mode should be enabled by default", state.isObsidianVault)
        assertEquals("MyObsidianVault", state.name)
    }

    @Test
    fun testNonObsidianFolderDetection() {
        val normalDir = tempFolder.newFolder("RegularDocuments")

        viewModel.updateLocalPath(normalDir.absolutePath)

        val state = viewModel.uiState.value
        assertEquals(false, state.isObsidianDetected)
        assertEquals(false, state.isObsidianVault)
    }

    @Test
    fun testWizardSaveFlow() = runTest {
        val vaultDir = tempFolder.newFolder("WorkNotes")
        File(vaultDir, ".obsidian").mkdir()

        viewModel.updateLocalPath(vaultDir.absolutePath)
        viewModel.nextStep()
        assertEquals(2, viewModel.uiState.value.currentStep)

        viewModel.updateRemotePath("/Notes/WorkNotes")
        viewModel.nextStep()
        assertEquals(3, viewModel.uiState.value.currentStep)

        viewModel.updateName("Work Notes Vault")
        viewModel.updateWifiOnly(true)
        viewModel.savePair().join()

        assertTrue(viewModel.uiState.value.isSavedSuccess)

        // Verify entity persisted in Room
        val pairs = database.folderPairDao().getEnabledPairs()
        assertEquals(1, pairs.size)
        val savedPair = pairs.first()
        assertEquals("Work Notes Vault", savedPair.name)
        assertEquals(vaultDir.absolutePath, savedPair.localPath)
        assertEquals("/Notes/WorkNotes", savedPair.remoteFolderPath)
        assertEquals("d0", savedPair.remoteFolderId)
        assertTrue(savedPair.isObsidianVault)
        assertTrue(savedPair.wifiOnly)
    }

    @Test
    fun testNonexistentLocalFolderCannotAdvanceOrSave() = runTest {
        val missing = File(tempFolder.root, "does-not-exist")
        viewModel.updateLocalPath(missing.absolutePath)

        viewModel.nextStep()
        assertEquals(1, viewModel.uiState.value.currentStep)
        assertTrue(viewModel.uiState.value.errorMessage?.contains("does not exist") == true)

        viewModel.updateName("Invalid pair")
        viewModel.savePair().join()
        assertEquals(0, database.folderPairDao().getEnabledPairs().size)
    }
    @Test fun rootAndTraversalDestinationsAreRejected() = runTest {
        val dir = tempFolder.newFolder("valid")
        for (path in listOf("/", "", "/Vault/../Other", "/Vault/./Other")) {
            val vm = FolderPairWizardViewModel(database, syncScheduler)
            vm.updateLocalPath(dir.path); vm.updateRemotePath(path); vm.savePair().join()
            assertTrue(vm.uiState.value.errorMessage != null)
            assertTrue(database.folderPairDao().getEnabledPairs().isEmpty())
        }
    }

    @Test fun overlappingLocalOrRemoteFoldersAndRepeatedSaveAreRejected() = runTest {
        val dir = tempFolder.newFolder("parent")
        viewModel.updateLocalPath(dir.path); viewModel.updateRemotePath("/Vault")
        val first = viewModel.savePair(); val repeat = viewModel.savePair()
        first.join(); repeat.join()
        assertEquals(1, database.folderPairDao().getEnabledPairs().size)
        for ((local, remote) in listOf(File(dir, "child").apply { mkdirs() } to "/Other", tempFolder.newFolder("separate") to "/Vault/Child")) {
            val vm = FolderPairWizardViewModel(database, syncScheduler)
            vm.updateLocalPath(local.path); vm.updateRemotePath(remote); vm.savePair().join()
            assertTrue(vm.uiState.value.errorMessage?.contains("overlaps") == true)
        }
        assertEquals(1, database.folderPairDao().getEnabledPairs().size)
    }

}
