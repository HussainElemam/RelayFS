# Phase 7: Storage Access Framework `DocumentsProvider` (Virtual Cloud Drive)

**Phase Status:** `PENDING`  
**Estimated Effort:** 5–7 Days  
**Prerequisites:** [Phase 1 through Phase 6](file:///home/hussain/AndroidStudioProjects/android_sync_idea/docs/ROADMAP.md) complete.  

---

## 1. Phase Goal
Expose the user's pCloud account directly inside the native Android system file picker (`DocumentsUI`):
1. Implement `CloudDocumentsProvider` extending `android.provider.DocumentsProvider`.
2. Build non-blocking `queryRoots()` and `queryChildDocuments()` backed by a local SQLite metadata cache to eliminate file picker UI stutter.
3. Handle asynchronous remote directory refresh with `DocumentsContract.EXTRA_LOADING`.
4. Implement seekable read streams in `openDocument("r")` via on-demand disk caching.
5. Capture document edits in third-party applications via `ParcelFileDescriptor.OnCloseListener` and enqueue automatic background uploads.
6. Support thumbnail generation for images and videos via pCloud's thumbnail API.

---

## 2. Detailed Task Checklist

### Task 2.1: Manifest & Provider Registration (`io.relayfs.android.core.saf`)
- [ ] Declare `CloudDocumentsProvider` in `AndroidManifest.xml` with authority `${applicationId}.documents` and permission `MANAGE_DOCUMENTS`.
- [ ] Implement `onCreate()` initializing database access and disk cache directories.

### Task 2.2: SQLite Metadata Cache (`io.relayfs.android.core.saf.cache`)
- [ ] Create `SafMetadataEntity.kt` and `SafMetadataDao.kt` in Room database:
  * Caches directory structures, document IDs, MIME types, sizes, and timestamps.
- [ ] Implement `queryRoots()`: returns active cloud account roots.
- [ ] Implement `queryDocument()`: returns single document metadata from SQLite.
- [ ] Implement `queryChildDocuments()`:
  * Reads cached rows from SQLite.
  * If stale, returns cursor with `EXTRA_LOADING = true` and launches background API refresh.
  * Dispatches `contentResolver.notifyChange()` when remote listing finishes.

### Task 2.3: Seekable File Descriptors (`openDocument`)
- [ ] Implement `openDocument(documentId, mode, signal)`:
  * **Read Mode (`"r"`):**
    * Check if file exists in internal disk cache (`context.cacheDir/saf_cache/`).
    * If missing, download from pCloud respecting `CancellationSignal`.
    * Return `ParcelFileDescriptor.open(cachedFile, MODE_READ_ONLY)`.
  * **Write Mode (`"w"` / `"rw"`):**
    * Create temporary edit file in cache.
    * Return `ParcelFileDescriptor.open(tempFile, MODE_READ_WRITE, handler, onCloseListener)`.
    * When `onClose` fires without error, enqueue immediate upload to pCloud.

### Task 2.4: File Management Operations
- [ ] Implement `createDocument(parentDocId, mimeType, displayName)`: calls pCloud create folder/file API.
- [ ] Implement `deleteDocument(documentId)`: calls pCloud delete API.
- [ ] Implement `renameDocument(documentId, displayName)`: calls pCloud rename API.
- [ ] Implement `openDocumentThumbnail(documentId, sizeHint, signal)`: fetches pCloud image thumbnail and returns bitmap descriptor.

### Task 2.5: Automated & Integration Tests
- [ ] Test `queryRoots` returns expected columns and cursor metadata.
- [ ] Test `queryChildDocuments` returns cached data immediately without blocking.
- [ ] Test `OnCloseListener` triggers cloud upload enqueue upon descriptor close.

---

## 3. Acceptance Criteria
1. The cloud account appears in Android's native file picker (`DocumentsUI`) under "Open from" or "Save to".
2. Users can browse folders, view thumbnails, and open PDF / image files in external viewer apps.
3. Media players can seek timestamps in audio/video files without crashing on `ESPIPE`.
4. Editing a document in an external text editor and saving triggers an automatic cloud upload.
5. All automated SAF tests pass.

---

## 4. Verification & Testing Commands

```bash
# Run unit tests for DocumentsProvider
./gradlew testDebugUnitTest --tests "io.relayfs.android.core.saf.*"

# Assemble APK for system file picker manual verification
./gradlew assembleDebug
```

---

## 5. Agent Completion Handoff
1. Ensure all tests pass.
2. Update checklist in this document and `docs/ROADMAP.md`.
3. Report results to user and **STOP** for review.

