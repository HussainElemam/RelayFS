# pCloud App Key (OAuth) Setup Guide

> **Note for Developers:**  
> When pCloud completes their manual review and approves your application, follow the steps below to connect your real pCloud account.

---

## 1. Obtain Your App Key (Client ID)

1. Log in to [pCloud Developers - My Apps](https://docs.pcloud.com/my_apps/).
2. Click on your registered application (e.g. `RelayFS`).
3. Ensure the **Redirect URI** is set to:
   ```text
   relayfs://oauth
   ```
4. Copy your **App Key / Client ID**.

---

## 2. Add Credentials to `local.properties`

Open [`local.properties`](file:///home/hussain/AndroidStudioProjects/android_sync_idea/local.properties) in the root of the project and set `PCLOUD_CLIENT_ID`:

```properties
PCLOUD_CLIENT_ID=your_actual_pcloud_app_key_here
```

*(This file is git-ignored, so your personal key will not be committed to source control).*

---

## 3. Rebuild & Run the App

1. Rebuild the app in Android Studio (or run `./gradlew assembleDebug`).
2. Run on your device.
3. In RelayFS, go to **Accounts** and tap **"Sign in with pCloud"**.
4. Log into your pCloud account in the browser tab and tap **"Allow"**.
5. The app automatically captures your access token via the **Token Flow** (`response_type=token`) and connects your storage.

