# ADBye — AdGuard-Equivalent Content Blocking Roadmap

> **App name / variant:** **ADBye** (AdBye content filter, built on top of PCAPdroid core).
> Repo: `com.emanuelef.remote_capture`, Java, minSdk 23.
> **Brand asset:** Shield vector (gradient pink → blue with checkmark) — see `icon_bluprint.md`.
> Vector source code committed at `app/src/main/res/drawable/ic_adbye_shield.xml`.
>
> **Goal:** Bring layered AdGuard-parity content filtering to PCAPdroid across:
> 1. **Layer 1** (DNS / hosts / in-process blacklist) — works without MITM, ships today.
> 2. **Layer 2** (SNI early-drop) — read domain from TLS ClientHello, drop at VPN TUN.
> 3. **Layer 3** (HTTPS content filtering) — full AdGuard-rule parsing, cosmetic, scriptlet. Requires the external `com.pcapdroid.mitm` addon's `MitmService` to honor a rules-file IPC field; this repo owns the data path, addon repo owns the parser.
>
> Each layer is independently shippable and falls back gracefully if downstream capability is missing.
>
> **Status:** ⬜ NOT STARTED (all phases).

---

## Master Plan Index

| Module | Doc | Owns |
|--------|-----|------|
| Filter downloader + multi-source parsing | `filter_list_plan.md` | URL fetching, AdGuard / hosts / EasyList parsing, merge to `filesDir/adblock_rules.txt`, ship Resource Protection bypass logic (UID/port/SNI/dynamic flow) |
| Protection tab UI inside `FirewallActivity` | `firewall_blocking_tab.md` | 4th tab (`POS_PROTECTION`) with 6 master toggles wiring pref flags to engine hot-reloads |
| Cross-module master switches (Ad/Tracking/Annoyance/DNS/Firewall/Security) | `protection_layout.md` | Reference design pattern; already integrated into `firewall_blocking_tab.md` |
| Per-UID app overrides | `app_management_tab_layout_blueprint.md`| `AppConfigEntity`, split tunneling, per-UID filterHttps/filterTraffic; hooked from Protection tab → Firewall reload |
| Network / capture mode / upstream proxy | `Network_layout_tab.md` | HTTPS master switch, upstream proxy wiring, routing mode (VPN/Root/Proxy) |
| AdGuard-parity blocking (full HTTP/HTTPS) | `blocking_plan.md` *(to be added)* | 3-layer design (L1/L2/L3) and IPC contract with `PCAPdroid-mitm` addon |

---

## Phased Roadmap & Testing Gates

### Phase 0 — Plumbing (Data & Engine Sync)
*Focus: Fetching, parsing, and merging filter lists into a single flat file seamlessly.*
- [ ] Implement `FilterListManager.java` (URL fetcher + file merge logic).
- [ ] Implement `FilterListRepository` (SQLite Room or JSON-backed SharedPreferences).
- [ ] Expand `addList()` to support user-added custom filter URLs.
- [ ] **⚙️ Testing Gate:**
  * *Unit Test:* Mock 3 filter URLs. Verify `FilterListManager.merge()` correctly combines them into `filesDir/adblock_rules.txt` without duplicates.
  * *Manual Check:* Inspect the generated `.txt` file via Android Studio Device Explorer to ensure standard AdGuard syntax is preserved.

### Phase 1 — GUI: Protection Tab
*Focus: Exposing master switches to the user and triggering hot-reloads.*
- [ ] Add 4th tab (`POS_PROTECTION`) to `FirewallActivity`.
- [ ] Implement `ProtectionFragment` with the 6 master toggles (Ad, Tracking, Annoyance, DNS, Firewall, Security).
- [ ] Wire UI state to `Prefs.java` (e.g., `pref_protect_adblock`).
- [ ] Trigger `FilterListManager.mergeEnabledLists()` dynamically when toggles change.
- [ ] **⚙️ Testing Gate:**
  * *UI Test (Espresso):* Click toggles and verify `Prefs.java` updates.
  * *Integration Test:* Toggle "Ad blocking" off while VPN is running. Verify `adblock_rules.txt` is regenerated instantly (excluding Ad lists) *without* restarting `CaptureService`.

