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

import com.instinct.widget.net.Resumable;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Downloads a release APK and hands it to the Android package installer, which shows
 * the system's own install confirmation - the user always approves the install.
 * Safety gates, all checked before any install prompt:
 *  1. the device allows installs from this app (canRequestPackageInstalls);
 *  2. the download is HTTPS from this repo's releases path only, with every
 *     redirect hop pinned to GitHub's own release hosts;
 *  3. GitHub MUST have reported a sha256 digest for the asset, and the complete
 *     file's digest must match it - that digest is what proves completeness
 *     even when the server gave no length (integrity policy: no digest, no
 *     install; see Resumable for the full download contract);
 *  4. the downloaded byte count must match the server's stated length exactly -
 *     a truncated download is reported as INCOMPLETE (kept for resume when the
 *     server gave a strong validator), never passed to package validation;
 *  5. the downloaded archive's package name equals this app's package;
 *  6. the archive's signing certificate SHA-256 equals THIS app's own certificate,
 *     so only same-signer updates (which keep pairing and data) are ever offered;
 *  7. versionCode is strictly higher than the installed one (no downgrade).
 * Checks 3 and 5-7 all run on the staged file BEFORE it is treated as an update;
 * an interrupted download resumes via Range + If-Range (see Resumable).
 * One download at a time: concurrent taps are refused by a process-wide lock.
 * Uses PackageInstaller sessions (framework only, no support libraries).
 */
public class UpdateInstall {
    private static final long MAX_BYTES = 30L * 1024 * 1024; // release APKs are a few MB; refuse anything bigger
    private static final String PREFIX =
            "https://github.com/irvan-putra/intodo/releases/download/";
    private static final Set<String> ALLOWED_HOSTS = new HashSet<>(Arrays.asList(
            "github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com"));
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);

    public interface Callback {
        /** Terminal status, delivered exactly once per downloadAndInstall call. */
        void onStatus(String message);
        /** Bytes so far and expected total (-1 when the server gave no length). Default: ignore. */
        default void onProgress(long done, long total) {}
    }

    public static boolean canInstall(Context c) {
        return c.getPackageManager().canRequestPackageInstalls();
    }

    public static void openInstallPermissionSettings(Context c) {
        c.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:" + c.getPackageName())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    public static void downloadAndInstall(Context context, String assetUrl, Callback cb) {
        downloadAndInstall(context, assetUrl, null, cb);
    }

    public static void downloadAndInstall(Context context, String assetUrl, String expectedSha256, Callback cb) {
        Handler main = new Handler(Looper.getMainLooper());
        if (!RUNNING.compareAndSet(false, true)) {
            post(main, cb, "an update download is already running");
            return;
        }
        new Thread(() -> {
            File apk = new File(context.getCacheDir(), "update.apk");
            try {
                Resumable.Spec s = new Resumable.Spec();
                s.url = assetUrl;
                s.allowedPrefix = PREFIX;
                s.allowedHosts = ALLOWED_HOSTS;
                s.maxBytes = MAX_BYTES;
                s.expectedSha256 = expectedSha256;
                s.requireDigest = true; // integrity policy: GitHub's asset digest is required
                s.out = apk;
                ProgressPump pump = new ProgressPump(main, (done, total) -> cb.onProgress(done, total));
                s.progress = pump::offer;
                // Package/signer/version checks run on the STAGED file, before it is
                // treated as the update - a truncated or foreign file never reaches them.
                s.verifier = part -> {
                    String why = verify(context, part);
                    if (why != null) throw new Resumable.Refused(why);
                };
                Resumable.Result r = Resumable.get(s);
                install(context, r.file, main, cb);
            } catch (Resumable.Incomplete i) {
                post(main, cb, "download incomplete: " + mb(i.done)
                        + (i.total > 0 ? " / " + mb(i.total) : "")
                        + (i.resumable ? " - tap to resume"
                                       : " - the server cannot resume; tap to try again from the start"));
            } catch (Resumable.Refused rf) {
                post(main, cb, "update refused: " + rf.getMessage());
            } catch (Exception e) {
                apk.delete();
                post(main, cb, "update failed: " + e.getClass().getSimpleName());
            } finally {
                RUNNING.set(false);
            }
        }).start();
    }

    private static void install(Context context, File apk, Handler main, Callback cb) {
        try {
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
            post(main, cb, "opening the installer - confirm there");
        } catch (Exception e) {
            apk.delete();
            post(main, cb, "update failed: " + e.getClass().getSimpleName());
        }
    }

    static String mb(long bytes) {
        if (bytes < 0) return "?";
        if (bytes < 1024 * 1024) return (bytes / 1024) + " KB";
        return String.format(java.util.Locale.US, "%.1f MB", bytes / 1048576.0);
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
