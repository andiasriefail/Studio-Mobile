package com.example.studiomobile.build.process;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Stops build processes that {@code Process.destroy()} leaves behind. Gradle forks a separate JVM (the single-use daemon), and killing
 * only the launcher can leave that JVM running; after a few cancelled or timed-out builds they pile up and eat the phone's memory.
 * The tree is read from /proc, so no hidden API and no process id of the launcher is needed.
 */
public final class ChildProcessReaper {
    private ChildProcessReaper() { }

    /** The child processes that existed when it was taken; they are never killed by {@link #killNew}. */
    public static final class Snapshot {
        final Set<Integer> pids;
        Snapshot(Set<Integer> pids) { this.pids = pids; }
    }

    public static Snapshot snapshot() { return new Snapshot(descendants(android.os.Process.myPid())); }

    /** Kills every descendant of this app that was not in the snapshot. Returns how many were signalled. */
    public static int killNew(Snapshot before) {
        Set<Integer> now = descendants(android.os.Process.myPid());
        if (before != null) now.removeAll(before.pids);
        for (int pid : now) kill(pid);
        return now.size();
    }

    /**
     * Kills the Gradle processes (launcher, daemon, workers) that are new since the snapshot and leaves every other child alone. Used when a
     * build ends normally: the Kotlin compile daemon that Gradle started stays alive for the next build instead of being started again.
     */
    public static int killNewGradle(Snapshot before) {
        Set<Integer> now = descendants(android.os.Process.myPid());
        if (before != null) now.removeAll(before.pids);
        int killed = 0;
        for (int pid : now) {
            String command = read(new File("/proc/" + pid + "/cmdline"));
            if (command != null && command.contains("org.gradle.")) { kill(pid); killed++; }
        }
        return killed;
    }

    /**
     * Total CPU time (user + system, in clock ticks) used so far by the child processes of this app, or -1 when /proc cannot be read.
     * The Build log compares two readings to tell "working" from "waiting".
     */
    public static long descendantsCpuTicks() {
        Set<Integer> pids = descendants(android.os.Process.myPid());
        long total = 0;
        boolean readAny = false;
        for (int pid : pids) {
            long ticks = cpuTicksOf(read(new File("/proc/" + pid + "/stat")));
            if (ticks >= 0) { total += ticks; readAny = true; }
        }
        return readAny ? total : -1;
    }

    /** utime + stime (fields 14 and 15 of /proc/<pid>/stat), counted after the last ')' because the process name may contain spaces. */
    static long cpuTicksOf(String stat) {
        if (stat == null) return -1;
        int close = stat.lastIndexOf(')');
        if (close < 0) return -1;
        String[] fields = stat.substring(close + 1).trim().split("\\s+");
        if (fields.length < 13) return -1;
        try { return Long.parseLong(fields[11]) + Long.parseLong(fields[12]); } catch (NumberFormatException bad) { return -1; }
    }

    /** Kills Gradle daemons of this app that no build owns (left by an earlier cancelled build). Call only when no build is running. */
    public static int killStaleGradleDaemons() {
        int killed = 0, self = android.os.Process.myPid();
        File[] entries = new File("/proc").listFiles();
        if (entries == null) return 0;
        for (File entry : entries) {
            Integer pid = number(entry.getName());
            if (pid == null || pid == self) continue;
            String command = read(new File(entry, "cmdline"));
            if (command != null && command.contains("org.gradle.launcher.daemon.bootstrap.GradleDaemon")) { kill(pid); killed++; }
        }
        return killed;
    }

    private static Set<Integer> descendants(int root) {
        Map<Integer, Set<Integer>> children = new HashMap<>();
        File[] entries = new File("/proc").listFiles();
        if (entries != null) for (File entry : entries) {
            Integer pid = number(entry.getName());
            if (pid == null) continue;
            int parent = parentOf(read(new File(entry, "stat")));
            if (parent <= 0) continue;
            Set<Integer> list = children.get(parent);
            if (list == null) { list = new HashSet<>(); children.put(parent, list); }
            list.add(pid);
        }
        Set<Integer> found = new HashSet<>();
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            Set<Integer> next = children.get(queue.poll());
            if (next == null) continue;
            for (int pid : next) if (found.add(pid)) queue.add(pid);
        }
        return found;
    }

    /** "pid (name) S ppid ..." where the name may contain spaces and brackets, so the parent id is read after the last ')'. */
    static int parentOf(String stat) {
        if (stat == null) return -1;
        int close = stat.lastIndexOf(')');
        if (close < 0) return -1;
        String[] fields = stat.substring(close + 1).trim().split("\\s+");
        if (fields.length < 2) return -1;
        try { return Integer.parseInt(fields[1]); } catch (NumberFormatException bad) { return -1; }
    }

    private static Integer number(String name) {
        for (int i = 0; i < name.length(); i++) if (name.charAt(i) < '0' || name.charAt(i) > '9') return null;
        try { return name.isEmpty() ? null : Integer.valueOf(name); } catch (NumberFormatException bad) { return null; }
    }

    private static void kill(int pid) { try { android.os.Process.killProcess(pid); } catch (RuntimeException ignored) { } }

    private static String read(File file) {
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[4096];
            StringBuilder text = new StringBuilder();
            int count;
            while ((count = in.read(buffer)) > 0) for (int i = 0; i < count; i++) text.append(buffer[i] == 0 ? ' ' : (char) (buffer[i] & 0xFF));
            return text.toString();
        } catch (IOException | RuntimeException unreadable) { return null; }
    }
}
