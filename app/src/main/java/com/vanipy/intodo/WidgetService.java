package com.vanipy.intodo;

import android.content.Context;
import android.appwidget.AppWidgetManager;
import android.content.Intent;
import android.view.View;
import android.widget.RemoteViews;
import android.widget.RemoteViewsService;
import java.util.ArrayList;
import java.util.List;

public class WidgetService extends RemoteViewsService {
    @Override
    public RemoteViewsFactory onGetViewFactory(Intent intent) {
        return new Factory(getApplicationContext(), intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID));
    }

    static class Factory implements RemoteViewsService.RemoteViewsFactory {
        private final Context context;
        private volatile List<Object> rows = new ArrayList<>();
        private long rowEpoch = -1;
        private final int widgetId;
        Factory(Context c) { this(c, AppWidgetManager.INVALID_APPWIDGET_ID); }
        Factory(Context c, int id) { context = c; widgetId = id; }
        boolean compact() {
            if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return false;
            return AppWidgetManager.getInstance(context).getAppWidgetOptions(widgetId)
                    .getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 48) <= 110;
        }

        @Override public void onCreate() { onDataSetChanged(); }
        @Override public void onDestroy() { }

        @Override
        public void onDataSetChanged() {
            rowEpoch = Prefs.modeEpoch(context);
            List<Object> next = new ArrayList<>();
            String json = Prefs.feed(context);
            if (json == null) { rows = next; return; }
            try {
                FeedParser.Feed feed = FeedParser.parse(json);
                // pure list (user-chosen): no section headings anywhere, items only
                for (FeedParser.Section sec : feed.sections) {
                    next.addAll(sec.items);
                }
            } catch (Exception ignored) { }
            rows = next;
        }

        @Override public int getCount() { return rowEpoch == Prefs.modeEpoch(context) ? rows.size() : 0; }
        @Override public int getViewTypeCount() { return 2; }
        @Override public long getItemId(int i) { return i; }
        @Override public boolean hasStableIds() { return true; }
        @Override public RemoteViews getLoadingView() { return null; }

        @Override
        public RemoteViews getViewAt(int i) {
            if (rowEpoch != Prefs.modeEpoch(context)) return null;
            List<Object> snapshot = rows;
            if (i < 0 || i >= snapshot.size()) return null;
            FeedParser.Item item = (FeedParser.Item) snapshot.get(i);
            return itemView(context, item, compact());
        }
    }

    static RemoteViews itemView(Context context, FeedParser.Item item, boolean compact) {
            RemoteViews v = new RemoteViews(context.getPackageName(), compact ? R.layout.widget_item_compact : R.layout.widget_item);
            String suffix = "in_progress".equals(item.status) ? "  (in progress)" : "";
            v.setTextViewText(R.id.item_title, (Prefs.isDemo(context) ? "DEMO: " : "") + item.title + suffix);
            if (item.note != null && !item.note.isEmpty()) {
                v.setTextViewText(R.id.item_note, item.note);
                v.setViewVisibility(R.id.item_note, View.VISIBLE);
            } else {
                v.setViewVisibility(R.id.item_note, View.GONE);
            }
            // the whole row opens the app: root (covers padding) and both lines
            v.setOnClickFillInIntent(R.id.item_row, new Intent());
            v.setOnClickFillInIntent(R.id.item_title, new Intent());
            v.setOnClickFillInIntent(R.id.item_note, new Intent());
            return v;
    }
}
