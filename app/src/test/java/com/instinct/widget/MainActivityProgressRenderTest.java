package com.instinct.widget;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;
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

/**
 * Pixel proof for the download/feed progress UI on a 360x640dp phone.
 * The progress formatting lives in MainActivity's anonymous callbacks, which
 * tests cannot reach; this test drives the SAME views with the SAME
 * expressions (including UpdateInstall.mb) the callbacks use, so the pixels
 * show the real views in every documented state. The callback glue itself is
 * covered by source review, not by this test.
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 32, qualifiers = "xxhdpi")
public class MainActivityProgressRenderTest {

    @Before
    public void pair() {
        // Robolectric does not run the androidx.startup initializer; the app's
        // onCreate schedules feed auto-sync via WorkManager.getInstance().
        androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(
                RuntimeEnvironment.getApplication());
        Prefs.saveKeys(RuntimeEnvironment.getApplication(), "test-private-key", "test-public-key");
    }

    private static Activity activity() {
        return Robolectric.buildActivity(MainActivity.class).setup().get();
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

    /** Mirrors the update-download onProgress expression for a known total. */
    private static void updateKnown(Activity a, long done, long total) {
        ProgressBar bar = a.findViewById(R.id.updateProgress);
        TextView line = a.findViewById(R.id.updateLine);
        bar.setVisibility(View.VISIBLE);
        bar.setIndeterminate(false);
        int pct = (int) (done * 100 / total);
        bar.setProgress(pct);
        line.setVisibility(View.VISIBLE);
        line.setText("downloading update: " + UpdateInstall.mb(done)
                + " / " + UpdateInstall.mb(total) + " (" + pct + "%)");
    }

    private static void updateUnknown(Activity a, long done) {
        ProgressBar bar = a.findViewById(R.id.updateProgress);
        TextView line = a.findViewById(R.id.updateLine);
        bar.setVisibility(View.VISIBLE);
        bar.setIndeterminate(true);
        line.setVisibility(View.VISIBLE);
        line.setText("downloading update: " + UpdateInstall.mb(done));
    }

    private static void feedStage(Activity a, String stage, long done, long total) {
        ProgressBar bar = a.findViewById(R.id.syncProgress);
        TextView lastSync = a.findViewById(R.id.lastSync);
        bar.setVisibility(View.VISIBLE);
        if ("page".equals(stage)) {
            bar.setIndeterminate(false);
            bar.setProgress((int) done);
            lastSync.setText("Syncing: loading feed page " + done + "%");
        } else if (total > 0) {
            bar.setIndeterminate(false);
            int pct = (int) (done * 100 / total);
            bar.setProgress(pct);
            lastSync.setText("Syncing: list data " + UpdateInstall.mb(done)
                    + " / " + UpdateInstall.mb(total) + " (" + pct + "%)");
        } else {
            bar.setIndeterminate(true);
            lastSync.setText("Syncing: list data " + UpdateInstall.mb(done));
        }
    }

    @Test
    public void renderUpdateKnownTotal() throws Exception {
        Activity a = activity();
        updateKnown(a, 12 * 1024 * 1024, 24400000L);
        TextView line = a.findViewById(R.id.updateLine);
        assertEquals("downloading update: 12.0 MB / 23.3 MB (51%)", line.getText().toString());
        render(a, "/tmp/prog-update-known.png");
    }

    @Test
    public void renderUpdateUnknownTotal() throws Exception {
        Activity a = activity();
        updateUnknown(a, 3500 * 1024);
        TextView line = a.findViewById(R.id.updateLine);
        assertEquals("downloading update: 3.4 MB", line.getText().toString());
        render(a, "/tmp/prog-update-unknown.png");
    }

    @Test
    public void renderUpdateResumed() throws Exception {
        // A resumed download reports the SAME view state with the resumed
        // bytes already counted: bar and label include the earlier partial.
        Activity a = activity();
        updateKnown(a, 18 * 1024 * 1024, 24400000L);
        TextView line = a.findViewById(R.id.updateLine);
        assertEquals("downloading update: 18.0 MB / 23.3 MB (77%)", line.getText().toString());
        render(a, "/tmp/prog-update-resumed.png");
    }

    @Test
    public void renderFeedPageStage() throws Exception {
        Activity a = activity();
        feedStage(a, "page", 42, 100);
        TextView lastSync = a.findViewById(R.id.lastSync);
        assertEquals("Syncing: loading feed page 42%", lastSync.getText().toString());
        render(a, "/tmp/prog-feed-page.png");
    }

    @Test
    public void renderFeedDataKnownAndUnknown() throws Exception {
        Activity a = activity();
        feedStage(a, "data", 120 * 1024, 300 * 1024);
        TextView lastSync = a.findViewById(R.id.lastSync);
        assertEquals("Syncing: list data 120 KB / 300 KB (40%)", lastSync.getText().toString());
        render(a, "/tmp/prog-feed-data-known.png");
        feedStage(a, "data", 84 * 1024, -1);
        assertEquals("Syncing: list data 84 KB", lastSync.getText().toString());
        render(a, "/tmp/prog-feed-data-unknown.png");
    }

    @Test
    public void renderTerminal() throws Exception {
        Activity a = activity();
        updateKnown(a, 24400000L, 24400000L);
        feedStage(a, "page", 100, 100);
        // every terminal path hides both bars
        ProgressBar up = a.findViewById(R.id.updateProgress);
        ProgressBar sp = a.findViewById(R.id.syncProgress);
        TextView line = a.findViewById(R.id.updateLine);
        up.setVisibility(View.GONE);
        sp.setVisibility(View.GONE);
        line.setText("update downloaded - Android will ask you to confirm the install");
        // onDone restores the footer through refreshStatus(); mirror that here
        TextView lastSync = a.findViewById(R.id.lastSync);
        lastSync.setText(SyncStatusText.build(a, "Tap Sync now"));
        render(a, "/tmp/prog-terminal.png");
        assertEquals(View.GONE, up.getVisibility());
        assertEquals(View.GONE, sp.getVisibility());
    }
}
