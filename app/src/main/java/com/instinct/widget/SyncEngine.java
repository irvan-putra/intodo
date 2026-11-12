package com.instinct.widget;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fetches the Instinct Files feed page. Files serves page content through its own
 * viewer lease, so this drives a hidden WebView through the public page exactly like
 * a browser and captures the response bytes the page itself receives (the feed
 * payload is embedded in the page's compiled content). No private API is called:
 * every request is one the public viewer makes. Subresource responses on the content
 * host are fetched manually so we can read the bytes; cookies stay in the
 * CookieManager both ways.
 */
public class SyncEngine {
    // Build-time config (local.properties -> BuildConfig), never hardcoded in source:
    // the public repo ships without an endpoint and reports "feed URL not configured".
    public static final String FEED_URL = BuildConfig.FEED_URL;
    private static final Pattern PAYLOAD = Pattern.compile("IFW1\\.([A-Za-z0-9_\\-]{40,})");
    private static final long TIMEOUT_MS = 90000;

    public interface Callback { void onDone(boolean ok, String message); }

    public static void run(Context context, Callback cb) {
        if (FEED_URL == null || FEED_URL.isEmpty()) {
            Prefs.saveStatus(context, "sync failed: feed URL not configured");
            cb.onDone(false, "feed URL not configured");
            return;
        }
        Handler main = new Handler(Looper.getMainLooper());
        main.post(() -> {
            final boolean[] finished = {false};
            final String[] found = {null};
            WebView web = new WebView(context);
            WebSettings s = web.getSettings();
            s.setJavaScriptEnabled(true);
            s.setDomStorageEnabled(true);
            s.setCacheMode(WebSettings.LOAD_NO_CACHE);
            CookieManager cm = CookieManager.getInstance();
            cm.setAcceptCookie(true);
            cm.setAcceptThirdPartyCookies(web, false);
            web.clearCache(true);

            Runnable timeout = () -> finish(web, finished, found, context, cb, "timed out");
            main.postDelayed(timeout, TIMEOUT_MS);
            final boolean[] settleScheduled = {false};

            web.setWebViewClient(new WebViewClient() {
                @Override
                public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
                    String url = req.getUrl().toString();
                    if (!expectedHost(req.getUrl().getHost())) {
                        // block third-party subresources (analytics beacons etc.)
                        return new WebResourceResponse("text/plain", "utf-8", new ByteArrayInputStream(new byte[0]));
                    }
                    if (!"GET".equals(req.getMethod())) return null;
                    if (!url.contains("instinctusercontent.com")) return null; // let WebView handle viewer/nav
                    try {
                        return fetch(url, cm, found);
                    } catch (Exception e) {
                        return null; // fall back to WebView's own fetch
                    }
                }
                @Override
                public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                    String host = req.getUrl().getHost();
                    boolean allowed = "files.instinct.com".equals(host)
                            || (host != null && host.endsWith(".instinctusercontent.com"));
                    return !allowed; // true = block: we never leave the expected origins
                }
                @Override
                public void onPageFinished(WebView view, String url) {
                    // subresources (the payload bundle) can still be in flight; allow a real window
                    main.postDelayed(() -> finish(view, finished, found, context, cb, "payload not found on page"), 15000);
                }
            });
            web.loadUrl(FEED_URL);
        });
    }

    private static final int MAX_BODY = 4 * 1024 * 1024;

    private static boolean expectedHost(String host) {
        // api.instinct.com is part of the Files viewer's own flow: the public page
        // mints its content lease there. Blocking it (as a "third party") leaves the
        // page without content - that was the v0.1 "payload not found on page" bug.
        return "files.instinct.com".equals(host) || "api.instinct.com".equals(host)
                || (host != null && host.endsWith(".instinctusercontent.com"));
    }

    private static WebResourceResponse fetch(String url, CookieManager cm, String[] found) throws Exception {
        String current = url;
        HttpURLConnection conn = null;
        for (int hops = 0; hops < 4; hops++) {
            conn = (HttpURLConnection) new URL(current).openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            conn.setInstanceFollowRedirects(false);
            String cookie = cm.getCookie(current);
            if (cookie != null) conn.setRequestProperty("Cookie", cookie);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) InstinctWidget/0.3");
            conn.connect();
            int hopCode = conn.getResponseCode();
            List<String> hopCookies = conn.getHeaderFields().get("Set-Cookie");
            if (hopCookies != null) for (String sc : hopCookies) cm.setCookie(current, sc);
            if (hopCode >= 300 && hopCode < 400) {
                String loc = conn.getHeaderField("Location");
                conn.disconnect();
                if (loc == null) return null;
                String next = new URL(new URL(current), loc).toString();
                if (!expectedHost(new URL(next).getHost())) return null; // refuse to follow off-origin
                current = next;
                continue;
            }
            break;
        }
        if (conn == null) return null;
        Map<String, List<String>> headers = conn.getHeaderFields();
        List<String> setCookies = conn.getHeaderFields().get("Set-Cookie");
        if (setCookies != null) for (String sc : setCookies) cm.setCookie(url, sc);
        int code = conn.getResponseCode();
        InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        if (in == null) return null;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int n;
        while ((n = in.read(chunk)) != -1) {
            buf.write(chunk, 0, n);
            if (buf.size() > MAX_BODY) { in.close(); return null; }
        }
        in.close();
        byte[] body = buf.toByteArray();
        if (found[0] == null) {
            String text = new String(body, "ISO-8859-1");
            Matcher m = PAYLOAD.matcher(text);
            if (m.find()) found[0] = m.group(1);
            else if (text.contains("IFW0.awaiting-pairing")) found[0] = "AWAITING";
        }
        String mime = "application/octet-stream";
        String ct = conn.getContentType();
        if (ct != null) {
            int semi = ct.indexOf(';');
            mime = semi > 0 ? ct.substring(0, semi).trim() : ct.trim();
        }
        WebResourceResponse res = new WebResourceResponse(mime, "utf-8", code,
                code == 200 ? "OK" : "ERR", headers == null ? java.util.Collections.emptyMap() :
                new java.util.HashMap<>(java.util.Collections.singletonMap("Content-Type", ct == null ? mime : ct)),
                new ByteArrayInputStream(body));
        cm.flush();
        return res;
    }

    private static void finish(WebView web, boolean[] finished, String[] found, Context context, Callback cb, String notFoundMsg) {
        if (finished[0]) return;
        finished[0] = true;
        web.stopLoading();
        web.destroy();
        String payload = found[0];
        if ("AWAITING".equals(payload)) {
            Prefs.saveStatus(context, "waiting for Instinct to publish your list");
            cb.onDone(false, "Instinct has not published your encrypted list yet");
            return;
        }
        if (payload == null) {
            Prefs.saveStatus(context, "sync failed: " + notFoundMsg);
            cb.onDone(false, notFoundMsg);
            return;
        }
        try {
            String priv = Prefs.privateKey(context);
            if (priv == null) { cb.onDone(false, "not paired yet"); return; }
            String json = Crypto.decrypt(payload, Crypto.decodePrivate(priv));
            String genAt = FeedParser.parse(json).generatedAt;
            switch (Freshness.compare(genAt, Prefs.feedGen(context))) {
                case SAME:
                    Prefs.saveStatus(context, "already up to date");
                    cb.onDone(true, "already up to date");
                    return;
                case OLDER:
                    Prefs.saveStatus(context, "sync skipped: source looks older than this phone's list");
                    cb.onDone(false, "the published list is older than the one on this phone");
                    return;
                default:
                    break;
            }
            Prefs.saveFeed(context, json, genAt);
            Prefs.saveStatus(context, "synced just now");
            cb.onDone(true, "synced");
        } catch (Exception e) {
            Prefs.saveStatus(context, "sync failed: " + e.getClass().getSimpleName());
            cb.onDone(false, "could not decrypt feed (paired with the right key?)");
        }
    }
}
