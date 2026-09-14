# Feature Specification: Storage Access Framework `DocumentsProvider` (Virtual Cloud Drive)

**Feature ID:** `FEAT-06`  
**Related Components:** `core:saf`, `core:database`, `core:provider`  

---

## 1. Overview & Objective
While the local folder sync engine (Feature 02) mirrors specific folders to physical storage, the **`DocumentsProvider`** integrates the user’s entire cloud storage account directly into Android's native system file picker (`DocumentsUI`). Third-party apps (PDF readers, media players, office suites, email clients) can browse, open, and save files directly to and from the cloud on-demand without prior full synchronization.

---

## 2. Component Architecture

```
┌────────────────────────────────────────────────────────────────────────┐
│                   Calling App / Android DocumentsUI                    │
│                 (e.g., Adobe Acrobat, VLC, Gmail, Files)               │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │ IPC Binder Transaction
                                    ▼
┌────────────────────────────────────────────────────────────────────────┐
│                        CloudDocumentsProvider                          │
│                                                                        │
│   queryRoots()             queryChildDocuments()         openDocument()│
└─────────┬──────────────────────────┬───────────────────────────┬───────┘
          │                          │                           │
          ▼                          ▼                           ▼
┌──────────────────┐       ┌──────────────────┐        ┌─────────────────┐
│ Room DB Accounts │       │ SQLite Metadata  │        │ Disk File Cache │
│ (Expose as Root) │       │ (Fast Cursor)    │        │  (Seekable PFD) │
└──────────────────┘       └─────────┬────────┘        └─────────┬───────┘
                                     │ Cache Miss / Stale        │
                                     ▼                           ▼
                           ┌─────────────────────────────────────────────┐
                           │          pCloud REST API Client             │
                           │   /listfolder   |   /getfilelink (stream)   │
                           └─────────────────────────────────────────────┘
```

---

## 3. Manifest Declaration

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application>
        <provider
            android:name=".core.saf.CloudDocumentsProvider"
            android:authorities="${applicationId}.documents"
            android:grantUriPermissions="true"
            android:exported="true"
            android:permission="android.permission.MANAGE_DOCUMENTS">
            <intent-filter>
                <action android:name="android.content.action.DOCUMENTS_PROVIDER" />
            </intent-filter>
        </provider>
    </application>
</manifest>
```

---

## 4. Method Implementation Contracts

### 4.1 `queryRoots(projection: Array<String>?)`
* Returns a `MatrixCursor` containing all active cloud accounts.
* **Columns:**
  * `Root.COLUMN_ROOT_ID`: Account UUID.
  * `Root.COLUMN_ICON`: App / provider drawable resource.
  * `Root.COLUMN_TITLE`: `"pCloud (${account.email})"`.
  * `Root.COLUMN_FLAGS`:
    * `FLAG_SUPPORTS_CREATE`
    * `FLAG_SUPPORTS_IS_CHILD`
  * `Root.COLUMN_DOCUMENT_ID`: Root directory ID (e.g. `"0"` for pCloud root).

### 4.2 `queryChildDocuments(parentDocumentId: String, ...)`
* **Rule:** Must never make blocking synchronous network requests on the IPC thread.
* **Execution Flow:**
  1. Query local SQLite table (`saf_metadata_cache`) for children of `parentDocumentId`.
  2. Populate `MatrixCursor` with cached children.
  3. Check if cached data is stale ($>60\text{ seconds}$).
  4. If stale or empty:
     * Attach extra: `cursor.extras = Bundle().apply { putBoolean(DocumentsContract.EXTRA_LOADING, true) }`.
     * Return cursor immediately (system picker displays loading spinner).
     * Launch asynchronous Coroutine to call `pcloudApi.listFolder(parentDocumentId)`.
     * Upon network completion: update SQLite and fire `contentResolver.notifyChange(childDocumentsUri, null)`.
     * The system picker automatically re-queries and renders fresh data.

### 4.3 `openDocument(documentId: String, mode: String, signal: CancellationSignal?)`
* **Handling Read Mode (`"r"`):**
  * Pipes (`createPipe()`) are non-seekable and crash apps that perform random reads (e.g., video players seeking timestamps or PDF readers reading cross-reference tables).
  * **Solution:** Check internal disk cache (`context.cacheDir/saf_cache/${documentId}`). If absent, stream-download from pCloud using the provided `CancellationSignal`.
  * Return seekable descriptor:
    ```kotlin
    return ParcelFileDescriptor.open(cachedFile, ParcelFileDescriptor.MODE_READ_ONLY)
    ```

* **Handling Write Mode (`"w"` or `"rw"`):**
  * When an external app edits a cloud document, open a temporary local file with an `OnCloseListener`:
    ```kotlin
    val pfd = ParcelFileDescriptor.open(
        tempFile,
        ParcelFileDescriptor.MODE_READ_WRITE,
        handler
    ) { ioException ->
        if (ioException == null) {
            // The external application has finished writing and closed the descriptor.
            // Enqueue immediate background upload to cloud.
            CloudUploadWorker.enqueueUpload(context, documentId, tempFile)
        }
    }
    return pfd
    ```

### 4.4 `openDocumentThumbnail(documentId: String, sizeHint: Point, signal: CancellationSignal?)`
* Query pCloud thumbnail endpoint (`/getthumb`) for images/videos.
* Cache thumbnail bitmap to disk and return a read-only `ParcelFileDescriptor`.

