# Filter List Manager — Implementation Plan

> Reference project: **PCAPdroid** (`com.emanuelef.remote_capture`), Java, minSdk 23.
>
> Feature:外部 DNS / hosts / USB-formatted contents inside PCAPdroid
> (no MITM needed), user-facing manager UI to choose and update lists.
>
> This plan covers **download + merge + UI** for Layer-1 blocking only.
> AdGuard-parity HTTPS content filtering rules are addressed in `blocking_plan.md`.

---

## Status 🟡 PARTIALLY IMPLEMENTED

> **Source:** item (e) investigation (2026-07-26). Verify against source before relying on this status.

**BypassManager.java** (`app/src/main/java/com/adbye/filter/filterlists/BypassManager.java`, 290 lines, 17 unit tests in `BypassManagerTest.java`) is fully implemented in the Java layer. All 4 layers are present as data/behavior, 17 unit tests cover the Java surface.

| Layer | Java layer | Native/JNI wired? | Notes |
|---|---|---|---|
| §1 App/UID allowlist | ✓ implemented | ❌ NOT WIRED | `! u:pkg` comments in rules file; no `shouldBypassByUid` JNI path exists |
| §2 Domain/SNI allowlist | ✓ implemented | ✅ LIVE | `@@||domain^` rules → `sni_allowlist` in JNI → consulted first in `check_adblock_sni_rules` (constraint #2 preserved) |
| §3 Dynamic flow/payload | ✓ implemented (API) | ❌ NOT ENFORCED | thresholds persist to SharedPreferences; no call site passes byte counts through `isFlowBypassed`/`isLargeDownloadBypassed`; Phase 4 MITM prerequisite |
| §4 FCM port bypass | ✓ implemented (data) | ❌ NOT WIRED | `! p:5228/5229/5230` comments in rules file; no port-gating, no raw splice; Phase 4 MITM prerequisite |

**Status key:**
- ✅ LIVE — wired into native/JNI capture path, operating at runtime
- ❌ NOT WIRED — Java data/class exists, no native consult path; same structural pattern as item (b) orphaned-native-decls finding

**Phase 4 dependency note:** §3 and §4 are framed in this document's own §3 as protecting against OOM from "MITM + content-filtering on high-throughput traffic." Both are primarily Phase 4 prerequisites — §3 prevents OOM from MITM intercepting video downloads; §4 keeps FCM alive without MITM overhead. Neither is urgent until Phase 4 (HTTPS MITM content filtering) lands. §1 App/UID allowlist is *not* MITM-dependent — it is general resource protection and is independently actionable now.

**Implementation Sketch note (see below):** The `shouldBypassByUid`/`shouldBypassByPort`/`shouldBypassBySni` + conntrack-callback architecture described in the Implementation Sketch section was **NEVER IMPLEMENTED**. The sketch is a historical design record only — do not treat it as current architecture.

---

## Resource Protection & Auto-Exceptions (Bypass Logic)

**Context:** MITM + content-filtering on high-throughput traffic (video streaming, large file downloads) or system-critical connections (GSF) causes OOM, lag, and battery drain. PCAPdroid already has a `dynamicBypassSet` pattern (see `LocalHttpsProxy.kt` in original codebase, and equivalent sockets in addon). We extend that with a 3-layer exception system.

> ⚠️ **Status annotation:** Only §2 Domain/SNI is currently LIVE (wired into JNI `sni_allowlist`). §1, §3, §4 are SPEC COMPLETE in Java but NOT wired into the native capture path.

### 1. App-Level Exceptions (Package Name / UID)

> 🔴 SPEC COMPLETE (Java), NOT WIRED to JNI — priority independent of Phase 4 (general resource protection, not MITM-specific)

Traffic from these apps auto-bypass HTTPS filtering/MITM:
- `com.android.vending` (Google Play Store - prevents APK download/update errors)
- `com.google.android.gms` (Google Play Services / GSF - prevents delayed notifications & battery drain)
- `com.google.android.syncadapters.contacts` / `calendar`
- `com.google.android.ims` (Carrier Services - already in existing firewall whitelist)

**Native path:** Not wired. Rules written to merged rules file as `! u:com.android.vending` **comment lines** only. No `shouldBypassByUid` JNI call site exists.

### 2. Domain & SNI-Level Exceptions (Video & CDNs)

> 🟢 LIVE — wired via `sni_allowlist` in JNI `blacklist.c`, consulted first in `check_adblock_sni_rules` (`pcapdroid.c:571`) before the blocklist. Constraint #2 (Resource Protection hardcoded) preserved by construction — allowlist and blocklist are in the same function with allowlist-first ordering.

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

**Native path:** `BypassManager.writeBypassRuleFragment()` (`BypassManager.java:264`) produces `@@||domain^` exception rules → `FilterListManager.mergeEnabledLists()` prepends to `adblock_rules.txt` → JNI `reloadAdblockList()` loads into `bl->sni_allowlist` (`blacklist.c:117`) → `blacklist_match_sni_allowlist` consulted first in `check_adblock_sni_rules` (`pcapdroid.c:571-573`).

### 3. Dynamic Flow & Payload-Level Exceptions

> 🔴 SPEC COMPLETE (Java thresholds persist), NOT ENFORCED at runtime. **Phase 4 MITM prerequisite** — protects against OOM from MITM intercepting high-throughput video/audio/APK traffic. Not actionable until Phase 4 lands.

Streaming videos often use Chunked Transfer Encoding (HLS/DASH) so `Content-Length` is unreliable. CDNs change constantly. Combine both signals:

* **Early Header Bypass:** If response header has `Content-Type: video/*`, `audio/*`, `application/vnd.android.package-archive` (APK), OR `Content-Length` > 20MB → bypass immediately.
* **Per-Flow Threshold Bypass (Dynamic):** Each TCP flow is monitored with a `bytes_received` counter. If a flow exceeds the dynamic threshold (e.g. **> 5 MB** total) *without triggering any adblock rule on the early payload*, the connection is auto-downgraded from `intercepted` to `raw TCP splice`.
  - Benefit: saves CPU, prevents OOM on unrecognized video CDNs, browser large downloads, in-app asset updates (e.g. game asset downloads).

**Native path:** Not wired. `BypassManager.getDynamicThresholdBytes()` / `getLargeDownloadThresholdBytes()` are readable (persist to SharedPreferences), but no native call site passes flow byte counts through `isFlowBypassed(long)` / `isLargeDownloadBypassed(long)`. The `bytes_received` counter described above does not exist in the JNI capture path.

### 4. GMS, GSF & Push Notification Exceptions (3-Layer Bypass)

> 🔴 SPEC COMPLETE (domains covered by §2), NOT WIRED for port/UID — **Phase 4 MITM prerequisite** (keeps FCM from being MITM-intercepted). Port layer: no raw-TCP splice on 5228/5229/5230 wired in JNI. SNI layer: `mtalk.google.com` / `android.clients.google.com` are already in the §2 domain allowlist and protected via the live `sni_allowlist` path (not a §4-specific mechanism).

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

> ⚠️ **CORRECTION (2026-07-26):** The design described below (J2 `BypassManager` singleton with `shouldBypassByUid`/`shouldBypassByPort`/`shouldBypassBySni` methods, wired into a native `conntrack` callback) was **NEVER IMPLEMENTED THIS WAY**. This section is a **historical design record only** — it represents what was planned, not what exists. Current implementation status for each component:
> - `BypassManager.java` singleton: EXISTS (`filterlists/BypassManager.java`), but the three `shouldBypass*` methods are dead APIs — no JNI call sites.
> - JNI `conntrack` callback: NOT wired to `BypassManager`. See the layer-by-layer table in the Status section above for what IS wired.
> - Payload-level bytes tracking: NOT implemented in JNI (`bytes_received` counter per flow does not exist in `pcapdroid.c`).

*Original design record (outdated — see correction note above):*

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