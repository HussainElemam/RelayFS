# RelayFS final data-safety audit — 4 October 2026

> **Historical pre-fix audit.** The findings and failing reproduction results below describe the audited snapshot before remediation. Current fixes, regression tests, reviewed findings, and remaining acceptance limits are recorded in [audit remediation and validation](2026-10-04_audit_remediation_validation.md). The original NO-GO verdict is not a verdict on the corrected working tree.

**Decision: NO-GO for real data.** The current application has reproducible paths that erase newer edits, overwrite conflict copies, or interpret invalid cloud state as file deletion. Successful ordinary tests and APK builds do not establish safe synchronization.

The audit covers the working tree, including the existing uncommitted changes, based on commit `4ce76bcfb647b0bd6b9a900a0a4bca83470e45db`. Application code was not changed. No real cloud account, user document, or installed application was modified. Tests use temporary files, in-memory Room databases, fake providers, and localhost MockWebServer responses.

## Blocking findings

Priority P1 means correct before using the affected feature with real data. “Reproduced” identifies executable evidence; “code review” identifies a verified implementation gap whose device/server consequence is inferred from the documented behavior.

### F01 — P1: Renaming/removing an established remote root can delete local files

**Source:** `app/src/main/java/io/relayfs/android/core/sync/engine/SyncCoordinator.kt:107`, `:296`, `:323`.

Every pass resolves `remoteFolderPath` from the account root and creates any missing segments, even when the pair already has a persisted remote folder ID and baseline. If `/Vault` was renamed or removed remotely, the coordinator creates a new empty `/Vault`, replaces the stored folder ID, and reconciles the existing baseline against the empty listing. Unchanged local files become `DeleteLocal` actions.

**Reproduced:** `missingEstablishedRemoteRootMustNotDeleteLocalData` leaves the original local file absent and reports one successful local deletion.

**Required:** Persist and honor the stable remote folder identity after initial setup. A missing/inaccessible established root or a different ID at the old path must stop synchronization and require explicit remapping. Restrict folder creation to deliberate initial provisioning. Add coverage for root rename, move, removal, and replacement by a different folder with the same name.

### F02 — P1: Planned writes/deletes can erase edits made after scanning

**Source:** `core/sync/engine/SyncExecutionPipeline.kt:53`, `:95`, `:126`, `:145` under `app/src/main/java/io/relayfs/android/`.

The planner uses snapshots, but the pipeline does not verify that either endpoint still matches those snapshots before overwriting or deleting. Local edits made while remote listing, earlier transfers, or a download are in progress can be replaced by an atomic rename or erased by `File.delete()`. Remote edits after listing can be overwritten by upload or erased by a stale remote-ID delete. A pair-level mutex cannot prevent Obsidian, another device, SAF edits, or another folder pair from changing the files.

**Reproduced:** `localEditAfterPlanningMustSurviveDownload`, `localEditAfterPlanningMustSurviveDeletion`, and `remoteEditAfterPlanningMustSurviveDeletion` all lose the newer version.

**Required:** Include expected endpoint identities/versions in actions; revalidate before destructive execution and again at download installation. Preserve changed destinations and branch/replan instead. Upload from immutable staging snapshots. Use provider revision/precondition support where available; where remote atomic preconditions are unavailable, preserve versions and document the remaining race rather than claim zero data loss.

### F03 — P1: Equal size and timestamp override a known changed content hash

**Source:** `core/sync/engine/SyncSnapshotModels.kt:66`, `:86`; `core/provider/pcloud/PCloudProvider.kt:309`; `core/sync/hash/FastHasher.kt:31`; `core/database/entity/SyncBaselineEntity.kt:32`.

Both delta functions return `NONE` if size and mtime match, even when supplied content hashes differ. Same-length edits with preserved timestamps or multiple remote edits within timestamp granularity can be silently missed, then overwritten or deleted as “unchanged.” Local scanning already hashes the files, so this discards available evidence.

