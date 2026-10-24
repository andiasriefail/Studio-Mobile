package com.example.studiomobile.build.network;

import android.os.SystemClock;

/**
 * Runs beside an online build. When the system reports that the network is gone, it probes the internet every few seconds; if the
 * probe keeps failing for the whole grace period (a Wi-Fi to mobile switch recovers well inside it) the action runs once and the
 * watchdog ends. It only acts on a connection that was working before, so a device that never had internet is left to Gradle's own errors.
 */
public final class ConnectionWatchdog extends Thread {
    static final long POLL_MILLIS = 3000;
    static final long GRACE_MILLIS = 20_000;

    private final NetworkMonitor monitor;
    private final Runnable onConnectionLost;
    private volatile boolean stopped;
    private volatile boolean fired;

    public ConnectionWatchdog(NetworkMonitor monitor, Runnable onConnectionLost) {
        super("ConnectionWatchdog");
        setDaemon(true);
        this.monitor = monitor;
        this.onConnectionLost = onConnectionLost;
    }

    public void finish() { stopped = true; interrupt(); }

    /** True once the connection was declared lost. */
    public boolean hasFired() { return fired; }

    @Override public void run() {
        boolean everOnline = monitor.isValidated() || NetworkMonitor.canReachInternet();
        long failingSince = 0;
        while (!stopped) {
            try { Thread.sleep(POLL_MILLIS); } catch (InterruptedException interrupted) { return; }
            if (monitor.isValidated()) { everOnline = true; failingSince = 0; continue; }
            if (NetworkMonitor.canReachInternet()) { everOnline = true; failingSince = 0; continue; }   // the system flag was wrong: the internet works
            if (!everOnline) continue;
            long now = SystemClock.elapsedRealtime();
            if (failingSince == 0) failingSince = now;
            if (now - failingSince >= GRACE_MILLIS && !stopped) {
                fired = true;
                try { onConnectionLost.run(); } catch (RuntimeException ignored) { }
                return;
            }
        }
    }
}
