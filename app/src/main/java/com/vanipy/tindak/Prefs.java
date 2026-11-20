package com.vanipy.tindak;

import android.content.Context;
import android.content.SharedPreferences;

public class Prefs {
    private static final String NAME = "tindak_widget";
    static SharedPreferences get(Context c) { return c.getSharedPreferences(NAME, Context.MODE_PRIVATE); }
    private static class FeedScope extends android.content.ContextWrapper {
        final boolean demo;
        final long epoch;
        FeedScope(Context c) { super(c); demo = isDemo(c); epoch = modeEpoch(c); }
    }
    /** Bind each fetch to its original mode. A switch cannot redirect a late response. */
    public static Context captureFeed(Context c) { return new FeedScope(c); }
    public static boolean isDemo(Context c) {
        return c instanceof FeedScope ? ((FeedScope)c).demo : get(c).getBoolean("demo_mode", false);
    }
    public static long modeEpoch(Context c) { return get(c).getLong("mode_epoch", 0); }
    public static boolean isCurrentFeed(Context c) {
        return !(c instanceof FeedScope) || (((FeedScope)c).epoch == modeEpoch(c)
                && ((FeedScope)c).demo == get(c).getBoolean("demo_mode", false));
    }
    private static String k(Context c, String name) { return (isDemo(c) ? "demo_" : "") + name; }
    private static final String[] FEED_KEYS = {"feed", "feed_at", "feed_gen", "check_at", "check_attempt_at", "check_error", "status"};
    public static boolean dualConfigured(Context c) { return get(c).getBoolean("dual_configured", false); }
    public static String realUrl(Context c) { return get(c).getString("feed_url", ""); }
    public static String demoUrl(Context c) { return get(c).getString("demo_feed_url", ""); }
    /** Explicit one-time setup. Keep the old cache in a recovery slot before assigning its role. */
    public static void configureFeeds(Context c, String real, String demo, boolean oldWasDemo) {
        android.content.SharedPreferences prefs = get(c);
        android.content.SharedPreferences.Editor e = prefs.edit();
        if (!dualConfigured(c)) {
            for (String key : FEED_KEYS) {
                Object value = prefs.getAll().get(key);
                if (value instanceof String) {
                    e.putString("legacy_" + key, (String)value);
                    if (oldWasDemo) e.putString("demo_" + key, (String)value).remove(key);
                } else if (value instanceof Long) {
                    e.putLong("legacy_" + key, (Long)value);
                    if (oldWasDemo) e.putLong("demo_" + key, (Long)value).remove(key);
                }
            }
            if (oldWasDemo) e.putBoolean("demo_mode", true);
        } else {
            if (!real.equals(realUrl(c))) for (String key : FEED_KEYS) e.remove(key);
            if (!demo.equals(demoUrl(c))) for (String key : FEED_KEYS) e.remove("demo_" + key);
        }
        e.putString("feed_url", real).putString("demo_feed_url", demo)
                .putBoolean("dual_configured", true).putLong("mode_epoch", modeEpoch(c) + 1).commit();
    }
    public static boolean setDemo(Context c, boolean demo) {
        if (!dualConfigured(c) || realUrl(c).isEmpty() || demoUrl(c).isEmpty()) return false;
        get(c).edit().putBoolean("demo_mode", demo).putLong("mode_epoch", modeEpoch(c) + 1).commit();
        return true;
    }
    public static boolean isPaired(Context c) { return get(c).getString("priv", null) != null; }
    public static void saveKeys(Context c, String priv, String pub) {
        get(c).edit().putString("priv", priv).putString("pub", pub).apply();
    }
    public static String privateKey(Context c) { return get(c).getString("priv", null); }
    public static String publicKey(Context c) { return get(c).getString("pub", null); }
    /** Atomic single commit: a crash mid-write can never leave feed/feed_at/feed_gen inconsistent. */
    public static void saveFeed(Context c, String json, String genAt) {
        get(c).edit()
                .putString(k(c, "feed"), json)
                .putLong(k(c, "feed_at"), System.currentTimeMillis())
                .putString(k(c, "feed_gen"), genAt == null ? "" : genAt)
                .commit();
    }
    /** Clears the cached list and marks pairing pending (used on key replacement). */
    public static void resetForRepair(Context c) {
        android.content.SharedPreferences.Editor e = get(c).edit();
        for (String key : FEED_KEYS) e.remove(key).remove("demo_" + key).remove("legacy_" + key);
        e.putLong("mode_epoch", modeEpoch(c) + 1).commit();
        saveStatus(c, "pairing pending: send the new public key to your assistant's WhatsApp chat");
    }
    public static String feed(Context c) { String json = get(c).getString(k(c, "feed"), null); return isDemo(c) && !DemoFeed.isSynthetic(json) ? null : json; }
    public static long feedAt(Context c) { return get(c).getLong(k(c, "feed_at"), 0); }
    public static String feedGen(Context c) { return get(c).getString(k(c, "feed_gen"), ""); }
    public static void saveStatus(Context c, String s) { get(c).edit().putString(k(c, "status"), s).apply(); }
    /** Records a successful fetch attempt (content new or unchanged); clears any failure. */
    public static void saveCheck(Context c) {
        get(c).edit().putLong(k(c, "check_at"), System.currentTimeMillis()).remove(k(c, "check_error")).commit();
    }
    /** Records a failed fetch attempt with a short reason; never clears the last success. */
    public static void saveCheckFailure(Context c, String reason) {
        get(c).edit().putLong(k(c, "check_attempt_at"), System.currentTimeMillis())
                .putString(k(c, "check_error"), reason == null ? "" : reason).apply();
    }
    public static long checkAt(Context c) { return get(c).getLong(k(c, "check_at"), 0); }
    public static long checkAttemptAt(Context c) { return get(c).getLong(k(c, "check_attempt_at"), 0); }
    public static String checkError(Context c) { return get(c).getString(k(c, "check_error"), ""); }
    public static String status(Context c) { return get(c).getString(k(c, "status"), "never synced"); }
    public static void saveInstallSession(Context c, int id) { get(c).edit().putInt("install_session", id).commit(); } // synchronous: must be durable before session.commit returns
    public static int installSession(Context c) { return get(c).getInt("install_session", -1); }
    public static void clearInstallSession(Context c) { get(c).edit().remove("install_session").apply(); }
    /** On-device feed address (unconfigured public builds). Empty = use build-time config. */
    public static String feedUrl(Context c) { return get(c).getString(k(c, "feed_url"), ""); }
    public static void saveFeedUrl(Context c, String url) { get(c).edit().putString(k(c, "feed_url"), url == null ? "" : url.trim()).putLong("mode_epoch", modeEpoch(c) + 1).commit(); }
}
