package com.instinct.widget;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.RemoteViews;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

/**
 * Regression guard for the real RemoteViews host inflation path.
 *
 * A bare android.view.View (or any class outside the RemoteViews whitelist)
 * makes the LAUNCHER fail widget inflation with "Couldn't add widget", while
 * a plain LayoutInflater in unit tests inflates it happily. RemoteViews.apply
 * runs the same framework class filter the launcher hits, so building the
 * widget's RemoteViews exactly as WidgetProvider does and applying it catches
 * that class of bug in CI. (Introduced after the 0.5.6 widget breakage.)
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 32)
public class WidgetRemoteApplyTest {

    @Test
    public void providerViewsSurviveRealApply() {
        Context ctx = RuntimeEnvironment.getApplication();
        // Mirror WidgetProvider's construction: root/filler/empty click targets,
        // collection template, empty-view visibility.
        RemoteViews views = new RemoteViews(ctx.getPackageName(), R.layout.widget_layout);
        views.setViewVisibility(R.id.widget_empty, View.GONE);
        Intent open = new Intent(ctx, MainActivity.class);
        views.setOnClickPendingIntent(R.id.widget_empty,
                PendingIntent.getActivity(ctx, 2, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        views.setOnClickPendingIntent(R.id.widget_root,
                PendingIntent.getActivity(ctx, 3, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        views.setOnClickPendingIntent(R.id.widget_filler,
                PendingIntent.getActivity(ctx, 4, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        views.setPendingIntentTemplate(R.id.widget_list,
                PendingIntent.getActivity(ctx, 5, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        // Throws (as the launcher would) if any view class is not whitelisted,
        // or any action targets a missing id.
        View inflated = views.apply(ctx, new FrameLayout(ctx));
        org.junit.Assert.assertNotNull(inflated.findViewById(R.id.widget_root));
        org.junit.Assert.assertNotNull(inflated.findViewById(R.id.widget_list));
        org.junit.Assert.assertNotNull(inflated.findViewById(R.id.widget_filler));
    }

    @Test
    public void rowLayoutsSurviveRealApply() {
        Context ctx = RuntimeEnvironment.getApplication();
        // RemoteViewsFactory rows inflate through the same host filter.
        for (int layout : new int[]{R.layout.widget_item, R.layout.widget_section}) {
            RemoteViews row = new RemoteViews(ctx.getPackageName(), layout);
            row.setOnClickFillInIntent(
                    layout == R.layout.widget_item ? R.id.item_row : R.id.section_title, new Intent());
            View inflated = row.apply(ctx, new FrameLayout(ctx));
            org.junit.Assert.assertNotNull(inflated);
        }
    }
}
