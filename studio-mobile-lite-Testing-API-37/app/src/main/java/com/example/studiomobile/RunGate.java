package com.example.studiomobile;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Lets only one Run exist at a time. The first run keeps the gate; a second request is refused at once, so the newest run is the one
 * that is cancelled and two runs never share the compile and dex folders. Plain Java, so it can be unit tested.
 */
public final class RunGate {

    /** Proof that the holder owns the gate. */
    public final class Ticket {
        private Ticket() { }

        /** Frees the gate. Safe to call more than once, and a ticket that is no longer current changes nothing. */
        public void release() { current.compareAndSet(this, null); }
    }

    private final AtomicReference<Ticket> current = new AtomicReference<>();

    /** @return a ticket when no run is active, or null when another run still holds the gate. */
    public Ticket tryAcquire() {
        Ticket ticket = new Ticket();
        return current.compareAndSet(null, ticket) ? ticket : null;
    }

    public boolean isBusy() { return current.get() != null; }
}
