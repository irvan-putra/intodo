package com.vanipy.intodo;

import android.content.Context;
import androidx.work.*;
import androidx.work.impl.WorkManagerImpl;
import androidx.work.impl.model.WorkSpec;
import androidx.work.testing.SynchronousExecutor;
import androidx.work.testing.WorkManagerTestInitHelper;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 32)
public class FeedSyncSchedulerUpgradeTest {
    @Test public void existingWorkRefreshesRemovedWorkerWithoutResettingSchedule() throws Exception {
        Context context=RuntimeEnvironment.getApplication();
        Configuration config=new Configuration.Builder().setExecutor(new SynchronousExecutor()).build();
        WorkManagerTestInitHelper.initializeTestWorkManager(context,config);
        WorkManagerImpl manager=WorkManagerImpl.getInstance(context);
        PeriodicWorkRequest old=new PeriodicWorkRequest.Builder(FeedSyncWorker.class,30,TimeUnit.MINUTES,10,TimeUnit.MINUTES)
                .setConstraints(new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build();
        manager.enqueueUniquePeriodicWork("feed-autosync",ExistingPeriodicWorkPolicy.KEEP,old).getResult().get();
        WorkSpec before=manager.getWorkDatabase().workSpecDao().getWorkSpec(old.getStringId());
        // Simulate persisted class metadata from an installed prior APK, without
        // adding a legacy class to the current source tree.
        before.workerClassName="com."+"instinct.widget.FeedSyncWorker";
        manager.getWorkDatabase().workSpecDao().updateWorkSpec(before);
        FeedSyncScheduler.schedule(context);
        // Operation is queued behind the schedule update on WorkManager's serial executor.
        manager.getWorkInfosForUniqueWork("feed-autosync").get();
        WorkSpec after=manager.getWorkDatabase().workSpecDao().getWorkSpec(old.getStringId());
        assertNotNull(after);
        assertEquals("com.vanipy.intodo.FeedSyncWorker",after.workerClassName);
        assertEquals(before.id,after.id);
        assertEquals(before.lastEnqueueTime,after.lastEnqueueTime);
        assertEquals(before.intervalDuration,after.intervalDuration);
        assertEquals(before.flexDuration,after.flexDuration);
        assertEquals(NetworkType.CONNECTED,after.constraints.getRequiredNetworkType());
        assertTrue(after.constraints.requiresBatteryNotLow());
        FeedSyncScheduler.schedule(context);
        assertEquals(1,manager.getWorkInfosForUniqueWork("feed-autosync").get().size());
    }
}
