# RelayFS v1 Validation Findings and Fixes

**Date:** 2026-09-20
**Scope:** pCloud v1 synchronization, local storage safety, SAF caching, WebDAV deferral, build policy, regression coverage, and documentation consistency.

## Outcome

The identified implementation and documentation issues were corrected. RelayFS is ready for controlled live-account testing with copied local data and a disposable pCloud folder. Production signing and live pCloud acceptance testing remain separate pre-release gates.

## Issues and Resolutions

### 1. Incomplete local scans could be interpreted as mass deletion

**Severity:** High
**Problem:** `SyncCoordinator` only checked whether the configured root existed and was a directory. It did not verify readability or detect a failed child-directory listing. Kotlin's previous filesystem walk could silently return a partial or empty snapshot, allowing reconciliation to interpret inaccessible local files as deletions.

**Fix:**

- Added `LocalScanner`, which validates the root and every traversed directory.
- A `null` directory listing, permission failure, unreadable file, or canonicalization failure now raises `SyncException.LocalStorageUnavailable`.
- `SyncCoordinator` catches that typed error, records a failed sync result, and stops before remote listing or reconciliation.
- Symbolic links outside the configured root are not followed.

**Regression coverage:** Complete scans, unreadable roots, and unavailable directory listings.

### 2. Conflict-temporary exclusions also hid legitimate user files

**Severity:** Medium
**Problem:** Any filename ending in `.resolved`, `.replacement`, or `.original` was excluded, including valid user documents such as `draft.original`.

**Fix:** The path filter now excludes only RelayFS's hidden conflict-temporary format: a dot-prefixed filename, a UUID conflict identifier, and one of the three internal suffixes. Ordinary files with those extensions remain synchronized.

**Regression coverage:** All three internal formats plus legitimate lookalike filenames.

### 3. New data-integrity fixes lacked direct regression tests

**Severity:** Medium
**Problem:** The upload timestamp race, blank remote ID guard, zero-byte SAF cache behavior, and conflict-temporary filter were implemented without tests that reproduced their original failure modes.

**Fix:** Added focused tests that verify:

- An upload-time local edit cannot replace the scan-time baseline metadata.
- A blank remote ID prunes its baseline without calling the provider or pCloud API.
- A legitimate zero-byte SAF document downloads once and is subsequently served from cache.
- Only exact RelayFS conflict-temporary filenames are ignored.
- Legacy WebDAV credentials remain dormant in v1.

### 4. WebDAV was documented as deferred but remained runtime-routable

**Severity:** Medium
**Problem:** `DefaultCloudProviderFactory` could still construct `WebDavProvider` for specially named accounts or tokens. Phase 8 also retained an acceptance criterion requiring working WebDAV accounts.

**Fix:**

- Removed the runtime dependency from `DefaultCloudProviderFactory` to `WebDavProvider`.
- Legacy WebDAV account IDs or token formats now return no provider instead of entering either WebDAV or pCloud execution.
- Retained the deprecated prototype and parser for the v2 roadmap only.
- Updated the PRD, roadmap, architecture, Phase 8 title, checklist, and acceptance criteria to describe pCloud-only v1 behavior.

### 5. Battery exemption flow carried a Play policy warning

**Severity:** Release policy
**Problem:** The manifest requested `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` and the UI launched the restricted direct-exemption intent.

**Fix:**

- Removed the restricted permission.
- The dashboard now opens Android's general battery-optimization settings for an explicit user choice.
- Updated tests and documentation to match the policy-safe flow.
- The lint `BatteryLife` warning is eliminated.

### 6. Validation and release documentation overstated current evidence

**Severity:** Documentation
**Problem:** The roadmap called v1 release-ready and claimed end-to-end coverage, while the release APK is unsigned, live pCloud acceptance testing has not been performed, and the device test only checked the package name.

**Fix:**

- The roadmap now describes v1 as feature-complete and ready for controlled validation.
- Release signing and live-account acceptance testing are explicit gates.
- The device suite now launches `MainActivity` and verifies a live resumed activity.
- Phase 8 records JVM/Robolectric integration and physical-device smoke coverage rather than claiming full end-to-end cloud testing.

### 7. Report implementation details did not match the code

**Severity:** Documentation
**Corrections:**

- Atomic downloads use same-directory `.sync_tmp` staging, byte-count validation when `Content-Length` is available, `fsync`, and fail-closed atomic replacement. `AtomicFileWriter` does not create a `.backup` file.
- Parent-by-parent remote hierarchy creation is implemented by `SyncExecutionPipeline.resolveOrCreateRemoteDirectory`, which delegates each folder operation to the active provider. It is not a `PCloudProvider.ensureRemoteFolderPath` method.
- Local scan protection is now implemented by the dedicated `LocalScanner` and `SyncException.LocalStorageUnavailable` types.

## Verification Results

| Check | Result |
|---|---:|
| Debug unit/Robolectric tests | 191 passed, 0 failed, 0 skipped |
| Release unit/Robolectric tests | 178 passed, 0 failed, 0 skipped |
| Physical-device instrumentation | 2 passed on SM-A528B / Android 14 |
| Android lint | 0 errors; 52 non-blocking warnings; policy warning removed |
| Debug APK | Built successfully |
| R8 release APK | Built successfully; unsigned as expected |
| Kotlin compilation | No compiler warnings |
| `git diff --check` | Clean |

## Remaining Pre-Release Gates

1. Run controlled live pCloud scenarios against disposable data: initial upload/download, offline recovery, concurrent edits, deletion propagation, nested folders, zero-byte files, interrupted downloads, and storage removal during scanning.
2. Configure and protect a production signing key before distributing a release APK.
3. Review the remaining non-blocking lint advisories for dependency updates, style/KTX improvements, and unused resources during release polish.
