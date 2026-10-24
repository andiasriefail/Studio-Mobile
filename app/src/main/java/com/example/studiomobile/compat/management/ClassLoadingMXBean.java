package com.example.studiomobile.compat.management;

/** Stand-in for java.lang.management.ClassLoadingMXBean. */
public interface ClassLoadingMXBean {
    long getTotalLoadedClassCount();
    int getLoadedClassCount();
    long getUnloadedClassCount();
    boolean isVerbose();
    void setVerbose(boolean value);
}
