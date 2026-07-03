# Firewall "Protection" Tab — Implementation Plan

> **App:** **ADBye** (AdBye, content-filter variant of PCAPdroid).
> **Brand shield icon:** `app/src/main/res/drawable/ic_adbye_shield.xml` (gradient pink → blue + checkmark, matches `icon_bluprint.md`).
> **Tab icon (toolbar of `FirewallActivity`):** the same drawable, applied to `POS_PROTECTION`. Used in `tab.setIcon(R.drawable.ic_adbye_shield)` next to the localized string `R.string.protection`.
>
> **Purpose:** Add a 4th tab "Protection" inside `FirewallActivity` that mirrors AdGuard's "Protection" master-switch screen and gates three feature groups: filter lists / content-blocking, app-level overrides, and network/capture mode.
> **Status:** ⬜ NOT STARTED

---

## 1. Where this tab fits

`FirewallActivity` is a `ViewPager2` with 3 tabs today: STATUS, BLOCKLIST, WHITELIST. We extend `StateAdapter` to add tab `POS_PROTECTION = 3` and a `FragmentProtection` that hosts 6 master toggles.

Each toggle is a single SharedPreferences key (`pref_protect_*`); it gates a backend module defined in a sibling plan.

| Toggle (UI row)                 | Pref key                  | Drives module / plan                              |
|--------------------------------|---------------------------|--------------------------------------------------|
| Ad blocking                    | `pref_protect_adblock`    | `filter_list_plan.md` (FilterListManager.mergeEnabledLists → "ad_blocking" category) |
| Tracking protection            | `pref_protect_tracking`   | `filter_list_plan.md` ("privacy" category)        |
| Annoyance blocking             | `pref_protect_annoyance`  | `filter_list_plan.md` ("annoyance" category)      |
| DNS protection                 | `pref_protect_dns`        | `filter_list_plan.md` + DNS path in `CaptureService` |
| Firewall                       | `pref_protect_firewall`   | existing `Blocklist`/`MatchList` + `app_managemen_tab_layout_blueprint.md` |
| Browsing Security              | `pref_protect_security`   | `filter_list_plan.md` ("security" category) + malware list in `Blacklists.java` |

Defaults: all `true` except `pref_protect_firewall` which already has logic in `Prefs.isFirewallWhitelistInitialized()`.

## 2. Files added

```
app/src/main/java/com/emanuelef/remote_capture/fragments/ProtectionFragment.java   (NEW)
app/src/main/res/layout/fragment_protection.xml                                    (NEW — list of 6 rows from protection_layout.md)
app/src/main/res/layout/protection_row.xml                                         (NEW — single row: icon + title + subtitle + SwitchMaterial)
app/src/main/res/values/strings.xml                                                (add 12 strings: 6 titles + 6 subtitles)
```

`ProtectionFragment` extends `Fragment`, uses `RecyclerView` with a `ProtectionAdapter` holding 6 `ProtectionRow` items. Each row holds a `Consumer<Boolean>` listener that runs when the switch flips.

## 3. File changes

| File | Change |
|------|--------|
| `FirewallActivity.java` | Add `POS_PROTECTION = 3`, `TOTAL_COUNT = 4`. Extend `StateAdapter.createFragment()` (always-visible, unlike whitelist). Update `getPageTitle()` to return `R.string.protection`. Update `getItemCount()` to `TOTAL_COUNT`. Bump `mPager.setOffscreenPageLimit(TOTAL_COUNT - 1)`. |
| `PCAPdroid.onCreate()` | After `mFirewallWhitelist` init, ensure Protection prefs default to `true` for the 6 keys (only when the user has not explicitly set them). |
| `Prefs.java` | Add 7 constants (`PREF_PROTECT_ADBLOCK` etc.) + getters/setters, mirroring existing `Prefs.isFirewallWhitelistMode()`. |
| `CaptureCtrl` / relevant CaptureService hooks | See section 4. |

## 4. Backend hook contract

Each toggle listener is a thin wrapper that:
1. Writes the new pref value (`prefs.edit().putBoolean(...).apply()`).
2. Calls into the relevant engine hot-reload.

| Toggle | Hot-reload call |
|--------|-----------------|
| Ad / Tracking / Annoyance / Security | `FilterListManager.getInstance().mergeEnabledLists()` (defined in `filter_list_plan.md`) — re-reads enabled lists filtered by category, rewrites `filesDir/adblock_rules.txt`, then calls `FilterEngine.loadRulesFromFile()` and triggers `CaptureService.requireInstance().reloadBlocklist()` equivalent. Show progress bar during reload. |
| DNS protection | `CaptureService.setDnsProtection(enabled)` — adds/reverts a flag consulted in DNS resolver path inside `CaptureService.java`. |
| Firewall | `CaptureService.requireInstance().reloadBlocklist()` and `reloadFirewallWhitelist()` so the new pref is re-evaluated against per-UID `AppConfigEntity` (see `app_managemen_tab_layout_blueprint.md`). |

The 6 toggles are independent — flipping one does not affect the others. The fragment does not own engine state; it only writes prefs and dispatches reloads.

## 5. Tying into the other plans

This tab is the **single entry point** a user needs to discover the three siblings:

* Tapping any row's title (or info icon at right) opens the relevant screen:
  * Ad / Tracking / Annoyance / Security → filter category list (`filter_list_plan.md`).
  * DNS protection → DNS settings screen (PCAPdroid already has one in `Menus` / Settings).
  * Firewall → current FirewallActivity tabs (we deep-enable BLOCKLIST/STATUS).

* Header card at the top of the fragment is a small "summary" computed at runtime: total enabled rules across active lists, last update timestamp. Pure `TextView`, no business logic — read from `FilterListManager.getStats()`.

## 6. Edge cases

* **First run:** All 6 toggles default `true`. If user disables everything the activity tab will show all switches off (consistent).
* **Service not running:** Toggling must not crash; we only call reloads when `CaptureService.isServiceActive()`.
* **DNS private:** When `pref_protect_dns=false`, malware-detection connection cardinality is unaffected (malware check is independent). `Blacklists.java` continues to operate regardless of DNS toggle.
* **Tablet/foldables:** Layout stays 2-pane-friendly; row layout uses `SwitchCompat` end-aligned.
* **Localization:** Provide `values-XX/strings.xml` overrides for the 6 titles + 6 subtitles when ready.

## 7. Checklist

- [ ] Add 6 pref constants + getters to `Prefs.java`.
- [ ] Create `fragment_protection.xml` + `protection_row.xml`.
- [ ] Create `ProtectionFragment` + `ProtectionAdapter` (RecyclerView).
- [ ] Extend `FirewallActivity.StateAdapter` for `POS_PROTECTION`.
- [ ] Wire 6 toggle listeners to engine hot-reload calls.
- [ ] Show progress indicator during list re-merge (~2-5 s).
- [ ] Add header summary card reading from `FilterListManager` (or stub if backend lands later).
- [ ] Run unit tests for `Prefs` round-trip, Fragment state restore.

## 8. Definition of Done

* Toggling "Ad blocking" off in a release APK re-merges `adblock_rules.txt` to a 0-rule subset within 5 seconds and the previously-blocked ad re-appears.
* Toggling "Firewall" off restores internet access to apps the user previously blocked.
* All 6 toggles survive `Fragment` recreation (process death + restore).
* Verified in a release build, not just debug — per `feedback_ship_tested_code`.
