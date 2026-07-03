# Network & Routing Settings Plan

> **Tujuan:** Membuat UI dan integrasi backend untuk pengaturan jaringan global (Master Switch untuk HTTPS, Upstream Proxy, dan Mode Tangkapan/Capture Mode).
> **Referensi UI:** AdGuard "Network" dan "Routing mode" screen.
> **Referensi Backend:** PCAPdroid `CaptureMode` (VPN, Root, Proxy) & PCAPdroid Upstream Proxy settings.
> **Status:** ⬜ NOT STARTED

---

## 1. Penyimpanan Data (SharedPreferences / DataStore)
Karena ini adalah pengaturan global (bukan per-aplikasi), sebaiknya menggunakan `SharedPreferences` atau `DataStore` (bukan Room Database).

**Key yang dibutuhkan:**
*   `pref_global_https_filtering` (Boolean, default: `false`) - Master switch MITM.
*   `pref_global_upstream_proxy` (Boolean, default: `false`) - Master switch untuk menggunakan proxy eksternal.
*   `pref_routing_mode` (String/Enum, default: `VPN`) - Pilihan mode tangkapan trafik.
*   `pref_manual_proxy_port` (Int, default: `8080`) - Port lokal jika menggunakan mode Manual Proxy.

---

## 2. Struktur UI (Mengacu pada Screenshot)

### Screen 1: Network Screen (`NetworkSettingsActivity.kt` atau Fragment)
*   **[Toggle] HTTPS filtering**
    *   *Deskripsi:* Filters traffic sent over the HTTPS protocol.
    *   *Aksi:* Menghidupkan/mematikan root certificate interception (MITM) secara global.
*   **[Toggle] Proxy**
    *   *Deskripsi:* Allows to route your device's traffic through a proxy server.
    *   *Aksi:* Membuka menu/mengaktifkan koneksi ke SOCKS5/HTTP Proxy eksternal (Upstream proxy).
*   **[Menu] Routing mode**
    *   *Deskripsi:* Sets how your traffic will be filtered.
    *   *Subtitle Dinamis:* Menampilkan mode yang sedang aktif (contoh: "Currently used: Local VPN").
    *   *Aksi:* Membuka halaman/dialog *Routing Mode*.

### Screen 2: Routing Mode Screen (`RoutingModeFragment.kt` atau BottomSheet)
Mengatur *Capture Mode* pada OS Android.
*   **[Radio] Local VPN** (Default)
    *   *Backend:* Menggunakan `VpnService` standar Android (Non-Root).
*   **[Radio] Automatic proxy (Root)**
    *   *Backend:* Menggunakan `iptables` / `su` (Root mode di PCAPdroid) untuk me-redirect trafik ke lokal port aplikasi. Tidak memunculkan ikon kunci VPN di status bar.
    *   *UI:* Teks peringatan warna oranye "Requires root access".
*   **[Radio] Manual proxy (Root / Manual WiFi)**
    *   *Backend:* Aplikasi berjalan sebagai HTTP Proxy Server lokal. Trafik ditangkap dengan cara user mengubah pengaturan WiFi secara manual ke `127.0.0.1:8080` atau di-inject via Root.
    *   *Sub-menu:* **Manual proxy port** (Input angka, default 8080).

---

## 3. Integrasi Backend (Core Logic PCAPdroid)

**A. HTTPS Filtering (Master Switch)**
Modifikasi pada `FilterEngine` atau `BypassManager`:
```kotlin
val isGlobalHttpsEnabled = prefs.getBoolean("pref_global_https_filtering", false)

if (!isGlobalHttpsEnabled && connection.port == 443) {
    // Jika master switch mati, SEMUA aplikasi otomatis di TCP Splice (Bypass MITM)
    // Abaikan rule per-aplikasi (AppConfigEntity.filterHttps).
    connection.stopIntercept()
}
```

**B. Proxy (Upstream)**
Modifikasi saat inisialisasi daemon PCAPdroid / `BraveVPNService`:
```kotlin
if (prefs.getBoolean("pref_global_upstream_proxy", false)) {
    // Ambil detail proxy dari database/prefs (IP, Port, Username, Password)
    // Inject argumen ke backend core (misal: SOCKS5 proxy)
    // pcapdroid_core.set_upstream_proxy(...)
}
```

**C. Routing Mode (Capture Mode)**
PCAPdroid Core secara bawaan sudah mendukung mode ini. Kita hanya perlu me-mapping UI ke konfigurasinya saat Start Service:
```kotlin
enum class RoutingMode { VPN, ROOT, PROXY }

val currentMode = prefs.getString("pref_routing_mode", "VPN")
when (currentMode) {
    "VPN" -> startVpnService()
    "ROOT" -> startRootCapture() // Butuh request root permission (su)
    "PROXY" -> startLocalProxyServer(port = prefs.getInt("pref_manual_proxy_port", 8080))
}
```

---

## 4. Urutan Implementasi (Checklist)

### Phase I: UI & Data Storage
- [ ] I1 — Buat kunci konfigurasi di `SharedPreferences` atau `DataStore`.
- [ ] I2 — Buat layout `activity_network_settings.xml`.
- [ ] I3 — Buat layout `fragment_routing_mode.xml` (Bisa berupa Activity, Fragment, atau BottomSheetDialog).
- [ ] I4 — Sinkronisasi teks dinamis di menu *Routing mode* agar sesuai dengan mode yang dipilih.

### Phase J: Backend Hooks
- [ ] J1 — Hook **HTTPS filtering toggle** ke `FilterEngine`/MITM engine (Master bypass jika OFF).
- [ ] J2 — Hook **Proxy toggle** ke sistem upstream proxy PCAPdroid.
- [ ] J3 — Hook **Routing mode** ke logika *startup* `VpnService` / Root (iptables) / Proxy daemon. Jika mode diubah saat service berjalan, lakukan auto-restart daemon.
- [ ] J4 — Tambahkan logic pengecekan Root (Superuser) saat user memilih "Automatic proxy". Jika tidak ada root, tolak/tampilkan pesan error.
```

***

**Catatan Khusus untuk Developer:**
Fitur "Automatic proxy" dan "Manual proxy" ini sangat bergantung pada kapabilitas Root atau Proxy-Server bawaan engine Anda (PCAPdroid). PCAPdroid memang memiliki dukungan ekstensif untuk penangkapan via root (`iptables`/`tc`) dan Proxy mode. Pastikan logic restart *service/daemon* dibuat *smooth* saat user mengganti antar mode ini di pengaturan.