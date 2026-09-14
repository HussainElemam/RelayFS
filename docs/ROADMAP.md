# Project Roadmap & Phase Execution Tracker

This tracker maintains the live state of implementation across all development phases. Any autonomous agent working on this repository must consult this document to determine the next task and update the checklist upon task completion.

---

## Roadmap Overview

| Phase | Title | Focus Area | Status | Target Completion |
| :--- | :--- | :--- | :--- | :--- |
| **Phase 1** | [Core Architecture & Domain Models](file:///home/hussain/AndroidStudioProjects/android_sync_idea/docs/phases/phase_01_core_architecture_and_models.md) | Domain models, Room DB schema, Keystore security, CloudProvider contract | `COMPLETED` | Week 1 |
| **Phase 2** | [pCloud Client & OAuth2 PKCE](file:///home/hussain/AndroidStudioProjects/android_sync_idea/docs/phases/phase_02_pcloud_client_and_oauth.md) | pCloud REST API, OAuth2 PKCE, delta change detection, chunked upload | `COMPLETED` | Week 2 |
| **Phase 3** | [3-Way Reconciliation Engine](file:///home/hussain/AndroidStudioProjects/android_sync_idea/docs/phases/phase_03_sync_engine_core.md) | 3-way sync engine, 11-state matrix, conflict branching, atomic writes | `COMPLETED` | Week 3–4 |
| **Phase 4** | [Filesystem Watcher & Storage](file:///home/hussain/AndroidStudioProjects/android_sync_idea/docs/phases/phase_04_filesystem_watcher_and_storage.md) | Recursive FileObserver (inotify), debouncing queue, Scoped Storage | `PENDING` | Week 5 |
| **Phase 5** | [Background Sync & WorkManager](file:///home/hussain/AndroidStudioProjects/android_sync_idea/docs/phases/phase_05_background_sync_and_workmanager.md) | WorkManager periodic sync, ForegroundService (dataSync), battery handling | `PENDING` | Week 6 |
| **Phase 6** | [Compose UI & Obsidian Presets](file:///home/hussain/AndroidStudioProjects/android_sync_idea/docs/phases/phase_06_ui_dashboard_and_obsidian_mode.md) | Material 3 UI, Folder pair config, Obsidian auto-detection, Conflict Center | `PENDING` | Week 7–8 |
| **Phase 7** | [DocumentsProvider Virtual Drive](file:///home/hussain/AndroidStudioProjects/android_sync_idea/docs/phases/phase_07_documents_provider.md) | SAF DocumentsProvider, SQLite metadata cache, seekable PFD, OnClose upload | `PENDING` | Week 9–10 |
| **Phase 8** | [WebDAV Provider & Polish](file:///home/hussain/AndroidStudioProjects/android_sync_idea/docs/phases/phase_08_webdav_and_polish.md) | Generic WebDAV, in-app Markdown diff view, release preparation | `PENDING` | Week 11 |

---

## Active Phase: Phase 4 (Filesystem Watcher & Storage Permissions)

> **Agent Instruction:** Phase 3 is complete. To continue implementation, instruct the agent:  
> *"Implement Phase 4 according to docs/phases/phase_04_filesystem_watcher_and_storage.md"*

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
- [ ] `MANAGE_EXTERNAL_STORAGE` permission flow and system settings launcher
- [ ] Fallback SAF `ACTION_OPEN_DOCUMENT_TREE` support
- [ ] Recursive `FileObserver` monitoring directory trees
- [ ] Event debouncing queue (3-second quiet window)
- [ ] Unit & Robolectric tests for watcher event debouncing

### Phase 5: Background Sync & WorkManager
- [ ] `SyncWorker` implementation with network and battery constraints
- [ ] Periodic sync scheduling (configurable 15m to 24h)
- [ ] `SyncForegroundService` for real-time sync with `foregroundServiceType="dataSync"`
- [ ] Ongoing notification with progress updates (`POST_NOTIFICATIONS`)
- [ ] Battery optimization exemption prompt (`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`)

### Phase 6: Jetpack Compose UI & Obsidian Presets
- [ ] Main Dashboard with sync status and manual "Sync Now" trigger
- [ ] Account management screen (Connect pCloud via OAuth2)
- [ ] Folder Pair configuration screen (Remote folder + Local directory picker)
- [ ] "Obsidian Mode" toggle with automated `.obsidian/workspace*` exclusion rules
- [ ] Conflict Resolution Center (view and resolve branched conflict files)
- [ ] Sync history and error audit log screen

### Phase 7: Storage Access Framework `DocumentsProvider`
- [ ] `CloudDocumentsProvider` registered in `AndroidManifest.xml`
- [ ] Non-blocking `queryRoots()` and `queryChildDocuments()` using Room cache
- [ ] Asynchronous remote folder refresh with `EXTRA_LOADING`
- [ ] `openDocument("r")` with disk caching for seekable descriptors
- [ ] `openDocument("w")` using `ParcelFileDescriptor.OnCloseListener` for cloud upload
- [ ] Document thumbnails via pCloud thumbnail API

### Phase 8: WebDAV Provider & Polish
- [ ] Generic RFC 4918 WebDAV provider implementation
- [ ] In-app side-by-side Markdown diff viewer
- [ ] End-to-end integration tests
- [ ] ProGuard / R8 rules verification and release APK build

