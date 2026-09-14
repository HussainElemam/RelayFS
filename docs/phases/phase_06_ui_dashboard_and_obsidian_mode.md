# Phase 6: Jetpack Compose UI & Obsidian Presets

**Phase Status:** `PENDING`  
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
- [ ] Add `androidx.navigation:navigation-compose:2.8.0`.
- [ ] Establish MVI / MVVM structure: ViewModels with immutable `UiState` and `UiEvent` flows.
- [ ] Define navigation graph: `Dashboard`, `Accounts`, `FolderPairConfig`, `Conflicts`, `Settings`.

### Task 2.2: Main Dashboard Screen (`com.example.android_sync_idea.ui.dashboard`)
- [ ] Top App Bar with overall sync health status.
- [ ] List of configured folder pairs with last-synced timestamp, sync direction badge, and quick toggle switch.
- [ ] Interactive "Sync Now" floating action button with live spinning progress indicator.
- [ ] Empty state illustration when no folder pairs are configured.

### Task 2.3: Account Management Screen (`com.example.android_sync_idea.ui.accounts`)
- [ ] Display connected pCloud account (email, datacenter location, quota usage).
- [ ] "Connect pCloud Account" button launching Chrome Custom Tab OAuth flow.
- [ ] Disconnect account button with confirmation dialog.

### Task 2.4: Folder Pair Wizard with Obsidian Auto-Detection (`com.example.android_sync_idea.ui.pair`)
- [ ] Step 1: Select local folder using directory picker.
  * Check for presence of `.obsidian/`.
  * If found, display banner: *"Obsidian Vault Detected - Obsidian Mode enabled with workspace exclusions."*
- [ ] Step 2: Browse and select remote pCloud folder via interactive tree list.
- [ ] Step 3: Configure sync direction, sync interval (15m, 30m, 1h, 6h, 24h), and Wi-Fi only toggle.
- [ ] Save configuration to `FolderPairDao` and trigger initial sync pass.

### Task 2.5: Conflict Center & History Screens (`com.example.android_sync_idea.ui.conflicts`)
- [ ] Conflict list displaying original file vs. branched conflict file with timestamps.
- [ ] Actions: "Keep Local", "Keep Cloud", or "Keep Both".
- [ ] Activity log showing historical sync runs, files uploaded/downloaded, and error messages.

### Task 2.6: UI Previews & Instrumentation Tests
- [ ] Provide `@Preview` annotations for all screen components in light and dark mode.
- [ ] Compose UI tests verifying folder pair creation flow and button interactions.

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
./gradlew testDebugUnitTest --tests "com.example.android_sync_idea.ui.*"

# Assemble debug APK for manual on-device testing
./gradlew assembleDebug
```

---

## 5. Agent Completion Handoff
1. Ensure Compose tests pass.
2. Update checklist in this document and `docs/ROADMAP.md`.
3. Report results to user and **STOP** for review.