### Phase 2 — Resource Protection (The "Anti-Crash" Layer)
*Focus: Ensuring critical system traffic and heavy payloads bypass the filtering engine completely.*
- [ ] Create `BypassManager.java` singleton.
- [ ] Hardcode App/UID Allowlist: `com.android.vending`, `com.google.android.gms/gsf/ims`.
- [ ] Hardcode Domain/SNI Allowlist: `googlevideo.com`, `mtalk.google.com`, CDN domains.
- [ ] Implement FCM Port Bypass: Drop MITM on ports 5228, 5229, 5230.
- [ ] Implement Dynamic Flow Threshold: Splice connection to raw TCP if payload > 5MB or `Content-Length` > 20MB.
- [ ] **⚙️ Testing Gate:**
  * *Manual (Video):* Open YouTube. Verify video plays immediately in 1080p. Check Logcat to ensure `BypassManager` printed "Bypassed googlevideo.com".
  * *Manual (Push):* Send a test WhatsApp or Firebase push notification. Verify it arrives instantly (port 5228 bypass).
  * *Manual (Download):* Download a large game (e.g., 2GB) from the Play Store. Ensure VPN memory usage stays flat (Dynamic Flow Bypass active).

### Phase 3 — SNI Early-Drop (Layer 2 Blocking)
*Focus: Blocking HTTPS tracker domains at the handshake level without needing a CA certificate.*
- [ ] Hook into PCAPdroid's TLS ClientHello parser.
- [ ] Read SNI and match against the merged domain blocklist from Phase 0.
- [ ] If matched, immediately close/drop the socket.
- [ ] Respect `filterHttps=false` per-UID setting from the App Management UI (skip drop).
- [ ] **⚙️ Testing Gate:**
  * *Network Test:* Run a test device without the MITM CA installed. Visit a known tracker domain (e.g., `google-analytics.com`).
  * *Verification:* The browser should immediately show `ERR_CONNECTION_CLOSED` or `ERR_CONNECTION_RESET`, proving the drop happened at the SNI layer.

### Phase 4 — HTTPS Content Filtering (Layer 3 via MITM Addon)
*Focus: Passing the rule payload to the PCAPdroid-mitm app for deep cosmetic/scriptlet injection.*
- [ ] Update `MitmAPI.MitmConfig` IPC bundle to include `rulesPath` and `cosmeticLibraryPath`.
- [ ] Ensure `FilterListManager` passes the absolute path of `adblock_rules.txt` via IPC.
- [ ] (Cross-repo) Add parsing for AdGuard syntax, CSS injection, and Scriptlets in the `PCAPdroid-mitm` codebase.
- [ ] Gate the "HTTPS filtering" UI toggle behind `MitmAddon.needsSetup()` (require CA install).
- [ ] **⚙️ Testing Gate:**
  * *E2E Test:* Install both ADBye and the modified MITM addon. Enable HTTPS filtering.
  * *Browser Test:* Visit `adblock-tester.com` or `d3ward.github.io/toolz/adblock`. Verify cosmetic rules (hiding empty ad containers) and scriptlets are successfully executed inside the page HTML.

---

## Non-Negotiable Constraints

## Non-Negotiable Constraints (Anti-Fake Green Tests)

1. **App branding.** App label is "ADBye" (`strings.xml` → `app_name`). Launcher icon variants derive from `ic_adbye_shield.xml` (vector, gradient fill + checkmark). Tinting matches the AdGuard-pastel theme.
2. **Resource Protection is hardcoded.** Video CDNs / Google push / Play Store cannot be accidentally blocked even if user enables "Strict" mode. See `filter_list_plan.md` § Resource Protection.
3. **Release-tested (No Debug-Only Illusions).** Each phase MUST be tested and shipped inside a **Release APK** (with ProGuard/R8 enabled). "Green tests in Android Studio" do not count as done. The developer must provide a built `.apk` for every completed phase.
4. **End-to-End (E2E) Wiring Proof.** Unit tests are not enough. Every UI toggle must have an Espresso E2E test that proves the UI click actually alters the underlying network behavior (e.g., Clicking "Ad Blocking OFF" -> Espresso verifies `adblock_rules.txt` is updated -> Network request to `ads.com` succeeds).
5. **JNI & IPC Verification.** For Layer 2 (SNI) and Layer 3 (HTTPS MITM), testing must include actual packet interception. Mocking the PCAPdroid core or Mocking the MitmService IPC is **STRICTLY PROHIBITED** for final validation.
6. **MITM is opt-in.** Master switch `"pref_global_https_filtering"` must default `false`. CA install + `MitmAddon.needsSetup()` gates the L3 menu.
7. **Hot-reload.** Toggling any of the 6 master switches in the Protection tab must re-merge rules and reload the engine without restarting `CaptureService`.
8. **Automated CI/CD Emulator Testing.** Code integration is gated by automated UI/E2E tests running on a CI/CD emulator (e.g., GitHub Actions with UIAutomator/Espresso). 
   - The test must physically install the APK, accept the VPN permission dialog, toggle the Protection switches, and perform real HTTP requests to verify dropping/blocking behavior.
   - Code that passes Unit Tests but fails the Emulator E2E pipeline will be rejected.