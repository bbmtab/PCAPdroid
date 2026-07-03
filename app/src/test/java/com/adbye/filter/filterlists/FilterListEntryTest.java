/*
 * This file is part of ADBye (PCAPdroid).
 */
package com.adbye.filter.filterlists;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Pure unit tests for {@link FilterListEntry}. No Robolectric is strictly required since the
 * model has no Android dependencies, but using RobolectricTestRunner keeps parity with the rest
 * of the suite and ensures the class loads on the JVM with Android's stdlib present.
 */
@RunWith(RobolectricTestRunner.class)
public class FilterListEntryTest {

    private static FilterListEntry mk(String label, FilterListManager.Category cat, String url) {
        return new FilterListEntry(label, cat, "f_" + label + ".txt", url);
    }

    @Test
    public void testDefaults() {
        FilterListEntry e = mk("easylist", FilterListManager.Category.AD_BLOCKING, "https://x/y");
        // Defaults: enabled=true (constructor leaves it at the field initial value),
        // not custom, not updated, not updating.
        assertTrue(e.isEnabled());
        assertFalse(e.isCustom());
        assertFalse(e.isUpToDate());
        assertFalse(e.isUpdating());
        assertEquals(0L, e.getLastUpdate());
        assertEquals(0, e.getNumRules());
    }

    @Test
    public void testMarkCustom() {
        FilterListEntry e = mk("u", FilterListManager.Category.CUSTOM, "https://x");
        assertFalse(e.isCustom());
        e.markCustom();
        assertTrue(e.isCustom());
        // idempotent
        e.markCustom();
        assertTrue(e.isCustom());
    }

    @Test
    public void testLifecycleTransitions() {
        FilterListEntry e = mk("u", FilterListManager.Category.AD_BLOCKING, "https://x");
        assertFalse(e.isUpToDate());

        // 1) Start an update -> isUpdating, isUpToDate=false
        e.setUpdating();
        assertTrue(e.isUpdating());
        assertFalse(e.isUpToDate());

        // 2) Finish -> clearing, isUpToDate=true when now!=0, lastUpdate = now
        long now = 1700000000000L;
        e.setUpdated(now);
        assertFalse(e.isUpdating());
        assertTrue(e.isUpToDate());
        assertEquals(now, e.getLastUpdate());

        // 3) Stale (e.g. on next-day threshold) -> setOutdated clears up-to-date
        e.setOutdated();
        assertFalse(e.isUpdating());
        assertFalse(e.isUpToDate());
        // setOutdated does not erase the previous timestamp
        assertEquals(now, e.getLastUpdate());
    }

    @Test
    public void testSetUpdatedWithZeroIsNotUpToDate() {
        // A 0 timestamp is the sentinel "never refreshed"; in FilterListManager we pass
        // System.currentTimeMillis() on success, but the contract here is: now!=0 -> updated.
        FilterListEntry e = mk("u", FilterListManager.Category.AD_BLOCKING, "https://x");
        e.setUpdating();
        e.setUpdated(0);
        assertFalse(e.isUpToDate());
        assertEquals(0L, e.getLastUpdate());
    }

    @Test
    public void testNumRules() {
        FilterListEntry e = mk("u", FilterListManager.Category.AD_BLOCKING, "https://x");
        e.setNumRules(1234);
        assertEquals(1234, e.getNumRules());
    }

    @Test
    public void testSetEnabled() {
        FilterListEntry e = mk("u", FilterListManager.Category.AD_BLOCKING, "https://x");
        e.setEnabled(false);
        assertFalse(e.isEnabled());
        e.setEnabled(true);
        assertTrue(e.isEnabled());
    }

    @Test
    public void testFieldsAreFinalAndExposed() {
        FilterListEntry e = mk("My List", FilterListManager.Category.PRIVACY, "https://x");
        // Final public fields: defensive â€” a regression that loses these would break the GUI
        // binding that reads them by name.
        assertEquals("My List", e.label);
        assertEquals(FilterListManager.Category.PRIVACY, e.category);
        assertEquals("f_My List.txt", e.fname);
        assertEquals("https://x", e.url);
    }
}


