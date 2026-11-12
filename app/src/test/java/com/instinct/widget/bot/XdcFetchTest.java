package com.instinct.widget.bot;
import static org.junit.Assert.*;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=32)
public class XdcFetchTest {
    private static JSONObject rel(String tag, boolean draft, boolean prerelease, JSONObject... assets) throws Exception {
        JSONObject r = new JSONObject();
        r.put("tag_name", tag);
        r.put("draft", draft);
        r.put("prerelease", prerelease);
        JSONArray a = new JSONArray();
        for (JSONObject x : assets) a.put(x);
        r.put("assets", a);
        return r;
    }
    private static JSONObject asset(String name, String digest) throws Exception {
        JSONObject a = new JSONObject();
        a.put("name", name);
        a.put("browser_download_url",
            "https://github.com/irvan-putra/intodo/releases/download/v9.9.9/" + name);
        if (digest != null) a.put("digest", digest);
        return a;
    }

    @Test public void picksFirstNewestReleaseWithXdc() throws Exception {
        // Newest release has NO xdc (apk-only release): must not hide the older one.
        JSONArray releases = new JSONArray();
        releases.put(rel("v0.4.8", false, false, asset("itdapk-v0.4.8.apk", null)));
        releases.put(rel("v0.4.7", false, false,
            asset("itdapk-v0.4.7.apk", null),
            asset("itdxdc-v0.4.3-final.xdc", "sha256:abc123")));
        XdcFetch.Found f = XdcFetch.choose(releases);
        assertNotNull(f);
        assertEquals("v0.4.7", f.tag);
        assertEquals("itdxdc-v0.4.3-final.xdc", f.name);
        assertEquals("abc123", f.sha256);
    }
    @Test public void skipsDraftsAndPrereleases() throws Exception {
        JSONArray releases = new JSONArray();
        releases.put(rel("v0.5.0", true, false, asset("itdxdc-v0.5.0.xdc", null)));
        releases.put(rel("v0.5.0-rc1", false, true, asset("itdxdc-v0.5.0.xdc", null)));
        releases.put(rel("v0.4.7", false, false, asset("itdxdc-v0.4.3-final.xdc", null)));
        XdcFetch.Found f = XdcFetch.choose(releases);
        assertEquals("v0.4.7", f.tag);
    }
    @Test public void rejectsWrongRepoUrl() throws Exception {
        JSONObject bad = asset("itdxdc-v0.4.3.xdc", null);
        bad.put("browser_download_url",
            "https://github.com/someone-else/other/releases/download/v1/itdxdc-v0.4.3.xdc");
        JSONArray releases = new JSONArray();
        releases.put(rel("v1", false, false, bad));
        assertNull(XdcFetch.choose(releases));
    }
    @Test public void rejectsNonXdcNames() throws Exception {
        JSONArray releases = new JSONArray();
        releases.put(rel("v1", false, false, asset("itdapk-v1.0.0.apk", null)));
        releases.put(rel("v1", false, false, asset("random.xdc", null)));
        releases.put(rel("v1", false, false, asset("itdxdc-nofinal.xdc", null)));
        assertNull(XdcFetch.choose(releases));
    }
    @Test public void backportDoesNotShadowNewerVersion() throws Exception {
        // NEWER release carries an OLDER app version (backport), an OLDER
        // release carries the NEWEST app version: max numeric semver wins,
        // not release order.
        JSONArray releases = new JSONArray();
        releases.put(rel("v0.4.8", false, false, asset("itdxdc-v0.4.3-final.xdc", null)));
        releases.put(rel("v0.4.7", false, false, asset("itdxdc-v0.5.0.xdc", null)));
        XdcFetch.Found f = XdcFetch.choose(releases);
        assertNotNull(f);
        assertEquals("itdxdc-v0.5.0.xdc", f.name);
        assertEquals("v0.4.7", f.tag);
    }
    @Test public void semverCompare() {
        assertTrue(XdcFetch.compareSemver(new int[]{0,5,0}, new int[]{0,4,9}) > 0);
        assertTrue(XdcFetch.compareSemver(new int[]{1,0,0}, new int[]{0,9,9}) > 0);
        assertTrue(XdcFetch.compareSemver(new int[]{0,4,3}, new int[]{0,4,3}) == 0);
        assertTrue(XdcFetch.compareSemver(new int[]{0,4,2}, new int[]{0,4,3}) < 0);
    }
    @Test public void digestAbsentYieldsNullSha() throws Exception {
        JSONArray releases = new JSONArray();
        releases.put(rel("v1", false, false, asset("itdxdc-v1.2.3.xdc", null)));
        XdcFetch.Found f = XdcFetch.choose(releases);
        assertNotNull(f);
        assertNull(f.sha256);
    }
    @Test public void manifestVersionParsing() {
        assertEquals("0.4.3", XdcFetch.manifestVersionFromText(
            "name = \"x\"\nversion = \"0.4.3\"\n"));
        assertNull(XdcFetch.manifestVersionFromText("name = \"x\"\n"));
        assertNull(XdcFetch.manifestVersionFromText("version = \"beta\"\n"));
        assertEquals("1.2.3", XdcFetch.manifestVersionFromText("version='1.2.3'\n"));
    }
}
