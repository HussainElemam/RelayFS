# Phase 4: Filesystem Watcher & Storage Permissions

**Phase Status:** `COMPLETED`  
**Estimated Effort:** 3–5 Days  
**Prerequisites:** [Phase 3: 3-Way Reconciliation Engine](file:///home/hussain/AndroidStudioProjects/android_sync_idea/docs/phases/phase_03_sync_engine_core.md) complete.  

---

## 1. Phase Goal
Enable real-time detection of local file edits:
1. Handle Android's `MANAGE_EXTERNAL_STORAGE` permission lifecycle cleanly.
2. Implement `RecursiveFileWatcher` utilizing Linux `inotify` via Android's `FileObserver`.
3. Build the Coroutine-based `DebounceEventQueue` (3-second quiet window) to absorb rapid text editor auto-saves.
4. Implement strict exclusion filters (`.obsidian/workspace*`, `.git`, `.tmp`).
5. Verify event debouncing and exclusion filters with automated tests.

---

## 2. Detailed Task Checklist

### Task 2.1: Storage Permissions Manager (`io.relayfs.android.core.storage`)
- [x] Implement `StoragePermissionHelper`:
  * Checks if `Environment.isExternalStorageManager()` is granted (Android 11+).
  * Generates Intent for `Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION`.
  * Provides fallback launcher for `ACTION_OPEN_DOCUMENT_TREE` with `takePersistableUriPermission`.

### Task 2.2: Exclusion Filter Engine (`io.relayfs.android.core.watcher.filter`)
- [x] Implement `PathFilter`:
  * Evaluates paths against glob and prefix rules.
  * Rejects `.git`, `.trash`, `.sync_tmp`, `workspace.json`, `workspace-mobile.json`, `.cache`.
  * Unit tests for path filtering.

### Task 2.3: Recursive `FileObserver` (`io.relayfs.android.core.watcher`)
- [x] Implement `RecursiveFileWatcher`:
  * Recursively traverses local directories and attaches `FileObserver` to all subdirectories.
  * Handles dynamic subfolder creation (`CREATE` / `MOVED_TO`) by attaching new watchers on the fly.
  * Cleans up observers when subfolders are deleted.

### Task 2.4: Coroutine Debouncing Queue (`io.relayfs.android.core.watcher.debounce`)
- [x] Implement `DebounceEventQueue`:
  * Receives file events, filters out excluded paths.
  * Collects distinct modified relative paths in a set.
  * Cancels previous timer and restarts a 3-second delay on each new event.
  * Upon timeout, emits a single batch of dirty paths to the sync pipeline.

### Task 2.5: Automated Tests
- [x] Unit tests for `DebounceEventQueue` using `kotlinx.coroutines.test.TestCoroutineScheduler` / virtual time:
  * Verify 10 rapid events within 1 second emit only 1 batch after 3 seconds.
  * Verify excluded files are completely ignored.

---

## 3. Acceptance Criteria
1. `StoragePermissionHelper` accurately reports storage permission status across API levels 26–35.
2. `RecursiveFileWatcher` detects nested file edits up to 10 subdirectories deep.
3. `DebounceEventQueue` successfully buffers rapid events into a single consolidated sync trigger.
4. All automated unit tests pass.

---

## 4. Verification & Testing Commands

```bash
# Run unit tests for watcher and debouncing queue
./gradlew testDebugUnitTest --tests "io.relayfs.android.core.watcher.*"
```

---

## 5. Agent Completion Handoff
1. Ensure all debouncer tests pass.
2. Update checklist in this document and `docs/ROADMAP.md`.
3. Report results to user and **STOP** for review.

