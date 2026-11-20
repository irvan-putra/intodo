package com.vanipy.tindak;
import org.junit.Test;
import static org.junit.Assert.*;
/** Current release identity must not silently revert to the historical app. */
public class AndroidIdentityTest {
    @Test public void currentBuildUsesVanipy() {
        assertEquals("com.vanipy.tindak", BuildConfig.APPLICATION_ID);
        assertEquals("com.vanipy.tindak", MainActivity.class.getPackage().getName());
    }
    @Test public void updaterOnlyAcceptsStrictlyNewerVersions() {
        assertFalse(UpdateCheck.isNewer("0.5.10", BuildConfig.VERSION_NAME));
        assertTrue(UpdateCheck.isNewer("0.5.13", "0.5.12"));
        assertFalse(UpdateCheck.isNewer("0.5.12", "0.5.12"));
    }
}
