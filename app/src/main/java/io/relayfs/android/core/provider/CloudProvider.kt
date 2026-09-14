package io.relayfs.android.core.provider

import io.relayfs.android.core.model.CloudAccount
import io.relayfs.android.core.model.CloudItem
import io.relayfs.android.core.model.DeltaChanges
import io.relayfs.android.core.model.ProviderType
import java.io.File

/**
 * Universal abstraction contract for remote cloud storage providers (pCloud, WebDAV, etc.).
 * Implementations are stateless with respect to local disk sync state.
 */
interface CloudProvider {

    /**
     * Identifies the type of cloud provider (e.g., PCLOUD, WEBDAV).
     */
    val providerType: ProviderType

    /**
     * Unique identifier of the connected cloud account.
     */
    val accountId: String

    /**
     * Verifies the active authentication credentials and retrieves account identity metadata.
     */
    suspend fun verifyConnection(): Result<CloudAccount>

    /**
     * Lists children of a remote folder.
     *
     * @param remoteFolderId Remote folder identifier (or root identifier e.g. "0" or "/")
     * @param recursive If true, returns all descendants in a single pass (if supported by provider)
     */
    suspend fun listFolder(remoteFolderId: String, recursive: Boolean = false): Result<List<CloudItem>>

    /**
     * Retrieves metadata for a single item by its remote ID.
     */
    suspend fun getItemMetadata(remoteId: String): Result<CloudItem>

    /**
     * Returns true if this provider supports incremental delta synchronization (e.g. pCloud diff).
     */
    fun supportsDeltaSync(): Boolean

    /**
     * Fetches incremental changes since a previous cursor token.
     * If [sinceToken] is null, fetches the current baseline token without downloading historical events.
     */
    suspend fun getDeltaChanges(sinceToken: String?): Result<DeltaChanges>

    /**
     * Downloads a remote file into a local destination file.
     *
     * @param remoteId Remote file identifier
     * @param destinationFile Local destination file
     * @param progress Optional progress callback (bytesRead, totalBytes)
     */
    suspend fun downloadFile(
        remoteId: String,
        destinationFile: File,
        progress: ((bytesRead: Long, totalBytes: Long) -> Unit)? = null
    ): Result<Unit>

    /**
     * Uploads a local file to a remote destination folder.
     * Implementations MUST handle chunked upload for files larger than 5MB.
     *
     * @param parentFolderId Remote destination folder identifier
     * @param file Local file to upload
     * @param remoteName Destination file name in the cloud
     * @param progress Optional progress callback (bytesWritten, totalBytes)
     */
    suspend fun uploadFile(
        parentFolderId: String,
        file: File,
        remoteName: String,
        progress: ((bytesWritten: Long, totalBytes: Long) -> Unit)? = null
    ): Result<CloudItem>

    /**
     * Creates a new remote directory under [parentFolderId].
     */
    suspend fun createFolder(parentFolderId: String, folderName: String): Result<CloudItem>

    /**
     * Moves or renames an item remotely.
     */
    suspend fun moveItem(
        remoteId: String,
        newParentFolderId: String,
        newName: String
    ): Result<CloudItem>

    /**
     * Deletes a remote file or folder.
     */
    suspend fun deleteItem(remoteId: String, isDirectory: Boolean): Result<Unit>
}

