/*
 * This file is part of ADBye (PCAPdroid).
 *
 * Licensed under GPLv3.
 */
package com.adbye.filter.filterlists;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.util.ArrayMap;

import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;
import androidx.preference.PreferenceManager;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * ADBye â€” Filter List Manager (Phase 0 / Phase 1).
 *
 * Holds the catalog of {@link FilterListEntry} entries (predefined + user-added),
 * downloads one or more lists to {@code filesDir/filter_lists/}, merges enabled
 * lists into {@code filesDir/adblock_rules.txt} with a hardcoded system
 * whitelist prepended for Resource Protection.
 *
 * Threading: public mutators that touch disk MUST be invoked from a worker
 * thread. {@link #addOnChangeListener(OnChangeListener)} delivers notifications
 * synchronously to whatever thread the caller mutated from; UI listeners should
 * post to the main thread as needed.
 */
public class FilterListManager {
    public static final String PREF_FILTER_LISTS_STATUS = "adbye_filter_lists_status";
    private static final String PREF_FILTER_LISTS_DISABLED = "adbye_filter_lists_disabled";
    public static final long FILTER_LISTS_UPDATE_MILLIS = 86400L * 1000; // 1 day

    /** Categories used by Protection master toggles (see firewall_blocking_tab.md). */
    public enum Category {
        AD_BLOCKING("ad_blocking"),
        PRIVACY("privacy"),
        SOCIAL("social"),
        ANNOYANCE("annoyance"),
        SECURITY("security"),
        LANGUAGE("language"),
        OTHER("other"),
        CUSTOM("custom");

        public final String key;
        Category(String k) { this.key = k; }
    }

    /** System-wide prepend rules that win over user rules. */
    public static final String HARDCODED_SYSTEM_WHITELIST =
            "! ADBye system whitelist (do not remove)\n" +
            "@@||googlevideo.com^\n" +
            "@@||nflxvideo.net^\n" +
            "@@||fbcdn.net^\n" +
            "@@||cdninstagram.com^\n" +
            "@@||ttvnw.net^\n" +
            "@@||tiktokcdn.com^\n" +
            "@@||play.googleapis.com^\n" +
            "@@||dl.google.com^\n" +
            "@@||mtalk.google.com^\n" +
            "@@||android.clients.google.com^\n";

    public interface OnChangeListener {
        void onFilterListsStateChanged();
    }

    /** Reads the persisted set of fnames the user has explicitly disabled.
     *  Returns a mutable copy -- SharedPreferences.getStringSet()'s returned
     *  instance must never be mutated directly (Android API contract). */
    private java.util.HashSet<String> readDisabledFnames() {
        java.util.Set<String> stored = mPrefs.getStringSet(PREF_FILTER_LISTS_DISABLED, null);
        return (stored != null) ? new java.util.HashSet<>(stored) : new java.util.HashSet<>();
    }

    private void writeDisabledFnames(java.util.HashSet<String> disabled) {
        mPrefs.edit().putStringSet(PREF_FILTER_LISTS_DISABLED, disabled).apply();
    }

    private final List<FilterListEntry> mLists = new ArrayList<>();
    private final ArrayMap<String, FilterListEntry> mListByFname = new ArrayMap<>();
    private final List<OnChangeListener> mListeners = new ArrayList<>();
    private final SharedPreferences mPrefs;
    private final File mFilesDir;
    private final File mListsDir;
    private final File mMergedFile;
    private final Context mContext;

    private boolean mUpdateInProgress;
    private long mLastUpdate;
    private long mLastUpdateMonotonic;

    public FilterListManager(Context ctx) {
        mContext = ctx.getApplicationContext();
        mFilesDir = mContext.getFilesDir();
        mListsDir = new File(mFilesDir, "filter_lists");
        //noinspection ResultOfMethodCallIgnored
        mListsDir.mkdirs();
        mMergedFile = new File(mFilesDir, "adblock_rules.txt");
        mPrefs = PreferenceManager.getDefaultSharedPreferences(mContext);
        mLastUpdate = 0;
        mLastUpdateMonotonic = -FILTER_LISTS_UPDATE_MILLIS;
    }

    /** File path under filesDir/filter_lists/ for a given entry. */
    public File getListFile(FilterListEntry e) {
        return new File(mListsDir, e.fname);
    }

    /** Merged adblock rules file (consumed downstream by FilterEngine). */
    public File getMergedRulesFile() {
        return mMergedFile;
    }

    public synchronized boolean needsUpdate(boolean firstUpdate) {
        long now = SystemClock.elapsedRealtime();
        return ((now - mLastUpdateMonotonic) >= FILTER_LISTS_UPDATE_MILLIS)
                || (firstUpdate && (getNumUpdated() < getNumLists()));
    }

    public synchronized int getNumLists() { return mLists.size(); }
    public synchronized int getNumEnabled() {
        int c = 0;
        for (FilterListEntry f : mLists) if (f.isEnabled()) c++;
        return c;
    }
    public synchronized int getNumUpdated() {
        int c = 0;
        for (FilterListEntry f : mLists) if (f.isUpToDate()) c++;
        return c;
    }
    public synchronized long getLastUpdate() { return mLastUpdate; }
    public synchronized boolean isUpdateInProgress() { return mUpdateInProgress; }

    public synchronized Iterator<FilterListEntry> iter() {
        return Collections.unmodifiableList(mLists).iterator();
    }

    public synchronized List<FilterListEntry> snapshot() {
        return new ArrayList<>(mLists);
    }

    public synchronized void addPredefined(String label, Category category,
                                           String fname, String url,
                                           boolean enabledByDefault) {
        if (mListByFname.containsKey(fname)) return;
        FilterListEntry e = new FilterListEntry(label, category, fname, url);
        boolean persistedDisabled = readDisabledFnames().contains(fname);
        e.setEnabled(enabledByDefault && !persistedDisabled);
        mLists.add(e);
        mListByFname.put(fname, e);
        notifyListeners();
    }

    /** Adds a user-defined URL. Custom lists default to enabled. */
    public synchronized FilterListEntry addCustom(String label, String url) {
        String fname = "custom_" + (mLists.size() + 1) + ".txt";
        FilterListEntry e = new FilterListEntry(label, Category.CUSTOM, fname, url);
        e.markCustom();
        e.setEnabled(true);
        mLists.add(e);
        mListByFname.put(fname, e);
        notifyListeners();
        return e;
    }

    /**
     * Loads filter list entries from the bundled assets/filters.json catalog,
     * replacing the previous hardcoded addPredefined() call-site approach.
     * Idempotent per fname (mirrors addPredefined's containsKey guard).
     *
     * JSON schema (top-level object): "groups"[], "tags"[], "filters"[].
     * Category mapping: filters.json groupId (1-7) -> FilterListManager.Category,
     * by groupId numeric value (1=AD_BLOCKING, 2=PRIVACY, 3=SOCIAL, 4=ANNOYANCE,
     * 5=SECURITY, 6=OTHER, 7=LANGUAGE). AD_BLOCKING group (groupId 1) defaults
     * enabled; all others default disabled, matching the prior addPredefined()
     * defaults pattern for this app.
     */
    @WorkerThread
    public synchronized void loadFromAssetJson(android.content.Context ctx) {
        try {
            java.io.InputStream is = ctx.getAssets().open("filters.json");
            java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int n;
            while ((n = is.read(chunk)) != -1) buf.write(chunk, 0, n);
            is.close();
            String json = buf.toString("UTF-8");
            org.json.JSONObject root = new org.json.JSONObject(json);
            org.json.JSONArray filters = root.getJSONArray("filters");

            for (int i = 0; i < filters.length(); i++) {
                org.json.JSONObject f = filters.getJSONObject(i);
                int filterId = f.getInt("filterId");
                int groupId = f.getInt("groupId");
                Category category = categoryFromGroupId(groupId);
                if (category == null) continue; // unknown groupId, skip defensively

                String label = f.optString("name", "Filter " + filterId);
                String fname = "json_filter_" + filterId + ".txt";
                String url = f.optString("downloadUrl", f.optString("subscriptionUrl", ""));
                if (url.isEmpty()) continue; // no usable download URL, skip

                String description = f.optString("description", "");
                String homepage = f.optString("homepage", "");
                String subscriptionUrl = f.optString("subscriptionUrl", "");
                String timeUpdated = f.optString("timeUpdated", "");

                java.util.List<Integer> tagIds = new java.util.ArrayList<>();
                org.json.JSONArray tagsArr = f.optJSONArray("tags");
                if (tagsArr != null) {
                    for (int t = 0; t < tagsArr.length(); t++) tagIds.add(tagsArr.getInt(t));
                }

                if (mListByFname.containsKey(fname)) continue;

                boolean enabledByDefault = (category == Category.AD_BLOCKING);
                boolean persistedDisabled = readDisabledFnames().contains(fname);

                FilterListEntry e = new FilterListEntry(label, category, fname, url,
                        description, homepage, subscriptionUrl, timeUpdated, tagIds);
                e.setEnabled(enabledByDefault && !persistedDisabled);
                mLists.add(e);
                mListByFname.put(fname, e);
            }
            notifyListeners();
        } catch (Exception e) {
            android.util.Log.e("FilterListManager", "loadFromAssetJson failed", e);
        }
    }

    /** Maps filters.json numeric groupId (1-7) to the app's Category enum. */
    private static Category categoryFromGroupId(int groupId) {
        switch (groupId) {
            case 1: return Category.AD_BLOCKING;
            case 2: return Category.PRIVACY;
            case 3: return Category.SOCIAL;
            case 4: return Category.ANNOYANCE;
            case 5: return Category.SECURITY;
            case 6: return Category.OTHER;
            case 7: return Category.LANGUAGE;
            default: return null;
        }
    }

    public synchronized boolean setEnabled(String fname, boolean enabled) {
        FilterListEntry e = mListByFname.get(fname);
        if (e == null) return false;
        e.setEnabled(enabled);

        java.util.HashSet<String> disabled = readDisabledFnames();
        if (enabled) {
            disabled.remove(fname);
        } else {
            disabled.add(fname);
        }
        writeDisabledFnames(disabled);

        notifyListeners();
        return true;
    }

    @Nullable
    public synchronized FilterListEntry findByFname(String fname) {
        return mListByFname.get(fname);
    }

    /**
     * Mark {@code count} entries as up-to-date (used by tests and by real downloader when
     * {@link Utils#downloadFile} succeeds). For production use, prefer
     * {@link #runUpdate} which iterates everything.
     */
    public synchronized void markUpdated(FilterListEntry e, long now) {
        e.setUpdated(now);
        mLastUpdate = now;
        mLastUpdateMonotonic = SystemClock.elapsedRealtime();
        notifyListeners();
    }

    /**
     * Build the {@link EnumSet set} of {@link Category} values that should be
     * included when merging, given the four Protection master toggles that
     * gate filter-list content. The other two master toggles (DNS, Firewall)
     * operate at the VPN routing layer and do NOT filter the merge.
     *
     * <p>{@link Category#LANGUAGE}, {@link Category#OTHER}, and
     * {@link Category#CUSTOM} have no master gate and are always included
     * (each is gated only by its own per-list {@code isEnabled()} flag).
     *
     * <p>Enum-keyed instead of a positional {@code boolean[6]} so a future
     * call-site mistake (e.g. swapping {@code tracking} and {@code annoyance})
     * fails loudly at compile-call, never silently swaps two categories.
     */
    public static EnumSet<Category> enabledCategories(boolean adblock,
                                                      boolean tracking,
                                                      boolean annoyance,
                                                      boolean security) {
        EnumSet<Category> s = EnumSet.of(
                Category.LANGUAGE, Category.OTHER, Category.CUSTOM);
        if (adblock)   s.add(Category.AD_BLOCKING);
        if (tracking)  s.add(Category.PRIVACY);
        if (annoyance) s.add(Category.ANNOYANCE);
        if (security)  s.add(Category.SECURITY);
        return s;
    }

    /**
     * Merge all enabled lists into {@code adblock_rules.txt}, prepending
     * the Resource Protection bypass fragment from {@link BypassManager}
     * (which includes the system whitelist as exception rules).
     *
     * <p>A list is included iff both {@code f.isEnabled()} is true AND
     * {@code enabledCategories.contains(f.category)} — that is, the per-list
     * flag is on AND the Protection master switch for that list's category
     * is on. This closes Phase 1's "toggling Ad blocking off must regenerate
     * {@code adblock_rules.txt} excluding Ad lists" promise (plan.md Phase 1
     * Integration Test, executed against a live VPN in Phase 1.b). Toggling
     * off a master switch here writes a smaller merged file; the engine
     * hot-reload downstream picks it up.
     *
     * <p>Returns the number of user-filter lines written (excluding the bypass
     * fragment).
     */
    @WorkerThread
    public synchronized int mergeEnabledLists(Set<Category> enabledCategories) throws IOException {
        int total = 0;
        // Write bypass fragment (system whitelist + UID/port comments) to a temp file first
        File bypassTmp = new File(mFilesDir, "adblock_bypass.tmp");
        BypassManager.get(mContext).writeBypassRuleFragment(bypassTmp);

        try (java.io.FileWriter w = new java.io.FileWriter(mMergedFile, false)) {
            // Prepend the bypass fragment
            String bypassContent = new String(
                    java.nio.file.Files.readAllBytes(bypassTmp.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8);
            w.write(bypassContent);

            // Then append user filter lists whose category is in the enabled set
            for (FilterListEntry f : mLists) {
                if (!f.isEnabled()) continue;
                if (!enabledCategories.contains(f.category)) continue;
                File src = getListFile(f);
                if (!src.exists()) continue;
                String content = new String(
                        java.nio.file.Files.readAllBytes(src.toPath()),
                        java.nio.charset.StandardCharsets.UTF_8);
                w.write(content);
                if (!content.endsWith("\n")) w.write("\n");
                total += content.split("\n", -1).length - 1;
            }
        } finally {
            bypassTmp.delete();
        }
        return total;
    }

    public synchronized void addOnChangeListener(OnChangeListener l) { mListeners.add(l); }
    public synchronized void removeOnChangeListener(OnChangeListener l) { mListeners.remove(l); }

    private void notifyListeners() {
        for (OnChangeListener l : mListeners) l.onFilterListsStateChanged();
    }

    // Internal hooks to satisfy tests that drive update lifecycle deterministically.
    synchronized void beginUpdate() {
        mUpdateInProgress = true;
        for (FilterListEntry f : mLists) f.setUpdating();
        notifyListeners();
    }
    synchronized void endUpdate() {
        mUpdateInProgress = false;
        long now = System.currentTimeMillis();
        mLastUpdate = now;
        mLastUpdateMonotonic = SystemClock.elapsedRealtime();
        // Mark all entries as up-to-date so getNumUpdated() reflects completion
        for (FilterListEntry f : mLists) f.setUpdated(now);
        notifyListeners();
    }
}


