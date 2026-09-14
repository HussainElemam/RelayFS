# Feature Specification: Cloud Provider Abstraction Layer

**Feature ID:** `FEAT-01`  
**Related Components:** `core:provider`, `core:model`, `core:security`  

---

## 1. Overview & Objective
The Cloud Provider Abstraction layer decouples the synchronization engine and the `DocumentsProvider` from specific cloud vendor APIs. This enables the app to support **pCloud** initially while allowing straightforward extension to **WebDAV**, **Nextcloud**, **Dropbox**, or **Google Drive** without modifying the core sync or virtual filesystem logic.

---

## 2. Core Domain Models

```kotlin
package com.example.android_sync_idea.core.model

import java.io.File

/**
 * Represents a user account connected to a cloud provider.
 */
data class CloudAccount(
    val id: String, // UUID
    val providerType: ProviderType, // PCLOUD, WEBDAV
    val email: String,
    val displayName: String,
    val serverUrl: String, // e.g., "https://api.pcloud.com"
    val connectedAt: Long
)

enum class ProviderType {
    PCLOUD,
    WEBDAV
}

/**
 * Standardized metadata for any remote file or folder.
 */
data class CloudItem(
    val id: String, // Persistent remote ID (e.g. pCloud fileid/folderid)
    val parentId: String?,
    val name: String,
    val path: String, // Canonical remote relative path e.g. "/Obsidian/Vault/note.md"
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val modifiedTimeMillis: Long,
    val contentHash: String?, // Fast hash or eTag for change detection
    val mimeType: String?
)

/**
 * Result of a delta change request.
 */
data class DeltaChanges(
    val newCursorToken: String, // e.g. pCloud diffid
    val changes: List<CloudChange>,
    val hasMore: Boolean
)

data class CloudChange(
    val item: CloudItem,
    val changeType: ChangeType
)

enum class ChangeType {
    CREATED,
    MODIFIED,
    DELETED,
    MOVED
}
```

---

## 3. The `CloudProvider` Interface Contract

```kotlin
package com.example.android_sync_idea.core.provider

import com.example.android_sync_idea.core.model.*
import java.io.File

interface CloudProvider {
    val providerType: ProviderType
    val accountId: String

    /**
     * Verifies credentials and retrieves account identity.
     */
    suspend fun verifyConnection(): Result<CloudAccount>

    /**
     * Lists children of a folder.
     * @param remoteFolderId Remote folder identifier (or root "/" identifier)
     * @param recursive If true, returns all descendants in a single pass (if supported)
     */
    suspend fun listFolder(remoteFolderId: String, recursive: Boolean = false): Result<List<CloudItem>>

    /**
     * Retrieves metadata for a single item by ID or remote path.
     */
    suspend fun getItemMetadata(remoteId: String): Result<CloudItem>

    /**
     * Checks if this provider supports incremental delta synchronization (e.g. pCloud diff).
     */
    fun supportsDeltaSync(): Boolean

    /**
     * Fetches incremental changes since a previous cursor token.
     * If [sinceToken] is null, fetches current baseline token without downloading historical events.
     */
    suspend fun getDeltaChanges(sinceToken: String?): Result<DeltaChanges>

    /**
     * Downloads a remote file into a local destination file.
     * @param progress Optional progress callback (bytesTransferred, totalBytes)
     */
    suspend fun downloadFile(
        remoteId: String,
        destinationFile: File,
        progress: ((bytesRead: Long, totalBytes: Long) -> Unit)? = null
    ): Result<Unit>

    /**
     * Uploads a local file to a remote destination folder.
     * Implementations MUST handle chunked upload for files > 5MB.
     */
    suspend fun uploadFile(
        parentFolderId: String,
        file: File,
        remoteName: String,
        progress: ((bytesWritten: Long, totalBytes: Long) -> Unit)? = null
    ): Result<CloudItem>

    /**
     * Creates a new remote directory.
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
```

---

## 4. pCloud Implementation Details (`PCloudProvider`)

### 4.1 OAuth 2.0 PKCE Flow
1. App generates a high-entropy cryptographic random `code_verifier` (43–128 characters).
2. App computes `code_challenge = BASE64URL-ENCODE(SHA256(code_verifier))`.
3. Opens Chrome Custom Tab to:
   ```
   https://my.pcloud.com/oauth2/authorize?client_id={CLIENT_ID}&response_type=code&code_challenge={CHALLENGE}&code_challenge_method=S256&redirect_uri=cloudsync://oauth
   ```
4. User logs in (supports 2FA, Google/Apple sign-in).
5. pCloud redirects to `cloudsync://oauth?code={AUTH_CODE}&locationid={1 or 2}`.
6. App captures intent and exchanges `code` + `code_verifier` for `access_token` at:
   * US: `https://api.pcloud.com/oauth2_token`
   * EU: `https://eapi.pcloud.com/oauth2_token`
7. Token is immediately saved in `EncryptedSharedPreferences` via Android Keystore.

### 4.2 Endpoint Mapping
* **Listing:** `GET /listfolder?folderid={id}&recursive=1`
* **Delta Sync:** `GET /diff?diffid={diffid}&limit=500`
* **Download:** `GET /getfilelink?fileid={id}` $\rightarrow$ stream from returned CDN URL.
* **Upload (<5MB):** `POST /uploadfile?folderid={id}&filename={name}`
* **Chunked Upload (>5MB):**
  1. `POST /upload_create` $\rightarrow$ returns `uploadid`
  2. `POST /upload_write?uploadid={id}&uploadoffset={offset}` $\rightarrow$ upload 2MB chunk
  3. `POST /upload_save?uploadid={id}&folderid={folderid}&name={name}`
* **Move:** `POST /movefile?fileid={id}&tofolderid={toid}&toname={name}`
* **Delete:** `POST /deletefile?fileid={id}` or `POST /deletefolderrecursive?folderid={id}`

---

## 5. Security & Error Handling
* **Rate Limiting:** Interceptor automatically detects HTTP 429 and retries using exponential backoff (initial delay: 1s, max delay: 30s).
* **Token Expiration:** Interceptor detects invalid access tokens and fires an authentication error event prompting the user to re-authorize.
* **Redaction:** Logs must mask all `access_token` query parameters and `Authorization: Bearer` headers.

