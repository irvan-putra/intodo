package com.instinct.widget.bot;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
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

/** Pixel-checks the compact BotActivity on a 360x640dp phone viewport.
 * Uses only synthetic state/log fixtures; no account, network or core starts. */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 32, qualifiers = "xxhdpi")
public class BotActivityRenderTest {
    private static Activity activityWithState(String state, boolean running, String log) throws Exception {
        org.robolectric.android.controller.ActivityController<BotActivity> controller = Robolectric.buildActivity(BotActivity.class);
        Activity activity = controller.get();
        File dir = activity.getFilesDir();
        if (state != null) {
            try (FileWriter w = new FileWriter(new File(dir, "bot_state.json"))) { w.write(state); }
        }
        if (running) new File(dir, "RUNNING").createNewFile();
        if (log != null) {
            try (FileWriter w = new FileWriter(new File(dir, "bot.log"))) { w.write(log); }
        }
        controller.setup();
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
            .idleFor(10, java.util.concurrent.TimeUnit.MILLISECONDS);
        return activity;
    }

    private static int countButtons(View view) {
        int count = view instanceof Button ? 1 : 0;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) count += countButtons(group.getChildAt(i));
        }
        return count;
    }

    private static TextView findText(View view, String prefix) {
        if (view instanceof TextView) {
            CharSequence text = ((TextView) view).getText();
            if (text != null && text.toString().startsWith(prefix)) return (TextView) view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                TextView found = findText(group.getChildAt(i), prefix);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static void render(Activity activity, String path) throws Exception {
        View root = activity.getWindow().getDecorView();
        int w = 1080, h = 1920; // 360x640dp at xxhdpi
        root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                     View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, w, h);
        Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        root.draw(new Canvas(bitmap));
        File output = new File(path);
        try (FileOutputStream stream = new FileOutputStream(output)) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream);
        }
        assertTrue("render produced: " + path, output.length() > 5000);
    }

    private static String syntheticLog() {
        StringBuilder log = new StringBuilder();
        for (int i = 1; i <= 70; i++) {
            log.append("01 Jan 00:0").append(i % 10)
                .append(" synthetic log line ").append(i)
                .append(" - fixture only, no user data\n");
        }
        return log.toString();
    }

    @Test
    public void compactProvisionedScreenAndExpandedLogs() throws Exception {
        String state = "{\"provisioned\":true,\"bot_addr\":\"bot-fixture@example.invalid\","
            + "\"chats\":{\"fixture-group\":{},\"fixture-dm\":{}}}";
        Activity activity = activityWithState(state, true, syntheticLog());
        View root = activity.getWindow().getDecorView();
        TextView status = findText(root, "Bot: bot-fixture@example.invalid");
        assertNotNull("status card is populated on first refresh", status);
        assertEquals("Bot: bot-fixture@example.invalid\nService: running  ·  Chats served: 2", status.getText().toString());
        TextView setupHeader = findText(root, "Setup & account");
        TextView logsHeader = findText(root, "Logs");
        assertNotNull(setupHeader);
        assertNotNull(logsHeader);
        assertEquals("Setup & account  ▸", setupHeader.getText().toString());
        assertEquals("Logs  ▸", logsHeader.getText().toString());
        assertEquals("all original controls remain available", 8, countButtons(root));
        render(activity, "/tmp/intodo-bot-compact.png");

        logsHeader.performClick();
        assertEquals("Logs  ▾", logsHeader.getText().toString());
        render(activity, "/tmp/intodo-bot-logs-expanded.png");
    }

    @Test
    public void firstRunSetupExpanded() throws Exception {
        Activity activity = activityWithState(null, false, null);
        View root = activity.getWindow().getDecorView();
        TextView status = findText(root, "No bot account yet");
        TextView setupHeader = findText(root, "Setup & account");
        assertNotNull(status);
        assertTrue(status.getText().toString().contains("Service: stopped"));
        assertNotNull(setupHeader);
        assertTrue("first-run setup is available", setupHeader.getText().toString().endsWith("▾"));
        assertEquals("all original controls remain available", 8, countButtons(root));
        render(activity, "/tmp/intodo-bot-setup-expanded.png");
    }
}
