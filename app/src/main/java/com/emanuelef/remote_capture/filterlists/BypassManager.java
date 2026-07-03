/*
 * This file is part of ADBye (PCAPdroid).
 *
 * Licensed under GPLv3.
 */
package com.emanuelef.remote_capture.filterlists;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.WorkerThread;
import androidx.preference.PreferenceManager;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Resource Protection (Phase 2).
 *
 * Central bypass lists that ALWAYS win over user/group rules:
 * 1. App UID allowlist — Play Store, Google Play Services, Google Services Framework, IMS.
 * 2. Domain allowlist — video CDNs, Google push endpoints, mtalk, Android provisioning.
 * 3. FCM ports — 5228, 5229, 5230 (FCM push).
 * 4. Dynamic flow threshold — skip inspection for any flow > 5 MB (video), > 20 MB (large download).
 *
 * This is a singleton; state lives in-memory and is (re)loaded from SharedPreferences on boot.
 * All mutating methods are @WorkerThread and synchronous.
 */
public final class BypassManager {

    /** Pref key for the dynamic flow threshold (MB). Default = 5 MB. */
    private static final String PREF_BYPASS_DYNAMIC_THRESHOLD_MB = "adbye_bypass_dynamic_threshold_mb";
    /** Pref key for the large-download threshold (MB). Default = 20 MB. */
    private static final String PREF_BYPASS_LARGE_DOWNLOAD_THRESHOLD_MB = "adbye_bypass_large_download_threshold_mb";

    /** Bypass if Content-Length exceeds this many bytes (or observed bytes > threshold). */
    private static final long DEFAULT_DYNAMIC_THRESHOLD_BYTES = 5L * 1024 * 1024; // 5 MB
    private static final long DEFAULT_LARGE_DOWNLOAD_THRESHOLD_BYTES = 20L * 1024 * 1024; // 20 MB

    private final SharedPreferences prefs;
    private final Set<String> uidAllowlist = new HashSet<>();
    private final Set<String> domainAllowlist = new HashSet<>();
    private final Set<Integer> portAllowlist = new HashSet<>();
    private long dynamicThresholdBytes = DEFAULT_DYNAMIC_THRESHOLD_BYTES;
    private long largeDownloadThresholdBytes = DEFAULT_LARGE_DOWNLOAD_THRESHOLD_BYTES;

    // Singleton holder (lazy, thread-safe by classload)
    private static volatile BypassManager instance;

    private BypassManager(@NonNull Context ctx) {
        prefs = PreferenceManager.getDefaultSharedPreferences(ctx);
        load();
    }

    /** Get the process-global singleton. Must be called from any thread after Application.onCreate(). */
    public static BypassManager get(@NonNull Context ctx) {
        // Double-checked locking is safe because instance is volatile.
        BypassManager inst = instance;
        if (inst == null) {
            synchronized (BypassManager.class) {
                inst = instance;
                if (inst == null) {
                    inst = new BypassManager(ctx.getApplicationContext());
                    instance = inst;
                }
            }
        }
        return inst;
    }

    /** Reset singleton for tests. */
    public static void resetForTests() {
        instance = null;
    }

    // ------------------------------------------------------------------------
    // Public query API (read-only, thread-safe for concurrent reads)
    // ------------------------------------------------------------------------

    /** True if the UID (package name or integer UID string) is exempt from all filtering. */
    public boolean isUidBypassed(@NonNull String uidOrPkg) {
        return uidAllowlist.contains(uidOrPkg);
    }

    /** True if the SNI / host is in the hardcoded domain allowlist. */
    public boolean isDomainBypassed(@NonNull String host) {
        if (host == null) return false;
        String h = host.toLowerCase(java.util.Locale.ROOT);
        // Exact or suffix match (e.g., "googlevideo.com" bypasses "r1---sn-...googlevideo.com")
        for (String allowed : domainAllowlist) {
            if (h.equals(allowed) || h.endsWith("." + allowed)) {
                return true;
            }
        }
        return false;
    }

