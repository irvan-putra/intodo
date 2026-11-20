package com.vanipy.tindak;
import android.content.*;
import android.graphics.Color;
import android.net.Uri;
import android.text.*;
import android.text.method.LinkMovementMethod;
import android.text.style.URLSpan;
import android.text.util.Linkify;
import android.widget.*;
import android.view.View;
/** Explicit browser links only. No auto-opening, phone numbers or custom schemes. */
final class ReminderLinks {
 static void apply(TextView view, String text) {
  SpannableString value = new SpannableString(text == null ? "" : text);
  Linkify.addLinks(value, Linkify.WEB_URLS);
  for (URLSpan span : value.getSpans(0,value.length(),URLSpan.class)) {
   String url = span.getURL(); int start=value.getSpanStart(span),end=value.getSpanEnd(span);
   value.removeSpan(span);
   // Linkify supplies http for bare domains. Explicitly permit only web schemes.
   Uri u=Uri.parse(url); String scheme=u.getScheme();
   if (!("https".equalsIgnoreCase(scheme)||"http".equalsIgnoreCase(scheme)) || u.getHost()==null) continue;
   value.setSpan(new URLSpan(url) {
    @Override public void onClick(View v) {
     try { v.getContext().startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(getURL())).addCategory(Intent.CATEGORY_BROWSABLE)); }
     catch(ActivityNotFoundException|SecurityException e) { Toast.makeText(v.getContext(),"No browser available for this link",Toast.LENGTH_LONG).show(); }
    }
   },start,end,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
  }
  view.setText(value); view.setTextIsSelectable(true);
  view.setLinkTextColor(Color.rgb(125,211,252));
  view.setMovementMethod(LinkMovementMethod.getInstance());
 }
}
