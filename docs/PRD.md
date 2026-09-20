# Product Requirements Document (PRD)

## Project Name: RelayFS
**Document Version:** 1.0.0  
**Status:** Approved for Implementation  
**Target Platform:** Android 8.0+ (API Level 26+), Optimized for Android 11–15 (API Levels 30–35)  
**Primary Language/Framework:** Kotlin, Jetpack Compose, AndroidX, Kotlin Coroutines & Flow  

---

## 1. Executive Summary & Vision

### 1.1 Problem Statement
Cloud storage services like **pCloud** offer desktop users (on Linux/Fedora, macOS, and Windows) virtual drive access (`pfs` FUSE) and selective bidirectional folder synchronization. On Android, however, pCloud and similar services provide only an isolated application with camera backup and proprietary offline caching. They lack:
1. **System-Level Virtual Drive Integration:** Files cannot be browsed or opened on-demand through Android’s native Storage Access Framework (`DocumentsProvider`) in the system file picker.
2. **Local Folder Synchronization:** They cannot map a remote cloud folder to a physical local directory on the device (e.g., `/storage/emulated/0/Documents/Obsidian/MyVault`).
3. **Support for Offline-First Apps:** Applications like **Obsidian**, Logseq, KeePassDX, and retro emulators require synchronous POSIX filesystem access to physical files. Because pCloud lacks local folder sync, mobile users cannot keep their vaults and documents synchronized without complex, closed-source, or battery-draining workarounds.

### 1.2 Proposed Solution
**RelayFS** is an open-source, privacy-first Android utility that acts as a bridge between cloud storage providers and the Android filesystem. It provides two complementary features:
1. **Local Folder Synchronization Engine:** A background service that maintains continuous, two-way parity between arbitrary remote cloud folders and physical local directories on Android, equipped with 3-way reconciliation, non-destructive conflict branching, file monitoring, and specialized presets for tools like Obsidian.
2. **Virtual Cloud Drive (`DocumentsProvider`):** An implementation of Android's Storage Access Framework that presents cloud files on-demand in the native Android file picker, complete with seekable random access for media/PDF viewers and background upload hooks when external apps save edits.

### 1.3 Target Audience & Personas
* **The Obsidian/PKM User:** Needs their Markdown vault kept synchronized between Linux/Fedora and Android without paying for proprietary sync subscriptions or running 24/7 home servers.
* **The Privacy-Conscious Cloud User:** Uses pCloud, Nextcloud, or WebDAV for data ownership and refuses to store credentials in closed-source, ad-supported sync utilities.
* **The Power User:** Demands fine-grained control over sync frequency, Wi-Fi constraints, conflict handling, and system-level file picker access.

---

## 2. High-Level Architecture & Core Concepts

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                             Cloud Providers                                 │
│                   [ pCloud REST API ]     [ Generic WebDAV ]                │
└──────────────────────────────────────┬──────────────────────────────────────┘
                                       │
┌──────────────────────────────────────▼──────────────────────────────────────┐
│                       CloudProvider Abstraction Layer                       │
│    listFolder() | getDeltaChanges() | downloadFile() | uploadFile() | move() │
└───────────────────────┬─────────────────────────────┬───────────────────────┘
                        │                             │
┌───────────────────────▼──────────────┐   ┌──────────▼───────────────────────┐
│     Local Folder Sync Engine         │   │   SAF DocumentsProvider          │
│  - 3-Way Snapshot Reconciler         │   │   (Virtual Cloud Drive)          │
│  - Room Baseline Database            │   │  - Non-blocking SQLite Cache     │
│  - Recursive FileObserver (Inotify)  │   │  - Seekable Disk Cache PFD       │
│  - Debounce Buffer & Atomic Writes   │   │  - OnCloseListener Upload Hook   │
│  - WorkManager Periodic Sync         │   │                                  │
└───────────────────────┬──────────────┘   └──────────┬───────────────────────┘
                        │                             │