    /** True if the destination port is in the FCM port allowlist. */
    public boolean isPortBypassed(int dstPort) {
        return portAllowlist.contains(dstPort);
    }

    /** True if the flow has exceeded the dynamic byte threshold (video/large download heuristic). */
    public boolean isFlowBypassed(long bytesTransferred) {
        return bytesTransferred >= dynamicThresholdBytes;
    }

    /** True if the flow has exceeded the large-download threshold (very large file). */
    public boolean isLargeDownloadBypassed(long bytesTransferred) {
        return bytesTransferred >= largeDownloadThresholdBytes;
    }

    /** Get the current dynamic threshold in bytes. */
    public long getDynamicThresholdBytes() { return dynamicThresholdBytes; }

    /** Get the current large-download threshold in bytes. */
    public long getLargeDownloadThresholdBytes() { return largeDownloadThresholdBytes; }

    // ------------------------------------------------------------------------
    // Configuration (WorkerThread) — called from Settings/Preferences UI
    // ------------------------------------------------------------------------

    /** Set dynamic threshold (MB). Persists to SharedPreferences. */
    @WorkerThread
    public void setDynamicThresholdMb(int mb) {
        long bytes = Math.max(1, mb) * 1024L * 1024L;
        dynamicThresholdBytes = bytes;
        prefs.edit().putInt(PREF_BYPASS_DYNAMIC_THRESHOLD_MB, mb).apply();
    }

    /** Set large-download threshold (MB). Persists to SharedPreferences. */
    @WorkerThread
    public void setLargeDownloadThresholdMb(int mb) {
        long bytes = Math.max(1, mb) * 1024L * 1024L;
        largeDownloadThresholdBytes = bytes;
        prefs.edit().putInt(PREF_BYPASS_LARGE_DOWNLOAD_THRESHOLD_MB, mb).apply();
    }

    /** Add a UID/package to the allowlist (e.g., user adds custom bypass). Persists. */
    @WorkerThread
    public void addUidAllowlist(@NonNull String uidOrPkg) {
        uidAllowlist.add(uidOrPkg);
        persistUidAllowlist();
    }

    /** Remove a UID/package from the allowlist. Persists. */
    @WorkerThread
    public void removeUidAllowlist(@NonNull String uidOrPkg) {
        uidAllowlist.remove(uidOrPkg);
        persistUidAllowlist();
    }

    /** Get immutable snapshot of UID allowlist. */
    @NonNull public List<String> getUidAllowlist() {
        return Collections.unmodifiableList(new ArrayList<>(uidAllowlist));
    }

    /** Get immutable snapshot of domain allowlist. */
    @NonNull public List<String> getDomainAllowlist() {
        return Collections.unmodifiableList(new ArrayList<>(domainAllowlist));
    }

    /** Get immutable snapshot of port allowlist. */
    @NonNull public List<Integer> getPortAllowlist() {
        return Collections.unmodifiableList(new ArrayList<>(portAllowlist));
    }

    // ------------------------------------------------------------------------
    // Private init / persistence
    // ------------------------------------------------------------------------

