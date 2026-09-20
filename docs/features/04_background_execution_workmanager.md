# Feature Specification: Background Execution, WorkManager & Battery Governance

**Feature ID:** `FEAT-04`  
**Related Components:** `core:work`, `core:service`  

---

## 1. Overview & Objective
Android strictly regulates background process execution and network access. This specification defines how RelayFS manages background tasks using **AndroidX WorkManager** for periodic synchronization, **Foreground Services** for active/immediate sync passes, and navigation of OEM battery optimization policies.

---

## 2. Background Execution Strategy

```
                               ┌─────────────────────────────┐
                               │  Synchronization Trigger    │
                               └──────────────┬──────────────┘
                                              │
                       ┌──────────────────────┴──────────────────────┐
                       ▼                                             ▼
        ┌─────────────────────────────┐               ┌─────────────────────────────┐
        │   Periodic Schedule (15m+)  │               │   Live Watcher / Sync Now   │
        └──────────────┬──────────────┘               └──────────────┬──────────────┘
                       │                                             │
                       ▼                                             ▼
        ┌─────────────────────────────┐               ┌─────────────────────────────┐
        │         WorkManager         │               │     Foreground Service      │
        │      (SyncWorker.kt)        │               │ (SyncForegroundService.kt)  │
        ├─────────────────────────────┤               ├─────────────────────────────┤
        │ • Network constraints       │               │ • Type: "dataSync"          │
        │ • Battery constraints       │               │ • User-visible progress bar │
        │ • Doze maintenance windows  │               │ • Keeps Inotify alive       │
        └─────────────────────────────┘               └─────────────────────────────┘
```

---

## 3. WorkManager Implementation (`SyncWorker`)

### 3.1 Worker Configuration
Periodic synchronization is scheduled using `WorkManager` with a `PeriodicWorkRequestBuilder`:

```kotlin
val syncConstraints = Constraints.Builder()
    .setRequiredNetworkType(
        if (folderPair.syncOnlyOnWifi) NetworkType.UNMETERED else NetworkType.CONNECTED
    )
    .setRequiresBatteryNotLow(true)
    .apply {
        if (folderPair.requireCharging) {
            setRequiresCharging(true)
        }
    }
    .build()

val periodicWorkRequest = PeriodicWorkRequestBuilder<SyncWorker>(
    folderPair.syncIntervalMinutes.toLong(), TimeUnit.MINUTES,
    flexTimeInterval = 5, TimeUnit.MINUTES
)
    .setConstraints(syncConstraints)
    .addTag("sync_pair_${folderPair.id}")
    .build()

WorkManager.getInstance(context).enqueueUniquePeriodicWork(
    "sync_${folderPair.id}",
    ExistingPeriodicWorkPolicy.UPDATE,
    periodicWorkRequest
)
```

### 3.2 Immediate / Expedited Execution
When a user taps "Sync Now" while the app is in the background or when a debounced local file event triggers an upload:
* Schedule an expedited one-time worker:
  ```kotlin
  val oneTimeRequest = OneTimeWorkRequestBuilder<SyncWorker>()
      .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
      .setInputData(workDataOf("FOLDER_PAIR_ID" to folderPairId))
      .build()
  ```

---

## 4. Foreground Service (`SyncForegroundService`)

### 4.1 Android 14+ Service Declaration
In Android 14 (API 34+), foreground services must declare a specific `foregroundServiceType`:

```xml
<service
    android:name=".core.work.SyncForegroundService"
    android:foregroundServiceType="dataSync"
    android:exported="false" />
```

### 4.2 When the Foreground Service is Used
1. **Active Long-Running Sync:** When a sync pass transfers files taking longer than 10 seconds, the worker promotes to foreground using `setForeground(ForegroundInfo)`.
2. **Continuous Sync Mode (User-Enabled):** An optional mode that keeps `RecursiveFileWatcher` running continuously in the background with a low-priority, persistent notification.

### 4.3 Notification Management (`POST_NOTIFICATIONS`)
* On Android 13+ (API 33+), the app requests `Manifest.permission.POST_NOTIFICATIONS`.
* Notification Channel: `sync_status` (Importance: `LOW` — no sound/vibration).
* During sync, the notification updates with:
  * Title: `"Syncing [Folder Name]..."`
  * Content: `"Uploading Document.md (4/12 files, 450 KB/s)"`
  * Progress: `setProgress(totalFiles, currentFileIndex, false)`

---

## 5. Battery Optimization & OEM Governance

### 5.1 Doze Mode & Standby Buckets
* Android's Doze mode batches background network calls into periodic maintenance windows.
* WorkManager automatically honors Doze mode without crashing or draining battery.

### 5.2 OEM Battery Killers
* Aggressive manufacturers (Samsung OneUI, Xiaomi HyperOS, Huawei EMUI) frequently kill background processes even when WorkManager is properly configured.
* **Mitigation Flow:**
  1. Detect if the app is currently subject to battery restrictions:
     ```kotlin
     val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
     val isIgnoring = powerManager.isIgnoringBatteryOptimizations(context.packageName)
     ```
  2. If false, display an educational card in the app settings:
     *"To allow reliable background syncing when your screen is off, disable battery optimization for RelayFS."*
  3. Open Android's battery-optimization settings for an explicit user choice:
     ```kotlin
     val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
     context.startActivity(intent)
     ```
     RelayFS does not request `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, avoiding the restricted direct-exemption flow and its Play policy risk.
