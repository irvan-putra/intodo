package com.instinct.widget;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.widget.RemoteViews;

public class WidgetProvider extends AppWidgetProvider {
    public static final String ACTION_OPEN_ITEM = "com.instinct.widget.OPEN_ITEM";

    @Override
    public void onUpdate(Context context, AppWidgetManager awm, int[] ids) {
        FeedSyncScheduler.schedule(context);
        for (int id : ids) updateOne(context, awm, id);
    }

    /** Refreshes list contents and footer on every widget; used after background syncs. */
    public static void refreshAll(Context context) {
        AppWidgetManager awm = AppWidgetManager.getInstance(context);
        int[] ids = awm.getAppWidgetIds(new ComponentName(context, WidgetProvider.class));
        if (ids.length == 0) return;
        awm.notifyAppWidgetViewDataChanged(ids, R.id.widget_list);
        updateAll(context);
    }

    public static void updateAll(Context context) {
        AppWidgetManager awm = AppWidgetManager.getInstance(context);
        int[] ids = awm.getAppWidgetIds(new ComponentName(context, WidgetProvider.class));
        for (int id : ids) updateOne(context, awm, id);
    }

    static void updateOne(Context context, AppWidgetManager awm, int id) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_layout);
        Intent svc = new Intent(context, WidgetService.class);
        svc.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id);
        svc.setData(android.net.Uri.parse("instinctwidget://widget/" + id));
        views.setRemoteAdapter(R.id.widget_list, svc);

        boolean hasData = Prefs.feed(context) != null;
        views.setViewVisibility(R.id.widget_empty, hasData ? View.GONE : View.VISIBLE);
        // no chrome left to tap: the empty view itself opens the app
        Intent openEmpty = new Intent(context, MainActivity.class);
        views.setOnClickPendingIntent(R.id.widget_empty,
                PendingIntent.getActivity(context, id * 2, openEmpty, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        // the root background covers the padding ring around the collection
        Intent openRoot = new Intent(context, MainActivity.class);
        views.setOnClickPendingIntent(R.id.widget_root,
                PendingIntent.getActivity(context, id * 2 + 2, openRoot, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        // the filler sibling covers the trailing area below the last row
        Intent openFiller = new Intent(context, MainActivity.class);
        views.setOnClickPendingIntent(R.id.widget_filler,
                PendingIntent.getActivity(context, id * 2 + 3, openFiller, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));

        Intent open = new Intent(context, MainActivity.class);
        views.setPendingIntentTemplate(R.id.widget_list,
                PendingIntent.getActivity(context, id * 2 + 1, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));

        awm.updateAppWidget(id, views);
    }
}
