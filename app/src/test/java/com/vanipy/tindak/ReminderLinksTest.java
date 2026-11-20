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
@RunWith(RobolectricTestRunner.class) @Config(sdk=32)
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
 @Test public void recycledRowRemovesOldLinksAndRejectsCustomSchemes(){
  TextView v=new TextView(RuntimeEnvironment.getApplication());ReminderLinks.apply(v,"https://example.org/test");
  ReminderLinks.apply(v,"No link here. javascript:alert(1) intent:unsafe file:/private tel:123");
  assertEquals(0,((Spanned)v.getText()).getSpans(0,v.length(),URLSpan.class).length);
 }
}
