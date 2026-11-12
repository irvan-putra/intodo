package com.instinct.widget;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;

/**
 * Receives PackageInstaller session results. The important one is
 * STATUS_PENDING_USER_ACTION: it carries EXTRA_INTENT, the system's own install
 * confirmation dialog, which must be started for the install to proceed at all.
 * Success and failure are recorded so the main screen can show the outcome.
 */
public class InstallStatusReceiver extends BroadcastReceiver {
    public static final String ACTION = "com.instinct.widget.INSTALL_STATUS";

    @Override public void onReceive(Context context, Intent intent) {
        // This receiver is not exported; only the system can deliver to it. Defense in
        // depth anyway: the status must carry the session id we actually committed, so a
        // forged intent (unknown session) is ignored before any EXTRA_INTENT is started.
        int sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1);
        if (sessionId < 0 || sessionId != Prefs.installSession(context)) return;
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS,
                PackageInstaller.STATUS_FAILURE);
        switch (status) {
            case PackageInstaller.STATUS_PENDING_USER_ACTION: {
                Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(confirm);
                }
                break;
            }
            case PackageInstaller.STATUS_SUCCESS:
                Prefs.saveStatus(context, "update installed - reopen the app");
                Prefs.clearInstallSession(context);
                break;
            default: {
                String msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                Prefs.saveStatus(context, "update install failed"
                        + (msg != null ? ": " + msg : ""));
                Prefs.clearInstallSession(context);
                break;
            }
        }
    }
}
