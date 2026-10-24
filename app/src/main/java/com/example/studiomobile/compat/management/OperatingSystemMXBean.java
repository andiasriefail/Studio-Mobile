package com.example.studiomobile.compat.management;

/** Stand-in for java.lang.management.OperatingSystemMXBean. */
public interface OperatingSystemMXBean {
    String getName();
    String getArch();
    String getVersion();
    int getAvailableProcessors();
    double getSystemLoadAverage();
}
