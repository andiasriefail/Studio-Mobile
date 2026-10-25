package com.example.studiomobile;

import java.util.concurrent.atomic.AtomicInteger;

public final class ActiveWork {
    public interface Listener { void onBusyChanged(boolean busy); }

    private static final AtomicInteger COUNT = new AtomicInteger();
    private static volatile Listener listener;

    private ActiveWork() { }

    public static void setListener(Listener value) { listener = value; }

    public static boolean isBusy() { return COUNT.get() > 0; }

    public static void begin() {
        if (COUNT.incrementAndGet() == 1) notifyListener(true);
    }

    public static void end() {
        int left = COUNT.decrementAndGet();
        if (left < 0) { COUNT.set(0); return; }
        if (left == 0) notifyListener(false);
    }

    private static void notifyListener(boolean busy) {
        Listener current = listener;
        if (current == null) return;
        try { current.onBusyChanged(busy); } catch (Throwable ignored) { }
    }
}
