# RelayFS

RelayFS connects pCloud storage to the Android filesystem. It synchronizes cloud folders with local directories and exposes cloud files through Android's system file picker, so apps can open and edit them on demand.

Built with Kotlin, Jetpack Compose, and Material 3, RelayFS is designed for workflows such as keeping an Obsidian vault available locally while syncing it with pCloud.

## Project status

The v1 implementation is complete and ready for controlled pCloud validation. Physical-device acceptance testing against a disposable pCloud folder and production release signing remain open release gates. See the [roadmap](docs/ROADMAP.md) and [audit remediation report](docs/reports/2026-10-04_audit_remediation_validation.md) for validation evidence and remaining limits.

Native pCloud is the supported provider in v1. WebDAV is deferred to v2; the retained prototype is disabled in the runtime provider factory. pCloud Crypto folder decryption is outside the current scope.

## Features

- **Folder synchronization:** Map local directories to dedicated pCloud folders, with two-way, device-to-cloud, or cloud-to-device synchronization.
- **Change reconciliation:** Compare local files, cloud files, and a stored baseline to identify changes, deletions, moves, and conflicts.
- **Conflict handling:** Preserve conflicting versions, review them in the Conflict Center, and compare text with the side-by-side diff viewer.
- **Background sync:** Schedule WorkManager jobs from 15 minutes to 24 hours, with Wi-Fi and charging constraints. A foreground service provides debounced filesystem monitoring for live sync.
- **Obsidian mode:** Exclude device-specific workspace files, Obsidian caches, and trash while syncing vault content and other configuration.
- **Virtual cloud drive:** Browse connected storage in Android's file picker, open files through a seekable disk cache, and queue uploads when an editor saves changes.
- **Recovery and visibility:** View sync activity and errors, and export or retry pending virtual-drive edits from the dashboard.
- **Direct cloud access:** Connect directly to pCloud, with credentials stored through Android Keystore-backed encrypted preferences and no analytics or advertising SDKs.

## Requirements

| Component | Requirement |
| --- | --- |
| Android device or emulator | Android 7.0+ (API 24+) |
| Android SDK | Platform 36, matching the compile and target SDK |
| Java | JetBrains Runtime 21, selected by the checked-in Gradle daemon criteria |
| Gradle | Use the included Gradle 8.13 wrapper |
| Cloud access | A pCloud account and an approved pCloud OAuth application for live sign-in |

The project uses Android Gradle Plugin 8.13.2 and Kotlin 2.0.21. Java/Kotlin bytecode targets Java 11; the Gradle runtime uses Java 21.

## Build and run

Open the repository in Android Studio, or run the following commands from its root. On Windows, use `gradlew.bat` in place of `./gradlew`.

### 1. Configure the local environment

Create or update the git-ignored `local.properties` file:

```properties
sdk.dir=/absolute/path/to/Android/Sdk
PCLOUD_CLIENT_ID=your_pcloud_app_key
```

Replace the example values with your Android SDK location and pCloud App Key. Android Studio can populate `sdk.dir` when importing the project. The app can build without `PCLOUD_CLIENT_ID`, but live pCloud sign-in requires it.

The checked-in `gradle.properties` sets `org.gradle.java.home=/usr/local/android-studio/jbr`. If that path does not exist on your machine, update it to your local JBR installation. The repository also selects JetBrains Java 21 in `gradle/gradle-daemon-jvm.properties`.

### 2. Configure pCloud OAuth

