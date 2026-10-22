package com.example.studiomobile.compat.management;

/** Stand-in for java.lang.management.MemoryMXBean. */
public interface MemoryMXBean {
    MemoryUsage getHeapMemoryUsage();
    MemoryUsage getNonHeapMemoryUsage();
    int getObjectPendingFinalizationCount();
    boolean isVerbose();
    void setVerbose(boolean value);
    void gc();
}
