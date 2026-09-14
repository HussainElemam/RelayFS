# Phase 2: pCloud Client & OAuth2 PKCE Integration

**Phase Status:** `PENDING`  
**Estimated Effort:** 4–6 Days  
**Prerequisites:** [Phase 1: Core Architecture & Domain Models](file:///home/hussain/AndroidStudioProjects/android_sync_idea/docs/phases/phase_01_core_architecture_and_models.md) complete.  

---

## 1. Phase Goal
Build a robust, reactive HTTP client for the **pCloud REST API**:
1. Implement the OAuth 2.0 PKCE authentication flow using Chrome Custom Tabs and deep linking.
2. Handle regional datacenter routing automatically (US `api.pcloud.com` vs. EU `eapi.pcloud.com`).
3. Implement core remote operations: `listfolder` (with recursive support), download streams, and metadata inspection.
4. Implement delta change detection via pCloud’s native `diff` endpoint.
5. Implement resumable chunked uploads for files $>5\text{ MB}$.
6. Verify all API interactions using `MockWebServer` unit tests.

---

## 2. Detailed Task Checklist

### Task 2.1: Network Dependencies & Setup
- [ ] Add OkHttp 4.x and Retrofit 2.x (or Ktor Client) with Kotlinx Serialization.
- [ ] Add `MockWebServer` for offline HTTP unit testing.
- [ ] Add AndroidX Browser (`androidx.browser:browser:1.8.0`) for Chrome Custom Tabs.

### Task 2.2: OAuth 2.0 PKCE Flow Handler
- [ ] Implement `OAuthPkceHelper`: generates cryptographic `code_verifier` and URL-safe SHA-256 `code_challenge`.
- [ ] Register custom URL scheme in `AndroidManifest.xml`: `cloudsync://oauth`.
- [ ] Implement `OAuthCallbackActivity` to capture redirect authorization code and `locationid`.
- [ ] Implement token exchange request and persist token into `TokenStorage`.

### Task 2.3: pCloud REST API Service & DTOs
- [ ] Create DTOs: `PCloudFolderResponse`, `PCloudFileMetadata`, `PCloudDiffResponse`, `PCloudUploadResponse`.
- [ ] Implement Retrofit interface or OkHttp client for pCloud endpoints:
  * `GET /listfolder` (with `recursive=1`)
  * `GET /diff` (with `diffid` tracking)
  * `GET /getfilelink` (download stream extraction)
  * `POST /uploadfile` (single-part upload)
  * `POST /upload_create`, `/upload_write`, `/upload_save` (chunked upload)
  * `POST /movefile` and `POST /deletefile`

### Task 2.4: `PCloudProvider` Implementation
- [ ] Implement `PCloudProvider` implementing the `CloudProvider` interface from Phase 1.
- [ ] Add OkHttp Interceptors:
  * `AuthInterceptor`: Injects `access_token` query parameter or Bearer header.
  * `RateLimitInterceptor`: Handles HTTP 429 with exponential backoff.
  * `LoggingInterceptor`: Redacts sensitive tokens.

### Task 2.5: Automated Network Unit Tests
- [ ] Test OAuth PKCE challenge calculation.
- [ ] Test `MockWebServer` responses for:
  * Recursive folder listing parsing.
  * `diff` event parsing (`createfile`, `modifyfile`, `deletefile`).
  * Chunked upload progression and retry on dropped socket.

---

## 3. Acceptance Criteria
1. The OAuth PKCE flow successfully launches Chrome Custom Tabs and processes redirect intent callbacks.
2. `PCloudProvider` automatically routes requests to the correct host based on `locationid`.
3. Delta change parsing accurately converts pCloud `diff` events into `CloudChange` domain objects.
4. All unit tests with `MockWebServer` pass with 100% deterministic success.

---

## 4. Verification & Testing Commands

```bash
# Run unit tests for pCloud API integration
./gradlew testDebugUnitTest --tests "com.example.android_sync_idea.core.provider.pcloud.*"
```

---

## 5. Agent Completion Handoff
1. Ensure all MockWebServer tests pass.
2. Check off tasks in this document and `docs/ROADMAP.md`.
3. Report progress to user and **STOP** for review.

