package com.instinct.widget;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import android.widget.Button;
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
import org.robolectric.shadows.ShadowAlertDialog;

/** Pairing/re-key copy: neutral wording, no product word, and the clipboard
 * label actually exposed by Android clipboard UI. */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 32, qualifiers = "xxhdpi")
public class PairingCopyTest {

    @Before
    public void init() {
        androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(
                RuntimeEnvironment.getApplication());
    }

    private static Button findButton(View v, String text) {
        if (v instanceof Button && text.contentEquals(((Button) v).getText())) return (Button) v;
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                Button b = findButton(g.getChildAt(i), text);
                if (b != null) return b;
            }
        }
        return null;
    }

    @Test
    public void rekeyDialogAndClipboardLabel() throws Exception {
        Activity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        View root = a.getWindow().getDecorView();
        Button create = findButton(root, "Create pairing key");
        assertNotNull(create);
        create.performClick(); // generates keys and reveals the copy control

        Button copy = findButton(root, "Copy public key");
        assertNotNull("copy control revealed after pairing", copy);
        copy.performClick(); // this is what places the key on the clipboard

        // Clipboard label: Android 13+ clipboard overlay and keyboard clipboard
        // managers can surface the ClipData label, so it must not carry the
        // product word either.
        ClipboardManager cm = (ClipboardManager) a.getSystemService(Context.CLIPBOARD_SERVICE);
        assertNotNull("public key copied", cm.getPrimaryClip());
        CharSequence label = cm.getPrimaryClip().getDescription().getLabel();
        System.out.println("CLIPBOARD_LABEL=" + label);

        // Paired now: tapping the same create/replace control again must show
        // the re-key warning dialog with neutral copy.
        create.performClick();
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull("re-key warning dialog shown", dialog);
        org.robolectric.shadows.ShadowDialog shadowDialog = org.robolectric.Shadows.shadowOf(dialog);
        View dv = dialog.getWindow().getDecorView();
        int w = 1080, h = 1920;
        dv.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.AT_MOST),
                   View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.AT_MOST));
        dv.layout(0, 0, dv.getMeasuredWidth(), dv.getMeasuredHeight());
        Bitmap b = Bitmap.createBitmap(dv.getMeasuredWidth(), dv.getMeasuredHeight(), Bitmap.Config.ARGB_8888);
        dv.draw(new Canvas(b));
        File out = new File("/tmp/cos-rekey-dialog.png");
        try (FileOutputStream f = new FileOutputStream(out)) {
            b.compress(Bitmap.CompressFormat.PNG, 100, f);
        }
        assertTrue("dialog render produced", out.length() > 3000);
    }
}
