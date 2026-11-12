package com.instinct.widget.bot;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowAlertDialog;

/** Local-only render proof (never shipped): the publish result dialog's
 *  final states, real Skia pixels at sdk=32. */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 32)
public class PublishDialogRenderTest {

    private static void renderDialog(Activity a, String doneJson, String name,
                                     String outPath) throws Exception {
        // the result poll starts on a 1s handler delay - advance the looper
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
            .idleFor(1500, java.util.concurrent.TimeUnit.MILLISECONDS);
        AlertDialog d = ShadowAlertDialog.getLatestAlertDialog();
        assertTrue("dialog shown", d != null && d.isShowing());
        // Prove the rendered dialog is the REAL production composition, not
        // a duplicated test copy: its text must equal composePublishSummary.
        CharSequence shown = org.robolectric.Shadows.shadowOf(d).getMessage();
        assertEquals(BotActivity.composePublishSummary(new org.json.JSONObject(doneJson), name),
                     shown == null ? null : shown.toString());
        // Phone-width PORTRAIT frame (a real phone dialog, not a wide
        // desktop-landscape canvas).
        View dv = d.getWindow().getDecorView();
        int w = 420;
        dv.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                   View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int h = Math.max(dv.getMeasuredHeight(), 760);
        dv.layout(0, 0, w, h);
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        dv.draw(new Canvas(b));
        File out = new File(outPath);
        try (FileOutputStream f = new FileOutputStream(out)) {
            b.compress(Bitmap.CompressFormat.PNG, 100, f);
        }
        assertTrue("render written: " + outPath, out.length() > 5000);
    }

    private static Activity activityWithDone(String doneJson, String name) throws Exception {
        Activity a = Robolectric.buildActivity(BotActivity.class).get();
        File dir = a.getFilesDir();
        try (FileWriter w = new FileWriter(new File(dir, "PUBLISH_REQ"))) { w.write("x"); }
        try (FileWriter w = new FileWriter(new File(dir, "PUBLISH_DONE"))) { w.write(doneJson); }
        a.getSharedPreferences("bot", 0).edit()
            .putString("pending_publish_token", "tok")
            .putString("pending_publish_name", name)
            .apply();
        return Robolectric.buildActivity(BotActivity.class).setup().get();
    }

    @Test
    public void renderAdoptedRecovery() throws Exception {
        String j = "{\"published\":{\"13\":{\"msg_id\":4242,\"ops_replayed\":3,\"adopted\":true}},"
            + "\"errors\":{},\"partial\":{},\"request\":\"tok\",\"sha256\":\"ab\"}";
        Activity a = activityWithDone(j, null);
        renderDialog(a, j, null, "/tmp/dialog-adopted.png");
    }

    @Test
    public void renderHistoryPending() throws Exception {
        String j = "{\"published\":{\"13\":{\"msg_id\":4242,\"ops_replayed\":3,\"adopted\":true,"
            + "\"ingest_pending\":true}},\"errors\":{},\"partial\":{},"
            + "\"request\":\"tok\",\"sha256\":\"ab\"}";
        Activity a = activityWithDone(j, null);
        renderDialog(a, j, null, "/tmp/dialog-history-pending.png");
    }

    @Test
    public void renderPartialRetry() throws Exception {
        String j = "{\"published\":{},\"errors\":{},"
            + "\"partial\":{\"13\":{\"msg_id\":4242,\"error\":\"relay down\"}},"
            + "\"request\":\"tok\",\"sha256\":\"ab\"}";
        Activity a = activityWithDone(j, null);
        renderDialog(a, j, null, "/tmp/dialog-partial.png");
    }

    @Test
    public void renderPlainErrorWithName() throws Exception {
        String j = "{\"published\":{},"
            + "\"errors\":{\"13\":\"manifest version unresolved in /data/user/0/x/files/publish-abc.xdc\"},"
            + "\"partial\":{},\"request\":\"tok\",\"sha256\":\"ab\"}";
        Activity a = activityWithDone(j, "itdxdc-v0.4.3-final.xdc");
        renderDialog(a, j, "itdxdc-v0.4.3-final.xdc", "/tmp/dialog-plain-error.png");
    }
}
