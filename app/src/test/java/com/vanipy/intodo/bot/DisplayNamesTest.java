package com.vanipy.intodo.bot;

import org.junit.Test;
import static org.junit.Assert.*;

public class DisplayNamesTest {
    @Test public void visibleAliasesUseTheFamilyName() {
        assertEquals("intodo-v0.4.7.xdc", DisplayNames.text("itdxdc-v0.4.7.xdc"));
        assertEquals("intodo / intodo / intodo", DisplayNames.text("itdapk / ITDXDC / itdweb"));
    }
    @Test public void ordinaryNamesAndMissingValuesArePreserved() {
        assertEquals("notes.xdc", DisplayNames.text("notes.xdc"));
        assertEquals("intodo-v0.5.14.apk", DisplayNames.text("intodo-v0.5.14.apk"));
        assertNull(DisplayNames.text(null));
    }
}
