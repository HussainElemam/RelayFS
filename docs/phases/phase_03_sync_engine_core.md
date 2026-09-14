# Phase 3: 3-Way Reconciliation Engine & Conflict Resolution

**Phase Status:** `PENDING`  
**Estimated Effort:** 5–7 Days  
**Prerequisites:** [Phase 1](file:///home/hussain/AndroidStudioProjects/android_sync_idea/docs/phases/phase_01_core_architecture_and_models.md) and [Phase 2](file:///home/hussain/AndroidStudioProjects/android_sync_idea/docs/phases/phase_02_pcloud_client_and_oauth.md) complete.  

---

## 1. Phase Goal
Implement the core mathematical heart of RelayFS:
1. Fast local file hashing using zero-dependency **xxHash64**.
2. The 3-way reconciliation algorithm implementing the complete 11-case decision matrix.
3. Safe atomic local file writes via staging files (`.sync_tmp` rename).
4. Non-destructive conflict branching (`[Name] (conflict-Cloud-[Date]).[ext]`).
5. Remote and local move/rename detection.
6. Comprehensive unit test suite covering every single state transition in the matrix.

---

## 2. Detailed Task Checklist

### Task 3.1: Fast Local Hashing (`io.relayfs.android.core.sync.hash`)
- [ ] Implement `FastHasher`: computes `xxHash64` over file input streams with 64KB buffer chunks.
- [ ] Add unit tests verifying known hash vectors against standard xxHash outputs.

### Task 3.2: Atomic Local File Operations (`io.relayfs.android.core.sync.io`)
- [ ] Implement `AtomicFileWriter`:
  * Streams data into `.filename.sync_tmp`.
  * Calls `fd.sync()` to force physical disk flush.
  * Executes atomic filesystem rename over destination.
- [ ] Add unit tests simulating write interruption and verifying target file is never left half-written.

### Task 3.3: 3-Way Reconciliation Engine (`io.relayfs.android.core.sync.engine`)
- [ ] Implement `SyncStateCalculator`:
  * Takes `LocalSnapshot`, `RemoteSnapshot`, and `BaselineSnapshot`.
  * Evaluates differences and returns a planned list of `SyncAction` items:
    * `UploadAction`
    * `DownloadAction`
    * `DeleteLocalAction`
    * `DeleteRemoteAction`
    * `ConflictBranchAction`
    * `MoveLocalAction`
    * `MoveRemoteAction`
- [ ] Implement `ConflictResolver`: generates formatted branch filenames and records entries in `SyncConflictEntity`.
- [ ] Implement `SyncExecutionPipeline`: executes the planned actions sequentially with error recovery and updates the Room baseline database in a single transaction per successful file.

### Task 3.4: Comprehensive Unit Tests
- [ ] Implement `ReconciliationMatrixTest` testing all 11 cases:
  * Case 1: Both unchanged $\rightarrow$ No-op.
  * Case 2: Local modified, remote unchanged $\rightarrow$ Upload.
  * Case 3: Remote modified, local unchanged $\rightarrow$ Download.
  * Case 4: Local created $\rightarrow$ Upload.
  * Case 5: Remote created $\rightarrow$ Download.
  * Case 6: Local deleted $\rightarrow$ Remote delete.
  * Case 7: Remote deleted $\rightarrow$ Local delete.
  * Case 8: Both modified, identical hashes $\rightarrow$ Resolve without transfer.
  * Case 9: Both modified, differing hashes $\rightarrow$ Conflict branch created.
  * Case 10: Local modified, remote deleted $\rightarrow$ Conflict preserve local.
  * Case 11: Local deleted, remote modified $\rightarrow$ Conflict re-download.

---

## 3. Acceptance Criteria
1. `AtomicFileWriter` guarantees that corrupted or partial files never replace existing files.
2. All 11 cases of the reconciliation matrix are covered by deterministic unit tests.
3. Conflict files are named according to specification and logged in Room `sync_conflicts`.
4. Zero regressions in previously implemented Phase 1 & 2 tests.

---

## 4. Verification & Testing Commands

```bash
# Run unit tests for sync engine and reconciliation matrix
./gradlew testDebugUnitTest --tests "io.relayfs.android.core.sync.*"
```

---

## 5. Agent Completion Handoff
1. Verify all matrix tests pass with 100% green status.
2. Update checklist in this document and `docs/ROADMAP.md`.
3. Report results to user and **STOP** for review.

