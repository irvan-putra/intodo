package com.instinct.widget.bot;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;
import java.io.FileWriter;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

/**
 * Device-free proof of the v3 log-view behavior on BotActivity:
 *  - opening the Logs section lands on the tail (freshest lines),
 *  - only the last ~4000 chars are shown, starting on a full line,
 *  - a vertical drag inside the log box scrolls the log while it can scroll,
 *    and falls through to the outer page scroll once the log hits its end.
 * Synthetic fixtures only: no account, network or core starts.
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 32, qualifiers = "xxhdpi")
public class BotActivityLogScrollTest {

    private static Activity activityWithLog(String log) throws Exception {
        org.robolectric.android.controller.ActivityController<BotActivity> controller =
            Robolectric.buildActivity(BotActivity.class);
        Activity activity = controller.get();
        if (log != null) {
            try (FileWriter w = new FileWriter(new File(activity.getFilesDir(), "bot.log"))) {
                w.write(log);
            }
        }
        controller.setup();
        Shadows.shadowOf(android.os.Looper.getMainLooper())
            .idleFor(10, java.util.concurrent.TimeUnit.MILLISECONDS);
        return activity;
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

    private static void layout(Activity activity) {
        View root = activity.getWindow().getDecorView();
        root.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                     View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, 1080, 1920);
    }

    private static String logLines(int count) {
        StringBuilder log = new StringBuilder();
        for (int i = 1; i <= count; i++) {
            log.append("01 Jan 00:0").append(i % 10)
                .append(" synthetic log line ").append(i)
                .append(" - fixture only, no user data\n");
        }
        return log.toString();
    }

    /** The log box is the ScrollView sibling directly after the "Logs" section header. */
    private static ScrollView findLogScroll(View decor) {
        ViewGroup content = decor.findViewById(android.R.id.content);
        ScrollView outer = (ScrollView) content.getChildAt(0);
        ViewGroup root = (ViewGroup) outer.getChildAt(0);
        for (int i = 0; i + 1 < root.getChildCount(); i++) {
            View child = root.getChildAt(i);
            if (child instanceof TextView) {
                CharSequence text = ((TextView) child).getText();
                if (text != null && text.toString().startsWith("Logs")
                        && root.getChildAt(i + 1) instanceof ScrollView) {
                    return (ScrollView) root.getChildAt(i + 1);
                }
            }
        }
        return null;
    }

    private static ScrollView outerScroll(Activity activity) {
        ViewGroup content = activity.getWindow().getDecorView().findViewById(android.R.id.content);
        return (ScrollView) content.getChildAt(0);
    }

    private static TextView openLogs(Activity activity) throws Exception {
        View decor = activity.getWindow().getDecorView();
        TextView logsHeader = findText(decor, "Logs");
        assertNotNull("Logs header exists", logsHeader);
        ScrollView logScroll = findLogScroll(decor);
        assertNotNull("log scroll box exists", logScroll);
        logsHeader.performClick();
        Shadows.shadowOf(android.os.Looper.getMainLooper())
            .idleFor(50, java.util.concurrent.TimeUnit.MILLISECONDS);
        layout(activity);
        Shadows.shadowOf(android.os.Looper.getMainLooper())
            .idleFor(50, java.util.concurrent.TimeUnit.MILLISECONDS);
        assertEquals("log box visible after opening", View.VISIBLE, logScroll.getVisibility());
        TextView logView = (TextView) logScroll.getChildAt(0);
        assertNotNull("log view populated", logView);
        return logView;
    }

    /**
     * Drags a finger vertically inside the visible part of target, dispatching
     * through dispatchRoot (the outer ScrollView) so the real intercept path runs:
     * outer.onInterceptTouchEvent -> the log box's disallow-intercept listener ->
     * inner ScrollView onTouchEvent. Coordinates are translated into dispatchRoot's
     * local space.
     */
    private static void dragY(View dispatchRoot, View target, float dy) {
        int[] rootLoc = new int[2];
        dispatchRoot.getLocationOnScreen(rootLoc);
        int[] loc = new int[2];
        target.getLocationOnScreen(loc);
        int[] screenRoot = new int[2];
        target.getRootView().getLocationOnScreen(screenRoot);
        float screenTop = Math.max(loc[1], screenRoot[1]) + 20;
        float screenBottom = Math.min(loc[1] + target.getHeight(), screenRoot[1] + target.getRootView().getHeight()) - 20;
        if (screenBottom - screenTop < 120) {
            throw new AssertionError("target has too little visible area on screen: "
                + screenTop + ".." + screenBottom);
        }
        float fromScreenY = dy < 0 ? screenBottom + dy - 10 : screenTop - dy + 10;
        fromScreenY = Math.min(Math.max(fromScreenY, screenTop), screenBottom);
        float x = loc[0] + target.getWidth() / 2f - rootLoc[0];
        float fromY = fromScreenY - rootLoc[1];
        long t = android.os.SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, fromY, 0);
        dispatchRoot.dispatchTouchEvent(down);
        down.recycle();
        int steps = 8;
        for (int i = 1; i <= steps; i++) {
            MotionEvent move = MotionEvent.obtain(t, t + i * 10, MotionEvent.ACTION_MOVE,
                x, fromY + dy * i / steps, 0);
            dispatchRoot.dispatchTouchEvent(move);
            move.recycle();
        }
        MotionEvent up = MotionEvent.obtain(t, t + 100, MotionEvent.ACTION_UP, x, fromY + dy, 0);
        dispatchRoot.dispatchTouchEvent(up);
        up.recycle();
    }

    @Test
    public void openingLogsLandsOnTail() throws Exception {
        Activity activity = activityWithLog(logLines(70));
        layout(activity);
        TextView logView = openLogs(activity);
        ScrollView inner = (ScrollView) logView.getParent();
        assertNotNull(inner);
        assertTrue("log content taller than the box", logView.getHeight() > inner.getHeight());
        int maxScroll = logView.getHeight() - inner.getHeight();
        assertEquals("opens scrolled to the tail", maxScroll, inner.getScrollY());
    }

    @Test
    public void logViewShowsTailWindowOnly() throws Exception {
        Activity activity = activityWithLog(logLines(200)); // ~9 KB, beyond the 4000-char window
        layout(activity);
        TextView logView = openLogs(activity);
        String shown = logView.getText().toString();
        assertTrue("tail line visible", shown.contains("synthetic log line 200"));
        assertFalse("oldest lines truncated away", shown.contains("synthetic log line 1 "));
        assertTrue("window starts on a full line",
            shown.matches("(?s)^== bot\\.log ==\\n01 Jan.*"));
    }

    @Test
    public void emptyLogExplainsNothingYet() throws Exception {
        Activity activity = activityWithLog(null);
        layout(activity);
        TextView logView = openLogs(activity);
        assertEquals("(nothing yet)", logView.getText().toString());
    }

    @Test
    public void innerScrollsWhileItCanOuterTakesOverAtEnd() throws Exception {
        Activity activity = activityWithLog(logLines(70));
        layout(activity);
        TextView logView = openLogs(activity);
        ScrollView inner = (ScrollView) logView.getParent();
        ScrollView outer = outerScroll(activity);
        assertNotNull(inner);
        assertNotNull(outer);

        // Bring the log box into the viewport: it sits at the bottom of a long page,
        // so a small outer scroll leaves it off-screen and the drag misses it entirely.
        inner.scrollTo(0, 0);
        int outerMax = Math.max(0, outer.getChildAt(0).getHeight() - outer.getHeight());
        int bringIntoView = Math.min(outerMax, Math.max(0, inner.getTop() - 300));
        outer.scrollTo(0, bringIntoView);
        layout(activity);
        int outerBefore = outer.getScrollY();
        // Case A: finger moves up (content scrolls down); inner can scroll -> inner moves, outer stays.
        dragY(outer, inner, -200);
        assertTrue("inner consumed the downward scroll", inner.getScrollY() > 0);
        assertEquals("outer did not steal the gesture", outerBefore, outer.getScrollY());

        // Case B: inner back at top, finger moves down (content scrolls up); inner cannot ->
        // the gesture falls through to the outer page scroll.
        inner.scrollTo(0, 0);
        layout(activity);
        assertTrue("outer has room to scroll up", outer.getScrollY() > 0);
        int outerBeforeB = outer.getScrollY();
        dragY(outer, inner, 200);
        assertEquals("inner stayed at its top", 0, inner.getScrollY());
        assertTrue("outer took over the gesture", outer.getScrollY() < outerBeforeB);

        // Drain posted runnables so the looper is clean for the next test.
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
    }

    /** setText resets selection: refresh must skip unchanged text and never yank an active selection. */
    @Test
    public void refreshKeepsActiveSelectionAndSkipsUnchangedText() throws Exception {
        Activity activity = activityWithLog(logLines(80));
        layout(activity);
        TextView logView = openLogs(activity);

        logView.setText(logView.getText(), TextView.BufferType.SPANNABLE);
        String shown = logView.getText().toString();
        android.text.Selection.setSelection((android.text.Spannable) logView.getText(), 0, 5);
        assertTrue("selection active", logView.hasSelection());

        // Unchanged log: refresh must not touch the text or the selection.
        invokeRefresh(activity);
        assertEquals("unchanged log text left alone", shown, logView.getText().toString());
        assertTrue("selection survives unchanged refresh", logView.hasSelection());

        // Changed log while selecting: the update is deferred, selection survives.
        try (FileWriter w = new FileWriter(new File(activity.getFilesDir(), "bot.log"), true)) {
            w.write("01 Jan 00:09 synthetic log line 999 - fixture only, no user data\n");
        }
        invokeRefresh(activity);
        assertEquals("no yank while selecting", shown, logView.getText().toString());
        assertTrue("selection survives deferred refresh", logView.hasSelection());

        // Selection cleared: the pending update lands.
        android.text.Selection.removeSelection((android.text.Spannable) logView.getText());
        invokeRefresh(activity);
        assertTrue("refresh lands after selection cleared",
            logView.getText().toString().contains("synthetic log line 999"));

        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
    }

    /** The nested handoff ignores child disallow requests - except while a selection is active. */
    @Test
    public void selectionDragDisallowIsHonoredOnlyWhileSelecting() throws Exception {
        Activity activity = activityWithLog(logLines(80));
        layout(activity);
        TextView logView = openLogs(activity);
        ViewGroup inner = (ViewGroup) logView.getParent();
        ViewGroup outer = outerScroll(activity);

        // No selection: the child's request stays ignored, so nested scrolling wins.
        inner.requestDisallowInterceptTouchEvent(true);
        assertFalse("inner flag not set without selection", disallowIntercept(inner));
        assertFalse("outer flag not set without selection", disallowIntercept(outer));

        // Active selection: the request is honored so selection drags are not stolen.
        logView.setText(logView.getText(), TextView.BufferType.SPANNABLE);
        android.text.Selection.setSelection((android.text.Spannable) logView.getText(), 0, 5);
        inner.requestDisallowInterceptTouchEvent(true);
        assertTrue("inner flag set while selecting", disallowIntercept(inner));
        assertTrue("outer flag set while selecting", disallowIntercept(outer));

        inner.requestDisallowInterceptTouchEvent(false);
        outer.requestDisallowInterceptTouchEvent(false);
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
    }

    private static void invokeRefresh(Activity activity) throws Exception {
        java.lang.reflect.Method m = BotActivity.class.getDeclaredMethod("refresh");
        m.setAccessible(true);
        m.invoke(activity);
    }

    private static boolean disallowIntercept(ViewGroup group) throws Exception {
        java.lang.reflect.Field f = ViewGroup.class.getDeclaredField("mGroupFlags");
        f.setAccessible(true);
        int flags = f.getInt(group);
        return (flags & 0x80000) != 0; // ViewGroup.FLAG_DISALLOW_INTERCEPT
    }
}