The baseline also has one `contentHash` field for two incompatible representations: local xxHash64 in hexadecimal and the provider's numeric hash converted to decimal. The implementation does not establish that these are the same algorithm or normalize their representation. Remote change detection and identical-edit resolution therefore cannot reliably use this field.

**Reproduced:** Both `localHashChangeMustOverrideEqualSizeAndTimestamp` and `remoteHashChangeMustOverrideEqualSizeAndTimestamp` return `NONE` instead of `MODIFIED`.

**Required:** A differing comparable hash must take precedence over equal timestamps. Store local content checksums and remote revision/hash independently. Compare endpoints using an explicitly shared checksum or downloaded content; migrate baseline storage safely.

### F04 — P1: Invalid cloud listings are accepted as empty folders

**Source:** `core/provider/pcloud/PCloudProvider.kt:65`; `core/provider/pcloud/api/PCloudModels.kt:13`, `:33`; `core/sync/engine/SyncCoordinator.kt:175`.

`PCloudFolderResponse.result` defaults to success. Missing metadata returns an empty successful list; missing folder contents is also silently accepted. The coordinator cannot distinguish a valid empty folder from an incomplete response and treats baseline files as remotely deleted.

**Reproduced:** A real provider/JSON/coordinator/pipeline test with HTTP 200 and `{}` deletes the local note and reports success: `malformedSuccessfulListingMustNotMeanAllFilesDeleted`.

**Required:** Validate required response fields, requested root ID/type, file identifiers, names, and complete recursive contents. Reject malformed/partial listings before reconciliation. Add a deletion circuit breaker for unexpectedly large removal plans, while retaining strict validation for single-file deletions too.

### F05 — P1: Interrupted small uploads lack pCloud's partial-save protection

**Source:** `core/provider/pcloud/api/PCloudApiClient.kt:63`.

