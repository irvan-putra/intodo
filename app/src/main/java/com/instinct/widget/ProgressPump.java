package com.instinct.widget;

import android.os.Handler;

/**
 * Coalesces a firehose of byte-count progress events onto the main thread:
 * at most one queued runnable, always carrying the newest values. Terminal
 * callbacks posted to the same Handler afterwards keep FIFO order, so the
 * last offered value is always delivered BEFORE the terminal status - a
 * late progress event can never overwrite a finished state.
 */
public final class ProgressPump {
    public interface Listener { void onProgress(long done, long total); }

    private final Handler main;
    private final Listener listener;
    private long done = -1, total = -1;
    private boolean queued;

    public ProgressPump(Handler main, Listener listener) {
        this.main = main;
        this.listener = listener;
    }

    public synchronized void offer(long d, long t) {
        done = d;
        total = t;
        if (queued) return;
        queued = true;
        main.post(() -> {
            long dd, tt;
            synchronized (ProgressPump.this) { dd = done; tt = total; queued = false; }
            listener.onProgress(dd, tt);
        });
    }
}