┌───────────────────────▼──────────────┐   ┌──────────▼───────────────────────┐
│       Physical Android Storage       │   │       System File Picker         │
│     /storage/emulated/0/Documents/   │   │         (DocumentsUI)            │
│       (Obsidian, Logseq, etc.)       │   │  (Third-party viewer/editor apps)│
└──────────────────────────────────────┘   └──────────────────────────────────┘
```

The system is strictly divided into:
1. **`CloudProvider` Layer:** Stateless, decoupled network abstractions for remote cloud storage.
2. **Sync Engine:** Stateful reconciliation engine operating against a local Room baseline database.
3. **Filesystem Watcher:** Debounced Linux `inotify` wrapper running inside an optional foreground service.
4. **DocumentsProvider:** Virtual filesystem bridge to Android's Storage Access Framework.
5. **UI & Configuration:** Modern Jetpack Compose interface with zero ads, analytics, or tracking SDKs.

---

## 3. Functional Requirements

### 3.1 Cloud Provider Integration (FR-1)
* **FR-1.1 (pCloud OAuth2 PKCE):** Authenticate with pCloud using OAuth 2.0 with PKCE via Chrome Custom Tabs. No passwords or 2FA codes are ever handled or stored by the app.
* **FR-1.2 (Datacenter Routing):** Detect pCloud account location (`locationid`: US vs. EU) during OAuth exchange and route all subsequent API requests to `api.pcloud.com` or `eapi.pcloud.com`.
* **FR-1.3 (Delta Sync via `diff`):** Utilize pCloud’s native `diff` endpoint with `diffid` tracking to fetch remote change feeds incrementally without scanning full directory trees.
* **FR-1.4 (Persistent File IDs):** Utilize pCloud's persistent `fileid` integers to track remote renames and file moves without deleting and re-uploading content.
* **FR-1.5 (Resumable Chunked Uploads):** For files $>5\text{ MB}$, execute multi-part uploads via `upload_create`, `upload_write`, and `upload_save` to enable resumption after connection drops.
* **FR-1.6 (WebDAV Provider - Deferred to v2):** Support RFC 4918 WebDAV endpoints with HTTP Basic/Digest authentication for Nextcloud, ownCloud, and generic NAS servers (Planned for v2 roadmap; v1 focuses strictly on native pCloud).

### 3.2 Two-Way Synchronization Engine (FR-2)
* **FR-2.1 (Folder Pairs):** Allow users to configure multiple folder pairs mapping a remote cloud directory to a local Android filesystem directory.
* **FR-2.2 (Sync Directions):**
  * Two-Way (Bidirectional with conflict detection) — *Default*
  * Cloud $\rightarrow$ Local (Mirror remote to local; local changes overwritten or blocked)
  * Local $\rightarrow$ Cloud (Backup local to remote)
* **FR-2.3 (3-Way Baseline Reconciliation):** Maintain a local SQLite baseline snapshot of all synced files. Differentiate between file modifications, creations, deletions, and moves on both sides.
* **FR-2.4 (Atomic File Writes):** When downloading remote changes, write to a temporary staging file (`.filename.sync_tmp`), sync to disk (`fsync`), and atomically rename over the destination.
* **FR-2.5 (Deletion Propagation):** Propagate deletions bidirectionally using stored baseline records (tombstones). If a file was deleted locally while untouched remotely, delete it remotely.
* **FR-2.6 (Move/Rename Detection):** Recognize local moves via `inotify` cookies and hash matches, and remote moves via `fileid`. Execute moves directly via API rather than delete-plus-upload.

### 3.3 Conflict Resolution (FR-3)
* **FR-3.1 (True Conflict Detection):** Trigger conflict logic only when a file was modified on **both** sides since the baseline snapshot and their content hashes differ. If hashes match, auto-resolve as an identical edit.
* **FR-3.2 (Non-Destructive Branching):** Never overwrite or drop user data during a conflict. Keep the local version at its original path and download the remote version under the branched naming format:
  `[Filename] (conflict - Cloud - YYYY-MM-DD-HHmm).[ext]`
* **FR-3.3 (Conflict Notification & History):** Post a system notification when conflicts occur and log them in the in-app Conflict Center for user inspection.

### 3.4 Filesystem Monitoring & Scheduling (FR-4)
* **FR-4.1 (Recursive FileObserver):** Attach recursive Linux `inotify` watches to user-selected local directories while the app process or foreground service is running.
* **FR-4.2 (Debouncing Queue):** Buffer local file modification events for a configurable delay (default: **3 seconds** of silence) to prevent syncing intermediate or partial writes from text editors.
* **FR-4.3 (Periodic Background Sync):** Schedule periodic background reconciliation passes via AndroidX `WorkManager` (configurable from 15 minutes to 24 hours).
* **FR-4.4 (Network & Battery Constraints):** Allow users to restrict background synchronization to unmetered Wi-Fi connections only, require device charging, or pause when battery is low.
* **FR-4.5 (Foreground Service for Live Sync):** Provide an optional persistent notification service (`foregroundServiceType="dataSync"`) to keep `FileObserver` alive for near-instantaneous continuous synchronization.

### 3.5 Obsidian Vault Preset (FR-5)
* **FR-5.1 (Vault Auto-Detection):** Detect if a selected directory contains an `.obsidian` folder and offer an "Obsidian Mode" profile.
* **FR-5.2 (Workspace Exclusions):** Automatically exclude device-specific workspace state files:
  * `.obsidian/workspace.json`
  * `.obsidian/workspace-mobile.json`
  * `.obsidian/cache/`
  * `.trash/`
* **FR-5.3 (Plugin & Config Sync):** Ensure `.obsidian/app.json`, `.obsidian/community-plugins.json`, and plugin directories are safely synchronized.

### 3.6 Storage Access Framework `DocumentsProvider` (FR-6)
* **FR-6.1 (System File Picker Root):** Expose configured cloud accounts as browsable roots in Android's native `DocumentsUI` picker.
* **FR-6.2 (Non-Blocking Caching):** Serve `queryRoots()` and `queryChildDocuments()` synchronously from a local SQLite metadata cache to prevent UI freezing, triggering asynchronous network refreshes in the background with `EXTRA_LOADING`.
* **FR-6.3 (Seekable Read Streams):** Download files to on-demand disk cache before returning a `ParcelFileDescriptor` to guarantee random seek capability for PDF readers and media players.
* **FR-6.4 (Write Hooks via OnCloseListener):** When external apps write to a cloud document, capture the descriptor close event using `ParcelFileDescriptor.OnCloseListener` and enqueue an immediate background upload to the cloud.

---

## 4. Non-Functional Requirements

* **Performance:**
  * Local vault scan time: $\le 100\text{ ms}$ for 2,000 files using POSIX `java.io.File` (`MANAGE_EXTERNAL_STORAGE`).
  * System file picker folder open latency: $\le 50\text{ ms}$ on cached directories.
* **Reliability & Data Integrity:**
  * Zero silent data loss under any failure mode (interrupted network, process death, battery exhaustion).
  * All database operations wrapped in ACID transactions.
* **Security & Privacy:**
  * Zero analytics, telemetry, crash reporting trackers, or third-party ad SDKs.
  * All OAuth tokens and secrets encrypted using the **Android Keystore** with AES-256-GCM.
  * Direct device-to-cloud communication; no intermediate proxy servers.
* **Battery Consumption:**
  * Background idling must consume $<1\%$ battery over a 24-hour period.
  * Must conform strictly to Android Doze mode and App Standby Buckets.

---

## 5. Permissions & Android OS Compliance

| Permission | Justification | Fallback Strategy |
| :--- | :--- | :--- |
| `MANAGE_EXTERNAL_STORAGE` | Required for low-latency scanning of local Obsidian vaults and recursive `FileObserver` monitoring. Eligible under Google Play "Device backup and sync" policy. | Fall back to `ACTION_OPEN_DOCUMENT_TREE` (SAF) with explicit UI warning about slower scan speeds. |
| `INTERNET` / `ACCESS_NETWORK_STATE` | Required to communicate with cloud APIs and monitor network type (Wi-Fi vs. cellular). | Essential; app cannot function without it. |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_DATA_SYNC` | Required for long-running sync operations and continuous file watching in Android 14+. | WorkManager periodic jobs only. |
| `POST_NOTIFICATIONS` | Required on Android 13+ to display sync progress notifications and conflict alerts. | Silent sync; conflicts visible only in in-app dashboard. |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Required on aggressive OEM skins (Samsung, Xiaomi) to prevent killing background sync. | Prompt user with direct link to system settings if sync delays are detected. |

---

## 6. Out of Scope (Initial Release)
* **pCloud Crypto Folder Decryption:** Client-side zero-knowledge encryption requires embedding custom C/C++ crypto routines; deferred to a future crypto add-on.
* **Interactive In-App 3-Way Text Diff Merge Tool:** The initial version will branch conflict files; interactive line-by-line diff merging is deferred to Phase 3.
* **Direct LAN Peer-to-Peer Sync:** This is already solved by Syncthing. RelayFS focuses strictly on cloud storage integration.

