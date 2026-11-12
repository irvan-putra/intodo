package com.instinct.widget.bot;

import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import java.io.File;
import java.io.FileOutputStream;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

/** Local-only render proof (never shipped): real Skia pixels at sdk=32. */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 32)
public class BotActivityRenderTest {

    @Test
    public void renderInitialUi() throws Exception {
        Activity a = Robolectric.buildActivity(BotActivity.class).setup().get();
        View root = a.getWindow().getDecorView();
        int w = 1080, h = 2200;
        root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                     View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, w, h);
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        root.draw(new Canvas(b));
        File out = new File("/tmp/integ-bot.png");
        try (FileOutputStream f = new FileOutputStream(out)) {
            b.compress(Bitmap.CompressFormat.PNG, 100, f);
        }
        assertTrue("render written", out.length() > 10000);
    }
}
