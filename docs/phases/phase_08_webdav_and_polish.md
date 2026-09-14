# Phase 8: WebDAV Provider, Diff Viewer & Release Readiness

**Phase Status:** `COMPLETED`  
**Estimated Effort:** 4–6 Days  
**Prerequisites:** [Phase 1 through Phase 7](file:///home/hussain/AndroidStudioProjects/android_sync_idea/docs/ROADMAP.md) complete.  

---

## 1. Phase Goal
Finalize the application for general availability:
1. Implement the generic **`WebDavProvider`** (RFC 4918) supporting Nextcloud, ownCloud, and NAS servers.
2. Build an in-app side-by-side **Markdown Diff Viewer** for reviewing and resolving file conflicts.
3. Establish ProGuard / R8 minimization rules and verify release build integrity.
4. Execute comprehensive end-to-end integration tests.

---

## 2. Detailed Task Checklist

### Task 2.1: Generic WebDAV Provider (`io.relayfs.android.core.provider.webdav`)
- [x] Implement `WebDavProvider` implementing `CloudProvider`.
- [x] Build XML parser for `PROPFIND` multi-status responses using `XmlPullParser`.
- [x] Implement HTTP operations: `PROPFIND` (listing), `GET` (download), `PUT` (upload), `MOVE`, `DELETE`.
- [x] Handle HTTP Basic and Digest authentication.
- [x] Unit tests for WebDAV XML parsing and error status handling.

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
1. Users can add and sync generic WebDAV accounts (Nextcloud, ownCloud).
2. The in-app diff viewer renders clear visual comparisons of conflicting Markdown notes.
3. Release APK compiles with R8 full mode enabled with zero crashes.
4. 100% test pass rate across the entire project test suite.

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

