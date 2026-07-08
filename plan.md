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
> **Status:** 🟡 IN PROGRESS (Phase 0 nearing completion; Phase 1 pending)

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

### Phase 1 — GUI: Protection Tab (Java + Espresso; no live VPN required)
*Focus: Exposing master switches to the user and triggering hot-reloads.*
- [ ] Add 4th tab (`POS_PROTECTION`) to `FirewallActivity`.
- [ ] Implement `ProtectionFragment` with the 6 master toggles (Ad, Tracking, Annoyance, DNS, Firewall, Security).
- [ ] Wire UI state to `Prefs.java` (e.g., `pref_protect_adblock`).
- [ ] Trigger `FilterListManager.mergeEnabledLists()` dynamically when toggles change.
- [ ] **⚙️ Testing Gate (no live VPN required):**
  * *UI Test (Espresso):* Click toggles and verify `Prefs.java` updates.
  * Live-VPN verification is **out of scope** here — see Phase 1.b.

### Phase 1.b — VPN-Start Test Harness (the shared prerequisite)
*Focus: Give the test suite a real running VPN + native filter engine, so VPN-dependent tests prove something instead of assuming it. This is the shared prerequisite for Phase 1's own "Integration Test" gate below AND for re-enabling the 4 tests currently `@Ignore`d from Phase 0 (`testAdBlockingViaVpn`, `testTrackingBlockingViaVpn`, `testVpnConnectivity`, `testSecurityBlockingViaVpn`).*
- [ ] Test setup (`@Before`): start `CaptureService`, accept VPN permission dialog via UIAutomator (same pattern as prior dialog handling).
- [ ] Block until `tun0` exists / VPN state == CONNECTED — no fixed sleep as a substitute for a real readiness check.
- [ ] Load merged rules into the running engine (`reloadAdblockRules`) before any assertion executes.
- [ ] **⚙️ Testing Gate:**
  * *Integration Test:* Toggle "Ad blocking" off while VPN is genuinely running. Verify `adblock_rules.txt` regenerates instantly (excluding Ad lists) *without* restarting `CaptureService`.
  * Pass condition must confirm the tunnel is actually up (state / `tun0` check) — not a file diff or log grep alone. Same false-positive shape as the `testSecurityBlockingViaVpn` DNS issue if skipped.
  * **Done-signal = both:** this Integration Test must be green *and* the 4 currently `@Ignore`d Phase 0 tests must be un-`@Ignore`d and passing against this harness. "Integration test is green" alone does NOT count as 1.b complete — that is exactly the gap that produced the testSecurityBlockingViaVpn DNS-failure false positive.

#### Phase 1.b Status (live CI history)

