package com.example.studiomobile.build.network;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.SystemClock;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * Watches the device's default network while an online build runs. The system callback gives an instant signal when the connection
 * drops; an active probe (a plain TCP connect to public resolvers, no DNS needed) then confirms whether the internet is really gone,
 * because "connected" does not always mean "working" and "not validated" does not always mean "broken".
 */
public final class NetworkMonitor {
    /** Label at the start of the failure message when a connection that was working is lost during a build. */
    public static final String CONNECTION_TIMEOUT_LABEL = "[Connection Timeout]";

    private static final String[] PROBE_HOSTS = {"1.1.1.1", "8.8.8.8", "9.9.9.9"};
    private static final int PROBE_PORT = 443;
    private static final int PROBE_TIMEOUT_MILLIS = 3000;

    private final ConnectivityManager manager;
    private volatile boolean validated = true;   // stays true when the state cannot be read, so the watchdog never fires on a guess
    private volatile boolean registered;
    private volatile long offlineSinceElapsed;   // 0 while the system reports a working network
    private final ConnectivityManager.NetworkCallback callback = new ConnectivityManager.NetworkCallback() {
        @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) { setValidated(isValidated(capabilities)); }
        @Override public void onLost(Network network) { setValidated(false); }
    };

    public NetworkMonitor(Context context) {
        ConnectivityManager found = null;
        try { found = (ConnectivityManager) context.getApplicationContext().getSystemService(Context.CONNECTIVITY_SERVICE); } catch (RuntimeException ignored) { }
        manager = found;
    }

    public void start() {
        if (manager == null || registered) return;
        try {
            Network active = manager.getActiveNetwork();
            setValidated(active == null ? false : isValidated(manager.getNetworkCapabilities(active)));
            manager.registerDefaultNetworkCallback(callback);
            registered = true;
        } catch (RuntimeException unavailable) {   // SecurityException without ACCESS_NETWORK_STATE, or a system that refuses the callback
            validated = true; offlineSinceElapsed = 0;
        }
    }

    public void stop() {
        if (manager == null || !registered) return;
        registered = false;
        try { manager.unregisterNetworkCallback(callback); } catch (RuntimeException ignored) { }
    }

    /** What the system says right now: a default network with internet that it has checked. */
    public boolean isValidated() { return validated; }

    /** Milliseconds since the system last reported a working network, 0 while it reports one. */
    public long offlineMillis() { long since = offlineSinceElapsed; return since == 0 ? 0 : SystemClock.elapsedRealtime() - since; }

    private void setValidated(boolean now) {
        validated = now;
        if (now) offlineSinceElapsed = 0; else if (offlineSinceElapsed == 0) offlineSinceElapsed = SystemClock.elapsedRealtime();
    }

    private static boolean isValidated(NetworkCapabilities capabilities) {
        return capabilities != null && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
    }

    /** One-shot check used before a retry. True when the state cannot be read, so a retry is never blocked by a guess. */
    public static boolean isOnlineNow(Context context) {
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getApplicationContext().getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return true;
            Network active = cm.getActiveNetwork();
            return active != null && isValidated(cm.getNetworkCapabilities(active));
        } catch (RuntimeException unavailable) { return true; }
    }

    /** True when a TCP connection to any public resolver succeeds. Blocking; call it from a worker thread. */
    public static boolean canReachInternet() {
        for (String host : PROBE_HOSTS) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(host, PROBE_PORT), PROBE_TIMEOUT_MILLIS);
                return true;
            } catch (Exception unreachable) { /* try the next host */ }
        }
        return false;
    }
}
