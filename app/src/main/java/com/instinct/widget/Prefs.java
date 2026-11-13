package com.instinct.widget;

import android.content.Context;
import android.content.SharedPreferences;

public class Prefs {
    private static final String NAME = "instinct_widget";
    static SharedPreferences get(Context c) { return c.getSharedPreferences(NAME, Context.MODE_PRIVATE); }
    public static boolean isPaired(Context c) { return get(c).getString("priv", null) != null; }
    public static void saveKeys(Context c, String priv, String pub) {
        get(c).edit().putString("priv", priv).putString("pub", pub).apply();
    }
    public static String privateKey(Context c) { return get(c).getString("priv", null); }
    public static String publicKey(Context c) { return get(c).getString("pub", null); }
    /** Atomic single commit: a crash mid-write can never leave feed/feed_at/feed_gen inconsistent. */
    public static void saveFeed(Context c, String json, String genAt) {
        get(c).edit()
                .putString("feed", json)
                .putLong("feed_at", System.currentTimeMillis())
                .putString("feed_gen", genAt == null ? "" : genAt)
                .commit();
    }
    /** Clears the cached list and marks pairing pending (used on key replacement). */
    public static void resetForRepair(Context c) {
        get(c).edit().remove("feed").remove("feed_at").remove("feed_gen").commit();
        saveStatus(c, "pairing pending: send the new public key to your assistant's WhatsApp chat");
    }
    public static String feed(Context c) { return get(c).getString("feed", null); }
    public static long feedAt(Context c) { return get(c).getLong("feed_at", 0); }
    public static String feedGen(Context c) { return get(c).getString("feed_gen", ""); }
    public static void saveStatus(Context c, String s) { get(c).edit().putString("status", s).apply(); }
    /** Records a successful fetch attempt (content new or unchanged); clears any failure. */
    public static void saveCheck(Context c) {
        get(c).edit().putLong("check_at", System.currentTimeMillis()).remove("check_error").commit();
    }
    /** Records a failed fetch attempt with a short reason; never clears the last success. */
    public static void saveCheckFailure(Context c, String reason) {
        get(c).edit().putLong("check_attempt_at", System.currentTimeMillis())
                .putString("check_error", reason == null ? "" : reason).apply();
    }
    public static long checkAt(Context c) { return get(c).getLong("check_at", 0); }
    public static long checkAttemptAt(Context c) { return get(c).getLong("check_attempt_at", 0); }
    public static String checkError(Context c) { return get(c).getString("check_error", ""); }
    public static String status(Context c) { return get(c).getString("status", "never synced"); }
    public static void saveInstallSession(Context c, int id) { get(c).edit().putInt("install_session", id).commit(); } // synchronous: must be durable before session.commit returns
    public static int installSession(Context c) { return get(c).getInt("install_session", -1); }
    public static void clearInstallSession(Context c) { get(c).edit().remove("install_session").apply(); }
    /** On-device feed address (unconfigured public builds). Empty = use build-time config. */
    public static String feedUrl(Context c) { return get(c).getString("feed_url", ""); }
    public static void saveFeedUrl(Context c, String url) { get(c).edit().putString("feed_url", url == null ? "" : url.trim()).apply(); }
}
