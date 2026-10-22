package com.example.studiomobile.compat.management;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Stand-in for java.lang.management.ManagementFactory, which Android does not have.
 *
 * <p>The Kotlin compiler (kotlin-compiler-embeddable) asks ManagementFactory for CPU time and memory figures when it starts, and fails with
 * "NoClassDefFoundError: Failed resolution of: Ljava/lang/management/ManagementFactory;". The class transformer in app/build.gradle rewrites
 * every reference to java.lang.management.* inside org.jetbrains.kotlin.* to this package, so the compiler gets these cheap answers instead.
 * The figures only feed the compiler's own timing statistics. Nothing here ever ends up in the APKs that Studio Mobile builds for users.
 */
public final class ManagementFactory {
    private static final long START_MILLIS = System.currentTimeMillis();

    private ManagementFactory() { }

    public static ThreadMXBean getThreadMXBean() { return THREADS; }
    public static MemoryMXBean getMemoryMXBean() { return MEMORY; }
    public static RuntimeMXBean getRuntimeMXBean() { return RUNTIME; }
    public static OperatingSystemMXBean getOperatingSystemMXBean() { return OS; }
    public static ClassLoadingMXBean getClassLoadingMXBean() { return CLASSES; }

    /** Android exposes no collector beans; an empty list is what callers treat as "nothing to report". */
    public static List<GarbageCollectorMXBean> getGarbageCollectorMXBeans() { return Collections.emptyList(); }

    /** CPU time of the calling thread in nanoseconds; falls back to wall-clock time when the platform cannot tell (for example in JVM unit tests). */
    static long currentThreadCpuNanos() {
        try { return android.os.Debug.threadCpuTimeNanos(); }
        catch (Throwable unavailable) { return System.nanoTime(); }
    }

    private static final ThreadMXBean THREADS = new ThreadMXBean() {
        @Override public boolean isCurrentThreadCpuTimeSupported() { return true; }
        @Override public boolean isThreadCpuTimeSupported() { return true; }
        @Override public boolean isThreadCpuTimeEnabled() { return true; }
        @Override public void setThreadCpuTimeEnabled(boolean enable) { }
        @Override public long getCurrentThreadCpuTime() { return currentThreadCpuNanos(); }
        @Override public long getCurrentThreadUserTime() { return currentThreadCpuNanos(); }
        @Override public long getThreadCpuTime(long id) { return id == Thread.currentThread().getId() ? currentThreadCpuNanos() : -1L; }
        @Override public long getThreadUserTime(long id) { return getThreadCpuTime(id); }
        @Override public int getThreadCount() { return Thread.activeCount(); }
        @Override public int getPeakThreadCount() { return Thread.activeCount(); }
        @Override public int getDaemonThreadCount() { return 0; }
        @Override public long getTotalStartedThreadCount() { return Thread.activeCount(); }
        @Override public long[] getAllThreadIds() { return new long[] { Thread.currentThread().getId() }; }
    };

    private static final MemoryMXBean MEMORY = new MemoryMXBean() {
        @Override public MemoryUsage getHeapMemoryUsage() {
            Runtime runtime = Runtime.getRuntime();
            long total = runtime.totalMemory();
            return new MemoryUsage(total, total - runtime.freeMemory(), total, runtime.maxMemory());
        }
        @Override public MemoryUsage getNonHeapMemoryUsage() { return new MemoryUsage(0L, 0L, 0L, -1L); }
        @Override public int getObjectPendingFinalizationCount() { return 0; }
        @Override public boolean isVerbose() { return false; }
        @Override public void setVerbose(boolean value) { }
        @Override public void gc() { Runtime.getRuntime().gc(); }
    };

    private static final RuntimeMXBean RUNTIME = new RuntimeMXBean() {
        private long pid() { try { return android.os.Process.myPid(); } catch (Throwable unavailable) { return 0L; } }
        @Override public long getPid() { return pid(); }
        @Override public String getName() { return pid() + "@android"; }
        @Override public String getVmName() { return System.getProperty("java.vm.name", "Android Runtime"); }
        @Override public String getVmVendor() { return System.getProperty("java.vm.vendor", "The Android Project"); }
        @Override public String getVmVersion() { return System.getProperty("java.vm.version", ""); }
        @Override public String getSpecName() { return "Java Virtual Machine Specification"; }
        @Override public String getSpecVendor() { return System.getProperty("java.vm.specification.vendor", "The Android Project"); }
        @Override public String getSpecVersion() { return System.getProperty("java.vm.specification.version", "0.9"); }
        @Override public String getClassPath() { return System.getProperty("java.class.path", ""); }
        @Override public String getLibraryPath() { return System.getProperty("java.library.path", ""); }
        @Override public List<String> getInputArguments() { return Collections.emptyList(); }
        @Override public long getUptime() { return System.currentTimeMillis() - START_MILLIS; }
        @Override public long getStartTime() { return START_MILLIS; }
        @Override public Map<String, String> getSystemProperties() {
            Map<String, String> copy = new HashMap<>();
            for (String name : System.getProperties().stringPropertyNames()) copy.put(name, System.getProperty(name));
            return copy;
        }
    };

    private static final OperatingSystemMXBean OS = new OperatingSystemMXBean() {
        @Override public String getName() { return "Linux"; }
        @Override public String getArch() { return System.getProperty("os.arch", ""); }
        @Override public String getVersion() { return System.getProperty("os.version", ""); }
        @Override public int getAvailableProcessors() { return Runtime.getRuntime().availableProcessors(); }
        @Override public double getSystemLoadAverage() { return -1.0; }
    };

    private static final ClassLoadingMXBean CLASSES = new ClassLoadingMXBean() {
        @Override public long getTotalLoadedClassCount() { return 0L; }
        @Override public int getLoadedClassCount() { return 0; }
        @Override public long getUnloadedClassCount() { return 0L; }
        @Override public boolean isVerbose() { return false; }
        @Override public void setVerbose(boolean value) { }
    };
}
