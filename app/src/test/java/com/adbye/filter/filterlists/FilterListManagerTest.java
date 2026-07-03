/*
 * This file is part of ADBye (PCAPdroid).
 */
package com.adbye.filter.filterlists;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import com.adbye.filter.filterlists.FilterListManager.OnChangeListener;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;
import java.io.FileWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
public class FilterListManagerTest {
    Context ctx;
    FilterListManager mgr;
    RecordingListener listener;

    private static class RecordingListener implements OnChangeListener {
        final List<String> events = new ArrayList<>();
        @Override public void onFilterListsStateChanged() { events.add("changed"); }
    }

    @Before
    public void setup() {
        ctx = ApplicationProvider.getApplicationContext();
        BypassManager.resetForTests();
        mgr = new FilterListManager(ctx);
        // Defensive: ensure a clean slate for tests that index by fname counts.
        for (FilterListEntry e : mgr.snapshot()) {
            mgr.setEnabled(e.fname, false);
        }
        // Wipe merged output and any cached per-list files for this run.
        mgr.getMergedRulesFile().delete();
        File listsDir = new File(ctx.getFilesDir(), "filter_lists");
        for (File f : listsDir.listFiles() != null ? listsDir.listFiles() : new File[0]) {
            f.delete();
        }
        listener = new RecordingListener();
        mgr.addOnChangeListener(listener);
    }

    @After
    public void tearDown() {
        if (listener != null) mgr.removeOnChangeListener(listener);
        BypassManager.resetForTests();
    }

    private void writeListFile(FilterListEntry e, String content) throws Exception {
        File f = mgr.getListFile(e);
        try (FileWriter w = new FileWriter(f, false)) {
            w.write(content);
        }
    }

    // --- catalog management ------------------------------------------------

    @Test
    public void testAddPredefinedDedupByFname() {
        mgr.addPredefined("EasyList", FilterListManager.Category.AD_BLOCKING, "easylist.txt",
                "https://example.org/easylist.txt", true);
        // Same fname, second add must be a no-op.
        mgr.addPredefined("EasyList (dup)", FilterListManager.Category.AD_BLOCKING, "easylist.txt",
                "https://example.org/easylist.txt", false);

        assertEquals(1, mgr.getNumLists());
        FilterListEntry e = mgr.findByFname("easylist.txt");
        assertNotNull(e);
        assertEquals("EasyList", e.label); // first add wins
        assertTrue(e.isEnabled());
    }

    @Test
    public void testAddPredefinedRespectsEnabledByDefault() {
        mgr.addPredefined("off-by-default", FilterListManager.Category.SECURITY, "off.txt",
                "https://x", false);
        mgr.addPredefined("on-by-default", FilterListManager.Category.SECURITY, "on.txt",
                "https://x", true);

        // off-by-default should be disabled, on-by-default should be enabled
        assertEquals(1, mgr.getNumEnabled());
        assertFalse(mgr.findByFname("off.txt").isEnabled());
        assertTrue(mgr.findByFname("on.txt").isEnabled());

        mgr.setEnabled("off.txt", true);
        assertEquals(2, mgr.getNumEnabled());
    }

    @Test
    public void testAddCustomGeneratesUniqueFnameAndMarksCustom() {
        FilterListEntry c1 = mgr.addCustom("My List 1", "https://x/1");
        FilterListEntry c2 = mgr.addCustom("My List 2", "https://x/2");

        assertEquals(FilterListManager.Category.CUSTOM, c1.category);
        assertEquals(FilterListManager.Category.CUSTOM, c2.category);
        assertTrue(c1.isCustom());
        assertTrue(c2.isCustom());
        assertTrue(c1.isEnabled());
        assertTrue(c2.isEnabled());
        // Both default to enabled.
        assertEquals(2, mgr.getNumEnabled());

        // fname pattern: custom_<n>.txt â€” must be distinct and findable.
        assertNotNull(mgr.findByFname(c1.fname));
        assertNotNull(mgr.findByFname(c2.fname));
        assertFalse(c1.fname.equals(c2.fname));
    }

    @Test
    public void testSetEnabledReportsAndFlips() {
        mgr.addPredefined("L", FilterListManager.Category.OTHER, "l.txt", "u", true);

        assertTrue(mgr.setEnabled("l.txt", false));
        assertFalse(mgr.findByFname("l.txt").isEnabled());
        // unknown fname
        assertFalse(mgr.setEnabled("nope.txt", true));
        assertNull(mgr.findByFname("nope.txt"));
    }

    @Test
    public void testIterAndSnapshotAreIndependent() {
        mgr.addPredefined("A", FilterListManager.Category.OTHER, "a.txt", "u", true);
        mgr.addPredefined("B", FilterListManager.Category.OTHER, "b.txt", "u", true);

        List<FilterListEntry> snap = mgr.snapshot();
        assertEquals(2, snap.size());

        // Snapshot mutations don't affect the manager.
        snap.clear();
        assertEquals(2, mgr.getNumLists());

        // Iterator is unmodifiable.
        var it = mgr.iter();
        assertTrue(it.hasNext());
        // remove() not supported by Collections.unmodifiableList wrapping
        try {
            it.remove();
            // Collection-returning iterator.remove() throws UOE; fail if not.
            org.junit.Assert.fail("iterator must be unmodifiable");
        } catch (UnsupportedOperationException expected) {
            // OK
        }
    }

    // --- update lifecycle --------------------------------------------------

    @Test
    public void testNeedsUpdateFirstRunBeforeAnyUpdate() {
        // Fresh mgr: lastUpdateMonotonic = -DELTA, so firstUpdate path triggers.
        assertTrue(mgr.needsUpdate(true));
    }