In the [pCloud developer portal](https://docs.pcloud.com/my_apps/), register an application and complete any required approval before live sign-in. Configure:

- **Allow implicit grant:** Allow.
- **Redirect URI:** `pcloud-oauth://io.relayfs.android`.
- **App Key / Client ID:** Copy it into `PCLOUD_CLIENT_ID` in `local.properties`.

No client secret is required. The implemented mobile login uses the token flow (`response_type=token`) with state validation; it does not perform a PKCE authorization-code exchange. US and EU account endpoints are selected during sign-in.

See the [pCloud setup guide](docs/PCLOUD_SETUP.md) for the detailed setup walkthrough. Rebuild the app after changing the client ID.

### 3. Build and install

```bash
./gradlew assembleDebug
./gradlew installDebug
```

Installation requires a connected Android device with USB debugging enabled or a running emulator. The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

To assemble the release variant:

```bash
./gradlew assembleRelease
```

The current build produces `app/build/outputs/apk/release/app-release-unsigned.apk`. Configure production signing before distributing a release.

## Using RelayFS

1. Open **Cloud Accounts** and select **Sign in with pCloud**. Complete authorization in the browser.
2. From the dashboard, select **Add Folder Pair**. Grant storage access and select an existing local directory.
3. Enter a dedicated remote folder path, such as `/RelayFS/TestVault`. Account-root synchronization and overlapping folder pairs are rejected.
4. Choose a name, sync direction, interval, and optional Wi-Fi, charging, and Obsidian settings. Select **Create & Sync Now**.
5. Check the dashboard, Activity Log, and Conflict Center for results. Use **Sync Now** to request another pass.

For on-demand cloud access, open the system file picker from another app and select the connected cloud root. Saved edits are uploaded in the background. Failed or interrupted uploads appear as pending edits on the dashboard, where you can export a copy or retry.

### Storage and background access

Local folder synchronization requires direct filesystem access: **All files access** on Android 11+, or storage permissions on older Android versions. The folder browser uses the Storage Access Framework as a picker; its URI grant alone does not replace the filesystem access required by the sync engine and watcher.

Allow notifications when prompted to see foreground sync status and conflict alerts. Android power management can delay background work; the dashboard provides battery-settings guidance when restrictions are detected.

### Obsidian exclusions

Obsidian mode excludes:

```text
.obsidian/workspace.json
.obsidian/workspace-mobile.json
.obsidian/cache/
.trash/
```

Other exclusions apply in every mode, including `.git/`, `.relayfs-recovery/`, temporary staging files, and common operating-system metadata files.

## Data safety and recovery

Sync can propagate deletions. Start validation with copied documents and a disposable cloud folder, and keep independent backups. Version checks and recovery copies reduce risk, but concurrent edits from other apps or devices can still race with a final write or deletion.

- Local versions replaced or deleted during sync are retained under the pair's `.relayfs-recovery/` directory, with `.path` sidecars identifying original paths. Cloud recovery copies are stored in an account-root `.relayfs-recovery` folder. These copies consume storage and are not automatically purged.
- Conflicts retain separate versions for review rather than silently selecting one.
- A pass is blocked if planned deletions exceed 20 files, or exceed 50% of at least 10 tracked files. Review both endpoints before reconfiguring a blocked pair.
- Pending virtual-drive edits live in persistent app-private storage. **Export them before clearing app data or uninstalling**, which removes those edits.

See the [audit report's operational and recovery guidance](docs/reports/2026-10-04_audit_remediation_validation.md#operational-changes-and-recovery) for details.

## Development and verification

```bash
# JVM, Robolectric, and Compose tests for the debug variant
./gradlew testDebugUnitTest

# Run only sync-engine tests
./gradlew testDebugUnitTest --tests 'io.relayfs.android.core.sync.*'

# Android lint
./gradlew lintDebug

# Release unit tests and APK assembly
./gradlew testReleaseUnitTest assembleRelease
```

Tests use JUnit, Robolectric, MockWebServer, Room testing, WorkManager testing, and Compose testing. Release unit tests exclude `*ScreenTest*`; those UI tests run in the debug suite. Automated checks complement the live-device and pCloud acceptance testing tracked in the roadmap.

## Repository layout

RelayFS currently uses a single Android application module, with subsystems organized into Kotlin packages:

```text
app/src/main/java/io/relayfs/android/
├── core/
│   ├── database/   # Room entities, DAOs, and migrations
│   ├── model/      # Accounts, folder pairs, conflicts, and results
│   ├── provider/   # Cloud contract, pCloud client, and WebDAV prototype
│   ├── saf/        # Virtual drive and pending-edit handling
│   ├── security/   # Encrypted credentials and account sessions
│   ├── service/    # Foreground sync service and notifications
│   ├── storage/    # Storage permissions and folder path resolution
│   ├── sync/       # Scanning, reconciliation, hashing, and atomic writes
│   ├── watcher/    # Recursive watching, filters, and debounce queue
│   ├── work/       # Background workers and scheduling
│   └── power/      # Battery restriction handling
└── ui/             # Compose screens, navigation, and ViewModels
app/src/test/       # Unit, Robolectric, UI, and audit regression tests
app/src/androidTest/ # Device instrumentation tests
docs/               # Requirements, architecture, phases, and audit reports
```

## Further documentation

- [Product requirements](docs/PRD.md)
- [Roadmap and release gates](docs/ROADMAP.md)
- [System architecture](docs/architecture/system_architecture.md)
- [Reconciliation algorithm](docs/architecture/reconciliation_algorithm.md)
- [Android DocumentsProvider design](docs/architecture/documents_provider_saf.md)
- [Feature specifications](docs/features/)
- [Phase implementation guides](docs/phases/)
- [Future ideas](docs/FUTURE_IDEAS.md)

For contributions, follow the specifications and verification practices in the [engineering manual](docs/AGENT.md). Coding agents should also read the root [operating instructions](AGENT.md).
