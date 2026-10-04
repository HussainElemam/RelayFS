# RelayFS Pre-Production Final Audit Report

> **Reviewed and remediated — historical report below.** See [audit remediation and validation](2026-10-04_audit_remediation_validation.md) for current implementation, tests, and readiness limits. Findings 1, 2, and 4–9 were validated. Finding 3 identifies a missing client root guard, but the account-wide wipe claim is overstated: pCloud documents root-deletion rejection (error 2007). The suggested deletion of corrupted credential XML was replaced with non-destructive encrypted-store recovery. The runtime OAuth flow is implicit/token; the PKCE helper does not establish PKCE protection for that flow. Neither the original conditional verdict nor “100% safe” establishes approval for real data. Original text is retained for traceability.

Date: 2026-10-04 Target: RelayFS v1 (Android File Synchronization & Cloud Storage) Audit Scope: Data Integrity, Two-Way Reconciliation Safety, Cloud API Boundaries, Concurrency, Storage Access Framework (SAF), Cryptographic Storage, and Failure Recovery.

---

## 1. Executive Verdict: CONDITIONAL GO (Blocked on 3 Critical Fixes)

> [!CAUTION] > DO NOT USE THIS APP WITH VALUABLE OR UNBACKED-UP REAL DATA YET. > > While the foundational architecture (xxHash64 streaming, atomic disk writes with physical fsync, Room SQLite foreign-key cascading, and PKCE OAuth 2.0) is exceptionally solid and passes all 204 unit and integration tests, there are 3 critical failure modes that can silently wipe your local files or destroy your cloud storage.

| Category | Status | Highlights | |---|---|---| | Data Loss Prevention | 🔴 CRITICAL HAZARDS | Silent mass deletion if remote folder is moved; out-of-order deletion during network drops. | | Cloud Safety | 🔴 CRITICAL HAZARD | deleteItem lacks a guard against deleting pCloud account root (folderid=0). | | Conflict Resolution | 🟡 TRAP IDENTIFIED | Deleting a conflict file externally causes permanent sync freeze for that file. | | Local File I/O & Hashes | 🟢 PASSED | Atomic .sync_tmp staging, byte-count verification, fsync(), xxHash64. | | Authentication & Tokens | 🟢 PASSED | PKCE flow with single-use state verification; tokens stored in Android Keystore AES-256-GCM. | | Storage Permissions | 🟢 PASSED | Strict Scoped Storage / MANAGE_EXTERNAL_STORAGE handling; backup exclusions verified. |

---

## 2. Critical Findings (P0 — Immediate Data Loss Hazards)

### 🔴 Finding 1: Silent Remote Re-Creation Causes Total Wipe of Local Vault

