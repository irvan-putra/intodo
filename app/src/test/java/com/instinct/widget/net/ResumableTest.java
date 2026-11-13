package com.instinct.widget.net;

import static org.junit.Assert.*;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.HashSet;

/**
 * Deterministic failure-simulation tests for the resumable download core.
 * Every server behavior is scripted by FakeServer; no network, no sleeps.
 * Pure JUnit4 - the core is deliberately Android-free so these run on any JVM.
 */
public class ResumableTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private static final String PREFIX =
            "https://github.com/irvan-putra/intodo/releases/download/";
    private static final Set<String> HOSTS = new HashSet<>(Arrays.asList(
            "github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com"));
    private static final String URL_A = PREFIX + "v9.9.9/itdapk-v9.9.9.apk";
    private static final long MAX = 1_000_000;

    /** Scripted server. Mutate fields between get() calls to change its answers. */
    static final class FakeServer implements Resumable.Opener {
        byte[] body;
        String etag = "\"test-etag\"";
        String lastmod = null;
        boolean rangeAware = true;     // false = ignores Range, always full 200
        boolean ifRangeStale = false;  // simulate a changed object: Range+If-Range -> 200
        boolean wrongStart = false;    // 206 whose Content-Range starts at have+1
        boolean noContentRange = false;// 206 without a Content-Range header
        boolean starTotal = false;     // 206 Content-Range with '*' total
        boolean omitContentLength = false;
        Integer forceCode = null;
        String redirectTo = null;      // emit one 302 to this location first
        boolean redirected = false;
        int eofAt = -1;                // clean EOF after N bytes of the (ranged) body
        int failAt = -1;               // IOException after N bytes
        String cr416 = null;           // Content-Range for 416 answers; "OMIT" = no header
        final List<Map<String, String>> requests = new ArrayList<>();
        int opens = 0;

        FakeServer(byte[] body) { this.body = body; }

        @Override public Resumable.Conn open(URL url, Map<String, String> rh) {
            opens++;
            requests.add(new LinkedHashMap<>(rh));
            if (redirectTo != null && !redirected) {
                redirected = true;
                return conn(302, hdr("Location", redirectTo), new byte[0]);
            }
            if (forceCode != null) return conn(forceCode, new HashMap<>(), new byte[0]);
            String range = rh.get("Range");
            if (range != null && rangeAware && !ifRangeStale) {
                long have = Long.parseLong(range.substring("bytes=".length(), range.length() - 1));
                if (have >= body.length) {
                    Map<String, String> h = new HashMap<>();
                    if (cr416 == null) h.put("Content-Range", "bytes */" + body.length);
                    else if (!"OMIT".equals(cr416)) h.put("Content-Range", cr416);
                    return conn(416, h, new byte[0]);
                }
                long start = wrongStart ? have + 1 : have;
                byte[] rest = Arrays.copyOfRange(body, (int) have, body.length);
                Map<String, String> h = new HashMap<>();
                if (!noContentRange) {
                    h.put("Content-Range", "bytes " + start + "-" + (body.length - 1)
                            + "/" + (starTotal ? "*" : body.length));
                }
                if (!omitContentLength) h.put("Content-Length", Integer.toString(rest.length));
                if (etag != null) h.put("ETag", etag);
                if (lastmod != null) h.put("Last-Modified", lastmod);
                return conn(206, h, rest);
            }
            Map<String, String> h = new HashMap<>();
            if (!omitContentLength) h.put("Content-Length", Integer.toString(body.length));
            if (etag != null) h.put("ETag", etag);
            if (lastmod != null) h.put("Last-Modified", lastmod);
            return conn(200, h, body);
        }

        private Resumable.Conn conn(int code, Map<String, String> headers, byte[] payload) {
            return new Resumable.Conn() {
                @Override public int code() { return code; }
                @Override public String header(String name) {
                    for (Map.Entry<String, String> e : headers.entrySet())
                        if (e.getKey().equalsIgnoreCase(name)) return e.getValue();
                    return null;
                }
                @Override public InputStream body() {
                    return new InputStream() {
                        int pos = 0;
                        @Override public int read(byte[] b, int off, int len) throws IOException {
                            int limit = eofAt >= 0 ? Math.min(eofAt, payload.length) : payload.length;
                            if (failAt >= 0 && pos >= failAt) throw new IOException("simulated drop");
                            if (pos >= limit) return -1;
                            int n = failAt >= 0 ? Math.min(Math.min(len, limit - pos), failAt - pos)
                                                : Math.min(len, limit - pos);
                            System.arraycopy(payload, pos, b, off, n);
                            pos += n;
                            return n;
                        }
                        @Override public int read() throws IOException {
                            byte[] one = new byte[1];
                            int n = read(one, 0, 1);
                            return n < 0 ? -1 : one[0] & 0xFF;
                        }
                    };
                }
                @Override public void disconnect() {}
            };
        }
    }

    private static Map<String, String> hdr(String k, String v) {
        Map<String, String> h = new HashMap<>(); h.put(k, v); return h;
    }

    private static String sha256(byte[] b) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        StringBuilder sb = new StringBuilder();
        for (byte x : md.digest(b)) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    private Resumable.Spec spec(FakeServer fs, File out, String digest) {
        Resumable.Spec s = new Resumable.Spec();
        s.url = URL_A;
        s.allowedPrefix = PREFIX;
        s.allowedHosts = HOSTS;
        s.maxBytes = MAX;
        s.expectedSha256 = digest;
        s.out = out;
        s.opener = fs;
        return s;
    }

    private static byte[] body(int n) {
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) b[i] = (byte) (i * 31 + 7);
        return b;
    }

    private static byte[] read(File f) throws Exception {
        return Files.readAllBytes(f.toPath());
    }

    // --- happy paths ---------------------------------------------------------

    @Test public void freshDownloadCompletes() throws Exception {
        byte[] b = body(200_000);
        FakeServer fs = new FakeServer(b);
        File out = tmp.newFile("app.apk");
        Resumable.Result r = Resumable.get(spec(fs, out, sha256(b)));
        assertArrayEquals(b, read(out));
        assertEquals(b.length, r.bytes);
        assertFalse(r.resumed);
        assertEquals(sha256(b), r.sha256);
        assertEquals("identity", fs.requests.get(0).get("Accept-Encoding"));
        assertNull(fs.requests.get(0).get("Range"));
        assertFalse(new File(out.getPath() + ".part").exists());
        assertFalse(new File(out.getPath() + ".meta").exists());
    }

    @Test public void resumeCompletesWithExact206() throws Exception {
        byte[] b = body(200_000);
        FakeServer fs = new FakeServer(b);
        File out = tmp.newFile("app.apk");
        fs.eofAt = 100_000; // clean-EOF truncation halfway
        try { Resumable.get(spec(fs, out, sha256(b))); fail("expected Incomplete"); }
        catch (Resumable.Incomplete i) {
            assertEquals(100_000, i.done);
            assertEquals(200_000, i.total);
            assertTrue(i.resumable);
        }
        assertFalse(out.exists() && read(out).length == b.length); // never treated as complete
        assertEquals(0, read(out).length); // previous (empty) out untouched by the failed round
        fs.eofAt = -1; // connection healthy now
        Resumable.Result r = Resumable.get(spec(fs, out, sha256(b)));
        assertArrayEquals(b, read(out));
        assertTrue(r.resumed);
        Map<String, String> resume = fs.requests.get(1);
        assertEquals("bytes=100000-", resume.get("Range"));
        assertEquals("\"test-etag\"", resume.get("If-Range"));
        assertEquals("identity", resume.get("Accept-Encoding"));
    }

    @Test public void progressShowsResumedBytesBeforeNetwork() throws Exception {
        byte[] b = body(200_000);
        FakeServer fs = new FakeServer(b);
        File out = tmp.newFile("app.apk");
        fs.eofAt = 50_000;
        try { Resumable.get(spec(fs, out, sha256(b))); } catch (Resumable.Incomplete ignored) {}
        fs.eofAt = -1;
        List<long[]> events = new ArrayList<>();
        int[] opensAtFirstEvent = {-1};
        int opensBefore = fs.opens;
        Resumable.Spec s = spec(fs, out, sha256(b));
        s.progress = (done, total) -> {
            if (opensAtFirstEvent[0] < 0) opensAtFirstEvent[0] = fs.opens;
            events.add(new long[]{done, total});
        };
        Resumable.get(s);
        assertEquals(opensBefore, opensAtFirstEvent[0]); // fired BEFORE this call's first open
        assertArrayEquals(new long[]{50_000, 200_000}, events.get(0));
    }

    // --- resume safety --------------------------------------------------------

    @Test public void wrongStart206RestartsClean() throws Exception {
        byte[] b = body(200_000);
        FakeServer fs = new FakeServer(b);
        File out = tmp.newFile("app.apk");
        fs.eofAt = 100_000;
        try { Resumable.get(spec(fs, out, sha256(b))); } catch (Resumable.Incomplete ignored) {}
        fs.eofAt = -1;
        fs.wrongStart = true; // first resume answer is a broken 206
        Resumable.Result r = Resumable.get(spec(fs, out, sha256(b)));
        assertArrayEquals(b, read(out)); // restarted clean, NOT patched onto
        // 3 requests: initial, broken 206, clean 200
        assertEquals(3, fs.opens);
        assertNull(fs.requests.get(2).get("Range"));
    }

    @Test public void missingContentRange206Restarts() throws Exception {
        byte[] b = body(200_000);
        FakeServer fs = new FakeServer(b);
        File out = tmp.newFile("app.apk");
        fs.eofAt = 100_000;
        try { Resumable.get(spec(fs, out, sha256(b))); } catch (Resumable.Incomplete ignored) {}
        fs.eofAt = -1;
        fs.noContentRange = true;
        Resumable.Result r = Resumable.get(spec(fs, out, sha256(b)));
        assertArrayEquals(b, read(out));
        assertEquals(3, fs.opens);
    }

    @Test public void starTotal206Restarts() throws Exception {
        byte[] b = body(200_000);
        FakeServer fs = new FakeServer(b);
        File out = tmp.newFile("app.apk");
        fs.eofAt = 100_000;
        try { Resumable.get(spec(fs, out, sha256(b))); } catch (Resumable.Incomplete ignored) {}
        fs.eofAt = -1;
        fs.starTotal = true;
        Resumable.Result r = Resumable.get(spec(fs, out, sha256(b)));
        assertArrayEquals(b, read(out));
        assertEquals(3, fs.opens);
    }

    @Test public void brokenResumeThenTruncatedRestartStaysHonest() throws Exception {
        byte[] b = body(200_000);
        FakeServer fs = new FakeServer(b);
        File out = tmp.newFile("app.apk");
        fs.eofAt = 100_000;
        try { Resumable.get(spec(fs, out, sha256(b))); } catch (Resumable.Incomplete ignored) {}
        fs.eofAt = -1;
        fs.wrongStart = true; // first resume answer is a broken 206
        // The core discards and restarts clean; make THAT restart truncate too:
        fs.eofAt = 10_000;
        try { Resumable.get(spec(fs, out, sha256(b))); fail(); }
        catch (Resumable.Incomplete i) {
            assertEquals(10_000, i.done); // clean restart count, never a patched-together number
            assertTrue(i.resumable);
        }
    }

    @Test public void rangeIgnored200ReplacesPartial() throws Exception {
        byte[] b = body(200_000);
        FakeServer fs = new FakeServer(b);
        File out = tmp.newFile("app.apk");
        fs.eofAt = 100_000;
        try { Resumable.get(spec(fs, out, sha256(b))); } catch (Resumable.Incomplete ignored) {}
        fs.eofAt = -1;
        fs.rangeAware = false; // server ignores Range: full 200
        Resumable.Result r = Resumable.get(spec(fs, out, sha256(b)));
        assertArrayEquals(b, read(out)); // replaced, not appended (would be 300k)
    }

    @Test public void changedValidatorRestartsClean() throws Exception {
        byte[] oldBytes = body(200_000);
        FakeServer fs = new FakeServer(oldBytes);
        File out = tmp.newFile("app.apk");
        fs.eofAt = 100_000;
        try { Resumable.get(spec(fs, out, null)); } catch (Resumable.Incomplete ignored) {}
        // Object changed on the server: new bytes, new etag, If-Range no longer matches.
        // (No digest in play: a changed expected digest would discard the partial at
        // binding time, which metaDigestMismatchDiscardsPartial covers separately.)
        byte[] newBytes = body(180_000);
        fs.body = newBytes;
        fs.etag = "\"other-etag\"";
        fs.eofAt = -1;
        fs.ifRangeStale = true; // RFC: stale If-Range -> 200 full body
        Resumable.Result r = Resumable.get(spec(fs, out, null));
        assertArrayEquals(newBytes, read(out));
        assertEquals("bytes=100000-", fs.requests.get(1).get("Range")); // resume WAS attempted
    }

    @Test public void metaWithoutValidatorNeverResumes() throws Exception {
        byte[] b = body(200_000);
        FakeServer fs = new FakeServer(b);
        File out = tmp.newFile("app.apk");
        // Hand-craft a partial + meta WITHOUT any validator (legacy/foreign state)
        File part = new File(out.getPath() + ".part");
        Files.write(part.toPath(), Arrays.copyOf(b, 100_000));
        Properties p = new Properties();
        p.setProperty("url", URL_A);
        p.setProperty("size", "200000");
        p.setProperty("sha256", sha256(b));
        try (java.io.OutputStream o = Files.newOutputStream(new File(out.getPath() + ".meta").toPath())) {
            p.store(o, "");
        }
        Resumable.get(spec(fs, out, sha256(b)));
        assertNull(fs.requests.get(0).get("Range")); // no resume attempted: safe restart
        assertArrayEquals(b, read(out));
    }

    @Test public void serverWithoutValidatorCannotResume() throws Exception {
        byte[] b = body(200_000);
        FakeServer fs = new FakeServer(b);
        fs.etag = null; // no validator at all
        File out = tmp.newFile("app.apk");
        fs.eofAt = 100_000;
        try { Resumable.get(spec(fs, out, sha256(b))); fail(); }
        catch (Resumable.Incomplete i) {
            assertFalse(i.resumable); // honest: next attempt restarts from zero
        }
        assertFalse(new File(out.getPath() + ".part").exists()); // untrustworthy partial dropped
        assertFalse(new File(out.getPath() + ".meta").exists());
    }

    @Test public void metaUrlMismatchDiscardsPartial() throws Exception {
        byte[] b = body(200_000);
        FakeServer fs = new FakeServer(b);
        File out = tmp.newFile("app.apk");
        File part = new File(out.getPath() + ".part");
        Files.write(part.toPath(), Arrays.copyOf(b, 100_000));
        Properties p = new Properties();
        p.setProperty("url", PREFIX + "v1.0.0/itdapk-v1.0.0.apk"); // different asset
        p.setProperty("size", "200000");
        p.setProperty("etag", "\"test-etag\"");
        try (java.io.OutputStream o = Files.newOutputStream(new File(out.getPath() + ".meta").toPath())) {
            p.store(o, "");
        }
        Resumable.get(spec(fs, out, sha256(b)));
        assertNull(fs.requests.get(0).get("Range"));
        assertArrayEquals(b, read(out));
    }

    @Test public void metaDigestMismatchDiscardsPartial() throws Exception {
        byte[] b = body(200_000);
        FakeServer fs = new FakeServer(b);
        File out = tmp.newFile("app.apk");
        fs.eofAt = 100_000;
        try { Resumable.get(spec(fs, out, sha256(b))); } catch (Resumable.Incomplete ignored) {}
        // The release's reported digest changed: the partial must not be reused
        fs.eofAt = -1;
        fs.body = body(200_000); // same length, same etag, DIFFERENT expected digest below
        byte[] other = body(123_456);
        try { Resumable.get(spec(fs, out, sha256(other))); fail(); }
        catch (Resumable.Refused r) { assertTrue(r.getMessage().contains("checksum")); }
        Map<String, String> first = fs.requests.get(1);
        assertNull(first.get("Range")); // discarded, not resumed
    }

    // --- 416 handling ---------------------------------------------------------

    @Test public void completePartialFinishesAt416WithoutMoreBytes() throws Exception {
        byte[] b = body(200_000);
        FakeServer fs = new FakeServer(b);
        File outDir = tmp.newFolder("dirout"); // rename target blocked: out is a directory
        File out = new File(outDir.getParentFile(), "app-" + System.nanoTime() + ".apk");
        assertTrue(out.mkdir()); // out is a DIRECTORY: renameTo will fail
        try { Resumable.get(spec(fs, out, sha256(b))); fail("expected rename Refused"); }
        catch (Resumable.Refused r) { assertTrue(r.getMessage().contains("move the verified download")); }
        // verified partial + meta kept for a 100% resume
        File part = new File(out.getPath() + ".part");
        assertTrue(part.exists());
        assertArrayEquals(b, read(part));
        // free the destination and retry: 416 complete-already finishes without any body bytes
        assertTrue(out.delete());
        Resumable.Result r = Resumable.get(spec(fs, out, sha256(b)));
        assertArrayEquals(b, read(out));
        assertEquals(2, fs.opens); // one per call; second call sent only the Range probe
        assertEquals("bytes=200000-", fs.requests.get(1).get("Range"));
    }

    @Test public void stalePartial416RestartsClean() throws Exception {
        byte[] b = body(200_000);
        FakeServer fs = new FakeServer(b);
        File out = tmp.newFile("app.apk");
        fs.eofAt = 100_000;
        try { Resumable.get(spec(fs, out, null)); } catch (Resumable.Incomplete ignored) {}
        // Server object shrank: our 100k partial is beyond its end. No digest in play
        // (a changed digest would discard at binding time instead of probing).
        fs.body = body(50_000);
        fs.eofAt = -1;
        Resumable.Result r = Resumable.get(spec(fs, out, null));
        assertArrayEquals(fs.body, read(out));
        assertEquals(3, fs.opens); // probe(416) + clean 200
    }

    /** Seeds a complete-on-disk partial + meta by making the first rename fail. */
    private File seedCompletePartial(byte[] b, FakeServer fs) throws Exception {
        File out = new File(tmp.getRoot(), "seed-" + System.nanoTime() + ".apk");
        assertTrue(out.mkdir()); // out is a DIRECTORY: renameTo fails, partial+meta stay
        try { Resumable.get(spec(fs, out, sha256(b))); fail("expected rename Refused"); }
        catch (Resumable.Refused r) { assertTrue(r.getMessage().contains("move the verified download")); }
        assertArrayEquals(b, read(new File(out.getPath() + ".part")));
        assertTrue(out.delete()); // free the destination for the real run
        return out;
    }

    @Test public void completePartial416MissingContentRangeRestarts() throws Exception {
        byte[] b = body(200_000);
        FakeServer fs = new FakeServer(b);
        File out = seedCompletePartial(b, fs);
        fs.cr416 = "OMIT"; // 416 without any Content-Range: must NOT vouch for completeness
        Resumable.Result r = Resumable.get(spec(fs, out, sha256(b)));
        assertArrayEquals(b, read(out));
        // probe(416) then a clean restart WITHOUT Range
        assertNull(fs.requests.get(fs.requests.size() - 1).get("Range"));
    }

    @Test public void completePartial416MalformedContentRangeRestarts() throws Exception {
        byte[] b = body(200_000);
        FakeServer fs = new FakeServer(b);
        File out = seedCompletePartial(b, fs);
        fs.cr416 = "garbage";
        Resumable.Result r = Resumable.get(spec(fs, out, sha256(b)));
        assertArrayEquals(b, read(out));
        assertNull(fs.requests.get(fs.requests.size() - 1).get("Range"));
    }

    @Test public void completePartial416WildcardTotalRestarts() throws Exception {
        byte[] b = body(200_000);
        FakeServer fs = new FakeServer(b);
        File out = seedCompletePartial(b, fs);
        fs.cr416 = "bytes */*";
        Resumable.Result r = Resumable.get(spec(fs, out, sha256(b)));
        assertArrayEquals(b, read(out));
        assertNull(fs.requests.get(fs.requests.size() - 1).get("Range"));
    }

    @Test public void completePartial416WrongTotalRestarts() throws Exception {
        byte[] b = body(200_000);
        FakeServer fs = new FakeServer(b);
        File out = seedCompletePartial(b, fs);
        fs.cr416 = "bytes */999999"; // well-formed but NOT our byte count
        Resumable.Result r = Resumable.get(spec(fs, out, sha256(b)));
        assertArrayEquals(b, read(out));
        assertNull(fs.requests.get(fs.requests.size() - 1).get("Range"));
    }

    @Test public void completePartial416RangedFormRejected() throws Exception {
        byte[] b = body(200_000);
        FakeServer fs = new FakeServer(b);
        File out = seedCompletePartial(b, fs);
        fs.cr416 = "bytes 0-199999/200000"; // numbers look right but 416 must use the wildcard form
        Resumable.Result r = Resumable.get(spec(fs, out, sha256(b)));
        assertArrayEquals(b, read(out));
        assertNull(fs.requests.get(fs.requests.size() - 1).get("Range"));
    }

    // --- completeness and integrity -------------------------------------------

    @Test public void unknownTotalWithDigestCompletes() throws Exception {
        byte[] b = body(200_000);
        FakeServer fs = new FakeServer(b);
        fs.omitContentLength = true;
        File out = tmp.newFile("app.apk");
        Resumable.Result r = Resumable.get(spec(fs, out, sha256(b)));
        assertArrayEquals(b, read(out));
    }

    @Test public void unknownTotalWithoutDigestRefused() throws Exception {
        byte[] b = body(200_000);
        FakeServer fs = new FakeServer(b);
        fs.omitContentLength = true;
        File out = tmp.newFile("app.apk");
        Resumable.Spec s = spec(fs, out, null); // requireDigest false, but no length AND no digest
        try { Resumable.get(s); fail(); }
        catch (Resumable.Refused r) { assertTrue(r.getMessage().contains("cannot be verified")); }
    }

    @Test public void requireDigestRefusesWhenAbsent() throws Exception {
        byte[] b = body(200_000);
        FakeServer fs = new FakeServer(b);
        File out = tmp.newFile("app.apk");
        Resumable.Spec s = spec(fs, out, null);
        s.requireDigest = true;
        try { Resumable.get(s); fail(); }
        catch (Resumable.Refused r) { assertTrue(r.getMessage().contains("checksum")); }
        assertEquals(0, fs.opens); // refused before any network
    }

    @Test public void digestMismatchRefusesAndPreservesPreviousOut() throws Exception {
        byte[] previous = "previous-good".getBytes("UTF-8");
        byte[] evil = body(100_000);
        FakeServer fs = new FakeServer(evil);
        File out = tmp.newFile("app.apk");
        Files.write(out.toPath(), previous);
        try { Resumable.get(spec(fs, out, sha256(body(200_000)))); fail(); }
        catch (Resumable.Refused r) { assertTrue(r.getMessage().contains("checksum")); }
        assertArrayEquals(previous, read(out)); // good previous file untouched
        assertFalse(new File(out.getPath() + ".part").exists());
    }

    @Test public void verifierFailurePreservesPreviousOut() throws Exception {
        byte[] previous = "previous-good".getBytes("UTF-8");
        byte[] b = body(100_000);
        FakeServer fs = new FakeServer(b);
        File out = tmp.newFile("app.apk");
        Files.write(out.toPath(), previous);
        Resumable.Spec s = spec(fs, out, sha256(b));
        s.verifier = part -> { throw new Resumable.Refused("not a valid app package"); };
        try { Resumable.get(s); fail(); }
        catch (Resumable.Refused r) { assertEquals("not a valid app package", r.getMessage()); }
        assertArrayEquals(previous, read(out));
        assertFalse(new File(out.getPath() + ".part").exists());
    }

    @Test public void declaredOversizeRefusedBeforeStreaming() throws Exception {
        byte[] b = body((int) MAX + 10);
        FakeServer fs = new FakeServer(b);
        File out = tmp.newFile("app.apk");
        try { Resumable.get(spec(fs, out, null)); fail(); }
        catch (Resumable.Refused r) { assertTrue(r.getMessage().contains("too large")); }
        assertFalse(new File(out.getPath() + ".part").exists());
    }

    @Test public void streamedOversizeRefusedMidStream() throws Exception {
        byte[] b = body((int) MAX + 10);
        FakeServer fs = new FakeServer(b);
        fs.omitContentLength = true; // declared unknown; cap enforced while streaming
        File out = tmp.newFile("app.apk");
        try { Resumable.get(spec(fs, out, sha256(b))); fail(); }
        catch (Resumable.Refused r) { assertTrue(r.getMessage().contains("too large")); }
        assertFalse(new File(out.getPath() + ".part").exists());
    }

    // --- redirect gates ---------------------------------------------------------

    @Test public void httpRedirectRefused() throws Exception {
        FakeServer fs = new FakeServer(body(100));
        fs.redirectTo = "http://github.com/insecure";
        File out = tmp.newFile("app.apk");
        try { Resumable.get(spec(fs, out, null)); fail(); }
        catch (Resumable.Refused r) { assertTrue(r.getMessage().contains("unsafe redirect")); }
    }

    @Test public void foreignHostRedirectRefused() throws Exception {
        FakeServer fs = new FakeServer(body(100));
        fs.redirectTo = "https://evil.example.com/x";
        File out = tmp.newFile("app.apk");
        try { Resumable.get(spec(fs, out, null)); fail(); }
        catch (Resumable.Refused r) { assertTrue(r.getMessage().contains("unexpected host")); }
    }

    @Test public void allowedRedirectKeepsRangeHeaders() throws Exception {
        byte[] b = body(200_000);
        FakeServer fs = new FakeServer(b);
        File out = tmp.newFile("app.apk");
        fs.eofAt = 100_000;
        try { Resumable.get(spec(fs, out, sha256(b))); } catch (Resumable.Incomplete ignored) {}
        fs.eofAt = -1;
        fs.redirectTo = "https://release-assets.githubusercontent.com/prod/itdapk.apk";
        Resumable.get(spec(fs, out, sha256(b)));
        assertArrayEquals(b, read(out));
        assertEquals(3, fs.opens); // resume probe -> 302 -> final 206
        assertEquals("bytes=100000-", fs.requests.get(2).get("Range")); // re-sent after redirect
    }

    @Test public void wrongPrefixRefused() throws Exception {
        FakeServer fs = new FakeServer(body(100));
        File out = tmp.newFile("app.apk");
        Resumable.Spec s = spec(fs, out, null);
        s.url = "https://github.com/someoneelse/other/releases/download/v1/x.apk";
        try { Resumable.get(s); fail(); }
        catch (Resumable.Refused r) { assertTrue(r.getMessage().contains("unexpected download source")); }
        assertEquals(0, fs.opens);
    }

    @Test public void httpErrorRefused() throws Exception {
        FakeServer fs = new FakeServer(body(100));
        fs.forceCode = 500;
        File out = tmp.newFile("app.apk");
        try { Resumable.get(spec(fs, out, null)); fail(); }
        catch (Resumable.Refused r) { assertTrue(r.getMessage().contains("HTTP 500")); }
    }

    // --- Content-Range parser ---------------------------------------------------

    @Test public void contentRangeParsing() {
        assertArrayEquals(new long[]{1000, 1999, 5000}, Resumable.parseContentRange("bytes 1000-1999/5000"));
        assertArrayEquals(new long[]{-1, -1, 5000}, Resumable.parseContentRange("bytes */5000"));
        assertArrayEquals(new long[]{1000, 1999, -1}, Resumable.parseContentRange("bytes 1000-1999/*"));
        assertNull(Resumable.parseContentRange(null));
        assertNull(Resumable.parseContentRange("bytes 1999-1000/5000")); // end < start
        assertNull(Resumable.parseContentRange("bytes 1000-5999/5000")); // end >= total
        assertNull(Resumable.parseContentRange("items 1000-1999/5000"));
        assertNull(Resumable.parseContentRange("bytes 1000/5000"));
    }
}
