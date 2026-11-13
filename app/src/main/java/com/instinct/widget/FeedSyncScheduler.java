package com.instinct.widget;

import android.content.Context;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import java.util.concurrent.TimeUnit;

/**
 * Schedules the periodic feed sync. Idempotent: KEEP keeps an existing
 * schedule, so calling this from app start and widget updates is safe and
 * survives reboots (WorkManager persists work across them). Cadence is the
 * user-approved 30 minutes; Android may defer execution to save battery.
 */
public final class FeedSyncScheduler {
    private static final String WORK_NAME = "feed-autosync";
    private static final long INTERVAL_MINUTES = 30;  // user-approved cadence; single knob
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
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request);
    }
}
