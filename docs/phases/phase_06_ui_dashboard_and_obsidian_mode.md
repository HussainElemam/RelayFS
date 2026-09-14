# Phase 6: Jetpack Compose UI & Obsidian Presets

**Phase Status:** `COMPLETED`  
**Estimated Effort:** 5–7 Days  
**Prerequisites:** [Phase 1 through Phase 5](file:///home/hussain/AndroidStudioProjects/android_sync_idea/docs/ROADMAP.md) complete.  

---

## 1. Phase Goal
Deliver a clean, modern, ad-free **Jetpack Compose (Material 3)** user interface:
1. Main Dashboard showing configured folder pairs, live sync status, and manual "Sync Now" action.
2. Account Management screen supporting one-tap pCloud OAuth2 PKCE login.
3. Folder Pair Setup wizard featuring:
   * Local folder selection (with automatic Obsidian vault detection).
   * Remote pCloud folder tree browser.
   * Sync direction selector (Two-Way, Cloud-to-Local, Local-to-Cloud).
   * Scheduling constraints (interval, Wi-Fi only, charging only).
4. Conflict Resolution Center allowing users to review and resolve branched conflict files.
5. Activity / Audit Log screen displaying transfer history and error details.

---

## 2. Detailed Task Checklist

### Task 2.1: Navigation & Architecture Setup
- [x] Add `androidx.navigation:navigation-compose:2.8.4` and Material Icons Extended.
- [x] Establish MVI / MVVM structure: ViewModels with immutable `UiState` flows and coroutine lifecycle scopes.
- [x] Define navigation graph: `Dashboard`, `Accounts`, `FolderPairWizard`, `Conflicts`, `Logs`.

### Task 2.2: Main Dashboard Screen (`io.relayfs.android.ui.dashboard`)
- [x] Top App Bar with overall sync health status (`HEALTHY`, `SYNCING`, `HAS_CONFLICTS`, `ERROR`).
- [x] List of configured folder pairs with last-synced timestamp, sync direction badge, and quick toggle switch.
- [x] Interactive "Sync Now" floating action button with live spinning progress indicator.
- [x] Empty state illustration when no folder pairs are configured.
- [x] Battery optimization whitelist prompt banner.

### Task 2.3: Account Management Screen (`io.relayfs.android.ui.accounts`)
- [x] Display connected pCloud account (email, datacenter location, quota usage progress bar).
- [x] "Connect pCloud Account" button launching Chrome Custom Tab OAuth flow with PKCE.
- [x] Disconnect account button with confirmation dialog.

### Task 2.4: Folder Pair Wizard with Obsidian Auto-Detection (`io.relayfs.android.ui.pair`)
- [x] Step 1: Select local folder with directory picker.
  * Check for presence of `.obsidian/`.
  * If found, display banner: *"Obsidian Vault Detected - Obsidian Mode enabled with workspace exclusions."*
- [x] Step 2: Configure remote pCloud destination path and folder ID.
- [x] Step 3: Configure sync direction, interval, Wi-Fi only, and charging only constraints.
- [x] Save configuration to `FolderPairDao` and trigger initial sync pass via `SyncScheduler`.

### Task 2.5: Conflict Center & History Screens (`io.relayfs.android.ui.conflicts`, `io.relayfs.android.ui.logs`)
- [x] Conflict list displaying original file vs. branched conflict file with timestamps.
- [x] Actions: "Keep Local", "Keep Cloud", or "Keep Both".
- [x] Activity log showing historical sync runs, log level filters (`ALL`, `ERRORS_ONLY`, `INFO_ONLY`), and error details.

### Task 2.6: UI Previews & Instrumentation Tests
- [x] Provide `@Preview` annotations for all screen components in light and dark mode.
- [x] Robolectric Compose UI tests and ViewModel unit tests verifying screens, dialogs, buttons, and state mutations.

---

## 3. Acceptance Criteria
1. The user can authenticate with pCloud and view account details inside the UI.
2. Selecting an Obsidian vault automatically sets the Obsidian preset and applies exclusions.
3. Tapping "Sync Now" triggers immediate background execution and animates progress.
4. All Compose previews render without errors in Android Studio.
5. Compose UI tests pass cleanly.

---

## 4. Verification & Testing Commands

```bash
# Run unit and compose UI tests
./gradlew testDebugUnitTest --tests "io.relayfs.android.ui.*"

# Assemble debug APK for manual on-device testing
./gradlew assembleDebug
```

---

## 5. Agent Completion Handoff
1. Ensure Compose tests pass.
2. Update checklist in this document and `docs/ROADMAP.md`.
3. Report results to user and **STOP** for review.

