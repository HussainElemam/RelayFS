package io.relayfs.android.core.storage

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * Helper utility for handling Android storage permissions across API levels 24–36+.
 *
 * Implements the permissions strategy:
 * 1. Primary: MANAGE_EXTERNAL_STORAGE (All Files Access) on Android 11+ (API 30+)
 *    providing raw POSIX java.io.File access and Linux inotify file monitoring.
 * Folder selection may use SAF as a picker, but synchronization still requires raw filesystem
 * access because the sync engine and file watcher operate on java.io.File paths.
 */
object StoragePermissionHelper {

    /**
     * Checks if the application has full file access on the device.
     * On Android 11+ (API 30+), checks Environment.isExternalStorageManager().
     * On Android 10 and lower, checks READ_EXTERNAL_STORAGE and WRITE_EXTERNAL_STORAGE.
     */
    fun hasAllFilesAccess(context: Context): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Environment.isExternalStorageManager()
            } else {
                val readGranted = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.READ_EXTERNAL_STORAGE
                ) == PackageManager.PERMISSION_GRANTED

                val writeGranted = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                ) == PackageManager.PERMISSION_GRANTED

                readGranted && writeGranted
            }
        } catch (t: Throwable) {
            false
        }
    }

    /**
     * Creates an Intent to prompt the user for All Files Access permission in System Settings.
     */
    fun createAllFilesAccessIntent(context: Context): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val packageUri = Uri.parse("package:${context.packageName}")
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, packageUri)
        } else {
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${context.packageName}")
            )
        }
    }

    /**
     * Creates an Intent for selecting a directory via Storage Access Framework (SAF).
     * This is a picker only; its URI grant is not a replacement for raw filesystem access.
     */
    fun createDocumentTreeIntent(initialUri: Uri? = null): Intent {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && initialUri != null) {
            intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, initialUri)
        }
        return intent
    }

    /**
     * Takes persistable URI permission for a SAF tree URI granted by ACTION_OPEN_DOCUMENT_TREE.
     */
    fun takePersistablePermissions(
        contentResolver: ContentResolver,
        treeUri: Uri,
        takeFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    ) {
        contentResolver.takePersistableUriPermission(treeUri, takeFlags)
    }

    /**
     * Checks whether persistable read/write permissions have been taken for the specified tree URI.
     */
    fun hasPersistedUriPermission(contentResolver: ContentResolver, treeUri: Uri): Boolean {
        return contentResolver.persistedUriPermissions.any { perm ->
            perm.uri == treeUri && perm.isReadPermission && perm.isWritePermission
        }
    }

    /**
     * Resolves a Storage Access Framework tree Uri to a local filesystem path if possible.
     * Supports standard primary external storage as well as secondary storage volumes.
     */
    fun resolveTreeUriToPath(treeUri: Uri): String? {
        return try {
            val docId = DocumentsContract.getTreeDocumentId(treeUri) ?: return null
            if (docId.startsWith("primary:")) {
                val relPath = docId.removePrefix("primary:")
                java.io.File(Environment.getExternalStorageDirectory(), relPath).absolutePath
            } else if (docId.contains(":")) {
                val parts = docId.split(":", limit = 2)
                val volumeId = parts[0]
                val relPath = parts[1]
                val file = java.io.File("/storage/$volumeId", relPath)
                file.absolutePath
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }
}