Single-part uploads specify `folderid` and `filename`, but omit `nopartial=1`. pCloud documents that setting `nopartial` prevents partial files being saved after a broken connection, and that same-name uploads overwrite existing files (with the previous version retained as a revision). Thus an unsuccessful request can still leave the current remote file partially uploaded. This server consequence is inferred from the [official upload contract](https://docs.pcloud.com/methods/file/uploadfile.html); live interruption testing was not performed.

**Reproduced request gap:** `smallUploadsMustDisablePartialRemoteCommits` captures `nopartial` as absent.

**Required:** Set `nopartial=1`, validate returned size/checksum, and exercise interrupted uploads against a disposable cloud folder. Preserve the prior version independently of the app's baseline; provider revision recovery must not be the only way to recover silently corrupted current content.

### F06 — P1: Account bearer credentials are sent to download hosts

**Source:** `core/provider/pcloud/api/PCloudInterceptors.kt:18`; `core/provider/pcloud/api/PCloudApiClient.kt:34`, `:180`; `core/provider/pcloud/api/PCloudModels.kt:73`.

The same authenticated OkHttp client handles API calls and CDN downloads. `AuthInterceptor` adds the token to every host without restricting the destination. Download URLs are assembled from API-provided hosts; those hosts can include a full HTTP URL. The signed download path already supplies access to the content ([pCloud getfilelink documentation](https://docs.pcloud.com/methods/streaming/getfilelink.html)). There is no need to expose the account-wide credential to the content host.

**Reproduced:** `downloadHostMustNotReceiveAccountBearerToken` receives `Authorization: Bearer audit-fake-secret` at a localhost content server unrelated to the configured API endpoint. The fake token is not a real credential.

**Required:** Use an unauthenticated client for downloads/thumbnails; scope authentication to approved HTTPS API origins, and validate HTTPS content URLs/redirect behavior. Verify that foreign-host and cleartext requests cannot acquire the bearer header.

### F07 — P1: Conflict filenames are not unique and existing copies are overwritten

**Source:** `core/sync/engine/ConflictResolver.kt:16`, `:22`; `core/sync/engine/ReconciliationPlanner.kt:217`; `core/sync/engine/SyncExecutionPipeline.kt:173`.

Conflict names have minute precision and are chosen without reserving a unique path. Downloads atomically replace that path even when it already contains a prior conflict, edits made to a preserved copy, or an ordinary user file. This occurs after quick repeated conflict/resolution cycles, duplicate pairs, a failed conflict-record insertion, or a preexisting matching filename. The singleton `SimpleDateFormat` is also shared across independently syncing pairs without thread safety.

**Reproduced:** `occupiedConflictFilenameMustNeverBeOverwritten` replaces “previous unique conflict edits” with the new remote version.

**Required:** Reserve unique sibling paths with exclusive creation/UUID suffixes, never replace existing branch files, and use thread-safe time formatting. Make conflict insertion and baseline changes transactional and recoverable after process death.

### F08 — P1: The virtual drive serves stale bytes and can overwrite newer cloud edits

**Source:** `core/saf/CloudDocumentsProvider.kt:217`, `:247`, `:272`; `core/work/CloudUploadWorker.kt:58`.

Read cache validity is based on file existence/nonzero length, with no stored cloud version. Even after successful metadata refresh reports a different modification time, the old bytes remain indefinitely. Write-open copies these stale bytes, and the worker uploads by parent/name without a version check or conflict branch. Editing through another app can therefore replace cloud updates made since the original download or while offline.

**Reproduced:** `refreshedSafMetadataMustInvalidateOldReadContent` still returns “original” after remote content and refreshed metadata change to “updated!”. Unconditional upload behavior is verified by code review.

**Required:** Associate cached content with a cloud version; invalidate/revalidate on change, separately track unsaved local edits, record the version at write-open, and preserve both versions if the cloud changes before upload. Route SAF writes through the same data-safety controls as folder sync.

### F09 — P1: Pending virtual-drive edits live only in an evictable cache

**Source:** `core/saf/CloudDocumentsProvider.kt:63`, `:243`, `:285`; `core/work/CloudUploadWorker.kt:33`, `:45`.

The read copy and upload staging file both reside under `Context.cacheDir`. Android can delete cache files under storage pressure ([Android storage documentation](https://developer.android.com/training/data-storage/app-specific)). An edit saved while offline may therefore lose both local copies before upload. The worker then returns terminal failure when the file is absent. A missing token also returns failure instead of preserving a recoverable pending upload. The close callback copies the read cache before enqueueing and has no durable save journal, leaving a process-death/error window between acknowledged editing and persistent scheduling.

**Evidence:** Code review of storage paths, close ordering, and failure branches; OS eviction and process-death outcomes inferred from the documented lifecycle. Not simulated on a device.

**Required:** Store pending edits in persistent app storage with fsync and a Room outbox. Persist intent before acknowledging saves; recover orphaned files/jobs on startup. Keep visible pending/error status and an export/retry path until upload is verified. Reauthentication must preserve pending work.

### F10 — P1: Valid pCloud hash values cannot be decoded

**Source:** `core/provider/pcloud/api/PCloudModels.kt:30`.

The `hash` field is a signed Kotlin `Long`, but official pCloud examples contain values above `Long.MAX_VALUE`, including `10681749967730527559`. JSON decoding throws numeric overflow. A legitimate file can make the entire recursive listing fail; an upload can commit remotely and then be reported as failed because its success metadata cannot be decoded. [Official upload example](https://docs.pcloud.com/methods/file/uploadfile.html), [official metadata example](https://docs.pcloud.com/structures/metadata.html).

**Reproduced:** `validPCloudUnsignedHashMustDeserialize` fails on the official example's value with “Numeric value overflow.”

**Required:** Preserve the complete unsigned value with a compatible serializer/string representation, add boundary tests through Retrofit and upload response parsing, and keep remote hashes separate from local checksums.

### F11 — P1: Cancellation can be swallowed while later deletions continue

**Source:** `core/sync/engine/SyncExecutionPipeline.kt:281`; broad `runCatching` in the pCloud client/provider; `core/work/scheduler/SyncScheduler.kt:128`.

The pipeline catches `Exception`, including `CancellationException`, and keeps executing actions. It has no explicit cancellation checkpoint before each action. Canceling/replacing work or disabling a pair therefore does not establish that destructive execution has stopped. The worker's outer cancellation catch cannot recover a cancellation already swallowed inside the pipeline/provider.

**Reproduced:** `cancellationMustStopSubsequentDestructiveActions` injects `CancellationException` during the first upload; the pipeline then deletes the second local file.

**Required:** Rethrow cancellation in every catch/result boundary, check coroutine activity before each operation, bind HTTP calls to coroutine cancellation, and serialize pause/delete/configuration changes with active execution. Test canceled work with later queued deletes.

### F12 — P1: A download with the wrong snapshot size is accepted and baselined

**Source:** `core/provider/pcloud/api/PCloudApiClient.kt:185`; `core/sync/engine/SyncExecutionPipeline.kt:103`.

Atomic download validation compares bytes only to the HTTP response's own `Content-Length`. It does not compare to the remote snapshot size/hash/version. The pipeline installs the bytes and records independent local and remote sizes, allowing both endpoints to appear unchanged on later passes despite differing data.

**Reproduced:** `downloadSizeMismatchMustNotReplaceLiveFile` exercises the real pCloud provider and HTTP client: a 100-byte remote snapshot accepts a complete four-byte HTTP body, overwrites the live file with “oops,” and reports success.

**Required:** Download to a private staging file; validate against the expected remote revision, size, and comparable checksum before installation. Replan if the revision changed; retain the existing local file on any integrity mismatch. Apply the same checks to conflicts and SAF content.

### F13 — P1: Account reconnection reuses old pairs, baselines, cache, and queued writes

**Source:** `ui/accounts/AccountsViewModel.kt:111`, `:184`, `:194`; `core/provider/pcloud/auth/OAuthCallbackActivity.kt:44`; `core/provider/CloudProviderFactory.kt:38`; `core/work/CloudUploadWorker.kt:39`.

All logins are stored as `pcloud_default`. Disconnect removes credentials but does not stop work, disable pairs, clear/isolate SAF state, or bind pending edits to a verified pCloud user ID. Connecting a different user or datacenter reuses the previous account's state. Existing local data can be uploaded into the new account (especially root-targeted jobs), and existing baselines can interpret the new account's empty/different folders as deletions. The provider also fetches current tokens dynamically during requests, so credential replacement can affect a running pass.

**Evidence:** Code review; no login/account-switch experiment with real accounts was performed.

**Required:** Bind state and jobs to a verified provider user ID plus datacenter. Suspend work during credential changes; reject jobs whose account identity no longer matches. Require explicit reconfiguration for a different account, keep pending edits recoverable, and invalidate old metadata/URI grants as appropriate.

## Additional findings

- **P2: SAF refresh retains deleted children.** `CloudDocumentsProvider.kt:178` upserts listings without pruning absent children. `completeSafRefreshMustRemoveDeletedChildren` reproduces the stale document after a successful empty refresh. Replace each directory snapshot transactionally and invalidate removed content/descendants.
- **P2: Dashboard health is not evidence of completed sync.** `ui/dashboard/DashboardViewModel.kt:111` clears `isSyncing` immediately after enqueueing and can show healthy before execution. Null sync status also falls through to healthy; early coordinator failures log an error without always updating pair status. Observe WorkManager/live results and require a successful verified pass before displaying healthy.
- **P2: Overlapping/duplicate pairs are accepted.** The wizard validates local readability but does not compare existing canonical local or remote roots. Locks are keyed by pair ID, so pairs sharing files can execute concurrently with incompatible baselines and use the same fixed `.sync_tmp` filenames. Reject unsupported overlaps and enforce filesystem/account-level execution coordination. Code-review finding, not a concurrent stress reproduction.
- **P2: Background/provider lifecycle and crash recovery remain incomplete.** One ordinary test result recorded a background refresh accessing a closed Room pool; the isolated rerun status is recorded below. `CloudDocumentsProvider` owns a scope and handler thread without a shutdown implementation. Conflict resolution moves files before database commit but has no startup recovery journal. Interrupting it can leave active conflicts with their files hidden in excluded internal paths. Add lifecycle cleanup and process-death recovery tests.
- **P2: Obsidian mode does not control filtering.** `isObsidianVault` is persisted/displayed, but the coordinator always uses default `PathFilter` exclusions. Turning the preset off still excludes workspace/cache/trash patterns. Wire the selected policy into scanning/watching and document always-excluded internal files.

## Verification and limits

The executable audit harness is [RealDataSafetyAuditTest.kt](2026-10-04-audit-reproductions/RealDataSafetyAuditTest.kt), loaded only by [audit.init.gradle](2026-10-04-audit-reproductions/audit.init.gradle). It is outside the normal application test source set. Its safety assertions are intended to pass after remediation; failures document unsafe current behavior, rather than tests that endorse it. Captured failure messages are in [results.json](2026-10-04-audit-reproductions/results.json).

```bash
./gradlew testDebugUnitTest --offline -Pksp.incremental=false \
  -I docs/reports/2026-10-04-audit-reproductions/audit.init.gradle \
  --tests 'io.relayfs.android.audit.RealDataSafetyAuditTest'
```

Validation was also run on an isolated copy under `/tmp/relayfs-final-audit-project` to avoid intermittent KSP-cache/test-report file errors in the shared build directory. `-Pksp.incremental=false` is a validation override, not a committed application setting. The isolated copy matched all application source files in the audited working tree.

| Check | Result |
| --- | --- |
| Ordinary debug tests, isolated build | 204 tests, 0 failures/errors/skips |
| Ordinary release tests, isolated build | 191 tests, 0 failures/errors/skips |
| Lint, isolated build | 0 errors, 34 warnings |
| Debug APK | Built successfully |
| R8 release APK | Built successfully; signing unconfigured |
| Added safety assertions | 15 failures; each identified unsafe current behavior |
| Existing diff whitespace check | Passed |

The ordinary passing totals exclude the audit-only safety assertions. Those assertions test additional failure scenarios the existing suite did not cover. Earlier shared-directory attempts encountered a missing KSP backup directory and a missing test-output index; a transient SAF background-exception result was also observed before an ordinary rerun passed. The isolated full build succeeded from freshly generated outputs.

No controlled live pCloud acceptance suite or on-device destructive/recovery test was run in this audit. The checked-in instrumentation suite only verifies package identity and Activity launch. Release signing remains unconfigured. Dependency lint advisories are not a comprehensive dependency vulnerability assessment.

## Required release gates

1. Correct F01–F13 and convert these reproductions into normal passing regression coverage. If deferring the virtual drive, remove writable exposure and clearly disable it; merely avoiding its UI does not eliminate its provider entry points.
2. Verify immutable transfers, precondition handling, directory identity, account identity, cancellation, and durable pending-edit recovery. Add fault injection around every filesystem/database boundary.
3. Run a device plus disposable-account matrix: both datacenters as applicable; upload/download including zero-byte and large files; same-length edits; cloud root rename/removal; two devices editing; deletes during edits; offline saves; cache pressure; permission loss/storage removal; process death during transfer/conflict resolution; reauthentication/account change. Compare file hashes and retained versions after each scenario.
4. Configure durable release signing and install the tested signed artifact. Keep an independent backup before the first controlled run with copies of important data.

**Real-data approval remains withheld until the fixes and acceptance gates are verified.**
