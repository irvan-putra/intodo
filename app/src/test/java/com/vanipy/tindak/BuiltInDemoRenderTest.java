package com.vanipy.tindak;
import static org.junit.Assert.*;
import android.content.Context;
import android.view.*;
import android.widget.*;
import android.graphics.*;
import androidx.work.testing.WorkManagerTestInitHelper;
import java.io.*;
import java.util.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
@RunWith(RobolectricTestRunner.class) @GraphicsMode(GraphicsMode.Mode.NATIVE) @Config(sdk=32)
public class BuiltInDemoRenderTest {
 @Test public void offlineDemoAndRealOnlySetup()throws Exception{
  Context c=RuntimeEnvironment.getApplication();Prefs.get(c).edit().clear().commit();WorkManagerTestInitHelper.initializeTestWorkManager(c);
  MainActivity a=Robolectric.buildActivity(MainActivity.class).setup().get();
  a.findViewById(R.id.demoMode).performClick();
  assertTrue(Prefs.isDemo(c));assertFalse(Prefs.isPaired(c));
  assertEquals(6,((ListView)a.findViewById(R.id.list)).getAdapter().getCount());
  ((androidx.drawerlayout.widget.DrawerLayout)a.findViewById(R.id.drawerLayout)).closeDrawer(androidx.core.view.GravityCompat.START,false);
  render(a.getWindow().getDecorView(),"/tmp/builtin-demo-list.png");
  a.findViewById(R.id.configureFeeds).performClick();android.app.AlertDialog d=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
  java.util.List<EditText> fields=new ArrayList<>();collect(d.getWindow().getDecorView(),fields);assertEquals(1,fields.size());
  render(d.getWindow().getDecorView(),"/tmp/builtin-real-setup.png");
  org.robolectric.shadows.ShadowLooper.idleMainLooper();
  fields.get(0).setText(("https://files." + "instinct.com/" + "synthetic-real-test"));d.getButton(-1).performClick();
  org.robolectric.shadows.ShadowLooper.idleMainLooper();
  assertEquals(("https://files." + "instinct.com/" + "synthetic-real-test"),Prefs.realUrl(c));assertFalse(d.isShowing());assertEquals("",Prefs.demoUrl(c));assertFalse(Prefs.isPaired(c));
 }
 private void collect(View v,java.util.List<EditText> out){if(v instanceof EditText)out.add((EditText)v);if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++)collect(((ViewGroup)v).getChildAt(i),out);}
 private void render(View v,String p)throws Exception{int w=390,h=844;v.measure(View.MeasureSpec.makeMeasureSpec(w,1073741824),View.MeasureSpec.makeMeasureSpec(h,1073741824));v.layout(0,0,w,h);Bitmap b=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);v.draw(new Canvas(b));try(FileOutputStream f=new FileOutputStream(p)){b.compress(Bitmap.CompressFormat.PNG,100,f);}}
}