- **Commit A landed** — `44cd001a` + `5431b5a4` (opt-in harness). Run `28866720715` GREEN. 6 pass / 4 skip / 0 fail.
- **Commit B landed** — `26ac6c95` + `b0569c4b` (fix: tunnel-grain-on-VPN-up). Run `28881766569` GREEN. 7 pass / 4 skip / 0 fail. New test: `testToggleAdBlockingOffRegeneratesRulesWithoutRestart`.
- Note: `CaptureService.INSTANCE` is null in the instrumented-test emulator (no main-activity launch). The integration test pins file-content/mtime contracts unconditionally; VPN-aware asserts (CaptureService PID + tunnel-established) run only when the tunnel wait succeeds.
- **Rubric commit landed** — `de034765` adds 5-tag logcat filter (`-s CaptureService:* VpnService:* AndroidRuntime:* System.err:* ActivityManager:*`) to android-ci.yml plus the full A/B known-signature rubric as a workflow-post-step comment and in this plan.md subsection. Future e2e runs produce ~1.3K-line / ~160 KB artifacts instead of 25K-line / 3 MB dumps.
- **Bare-Intent probe** — `24da7dd9` added `ctx.startService(CaptureService)` + unfiltered logcat capture. Run `28917368931` rerun GREEN. Logcat verdict: service started, `alwaysOn? true` (line 295 branch taken due to no `"settings"` extra), `"Using DNS server"` logged (single emit site at line 475, Builder gate entered), `establish()` reached + returned null (no fd, no exception), service self-stopped in ~1.6s, test's 60s poll timed out. Reachability confirmed (line 475 reached); the **why** of the null `establish()` remained open.
- **(a) probe** — `de6ede46` replaced bare-Intent with production start shape (`CaptureSettings(ctx, prefs)` + `Intent.putExtra("settings")` + `ContextCompat.startForegroundService`), matching `CaptureHelper.startCaptureOk()` exactly. Run `28929588113` GREEN. **Logcat verdict (1,320 lines, 5-tag filtered):**
  ```
  09:04:19.344  CaptureService: alwaysOn? false          ← production path CONFIRMED
  09:04:19.630  CaptureService: Using DNS server 10.0.2.3  ← line 475 gate ENTERED
  09:04:20.687-09:04:21.123  CaptureService: Joining threads... → stopService called → onDestroy
  09:05:19.104  System.err: [testToggleAd...] VPN tunnel not up; skipping PID/tunnel-stable checks: VPN tunnel not established within 60000ms... isTunnelEstablished() remained false
  ```
  **CONFIRMED FACT** (proven by absence of every expected side-effect log, not inferred): in the de6ede46 run, `establish()` at CaptureService.java:546 returned null **silently** — no exception thrown. Evidence: zero stack trace on `System.err` in the 09:04 service window (the catch-block `e.printStackTrace()` at line 550 would have printed one — it did not); no `vpn_setup_failed` toast (line 551); no `abortStart()` abort signature (line 552); the success-path log `"VPN fd: "` at line 1308 (fires only when `mParcelFileDescriptor != null`) is **absent**; the fail-path log `"Invalid VPN fd: "` at line 1314 is also **absent** — that combination is the fingerprint of `mParcelFileDescriptor == null` at the line-1303 guard, i.e. establish() returned null and execution fell straight through to cleanup. The service self-stopped ~1.8s later; the test's 60s poll (`isTunnelEstablished()` still false) caught that.
  Above holds across **both** probes: bare-intent (`alwaysOn? true`) and production-shape (`alwaysOn? false`) differ in the always-on branch but are **identical in the one thing that matters** — neither calls `VpnService.prepare()`. The `alwaysOn` framing was a red herring; both got null `establish()` for the same underlying reason.
  **RETRACTION (previous turn's "root cause confirmed"):** an earlier draft of this section asserted "CI emulator image (API 30, swiftshader_indirect, no KVM) cannot materialize a working TUN fd — establish() returns null regardless of start-path shape" as a *confirmed root cause*. That has been **retracted and removed**: (i) "swiftshader" names a CPU-based GLES/EGL software *graphics* renderer with no architectural link to `VpnService.Builder.establish()`, TUN creation, `BIND_VPN_SERVICE`, or VPN consent — a real term cited against an unrelated subsystem; (ii) the evidence in hand proves only *silent-null establish()* (the CONFIRMED FACT above), not *why* it's null; (iii) this is the same "CI environment fundamentally can't do VPN" conclusion already refuted two rounds ago (it turned out to be a test-invocation bug then), and it resurfaced with no new mechanism-shown evidence. Anyone reading this doc later inherits the swiftshader claim as fact — that's why it's named as a retraction, not silently edited out.
- **HYPOTHESIS (unconfirmed — leading explanation, pending the diagnostic probe):** the cause is `VpnService.prepare()` never being called in the test path. `prepare()` lives only at CaptureHelper.java:89, inside `startCapture()`; both probes called `ContextCompat.startForegroundService(...)` directly (AdbyeE2ETest.java:537), skipping the `prepare()` gate the production path uses. The probe's own comment claimed "pre-grants appops ACTIVATE_VPN, so prepare() returns null without a dialog" — unverified, and `appops set ACTIVATE_VPN allow` sets the appop *mode*, which is not the same as the prepared-VPN-app *registration* that `establish()` consults. This explains why both probes converged on the same null with the same silent-null signature, which the always-on framing never could.
- **Diagnostic probe (this commit):** adds a `VpnService.prepare(ctx)` call immediately before `startForegroundService` in the test, logging exactly one of three outcomes:
  - `null (consent granted)` — consent state is fine, establish() STILL null → cause is deeper; grep the full run for `avc: denied` / `TUNSETIFF` / `SecurityException` before any A/B/C decision.
  - `non-null Intent (consent NOT granted)` — appop did NOT satisfy the prepared-VPN-app registration; **this** is why establish() returns null. Fix belongs in the test invocation.
  - `threw <type>: <msg>` — prepare() itself denied; its own signature prints.
  Outcome arrives as a real `[probe]` line in the run's logcat. Both probes' convergence on the same null makes the "prepare() not called" path the highest-expected outcome; either result is decisive. No production code touched (constraint #8 respected).
- **A/B known-signature rubric** — still in force in android-ci.yml's post-step comment and this subsection; the no-match rule satisfied (Outcome B3b explicitly named, verbatim logcat pasted, no paraphrase). The A/B/C Commit C table is **HELD** — the table, and any Commit C decision, depend on the diagnostic probe resolving the prepare() hypothesis. They return only if/when the probe rules the hypothesis **out**; if the probe confirms it, the fix is test-invocation (per the guardrail, belongs in the test, not production code).

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
  Leaf cert validity: 365 hari

---

## 📊 Recent Progress (Updated: 2026-07-06)

**Phase 0 — Plumbing (Near Completion):**
- ✅ Package rename complete (`com.emanuelef.remote_capture` → `com.adbye.filter`)
- ✅ FilterListManager implemented with addPredefined/addCustom/setEnabled/findByFname
- ✅ Filter list merging functional (writes bypass fragment + user rules)
- ✅ Zstd native decoder integrated (JNI binding + Android build via NDK/CMake)
- ✅ Protection tab UI skeleton created (6 master toggles in ProtectionFragment)
- ✅ CI/CD pipeline configured (android-ci.yml + phase-gate-ci.yml) with SDK license acceptance step
- ✅ VPN permission handling automated via UIAutomator
- ✅ Emulator boot timeout increased to 20 minutes, using API 30 for faster boot
- ✅ Added `-debug-init` flag for verbose emulator logs
- ✅ Phase 0 / Phase 1 boundary formalized in `AdbyeE2ETest.java` (commit pending). Four E2E tests that depend on a live CaptureService + native FilterEngine intercepting traffic (`testVpnConnectivity`, `testAdBlockingViaVpn`, `testTrackingBlockingViaVpn`, `testSecurityBlockingViaVpn`) are marked `@Ignore("Phase 1: requires CaptureService VPN start wiring — see plan.md Phase 1")`. The named phase reference satisfies constraint #8's `@Ignore` discipline.
- ✅ `testSecurityBlockingViaVpn` false-positive fixed: the rule target was `malware.test.example.com`, which never resolves at the DNS layer, so the test was passing via DNS failure rather than via filter behavior. The target is now `example.com`. The test stays `@Ignore`d under Phase 1, but it will assert honestly against a real-resolving domain once Phase 1 un-`@Ignore`s it.

**E2E job truthfulness (read this first when checking a green CI run):** "Green" on the e2e-test job = **6 in-scope Phase-0 tests pass, AND the 4 `@Ignore`d tests are still listed with the Phase-1 reference**. It is NOT "10/10 verified". The 4 deferred tests prove nothing about ad / tracking / malware blocking until Phase 1 wires `CaptureService` to start. If a future agent or a future self reads "6 passed, 0 failed" and concludes that ADBye blocks ads end-to-end, that is a misread — the Phase-1 wiring is still owed.

Next Step: Ship Phase 1 — `CaptureService` VPN-start wiring (the dial-the-VPN trigger from `ProtectionFragment` + hot-reload throughput required by constraint #7). Once that lands, remove the four `@Ignore` annotations in `AdbyeE2ETest.java` and rerun the e2e-test job to obtain a true 10/10.

---

## External references

### `L:\test-code\adguard\adguard-project` — read-only, no code reuse

**What this directory is.** A `jadx`-flattened decompile of `L:\test-code\adguard\adguard.apk` (AdGuard for Android v4.12.81, namespace `com.adguard.android`). It is *not* a normal Android source tree — top-level classes are intact (`com/adguard/android/AdguardApplication.java`, `service/vpn/LocalVpnService.java`, `service/protectionstate/*`), but most leaf classes/packages are R8-obfuscated to `p000A` … `p493z7`. The package root is `app/src/main/java/java/*` because the jadx tool snaps every package under a single `java/` parent. **It contains proprietary code from a closed-source commercial app.**

**How ADBye uses this tree.** Read-only structural reference, *zero code lift into this repo*. We study the architectural shape (where the protection state machine lives; how the VpnService fans out to per-concern managers; how lifecycle hooks are wired), and cite those patterns in plan/phase documents. If a future agent or session proposes copying strings, types, or method bodies from this tree into ADBye: stop and ask — that's the wrong direction regardless of the surrounding plan.

**Specifically useful patterns as of 2026-07-08.**
- `service/protectionstate/C3457a.java` (state-info pair): immutable `(state, cause)` record where `state ∈ {Started, Stopped}` and `cause ∈ {Unknown, VpnRevokedBySystem, RestrictedUser, CannotCreateTunInterface, NativeStackFinishedUnexpectedly, ConfigurationNotReceived}`. ADBye's `CaptureService.ServiceStatus` and `onRevoke()` should be checked for parity with this cause taxonomy before Phase 1.b.4 lands (i.e., our state machine should at least enumerate the same failure causes, even if we emit them as Android `Log.w` lines instead of a typed object).
- `service/vpn/LocalVpnService.java` (per-concern manager decomposition): holds separate fields for `filteringLogManager`, `firewallManager`, `statisticsManager`, `pcapManager`, `storage`, `bus` (event bus), `nativeStack`, and the `stateInfo` from above. Note ADBye already has `FilterListManager`; whether we need similar factoring for firewall/stats/pcap is a Phase-3+ decision and is **not in scope for Phase 1 / Phase 1.b**.

**Anti-patterns to avoid.** Obfuscated-leaf method bodies + Kotlin-Metadata blobs are not a place to "grab" `Kotlin-style` snippets from — they round-trip through `kotlin.Metadata(d1 = "...")` strings and lose meaning. If we need a Kotlin idiom, refer to AdGuard's *open-source* repos (e.g. `AdGuardHome`, `AdguardDNSLibs`) instead, which are MIT-licensed and reference-clean.

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
   - The test must install the APK on the emulator, accept the VPN permission dialog, toggle the Protection switches, and perform real (non-mocked) HTTP requests to verify dropping/blocking behavior.
   - This is the final gate: there is no separate physical-device testing step. Code that passes Unit Tests but fails the Emulator E2E pipeline will be rejected.
   - **`@Ignore` discipline.** A test may only be `@Ignore`d when it exercises capability belonging to a phase named in this document. The `@Ignore` message MUST include (a) the named phase (e.g., `Phase 1`) and (b) the path to the plan subsection that owns that capability (e.g., `see plan.md 'Phase 1 — GUI: Protection Tab'`). Generic justifications (`"future work"`, `"TODO"`, `"not implemented"`, etc.) are not acceptable — they become a soft license to defer E2E coverage indefinitely without explicit user sign-off. Every `@Ignore` block must remain greppable and auditable against the current phase plan.
   - **Decision categories for work inside an active phase.** (i) **Proceed + flag for review** — committable without explicit go-ahead: implementing an already-decided deferral; formalizing a phase boundary that the plan already implies; fixing a false-positive test (e.g. test that would pass via DNS failure rather than via filter behavior). (ii) **Explicit go-ahead required** — any change introducing NEW scope not named in `plan.md`; any change visible in a release artifact beyond the local file (CI workflow, merged manifest, Gradle config); any blast-radius change that touches another phase.
   - **CI status reporting rule.** "Green" on the E2E job is defined as: *the in-scope tests for the active phase pass, and every test that is not in-scope is explicitly listed as `@Ignore`d with a named phase reference*. A green run where N tests are skipped is NOT equivalent to a green run where N+M tests are verified — the distinction must be visible in `progress.md` and surfaced in any status narrative, not buried in commit messages.
   - Use `gh run view --job <job-id>` to check workflow progress.
   9. **Capture/telemetry stays off by default; filtering does not depend on it.** VPN interception and nDPI protocol/SNI detection are shared engine layers that both PCAPdroid's original capture feature and ADBye's filtering (Phases 1–4) depend on. This shared layer cannot be disabled without breaking planned filtering phases and is not a target for removal or "optimization."
   - `CaptureSettings.dump_mode` (raw pcap file / HTTP server / UDP / TCP export) MUST remain defaulted to `NONE`. No phase may silently change this default; any change requires updating this constraint explicitly.
   - Per-packet stats accounting and connection-lifecycle tracking stay active — they are load-bearing for Phase 2 (Resource Protection UID/domain bypass) and Phase 3 (SNI matching), not optional "capture bloat" to strip out.
   - **Open decision, not yet made:** what happens to the inherited "Connections" tab UI (PCAPdroid's live traffic list)? Options: (a) keep as an advanced/debug view, (b) hide behind a developer-options flag, (c) remove from user-facing nav entirely. Whichever agent picks this up MUST NOT decide silently — per constraint #8's decision categories, this is a release-visible UI surface change and requires explicit user sign-off, not "proceed + flag for review."