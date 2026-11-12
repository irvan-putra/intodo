package com.instinct.widget.canvas;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Hosts the review canvas: a small header, the canvas fitted to the available
 * screen height (ReviewCanvasView caps itself at 600dp, min 320dp), and the
 * gesture caption. Programmatic layout, no new resources. Purely additive to
 * the app - the widget, list, pairing and sync paths are untouched.
 */
public class ReviewCanvasActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        float d = getResources().getDisplayMetrics().density;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#101828"));

        TextView header = new TextView(this);
        header.setText("Review canvas");
        header.setTextColor(Color.parseColor("#F2F4F7"));
        header.setTextSize(18);
        header.setPadding((int) (16 * d), (int) (14 * d), (int) (16 * d), 0);
        root.addView(header, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView sub = new TextView(this);
        sub.setText("Interactive design prototype - layout persists on this device only");
        sub.setTextColor(Color.parseColor("#98A2B3"));
        sub.setTextSize(12);
        sub.setPadding((int) (16 * d), (int) (2 * d), (int) (16 * d), (int) (8 * d));
        root.addView(sub, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        ReviewCanvasView canvas = new ReviewCanvasView(this);
        LinearLayout.LayoutParams cv = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        cv.leftMargin = (int) (16 * d);
        cv.rightMargin = (int) (16 * d);
        root.addView(canvas, cv);

        TextView caption = new TextView(this);
        caption.setText("Drag the background to pan, drag a card to move it, pinch or use + and - to zoom, tap a card's Focus to read it. The canvas is bounded, not infinite.");
        caption.setTextColor(Color.parseColor("#667085"));
        caption.setTextSize(11);
        caption.setGravity(Gravity.START);
        caption.setPadding((int) (16 * d), (int) (8 * d), (int) (16 * d), (int) (12 * d));
        root.addView(caption, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        setContentView(root);
    }
}
