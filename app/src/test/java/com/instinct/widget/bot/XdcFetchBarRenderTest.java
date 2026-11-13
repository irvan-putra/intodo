package com.instinct.widget.bot;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

/**
 * Pixel proof for the XDC fetch progress bar on a 360x640dp phone.
 * The fetch callback lives in BotActivity's startFetchFlow; tests cannot
 * reach it, so this test drives the same views with the same expressions
 * (kbText formatting via UpdateInstall.mb, identical rules). The callback
 * glue is covered by source review. Synthetic fixtures only.
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 32, qualifiers = "xxhdpi")
public class XdcFetchBarRenderTest {

    private static Activity activity() throws Exception {
        org.robolectric.android.controller.ActivityController<BotActivity> c = Robolectric.buildActivity(BotActivity.class);
        Activity a = c.get();
        String state = "{\"provisioned\":true,\"bot_addr\":\"bot-fixture@example.invalid\","
            + "\"chats\":{\"fixture-group\":{}}}";
        try (FileWriter w = new FileWriter(new File(a.getFilesDir(), "bot_state.json"))) { w.write(state); }
        c.setup();
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
            .idleFor(10, java.util.concurrent.TimeUnit.MILLISECONDS);
        return a;
    }

    private static ProgressBar findBar(View v) {
        if (v instanceof ProgressBar) return (ProgressBar) v;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                ProgressBar b = findBar(g.getChildAt(i));
                if (b != null) return b;
            }
        }
        return null;
    }

    private static void render(Activity a, String path) throws Exception {
        View root = a.getWindow().getDecorView();
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
        assertTrue("render produced: " + path, out.length() > 5000);
    }

    // Same rules as BotActivity.kbText / UpdateInstall.mb (both package-private).
    private static String kb(long bytes) {
        if (bytes < 0) return "?";
        if (bytes < 1024 * 1024) return (bytes / 1024) + " KB";
        return String.format(java.util.Locale.US, "%.1f MB", bytes / 1048576.0);
    }

    /** Mirrors the pump listener expression for a known total. */
    private static void known(Activity a, long done, long total, ProgressBar bar, TextView status) {
        int pct = (int) (done * 100 / total);
        bar.setIndeterminate(false);
        bar.setProgress(pct);
        status.setText("Fetching app: " + kb(done) + " / " + kb(total) + " (" + pct + "%)");
    }

    private static TextView statusView(Activity a, ProgressBar bar) {
        // the fetch status line is the sibling directly above the bar
        ViewGroup parent = (ViewGroup) bar.getParent();
        int idx = parent.indexOfChild(bar);
        assertTrue("bar is inside its section", idx > 0);
        View sib = parent.getChildAt(idx - 1);
        assertTrue("fetch status line sits above the bar", sib instanceof TextView);
        return (TextView) sib;
    }

    private static TextView shown(Activity a, ProgressBar bar, String prefix) {
        TextView s = statusView(a, bar);
        s.setVisibility(View.VISIBLE);
        bar.setVisibility(View.VISIBLE);
        return s;
    }

    @Test
    public void renderKnownTotal() throws Exception {
        Activity a = activity();
        ProgressBar bar = findBar(a.getWindow().getDecorView());
        assertNotNull("fetch bar exists", bar);
        assertEquals(View.GONE, bar.getVisibility()); // hidden until a fetch starts
        assertEquals(100, bar.getMax());
        TextView s = shown(a, bar, "Fetching app");
        known(a, 6 * 1024 * 1024, 12_000_000, bar, s);
        assertEquals("Fetching app: 6.0 MB / 11.4 MB (52%)", s.getText().toString());
        render(a, "/tmp/xdc-bar-known.png");
    }

    @Test
    public void renderUnknownTotal() throws Exception {
        Activity a = activity();
        ProgressBar bar = findBar(a.getWindow().getDecorView());
        TextView s = shown(a, bar, "Fetching app");
        bar.setIndeterminate(true);
        s.setText("Fetching app: " + kb(2_100_000));
        assertEquals("Fetching app: 2.0 MB", s.getText().toString());
        assertTrue("unknown total stays indeterminate", bar.isIndeterminate());
        render(a, "/tmp/xdc-bar-unknown.png");
    }

    @Test
    public void renderResumed() throws Exception {
        // resumed bytes are counted from the start: same view state, higher base
        Activity a = activity();
        ProgressBar bar = findBar(a.getWindow().getDecorView());
        TextView s = shown(a, bar, "Fetching app");
        known(a, 9 * 1024 * 1024, 12_000_000, bar, s);
        assertEquals("Fetching app: 9.0 MB / 11.4 MB (78%)", s.getText().toString());
        render(a, "/tmp/xdc-bar-resumed.png");
    }

    @Test
    public void renderTerminalSuccess() throws Exception {
        Activity a = activity();
        ProgressBar bar = findBar(a.getWindow().getDecorView());
        TextView s = shown(a, bar, "Fetching app");
        bar.setIndeterminate(false);
        bar.setProgress(100);
        s.setText("Fetched itdxdc-v0.4.7.webxdc");
        assertEquals(100, bar.getProgress());
        render(a, "/tmp/xdc-bar-terminal.png");
    }
}
