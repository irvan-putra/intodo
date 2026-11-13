package com.instinct.widget.bot;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

/**
 * Renders BotActivity's UI through Robolectric native graphics (real Skia on
 * the JVM). sdk=32 avoids the POST_NOTIFICATIONS prompt; onCreate never starts
 * Python, so the render touches no core/account/network. Small-screen height
 * (1600px at xxhdpi ~ 360x533dp) proves the setup text and buttons SCROLL OUT
 * OF THE WAY so the log can fill the screen (user request); all controls stay
 * inside the one scroll container.
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 32, qualifiers = "xxhdpi")
public class BotActivityRenderTest {

    private static ScrollView findScrollView(View v) {
        if (v instanceof ScrollView) return (ScrollView) v;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                ScrollView r = findScrollView(g.getChildAt(i));
                if (r != null) return r;
            }
        }
        return null;
    }

    @Test
    public void controlsScrollAwayFromLog() throws Exception {
        Activity a = Robolectric.buildActivity(BotActivity.class).setup().get();
        View root = a.getWindow().getDecorView();
        int w = 1080, h = 1600; // small-screen height on purpose
        root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                     View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, w, h);

        ScrollView sv = findScrollView(root);
        assertTrue("one ScrollView present", sv != null);
        assertEquals("single scroll child", 1, sv.getChildCount());
        LinearLayout content = (LinearLayout) sv.getChildAt(0);

        int buttons = 0;
        for (int i = 0; i < content.getChildCount(); i++) {
            if (content.getChildAt(i) instanceof Button) buttons++;
        }
        assertEquals("all 8 control buttons inside the scroll container", 8, buttons);
        assertTrue("setup text is the first scrolled child",
                content.getChildAt(0) instanceof TextView
                        && !(content.getChildAt(0) instanceof Button));

        // seed a SYNTHETIC long log (invented date/ids/counts, no session
        // provenance): last child is the log TextView
        TextView log = (TextView) content.getChildAt(content.getChildCount() - 1);
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 60; i++) {
            sb.append("01 Jan 00:0").append(i % 10).append(" bot: log line ").append(i)
              .append(" restore into 777: replayed 42 op(s) (synthetic fixture)\n");
        }
        log.setText(sb.toString());

        root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                     View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, w, h);

        int range = Math.max(0, content.getHeight() - sv.getHeight());
        assertTrue("content taller than viewport: controls can scroll away, range=" + range,
                range > h / 2);

        // top position: controls visible
        Bitmap top = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        root.draw(new Canvas(top));
        File f1 = new File("/tmp/integ-bot-scroll-top.png");
        try (FileOutputStream f = new FileOutputStream(f1)) {
            top.compress(Bitmap.CompressFormat.PNG, 100, f);
        }

        // scrolled to the bottom: log tail fills the screen
        sv.scrollTo(0, range);
        Bitmap bottom = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        root.draw(new Canvas(bottom));
        File f2 = new File("/tmp/integ-bot-scroll-bottom.png");
        try (FileOutputStream f = new FileOutputStream(f2)) {
            bottom.compress(Bitmap.CompressFormat.PNG, 100, f);
        }
        assertEquals("reached the log tail", range, sv.getScrollY());

        assertTrue("top render written", f1.length() > 10000);
        assertTrue("bottom render written", f2.length() > 10000);
    }
}
