# App Management & Firewall Plan (Per-App Config)

> **Tujuan:** Membuat UI dan backend untuk manajemen trafik per-aplikasi (App Management). Fitur ini mengontrol apakah suatu aplikasi di-route ke VPN, apakah difilter (Adblock), apakah di-intercept (HTTPS/MITM), dan aturan akses internetnya (Firewall).
> **Referensi UI:** AdGuard "App management" list & detail screen.
> **Referensi Backend:** PCAPdroid `VpnService` split tunneling & `BypassManager`.
> **Status:** ⬜ NOT STARTED

---

## 1. Arsitektur Database (Room)

Kita butuh tabel untuk menyimpan konfigurasi user per-aplikasi (atau per-UID, karena beberapa aplikasi Android berbagi UID yang sama, misal: Play Services & Services Framework).

```kotlin
@Entity(tableName = "app_management")
data class AppConfigEntity(
    @PrimaryKey val uid: Int,
    val packageNames: String,       // Comma-separated jika ada multiple apps di 1 UID
    val appName: String,            // Nama representatif
    
    // Toggles
    val routeThroughVpn: Boolean = true,   // Route traffic through Adblock/VPN
    val filterTraffic: Boolean = true,     // Filter unencrypted/DNS traffic
    val filterHttps: Boolean = false,      // Filter HTTPS (MITM). Default OFF untuk app sistem
    val useProxy: Boolean = false,         // Upstream proxy routing
    
    // Firewall Rules
    val allowWifi: Boolean = true,
    val allowMobile: Boolean = true,
    val allowScreenOff: Boolean = true
)
```

---

## 2. Struktur UI (Mengacu pada Screenshot)

### Screen 1: App List Screen (`AppManagementActivity.kt`)
*   **Search Bar:** Filter berdasarkan nama aplikasi.
*   **List View (RecyclerView):** Menampilkan daftar aplikasi.
    *   *Perhatian Khusus (Shared UID):* Android mengelompokkan beberapa aplikasi dalam 1 UID (contoh: "Google Play services, Google Services Framework"). Sistem kita harus melakukan grouping by UID, bukan hanya by Package Name.
*   **Icon & Subtitle:** Menampilkan Icon aplikasi, Nama, dan jumlah aplikasi di dalam UID tersebut (misal: "2 apps").

### Screen 2: App Detail Screen (`AppDetailActivity.kt`)
Berisi toggle switches untuk mengatur properti `AppConfigEntity`:

1.  **[Toggle] Route traffic through VPN**
    *   *Backend:* Mempengaruhi `VpnService.Builder().addAllowedApplication()` atau `addDisallowedApplication()`. Jika dimatikan, trafik app ini lewat jalur koneksi normal (bypass VPN OS-level). VPN harus di-restart (seamless) saat ini diubah.
2.  **[Toggle] Filter traffic**
    *   *Backend:* Trafik masuk ke VPN, tapi PCAPdroid melewatinya dari `FilterEngine` (DNS/HTTP Adblock). Hanya mencatat statistik.
3.  **[Toggle] Filter HTTPS traffic**
    *   *Backend:* Terhubung ke `BypassManager.kt`. Jika dimatikan, koneksi port 443 dari UID ini otomatis di `TCP Splice` (di-bypass dari proses MITM/SSL Decryption).
    *   *UI Info:* Tampilkan warning kuning *"App may malfunction on non-rooted devices when enabled"* (seperti AdGuard) karena HTTPS filtering butuh user certificate.
4.  **[Toggle] Route app through proxy**
    *   *Backend:* Menggunakan fitur upstream proxy PCAPdroid untuk me-route trafik UID ini ke SOCKS5/HTTP proxy lain.
5.  **[Menu] App firewall settings**
    *   *Backend:* Masuk ke sub-menu untuk memutus koneksi (Drop packets) jika UI tidak sesuai kondisi (Misal: Toggle "Mobile Data" dimatikan, maka jika interface = CELLULAR, packet dari UID ini langsung di-drop).

---

## 3. Integrasi Backend (Core Logic)

**A. Fase Split Tunneling (VPN Init)**
Saat `BraveVPNService` start, baca semua UID yang `routeThroughVpn = false`. Masukkan daftar paket tersebut ke `builder.addDisallowedApplication(packageName)`.

**B. Fase Interception (PCAPdroid Flow)**
Modifikasi pipeline koneksi PCAPdroid:
```java
// Di dalam State Machine / Connection Handler PCAPdroid
int uid = connection.getUid();
AppConfigEntity config = Database.getAppConfig(uid);

// 1. Firewall Check
if (isMobileData && !config.allowMobile) {
    connection.drop(); return;
}

// 2. HTTP/DNS Filtering Check
if (!config.filterTraffic) {
    connection.setBypassAdblock(true);
}

// 3. HTTPS / MITM Check
if (!config.filterHttps && connection.getPort() == 443) {
    connection.stopIntercept(); // Fallback ke raw TCP Splice
}
```

---

## 4. Urutan Implementasi (Checklist)

### Phase F: App Management Core & Database
- [ ] F1 — Buat `AppConfigEntity`, DAO, dan Repository (termasuk prepopulate data saat first run membaca `PackageManager`).
- [ ] F2 — Buat logic pengelompokan (Grouping) Package Names berdasarkan `Shared UID`.

### Phase G: User Interface
- [ ] G1 — Buat layout `activity_app_management.xml` dan `AppManagementActivity` (RecyclerView + Search).
- [ ] G2 — Buat layout `activity_app_detail.xml` (Toggles persis seperti screenshot).
- [ ] G3 — Buat `AppDetailActivity` untuk mengikat UI dengan update ke database.

### Phase H: Enforcement / Engine Hook
- [ ] H1 — Hubungkan `routeThroughVpn` dengan Android `VpnService.Builder` (Split tunneling).
- [ ] H2 — Hubungkan `filterHttps` dengan `BypassManager` (Eksekusi TCP Splicing untuk app yang di-bypass).
- [ ] H3 — Hubungkan Firewall rules (Wi-Fi/Mobile) untuk melakukan packet drop di layer PCAPdroid.
```

***

### Penjelasan Mengapa Desain ini Sangat Solid:

1. **Efisiensi UID Grouping:** Di Android, *firewall* dan VPN rules (seperti blokir internet atau bypass VPN) beroperasi di level UID, bukan level App (Package Name). UI Anda nanti akan sangat akurat jika mengelompokkan App seperti AdGuard (Screenshot 1: "Backup, Mi Mover -> 2 apps").
2. **Sinkronisasi dengan Bypass Logic (Phase E):** Opsi nomor 3 di screenshot Anda (`Filter HTTPS traffic`) adalah tombol manual untuk fitur Auto-Exception yang kita bahas sebelumnya. 
   * Saat pertama kali diinstall, set *default value* `filterHttps = false` untuk *Google Play Services* dan bank apps secara otomatis di database. User bisa melihatnya di UI dan mengubahnya jika mereka mau.