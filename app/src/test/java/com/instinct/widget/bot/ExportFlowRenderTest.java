package com.instinct.widget.bot;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Looper;
import android.view.View;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowAlertDialog;

/** Local-only render proof (never shipped): the migration export flow's
 *  screens, real Skia pixels at sdk=32. Every dialog is built by the
 *  production code path it ships as - the test only stages the
 *  EXPORT_RESULT file the flow already reads, or invokes the production
 *  private method; no view is reconstructed in test code. Proves
 *  composition/layout/legibility, NOT device-frame fidelity. */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 32)
public class ExportFlowRenderTest {

    // Local-only migration pixels. Intentionally outside CI artifact globs.
    private static final String OUT = "/tmp/mig/render-out/";
    // Exactly 44 chars like a real transfer code, unmistakably a fixture.
    private static final String FIXTURE_CODE =
        "FIXTURE0CODE0NOT0REAL0EXPORT0FIXTURE0CODE0XX";

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

    /** Stage EXPORT_RESULT before onCreate, then drive the REAL
     *  resumeFinishedExportWhenReady path (same as process-death resume). */
    private static BotActivity activityWithExportResult(String json) throws Exception {
        BotActivity probe = Robolectric.buildActivity(BotActivity.class).get();
        try (FileWriter w = new FileWriter(new File(probe.getFilesDir(), "EXPORT_RESULT"))) {
            w.write(json);
        }
        BotActivity a = Robolectric.buildActivity(BotActivity.class).setup().get();
        shadowOf(Looper.getMainLooper()).idleFor(2, TimeUnit.SECONDS);
        return a;
    }

    @Test
    public void renderTransferCodeDialog() throws Exception {
        String j = "{\"code\":\"" + FIXTURE_CODE + "\","
            + "\"bundle\":\"/data/user/0/com.instinct.widget/files/export-out/intodo-migration.dcmig\"}";
        activityWithExportResult(j);
        AlertDialog d = latestDialog();
        assertEquals("1/2: one-time transfer code", shadowOf(d).getTitle().toString());
        render(d.getWindow().getDecorView(), OUT + "export-1-transfer-code.png", 600);
    }

    @Test
    public void renderSaveStepDialog() throws Exception {
        String j = "{\"code\":\"" + FIXTURE_CODE + "\","
            + "\"bundle\":\"/data/user/0/com.instinct.widget/files/export-out/intodo-migration.dcmig\"}";
        activityWithExportResult(j);
        AlertDialog d = latestDialog();
        assertEquals("1/2: one-time transfer code", shadowOf(d).getTitle().toString());
        // Production "Next" button leads to the save step.
        d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        shadowOf(Looper.getMainLooper()).idle();
        AlertDialog d2 = latestDialog();
        assertEquals("2/2: save the bundle file", shadowOf(d2).getTitle().toString());
        render(d2.getWindow().getDecorView(), OUT + "export-2-save-step.png", 600);
    }

    @Test
    public void renderExportFailedDialog() throws Exception {
        String j = "{\"error\":\"fixture failure: the core refused the backup (fixture only)\"}";
        activityWithExportResult(j);
        AlertDialog d = latestDialog();
        assertEquals("Export failed", shadowOf(d).getTitle().toString());
        render(d.getWindow().getDecorView(), OUT + "export-failed.png", 500);
    }

    @Test
    public void renderExportConfirmDialog() throws Exception {
        BotActivity probe = Robolectric.buildActivity(BotActivity.class).get();
        // The production guard only exports a provisioned bot.
        try (FileWriter w = new FileWriter(new File(probe.getFilesDir(), "bot_state.json"))) {
            w.write("{\"provisioned\":true,\"fixture\":true}");
        }
        BotActivity a = Robolectric.buildActivity(BotActivity.class).setup().get();
        // The production confirm step behind the Export button; no publish
        // in flight, so the confirm branch shows.
        java.lang.reflect.Method m = BotActivity.class.getDeclaredMethod("startExportFlow");
        m.setAccessible(true);
        m.invoke(a);
        AlertDialog d = latestDialog();
        assertEquals("Export bot + settings", shadowOf(d).getTitle().toString());
        render(d.getWindow().getDecorView(), OUT + "export-0-confirm.png", 700);
    }

    @Test
    public void renderSetupScreenWithExportButton() throws Exception {
        BotActivity a = Robolectric.buildActivity(BotActivity.class).setup().get();
        render(a.getWindow().getDecorView(), OUT + "bot-setup-screen.png", 900);
    }
}
