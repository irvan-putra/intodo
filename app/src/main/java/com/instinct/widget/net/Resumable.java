package com.instinct.widget.net;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;

/**
 * Verified resumable-download core shared by the APK updater (UpdateInstall)
 * and the .xdc fetcher (XdcFetch). One implementation, one audit surface.
 *
 * Trust rules:
 *  - the first URL must sit under a caller-pinned prefix, and every redirect
 *    hop stays HTTPS on the caller's allowed hosts (3 hops max);
 *  - Accept-Encoding is always identity so byte counts and Content-Length /
 *    Content-Range mean the same thing;
 *  - resume state lives in a small .meta sidecar bound to the asset URL, the
 *    expected sha256 (when any), and caller-supplied extra identity (for the
 *    .xdc fetcher: the asset name, which pins the version). A partial that
 *    does not bind to the CURRENT request is discarded, never patched onto;
 *  - a partial is only reused when the server gave a strong validator (ETag
 *    that is not weak, else Last-Modified) at partial time. Resume sends
 *    Range + If-Range with that validator; a missing validator means the
 *    partial cannot be trusted and the download safely restarts from zero;
 *  - a 206 is accepted only when its Content-Range exactly matches the
 *    requested offset, has an exact end and total (no '*'), the arithmetic
 *    have + (end-start+1) == total holds, the stored size (when known)
 *    agrees, the response validator still equals the stored one, and a
 *    present Content-Length equals the ranged remainder. Any mismatch is a
 *    failed resume: discard and restart clean ONCE, then refuse;
 *  - 200 to a Range request means the server ignored Range or the validator
 *    went stale: the body replaces the partial from byte zero (never append);
 *  - 416 means the range cannot be satisfied: it vouches for completeness
 *    ONLY with a well-formed wildcard Content-Range whose total exactly
 *    equals the bytes held and the stored size; anything else (missing,
 *    malformed, wildcard total, mismatch) is a stale partial: discarded
 *    and restarted once;
 *  - a complete download proves itself: exact byte count when the total is
 *    known (clean-EOF truncation is INCOMPLETE, never verified), then the
 *    sha256 when one was supplied, then the caller's own Verifier. Unknown
 *    total with no checksum is refused - completeness cannot be proven;
 *  - only after EVERY check passes does the staged file replace the output
 *    with one same-directory rename. A previous good output is never deleted
 *    by a failing download. If the rename itself fails, the verified partial
 *    plus its meta stay on disk so the next attempt resumes at 100%.
 *
 * The core is pure JDK (no Android, no org.json) so every behavior above is
 * covered by deterministic local unit tests with scripted connections.
 */
public final class Resumable {
    private Resumable() {}

    private static final int MAX_HOPS = 3;
    private static final long TIMEOUT_MS = 30000;

    /** Minimal connection seam so tests can script every server behavior. */
    public interface Conn {
        int code() throws IOException;
        /** Response header, case-insensitive; null when absent. */
        String header(String name);
        /** Response body; throws when the response has none. */
        InputStream body() throws IOException;
        void disconnect();
    }

    /** Opens a connection to url with the given request headers already applied. */
    public interface Opener {
        Conn open(URL url, Map<String, String> requestHeaders) throws IOException;
    }

    public interface Progress {
        /**
         * Bytes held so far and expected total (-1 unknown). Fired once before
         * any network when a partial is resumed (so the UI shows the resumed
         * bytes immediately), then per chunk. A server-forced restart rewinds
         * the count to 0 - that rewind is the honest state, never hide it.
         */
        void onProgress(long done, long total);
    }

    /** Caller verification on the COMPLETE staged file, before replace. */
    public interface Verifier {
        void verify(File completePart) throws Refused;
    }

    /** A refused download: wording safe to show the user. Nothing was installed. */
    public static class Refused extends Exception {
        public Refused(String message) { super(message); }
    }

    /** Stopped before completeness; the partial was NEVER verified as complete. */
    public static final class Incomplete extends Exception {
        public final long done;
        public final long total;        // -1 unknown
        public final boolean resumable; // false = no server validator; next try restarts
        Incomplete(long done, long total, boolean resumable) {
            super("incomplete at " + done + (total > 0 ? " / " + total : "") + " bytes");
            this.done = done; this.total = total; this.resumable = resumable;
        }
    }

    public static final class Spec {
        public String url;                    // asset URL; must start with allowedPrefix
        public String allowedPrefix;          // caller-pinned path prefix
        public Set<String> allowedHosts;      // redirect hosts; HTTPS only
        public long maxBytes;
        public String expectedSha256;         // null only allowed when requireDigest == false
        public boolean requireDigest;
        public File out;                      // final destination; previous good file is preserved
        public Map<String, String> extraMeta; // extra identity bindings (e.g. asset name)
        public Verifier verifier;             // optional; runs on the complete staged file
        public Opener opener;                 // null = real HttpURLConnection
        public Progress progress;             // optional
    }

