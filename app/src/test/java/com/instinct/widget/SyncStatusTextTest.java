package com.instinct.widget;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 32)
public class SyncStatusTextTest {
    private Context c;
    private SharedPreferences prefs;

    @Before
    public void setUp() {
        c = RuntimeEnvironment.getApplication();
        prefs = c.getSharedPreferences("instinct_widget", Context.MODE_PRIVATE);
        prefs.edit().clear().commit();
    }

    @Test
    public void neverSyncedShowsFallback() {
        assertEquals("never synced", SyncStatusText.build(c, Prefs.status(c)));
    }

    @Test
    public void legacyFeedShowsListChangeWithoutSyncClaim() {
        // Upgraded installs have feed_at without any check record: show the
        // content-change age honestly, with no implied successful sync.
        Prefs.saveFeed(c, "{}", "2026-11-13T02:00:00Z");
        String s = SyncStatusText.build(c, Prefs.status(c));
        assertTrue(s, s.contains("list changed"));
        assertTrue(s, s.contains("as of"));
        assertFalse(s, s.contains("synced"));
    }

    @Test
    public void successfulSyncShowsBothAges() {
        Prefs.saveFeed(c, "{}", "2026-11-13T02:00:00Z");
        Prefs.saveCheck(c);
        String s = SyncStatusText.build(c, Prefs.status(c));
        assertTrue(s, s.startsWith("synced "));
        assertTrue(s, s.contains("list changed"));
        assertTrue(s, s.contains("as of"));
        assertFalse(s, s.contains("failed"));
    }

    @Test
    public void failureAfterSuccessIsSurfaced() {
        long now = System.currentTimeMillis();
        prefs.edit()
                .putLong("feed_at", now).putString("feed_gen", "2026-11-13T02:00:00Z")
                .putLong("check_at", now)
                .putLong("check_attempt_at", now + 1000).putString("check_error", "timed out")
                .commit();
        String s = SyncStatusText.build(c, Prefs.status(c));
        assertTrue(s, s.contains("synced "));
        assertTrue(s, s.contains("list changed"));
        assertTrue(s, s.contains("last sync failed"));
    }

    @Test
    public void successClearsFailure() {
        Prefs.saveCheckFailure(c, "timed out");
        Prefs.saveCheck(c);
        String s = SyncStatusText.build(c, Prefs.status(c));
        assertTrue(s, s.startsWith("synced "));
        assertFalse(s, s.contains("failed"));
    }

    @Test
    public void compactWidgetFormKeepsBothAgesDropsVersion() {
        Prefs.saveFeed(c, "{}", "2026-11-13T02:00:00Z");
        Prefs.saveCheck(c);
        String s = SyncStatusText.build(c, "tap refresh to sync", true);
        assertTrue(s, s.startsWith("synced "));
        assertTrue(s, s.contains("list changed"));
        assertFalse(s, s.contains("as of"));
    }

    @Test
    public void widgetFallbacksUnchanged() {
        assertEquals("pair in app", SyncStatusText.build(c, "pair in app"));
        assertEquals("tap refresh to sync", SyncStatusText.build(c, "tap refresh to sync"));
    }
}
