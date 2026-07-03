/*
 * This file is part of ADBye (PCAPdroid).
 */
package com.adbye.filter.filterlists;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for {@link BypassManager}.
 */
@RunWith(RobolectricTestRunner.class)
public class BypassManagerTest {
    Context ctx;
    BypassManager mgr;

    @Before
    public void setup() {
        ctx = ApplicationProvider.getApplicationContext();
        BypassManager.resetForTests();
        mgr = BypassManager.get(ctx);
    }

    @After
    public void tearDown() {
        BypassManager.resetForTests();
    }

    // --- Hardcoded UID allowlist ------------------------------------------------

    @Test
    public void testHardcodedUidAllowlistPresent() {
        assertTrue("Play Store must be bypassed", mgr.isUidBypassed("com.android.vending"));
        assertTrue("Google Play Services must be bypassed", mgr.isUidBypassed("com.google.android.gms"));
        assertTrue("Google Services Framework must be bypassed", mgr.isUidBypassed("com.google.android.gsf"));
        assertTrue("IMS must be bypassed", mgr.isUidBypassed("com.android.ims"));
    }

    @Test
    public void testUnknownUidNotBypassed() {
        assertFalse("Random UID not bypassed", mgr.isUidBypassed("com.example.random"));
        assertFalse("Empty string not bypassed", mgr.isUidBypassed(""));
    }

    // --- Hardcoded domain allowlist ---------------------------------------------

    @Test
    public void testHardcodedDomainAllowlistExactMatch() {
        assertTrue("googlevideo.com exact", mgr.isDomainBypassed("googlevideo.com"));
        assertTrue("nflxvideo.net exact", mgr.isDomainBypassed("nflxvideo.net"));
        assertTrue("fbcdn.net exact", mgr.isDomainBypassed("fbcdn.net"));
    }

    @Test
    public void testHardcodedDomainAllowlistSuffixMatch() {
        // Real SNI hostnames are subdomains like r1---sn-xxxx.googlevideo.com
        assertTrue("sub.googlevideo.com suffixed", mgr.isDomainBypassed("r1---sn-abc.googlevideo.com"));
        assertTrue("deep.sub.nflxvideo.net suffixed", mgr.isDomainBypassed("a.b.c.nflxvideo.net"));
        assertTrue("fbcdn.net suffix", mgr.isDomainBypassed("video.fbcdn.net"));
    }

    @Test
    public void testDomainAllowlistCaseInsensitive() {
        assertTrue(mgr.isDomainBypassed("GOOGLEVIDEO.COM"));
        assertTrue(mgr.isDomainBypassed("GoOgLeViDeO.CoM"));
        assertTrue(mgr.isDomainBypassed("Sub.GoogleVideo.Com"));
    }

    @Test
    public void testDomainNotBypassedIfOnlyPartial() {
        // "mygooglevideo.com" must NOT match "googlevideo.com"
        assertFalse("partial match not bypassed", mgr.isDomainBypassed("mygooglevideo.com"));
        assertFalse(mgr.isDomainBypassed("fakefbcdn.net"));
        assertFalse(mgr.isDomainBypassed("tiktokcdn.com.evil.com"));
    }

    // --- Hardcoded port allowlist -----------------------------------------------

    @Test
    public void testHardcodedPortAllowlist() {
        assertTrue("FCM port 5228", mgr.isPortBypassed(5228));
        assertTrue("FCM port 5229", mgr.isPortBypassed(5229));
        assertTrue("FCM port 5230", mgr.isPortBypassed(5230));
    }

    @Test
    public void testPortNotBypassed() {
        assertFalse("HTTP 80 not bypassed", mgr.isPortBypassed(80));
        assertFalse("HTTPS 443 not bypassed", mgr.isPortBypassed(443));
        assertFalse("Random port", mgr.isPortBypassed(12345));
    }

    // --- Dynamic flow threshold -------------------------------------------------

    @Test
    public void testDefaultDynamicThreshold() {
        long defaultBytes = 5L * 1024 * 1024;
        assertEquals(defaultBytes, mgr.getDynamicThresholdBytes());
        assertFalse("Under threshold", mgr.isFlowBypassed(defaultBytes - 1));
        assertTrue("At threshold", mgr.isFlowBypassed(defaultBytes));
        assertTrue("Over threshold", mgr.isFlowBypassed(defaultBytes + 1));
    }

    @Test
    public void testDefaultLargeDownloadThreshold() {
        long defaultBytes = 20L * 1024 * 1024;
        assertEquals(defaultBytes, mgr.getLargeDownloadThresholdBytes());
        assertFalse("Under large threshold", mgr.isLargeDownloadBypassed(defaultBytes - 1));
        assertTrue("At large threshold", mgr.isLargeDownloadBypassed(defaultBytes));
        assertTrue("Over large threshold", mgr.isLargeDownloadBypassed(defaultBytes + 1));
    }

    @Test
    public void testSetDynamicThresholdMbPersists() throws Exception {
        mgr.setDynamicThresholdMb(10);
        assertEquals(10L * 1024 * 1024, mgr.getDynamicThresholdBytes());

        // Simulate process restart by resetting singleton and reloading
        BypassManager.resetForTests();
        BypassManager mgr2 = BypassManager.get(ctx);
        assertEquals("Persisted across reload", 10L * 1024 * 1024, mgr2.getDynamicThresholdBytes());
    }

