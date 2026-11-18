package com.vanipy.intodo;

import android.appwidget.AppWidgetManager;
import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ListView;
import android.widget.TextView;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Synthetic sizing regression; combined launcher hosting is a separate check. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=32)
public class WidgetCompactTest {
    @Test public void compactRemoteViewsKeepEveryItemAndReadableRow() {
        Context c=RuntimeEnvironment.getApplication();
        StringBuilder feed=new StringBuilder("{\"sections\":[{\"id\":\"demo\",\"title\":\"DEMO\",\"items\":[");
        for(int i=0;i<40;i++) {
            if(i>0) feed.append(',');
            feed.append("{\"id\":\"").append(i).append("\",\"title\":\"Synthetic reminder ").append(i)
                .append("\",\"note\":\"Made-up demo only\",\"status\":\"open\"}");
        }
        feed.append("]}]}");
        Prefs.saveFeed(c,feed.toString(),"");
        int id=913;
        Bundle opts=new Bundle();
        opts.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT,48);
        AppWidgetManager.getInstance(c).updateAppWidgetOptions(id,opts);
        WidgetService.Factory f=new WidgetService.Factory(c,id);
        f.onDataSetChanged();
        assertTrue(f.compact());
        assertEquals(40,f.getCount());
        View row=f.getViewAt(39).apply(c,new FrameLayout(c));
        TextView title=row.findViewById(R.id.item_title),note=row.findViewById(R.id.item_note);
        assertEquals("Synthetic reminder 39",title.getText().toString());
        assertEquals(1,title.getMaxLines());
        assertEquals(1,note.getMaxLines());
        row.measure(View.MeasureSpec.makeMeasureSpec(360,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));
        assertTrue("one-row content fits minimum minus padding",row.getMeasuredHeight()<=40*c.getResources().getDisplayMetrics().density);
        opts.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT,180);
        AppWidgetManager.getInstance(c).updateAppWidgetOptions(id,opts);
        assertFalse(f.compact());
        View expanded=f.getViewAt(39).apply(c,new FrameLayout(c));
        assertEquals(2,((TextView)expanded.findViewById(R.id.item_title)).getMaxLines());
        assertEquals(40,f.getCount());
    }
    @Test public void oneCellSummaryAndExpandedChoices() {
        assertTrue(WidgetProvider.summaryForSize(48,48));
        assertTrue(WidgetProvider.summaryForSize(72,96));
        assertFalse(WidgetProvider.summaryForSize(360,72));
        assertFalse(WidgetProvider.summaryForSize(72,180));
        assertEquals(0,WidgetProvider.itemCount(null));
        assertEquals(0,WidgetProvider.itemCount("bad json"));
        assertEquals(2,WidgetProvider.itemCount("{\"sections\":[{\"items\":[{},{}]}]}"));
        Context c=RuntimeEnvironment.getApplication();
        View v=new android.widget.RemoteViews(c.getPackageName(),R.layout.widget_summary).apply(c,new FrameLayout(c));
        assertNotNull(v.findViewById(R.id.widget_summary_root));
        assertNotNull(v.findViewById(R.id.widget_summary_count));
    }
    @Test public void paddingAndCollectionArePreserved() {
        assertEquals(4,WidgetProvider.paddingDpForHeight(48));
        assertEquals(4,WidgetProvider.paddingDpForHeight(110));
        assertEquals(10,WidgetProvider.paddingDpForHeight(111));
        Context c=RuntimeEnvironment.getApplication();
        View root=new android.widget.RemoteViews(c.getPackageName(),R.layout.widget_layout).apply(c,new FrameLayout(c));
        assertTrue(root.findViewById(R.id.widget_list) instanceof ListView);
        assertNotNull(root.findViewById(R.id.widget_filler));
    }
}
