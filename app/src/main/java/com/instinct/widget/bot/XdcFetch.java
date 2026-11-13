package com.instinct.widget.bot;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import com.instinct.widget.net.Resumable;

/**
 * Manual-tap "get the latest app version (.xdc) from GitHub" for the bot
 * publish flow. One unauthenticated api.github.com request per tap; no
 * background polling, nothing is published automatically. Trust rules mirror
 * UpdateCheck/UpdateInstall:
 *  - only the irvan-putra/intodo repo is queried;
 *  - drafts and prereleases never qualify;
 *  - releases are walked newest-first and the FIRST release carrying an
 *    itdxdc-v*.xdc asset wins, so a newer release without an .xdc (for
 *    example an itdapk-only release) does not hide the newest app version;
 *  - the asset's download URL must stay under this repo's releases path, and
 *    every redirect hop must stay on GitHub's own release hosts over HTTPS;
 *  - when GitHub reports a sha256 digest for the asset, the downloaded bytes
 *    must match it;
 *  - the downloaded .xdc must be a zip whose manifest.toml carries a
 *    version = "x.y.z" line, and the asset name must start with
 *    itdxdc-v<that version> - a versionless or mismatched file is refused
 *    here, before it could fail inside the publish flow.
 */
public class XdcFetch {
    static final int RELEASES_BOUND = 20; // explicit bound: never unbounded "latest"
    static final String RELEASES_API =
            "https://api.github.com/repos/irvan-putra/intodo/releases?per_page="
            + RELEASES_BOUND;
    static final String DOWNLOAD_PREFIX =
            "https://github.com/irvan-putra/intodo/releases/download/";
    private static final java.util.Set<String> ALLOWED_HOSTS = new java.util.HashSet<>(
            java.util.Arrays.asList("github.com", "release-assets.githubusercontent.com",
                    "objects.githubusercontent.com"));
    private static final Pattern ASSET_NAME =
            Pattern.compile("^itdxdc-v(\\d+\\.\\d+\\.\\d+).*\\.xdc$");
    private static final long TIMEOUT_MS = 30000;
    private static final long MAX_BYTES = 5L * 1024 * 1024; // an .xdc is tens of KB

    public static class Found {
        public String tag, name, url, sha256; // sha256 null when GitHub reports none
    }

    public static class FetchException extends Exception {
        FetchException(String plainMessage) { super(plainMessage); }
    }

    /** Bytes so far and expected total (-1 when the server gave no length). */
    public interface ProgressListener { void onProgress(long done, long total); }

    /**
     * Walks every listed release and picks the qualified asset with the MAX
     * numeric semver in its itdxdc-v<d.d.d> name - never API order, so a
     * backport release (newer release carrying an OLDER app version) does
     * not shadow the newest app version in an older release. Pure.
     */
    static Found choose(JSONArray releases) {
        Found best = null;
        int[] bestVer = null;
        for (int i = 0; i < releases.length(); i++) {
            JSONObject rel = releases.optJSONObject(i);
            if (rel == null || rel.optBoolean("draft", false) || rel.optBoolean("prerelease", false))
                continue;
            JSONArray assets = rel.optJSONArray("assets");
            if (assets == null) continue;
            for (int j = 0; j < assets.length(); j++) {
                JSONObject a = assets.optJSONObject(j);
                if (a == null) continue;
                String name = a.optString("name", "");
                String dl = a.optString("browser_download_url", "");
                java.util.regex.Matcher m = ASSET_NAME.matcher(name);
                if (!m.matches() || !dl.startsWith(DOWNLOAD_PREFIX)) continue;
                int[] ver = parseSemver(m.group(1));
                if (best == null || compareSemver(ver, bestVer) > 0) {
                    bestVer = ver;
                    best = new Found();
                    best.tag = rel.optString("tag_name", "");
                    best.name = name;
                    best.url = dl;
                    String digest = a.optString("digest", "");
                    best.sha256 = digest.startsWith("sha256:") ? digest.substring("sha256:".length()) : null;
                }
            }
        }
        return best;
    }

