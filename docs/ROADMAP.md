# Project Roadmap & Phase Execution Tracker

This tracker maintains the live state of implementation across all development phases. Any autonomous agent working on this repository must consult this document to determine the next task and update the checklist upon task completion.

---

## Roadmap Overview

| Phase | Title | Focus Area | Status | Target Completion |
| :--- | :--- | :--- | :--- | :--- |
| **Phase 1** | [Core Architecture & Domain Models](file:///home/hussain/AndroidStudioProjects/android_sync_idea/docs/phases/phase_01_core_architecture_and_models.md) | Domain models, Room DB schema, Keystore security, CloudProvider contract | `COMPLETED` | Week 1 |
| **Phase 2** | [pCloud Client & OAuth2 PKCE](file:///home/hussain/AndroidStudioProjects/android_sync_idea/docs/phases/phase_02_pcloud_client_and_oauth.md) | pCloud REST API, OAuth2 PKCE, delta change detection, chunked upload | `COMPLETED` | Week 2 |
| **Phase 3** | [3-Way Reconciliation Engine](file:///home/hussain/AndroidStudioProjects/android_sync_idea/docs/phases/phase_03_sync_engine_core.md) | 3-way sync engine, 11-state matrix, conflict branching, atomic writes | `COMPLETED` | Week 3–4 |
| **Phase 4** | [Filesystem Watcher & Storage](file:///home/hussain/AndroidStudioProjects/android_sync_idea/docs/phases/phase_04_filesystem_watcher_and_storage.md) | Recursive FileObserver (inotify), debouncing queue, Scoped Storage | `COMPLETED` | Week 5 |
| **Phase 5** | [Background Sync & WorkManager](file:///home/hussain/AndroidStudioProjects/android_sync_idea/docs/phases/phase_05_background_sync_and_workmanager.md) | WorkManager periodic sync, ForegroundService (dataSync), battery handling | `COMPLETED` | Week 6 |
| **Phase 6** | [Compose UI & Obsidian Presets](file:///home/hussain/AndroidStudioProjects/android_sync_idea/docs/phases/phase_06_ui_dashboard_and_obsidian_mode.md) | Material 3 UI, Folder pair config, Obsidian auto-detection, Conflict Center | `COMPLETED` | Week 7–8 |
| **Phase 7** | [DocumentsProvider Virtual Drive](file:///home/hussain/AndroidStudioProjects/android_sync_idea/docs/phases/phase_07_documents_provider.md) | SAF DocumentsProvider, SQLite metadata cache, seekable PFD, OnClose upload | `COMPLETED` | Week 9–10 |
| **Phase 8** | [Diff Viewer, Polish & Release Prep](file:///home/hussain/AndroidStudioProjects/android_sync_idea/docs/phases/phase_08_webdav_and_polish.md) | In-app Markdown diff view, release preparation (WebDAV deferred to v2) | `COMPLETED` | Week 11 |

---

## All Phases Completed! (Phases 1 - 8)

> **Milestone Achieved:** RelayFS v1 is feature-complete and ready for controlled pCloud validation.
> Native pCloud, the 3-way sync engine, filesystem watchers, WorkManager, Compose UI, SAF virtual drive, conflict diff viewer, and R8 builds are implemented. Live-account acceptance testing and release signing remain explicit pre-release gates. Generic WebDAV is deferred to v2.

---

## Phase Status Summary

### Phase 1: Core Architecture & Domain Models
- [x] Multi-module or package structure established
- [x] Core domain models defined (`SyncPair`, `FileItem`, `SyncConflict`, `AccountInfo`)
- [x] Room SQLite database entities and DAOs created (`FolderPairEntity`, `SyncBaselineEntity`, `SyncLogEntity`)
- [x] Keystore token storage implemented (`TokenStorage` with `EncryptedSharedPreferences`)
- [x] `CloudProvider` interface contract finalized
- [x] Unit tests for database migrations and DAOs passing

### Phase 2: pCloud Client & OAuth2 PKCE
- [x] pCloud API models (JSON serialization for `listfolder`, `diff`, `upload`)
- [x] Retrofit / OkHttp client with OAuth Bearer interceptor
- [x] OAuth2 PKCE flow handler with Chrome Custom Tabs and redirect intent
- [x] Datacenter auto-routing (US `api.pcloud.com` vs. EU `eapi.pcloud.com`)
- [x] Delta sync parser (`diff` endpoint with `diffid` pagination)
- [x] Chunked upload implementation (`upload_create`, `upload_write`, `upload_save`)
- [x] MockWebServer unit tests for all pCloud API operations

### Phase 3: 3-Way Reconciliation Engine
- [x] Fast local content hashing implementation (xxHash64 / SHA-1)
- [x] Reconciliation state engine implementing the 11-case state matrix
- [x] Non-destructive conflict branching mechanism
- [x] Atomic local file writer (`.sync_tmp` staging and rename)
- [x] Move and rename detection (cookie matching & persistent `fileid`)
- [x] Unit tests covering all 11 reconciliation states and conflict edge cases

### Phase 4: Filesystem Watcher & Storage Permissions
- [x] `MANAGE_EXTERNAL_STORAGE` permission flow and system settings launcher
- [x] Fallback SAF `ACTION_OPEN_DOCUMENT_TREE` support
- [x] Recursive `FileObserver` monitoring directory trees
- [x] Event debouncing queue (3-second quiet window)
- [x] Unit & Robolectric tests for watcher event debouncing

### Phase 5: Background Sync & WorkManager
- [x] `SyncWorker` implementation with network and battery constraints
- [x] Periodic sync scheduling (configurable 15m to 24h)
- [x] `SyncForegroundService` for real-time sync with `foregroundServiceType="dataSync"`
- [x] Ongoing notification with progress updates (`POST_NOTIFICATIONS`)
- [x] Battery-optimization guidance via Android system settings (no restricted direct-exemption permission)

### Phase 6: Jetpack Compose UI & Obsidian Presets
- [x] Main Dashboard with sync status and manual "Sync Now" trigger
- [x] Account management screen (Connect pCloud via OAuth2)
- [x] Folder Pair configuration screen (Remote folder + Local directory picker)
- [x] "Obsidian Mode" toggle with automated `.obsidian/workspace*` exclusion rules
- [x] Conflict Resolution Center (view and resolve branched conflict files)
- [x] Sync history and error audit log screen

### Phase 7: Storage Access Framework `DocumentsProvider`
- [x] `CloudDocumentsProvider` registered in `AndroidManifest.xml`
- [x] Non-blocking `queryRoots()` and `queryChildDocuments()` using Room cache
- [x] Asynchronous remote folder refresh with `EXTRA_LOADING`
- [x] `openDocument("r")` with disk caching for seekable descriptors
- [x] `openDocument("w")` using `ParcelFileDescriptor.OnCloseListener` for cloud upload
- [x] Document thumbnails via pCloud thumbnail API

### Phase 8: Diff Viewer, Polish & Release Prep
- [ ] Generic RFC 4918 WebDAV provider (*Deferred to v2*)
- [x] In-app side-by-side Markdown diff viewer
- [x] JVM/Robolectric integration suite and physical-device launch smoke test
- [x] ProGuard / R8 rules verification and release APK build
