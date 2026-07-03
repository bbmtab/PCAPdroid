# SKILL: pcap HTTPS Inspection Modifier

## KONTEKS PROYEK
- Base: pcap (Kotlin, Android)
  Repo: https://github.com/celzero/rethink-app
- Tujuan: Tambahkan local MITM proxy layer untuk HTTP/HTTPS filtering
- Target: Non-root device, user install CA sendiri secara sadar
- Bahasa: Kotlin, Android API 21+ (minSdk 23)

## STACK YANG DIPAKAI
- TLS/Cert generation : BouncyCastle (bcprov-jdk18on:1.78, bcpkix-jdk18on:1.78)
- Proxy engine       : Custom implementation (LocalHttpsProxy.kt)
- Filter engine      : Format AdGuard/uBlock filterlist (##, ##^, #?#, #%#, ||, @@, $csp=)
- Routing            : Android VpnService TUN interface (existing)

## BACA DULU SEBELUM MULAI
File kritis di codebase pcap yang harus dipahami:
1. `app/src/main/java/com/celzero/bravedns/service/BrainDNSService.kt`
   → Entry point VPN service
2. `app/src/main/java/com/celzero/bravedns/core/proxy/LocalHttpsProxy.kt`
   → MITM proxy server (port 8443) — **IMPLEMENTED**
3. `app/src/main/java/com/celzero/bravedns/core/ca/CertificateAuthority.kt`
   → CA cert generation & management — **IMPLEMENTED**
4. `app/src/main/java/com/celzero/bravedns/core/filter/FilterEngine.kt`
   → AdGuard filter parser & matcher — **IMPLEMENTED**
5. `app/src/main/java/com/celzero/bravedns/core/filter/CosmeticFilter.kt`
   → CSS cosmetic injection — **IMPLEMENTED**
6. `app/src/main/java/com/celzero/bravedns/core/filter/ScriptletFilter.kt`
   → Scriptlet injection (#%#) — **IMPLEMENTED (Phase 3)**
7. `app/src/main/java/com/celzero/bravedns/core/filter/ProceduralFilter.kt`
   → Procedural cosmetic filters (#?#) — **IMPLEMENTED (Phase 2)**
8. `app/src/main/java/com/celzero/bravedns/core/filter/HtmlFilter.kt`
   → HTML element removal (##^) — **IMPLEMENTED (Phase 4)**
9. `app/src/main/java/com/celzero/bravedns/core/filter/CspInjector.kt`
   → CSP header injection ($csp=) — **IMPLEMENTED (Phase 1)**
10. `app/src/main/java/com/celzero/bravedns/ui/activity/CertificateSetupActivity.kt`
    → CA install UI flow — **IMPLEMENTED**

## FASE KERJA — STATUS (✅ = DONE)

### FASE 1 — Pemetaan arsitektur (baca saja, jangan edit)
✅ Trace alur packet dari TUN interface sampai keluar
✅ Identifikasi di mana TCP connection dibuat
✅ Temukan titik injeksi yang tidak merusak DNS + firewall existing

### FASE 2 — Certificate Authority module
✅ Buat file baru: `core/ca/CertificateAuthority.kt`

Tugas:
- Generate Root CA (RSA 2048, self-signed, validity 10 tahun)
- Simpan CA key + cert di Android Keystore (bukan SharedPrefs)
- Expose fungsi: `generateLeafCert(hostname: String): X509Certificate`
  → Cert per-domain, ditandatangani oleh Root CA
  → Cache hasil di memory (LRU, max 500 entry)
- Expose fungsi: `exportCaCert(): ByteArray`
  → Untuk flow install ke user

Constraint:
- JANGAN simpan private key di file/SharedPrefs
- Gunakan Android Keystore API untuk penyimpanan key
- Leaf cert validity: 365 hari

### FASE 3 — Local MITM Proxy Server
✅ Buat file baru: `core/proxy/LocalHttpsProxy.kt`

Tugas:
- Buka ServerSocket di localhost:8443 (configurable)
- Handle HTTP CONNECT tunnel:
  1. Parse CONNECT hostname:port
  2. Buat SSLSocket ke server asli (upstream)
  3. Generate leaf cert untuk hostname
  4. Buka SSLSocket ke client pakai leaf cert
  5. Pipe traffic dua arah
- Handle plain HTTP (port 8080) untuk non-TLS
- Expose callback: `onRequest(method, url, headers): FilterDecision`
  → ALLOW / BLOCK / MODIFY

Constraint:
- Proxy harus non-blocking (coroutines, bukan thread-per-connection)
- Handle koneksi simultan minimum 50
- Timeout: connect 10s, read 30s
- Jika upstream TLS error (cert pinning) → fallback ke tunnel passthrough
  (jangan crash, cukup bypass filtering untuk koneksi itu)

### FASE 4 — Filter Engine
✅ Buat file baru: `core/filter/FilterEngine.kt`

Tugas:
- Parse AdGuard filter list format:
  - `||example.com^` → block domain
  - `||cdn.example.com/ads/*` → block URL pattern
  - `@@||example.com^` → whitelist
- Compile rules ke Trie/Regex hybrid untuk performa
- Expose: `check(url: String, appUid: Int): FilterDecision`

### FASE 5 — Routing Integration
✅ Edit (HATI-HATI): `BrainDNSService.kt`

Tugas:
- Setelah TUN interface aktif, tambahkan iptables rule (root)
  ATAU modifikasi packet routing untuk redirect port 80/443 ke proxy
- Untuk non-root: gunakan VpnService.Builder untuk set HTTP proxy
  → `builder.setHttpProxy(ProxyInfo.buildDirectProxy("localhost", 8443))`
  → Ini hanya bekerja untuk app yang respect system proxy

Constraint:
- Jangan ubah DNS routing path yang sudah ada
- Jangan ubah firewall per-app yang sudah ada
- Tambahkan feature flag `HTTPS_INSPECTION_ENABLED` (default: false)
  → Fitur ini opt-in, bukan default on

### FASE 6 — UI: CA Install Flow
✅ Buat screen baru: `ui/CertificateSetupActivity.kt`

Tugas:
- Tampilkan penjelasan apa yang akan dilakukan
- Tombol "Generate & Install CA Certificate"
- Panggil `CertificateAuthority.exportCaCert()`
- Launch Android intent untuk install cert:
```kotlin
val intent = KeyChain.createInstallIntent()
intent.putExtra(KeyChain.EXTRA_CERTIFICATE, certBytes)
intent.putExtra(KeyChain.EXTRA_NAME, "pcap Custom CA")
startActivity(intent)
```
- Setelah install: tampilkan checklist verifikasi

### FASE 7 — Cosmetic Filter Engine
✅ Buat file baru: `core/filter/CosmeticFilter.kt`

Tugas:
- Parse AdGuard cosmetic rules:
  - `example.com##.ad-banner`     → hide selector .ad-banner di domain itu
  - `##div[id^="google_ad"]`      → global, semua domain
  - `example.com#@#.ad-banner`    → whitelist cosmetic
- Generate CSS string dari rules yang match per domain
- Expose: `getCssForDomain(domain: String): String?`
  → Return null jika tidak ada rules → skip injection

Integrasi ke LocalHttpsProxy.kt (Fase 3):
- Di handler response, sebelum forward ke client:
  1. Cek header Content-Type → hanya proses "text/html"
  2. Panggil CosmeticFilter.getCssForDomain(hostname)
  3. Jika ada CSS → inject sebelum </head>:
```kotlin
val injection = "<style>${css}</style>"
body = body.replace("</head>", "$injection</head>")
```
  4. Update Content-Length header setelah modifikasi body
  5. Forward body yang sudah dimodifikasi

Constraint:
- JANGAN modifikasi response selain text/html
- JANGAN buffer seluruh response ke memory untuk file besar
  → Gunakan streaming, inject hanya ketika </head> ditemukan
- Encoding-aware: handle gzip/deflate Content-Encoding
  (decompress dulu, inject, compress lagi)

### FASE 8 — Procedural Cosmetic Filters (#?#) — **PHASE 2 COMPLETED** ✅
✅ File: `core/filter/ProceduralFilter.kt`, `FilterEngine.kt`, `LocalHttpsProxy.kt`
✅ Tests: `ProceduralFilterTest.kt` — 20/20 passed

Syntax:
```
example.com#?#.banner:has(img[src*="ads"])
example.com#?#div:xpath(//div[@data-ad])
##:upward(.ad-wrapper)
##:matches-css(display: block)
```

Operators: `:has()`, `:upward()`, `:xpath()`, `:matches-css()`, `:nth-ancestor()`, `:remove()`

### FASE 9 — Scriptlet Injection (#%#) — **PHASE 3 COMPLETED** ✅
✅ File: `core/filter/ScriptletFilter.kt`, `assets/scriptlets.js` (281 lines), `FilterEngine.kt`, `LocalHttpsProxy.kt`
✅ Tests: `ScriptletFilterTest.kt` — 23/23 passed

Syntax:
```
example.com#%#//scriptlet('abort-on-property-read', 'adsbygoogle')
example.com#%#//scriptlet('set-constant', 'canRunAds', 'true')
```

Scriptlets tersedia: `abort-on-property-read`, `abort-on-property-write`, `set-constant`, `remove-attr`, `remove-class`, `no-fetch-if`, `no-xhr-if`, `json-prune`

### FASE 10 — CSP Injection ($csp=) — **PHASE 1 COMPLETED** ✅
✅ File: `core/filter/CspInjector.kt`, `FilterEngine.kt`, `LocalHttpsProxy.kt`
✅ Tests: `CspInjectorTest.kt` — 13/13 passed

Syntax:
```
||example.com^$csp=script-src 'self' 'unsafe-inline'
$csp=connect-src 'self',third-party
```

### FASE 11 — HTML Filtering (##^) — **PHASE 4 COMPLETED** ✅
✅ File: `core/filter/HtmlFilter.kt`, `FilterEngine.kt`, `LocalHttpsProxy.kt`
✅ Tests: `HtmlFilterTest.kt` — 24/24 passed
✅ Dependency: `org.jsoup:jsoup:1.19.1`

Syntax:
```
example.com##^script[src*="analytics"]
##^.advertisement
example.com##^div[id="ad-container"]
```

### Pipeline Injeksi Urutan (LocalHttpsProxy.kt):
```
1. Scriptlet Injection      (#%#) — running paling awal
2. Procedural Filters       (#?#) — CSS dengan operator
3. CSS Injection            (##)  — cosmetic hiding standard
4. HTML Filter              (##^) — remove element dari body
5. CSP Header Merge         ($csp=) — response header
```

## ATURAN UNTUK AGEN

1. Setiap fase selesai → jalankan unit test sebelum lanjut ke fase berikutnya
2. Jangan modifikasi file di luar scope yang disebutkan
3. Setiap fungsi baru harus ada KDoc comment minimal 3 baris
4. Kalau menemukan ambiguitas di codebase → tanyakan, jangan asumsi
5. Feature flag `HTTPS_INSPECTION_ENABLED` harus dicek di SEMUA titik kode baru
6. Semua network operation harus lewat coroutines (Dispatchers.IO)

## DEFINISI SELESAI (Definition of Done)
- [x] CA cert bisa di-generate dan di-export
- [x] User bisa install cert via UI
- [x] HTTPS traffic dari Chrome bisa diinspeksi (test case dasar)
- [x] App dengan cert pinning (test: BCA Mobile) → bypass gracefully, tidak crash
- [x] DNS filtering existing masih berfungsi
- [x] Per-app firewall existing masih berfungsi
- [x] Fitur bisa dimatikan via toggle tanpa restart app

## BUILD
Compile semua di GitHub Actions menjadi APK dengan suffix "plus"

---

## VERIFIKASI TEST SUITE (ALL GREEN)
```bash
./gradlew :app:testFdroidFullDebugUnitTest --tests "com.celzero.bravedns.core.filter.*" --no-configuration-cache
# BUILD SUCCESSFUL
```

| Test Class | Tests | Status |
|------------|-------|--------|
| CspInjectorTest | 13 | ✅ Pass |
| ProceduralFilterTest | 20 | ✅ Pass |
| ScriptletFilterTest | 23 | ✅ Pass |
| HtmlFilterTest | 24 | ✅ Pass |
| FilterEngineTest | ~30 | ✅ Pass |

---

## RECENT FIXES (June 2025)

### Windscribe Login UI — Spacing & Layout (FIXED)

### CertificateAuthority Unit Tests — BouncyCastle Provider Registration (FIXED)
**File Modified:**
- `app/src/main/java/com/celzero/bravedns/core/ca/CertificateAuthority.kt`

**Issue:** Unit tests on JVM (non-Android) failed with `NoSuchProviderException: no such provider: BC` because BouncyCastle security provider wasn't auto-registered outside Android environment.

**Fix:**
1. Added `companion object` with `ensureProviderRegistered()` method that registers `BouncyCastleProvider` at security position 1 if not already present
2. Call `ensureProviderRegistered()` at start of `initializeCA()`
3. All 7 CertificateAuthority tests now pass in JVM unit test environment

**Test Results:** 7/7 tests passing, plus 97 filter tests = 104 total passing
**Files Modified:**
- `app/src/main/res/layout/activity_windscribe_login.xml`
- `app/src/main/res/layout/rpn_country_config_list_item.xml`

**Changes:**
1. **`activity_windscribe_login.xml`** — Fixed RecyclerView overlap with logout button:
   - Changed `ll_server_group` from LinearLayout → ConstraintLayout
   - RecyclerView: `layout_height="0dp"` + `constraintTop_toBottomOf=search` + `constraintBottom_toTopOf=btn_logout`
   - Logout button: `layout_marginTop="16dp"` (was 24dp) + constrained to bottom
   - Search input gets explicit ID + constraints

2. **`rpn_country_config_list_item.xml`** — Fixed missing resource & modern styling:
   - Fixed `cardBackgroundColor="?attr/cardBgColor"` → `"?attr/chipColorBgNormal"` (attr exists in styles)
   - Added `cardElevation="2dp"`, `cardCornerRadius="16dp"` for Material 3 card look
   - Margins: horizontal 16dp, vertical 8dp for proper list spacing
   - Inner padding 16dp for content breathing room

### Build Resource Fix
- **Issue**: `attr/cardBgColor not found` causing resource linking failure
- **Fix**: Use existing theme attribute `chipColorBgNormal` defined in `attrs.xml` and mapped in `styles.xml`

---

## MTU DISPLAY — INFORMATIONAL ONLY
- "MTU=false/1500" appears in HomeScreenSettingBottomSheet via `hsf_uptime` string
- **NOT a warning/popup** — just connection stats display
- No dialog, no alert, no action required