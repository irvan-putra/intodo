package com.instinct.widget.bot;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.net.Uri;
import android.view.View;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowAlertDialog;

/** Local-only render proof (never shipped): the migration import flow's
 *  dialogs and the export's "Bundle saved" confirmation, real Skia pixels
 *  at sdk=32. Every dialog is built by production code (BotImport.launch /
 *  BotActivity.onActivityResult); the test only stages the files, fields
 *  and activity-result the flow already handles. Proves
 *  composition/layout/legibility, NOT device-frame fidelity and NOT the
 *  Delta Chat account runtime. */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 32)
public class ImportFlowRenderTest {

    // Local-only migration pixels. Intentionally outside CI artifact globs.
    private static final String OUT = "/tmp/mig/render-out/";

    private static void render(View v, String outPath, int minH) throws Exception {
        int w = 420;
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                  View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int h = Math.max(v.getMeasuredHeight(), minH);
        v.layout(0, 0, w, h);
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.drawColor(0xFFEEEEEA); // light backdrop so dialog bounds read
        v.draw(c);
        File out = new File(outPath);
        File parent = out.getParentFile();
        assertTrue("render directory available", parent.isDirectory() || parent.mkdirs());
        try (FileOutputStream f = new FileOutputStream(out)) {
            b.compress(Bitmap.CompressFormat.PNG, 100, f);
        }
        assertTrue("render written: " + outPath, out.length() > 5000);
    }

    private static AlertDialog latestDialog() {
        AlertDialog d = ShadowAlertDialog.getLatestAlertDialog();
        assertTrue("dialog shown", d != null && d.isShowing());
        return d;
    }

    private static BotImport importerOf(BotActivity a) throws Exception {
        Field f = BotActivity.class.getDeclaredField("importer");
        f.setAccessible(true);
        return (BotImport) f.get(a);
    }

    @Test
    public void renderImportIntroDialog() throws Exception {
        BotActivity a = Robolectric.buildActivity(BotActivity.class).setup().get();
        // Production entry point behind the "Import bot backup" button.
        importerOf(a).launch();
        AlertDialog d = latestDialog();
        assertEquals("Import bot backup", shadowOf(d).getTitle().toString());
        render(d.getWindow().getDecorView(), OUT + "import-0-intro.png", 600);
    }

    @Test
    public void renderTransferCodeEntryDialog() throws Exception {
        BotActivity a = Robolectric.buildActivity(BotActivity.class).setup().get();
        // The production retry path: a picked bundle cached, launch()
        // re-offers the SAME bundle and asks for the code.
        File picked = File.createTempFile("fixture-bundle", ".dcmig", a.getCacheDir());
        try (FileOutputStream f = new FileOutputStream(picked)) {
            f.write("fixture bundle bytes - not a real export".getBytes("UTF-8"));
        }
        BotImport imp = importerOf(a);
        Field bf = BotImport.class.getDeclaredField("bundleFile");
        bf.setAccessible(true);
        bf.set(imp, picked);
        imp.launch();
        AlertDialog d = latestDialog();
        assertEquals("Transfer code", shadowOf(d).getTitle().toString());
        render(d.getWindow().getDecorView(), OUT + "import-1-code-entry.png", 600);
    }

    @Test
    public void renderBundleSavedDialog() throws Exception {
        BotActivity a = Robolectric.buildActivity(BotActivity.class).setup().get();
        // Stage what a finished export leaves: the bundle the user saves.
        File bundle = File.createTempFile("intodo-migration", ".dcmig", a.getCacheDir());
        try (FileOutputStream f = new FileOutputStream(bundle)) {
            f.write("fixture dcmig bundle bytes - not a real export".getBytes("UTF-8"));
        }
        Field pbp = BotActivity.class.getDeclaredField("pendingBundlePath");
        pbp.setAccessible(true);
        pbp.set(a, bundle.getAbsolutePath());
        // The document picker returns a Uri; register a real output file
        // for a fixture content Uri so the production copy path runs.
        File saved = File.createTempFile("saved-intodo-migration", ".dcmig", a.getCacheDir());
        saved.delete();
        Uri uri = Uri.parse("content://fixture.save/intodo-migration.dcmig");
        FileOutputStream savedOut = new FileOutputStream(saved);
        shadowOf(a.getContentResolver()).registerOutputStream(uri, savedOut);
        Intent data = new Intent().setData(uri);
        Field req = BotActivity.class.getDeclaredField("REQ_SAVE_BUNDLE");
        req.setAccessible(true);
        Method m = BotActivity.class.getDeclaredMethod(
            "onActivityResult", int.class, int.class, Intent.class);
        m.setAccessible(true);
        m.invoke(a, req.getInt(null), android.app.Activity.RESULT_OK, data);
        AlertDialog d = latestDialog();
        assertEquals("Bundle saved", shadowOf(d).getTitle().toString());
        render(d.getWindow().getDecorView(), OUT + "export-3-bundle-saved.png", 700);
        savedOut.close();
        // The production copy must have landed and the pending path cleared.
        assertTrue("saved bundle has content", saved.length() > 0);
        assertNull("pending bundle path cleared", pbp.get(a));
    }
}
