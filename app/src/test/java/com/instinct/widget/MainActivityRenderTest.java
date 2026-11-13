package com.instinct.widget;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import android.widget.TextView;
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
    public void renderWidgetListOnly() throws Exception {
        // List-only widget (user-chosen): no title, no status line, no refresh
        // chrome. The list fills the cell; rows and the empty view open the app.
        android.content.Context ctx = RuntimeEnvironment.getApplication();
        Prefs.saveKeys(ctx, "test-private-key", "test-public-key");
        Prefs.saveFeed(ctx, FEED_JSON, "2026-11-13T02:00:00Z");
        Prefs.saveCheck(ctx);
        assertWidgetListOnlyReadable(ctx, 1000, "/tmp/integ-widget-listonly.png");
        assertWidgetListOnlyReadable(ctx, 500, "/tmp/integ-widget-listonly-narrow.png");
    }

    @Test
    public void widgetLayoutsUseOnlyRemoteViewsClasses() throws Exception {
        // A bare android.view.View (or any non-whitelisted class) makes the
        // host fail inflation with "Couldn't add widget". Robolectric inflates
        // it happily, so guard the class set explicitly. Whitelist per the
        // Android app-widget docs: FrameLayout, LinearLayout, RelativeLayout,
        // GridLayout, AnalogClock, Button, Chronometer, ImageButton, ImageView,
        // ProgressBar, TextView, ListView, GridView, StackView,
        // AdapterViewFlipper, ViewFlipper.
        android.content.Context ctx = RuntimeEnvironment.getApplication();
        for (int layout : new int[]{R.layout.widget_layout, R.layout.widget_item,
                                    R.layout.widget_section}) {
            View root = android.view.LayoutInflater.from(ctx).inflate(layout, null);
            java.util.ArrayDeque<View> stack = new java.util.ArrayDeque<>();
            stack.push(root);
            while (!stack.isEmpty()) {
                View v = stack.pop();
                org.junit.Assert.assertTrue(
                        "widget layout " + layout + " uses non-RemoteViews class "
                                + v.getClass().getName(),
                        v instanceof android.widget.FrameLayout
                                || v instanceof android.widget.LinearLayout
                                || v instanceof android.widget.RelativeLayout
                                || v instanceof android.widget.GridLayout
                                || v instanceof android.widget.TextView
                                || v instanceof android.widget.ImageView
                                || v instanceof android.widget.ProgressBar
                                || v instanceof android.widget.ListView
                                || v instanceof android.widget.GridView
                                || v instanceof android.widget.StackView
                                || v instanceof android.widget.AdapterViewFlipper
                                || v instanceof android.widget.ViewFlipper);
                if (v instanceof android.view.ViewGroup) {
                    android.view.ViewGroup g = (android.view.ViewGroup) v;
                    for (int i = 0; i < g.getChildCount(); i++) stack.push(g.getChildAt(i));
                }
            }
        }
    }

    @Test
    public void listIsSwipeRefreshDirectChild() throws Exception {
        // Pull-to-sync must only trigger at the top of the list: the
        // SwipeRefreshLayout's direct child must be the scrollable ListView
        // itself - a wrapper would report canChildScrollUp=false mid-scroll.
        Activity a = a();
        android.widget.ListView list = a.findViewById(R.id.list);
        org.junit.Assert.assertTrue("ListView is SwipeRefreshLayout's direct child",
                list.getParent() instanceof androidx.swiperefreshlayout.widget.SwipeRefreshLayout);
    }

    @Test
    public void backClosesDrawerBeforeFinishing() throws Exception {
        Activity a = a();
        androidx.drawerlayout.widget.DrawerLayout d = a.findViewById(R.id.drawerLayout);
        d.openDrawer(androidx.core.view.GravityCompat.START, false);
        org.junit.Assert.assertTrue(d.isDrawerOpen(androidx.core.view.GravityCompat.START));
        // Back with the menu open must not leave the activity: the override
        // routes it to closeDrawer. The animated close itself is not executed
        // under Robolectric, so the closed state is not asserted here.
        a.onBackPressed();
        org.junit.Assert.assertFalse("Back with menu open keeps the activity",
                a.isFinishing());
        d.closeDrawer(androidx.core.view.GravityCompat.START, false);
        a.onBackPressed();
        org.junit.Assert.assertTrue("Back with menu shut finishes the activity",
                a.isFinishing());
    }

    private static void assertWidgetListOnlyReadable(android.content.Context ctx, int w, String path)
            throws Exception {
        View v = android.view.LayoutInflater.from(ctx).inflate(R.layout.widget_layout, null);
        // the header row is gone entirely: root holds exactly the collection,
        // the empty view and the tappable trailing filler
        org.junit.Assert.assertEquals("widget is list + empty view + filler only",
                3, ((android.widget.LinearLayout) v).getChildCount());
        android.widget.ListView list = v.findViewById(R.id.widget_list);
        org.junit.Assert.assertNotNull("widget keeps its list", list);
        org.junit.Assert.assertNotNull("root takes the background tap",
                v.findViewById(R.id.widget_root));
        org.junit.Assert.assertNotNull("filler takes the trailing tap",
                v.findViewById(R.id.widget_filler));
        org.junit.Assert.assertEquals("no dead divider strips",
                0, list.getDividerHeight());
        // Local adapter fixture mirroring WidgetService.Factory row building
        // (pure list, no section headings), so this render shows real rows
        // instead of a blank unbound ListView.
        FeedParser.Feed feed = FeedParser.parse(FEED_JSON);
        final java.util.List<Object> rows = new java.util.ArrayList<>();
        for (FeedParser.Section sec : feed.sections) {
            rows.addAll(sec.items);
        }
        org.junit.Assert.assertTrue("fixture produced rows", rows.size() > 0);
        list.setAdapter(new android.widget.BaseAdapter() {
            @Override public int getCount() { return rows.size(); }
            @Override public Object getItem(int p) { return rows.get(p); }
            @Override public long getItemId(int p) { return p; }
            @Override public View getView(int p, View c, android.view.ViewGroup parent) {
                android.view.LayoutInflater inf = android.view.LayoutInflater.from(parent.getContext());
                FeedParser.Item item = (FeedParser.Item) rows.get(p);
                View r = inf.inflate(R.layout.widget_item, parent, false);
                String suffix = "in_progress".equals(item.status) ? "  (in progress)" : "";
                ((TextView) r.findViewById(R.id.item_title)).setText(item.title + suffix);
                TextView note = r.findViewById(R.id.item_note);
                if (item.note != null && !item.note.isEmpty()) {
                    note.setText(item.note);
                    note.setVisibility(View.VISIBLE);
                } else {
                    note.setVisibility(View.GONE);
                }
                return r;
            }
        });
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
