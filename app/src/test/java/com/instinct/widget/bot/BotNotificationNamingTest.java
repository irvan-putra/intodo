package com.instinct.widget.bot;

import static org.junit.Assert.assertEquals;

import android.content.Context;
import com.instinct.widget.R;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** The foreground notification title and the launcher label share one
 *  display-name resource: the default build must keep the old name and
 *  the renamed (-PemuAppId) build must present its own. This guards the
 *  default build's value; the renamed variant also needs its own compiled-resource check.
 *  This JVM test does not prove an emulator runtime result. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 32)
public class BotNotificationNamingTest {

    @Test
    public void appNameResourceMatchesBuildIdentity() {
        Context ctx = RuntimeEnvironment.getApplication();
        assertEquals("itdapk", ctx.getString(R.string.app_name));
        assertEquals("itdapk", ctx.getApplicationInfo().loadLabel(ctx.getPackageManager()));
    }
}
