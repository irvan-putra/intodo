package com.instinct.widget.bot;

import static org.junit.Assert.assertEquals;

import android.content.Context;
import com.instinct.widget.R;
import com.instinct.widget.BuildConfig;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** The notification title and launcher label share one display-name resource.
 * Each supported application ID has an exact expected label. Host tests do
 * not prove a device runtime result.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 32)
public class BotNotificationNamingTest {

    @Test
    public void appNameResourceMatchesBuildIdentity() {
        Context ctx = RuntimeEnvironment.getApplication();
        String expected;
        if ("com.instinct.widget".equals(BuildConfig.APPLICATION_ID)) expected = "itdapk";
        else if ("com.vanipy.intodo".equals(BuildConfig.APPLICATION_ID)) expected = "Intodo";
        else throw new AssertionError("unexpected application ID");
        assertEquals(expected, ctx.getString(R.string.app_name));
        assertEquals(expected, ctx.getApplicationInfo().loadLabel(ctx.getPackageManager()));
    }
}