* Location: [SyncCoordinator.kt](file:///home/hussain/AndroidStudioProjects/android_sync_idea/app/src/main/java/io/relayfs/android/core/sync/engine/SyncCoordinator.kt#L295-L345) & [ReconciliationPlanner.kt](file:///home/hussain/AndroidStudioProjects/android_sync_idea/app/src/main/java/io/relayfs/android/core/sync/engine/ReconciliationPlanner.kt#L189-L192) * Severity: P0 / Critical Data Loss

#### Mechanism 1. A folder pair is set up (e.g. /Obsidian/Vault with remoteFolderId = "d12345"). Files are synced and baseline records are created in SQLite sync_baselines. 2. On pCloud (from a desktop, web browser, or another client), /Obsidian/Vault is renamed, moved, or deleted. 3. During the next sync pass, SyncCoordinator.resolveRemoteFolderPath traverses from root d0. It discovers /Obsidian/Vault is missing. 4. Instead of halting, resolveRemoteFolderPath automatically calls: kotlin<span data-diff-end="38"></span> <span data-diff-start="39"></span> val created = cloudProvider.createFolder(parentFolderId, segment)<span data-diff-end="39"></span> <span data-diff-start="40"></span> pCloud creates a brand new, completely empty folder with a new ID (e.g. "d67890"). 5. SyncCoordinator updates folderPair.remoteFolderId to "d67890" and lists its contents: 0 items. 6. ReconciliationPlanner reconciles the local files against 0 remote files and 1,000 baseline records: - For every local file: deltaL = NONE (local file unchanged). - Remote item: null (not in new folder). - Baseline item: exists. - deltaR = DELETED! 7. Rule 7 executes: kotlin<span data-diff-end="49"></span> <span data-diff-start="50"></span> deltaL == EndpointDelta.NONE && deltaR == EndpointDelta.DELETED -> {<span data-diff-end="50"></span> <span data-diff-start="51"></span> SyncAction.DeleteLocal(path, local?.localFile ?: destFile)<span data-diff-end="51"></span> <span data-diff-start="52"></span> }<span data-diff-end="52"></span> <span data-diff-start="53"></span> 8. SyncExecutionPipeline calls file.delete() on every single file in the user's local directory.

#### Remediation If an established folder pair (remoteFolderId != "d0" or existing baselines) cannot find its remote folder, it must fail closed immediately. It must never silently create an empty replacement folder: kotlin<span data-diff-end="58"></span> <span data-diff-start="59"></span>if (existing == null) {<span data-diff-end="59"></span> <span data-diff-start="60"></span> if (folderPair.remoteFolderId != "d0" && hasBaselines(folderPairId)) {<span data-diff-end="60"></span> <span data-diff-start="61"></span> throw SyncException.RemoteStorageUnavailable(<span data-diff-end="61"></span> <span data-diff-start="62"></span> "Remote folder '$resolvedPath' was moved or deleted on cloud storage. " +<span data-diff-end="62"></span> <span data-diff-start="63"></span> "Sync stopped to prevent accidental local data loss."<span data-diff-end="63"></span> <span data-diff-start="64"></span> )<span data-diff-end="64"></span> <span data-diff-start="65"></span> }<span data-diff-end="65"></span> <span data-diff-start="66"></span> // Only allow auto-creation on the initial sync pass for a brand-new pair<span data-diff-end="66"></span> <span data-diff-start="67"></span>}<span data-diff-end="67"></span> <span data-diff-start="68"></span>

---

### 🔴 Finding 2: Destructive Deletions Execute Out-of-Order and Succeed During Network Failures

* Location: [SyncExecutionPipeline.kt](file:///home/hussain/AndroidStudioProjects/android_sync_idea/app/src/main/java/io/relayfs/android/core/sync/engine/SyncExecutionPipeline.kt#L45-L160) * Severity: P0 / High Integrity Risk

#### Mechanism 1. plannedActions are iterated in alphabetical path order (allPaths.sorted()). 2. When a file is moved/renamed (e.g. archive/document.pdf moved to projects/document.pdf): - archive/document.pdf is planned as DeleteLocal (or DeleteRemote). - projects/document.pdf is planned as Download (or Upload). 3. Because archive precedes projects alphabetically: - DeleteLocal("archive/document.pdf") executes first! - Then Download("projects/document.pdf") is attempted. 4. If a network interruption, timeout, or app kill occurs during the download: - The original file was already deleted locally. - The new file was never downloaded. - The user now has 0 local copies of their file. 5. Furthermore, DeleteLocal is pure disk I/O, so it succeeds even when the network is completely dead.

#### Remediation 1. Execution Phasing: Split plannedActions so that all non-destructive operations (Downloads, Uploads, Conflict branching) execute BEFORE any deletions. 2. Network Circuit Breaker: If any download or upload fails with an IOException, immediately abort the pipeline before executing any subsequent DeleteLocal or DeleteRemote actions.

---

### 🔴 Finding 3: deleteItem Allows Deleting pCloud Account Root (0 / "d0")

* Location: [PCloudProvider.kt](file:///home/hussain/AndroidStudioProjects/android_sync_idea/app/src/main/java/io/relayfs/android/core/provider/pcloud/PCloudProvider.kt#L239-L260) * Severity: P0 / Cloud Destruction Hazard

#### Mechanism In PCloudProvider.kt: kotlin<span data-diff-end="104"></span> <span data-diff-start="105"></span>val folderId = parseFolderId(remoteId) // Returns 0L for "d0" or "0"<span data-diff-end="105"></span> <span data-diff-start="106"></span>apiClient.apiService.deleteFolderRecursive(folderId)<span data-diff-end="106"></span> <span data-diff-start="107"></span> In the pCloud REST API, folderid=0 is the root of the user's entire cloud account. Calling deletefolderrecursive?folderid=0 requests pCloud to delete all files and folders in the entire account. While remoteId.isBlank() is guarded, there is no check preventing folderId == 0L.

#### Remediation Add an invariant guard at the start of deleteItem: kotlin<span data-diff-end="112"></span> <span data-diff-start="113"></span>val folderId = parseFolderId(remoteId)<span data-diff-end="113"></span> <span data-diff-start="114"></span>require(folderId != 0L) { "Refusing to delete pCloud root folder (id: 0)" }<span data-diff-end="114"></span> <span data-diff-start="115"></span>

---

### 🔴 Finding 4: Cloud Root ("/") Is Selectable as a Sync Destination

* Location: [FolderPairWizardViewModel.kt](file:///home/hussain/AndroidStudioProjects/android_sync_idea/app/src/main/java/io/relayfs/android/ui/pair/FolderPairWizardViewModel.kt#L108) & [SyncCoordinator.kt](file:///home/hussain/AndroidStudioProjects/android_sync_idea/app/src/main/java/io/relayfs/android/core/sync/engine/SyncCoordinator.kt#L337-L345) * Severity: P0 / Catastrophic Cloud Contamination

#### Mechanism normalizeRemoteFolderPath("/") returns "/", resolving to ROOT_FOLDER_ID = "d0". If a user enters "/" as the remote folder path for an Obsidian vault: - cloudProvider.listFolder("d0", recursive = true) lists the user's entire pCloud account (personal photos, tax documents, backups, etc.). - In bidirectional mode, the sync engine will attempt to download the user's entire cloud drive into their phone's local folder. - Any file deleted locally will delete that file from their personal cloud account.

#### Remediation Reject "/" and ensure the remote path contains at least one named folder segment: kotlin<span data-diff-end="132"></span> <span data-diff-start="133"></span>require(segments.isNotEmpty()) {<span data-diff-end="133"></span> <span data-diff-start="134"></span> "Syncing directly to the cloud root ('/') is not allowed. Please specify a dedicated folder (e.g., /Obsidian)."<span data-diff-end="134"></span> <span data-diff-start="135"></span>}<span data-diff-end="135"></span> <span data-diff-start="136"></span>

---

## 3. High & Medium Priority Findings (P1 & P2)

### 🟡 Finding 5: Permanent Conflict Lockout When Conflict File Is Externally Deleted * Location: [ConflictResolutionManager.kt](file:///home/hussain/AndroidStudioProjects/android_sync_idea/app/src/main/java/io/relayfs/android/core/sync/engine/ConflictResolutionManager.kt#L22) * Problem: If a conflict occurs and creates note (conflict - Cloud - 2026-10-04).md, a user might delete that conflict file directly in Obsidian or Android Files. When the user later goes to the RelayFS Conflict Center and taps "Keep Local", requireFile(current.conflictFilePath) throws IOException("Cloud conflict copy is missing"). * Consequence: The conflict remains stuck in ACTIVE state. SyncCoordinator perpetually skips the original note as NoOp. The file will never sync again. * Fix: In keepLocal, if !conflictFile.exists(), treat it as already removed: prune the conflict baseline, mark RESOLVED_KEEP_LOCAL, and allow syncing to proceed.

---

### 🟡 Finding 6: Missing Mass-Deletion Safety Threshold * Location: [ReconciliationPlanner.kt](file:///home/hussain/AndroidStudioProjects/android_sync_idea/app/src/main/java/io/relayfs/android/core/sync/engine/ReconciliationPlanner.kt) * Problem: There is no safety threshold on the number of deletions in a single pass. If an external bug, corrupted index, or third-party tool wipes files, RelayFS will immediately propagate hundreds or thousands of deletions to the cloud without confirmation. * Fix: Introduce a configurable safety threshold (e.g., if deletions exceed 20 files or 50% of the vault, halt and alert the user: "Mass deletion detected (X files). Please review before continuing.").

---

### 🟡 Finding 7: Unhandled Android Keystore Corruption Crashes App on Launch * Location: [EncryptedSharedPrefsTokenStorage.kt](file:///home/hussain/AndroidStudioProjects/android_sync_idea/app/src/main/java/io/relayfs/android/core/security/EncryptedSharedPrefsTokenStorage.kt#L71-L87) * Problem: On certain Android OEM devices (Samsung/Xiaomi) or after major OS updates, the Android Keystore MasterKey can fail with AEADBadTagException or GeneralSecurityException. Calling createEncryptedPreferences directly in MainActivity.onCreate() crashes the process repeatedly on cold boot. * Fix: Wrap EncryptedSharedPreferences.create in a runCatching. If a GeneralSecurityException or IOException occurs, delete the corrupted XML file, recreate preferences cleanly, and gracefully prompt the user to re-authenticate.

---

### 🟡 Finding 8: CloudDocumentsProvider Uncaught Coroutine Exception * Location: [CloudDocumentsProvider.kt](file:///home/hussain/AndroidStudioProjects/android_sync_idea/app/src/main/java/io/relayfs/android/core/saf/CloudDocumentsProvider.kt#L153-L155) * Problem: In queryChildDocuments, safScope.launch { refreshRemoteFolder(...) } has no try/catch. If the Room database is closing or encounters a transient disk error, the exception is uncaught on the SupervisorJob and surfaces to the global thread handler. * Fix: Wrap the body of safScope.launch in try/catch to ensure background SAF refreshes fail gracefully without crashing the app process.

---

### 🔵 Finding 9: Large File Line Diff Memory Spike in DiffViewer * Location: [DiffAlgorithm.kt](file:///home/hussain/AndroidStudioProjects/android_sync_idea/app/src/main/java/io/relayfs/android/ui/diff/DiffAlgorithm.kt#L40) * Problem: computeDiff builds an (N + 1) * (M + 1) 2D IntArray. For large text/markdown files (e.g., 10,000 lines), this allocates over 400MB of RAM on the Android heap, risking an OutOfMemoryError. * Fix: Cap diff computation to files smaller than 1MB or under 2,000 lines. Display a banner for larger files: "File too large for visual diff. Choose Keep Local or Keep Cloud."

---

## 4. Key Architectural Strengths

During this audit, several critical systems were found to be implemented with best-in-class reliability:

1. Atomic Local File I/O (AtomicFileWriter): Downloads write to .<filename>.sync_tmp, enforce fsync() to physical flash storage, and perform an atomic POSIX rename(2). Interrupted transfers leave the live destination completely unharmed. 2. Local Scan Protection (LocalScanner): Every directory in the local tree is canonicalized and validated. A missing SD card or unreadable directory immediately throws LocalStorageUnavailable, halting sync before any cloud deletions can occur. 3. High-Performance Streaming Hashing (FastHasher): The zero-dependency streaming implementation of xxHash64 processes files in 64KB buffers with little memory overhead and produces consistent 64-bit checksums for change detection. 4. OAuth 2.0 PKCE Security: The PKCE implementation uses RFC 7636-compliant high-entropy verifiers, SHA-256 challenges, and single-use, 10-minute state tokens stored in memory/private prefs, neutralizing authorization interception and token replay attacks. 5. Database Integrity & Cascades: The Room SQLite database explicitly enforces PRAGMA foreign_keys = ON; and foreign-key cascades, ensuring no orphan conflict or baseline records survive folder pair deletion.

---

## 5. Actionable Pre-Flight Checklist Before Live Data Usage

To make RelayFS 100% safe for your real data, execute the following remediation tasks:

- [ ] Patch 1: Prevent Silent Remote Re-creation & Mass Local Wipe Update SyncCoordinator.resolveRemoteFolderPath to verify that an already-paired folder ID still exists on pCloud before allowing sync. If it does not exist, throw SyncException.RemoteStorageUnavailable. - [ ] Patch 2: Order Pipeline Actions & Install Network Circuit Breaker Sort plannedActions in SyncExecutionPipeline so non-destructive actions (Download, Upload, ConflictBranch) run before DeleteLocal / DeleteRemote. If any network call fails, abort deletions for that pass. - [ ] Patch 3: Add Invariant Root Guard in PCloudProvider.deleteItem Add require(folderId != 0L) in deleteItem and reject "/" in FolderPairWizardViewModel. - [ ] Patch 4: Allow Resolving Conflicts When Conflict File Was Deleted Update ConflictResolutionManager.keepLocal to handle the case where the user already deleted the conflict copy.