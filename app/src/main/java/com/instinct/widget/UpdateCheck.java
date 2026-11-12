package com.instinct.widget;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Checks the public intodo repo's GitHub Releases for a strictly newer itdapk build.
 * One unauthenticated api.github.com request per app open / manual sync; no background
 * polling. Trust rules:
 *  - only the irvan-putra/intodo repo is queried (never other repos' releases);
 *  - drafts and prereleases never qualify (the /releases/latest endpoint excludes them,
 *    and a repo with no published release answers 404 = "no update info", not an error);
 *  - only a STRICTLY newer dotted-numeric version is offered (no downgrade/same-version);
 *  - the APK asset must be named exactly itdapk-v<version>.apk - anything else is ignored;
 *  - the download URL must stay on github.com under this repo's releases path.
 */
public class UpdateCheck {
    private static final String LATEST =
            "https://api.github.com/repos/irvan-putra/intodo/releases/latest";
    private static final String RELEASES_DOWNLOAD_PREFIX =
            "https://github.com/irvan-putra/intodo/releases/download/";
    private static final long TIMEOUT_MS = 15000;

    public interface Callback { void onResult(String newerVersion, String assetUrl, String releaseUrl); }

    /** Strict dotted-numeric compare: returns true only when candidate > current. */
    static boolean isNewer(String candidate, String current) {
        String[] a = candidate.split("\\."), b = current.split("\\.");
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int x = i < a.length ? parse(a[i]) : 0;
            int y = i < b.length ? parse(b[i]) : 0;
            if (x != y) return x > y;
        }
        return false;
    }

    private static int parse(String s) {
        try { return Integer.parseInt(s.replaceAll("[^0-9].*$", "")); }
        catch (Exception e) { return 0; }
    }

    public static void run(Context context, Callback cb) {
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            // ABI guard: from 0.4.5 the app bundles the bot's arm64-v8a-only
            // native runtime (librpcsrv.so). A device without arm64-v8a cannot
            // run 0.4.5+; never offer it an update it cannot install. (The
            // already-shipped 0.4.4 updater cannot be retro-guarded; on a
            // non-arm64 device its install would simply fail harmlessly with
            // INSTALL_FAILED_NO_MATCHING_ABIS - no data loss, old app intact.)
            boolean arm64 = false;
            for (String abi : android.os.Build.SUPPORTED_ABIS) {
                if ("arm64-v8a".equals(abi)) { arm64 = true; break; }
            }
            if (!arm64) { main.post(() -> cb.onResult(null, null, null)); return; }
            String newer = null, assetUrl = null, releaseUrl = null;
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(LATEST).openConnection();
                conn.setConnectTimeout((int) TIMEOUT_MS);
                conn.setReadTimeout((int) TIMEOUT_MS);
                conn.setInstanceFollowRedirects(false); // api.github.com must answer directly
                conn.setRequestProperty("Accept", "application/vnd.github+json");
                conn.setRequestProperty("User-Agent", "itdapk-update-check");
                if (conn.getResponseCode() == 200) {
                    StringBuilder sb = new StringBuilder();
                    try (BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                        String line; while ((line = r.readLine()) != null) sb.append(line);
                    }
                    JSONObject rel = new JSONObject(sb.toString());
                    if (!rel.optBoolean("draft", false) && !rel.optBoolean("prerelease", false)) {
                        String tag = rel.optString("tag_name", "");
                        String latest = tag.startsWith("v") ? tag.substring(1) : tag;
                        String expectedAsset = "itdapk-v" + latest + ".apk";
                        if (isNewer(latest, BuildConfig.VERSION_NAME)) {
                            JSONArray assets = rel.optJSONArray("assets");
                            if (assets != null) {
                                for (int i = 0; i < assets.length(); i++) {
                                    JSONObject a = assets.getJSONObject(i);
                                    String dl = a.optString("browser_download_url", "");
                                    if (expectedAsset.equals(a.optString("name"))
                                            && dl.startsWith(RELEASES_DOWNLOAD_PREFIX)) {
                                        newer = latest;
                                        assetUrl = dl;
                                        releaseUrl = rel.optString("html_url", "");
                                        break;
                                    }
                                }
                            }
                        }
                    }
                } // 404 (no releases yet) and any other status: no update info, not an error
                conn.disconnect();
            } catch (Exception ignored) { /* offline or blocked: no update info, not an error */ }
            final String fN = newer, fA = assetUrl, fR = releaseUrl;
            main.post(() -> cb.onResult(fN, fA, fR));
        }).start();
    }
}