    public static final class Result {
        public File file;
        public long bytes;
        public boolean resumed;   // true when an existing partial was used
        public String sha256;     // hex digest of the verified file
    }

    /** Production opener over HttpURLConnection. */
    private static Opener http() {
        return (url, headers) -> {
            HttpURLConnection c = (HttpURLConnection) url.openConnection();
            c.setConnectTimeout((int) TIMEOUT_MS);
            c.setReadTimeout((int) TIMEOUT_MS);
            c.setInstanceFollowRedirects(false);
            for (Map.Entry<String, String> e : headers.entrySet())
                c.setRequestProperty(e.getKey(), e.getValue());
            return new Conn() {
                @Override public int code() throws IOException { return c.getResponseCode(); }
                @Override public String header(String name) { return c.getHeaderField(name); }
                @Override public InputStream body() throws IOException {
                    InputStream in = c.getResponseCode() >= 400 ? c.getErrorStream() : c.getInputStream();
                    if (in == null) throw new IOException("response has no body");
                    return in;
                }
                @Override public void disconnect() { c.disconnect(); }
            };
        };
    }

    public static Result get(Spec s) throws Refused, Incomplete {
        if (s.url == null || s.allowedPrefix == null || !s.url.startsWith(s.allowedPrefix))
            throw new Refused("unexpected download source");
        if (s.requireDigest && s.expectedSha256 == null)
            throw new Refused("the server did not provide a checksum for this file");
        Opener opener = s.opener != null ? s.opener : http();
        File part = partOf(s.out);
        File meta = metaOf(s.out);

        Meta m = Meta.load(meta);
        long have = 0;
        if (m != null && m.binds(s) && m.validator() != null && part.exists() && part.length() > 0) {
            have = part.length();
        } else {
            // foreign, stale, or unverifiable partial: never patch onto it
            part.delete(); meta.delete(); m = null;
        }
        if (have > 0 && s.progress != null)
            s.progress.onProgress(have, m.size >= 0 ? m.size : -1); // resumed bytes, before any network

        boolean restarted = false; // at most one forced clean restart per call
        while (true) {
            boolean wantRange = have > 0;
            Map<String, String> rh = new HashMap<>();
            rh.put("Accept-Encoding", "identity");
            if (wantRange) {
                rh.put("Range", "bytes=" + have + "-");
                rh.put("If-Range", m.validator());
            }
            Conn conn = openFollowingRedirects(opener, s, rh);
            int code;
            try { code = conn.code(); }
            catch (IOException ioe) { conn.disconnect(); throw new Refused("could not reach the download server"); }

            if (wantRange && code == 206) {
                long[] cr = parseContentRange(conn.header("Content-Range")); // null or [start,end,total(-1=*)]
                String vEtag = strong(conn.header("ETag"));
                String vLastmod = conn.header("Last-Modified");
                long declaredLen = parseLong(conn.header("Content-Length"));
                boolean exact = cr != null && cr[0] == have && cr[2] > 0
                        && have + (cr[1] - cr[0] + 1) == cr[2]
                        && (m.size < 0 || m.size == cr[2])
                        && validatorMatches(m, vEtag, vLastmod)
                        && (declaredLen < 0 || declaredLen == (cr[1] - cr[0] + 1));
                if (!exact) {
                    conn.disconnect();
                    if (restarted) throw new Refused("the server's resume answer did not match the saved partial");
                    restarted = true; have = 0; m = null; part.delete(); meta.delete();
                    continue;
                }
                long total = cr[2];
                if (total > s.maxBytes) {
                    conn.disconnect(); part.delete(); meta.delete();
                    throw new Refused("download too large");
                }
                Meta round = m;
                long held = stream(s, conn, part, meta, round, true, have, total);
                return finish(s, part, meta, held, true, round);
            }

            if (wantRange && code == 416) {
                long[] wr = parseContentRange(conn.header("Content-Range")); // must be the wildcard-range form
                conn.disconnect();
                // Strict: a 416 may vouch for completeness ONLY with a well-formed
                // wildcard Content-Range whose total exactly equals both the bytes we
                // hold and the size we stored. A missing, malformed, wildcard-total,
                // or mismatched header is a failed resume: discard and restart clean.
                if (m.size >= 0 && have == m.size && wr != null && wr[0] < 0 && wr[2] == have) {
                    // the partial already IS the complete object: finish without more bytes
                    return finish(s, part, meta, have, true, m);
                }
                if (restarted) throw new Refused("the server refused the saved partial file");
                restarted = true; have = 0; m = null; part.delete(); meta.delete();
                continue;
            }

            if (code == 200) {
                // fresh body: either a first attempt, or the server ignored Range / the
                // validator went stale - in both cases the body REPLACES the partial.
                if (wantRange) { have = 0; m = null; meta.delete(); }
                String etag = strong(conn.header("ETag"));
                String lastmod = conn.header("Last-Modified");
                long declared = parseLong(conn.header("Content-Length")); // -1 unknown
                if (declared > s.maxBytes) {
                    conn.disconnect(); part.delete(); meta.delete();
                    throw new Refused("download too large");
                }
                if (declared < 0 && s.expectedSha256 == null) {
                    conn.disconnect(); part.delete(); meta.delete();
                    throw new Refused("the server stated neither the file size nor a checksum"
                            + " - a complete download cannot be verified");
                }
                Meta round = new Meta();
                round.url = s.url;
                round.sha256 = s.expectedSha256;
                round.size = declared;
                round.etag = etag;
                round.lastmod = lastmod;
                round.extra = s.extraMeta;
                long held = stream(s, conn, part, meta, round, false, 0, declared);
                return finish(s, part, meta, held, false, round);
            }

            conn.disconnect();
            throw new Refused("download failed: HTTP " + code);
        }
    }

