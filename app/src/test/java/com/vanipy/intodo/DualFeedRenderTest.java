package com.vanipy.intodo;
import static org.junit.Assert.*;
import android.content.Context;
import android.view.*;
import android.widget.*;
import android.graphics.*;
import androidx.work.testing.WorkManagerTestInitHelper;
import java.io.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk=32)
public class DualFeedRenderTest {
    @Test public void renderModesAndSetup() throws Exception {
        Context c=RuntimeEnvironment.getApplication();Prefs.get(c).edit().clear().commit();WorkManagerTestInitHelper.initializeTestWorkManager(c);
        Prefs.saveKeys(c,"test-only-not-a-key","test-only");Prefs.configureFeeds(c,DualFeedTest.REAL,DualFeedTest.DEMO,false);
        Prefs.saveFeed(c,DualFeedTest.REAL_JSON,"g");
        MainActivity a=Robolectric.buildActivity(MainActivity.class).setup().get();
        screenshot(a,"/tmp/dual-real.png");
        a.findViewById(R.id.demoMode).performClick();
        assertTrue(Prefs.isDemo(c));assertNull(Prefs.feed(c));assertEquals(0,((ListView)a.findViewById(R.id.list)).getAdapter().getCount());
        screenshot(a,"/tmp/dual-demo-empty.png");
        Prefs.saveFeed(c,DualFeedTest.DEMO_JSON,"d");
        org.robolectric.shadows.ShadowLooper.idleMainLooper();
        assertEquals(1,((ListView)a.findViewById(R.id.list)).getAdapter().getCount());
        assertEquals(View.GONE,a.findViewById(R.id.openBot).getVisibility());
        screenshot(a,"/tmp/dual-demo.png");
        ((androidx.drawerlayout.widget.DrawerLayout)a.findViewById(R.id.drawerLayout)).closeDrawer(androidx.core.view.GravityCompat.START,false);
        org.robolectric.shadows.ShadowLooper.idleMainLooper();
        render(a.getWindow().getDecorView(),"/tmp/dual-demo-list.png");
        assertEquals(View.VISIBLE,a.findViewById(R.id.demoBanner).getVisibility());
        ((androidx.drawerlayout.widget.DrawerLayout)a.findViewById(R.id.drawerLayout)).openDrawer(androidx.core.view.GravityCompat.START,false);
        a.findViewById(R.id.demoMode).performClick();
        android.app.AlertDialog confirm=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
        assertTrue(Prefs.isDemo(c));confirm.getButton(-2).performClick();assertTrue(Prefs.isDemo(c));
        a.findViewById(R.id.configureFeeds).performClick();
        android.app.AlertDialog dialog=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
        View root=dialog.getWindow().getDecorView(); render(root,"/tmp/dual-setup.png");
    }
    private void screenshot(MainActivity a,String path)throws Exception {
        ((androidx.drawerlayout.widget.DrawerLayout)a.findViewById(R.id.drawerLayout)).openDrawer(androidx.core.view.GravityCompat.START,false);
        render(a.getWindow().getDecorView(),path);
    }
    private void render(View v,String path)throws Exception {int w=390,h=844;v.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));v.layout(0,0,w,h);Bitmap b=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);v.draw(new Canvas(b));try(FileOutputStream f=new FileOutputStream(path)){b.compress(Bitmap.CompressFormat.PNG,100,f);}}
}
