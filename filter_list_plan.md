# Filter List Manager — Implementation Plan

> Reference project: **PCAPdroid** (`com.emanuelef.remote_capture`), Java, minSdk 23.
>
> Feature:外部 DNS / hosts / USB-formatted contents inside PCAPdroid
> (no MITM needed), user-facing manager UI to choose and update lists.
>
> This plan covers **download + merge + UI** for Layer-1 blocking only.
> AdGuard-parity HTTPS content filtering rules are addressed in `blocking_plan.md`.

---

## Status ⬜ NOT STARTED

---

## Resource Protection & Auto-Exceptions (Bypass Logic)

**Context:** MITM + content-filtering on high-throughput traffic (video streaming, large file downloads) or system-critical connections (GSF) causes OOM, lag, and battery drain. PCAPdroid already has a `dynamicBypassSet` pattern (see `LocalHttpsProxy.kt` in original codebase, and equivalent sockets in addon). We extend that with a 3-layer exception system.

### 1. App-Level Exceptions (Package Name / UID)
Traffic from these apps auto-bypass HTTPS filtering/MITM:
- `com.android.vending` (Google Play Store - prevents APK download/update errors)
- `com.google.android.gms` (Google Play Services / GSF - prevents delayed notifications & battery drain)
- `com.google.android.syncadapters.contacts` / `calendar`
- `com.google.android.ims` (Carrier Services - already in existing firewall whitelist)

### 2. Domain & SNI-Level Exceptions (Video & CDNs)
Hardcoded system-wide whitelist (`@@||domain.com^` style, prepend to merged rule file ahead of user rules):
- **Video CDNs:**
  - `googlevideo.com` (YouTube streams)
  - `nflxvideo.net` (Netflix)
  - `fbcdn.net` (Facebook CDN)
  - `cdninstagram.com` (Instagram CDN)
  - `ttvnw.net` (Twitch)
  - `tiktokcdn.com` (TikTok)
- **File CDNs:**
  - `play.googleapis.com`
  - `dl.google.com`

### 3. Dynamic Flow & Payload-Level Exceptions
Streaming videos often use Chunked Transfer Encoding (HLS/DASH) so `Content-Length` is unreliable. CDNs change constantly. Combine both signals:

* **Early Header Bypass:** If response header has `Content-Type: video/*`, `audio/*`, `application/vnd.android.package-archive` (APK), OR `Content-Length` > 20MB → bypass immediately.
* **Per-Flow Threshold Bypass (Dynamic):** Each TCP flow is monitored with a `bytes_received` counter. If a flow exceeds the dynamic threshold (e.g. **> 5 MB** total) *without triggering any adblock rule on the early payload*, the connection is auto-downgraded from `intercepted` to `raw TCP splice`.
  - Benefit: saves CPU, prevents OOM on unrecognized video CDNs, browser large downloads, in-app asset updates (e.g. game asset downloads).

### 4. GMS, GSF & Push Notification Exceptions (3-Layer Bypass)
Aggressive bypass to keep notifications instant and avoid killing non-HTTP protocols:

* **Layer 1: Port-Level Bypass (fastest)**
  All TCP to ports **5228, 5229, 5230** (FCM/GCM keep-alive) → raw TCP, never enter MITM engine. Saves CPU on always-on connections.
* **Layer 2: UID / Package Level Bypass**
  `VpnService.getConnectionUid()` matches:
  - `com.google.android.gms` (Google Play Services)
  - `com.google.android.gsf` (Google Services Framework)
  - `com.android.vending` (Play Store)
  → bypass (status `intercept = false`).
* **Layer 3: SNI / Domain Fallback (for microG & 3rd-party Firebase)**
  If Layer 1+2 fail (e.g. 3rd-party app using Firebase), but TLS ClientHello SNI shows:
  - `mtalk.google.com` (and `alt*.mtalk.google.com`)
  - `android.clients.google.com`
  - `play.googleapis.com`
  → abort MITM, splice.

### Implementation Sketch (PCAPdroid)

1. **`BypassManager.java`** — singleton stored in `PCAPdroid.getInstance()`:
   - Static lists:
     - `appUidAllowlist` (UIDs for Vending/GMS/GSF/IMS, resolved once + refreshed on `ACTION_PACKAGE_*`)
     - `domainAllowlist` (Set<String> from hardcoded video CDNs + Google push domains)
     - `fcmPorts = [5228, 5229, 5230]`
   - Methods: `shouldBypassByUid(int)`, `shouldBypassByPort(int)`, `shouldBypassBySni(String)`
2. **`CaptureService` / VPN pipeline hook:**
   - When TCP connection is established (`conntrack` callback), call `BypassManager.shouldBypassByUid(uid)` first. If true → mark connection as **no-inspect** (`block_inspection=true` flag) and pass-through at socket level.
   - When `dnshandle` resolves SNI from TLS ClientHello, call `BypassManager.shouldBypassBySni(sni)` → mark no-inspect before MITM can latch.
   - First packet inspect: if `src_port ∈ fcmPorts` or `dst_port ∈ fcmPorts` → mark no-inspect.
3. **Payload-level bypass (in MITM addon or in HTTP reassembler):**
   - If `intercept=true` initially but response headers match `video/*` or `Content-Length > 20MB` → flip to `raw splice`.
   - Track `bytes_received` per flow; on threshold breach with no prior rule hit, switch to `raw splice` mid-stream.
4. **Whitelist insertion in merged rule file:**
   - During `FilterListManager.mergeEnabledLists()`, prepend hardcoded `@@||googlevideo.com^` etc. as the **first** rules in `adblock_rules.txt` so they win precedence over user lists. User cannot toggle these off (only mark them hollowed by adding their own `@@||...^` whitelist entries — already supported by AdGuard rule precedence).
