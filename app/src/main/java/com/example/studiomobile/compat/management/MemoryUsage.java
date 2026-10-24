package com.example.studiomobile.compat.management;

/** Stand-in for java.lang.management.MemoryUsage (the real one does not exist on Android). */
public final class MemoryUsage {
    private final long init, used, committed, max;

    public MemoryUsage(long init, long used, long committed, long max) {
        this.init = init; this.used = used; this.committed = committed; this.max = max;
    }

    public long getInit() { return init; }
    public long getUsed() { return used; }
    public long getCommitted() { return committed; }
    public long getMax() { return max; }

    @Override public String toString() {
        return "init = " + init + "(" + (init >> 10) + "K) used = " + used + "(" + (used >> 10) + "K) committed = " + committed
            + "(" + (committed >> 10) + "K) max = " + max + "(" + (max >> 10) + "K)";
    }
}
