package com.instinct.widget;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.text.format.DateUtils;
import android.view.View;
import android.widget.RemoteViews;

public class WidgetProvider extends AppWidgetProvider {
    public static final String ACTION_OPEN_ITEM = "com.instinct.widget.OPEN_ITEM";

    @Override
    public void onUpdate(Context context, AppWidgetManager awm, int[] ids) {
        FeedSyncScheduler.schedule(context);
        for (int id : ids) updateOne(context, awm, id);
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

        long at = Prefs.feedAt(context);
        if (at > 0) {
            String gen = MainActivity.shortGen(Prefs.feedGen(context));
            String asOf = gen.isEmpty() ? "" : "as of " + gen + " \u00b7 ";
            views.setTextViewText(R.id.widget_sync_state, asOf + "fetched " + DateUtils.getRelativeTimeSpanString(at));
        } else {
            views.setTextViewText(R.id.widget_sync_state, Prefs.isPaired(context) ? "tap refresh to sync" : "pair in app");
        }
        boolean hasData = Prefs.feed(context) != null;
        views.setViewVisibility(R.id.widget_empty, hasData ? View.GONE : View.VISIBLE);

        Intent refresh = new Intent(context, MainActivity.class);
        refresh.putExtra("sync", true);
        views.setOnClickPendingIntent(R.id.widget_refresh,
                PendingIntent.getActivity(context, id * 2, refresh, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));

        Intent open = new Intent(context, MainActivity.class);
        views.setPendingIntentTemplate(R.id.widget_list,
                PendingIntent.getActivity(context, id * 2 + 1, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));

        awm.updateAppWidget(id, views);
    }
}
