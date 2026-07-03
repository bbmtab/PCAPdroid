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
import java.util.Iterator;
import java.util.List;

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
        e.setEnabled(enabledByDefault);
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

    public synchronized boolean setEnabled(String fname, boolean enabled) {
        FilterListEntry e = mListByFname.get(fname);
        if (e == null) return false;
        e.setEnabled(enabled);
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
     * Merge all enabled lists into {@code adblock_rules.txt}, prepending
     * the Resource Protection bypass fragment from {@link BypassManager}
     * (which includes the system whitelist as exception rules).
     * Returns the number of user-filter lines written (excluding the bypass fragment).
     */
    @WorkerThread
    public synchronized int mergeEnabledLists() throws IOException {
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

            // Then append enabled user filter lists
            for (FilterListEntry f : mLists) {
                if (!f.isEnabled()) continue;
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