    private void load() {
        // ---- HARDCODED DEFAULTS (must win over user rules) ----

        // UID allowlist: package names / UIDs that must never be filtered.
        // com.android.vending = Play Store
        // com.google.android.gms = Google Play Services (FCM, auth, etc.)
        // com.google.android.gsf = Google Services Framework (legacy push)
        // com.android.ims = IMS (VoLTE/WiFi calling)
        uidAllowlist.add("com.android.vending");
        uidAllowlist.add("com.google.android.gms");
        uidAllowlist.add("com.google.android.gsf");
        uidAllowlist.add("com.android.ims");

        // Domain allowlist: SNI patterns that must never be blocked.
        // Matches FilterListManager.HARDCODED_SYSTEM_WHITELIST but as domain suffixes.
        domainAllowlist.add("googlevideo.com");      // YouTube video CDN
        domainAllowlist.add("nflxvideo.net");        // Netflix video CDN
        domainAllowlist.add("fbcdn.net");            // Facebook/Meta CDN
        domainAllowlist.add("cdninstagram.com");     // Instagram CDN
        domainAllowlist.add("ttvnw.net");            // Twitch video CDN
        domainAllowlist.add("tiktokcdn.com");        // TikTok video CDN
        domainAllowlist.add("play.googleapis.com");  // Play services APIs
        domainAllowlist.add("dl.google.com");        // Google downloads (OTA, Chrome, etc.)
        domainAllowlist.add("mtalk.google.com");     // FCM/messaging
        domainAllowlist.add("android.clients.google.com"); // Android provisioning/checkin

        // Port allowlist: FCM push ports (never block).
        portAllowlist.add(5228);
        portAllowlist.add(5229);
        portAllowlist.add(5230);

        // ---- USER-OVERRIDABLE SETTINGS (from SharedPreferences) ----
        int dynamicMb = prefs.getInt(PREF_BYPASS_DYNAMIC_THRESHOLD_MB, (int) (DEFAULT_DYNAMIC_THRESHOLD_BYTES / 1024 / 1024));
        dynamicThresholdBytes = (long) dynamicMb * 1024 * 1024;

        int largeMb = prefs.getInt(PREF_BYPASS_LARGE_DOWNLOAD_THRESHOLD_MB, (int) (DEFAULT_LARGE_DOWNLOAD_THRESHOLD_BYTES / 1024 / 1024));
        largeDownloadThresholdBytes = (long) largeMb * 1024 * 1024;

        // Optional: load user-added UID allowlist from prefs (JSON set)
        String uidJson = prefs.getString("adbye_bypass_uid_allowlist", null);
        if (uidJson != null && !uidJson.isEmpty()) {
            try {
                // Simple CSV for now; could be JSON array
                for (String s : uidJson.split(",")) {
                    String t = s.trim();
                    if (!t.isEmpty()) uidAllowlist.add(t);
                }
            } catch (Exception ignored) {}
        }
    }

    private void persistUidAllowlist() {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (String s : uidAllowlist) {
            if (!first) sb.append(",");
            sb.append(s);
            first = false;
        }
        prefs.edit().putString("adbye_bypass_uid_allowlist", sb.toString()).apply();
    }

    // ------------------------------------------------------------------------
    // Rule-file export: produces a domain/port blocklist fragment consumed by
    // FilterListManager.mergeEnabledLists() as an extra prepend section.
    // ------------------------------------------------------------------------

    /**
     * Write a rule fragment to {@code outFile} containing the hardcoded bypass rules
     * in AdGuard exception-rule format. Called by FilterListManager before merging enabled lists.
     *
     * Format:
     *   ! ADBye Resource Protection — UID bypass (informational; enforced in JNI)
     *   ! u:com.android.vending
     *   ...
     *   ! ADBye Resource Protection — Domain bypass (exception rules)
     *   @@||googlevideo.com^
     *   ...
     *   ! ADBye Resource Protection — Port bypass (informational; enforced in JNI)
     *   ! p:5228
     *   ...
     */
    @WorkerThread
    public void writeBypassRuleFragment(@NonNull File outFile) throws IOException {
        try (FileWriter w = new FileWriter(outFile, false)) {
            w.write("! ADBye Resource Protection — UID bypass (informational; enforced in JNI)\n");
            for (String uid : uidAllowlist) {
                w.write("! u:" + uid + "\n");
            }
            w.write("\n! ADBye Resource Protection — Domain bypass (exception rules)\n");
            for (String d : domainAllowlist) {
                w.write("@@||" + d + "^\n");
            }
            w.write("\n! ADBye Resource Protection — Port bypass (informational; enforced in JNI)\n");
            for (int p : portAllowlist) {
                w.write("! p:" + p + "\n");
            }
            w.write("\n");
        }
    }

    /** Convenience to get the fragment as a string (for testing). */
    @WorkerThread
    public String getBypassRuleFragment() throws IOException {
        File tmp = File.createTempFile("bypass_", ".txt");
        tmp.deleteOnExit();
        writeBypassRuleFragment(tmp);
        return new String(Files.readAllBytes(tmp.toPath()), StandardCharsets.UTF_8);
    }
}