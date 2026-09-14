# Feature Specification: Two-Way Synchronization Engine & Baseline Database

**Feature ID:** `FEAT-02`  
**Related Components:** `core:sync`, `core:database`, `core:model`  

---

## 1. Overview & Objective
The Two-Way Synchronization Engine is responsible for keeping a local Android directory in mathematical consistency with a remote cloud directory. It operates using a **3-Way Snapshot Reconciliation** algorithm that relies on a persistent Room SQLite database to track the last-known synchronized state (the "Baseline").

---

## 2. Room Database Schema

### 2.1 Entity: `FolderPairEntity`
Defines a configured synchronization link between a local directory and a cloud folder.

```kotlin
@Entity(tableName = "folder_pairs")
data class FolderPairEntity(
    @PrimaryKey val id: String, // UUID
    val accountId: String,
    val localPath: String, // e.g. "/storage/emulated/0/Documents/Obsidian/Vault"
    val remoteFolderId: String, // e.g. "12345678"
    val remotePath: String, // e.g. "/Obsidian/Vault"
    val syncDirection: String, // TWO_WAY, CLOUD_TO_LOCAL, LOCAL_TO_CLOUD
    val isEnabled: Boolean,
    val isObsidianVault: Boolean,
    val lastSyncTimestamp: Long,
    val lastRemoteCursorToken: String?, // e.g. pCloud diffid
    val syncIntervalMinutes: Int // Default: 30
)
```

### 2.2 Entity: `SyncBaselineEntity`
Stores the snapshot of every file at the end of the last successful sync cycle.

```kotlin
@Entity(
    tableName = "sync_baseline",
    primaryKeys = ["folderPairId", "relativePath"],
    indices = [
        Index(value = ["folderPairId", "remoteId"]),
        Index(value = ["folderPairId", "relativePath"])
    ]
)
data class SyncBaselineEntity(
    val folderPairId: String,
    val relativePath: String, // e.g. "Work/Projects/Notes.md"
    val remoteId: String, // Persistent cloud identifier (pCloud fileid)
    val localSizeBytes: Long,
    val localModifiedTimeMillis: Long,
    val localContentHash: String, // xxHash64 of local file
    val remoteContentHash: String, // Cloud hash/eTag
    val isDirectory: Boolean,
    val lastSyncedAt: Long
)
```

### 2.3 Entity: `SyncConflictEntity`
Tracks unresolved and resolved conflict branches.

```kotlin
@Entity(tableName = "sync_conflicts")
data class SyncConflictEntity(
    @PrimaryKey val id: String, // UUID
    val folderPairId: String,
    val originalRelativePath: String,
    val branchedRelativePath: String, // e.g. "Notes (conflict-Cloud-2026-09-14-1600).md"
    val detectedAt: Long,
    val isResolved: Boolean
)
```

---

## 3. The 3-Way Reconciliation Algorithm

The algorithm takes three input states for each path:
1. **$B$ (Baseline):** State recorded in `SyncBaselineEntity`.
2. **$L$ (Local):** Current physical file state on Android storage.
3. **$R$ (Remote):** Current metadata reported by the cloud provider.

### 3.1 State Calculation
For each item, determine delta against Baseline:
* $\Delta L = \text{computeDelta}(L, B)$
* $\Delta R = \text{computeDelta}(R, B)$

Possible deltas: `UNCHANGED`, `CREATED`, `MODIFIED`, `DELETED`, `MOVED`.

### 3.2 Complete Decision Matrix

| Case | Local Delta ($\Delta L$) | Remote Delta ($\Delta R$) | Action | Baseline Update |
| :--- | :--- | :--- | :--- | :--- |
| **1** | `UNCHANGED` | `UNCHANGED` | None | None |
| **2** | `MODIFIED` | `UNCHANGED` | **Upload $L \rightarrow R$** | Update $B \leftarrow L, R_{\text{new}}$ |
| **3** | `UNCHANGED` | `MODIFIED` | **Download $R \rightarrow L$** (atomic) | Update $B \leftarrow L_{\text{new}}, R$ |
| **4** | `CREATED` | `NONE` | **Upload $L \rightarrow R$** | Insert $B \leftarrow L, R_{\text{new}}$ |
| **5** | `NONE` | `CREATED` | **Download $R \rightarrow L$** (atomic) | Insert $B \leftarrow L_{\text{new}}, R$ |
| **6** | `DELETED` | `UNCHANGED` | **Delete $R$ remotely** | Delete from $B$ |
| **7** | `UNCHANGED` | `DELETED` | **Delete $L$ locally** | Delete from $B$ |
| **8** | `MODIFIED` | `MODIFIED` (Hashes match) | **Identical Edit.** No transfer. | Update $B$ with new mtimes |
| **9** | `MODIFIED` | `MODIFIED` (Hashes differ) | **CONFLICT!** Branch remote file: `[Name] (conflict - Cloud - [Date]).[ext]` | Keep $L$ at original path; insert branched file into $B$; record conflict entity. |
| **10**| `MODIFIED` | `DELETED` | **Conflict.** Re-upload $L \rightarrow R$ (preserve local work) | Update $B$ |
| **11**| `DELETED` | `MODIFIED` | **Conflict.** Re-download $R \rightarrow L$ (preserve cloud work) | Update $B$ |

---

## 4. Conflict Handling & Branching Strategy

When Case 9 occurs:
1. The local file is left untouched at its original path (ensuring no interrupted edits for an active Obsidian user).
2. The remote file is downloaded to a new sibling path using the format:
   ```
   [Directory]/[BaseName] (conflict - Cloud - YYYY-MM-DD-HHmm).[Extension]
   ```
3. A notification is posted to the user via Android Notification Manager.
4. An entry is created in `SyncConflictEntity`.
5. Obsidian displays both files side-by-side in its file explorer, allowing the user to review differences.

---

## 5. Atomic Local File Operations

To prevent data corruption if the app is killed or loses power during a download:
1. Remote data is streamed into a hidden temporary file:
   ```kotlin
   val target = File(localDir, relativePath)
   val temp = File(target.parentFile, ".${target.name}.sync_tmp")
   ```
2. The stream is flushed to disk via `FileOutputStream.fd.sync()`.
3. The temporary file is atomically renamed over the target:
   ```kotlin
   if (!temp.renameTo(target)) {
       Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
   }
   ```

---

## 6. Move and Rename Detection

1. **Remote Move Tracking:**
   * pCloud assigns an immutable integer `fileid`. If an item's `fileid` exists in Baseline under `PathA` but is reported at `PathB`, it is immediately classified as a `MOVED` event on Remote.
   * The local file at `PathA` is renamed to `PathB` without re-downloading.
2. **Local Move Tracking:**
   * If a file disappears from `PathA` and a new file appears at `PathB` with identical file size and identical `xxHash64`, it is classified as a local move.
   * The engine executes a remote `/movefile` API call instead of deleting and re-uploading.

