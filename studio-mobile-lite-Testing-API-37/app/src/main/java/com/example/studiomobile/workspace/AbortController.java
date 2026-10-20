package com.example.studiomobile.workspace;

import java.util.concurrent.Future;

/**
 * Same idea as the web's AbortController: every new round of work calls {@link #renew()}, which aborts the round before it.
 * A stale refresh then never reaches the screen, and one that has not started yet never runs.
 */
public final class AbortController {

    public static final class Signal {
        private volatile boolean aborted;
        private volatile Future<?> task;

        public boolean isAborted() { return aborted; }

        /** Ties the running task to this signal so abort() also interrupts it (or cancels it before it starts). */
        public void attach(Future<?> future) {
            task = future;
            if (aborted) future.cancel(true);
        }

        void abort() {
            aborted = true;
            Future<?> running = task;
            if (running != null) running.cancel(true);
        }
    }

    private Signal current;

    /** Aborts the previous signal and returns a fresh one. */
    public synchronized Signal renew() {
        abort();
        current = new Signal();
        return current;
    }

    public synchronized void abort() {
        if (current != null) current.abort();
    }
}
