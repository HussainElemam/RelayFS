# Feature Specification: Local Filesystem Watcher & Scoped Storage

**Feature ID:** `FEAT-03`  
**Related Components:** `core:watcher`, `core:storage`  

---

## 1. Overview & Objective
To achieve responsive synchronization without relying solely on 15-minute polling, the application monitors local directories for user modifications. This feature manages Scoped Storage permissions and implements a recursive, debounced Linux `inotify` file watcher.

---

## 2. Android Storage Permissions Strategy

### 2.1 Primary Access: `MANAGE_EXTERNAL_STORAGE` ("All Files Access")
* **Why it is required:**
  * Obsidian vaults and shared documents reside in `/storage/emulated/0/Documents/`.
  * Allows raw POSIX `java.io.File` access. Directory scans take $\le 50\text{ ms}$ for 2,000 files, compared to 30–60 seconds via `DocumentFile` SAF tree traversal.
  * Allows attaching Linux `inotify` watches via Android’s `FileObserver`.
* **Play Store Policy Compliance:**
  * Google Play explicitly permits `MANAGE_EXTERNAL_STORAGE` for apps with the core functionality of **"Device backup and sync"**.
  * The app includes an in-app declaration screen and system settings intent:
    ```kotlin
    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
        data = Uri.parse("package:${context.packageName}")
    }
    context.startActivity(intent)
    ```

### 2.2 Fallback Access: `ACTION_OPEN_DOCUMENT_TREE` (SAF)
* Used if the user explicitly refuses `MANAGE_EXTERNAL_STORAGE`.
* The user selects a specific folder using the system picker.
* Persisted via `contentResolver.takePersistableUriPermission(treeUri, flags)`.
* **Constraint:** Filesystem monitoring (`FileObserver`) is disabled; synchronization relies strictly on WorkManager periodic reconciliation.

---

## 3. Recursive Linux `FileObserver` Architecture

Android's `android.os.FileObserver` wraps Linux kernel `inotify`. In Linux, `inotify` is **non-recursive** (watching a directory does not automatically watch its children).

```
┌──────────────────────────────────────────────────────────┐
│                   RecursiveFileWatcher                   │
│                                                          │
│  Directory Root: /storage/emulated/0/Documents/Vault     │
│  ├─ Work/        [Inotify Watch Descriptor #101]         │
│  ├─ Personal/    [Inotify Watch Descriptor #102]         │
│  └─ Attachments/ [Inotify Watch Descriptor #103]         │
└────────────────────────────┬─────────────────────────────┘
                             │
                      Emits OS Events:
             CREATE, MODIFY, DELETE, MOVED_TO
                             │
                             ▼
┌──────────────────────────────────────────────────────────┐
│                     Debounce Queue                       │
│                                                          │
│  - Absorbs rapid keystrokes from text editors            │
│  - Ignores transient swap files (.tmp, ~*)               │
│  - Resets 3-second inactivity timer on each event        │
└────────────────────────────┬─────────────────────────────┘
                             │
                 Inactivity Timer Expires
                             ▼
┌──────────────────────────────────────────────────────────┐
│               Trigger Local Sync Pass                    │
└──────────────────────────────────────────────────────────┘
```

### 3.1 Recursive Watcher Implementation Details
* A `RecursiveFileWatcher` maintains a concurrent map of active `FileObserver` instances keyed by directory path:
  ```kotlin
  val observers = ConcurrentHashMap<String, FileObserver>()
  ```
* When initialized, it traverses all subdirectories recursively and registers an observer on each folder.
* **Dynamic Directory Handling:**
  * When a `CREATE` or `MOVED_TO` event occurs on a directory, the watcher immediately creates a new `FileObserver` for that new subfolder and registers its path.
  * When a `DELETE` or `MOVED_FROM` event occurs on a directory, its observer is stopped and removed from the map.

---

## 4. The Debounce Event Queue

### 4.1 The Rapid-Write Problem
Obsidian and markdown editors frequently auto-save every few seconds while the user types, or write to temporary staging files before renaming. Triggering a cloud upload on every individual `MODIFY` event wastes bandwidth, exhausts battery, and risks uploading incomplete content.

### 4.2 Debounce Logic
* When a local file event arrives:
  1. Check against **Exclusion Filters** (e.g. `.git`, `.obsidian/workspace*`, `.sync_tmp`). If matched, discard immediately.
  2. Normalize the relative path and add to a dirty paths set:
     ```kotlin
     val dirtyPaths = ConcurrentHashMap.newKeySet<String>()
     dirtyPaths.add(relativePath)
     ```
  3. Reset a Coroutine-based **3-second debounce timer**:
     ```kotlin
     debounceJob?.cancel()
     debounceJob = scope.launch {
         delay(3000) // 3 seconds of silence
         val pathsToSync = dirtyPaths.toList()
         dirtyPaths.clear()
         syncEngine.triggerPartialLocalSync(folderPairId, pathsToSync)
     }
     ```

---

## 5. Exclusions & Filters
Files and directories matching any of the following patterns are discarded by the watcher and reconciliation scanner:
* `**/.obsidian/workspace.json`
* `**/.obsidian/workspace-mobile.json`
* `**/.obsidian/cache/**`
* `**/.trash/**`
* `**/.git/**`
* `**/*.sync_tmp`
* `**/*.tmp`
* `**/*~`
* `**/.DS_Store`
* `**/desktop.ini`

