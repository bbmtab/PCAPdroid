# Protection Settings Plan (Global Modules)

> **Tujuan:** Membuat UI untuk Master Switch dari modul-modul perlindungan (AdBlocking, Tracking, Annoyance, DNS, Firewall, Security) dan mengintegrasikannya dengan engine backend.
> **Referensi UI:** AdGuard "Protection" screen.
> **Referensi Backend:** `FilterEngine` (untuk rules), DNS Resolver (untuk DNS), dan Connection State (untuk Firewall).
> **Status:** ⬜ NOT STARTED

---

## 1. Penyimpanan Data (SharedPreferences / DataStore)
Gunakan *SharedPreferences* karena ini merupakan status toggle global.

**Key yang dibutuhkan (Boolean, Default = true):**
*   `pref_protect_adblock` (Ad blocking)
*   `pref_protect_tracking` (Tracking protection)
*   `pref_protect_annoyance` (Annoyance blocking)
*   `pref_protect_dns` (DNS protection)
*   `pref_protect_firewall` (Firewall)
*   `pref_protect_security` (Browsing Security)

---

## 2. Struktur UI (Mengacu pada Screenshot)

### Screen: Protection Screen (`ProtectionFragment.kt` atau Activity)
Berisi daftar *Toggle Switches* dengan *Title* dan *Subtitle* statis:

1.  **[Toggle] Ad blocking**
    *   *Subtitle:* Blocks unwanted ads like banners, videos, and popups.
2.  **[Toggle] Tracking protection**
    *   *Subtitle:* Protects your online activity and personal data from web trackers.
3.  **[Toggle] Annoyance blocking**
    *   *Subtitle:* Blocks elements such as cookie notices or in-page pop-ups.
4.  **[Toggle] DNS protection**
    *   *Subtitle:* Protects you from ads, trackers, and threats at the DNS level.
5.  **[Toggle] Firewall**
    *   *Subtitle:* Allows to control app access to the Internet.
6.  **[Toggle] Browsing Security**
    *   *Subtitle:* Blocks requests to malicious and phishing websites.

*Catatan UI:* Jika sebuah toggle dimatikan, icon di sebelahnya bisa diubah menjadi warna abu-abu (disabled state) atau disilang untuk memberikan *feedback* visual yang jelas.

---

## 3. Integrasi Backend (Core Logic)

Berbeda dengan App Management yang beroperasi di level *UID*, modul Protection beroperasi di level **Engine/Kategori**.

**A. Integrasi Content Filtering (Ad, Tracking, Annoyance, Security)**
Ada dua cara mengimplementasikannya, namun cara terbaik adalah mengaitkannya dengan `FilterListManager` yang sudah kita rancang sebelumnya (di `filter_list_plan.md`).

*Logic saat memanggil `FilterListManager.mergeEnabledLists()`:*
```kotlin
// Saat melakukan merge file adblock_rules.txt, periksa master switch ini
val prefs = SharedPreferences(...)

val rulesListToMerge = filterListDao.getAllEnabled().filter { list ->
    when (list.category) {
        "ad_blocking" -> prefs.getBoolean("pref_protect_adblock", true)
        "privacy" -> prefs.getBoolean("pref_protect_tracking", true)
        "annoyance" -> prefs.getBoolean("pref_protect_annoyance", true)
        "security" -> prefs.getBoolean("pref_protect_security", true)
        else -> true // Custom filters selalu di-merge
    }
}
// Tulis ke adblock_rules.txt lalu reload FilterEngine
```
*Efek:* Jika user mematikan toggle "Ad blocking", list seperti *EasyList* sementara tidak akan dimuat ke memori, menghemat RAM, dan engine langsung tidak memblokir iklan.

**B. Integrasi DNS Protection**
Modifikasi pada DNS Engine (misal di PCAPdroid DNS interceptor atau BraveDNS core):
```kotlin
if (!prefs.getBoolean("pref_protect_dns", true)) {
    // Abaikan DNS rule matching, teruskan (forward) semua query DNS ke upstream resolver
    return DnsResponse.forward()
}
```

**C. Integrasi Firewall**
Modifikasi pada `BypassManager` atau Connection Handler yang kita buat di `app_management_plan.md`:
```kotlin
val isFirewallEnabled = prefs.getBoolean("pref_protect_firewall", true)

// Bypass semua aturan blokir internet per-app jika master firewall dimatikan
if (isFirewallEnabled) {
    AppConfigEntity config = Database.getAppConfig(uid)
    if (isMobileData && !config.allowMobile) connection.drop()
    if (isWifi && !config.allowWifi) connection.drop()
}
```

---

## 4. Urutan Implementasi (Checklist)

### Phase K: UI & Settings
- [ ] K1 — Buat `ProtectionFragment` beserta layout-nya (6 baris toggle sesuai screenshot).
- [ ] K2 — Hubungkan state toggle dengan `SharedPreferences`.

### Phase L: Backend Hook & Enforcement
- [ ] L1 — Update logika `FilterListManager.mergeEnabledLists()` agar membaca state Protection (Kategori diabaikan jika toggle off).
- [ ] L2 — Berikan *listener* pada toggle: Jika Ad/Tracking/Annoyance/Security diubah, pemicu `mergeEnabledLists()` agar memori di-update secara *real-time*.
- [ ] L3 — Pasang bypass condition di DNS handler jika `pref_protect_dns` dimatikan.
- [ ] L4 — Pasang bypass condition di pengecekan Network (Wi-Fi/Cellular) jika `pref_protect_firewall` dimatikan.
```

***

### Catatan Penting
Pada **Phase L2**, setiap kali user mematikan/menghidupkan *Ad blocking* atau *Tracking protection*, aplikasi harus menjalankan ulang (re-merge) filter list di *background* dan memuat ulang `FilterEngine`. 
* Tampilkan *progress indicator* kecil di UI saat *toggle* ditekan agar user tahu engine sedang memproses ribuan rules.