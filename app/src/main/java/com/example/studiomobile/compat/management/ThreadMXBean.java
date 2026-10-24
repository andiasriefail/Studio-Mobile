package com.example.studiomobile.compat.management;

/** Stand-in for java.lang.management.ThreadMXBean (only the CPU-time and counting parts; thread dumps are not available on Android). */
public interface ThreadMXBean {
    boolean isCurrentThreadCpuTimeSupported();
    boolean isThreadCpuTimeSupported();
    boolean isThreadCpuTimeEnabled();
    void setThreadCpuTimeEnabled(boolean enable);
    long getCurrentThreadCpuTime();
    long getCurrentThreadUserTime();
    long getThreadCpuTime(long id);
    long getThreadUserTime(long id);
    int getThreadCount();
    int getPeakThreadCount();
    int getDaemonThreadCount();
    long getTotalStartedThreadCount();
    long[] getAllThreadIds();
}
