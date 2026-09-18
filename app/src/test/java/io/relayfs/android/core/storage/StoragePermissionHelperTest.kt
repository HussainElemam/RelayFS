package io.relayfs.android.core.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StoragePermissionHelperTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun createAllFilesAccessIntent_onAndroid11Plus_returnsManageAppPermissionIntent() {
        val intent = StoragePermissionHelper.createAllFilesAccessIntent(context)
        assertNotNull(intent)
        // On SDK 34, should produce ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION or ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION
        val action = intent.action
        assertTrue(
            action == Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION ||
                action == Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION
        )
    }

    @Test
    fun createDocumentTreeIntent_setsRequiredPersistableFlags() {
        val intent = StoragePermissionHelper.createDocumentTreeIntent()
        assertEquals(Intent.ACTION_OPEN_DOCUMENT_TREE, intent.action)

        val flags = intent.flags
        assertTrue("Must contain FLAG_GRANT_READ_URI_PERMISSION", (flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0)
        assertTrue("Must contain FLAG_GRANT_WRITE_URI_PERMISSION", (flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0)
        assertTrue("Must contain FLAG_GRANT_PERSISTABLE_URI_PERMISSION", (flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) != 0)
        assertTrue("Must contain FLAG_GRANT_PREFIX_URI_PERMISSION", (flags and Intent.FLAG_GRANT_PREFIX_URI_PERMISSION) != 0)
    }

    @Test
    fun createDocumentTreeIntent_withInitialUri_includesExtra() {
        val initialUri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ADocuments")
        val intent = StoragePermissionHelper.createDocumentTreeIntent(initialUri)

        assertNotNull(intent)
        val extra = androidx.core.content.IntentCompat.getParcelableExtra(
            intent,
            DocumentsContract.EXTRA_INITIAL_URI,
            Uri::class.java
        )
        assertEquals(initialUri, extra)
    }

    @Test
    fun resolveTreeUriToPath_primaryStorage_resolvesToExternalStoragePath() {
        val uri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ADocuments%2FObsidian")
        val path = StoragePermissionHelper.resolveTreeUriToPath(uri)
        assertNotNull(path)
        assertTrue(path!!.endsWith("Documents/Obsidian"))
    }
}