    /**
     * Streams one body into part (append when resuming), enforcing the size cap
     * and the exact byte count. round carries THIS body's validators/size so a
     * failure can persist honest resume state. A truncation or I/O failure
     * throws Incomplete and never reaches verification.
     */
    private static long stream(Spec s, Conn conn, File part, File meta, Meta round,
                               boolean append, long startAt, long expectedTotal)
            throws Refused, Incomplete {
        long total = startAt;
        boolean incomplete = false;
        try (InputStream in = conn.body();
             OutputStream o = new FileOutputStream(part, append)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) != -1) {
                total += n;
                if (total > s.maxBytes) {
                    conn.disconnect(); part.delete(); meta.delete();
                    throw new Refused("download too large");
                }
                o.write(buf, 0, n);
                if (s.progress != null) s.progress.onProgress(total, expectedTotal);
            }
        } catch (Refused r) {
            throw r;
        } catch (IOException ioe) {
            incomplete = true;
        } finally {
            conn.disconnect();
        }
        if (!incomplete && expectedTotal >= 0 && total != expectedTotal)
            incomplete = true; // clean-EOF truncation: NOT a complete file
        if (incomplete) {
            round.size = expectedTotal;
            // Resume state only when the server gave a strong validator; without one
            // the partial can never be trusted, so drop it and say resume is impossible.
            boolean resumable = round.validator() != null && total > 0;
            if (resumable) round.save(meta); else { part.delete(); meta.delete(); }
            throw new Incomplete(total, expectedTotal, resumable);
        }
        return total;
    }

    /** All checks passed: verify the staged file, then replace the output atomically. */
    private static Result finish(Spec s, File part, File meta, long held,
                                 boolean resumed, Meta round) throws Refused {
        String sha = sha256File(part);
        if (s.expectedSha256 != null && !s.expectedSha256.equalsIgnoreCase(sha)) {
            part.delete(); meta.delete();
            throw new Refused("download verification failed: the file does not match the expected checksum");
        }
        if (s.verifier != null) {
            try {
                s.verifier.verify(part);
            } catch (Refused r) {
                part.delete(); meta.delete();
                throw r;
            }
        }
        // Persist resume state BEFORE the move: if the rename fails, the next attempt
        // resumes at 100% (416 complete-already) instead of downloading again.
        if (round != null && round.validator() != null) {
            round.size = held;
            round.save(meta);
        }
        if (!part.renameTo(s.out)) {
            // Same-dir rename failing is abnormal; keep the verified partial + meta.
            throw new Refused("could not move the verified download into place"
                    + " - the previous file is untouched; try again");
        }
        meta.delete();
        Result r = new Result();
        r.file = s.out; r.bytes = held; r.resumed = resumed; r.sha256 = sha;
        return r;
    }

    /** Opens url and follows bounded redirects; Range/If-Range are re-sent on every hop. */
    private static Conn openFollowingRedirects(Opener opener, Spec s, Map<String, String> rh)
            throws Refused {
        String current = s.url;
        for (int hops = 0; hops <= MAX_HOPS; hops++) {
            URL u;
            try { u = new URL(current); }
            catch (Exception e) { throw new Refused("bad download address"); }
            if (!"https".equals(u.getProtocol())) throw new Refused("download refused: not HTTPS");
            if (s.allowedHosts == null || !s.allowedHosts.contains(u.getHost()))
                throw new Refused("download refused: unexpected host");
            Conn conn;
            int code;
            try {
                conn = opener.open(u, rh);
                code = conn.code();
            } catch (IOException ioe) {
                throw new Refused("could not reach the download server");
            }
            if (code >= 300 && code < 400) {
                String loc = conn.header("Location");
                conn.disconnect();
                if (loc == null || !loc.startsWith("https://"))
                    throw new Refused("download refused: unsafe redirect");
                current = loc;
                continue;
            }
            return conn;
        }
        throw new Refused("download refused: too many redirects");
    }

    static File partOf(File out) { return new File(out.getPath() + ".part"); }
    static File metaOf(File out) { return new File(out.getPath() + ".meta"); }

    /** -1 when absent or unparseable. */
    private static long parseLong(String v) {
        if (v == null) return -1;
        try { return Long.parseLong(v.trim()); } catch (Exception e) { return -1; }
    }

    /** Weak ETags (W/...) can never satisfy If-Range: treated as absent. */
    private static String strong(String etag) {
        if (etag == null) return null;
        String t = etag.trim();
        if (t.startsWith("W/") || t.startsWith("w/")) return null;
        return t;
    }

    private static boolean validatorMatches(Meta m, String etag, String lastmod) {
        if (m.etag != null) return m.etag.equals(etag);
        if (m.lastmod != null) return m.lastmod.equals(lastmod);
        return false;
    }

    /**
     * Parses a Content-Range header (unit bytes, either START-END or a wildcard
     * range, with a numeric total or a wildcard total). Returns
     * [start, end, total] with total -1 for a wildcard total, or null when malformed.
     */
    static long[] parseContentRange(String h) {
        if (h == null || !h.startsWith("bytes ")) return null;
        String rest = h.substring("bytes ".length()).trim();
        int slash = rest.indexOf('/');
        if (slash < 0) return null;
        String totalS = rest.substring(slash + 1).trim();
        long total = "*".equals(totalS) ? -1 : parseLong(totalS);
        if (!"*".equals(totalS) && total < 0) return null;
        String range = rest.substring(0, slash).trim();
        if ("*".equals(range)) return new long[]{-1, -1, total};
        int dash = range.indexOf('-');
        if (dash < 0) return null;
        long start = parseLong(range.substring(0, dash));
        long end = parseLong(range.substring(dash + 1));
        if (start < 0 || end < start) return null;
        if (total >= 0 && end >= total) return null;
        return new long[]{start, end, total};
    }

    private static String sha256File(File f) throws Refused {
        try (InputStream in = new FileInputStream(f)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) != -1) md.update(buf, 0, n);
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new Refused("could not verify the downloaded file");
        }
    }

    /** Resume-state sidecar. Bound to url + expected digest + extra identity. */
    static final class Meta {
        String url;
        long size = -1;
        String etag;
        String lastmod;
        String sha256;
        Map<String, String> extra;

        String validator() { return etag != null ? etag : lastmod; }

        boolean binds(Spec s) {
            if (!Objects.equals(url, s.url)) return false;
            if (!Objects.equals(sha256, s.expectedSha256)) return false;
            if (s.extraMeta != null) {
                if (extra == null) return false;
                for (Map.Entry<String, String> e : s.extraMeta.entrySet())
                    if (!Objects.equals(extra.get("x." + e.getKey()), e.getValue())) return false;
            }
            return true;
        }

        void save(File meta) {
            Properties p = new Properties();
            p.setProperty("url", url == null ? "" : url);
            if (size >= 0) p.setProperty("size", Long.toString(size));
            if (etag != null) p.setProperty("etag", etag);
            if (lastmod != null) p.setProperty("lastmod", lastmod);
            if (sha256 != null) p.setProperty("sha256", sha256);
            if (extra != null) for (Map.Entry<String, String> e : extra.entrySet())
                p.setProperty("x." + e.getKey(), e.getValue());
            try (OutputStream o = new FileOutputStream(meta)) {
                p.store(o, "resumable download state");
            } catch (Exception ignored) {}
        }

        static Meta load(File meta) {
            if (!meta.exists()) return null;
            Properties p = new Properties();
            try (InputStream in = new FileInputStream(meta)) { p.load(in); }
            catch (Exception e) { return null; }
            Meta m = new Meta();
            m.url = p.getProperty("url");
            m.size = p.getProperty("size") != null ? parseLong(p.getProperty("size")) : -1;
            m.etag = p.getProperty("etag");
            m.lastmod = p.getProperty("lastmod");
            m.sha256 = p.getProperty("sha256");
            m.extra = new HashMap<>();
            for (String name : p.stringPropertyNames())
                if (name.startsWith("x.")) m.extra.put(name, p.getProperty(name));
            return m;
        }
    }
}
