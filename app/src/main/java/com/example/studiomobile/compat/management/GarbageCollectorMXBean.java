package com.example.studiomobile.compat.management;

/** Stand-in for java.lang.management.GarbageCollectorMXBean. */
public interface GarbageCollectorMXBean {
    String getName();
    boolean isValid();
    long getCollectionCount();
    long getCollectionTime();
    String[] getMemoryPoolNames();
}
