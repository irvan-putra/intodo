package com.vanipy.intodo;

import android.content.Context;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import java.util.concurrent.TimeUnit;

/**
 * Schedules the existing periodic feed sync. UPDATE keeps the persisted
 * schedule identity/timing but refreshes its worker class after a package
 * namespace move. KEEP would retain an unresolvable old worker class.
 * WorkManager persists this across reboots; Android may defer execution.
 */
public final class FeedSyncScheduler {
    private static final String WORK_NAME = "feed-autosync";
    private static final long INTERVAL_MINUTES = 30;  // existing cadence, unchanged
    private static final long FLEX_MINUTES = 10;

    private FeedSyncScheduler() {}

    public static void schedule(Context context) {
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build();
        PeriodicWorkRequest request =
                new PeriodicWorkRequest.Builder(FeedSyncWorker.class, INTERVAL_MINUTES, TimeUnit.MINUTES, FLEX_MINUTES, TimeUnit.MINUTES)
                        .setConstraints(constraints)
                        .build();
        WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request);
    }
}