    @Test
    public void testNeedsUpdateClearedByEndUpdate() throws Exception {
        mgr.addPredefined("L", FilterListManager.Category.OTHER, "l.txt", "u", true);
        writeListFile(mgr.findByFname("l.txt"), "||ads.example.com^\n");

        mgr.beginUpdate();
        assertTrue(mgr.isUpdateInProgress());
        assertFalse(mgr.findByFname("l.txt").isUpToDate());

        mgr.endUpdate();
        assertFalse(mgr.isUpdateInProgress());
        assertTrue(mgr.findByFname("l.txt").isUpToDate());
        // Last update timestamp is now > 0.
        assertTrue(mgr.getLastUpdate() > 0);
        // Cooldown means we don't need another immediately.
        assertFalse(mgr.needsUpdate(false));
    }

    @Test
    public void testMarkUpdatedForIndividualEntry() {
        mgr.addPredefined("L", FilterListManager.Category.OTHER, "l.txt", "u", true);
        FilterListEntry e = mgr.findByFname("l.txt");
        e.setUpdating();

        long now = 1_700_000_000_000L;
        mgr.markUpdated(e, now);

        assertFalse(e.isUpdating());
        assertTrue(e.isUpToDate());
        assertEquals(now, e.getLastUpdate());
        assertEquals(now, mgr.getLastUpdate());
    }

    // --- listener notifications -------------------------------------------

    @Test
    public void testOnChangeListenerFiresOnMutation() {
        int before = listener.events.size();
        mgr.addPredefined("L", FilterListManager.Category.OTHER, "l.txt", "u", true);
        mgr.setEnabled("l.txt", false);
        // both mutate + setEnabled notify
        assertTrue(listener.events.size() - before >= 2);
    }

    @Test
    public void testListenerRemoval() {
        mgr.removeOnChangeListener(listener);
        int before = listener.events.size();
        mgr.addPredefined("L", FilterListManager.Category.OTHER, "l.txt", "u", true);
        assertEquals(0, listener.events.size() - before);
    }

    // --- merge: contract ---------------------------------------------------

    @Test
    public void testMergeEnabledListsPrependsBypassFragment() throws Exception {
        mgr.addPredefined("L", FilterListManager.Category.AD_BLOCKING, "l.txt", "u", true);
        writeListFile(mgr.findByFname("l.txt"),
                "||doubleclick.net^\n" +
                "||ads.example.org^\n");

        int lines = mgr.mergeEnabledLists();
        assertEquals(2, lines);

        String merged = new String(Files.readAllBytes(mgr.getMergedRulesFile().toPath()),
                StandardCharsets.UTF_8);
        // Bypass fragment (Resource Protection) must be prepended
        assertTrue("Must start with Resource Protection comment",
                merged.startsWith("! ADBye Resource Protection â€” UID bypass"));
        // Domain exception rules from BypassManager
        assertTrue(merged.contains("@@||googlevideo.com^"));
        assertTrue(merged.contains("@@||nflxvideo.net^"));
        assertTrue(merged.contains("@@||fbcdn.net^"));
        // User rules still appended
        assertTrue(merged.contains("||doubleclick.net^"));
        assertTrue(merged.contains("||ads.example.org^"));
    }

    @Test
    public void testMergeSkipsDisabledLists() throws Exception {
        mgr.addPredefined("on",  FilterListManager.Category.AD_BLOCKING, "on.txt",  "u", true);
        mgr.addPredefined("off", FilterListManager.Category.AD_BLOCKING, "off.txt", "u", false);
        writeListFile(mgr.findByFname("on.txt"),  "||on.example.com^\n");
        writeListFile(mgr.findByFname("off.txt"), "||off.example.com^\n");

        mgr.mergeEnabledLists();
        String merged = new String(Files.readAllBytes(mgr.getMergedRulesFile().toPath()),
                StandardCharsets.UTF_8);
        assertTrue(merged.contains("||on.example.com^"));
        assertFalse(merged.contains("||off.example.com^"));
        // No rule named "on" in the system whitelist, so count == 1 user filter line.
        long userCount = merged.lines()
                .filter(s -> !s.startsWith("!") && !s.startsWith("@@") && !s.isEmpty())
                .count();
        assertTrue("user rules leaked from disabled list", userCount >= 1);
    }

    @Test
    public void testMergeSkipsMissingFiles() throws Exception {
        // enabled entry, but file does not exist on disk -> must not throw.
        mgr.addPredefined("nofile", FilterListManager.Category.AD_BLOCKING, "missing.txt", "u", true);

        int lines = mgr.mergeEnabledLists();
        // No user rules written; calls must succeed.
        assertEquals(0, lines);

        String merged = new String(Files.readAllBytes(mgr.getMergedRulesFile().toPath()),
                StandardCharsets.UTF_8);
        // Bypass fragment still prepended (and is the entire contents since no user rules).
        assertTrue("Must start with Resource Protection comment",
                merged.startsWith("! ADBye Resource Protection â€” UID bypass"));
        assertTrue(merged.contains("@@||googlevideo.com^"));
    }

    @Test
    public void testMergeCountsAllUserRulesAcrossLists() throws Exception {
        mgr.addPredefined("a", FilterListManager.Category.AD_BLOCKING, "a.txt", "u", true);
        mgr.addPredefined("b", FilterListManager.Category.AD_BLOCKING, "b.txt", "u", true);
        writeListFile(mgr.findByFname("a.txt"), "||a1.com^\n||a2.com^\n");
        writeListFile(mgr.findByFname("b.txt"), "||b1.com^\n");

        assertEquals(3, mgr.mergeEnabledLists());
    }
}


