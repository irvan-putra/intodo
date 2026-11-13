package com.instinct.widget;

import android.content.Context;
import android.text.format.DateUtils;

/**
 * Single source for the sync footer text in the activity and the widget,
 * showing both ages the user asked for: when the list last synced (any
 * successful check, content new or unchanged) and when the list content
 * last changed. "as of" is the feed's own generation version (activity only;
 * the widget uses the compact form so narrow widgets keep both ages). A
 * failed attempt newer than the last success is surfaced, never silently
 * recast as success; an old content timestamp is never relabeled as a sync.
 */
public final class SyncStatusText {
    private SyncStatusText() {}

    public static String build(Context c, String emptyFallback) {
        return build(c, emptyFallback, false);
    }

    public static String build(Context c, String emptyFallback, boolean compact) {
        long feedAt = Prefs.feedAt(c);   // written only when new list content is applied
        long checkAt = Prefs.checkAt(c); // written on every successful fetch
        if (feedAt <= 0 && checkAt <= 0) return emptyFallback;
        long now = System.currentTimeMillis();
        int flags = compact ? DateUtils.FORMAT_ABBREV_RELATIVE : 0;
        StringBuilder sb = new StringBuilder();
        if (checkAt > 0) {
            sb.append("synced ").append(rel(checkAt, now, flags));
        }
        if (feedAt > 0) {
            if (sb.length() > 0) sb.append("  \u00b7  ");
            sb.append("list changed ").append(rel(feedAt, now, flags));
            String gen = shortGen(Prefs.feedGen(c));
            if (!compact && !gen.isEmpty()) sb.append("  \u00b7  as of ").append(gen);
        }
        long attemptAt = Prefs.checkAttemptAt(c);
        if (attemptAt > checkAt && !Prefs.checkError(c).isEmpty()) {
            sb.append("  \u00b7  last sync failed ").append(rel(attemptAt, now, flags));
        }
        return sb.toString();
    }

    private static String rel(long at, long now, int flags) {
        return String.valueOf(DateUtils.getRelativeTimeSpanString(
                at, now, DateUtils.MINUTE_IN_MILLIS, flags));
    }

    /** "2026-11-12T07:01:00Z" -> "2026-11-12 14:01" in the phone's own timezone */
    static String shortGen(String iso) {
        if (iso == null || iso.isEmpty()) return "";
        try {
            java.time.ZonedDateTime z = java.time.OffsetDateTime.parse(iso)
                    .atZoneSameInstant(java.time.ZoneId.systemDefault());
            return z.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        } catch (Exception e) {
            return iso.length() >= 16 ? iso.substring(0, 10) + " " + iso.substring(11, 16) : iso;
        }
    }
}
