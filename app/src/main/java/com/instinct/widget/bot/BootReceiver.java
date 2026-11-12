package com.instinct.widget.bot;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Restarts the bot after reboot when it was running, mirroring the official
 * DeltaChat-Android BootReceiver + KeepAliveService.maybeStartSelf path
 * (official BootReceiver is itself a no-op; receiving the broadcast starts
 * the app process and Application.onCreate re-starts the service). A user
 * stop clears last_mode first, so only a previously live bot comes back.
 */
public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null || !Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        String last = context.getSharedPreferences("bot", Context.MODE_PRIVATE)
                .getString("last_mode", "");
        if (last.isEmpty() || "validate".equals(last)) return;
        Intent i = new Intent(context, BotService.class);
        i.putExtra("mode", last);
        try {
            // minSdk is 26, so Context.startForegroundService is always
            // available; no compat wrapper needed.
            context.startForegroundService(i);
        } catch (Exception ignored) {
            // Background-start restrictions: the user re-taps Start; state
            // (last_mode) survives, so the next manual start resumes fully.
        }
    }
}
