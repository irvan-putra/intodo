package com.vanipy.tindak;
import static org.junit.Assert.*;
import android.app.Activity;
import android.text.*;
import android.text.style.URLSpan;
import android.widget.TextView;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
@RunWith(RobolectricTestRunner.class) @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE) @Config(sdk=32)
public class ReminderLinksTest {
 @Test public void linksOpenOnlyByExplicitTapAndKeepText(){
  Activity a=Robolectric.buildActivity(Activity.class).setup().get();TextView v=new TextView(a);
  String text="Fictional ticket https://example.org/ticket/123?mode=demo and https://example.net/info";
  ReminderLinks.apply(v,text);assertEquals(text,v.getText().toString());assertTrue(v.isTextSelectable());
  URLSpan[] spans=((Spanned)v.getText()).getSpans(0,v.length(),URLSpan.class);assertEquals(2,spans.length);
  assertNull(Shadows.shadowOf(a).getNextStartedActivity());spans[0].onClick(v);
  android.content.Intent i=Shadows.shadowOf(a).getNextStartedActivity();assertEquals("https://example.org/ticket/123?mode=demo",i.getDataString());assertEquals(android.content.Intent.ACTION_VIEW,i.getAction());
  assertEquals(android.graphics.Color.rgb(125,211,252),v.getLinkTextColors().getDefaultColor());
 }
 @Test public void nativeReminderRowsAtPhoneWidths()throws Exception{
  Activity a=Robolectric.buildActivity(Activity.class).setup().get();
  FeedParser.Item item=new FeedParser.Item();item.title="Fictional event reminder";item.note="Fri 20 Nov, 21:00 WIB. Fictional ticket: https://example.org/ticket/demo?seat=123 More information https://example.net/info";
  MainActivity.FeedAdapter adapter=new MainActivity.FeedAdapter(a);adapter.setRows(java.util.Arrays.asList(item));
  for(int w:new int[]{320,390}){
   android.widget.LinearLayout root=new android.widget.LinearLayout(a);root.setPadding(16,16,16,16);root.setBackgroundColor(android.graphics.Color.rgb(17,24,39));
   android.view.View row=adapter.getView(0,null,root);root.addView(row);
   root.measure(android.view.View.MeasureSpec.makeMeasureSpec(w,1073741824),android.view.View.MeasureSpec.makeMeasureSpec(600,1073741824));root.layout(0,0,w,600);
   android.graphics.Bitmap bmp=android.graphics.Bitmap.createBitmap(w,600,android.graphics.Bitmap.Config.ARGB_8888);root.draw(new android.graphics.Canvas(bmp));
   try(java.io.FileOutputStream out=new java.io.FileOutputStream("/tmp/builtin-links-"+w+".png")){bmp.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}
   TextView note=row.findViewById(R.id.item_note);assertEquals(2,((Spanned)note.getText()).getSpans(0,note.length(),URLSpan.class).length);
  }
 }
 @Test public void recycledRowRemovesOldLinksAndRejectsCustomSchemes(){
  TextView v=new TextView(RuntimeEnvironment.getApplication());ReminderLinks.apply(v,"https://example.org/test");
  ReminderLinks.apply(v,"No link here. javascript:alert(1) intent:unsafe file:/private tel:123");
  assertEquals(0,((Spanned)v.getText()).getSpans(0,v.length(),URLSpan.class).length);
 }
}
