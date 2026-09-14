# Phase 1: Core Architecture, Domain Models & Database

**Phase Status:** `COMPLETED`  
**Estimated Effort:** 3–5 Days  
**Prerequisites:** Base Android Gradle project initialized.  

---

## 1. Phase Goal
Establish the foundational Clean Architecture layers for the application:
1. Define the immutable core domain models.
2. Build the Room SQLite database schema (entities, DAOs, converters) for folder pairs, baseline snapshots, conflicts, and audit logs.
3. Implement secure token storage backed by the **Android Keystore** (`EncryptedSharedPreferences`).
4. Finalize the `CloudProvider` interface contract.
5. Create comprehensive unit tests verifying database transactions, entity integrity, and DAO operations.

---

## 2. Detailed Task Checklist

### Task 1.1: Dependency Setup in `libs.versions.toml` & `build.gradle.kts`
- [x] Add Room runtime, compiler, and KTX (`androidx.room:room-runtime`, `androidx.room:room-compiler`, `androidx.room:room-ktx`) versions 2.6+.
- [x] Add AndroidX Security Crypto (`androidx.security:security-crypto:1.1.0-alpha06`).
- [x] Add Kotlin Coroutines & Flow testing libraries (`org.jetbrains.kotlinx:kotlinx-coroutines-test`).
- [x] Enable Room KSP plugin if not already present.

### Task 1.2: Core Domain Models (`io.relayfs.android.core.model`)
- [x] `CloudAccount.kt`: Represents a connected cloud user.
- [x] `CloudItem.kt`: Normalized remote file/folder metadata.
- [x] `SyncPair.kt`: Domain representation of a configured sync pair.
- [x] `SyncConflict.kt`: Represents an active or resolved file conflict.
- [x] `SyncResult.kt`: Summary of a completed sync pass (files transferred, errors, elapsed time).

### Task 1.3: Room SQLite Database (`io.relayfs.android.core.database`)
- [x] `FolderPairEntity.kt`: Storage table for configured folder pairs.
- [x] `SyncBaselineEntity.kt`: Storage table for the 3-way sync baseline snapshots.
- [x] `SyncConflictEntity.kt`: Storage table for conflict branches.
- [x] `SyncLogEntity.kt`: Storage table for audit and activity logs.
- [x] `FolderPairDao.kt`: CRUD operations and Flow queries for folder pairs.
- [x] `SyncBaselineDao.kt`: Fast bulk lookup, upsert, and deletion for baseline snapshots.
- [x] `AppDatabase.kt`: Main RoomDatabase declaration with database builder.

### Task 1.4: Keystore Token Storage (`io.relayfs.android.core.security`)
- [x] `TokenStorage.kt`: Interface and implementation using `EncryptedSharedPreferences` with `MasterKey.DEFAULT_MASTER_KEY_ALIAS`.
- [x] Secure methods: `saveToken(accountId, token)`, `getToken(accountId)`, `deleteToken(accountId)`.

### Task 1.5: `CloudProvider` Interface Definition (`io.relayfs.android.core.provider`)
- [x] Create `CloudProvider.kt` with all methods specified in `docs/features/01_cloud_provider_abstraction.md`.

---

## 3. Acceptance Criteria
1. All domain models and entities compile without warnings.
2. `AppDatabase` builds successfully with Room annotation processing.
3. Database unit tests run in-memory and verify:
   * Inserting and querying folder pairs.
   * Upserting baseline snapshots.
   * Querying baseline records by folder pair ID and relative path.
   * Cascading deletes when a folder pair is removed.
4. `TokenStorage` unit tests verify encryption and retrieval of OAuth tokens.

---

## 4. Verification & Testing Commands

```bash
# Verify compilation and run unit tests for database and security
./gradlew testDebugUnitTest --tests "io.relayfs.android.core.database.*"
./gradlew testDebugUnitTest --tests "io.relayfs.android.core.security.*"

# Verify entire project compiles cleanly
./gradlew assembleDebug
```

---

## 5. Agent Completion Handoff
When all tasks in this phase are complete:
1. Verify both test commands exit with code `0`.
2. Check off all boxes in this document and in `docs/ROADMAP.md`.
3. Provide a summary of created files and test results to the user.
4. **STOP** and wait for user review before proceeding to Phase 2.

