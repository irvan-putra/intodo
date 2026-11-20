package com.vanipy.tindak.bot;

import org.junit.Test;
import static org.junit.Assert.*;

public class DisplayNamesTest {
    @Test public void visibleAliasesUseTheFamilyName() {
        assertEquals("tindak-v0.4.7.xdc", DisplayNames.text("tindak-v0.4.7.xdc"));
        assertEquals("tindak / tindak / tindak", DisplayNames.text("tindak / tindak / tindak"));
    }
    @Test public void ordinaryNamesAndMissingValuesArePreserved() {
        assertEquals("notes.xdc", DisplayNames.text("notes.xdc"));
        assertEquals("tindak-v0.5.14.apk", DisplayNames.text("tindak-v0.5.14.apk"));
        assertNull(DisplayNames.text(null));
    }
}
