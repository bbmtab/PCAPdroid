/*
 * This file is part of ADBye (PCAPdroid).
 *
 * Licensed under GPLv3.
 */
package com.adbye.filter.model;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;
import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import static org.junit.Assert.assertEquals;

/**
 * Phase 0 invariant tests for {@link Prefs}.
 *
 * <p>Currently the only assertion is the plan.md constraint #9 invariant:
 * {@code CaptureSettings.dump_mode} MUST default to {@link Prefs.DumpMode#NONE}.
 * The constraint documents that no phase may silently change this default;
 * any phase that does MUST update the constraint text explicitly. This test
 * enforces the invariant at CI run-time so a regression surfaces immediately,
 * not just at code-review time.
 */
@RunWith(RobolectricTestRunner.class)
public class PrefsTest {
    private SharedPreferences mPrefs;

    @Before
    public void setup() {
        Context ctx = ApplicationProvider.getApplicationContext();
        mPrefs = PreferenceManager.getDefaultSharedPreferences(ctx);
        // Defensive: every assertion below asserts the *default* path. Wipe
        // the prefs file so any leftover from another test class (or a prior
        // run leaking into Robolectric's in-memory backing) cannot influence
        // the result.
        mPrefs.edit().clear().commit();
    }

    @Test
    public void testDumpModeDefaultIsNone() {
        // plan.md constraint #9: dump_mode MUST remain defaulted to NONE.
        // CaptureSettings.dump_mode is the carrier for raw-pcap-file / HTTP
        // server / UDP / TCP export modes — AND it gates the heavy
        // capture/telemetry surface that Phase 2 and Phase 3 depend on. A
        // phase that flips this default silently would also re-introduce the
        // telemetry fan-out this constraint is designed to keep off.
        // See plan.md constraint #9 for the rationale + the open
        // Connections-tab decision.
        Prefs.DumpMode actual = Prefs.getDumpMode(mPrefs);
        assertEquals(
                "plan.md constraint #9: CaptureSettings.dump_mode MUST remain defaulted to NONE. " +
                        "A future phase that needs a different default MUST update plan.md " +
                        "constraint #9 explicitly, not silently change the constant.",
                Prefs.DumpMode.NONE, actual);
    }
}
