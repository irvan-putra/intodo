package com.instinct.widget;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import androidx.work.testing.WorkManagerTestInitHelper;
import java.io.File;
import java.io.FileOutputStream;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

/** Local-only render proof (never shipped): real Skia pixels at sdk=32. */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 32)
public class MainActivityRenderTest {

    private static final String FEED_JSON =
            "{\"generated_at\":\"2026-11-13T02:00:00Z\",\"sections\":["
            + "{\"id\":\"s1\",\"title\":\"Reminders\",\"items\":["
            + "{\"id\":\"i1\",\"title\":\"Water the plants\",\"note\":\"every morning\",\"status\":\"\"},"
            + "{\"id\":\"i2\",\"title\":\"Read 20 pages\",\"note\":\"\",\"status\":\"in_progress\"}]},"
            + "{\"id\":\"s2\",\"title\":\"Todos\",\"items\":["
            + "{\"id\":\"i3\",\"title\":\"Review test results\",\"note\":\"\",\"status\":\"\"}]}]}";

    @Before
    public void initWorkManager() {
        // Robolectric does not run the androidx.startup initializer; the app's
        // onCreate schedules feed auto-sync via WorkManager.getInstance().
        android.content.Context ctx = RuntimeEnvironment.getApplication();
        ctx.getSharedPreferences("instinct_widget", android.content.Context.MODE_PRIVATE).edit().clear().commit();
        WorkManagerTestInitHelper.initializeTestWorkManager(ctx);
    }

    @Test
    public void renderInitialUi() throws Exception {
        renderTo(a(), "/tmp/integ-main.png");
    }

    @Test
    public void renderSyncedFooter() throws Exception {
        // Unchanged list, fresh sync: the footer must show the last sync AND
        // the older list-change age (no real user data).
        android.content.Context ctx = RuntimeEnvironment.getApplication();
        Prefs.saveKeys(ctx, "test-private-key", "test-public-key");
        Prefs.saveFeed(ctx, FEED_JSON, "2026-11-13T02:00:00Z");
        Prefs.saveCheck(ctx);
        // list content applied 3h ago, last successful sync just now
        ctx.getSharedPreferences("instinct_widget", android.content.Context.MODE_PRIVATE)
                .edit().putLong("feed_at", System.currentTimeMillis() - 3L * 3600 * 1000).commit();
        renderTo(a(), "/tmp/integ-main-synced.png");
    }

    @Test
    public void renderFailedFooter() throws Exception {
        // Last sync succeeded, then a newer attempt failed: the footer must
        // show both the success and the surfaced failure.
        android.content.Context ctx = RuntimeEnvironment.getApplication();
        Prefs.saveKeys(ctx, "test-private-key", "test-public-key");
        Prefs.saveFeed(ctx, FEED_JSON, "2026-11-13T02:00:00Z");
        long now = System.currentTimeMillis();
        ctx.getSharedPreferences("instinct_widget", android.content.Context.MODE_PRIVATE).edit()
                .putLong("check_at", now - 60000)
                .putLong("check_attempt_at", now).putString("check_error", "timed out")
                .commit();
        renderTo(a(), "/tmp/integ-main-failed.png");
    }

    @Test
    public void renderLegacyFooter() throws Exception {
        // Upgraded install: content exists, no sync record yet. The footer
        // must show the content-change age with no implied successful sync.
        android.content.Context ctx = RuntimeEnvironment.getApplication();
        Prefs.saveKeys(ctx, "test-private-key", "test-public-key");
        long now = System.currentTimeMillis();
        ctx.getSharedPreferences("instinct_widget", android.content.Context.MODE_PRIVATE).edit()
                .putString("feed", FEED_JSON).putString("feed_gen", "2026-11-13T02:00:00Z")
                .putLong("feed_at", now - 3L * 3600 * 1000).commit();
        renderTo(a(), "/tmp/integ-main-legacy.png");
    }

    @Test
    public void footerRefreshesWhileActivityOpen() throws Exception {
        // A background sync writes prefs while the activity is visible; the
        // footer must update without a manual sync or lifecycle restart.
        android.content.Context ctx = RuntimeEnvironment.getApplication();
        Activity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        android.widget.TextView footer = a.findViewById(R.id.lastSync);
        String before = String.valueOf(footer.getText());
        Prefs.saveKeys(ctx, "test-private-key", "test-public-key");
        Prefs.saveFeed(ctx, FEED_JSON, "2026-11-13T02:00:00Z");
        Prefs.saveCheck(ctx);
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        String after = String.valueOf(footer.getText());
        assertTrue("footer updated, was: " + before, after.startsWith("synced "));
        assertTrue(after, after.contains("list changed"));
    }

    @Test
    public void renderWidgetFooter() throws Exception {
        // Widget footer must keep both timestamps visible from an A24-like
        // 4-cell full-width widget down to the 250dp minimum (xhdpi here),
        // wrapping instead of ellipsizing either age away.
        android.content.Context ctx = RuntimeEnvironment.getApplication();
        Prefs.saveKeys(ctx, "test-private-key", "test-public-key");
        Prefs.saveFeed(ctx, FEED_JSON, "2026-11-13T02:00:00Z");
        Prefs.saveCheck(ctx);
        ctx.getSharedPreferences("instinct_widget", android.content.Context.MODE_PRIVATE)
                .edit().putLong("feed_at", System.currentTimeMillis() - 3L * 3600 * 1000).commit();
        assertWidgetFooterReadable(ctx, 1000, "/tmp/integ-widget-footer.png");
        assertWidgetFooterReadable(ctx, 500, "/tmp/integ-widget-footer-narrow.png");
    }

    private static void assertWidgetFooterReadable(android.content.Context ctx, int w, String path)
            throws Exception {
        View v = android.view.LayoutInflater.from(ctx).inflate(R.layout.widget_layout, null);
        android.widget.TextView state = v.findViewById(R.id.widget_sync_state);
        String text = SyncStatusText.build(ctx, "tap refresh to sync", true);
        assertTrue(text, text.contains("synced "));
        assertTrue(text, text.contains("list changed"));
        state.setText(text);
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                  View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        v.layout(0, 0, w, v.getMeasuredHeight());
        Bitmap b = Bitmap.createBitmap(w, v.getMeasuredHeight(), Bitmap.Config.ARGB_8888);
        v.draw(new Canvas(b));
        File out = new File(path);
        try (FileOutputStream f = new FileOutputStream(out)) {
            b.compress(Bitmap.CompressFormat.PNG, 100, f);
        }
        assertTrue("render written: " + out.length(), out.length() > 1000);
        android.text.Layout tl = state.getLayout();
        for (int i = 0; i < tl.getLineCount(); i++) {
            assertEquals("line " + i + " ellipsized at width " + w + ": " + text,
                    0, tl.getEllipsisCount(i));
        }
    }

    private static Activity a() {
        return Robolectric.buildActivity(MainActivity.class).setup().get();
    }

    private static void renderTo(Activity act, String path) throws Exception {
        View root = act.getWindow().getDecorView();
        int w = 1080, h = 1920;
        root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                     View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, w, h);
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        root.draw(new Canvas(b));
        File out = new File(path);
        try (FileOutputStream f = new FileOutputStream(out)) {
            b.compress(Bitmap.CompressFormat.PNG, 100, f);
        }
        assertTrue("render written", out.length() > 10000);
    }
}