    static int[] parseSemver(String v) {
        String[] p = v.split("\\.");
        return new int[]{Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2])};
    }

    /** Negative/zero/positive like a comparator; dotted-numeric, no qualifiers. */
    static int compareSemver(int[] a, int[] b) {
        for (int i = 0; i < 3; i++) if (a[i] != b[i]) return a[i] - b[i];
        return 0;
    }

    /** One network call for the release list. Throws FetchException with plain wording. */
    public static Found fetchLatest() throws FetchException {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(RELEASES_API).openConnection();
            conn.setConnectTimeout((int) TIMEOUT_MS);
            conn.setReadTimeout((int) TIMEOUT_MS);
            conn.setInstanceFollowRedirects(false); // api.github.com must answer directly
            conn.setRequestProperty("Accept", "application/vnd.github+json");
            conn.setRequestProperty("User-Agent", "itdapk-xdc-fetch");
            int code = conn.getResponseCode();
            if (code != 200) throw new FetchException(
                    "GitHub did not answer the release list (HTTP " + code
                    + "). Check your connection and try again.");
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                String line; while ((line = r.readLine()) != null) sb.append(line);
            }
            Found f = choose(new JSONArray(sb.toString()));
            if (f == null) throw new FetchException(
                    "No app version (.xdc) found in the " + RELEASES_BOUND
                    + " most recent GitHub releases. Older releases are not"
                    + " searched; publish a file manually instead.");
            return f;
        } catch (FetchException fe) {
            throw fe;
        } catch (Exception e) {
            throw new FetchException(
                    "Could not reach GitHub. Check your connection and try again.");
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * Download + verify with progress reporting and safe resume, via the shared
     * Resumable core (its class doc is the full contract):
     *  - an interrupted download keeps a .part file plus a .meta sidecar bound to
     *    the asset URL, this asset's NAME (which pins the version), and the
     *    expected sha256 (when GitHub reported one). A partial that does not
     *    bind to the CURRENT pick is discarded, never patched onto;
     *  - resume sends Range + If-Range and accepts a 206 only with an exact
     *    Content-Range (offset, end, total) and the same validator; otherwise
     *    the partial is discarded and the body is restarted from zero;
     *  - the byte count must match the server's stated length exactly - a
     *    truncated file is INCOMPLETE and kept for resume (when resumable),
     *    never verified as if complete;
     *  - the sha256 check (when GitHub reported a digest) and the
     *    manifest/name checks run on the COMPLETE staged file, and only after
     *    every check passes does it replace any previously fetched file - a
     *    failed fetch never deletes a good earlier download;
     *  - unknown length with no checksum is refused (completeness unprovable).
     * Returns the manifest version of the verified file.
     */
    public static String downloadTo(File dir, Found f, File out, ProgressListener pl) throws FetchException {
        Resumable.Spec s = new Resumable.Spec();
        s.url = f.url;
        s.allowedPrefix = DOWNLOAD_PREFIX;
        s.allowedHosts = ALLOWED_HOSTS;
        s.maxBytes = MAX_BYTES;
        s.expectedSha256 = f.sha256;
        s.requireDigest = false; // old releases predate GitHub's digest field; size equality still enforced
        s.out = out;
        s.extraMeta = java.util.Collections.singletonMap("name", f.name);
        s.verifier = part -> {
            String version = manifestVersion(part);
            if (version == null) throw new Resumable.Refused(
                    "The fetched file has no version number inside (manifest.toml). It would be refused at publish time.");
            if (!f.name.startsWith("itdxdc-v" + version)) throw new Resumable.Refused(
                    "The fetched file's name (" + f.name + ") does not match the version inside it ("
                    + version + "). Not using it.");
        };
        s.progress = pl == null ? null : pl::onProgress;
        try {
            Resumable.Result r = Resumable.get(s);
            return manifestVersion(r.file);
        } catch (Resumable.Incomplete i) {
            throw new FetchException("Download incomplete: " + kb(i.done)
                    + (i.total > 0 ? " / " + kb(i.total) : "")
                    + (i.resumable ? ". It is kept on the phone - tap Get latest app again to resume."
                                   : ". This server cannot resume - tap Get latest app again to restart."));
        } catch (Resumable.Refused rf) {
            throw new FetchException(rf.getMessage());
        } catch (Exception e) {
            throw new FetchException("Download failed. Check your connection and try again.");
        }
    }

    /** Reads version = "x.y.z" from manifest.toml inside the .xdc zip; null when absent. Pure. */
    static String manifestVersion(File xdc) {
        try (ZipFile z = new ZipFile(xdc)) {
            ZipEntry e = z.getEntry("manifest.toml");
            if (e == null) return null;
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(z.getInputStream(e)))) {
                String line; while ((line = r.readLine()) != null) sb.append(line).append('\n');
            }
            return manifestVersionFromText(sb.toString());
        } catch (Exception e) {
            return null;
        }
    }

    /** Pure text parse: first version = "..." line, dotted-numeric only. */
    static String manifestVersionFromText(String toml) {
        for (String line : toml.split("\n")) {
            String t = line.trim();
            if (!t.startsWith("version")) continue;
            int eq = t.indexOf('=');
            if (eq < 0) continue;
            String v = t.substring(eq + 1).trim().replace("\"", "").replace("'", "").trim();
            if (v.matches("\\d+\\.\\d+\\.\\d+")) return v;
        }
        return null;
    }

    private static String kb(long bytes) {
        if (bytes < 0) return "?";
        if (bytes < 1024 * 1024) return (bytes / 1024) + " KB";
        return String.format(java.util.Locale.US, "%.1f MB", bytes / 1048576.0);
    }
}
