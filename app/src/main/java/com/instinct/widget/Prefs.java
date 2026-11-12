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
        saveStatus(c, "pairing pending: send the new public key to Instinct");
    }
    public static String feed(Context c) { return get(c).getString("feed", null); }
    public static long feedAt(Context c) { return get(c).getLong("feed_at", 0); }
    public static String feedGen(Context c) { return get(c).getString("feed_gen", ""); }
    public static void saveStatus(Context c, String s) { get(c).edit().putString("status", s).apply(); }
    public static String status(Context c) { return get(c).getString("status", "never synced"); }
}
