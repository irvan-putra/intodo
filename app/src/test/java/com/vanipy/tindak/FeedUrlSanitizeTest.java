package com.vanipy.tindak;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * canonicalFeedUrl: the Files viewer writes the served revision into the
 * address bar as "?preview=<revision>". A feed URL saved with that pin works
 * signed-out only while the pinned revision is the current published one;
 * after a newer publish it hits an owner-only sign-in wall and sync fails
 * with "payload not found on page" (phone-observed on 0.5.9). The sanitizer
 * must drop only the preview pin and leave everything else alone.
 */
public class FeedUrlSanitizeTest {

    private static final String BASE = "https://files.synthetic.test/example-feed";

    @Test
    public void nullBecomesEmpty() {
        assertEquals("", SyncEngine.canonicalFeedUrl(null));
    }

    @Test
    public void plainUrlUnchanged() {
        assertEquals(BASE, SyncEngine.canonicalFeedUrl(BASE));
    }

    @Test
    public void trimsWhitespace() {
        assertEquals(BASE, SyncEngine.canonicalFeedUrl("  " + BASE + " \n"));
    }

    @Test
    public void dropsSoloPreviewParam() {
        assertEquals(BASE, SyncEngine.canonicalFeedUrl(
                BASE + "?preview=filerevision-test-dummy"));
    }

    @Test
    public void dropsPreviewAmongOthers() {
        assertEquals(BASE + "?via=link&x=1", SyncEngine.canonicalFeedUrl(
                BASE + "?via=link&preview=filerevision-test-dummy&x=1"));
    }

    @Test
    public void previewFirstKeepsRest() {
        assertEquals(BASE + "?x=1", SyncEngine.canonicalFeedUrl(
                BASE + "?preview=filerevision-test-dummy&x=1"));
    }

    @Test
    public void valueNamedPreviewIsKept() {
        // only the parameter name matters; "x=preview" is not the pin
        assertEquals(BASE + "?x=preview", SyncEngine.canonicalFeedUrl(BASE + "?x=preview"));
    }

    @Test
    public void fragmentSurvives() {
        assertEquals(BASE + "#frag", SyncEngine.canonicalFeedUrl(
                BASE + "?preview=filerevision-test-dummy#frag"));
    }

    @Test
    public void emptyQueryCollapses() {
        assertEquals(BASE, SyncEngine.canonicalFeedUrl(BASE + "?preview="));
    }
}
