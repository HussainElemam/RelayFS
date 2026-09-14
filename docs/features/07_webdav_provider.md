# Feature Specification: Generic WebDAV Cloud Provider

**Feature ID:** `FEAT-07`  
**Related Components:** `core:provider`, `core:security`  

---

## 1. Overview & Objective
While pCloud is the primary target provider, the `WebDavProvider` implements RFC 4918 WebDAV to enable two-way sync and virtual drive access for **Nextcloud**, **ownCloud**, **Synology NAS**, **QNAP**, and generic WebDAV servers.

---

## 2. WebDAV Capabilities & Limitations

| Capability | Supported? | Implementation Notes |
| :--- | :--- | :--- |
| **Authentication** | ✅ Yes | HTTP Basic & Digest over TLS/HTTPS. Credentials stored in Android Keystore. |
| **Directory Listing** | ✅ Yes | `PROPFIND` with `Depth: 1` header. XML response parsed using `XmlPullParser`. |
| **File Download** | ✅ Yes | Standard HTTP `GET` with OkHttp streaming response. |
| **File Upload** | ✅ Yes | Standard HTTP `PUT` with `RequestBody` streaming. |
| **File Move / Rename** | ✅ Yes | HTTP `MOVE` with `Destination` header. |
| **File Deletion** | ✅ Yes | HTTP `DELETE`. |
| **Delta Synchronization** | ❌ No | RFC 4918 does not define delta feeds. Engine performs full recursive `PROPFIND` comparison. |
| **Two-Factor Auth** | ⚠️ App Passwords | Requires user to generate an "App Password" in their cloud service (e.g. Nextcloud Security settings). |

---

## 3. Implementation Blueprint

### 3.1 Directory Listing (`PROPFIND`)
* Request:
  ```http
  PROPFIND /remote.php/webdav/Vault/ HTTP/1.1
  Host: nextcloud.example.com
  Authorization: Basic dXNlcjpwYXNzd29yZA==
  Depth: 1
  Content-Type: application/xml
  
  <?xml version="1.0" encoding="utf-8" ?>
  <d:propfind xmlns:d="DAV:">
    <d:prop>
      <d:displayname/>
      <d:getcontentlength/>
      <d:getlastmodified/>
      <d:resourcetype/>
      <d:getetag/>
      <d:getcontenttype/>
    </d:prop>
  </d:propfind>
  ```
* Response parsing maps `<d:response>` XML elements into `CloudItem` domain models:
  * `d:resourcetype/d:collection` indicates a directory.
  * `d:getetag` is stored as `contentHash`.
  * `d:getlastmodified` is parsed to UTC timestamp.

### 3.2 Change Detection via eTags
* On each sync pass, the engine queries the remote directory tree via `PROPFIND`.
* If a remote item's `d:getetag` differs from the stored Baseline `remoteContentHash`, it is marked as `MODIFIED` on Remote.