    @Test
    public void testSetLargeDownloadThresholdMbPersists() throws Exception {
        mgr.setLargeDownloadThresholdMb(50);
        assertEquals(50L * 1024 * 1024, mgr.getLargeDownloadThresholdBytes());

        BypassManager.resetForTests();
        BypassManager mgr2 = BypassManager.get(ctx);
        assertEquals("Persisted across reload", 50L * 1024 * 1024, mgr2.getLargeDownloadThresholdBytes());
    }

    // --- User-added UID allowlist ------------------------------------------------

    @Test
    public void testAddRemoveUserUidAllowlist() {
        String custom = "com.my.app";
        assertFalse("Initially not bypassed", mgr.isUidBypassed(custom));

        mgr.addUidAllowlist(custom);
        assertTrue("After add", mgr.isUidBypassed(custom));

        mgr.removeUidAllowlist(custom);
        assertFalse("After remove", mgr.isUidBypassed(custom));
    }

    @Test
    public void testUserUidAllowlistPersists() {
        mgr.addUidAllowlist("com.test.persist");
        BypassManager.resetForTests();
        BypassManager mgr2 = BypassManager.get(ctx);
        assertTrue("Persisted UID bypassed after reload", mgr2.isUidBypassed("com.test.persist"));
    }

    @Test
    public void testGetUidAllowlistSnapshotImmutable() {
        List<String> list = mgr.getUidAllowlist();
        int before = list.size();
        // Attempt to mutate returned list (should be unmodifiable or copy)
        try {
            list.add("com.hack");
            // If it allows add, the internal set should be unaffected
        } catch (UnsupportedOperationException e) {
            // Expected for unmodifiable list
        }
        assertEquals("Internal list unchanged", before, mgr.getUidAllowlist().size());
    }

    // --- Rule fragment export ----------------------------------------------------

    @Test
    public void testWriteBypassRuleFragmentContainsAllSections() throws IOException {
        File out = File.createTempFile("bypass_", ".txt");
        out.deleteOnExit();
        mgr.writeBypassRuleFragment(out);

        String content = new String(Files.readAllBytes(out.toPath()), StandardCharsets.UTF_8);

        // UID section (informational comments)
        assertTrue(content.contains("! ADBye Resource Protection â€” UID bypass"));
        assertTrue(content.contains("! u:com.android.vending"));
        assertTrue(content.contains("! u:com.google.android.gms"));
        assertTrue(content.contains("! u:com.google.android.gsf"));
        assertTrue(content.contains("! u:com.android.ims"));

        // Domain section: must be EXCEPTION rules (@@||domain^) to ALLOW these domains
        assertTrue(content.contains("! ADBye Resource Protection â€” Domain bypass"));
        assertTrue(content.contains("@@||googlevideo.com^"));
        assertTrue(content.contains("@@||nflxvideo.net^"));
        assertTrue(content.contains("@@||fbcdn.net^"));
        assertTrue(content.contains("@@||cdninstagram.com^"));
        assertTrue(content.contains("@@||ttvnw.net^"));
        assertTrue(content.contains("@@||tiktokcdn.com^"));
        assertTrue(content.contains("@@||play.googleapis.com^"));
        assertTrue(content.contains("@@||dl.google.com^"));
        assertTrue(content.contains("@@||mtalk.google.com^"));
        assertTrue(content.contains("@@||android.clients.google.com^"));

        // Port section (informational comments; enforced in JNI)
        assertTrue(content.contains("! ADBye Resource Protection â€” Port bypass"));
        assertTrue(content.contains("! p:5228"));
        assertTrue(content.contains("! p:5229"));
        assertTrue(content.contains("! p:5230"));
    }

    @Test
    public void testWriteBypassRuleFragmentIncludesUserAddedUids() throws IOException {
        mgr.addUidAllowlist("com.user.added");
        File out = File.createTempFile("bypass_", ".txt");
        out.deleteOnExit();
        mgr.writeBypassRuleFragment(out);
        String content = new String(Files.readAllBytes(out.toPath()), StandardCharsets.UTF_8);
        assertTrue("User UID in fragment", content.contains("! u:com.user.added"));
    }

    @Test
    public void testGetBypassRuleFragmentString() throws IOException {
        String s = mgr.getBypassRuleFragment();
        assertTrue(s.contains("! ADBye Resource Protection"));
        assertTrue(s.contains("||googlevideo.com^"));
        assertTrue(s.contains(":5228"));
    }

    // --- Singleton behavior ------------------------------------------------------

    @Test
    public void testSingletonReturnsSameInstance() {
        BypassManager m1 = BypassManager.get(ctx);
        BypassManager m2 = BypassManager.get(ctx);
        assertEquals(m1, m2);
    }

    @Test
    public void testResetForTestsClearsSingleton() {
        BypassManager m1 = BypassManager.get(ctx);
        BypassManager.resetForTests();
        BypassManager m2 = BypassManager.get(ctx);
        // Different instance after reset
        assertFalse("Reset creates new instance", m1 == m2);
    }
}

