# Feature Specification: Obsidian Vault Preset & Optimizations

**Feature ID:** `FEAT-05`  
**Related Components:** `core:sync`, `core:watcher`, `app:ui`  

---

## 1. Overview & Objective
Obsidian is one of the most popular offline-first Personal Knowledge Management (PKM) tools. Obsidian stores all user notes as plain Markdown files in a local directory ("vault"). However, its internal state files and rapid auto-save behavior create unique synchronization challenges. This feature provides an automated preset that optimizes the sync engine specifically for Obsidian vaults.

---

## 2. Anatomy of an Obsidian Vault

```
MyVault/                               # Root Vault Directory
├── Work/                              # User Markdown Folders
│   ├── Meeting Notes.md
│   └── Project Roadmap.md
├── Attachments/                       # Images, PDFs, Audio
│   └── Diagram.png
└── .obsidian/                         # Vault Configuration & Internal State
    ├── app.json                       # Global settings (SAFE to sync)
    ├── appearance.json                # Themes & styling (SAFE to sync)
    ├── community-plugins.json         # Enabled plugins (SAFE to sync)
    ├── hotkeys.json                   # Custom hotkeys (SAFE to sync)
    ├── plugins/                       # Installed plugin code & data (SAFE to sync)
    │   └── dataview/
    │       ├── main.js
    │       ├── manifest.json
    │       └── data.json
    ├── cache/                         # Metadata index (DO NOT SYNC - local rebuild)
    ├── workspace.json                 # Desktop open tabs/cursor (DO NOT SYNC)
    └── workspace-mobile.json          # Mobile open tabs/cursor (DO NOT SYNC)
```

---

## 3. The Workspace Conflict Problem

### 3.1 Why `workspace.json` Breaks Standard Sync Tools
`workspace.json` records the active tab, cursor line/column, open sidebar panels, and scroll positions.
* When typing on Android, Obsidian writes to `workspace-mobile.json` on **every scroll, pane toggle, and tab switch**.
* When editing on Fedora, Obsidian updates `workspace.json` constantly.
* If synchronized naively, this file generates dozens of merge conflicts every day, causes tabs to randomly close or re-arrange on mobile, and wastes network bandwidth.

### 3.2 Automated Exclusion Rules
When a folder pair has `isObsidianVault = true`, the sync engine automatically enforces the following exclusion patterns:

```
# Obsidian Vault Internal Exclusions
.obsidian/workspace.json
.obsidian/workspace-mobile.json
.obsidian/cache/**
.trash/**
```

Files matching these patterns are:
1. Completely ignored by the `RecursiveFileWatcher`.
2. Filtered out during the local directory scan.
3. Excluded from the remote delta change list.

---

## 4. Rapid Auto-Save & Debounce Integration
* Obsidian on mobile auto-saves while the user is actively typing.
* The local sync engine enforces a **3-second inactivity debounce timer**:
  * If a user writes 50 words in 30 seconds, 15 discrete file modification events may occur.
  * The debouncer collapses all 15 events into a single sync pass, triggered only 3 seconds after the user pauses typing.

---

## 5. UI Integration & Automatic Detection
* When a user selects a local folder to sync, the application inspects the directory contents:
  ```kotlin
  val isVault = File(selectedLocalPath, ".obsidian").exists()
  ```
* If `.obsidian` is detected, the UI prompts the user:
  > **"Obsidian Vault Detected"**  
  > *"We recommend enabling Obsidian Mode. This safely syncs your notes, attachments, and plugins while preventing workspace tab conflicts between devices."*
* The `isObsidianVault` toggle is enabled by default with all recommended rules pre-configured.

