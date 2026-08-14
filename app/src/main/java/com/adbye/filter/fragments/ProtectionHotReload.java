/*
 * This file is part of ADBye (PCAPdroid).
 *
 * Shared host-independent hot-reload logic, extracted from
 * ProtectionFragment.applyProtectionChange (2026-08-13, F1 Network HTTPS
 * Filtering task) so it can be called from both ProtectionFragment and the
 * new NetworkFragment without duplicating the merge+reload logic. Plan.md
 * constraint #7: toggling any master switch must re-merge rules and reload
 * the engine without restarting CaptureService. Uses only
 * Context.getApplicationContext(), CaptureService's public static methods,
 * and FilterListManager(Context) -- all confirmed host-agnostic.
 */
package com.adbye.filter.fragments;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

import com.adbye.filter.CaptureService;
import com.adbye.filter.Log;
import com.adbye.filter.filterlists.FilterListManager;
import com.adbye.filter.model.Prefs;

import java.io.File;
import java.util.EnumSet;

public final class ProtectionHotReload {
    private static final String TAG = "ProtectionHotReload";

    private ProtectionHotReload() {}

    public static void apply(Context appCtx, String prefKey, boolean enabled) {
        Log.d(TAG, "Protection changed: " + prefKey + "=" + enabled);

        if (Prefs.PREF_PROTECT_ADBLOCK.equals(prefKey)) {
            CaptureService.setAdblockEnabled(enabled);
        }

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(appCtx);

        new Thread(() -> {
            try {
                EnumSet<FilterListManager.Category> cats = FilterListManager.enabledCategories(
                        Prefs.isProtectAdblock(prefs),
                        Prefs.isProtectTracking(prefs),
                        Prefs.isProtectAnnoyance(prefs),
                        Prefs.isProtectSecurity(prefs));
                Log.d(TAG, "Master switches -> enabled categories: " + cats);

                FilterListManager mgr = new FilterListManager(appCtx);
                int n = mgr.mergeEnabledLists(cats);
                File merged = mgr.getMergedRulesFile();
                Log.d(TAG, "mergeEnabledLists wrote " + n + " user lines -> " + merged);
                CaptureService.reloadAdblockRules(merged.getAbsolutePath());
            } catch (java.io.IOException e) {
                Log.e(TAG, "mergeEnabledLists failed for " + prefKey + ": " + e);
            }
        }, "AdbyeHotReload").start();
    }
}
