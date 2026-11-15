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
    // Identity of THIS service instance for the lifecycle reservation:
    // only the worker that claimed the SERVICE/EXPORT phase may release it.
    private final Object serviceOwner = new Object();

    // Matches the official DeltaChat-Android FOSS flavor's KeepAliveService:
    // permanent-notification foreground service, START_STICKY, no wake lock
    // (official source: connect/KeepAliveService.java, foss manifest
    // foregroundServiceType="specialUse"; it holds no PowerManager.WakeLock).
    // Process-wide worker reference: service INSTANCES are recreated on
    // restart, but the old worker THREAD outlives its instance. Only a
    // static reference lets a new instance order itself behind the old
    // worker's teardown (an instance field would read null and skip it).
    private static final Object WORKER_LOCK = new Object();
    private static Thread activeWorker;
    // True while activeWorker is a JOINER parked on a dying worker's
    // teardown; false once it is promoted to the RUNNER (or the slot is
    // handed back to the dying runner). The kind decides dedupe.
    private static boolean activeWorkerJoining;

    // Test hooks (same package): the restart-ordering test installs a
    // stand-in "old worker" as if a previous service instance had left
    // it tearing down.
    static void setActiveWorkerForTesting(Thread worker) {
        // Installs a stand-in RUNNER (a previous instance's worker).
        synchronized (WORKER_LOCK) {
            activeWorker = worker;
            activeWorkerJoining = false;
        }
    }

    static Thread getActiveWorkerForTesting() {
        synchronized (WORKER_LOCK) { return activeWorker; }
    }

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
                : mode.equals("export") ? "exporting bot + settings"
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
        // A previous worker may still be tearing down (onDestroy wrote its
        // STOP flag but did not join it). The NEW worker joins the old one
        // off the main thread (no UI hang): only after the old runtime is
        // dead may this start clear its STOP flag and run - so a restart
        // can never delete STOP before the old worker observes it.
        final String modeF = mode;  // lambdas need an effectively final capture
        final Thread previous;
        final Thread t;
        synchronized (WORKER_LOCK) {
            previous = activeWorker;
            if (previous != null && previous.isAlive()) {
                // Pending-start dedupe, by worker KIND, atomically:
                // - no STOP + live RUNNER: a duplicate start is a no-op.
                // - STOP + live RUNNER: a teardown is pending - allow
                //   EXACTLY ONE joiner to park on it (below); without it
                //   nobody starts after the old worker exits, because
                //   Android does not re-invoke onStartCommand when a
                //   worker thread merely ends.
                // - live JOINER: a waiter already owns the chain -
                //   dedupe. Exactly one waiter means no loser whose
                //   stopSelf() could tear down the winner's service,
                //   and the registry entry is never overwritten.
                if (!new File(getFilesDir(), "STOP").exists()
                        || activeWorkerJoining) {
                    return START_STICKY;
                }
            }
            t = new Thread(() -> {
                if (previous != null && previous.isAlive()) {
                    try { previous.join(15000); } catch (InterruptedException ignored) {}
                    if (previous.isAlive()) {
                        // Still dying: hand the process-wide slot back to
                        // the ACTUAL old thread and refuse this start;
                        // the sticky restart retries after the old worker
                        // is gone and joins it again.
                        synchronized (WORKER_LOCK) {
                            if (activeWorker == Thread.currentThread()) {
                                activeWorker = previous;
                                activeWorkerJoining = false;
                            }
                        }
                        stopSelf();
                        return;
                    }
                }
                // Export is a mutation that outlives one lock scope: the
                // phase marks the boundary while it runs (finally clears).
                final boolean exportMode = modeF.equals("export");
                try {
                    // Recovery barrier + atomic service reservation BEFORE
                    // any bot mode: an interrupted import/export must be
                    // resolved first, whether this start came from the UI,
                    // a START_STICKY process restart (null intent), or the
                    // boot receiver. A live lifecycle owner REJECTS this
                    // start (never overwritten); failed recovery fails
                    // closed (journal kept; the next start retries
                    // recovery). The STOP flag is PRESERVED on every
                    // reject path - an old runtime's pending stop request
                    // is never erased by a start that fails to own the
                    // lifecycle.
                    BotImport.ReserveResult res =
                            BotImport.Companion.recoverAndReserveService(this, serviceOwner);
                    if (res != BotImport.ReserveResult.RESERVED) {
                        stopSelf();
                        return;
                    }
                    // The SERVICE reservation is now held by THIS instance:
                    // no import can be admitted between the barrier
                    // passing and the runtime opening, and only this
                    // owner's token releases it (finally below). The old
                    // worker is dead (joined above) and this start owns
                    // the lifecycle, so the STOP flag it left is stale:
                    // only NOW is clearing it safe - immediately before
                    // the runtime opens.
                    try { new File(getFilesDir(), "STOP").delete(); } catch (Exception ignored) {}
                    // Reservation held AND the stale STOP cleared: only
                    // NOW promote JOINER -> RUNNER, atomically. Keeping
                    // JOINING through the reservation window closes the
                    // gap where a concurrent start (STOP still present,
                    // worker alive, flag already cleared) parked a second
                    // joiner whose join timeout could later stopSelf()
                    // the winner's service.
                    synchronized (WORKER_LOCK) {
                        if (activeWorker == Thread.currentThread()) {
                            activeWorkerJoining = false;
                        }
                    }
                    if (!Python.isStarted()) Python.start(new AndroidPlatform(this));
                    if (exportMode) BotImport.Companion.markExportPhase(serviceOwner);
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
                } finally {
                    // Owner-checked release: a superseded worker can never
                    // clear a NEWER owner's reservation. The process-wide
                    // slot is likewise released only by its holder.
                    BotImport.Companion.releaseServiceReservation(serviceOwner);
                    synchronized (WORKER_LOCK) {
                        if (activeWorker == Thread.currentThread()) {
                            activeWorker = null;
                            activeWorkerJoining = false;
                        }
                    }
                }
            }, "bot-" + modeF);
            activeWorker = t;
            activeWorkerJoining = true;
            t.start();
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
