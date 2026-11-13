package com.instinct.widget;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Periodic background feed sync. SyncEngine.run() posts all WebView work to
 * the main Looper itself, so calling it from this background thread is safe;
 * we only wait here for its callback. A skipped sync (no feed URL configured
 * yet, or a transient network failure) is retried by WorkManager backoff -
 * never crash-loop the process for a fetch the next manual sync can also do.
 */
public class FeedSyncWorker extends Worker {
    public FeedSyncWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context c = getApplicationContext();
        if (!Prefs.isPaired(c) || SyncEngine.effectiveFeedUrl(c).isEmpty()) {
            return Result.success(); // nothing to sync on an unpaired/unconfigured phone
        }
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicBoolean ok = new AtomicBoolean(false);
        SyncEngine.run(c, (success, message) -> {
            ok.set(success);
            done.countDown();
        });
        try {
            // SyncEngine has its own 90s timeout; allow a little headroom.
            if (!done.await(120, TimeUnit.SECONDS)) return Result.retry();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.retry();
        }
        return ok.get() ? Result.success() : Result.retry();
    }
}
