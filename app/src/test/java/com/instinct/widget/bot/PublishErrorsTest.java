package com.instinct.widget.bot;
import static org.junit.Assert.*;
import org.junit.Test;

public class PublishErrorsTest {
    @Test public void versionUnresolvedGetsPlainLead() {
        String lead = PublishErrors.friendlyLead(
            "manifest version unresolved in /data/user/0/x/files/publish-abc.xdc",
            "itdxdc-v0.4.3-final.xdc");
        assertNotNull(lead);
        assertTrue(lead.contains("\"itdxdc-v0.4.3-final.xdc\""));
        assertTrue(lead.contains("no version number"));
        assertTrue(lead.contains(PublishErrors.LATEST_XDC_URL));
    }
    @Test public void fallsBackToGenericName() {
        String lead = PublishErrors.friendlyLead("manifest version unresolved in /x", null);
        assertTrue(lead.contains("The selected file"));
        String lead2 = PublishErrors.friendlyLead("manifest version unresolved in /x", "");
        assertTrue(lead2.contains("The selected file"));
    }
    @Test public void otherErrorsHaveNoLead() {
        assertNull(PublishErrors.friendlyLead("relay down", "f.xdc"));
        assertNull(PublishErrors.friendlyLead(null, "f.xdc"));
    }
}
