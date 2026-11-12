package com.instinct.widget.bot;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;
import java.io.File;
import java.io.FileWriter;

public class BotService extends Service {
    // Matches the official DeltaChat-Android FOSS flavor's KeepAliveService:
    // permanent-notification foreground service, START_STICKY, no wake lock
    // (official source: connect/KeepAliveService.java, foss manifest
    // foregroundServiceType="specialUse"; it holds no PowerManager.WakeLock).
    private Thread worker;

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel("bot", "Bot service", NotificationManager.IMPORTANCE_LOW));
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        // START_STICKY restarts arrive with a null intent: resume the last
        // mode the user chose (persisted), like the official app's boot path.
        String mode = intent == null ? null : intent.getStringExtra("mode");
        if (mode == null || mode.isEmpty()) {
            mode = getSharedPreferences("bot", MODE_PRIVATE).getString("last_mode", "validate");
        } else {
            getSharedPreferences("bot", MODE_PRIVATE).edit().putString("last_mode", mode).apply();
        }
        String label = mode.equals("live") ? "itdxdc delivery bot running"
                : mode.equals("provision") ? "creating bot account"
                : mode.equals("export") ? "exporting bot for itdapk"
                : "offline validation running";
        Notification.Builder nb = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, "bot") : new Notification.Builder(this);
        Notification n = nb.setContentTitle("itdapk bot")
                .setContentText(label)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .build();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST);
        } else {
            startForeground(1, n);
        }
        // No wake lock, matching official DeltaChat-Android: it keeps its
        // IMAP connection with a foreground service + battery-exemption
        // prompt only. A partial wake lock cannot beat doze network
        // suspension anyway, it only costs battery.
        // Clear a stale STOP flag from a previous shutdown so the new python
        // worker does not die instantly (python clears it too, belt+braces).
        try { new File(getFilesDir(), "STOP").delete(); } catch (Exception ignored) {}
        if (worker == null || !worker.isAlive()) {
            final String modeF = mode;  // lambdas need an effectively final capture
            worker = new Thread(() -> {
                try {
                    if (!Python.isStarted()) Python.start(new AndroidPlatform(this));
                    if (modeF.equals("validate")) {
                        Python.getInstance().getModule("bot_validation")
                                .callAttr("run", getApplicationInfo().nativeLibraryDir, getFilesDir().getAbsolutePath());
                    } else {
                        Python.getInstance().getModule("bot_runtime")
                                .callAttr("service_entry", getApplicationInfo().nativeLibraryDir,
                                        getFilesDir().getAbsolutePath(), modeF);
                    }
                } catch (Exception e) {
                    try {
                        FileWriter w = new FileWriter(new File(getFilesDir(), "bot.log"), true);
                        w.write("java-side error (" + modeF + "): " + e + "\n"); w.close();
                    } catch (Exception ignored) {}
                }
            }, "bot-" + modeF);
            worker.start();
        }
        return START_STICKY;
    }

    @Override public void onDestroy() {
        // Real stop: the python watchdog sees this flag, closes the RPC
        // (breaking run_forever) and exits; the lock file is removed there.
        try {
            FileWriter w = new FileWriter(new File(getFilesDir(), "STOP"));
            w.write("stop"); w.close();
        } catch (Exception ignored) {}
        stopForeground(true);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent i) { return null; }
}
