package com.instinct.widget;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;

/**
 * Downloads a release APK and hands it to the Android package installer, which shows
 * the system's own install confirmation - the user always approves the install.
 * Safety gates, all checked before any install prompt:
 *  1. the device allows installs from this app (canRequestPackageInstalls);
 *  2. the download is HTTPS from this repo's releases path only;
 *  3. the downloaded archive's package name equals this app's package;
 *  4. the archive's signing certificate SHA-256 equals THIS app's own certificate,
 *     so only same-signer updates (which keep pairing and data) are ever offered;
 *  5. versionCode is strictly higher than the installed one (no downgrade).
 * Uses PackageInstaller sessions (framework only, no support libraries).
 */
public class UpdateInstall {
    private static final long TIMEOUT_MS = 30000;
    private static final long MAX_BYTES = 30L * 1024 * 1024; // release APKs are a few MB; refuse anything bigger
    private static final java.util.Set<String> ALLOWED_HOSTS = new java.util.HashSet<>(java.util.Arrays.asList(
            "github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com"));

    public interface Callback { void onStatus(String message); }

    public static boolean canInstall(Context c) {
        return c.getPackageManager().canRequestPackageInstalls();
    }

    public static void openInstallPermissionSettings(Context c) {
        c.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:" + c.getPackageName())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    public static void downloadAndInstall(Context context, String assetUrl, Callback cb) {
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            File apk = new File(context.getCacheDir(), "update.apk");
            try {
                if (!assetUrl.startsWith("https://github.com/irvan-putra/intodo/releases/download/")) {
                    post(main, cb, "update refused: unexpected download source"); return;
                }
                post(main, cb, "downloading update...");
                // Bounded redirect policy: the repo releases URL must answer, and every hop
                // (GitHub sends release assets to its CDN) must stay HTTPS; 3 hops max.
                String current = assetUrl;
                HttpURLConnection conn = null;
                for (int hops = 0; hops <= 3; hops++) {
                    java.net.URL u = new URL(current);
                    // redirect targets stay on GitHub's own release hosts only
                    if (!ALLOWED_HOSTS.contains(u.getHost())) {
                        post(main, cb, "update refused: unexpected download host"); return;
                    }
                    conn = (HttpURLConnection) u.openConnection();
                    conn.setConnectTimeout((int) TIMEOUT_MS);
                    conn.setReadTimeout((int) TIMEOUT_MS);
                    conn.setInstanceFollowRedirects(false);
                    int code = conn.getResponseCode();
                    if (code >= 300 && code < 400) {
                        String loc = conn.getHeaderField("Location");
                        conn.disconnect(); conn = null;
                        if (loc == null || !loc.startsWith("https://")) {
                            post(main, cb, "update refused: unsafe download redirect"); return;
                        }
                        current = loc;
                        continue;
                    }
                    if (code != 200) { conn.disconnect(); post(main, cb, "update download failed: HTTP " + code); return; }
                    break;
                }
                if (conn == null) { post(main, cb, "update refused: too many redirects"); return; }
                long declared = conn.getContentLengthLong();
                if (declared > MAX_BYTES) { conn.disconnect(); post(main, cb, "update refused: download too large"); return; }
                long total = 0;
                try (InputStream in = conn.getInputStream(); OutputStream out = new FileOutputStream(apk)) {
                    byte[] buf = new byte[65536]; int n;
                    while ((n = in.read(buf)) != -1) {
                        total += n;
                        if (total > MAX_BYTES) { conn.disconnect(); apk.delete(); post(main, cb, "update refused: download too large"); return; }
                        out.write(buf, 0, n);
                    }
                }
                conn.disconnect();

                String why = verify(context, apk);
                if (why != null) { apk.delete(); post(main, cb, "update refused: " + why); return; }

                post(main, cb, "opening the installer - confirm there");
                PackageInstaller pi = context.getPackageManager().getPackageInstaller();
                PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                        PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                int sessionId = pi.createSession(params);
                PackageInstaller.Session session = null;
                boolean committed = false;
                try {
                    session = pi.openSession(sessionId); // opened exactly once
                    try (OutputStream out = session.openWrite("update.apk", 0, apk.length());
                         InputStream in = new FileInputStream(apk)) {
                        byte[] buf = new byte[65536]; int n;
                        while ((n = in.read(buf)) != -1) { out.write(buf, 0, n); }
                        session.fsync(out);
                    }
                    // Status goes to a broadcast receiver: the system fills the extras in,
                    // so the PendingIntent must be MUTABLE on API 31+. The receiver starts
                    // the system's own confirmation dialog (STATUS_PENDING_USER_ACTION).
                    Intent status = new Intent(context, InstallStatusReceiver.class);
                    Prefs.saveInstallSession(context, sessionId);
                    PendingIntent pi2 = PendingIntent.getBroadcast(context, 0, status,
                            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
                    session.commit(pi2.getIntentSender());
                    committed = true;
                } finally {
                    if (session != null) {
                        if (!committed) session.abandon();
                        session.close();
                    }
                    apk.delete();
                }
            } catch (Exception e) {
                apk.delete();
                post(main, cb, "update failed: " + e.getClass().getSimpleName());
            }
        }).start();
    }

    private static void post(Handler main, Callback cb, String msg) {
        main.post(() -> cb.onStatus(msg));
    }

    /** Returns null when the archive is a valid same-signer upgrade, else the refusal reason. */
    static String verify(Context context, File apk) {
        PackageManager pm = context.getPackageManager();
        PackageInfo info = pm.getPackageArchiveInfo(apk.getAbsolutePath(),
                Build.VERSION.SDK_INT >= 28
                        ? PackageManager.GET_SIGNING_CERTIFICATES
                        : PackageManager.GET_SIGNATURES);
        if (info == null) return "not a valid app package";
        if (!context.getPackageName().equals(info.packageName)) return "wrong package name";
        if (Build.VERSION.SDK_INT >= 28
                ? info.getLongVersionCode() <= ownVersionCode(context)
                : info.versionCode <= ownVersionCode(context)) return "not a newer version";
        String theirs = signerSha256(info);
        String ours = ownSignerSha256(context);
        if (theirs == null || ours == null) return "could not read signing certificates";
        if (!theirs.equals(ours)) return "signed by a different key";
        return null;
    }

    private static long ownVersionCode(Context c) {
        try {
            PackageInfo i = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? i.getLongVersionCode() : i.versionCode;
        } catch (Exception e) { return Long.MAX_VALUE; }
    }

    private static String ownSignerSha256(Context c) {
        try {
            PackageInfo i = c.getPackageManager().getPackageInfo(c.getPackageName(),
                    Build.VERSION.SDK_INT >= 28
                            ? PackageManager.GET_SIGNING_CERTIFICATES
                            : PackageManager.GET_SIGNATURES);
            return signerSha256(i);
        } catch (Exception e) { return null; }
    }

    @SuppressWarnings("deprecation")
    private static String signerSha256(PackageInfo info) {
        try {
            android.content.pm.Signature[] sigs = Build.VERSION.SDK_INT >= 28
                    ? (info.signingInfo != null ? info.signingInfo.getApkContentsSigners() : null)
                    : info.signatures;
            if (sigs == null || sigs.length == 0) return null;
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(sigs[0].toByteArray());
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) { return null; }
    }
}
