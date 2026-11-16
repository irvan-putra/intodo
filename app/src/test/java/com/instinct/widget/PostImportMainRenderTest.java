package com.instinct.widget;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import androidx.work.testing.WorkManagerTestInitHelper;
import com.instinct.widget.bot.PrefsFile;
import com.instinct.widget.bot.SharedPrefsFile;
import com.instinct.widget.bot.StagedPrefsApplier;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

/** Local-only render proof (never shipped): the main screen as the import
 *  leaves it - the REAL StagedPrefsApplier applies a fixture IMPORT_PREFS
 *  snapshot (the dcmig/2 settings bundle), then MainActivity renders from
 *  those settings with real Skia pixels at sdk=32. Proves the imported
 *  SETTINGS state renders (feed pairing keys, cached feed); it does NOT
 *  prove the Delta Chat account layer (bot running with the imported
 *  account) - that remains emulator/runtime territory. */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 32)
public class PostImportMainRenderTest {

    // Local-only migration pixels. Intentionally outside CI artifact globs.
    private static final String OUT = "/tmp/mig/render-out/";

    private static final String FEED_JSON =
            "{\"generated_at\":\"2026-11-13T02:00:00Z\",\"sections\":["
            + "{\"id\":\"s1\",\"title\":\"Reminders\",\"items\":["
            + "{\"id\":\"i1\",\"title\":\"Water the plants\",\"note\":\"every morning\",\"status\":\"\"},"
            + "{\"id\":\"i2\",\"title\":\"Read 20 pages\",\"note\":\"\",\"status\":\"in_progress\"}]},"
            + "{\"id\":\"s2\",\"title\":\"Todos\",\"items\":["
            + "{\"id\":\"i3\",\"title\":\"Review test results\",\"note\":\"\",\"status\":\"\"}]}]}";

    @Before
    public void initWorkManager() {
        Context ctx = RuntimeEnvironment.getApplication();
        ctx.getSharedPreferences("instinct_widget", Context.MODE_PRIVATE).edit().clear().commit();
        WorkManagerTestInitHelper.initializeTestWorkManager(ctx);
    }

    @Test
    public void renderMainScreenWithImportedSettings() throws Exception {
        Context ctx = RuntimeEnvironment.getApplication();
        // Fixture IMPORT_PREFS.json in exactly the shape
        // writeExportPrefsSnapshot / import_prepare stage it.
        JSONObject iw = new JSONObject();
        iw.put("priv", "fixture-priv-POSTIMPORT-31c7aa");
        iw.put("pub", "fixture-pub-POSTIMPORT-9f02e4");
        iw.put("feed", FEED_JSON);
        iw.put("feed_gen", "2026-11-13T02:00:00Z");
        iw.put("feed_at", System.currentTimeMillis());
        JSONObject root = new JSONObject();
        root.put("instinct_widget", iw);
        File staged = new File(ctx.getFilesDir(), "IMPORT_PREFS.json");
        try (FileWriter w = new FileWriter(staged)) { w.write(root.toString()); }
        File prior = new File(ctx.getFilesDir(), "IMPORT_PRIOR_PREFS.json");

        // The REAL production apply path (BotImport.kt).
        StagedPrefsApplier applier = new StagedPrefsApplier(staged, prior,
            name -> new SharedPrefsFile(ctx.getSharedPreferences(name, Context.MODE_PRIVATE)));
        applier.writePriorSnapshot();
        assertNull("staged settings apply failed", applier.applyStaged());
        assertFalse("staging file must be consumed", staged.exists());
        // The applied settings must be what MainActivity will read.
        assertTrue(ctx.getSharedPreferences("instinct_widget", Context.MODE_PRIVATE)
            .getString("pub", "").equals("fixture-pub-POSTIMPORT-9f02e4"));

        Activity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        View rootView = a.getWindow().getDecorView();
        int w = 1080, h = 1920;
        rootView.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                         View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
        rootView.layout(0, 0, w, h);
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        rootView.draw(new Canvas(b));
        File out = new File(OUT + "main-post-import.png");
        File parent = out.getParentFile();
        assertTrue("render directory available", parent.isDirectory() || parent.mkdirs());
        try (FileOutputStream f = new FileOutputStream(out)) {
            b.compress(Bitmap.CompressFormat.PNG, 100, f);
        }
        assertTrue("render written", out.length() > 10000);
    }
}
