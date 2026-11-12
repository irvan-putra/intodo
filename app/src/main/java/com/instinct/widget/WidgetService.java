package com.instinct.widget;

import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.widget.RemoteViews;
import android.widget.RemoteViewsService;
import java.util.ArrayList;
import java.util.List;

public class WidgetService extends RemoteViewsService {
    @Override
    public RemoteViewsFactory onGetViewFactory(Intent intent) {
        return new Factory(getApplicationContext());
    }

    static class Factory implements RemoteViewsService.RemoteViewsFactory {
        private final Context context;
        private List<Object> rows = new ArrayList<>();
        Factory(Context c) { context = c; }

        @Override public void onCreate() { }
        @Override public void onDestroy() { }

        @Override
        public void onDataSetChanged() {
            rows = new ArrayList<>();
            String json = Prefs.feed(context);
            if (json == null) return;
            try {
                FeedParser.Feed feed = FeedParser.parse(json);
                for (FeedParser.Section sec : feed.sections) {
                    if (sec.items.isEmpty()) continue;
                    rows.add(sec);
                    rows.addAll(sec.items);
                }
            } catch (Exception ignored) { }
        }

        @Override public int getCount() { return rows.size(); }
        @Override public int getViewTypeCount() { return 2; }
        @Override public long getItemId(int i) { return i; }
        @Override public boolean hasStableIds() { return true; }
        @Override public RemoteViews getLoadingView() { return null; }

        @Override
        public RemoteViews getViewAt(int i) {
            Object row = rows.get(i);
            if (row instanceof FeedParser.Section) {
                RemoteViews v = new RemoteViews(context.getPackageName(), R.layout.widget_section);
                v.setTextViewText(R.id.section_title, ((FeedParser.Section) row).title);
                return v;
            }
            FeedParser.Item item = (FeedParser.Item) row;
            RemoteViews v = new RemoteViews(context.getPackageName(), R.layout.widget_item);
            String suffix = "in_progress".equals(item.status) ? "  (in progress)" : "";
            v.setTextViewText(R.id.item_title, item.title + suffix);
            if (item.note != null && !item.note.isEmpty()) {
                v.setTextViewText(R.id.item_note, item.note);
                v.setViewVisibility(R.id.item_note, View.VISIBLE);
            } else {
                v.setViewVisibility(R.id.item_note, View.GONE);
            }
            v.setOnClickFillInIntent(R.id.item_title, new Intent());
            return v;
        }
    }
}
