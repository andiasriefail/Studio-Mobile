package com.example.studiomobile.compat.management;

import java.util.List;
import java.util.Map;

/** Stand-in for java.lang.management.RuntimeMXBean. */
public interface RuntimeMXBean {
    long getPid();
    String getName();
    String getVmName();
    String getVmVendor();
    String getVmVersion();
    String getSpecName();
    String getSpecVendor();
    String getSpecVersion();
    String getClassPath();
    String getLibraryPath();
    List<String> getInputArguments();
    long getUptime();
    long getStartTime();
    Map<String, String> getSystemProperties();
}
