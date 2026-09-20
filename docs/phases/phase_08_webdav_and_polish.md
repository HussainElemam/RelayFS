# Phase 8: Diff Viewer, pCloud Polish & Release Readiness

**Phase Status:** `COMPLETED`  
**Estimated Effort:** 4–6 Days  
**Prerequisites:** [Phase 1 through Phase 7](file:///home/hussain/AndroidStudioProjects/android_sync_idea/docs/ROADMAP.md) complete.  

---

## 1. Phase Goal
Finalize the application for general availability:
1. Build an in-app side-by-side **Markdown Diff Viewer** for reviewing and resolving file conflicts.
2. Establish ProGuard / R8 minimization rules and verify release build integrity.
3. Harden and verify native pCloud synchronization and error handling.
4. Note on WebDAV: Generic WebDAV provider implementation is deferred to v2 so v1 focuses exclusively on native pCloud.

---

## 2. Detailed Task Checklist

### Task 2.1: Generic WebDAV Provider (`io.relayfs.android.core.provider.webdav`)
> *Status: DEFERRED TO v2.* Base class and XML parser are retained in `core.provider.webdav` but marked `@Deprecated` for v1. No UI exposures.
- [ ] Production WebDAV deployment (*Deferred to v2*)
- [x] Initial RFC 4918 prototype & XML parser implemented
- [x] Unit tests for WebDAV XML parsing passing

### Task 2.2: In-App Markdown Diff & Conflict Resolution UI (`io.relayfs.android.ui.diff`)
- [x] Integrate a lightweight text diff algorithm (e.g. Myers diff).
- [x] Build a split-screen / unified diff viewer in Jetpack Compose showing added, removed, and modified lines between local note and branched conflict note.
- [x] Actions: "Accept Local", "Accept Remote", or "Merge Changes".

### Task 2.3: Security Audit & ProGuard Configuration
- [x] Configure `proguard-rules.pro` to keep Room, Retrofit, and Kotlinx Serialization models.
- [x] Verify that OAuth tokens, passwords, and file paths are never logged in release builds.

### Task 2.4: Release Build Verification
- [x] Build release APK:
  ```bash
  ./gradlew assembleRelease
  ```
- [x] Validate APK size, resource shrinking, and startup latency.

---

## 3. Acceptance Criteria
1. WebDAV has no v1 UI or runtime provider route; its prototype remains isolated for the v2 roadmap.
2. The in-app diff viewer renders clear visual comparisons of conflicting Markdown notes.
3. The unsigned release APK compiles with R8 full mode and the debug APK passes a physical-device launch smoke test.
4. Debug and release unit suites, lint, and the available device instrumentation suite pass before testing handoff.

---

## 4. Verification & Testing Commands

```bash
# Run the entire test suite across all modules
./gradlew testDebugUnitTest

# Run release build assembly
./gradlew assembleRelease
```

---

## 5. Agent Completion Handoff
1. Ensure all tests pass.
2. Update checklist in this document and `docs/ROADMAP.md`.
3. Provide final release summary to user.
