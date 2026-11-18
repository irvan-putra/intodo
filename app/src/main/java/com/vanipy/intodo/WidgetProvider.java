package com.vanipy.intodo;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.RemoteViews;

public class WidgetProvider extends AppWidgetProvider {
    public static final String ACTION_OPEN_ITEM = "com.vanipy.intodo.OPEN_ITEM";

    @Override
    public void onUpdate(Context context, AppWidgetManager awm, int[] ids) {
        FeedSyncScheduler.schedule(context);
        for (int id : ids) updateOne(context, awm, id);
    }

    @Override
    public void onAppWidgetOptionsChanged(Context context, AppWidgetManager awm, int id, Bundle options) {
        super.onAppWidgetOptionsChanged(context, awm, id, options);
        updateOne(context, awm, id);
        awm.notifyAppWidgetViewDataChanged(id, R.id.widget_list);
    }

    static boolean summaryForSize(int widthDp, int heightDp) {
        // Counter only at one column; two columns and wider get real list content.
        return widthDp < 100;
    }

    static int itemCount(String json) {
        if (json == null) return 0;
        try {
            int n = 0;
            for (FeedParser.Section section : FeedParser.parse(json).sections) n += section.items.size();
            return n;
        } catch (Exception ignored) { return 0; }
    }

    static int paddingDpForHeight(int heightDp) {
        return heightDp > 110 ? 10 : 4;
    }

    /** Refreshes list contents and footer on every widget; used after background syncs. */
    public static void refreshAll(Context context) {
        AppWidgetManager awm = AppWidgetManager.getInstance(context);
        int[] ids = awm.getAppWidgetIds(new ComponentName(context, WidgetProvider.class));
        if (ids.length == 0) return;
        awm.notifyAppWidgetViewDataChanged(ids, R.id.widget_list);
        updateAll(context);
    }

    /** Blank each launcher snapshot before a privacy-sensitive mode switch. */
    public static void blankAll(Context c) {
        AppWidgetManager awm = AppWidgetManager.getInstance(c);
        for (int id : awm.getAppWidgetIds(new ComponentName(c, WidgetProvider.class))) {
            RemoteViews blank = new RemoteViews(c.getPackageName(), R.layout.widget_summary);
            blank.setTextViewText(R.id.widget_summary_count, "...");
            awm.updateAppWidget(id, blank);
        }
    }

    public static void updateAll(Context context) {
        AppWidgetManager awm = AppWidgetManager.getInstance(context);
        int[] ids = awm.getAppWidgetIds(new ComponentName(context, WidgetProvider.class));
        for (int id : ids) updateOne(context, awm, id);
    }

    static void updateOne(Context context, AppWidgetManager awm, int id) {
        Bundle options = awm.getAppWidgetOptions(id);
        int heightDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 48);
        int widthDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 48);
        if (summaryForSize(widthDp, heightDp)) {
            RemoteViews summary = new RemoteViews(context.getPackageName(), R.layout.widget_summary);
            int count = itemCount(Prefs.feed(context));
            summary.setTextViewText(R.id.widget_summary_count, (Prefs.isDemo(context) ? "D " : "") + (count > 999 ? "999+" : String.valueOf(count)));
            summary.setContentDescription(R.id.widget_summary_root, "intodo: " + count + " items. Tap for full list.");
            summary.setOnClickPendingIntent(R.id.widget_summary_root, PendingIntent.getActivity(context,
                    id * 2 + 2, new Intent(context, MainActivity.class), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
            awm.updateAppWidget(id, summary);
            return;
        }
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_layout);
        int paddingPx = Math.round(paddingDpForHeight(heightDp) * context.getResources().getDisplayMetrics().density);
        views.setViewPadding(R.id.widget_root, paddingPx, paddingPx, paddingPx, paddingPx);
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            // Bind an immutable row snapshot in this update. Resizing cannot leave a
            // Samsung launcher waiting on an old RemoteViewsService connection.
            RemoteViews.RemoteCollectionItems.Builder items = new RemoteViews.RemoteCollectionItems.Builder()
                    .setHasStableIds(true).setViewTypeCount(2);
            try {
                String json = Prefs.feed(context);
                if (json != null) {
                    long row = 0;
                    for (FeedParser.Section section : FeedParser.parse(json).sections) {
                        for (FeedParser.Item item : section.items) {
                            items.addItem(row++, WidgetService.itemView(context, item, heightDp <= 110));
                        }
                    }
                }
            } catch (Exception ignored) { }
            views.setRemoteAdapter(R.id.widget_list, items.build());
        } else {
            Intent svc = new Intent(context, WidgetService.class);
            svc.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id);
            svc.setData(android.net.Uri.parse("intodo://widget/" + id + "/" + (heightDp <= 110 ? "compact" : "full") + "/" + Prefs.modeEpoch(context)));
            views.setRemoteAdapter(R.id.widget_list, svc);
        }

        views.setTextViewText(R.id.widget_empty, Prefs.isDemo(context) ? "DEMO - no synthetic items synced" : "No items synced yet");
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
