package com.example.studiomobile;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;

import com.example.studiomobile.workspace.StudioStorage;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Debug Mode for Studio Mobile itself (the other Debug Mode is for the apps users build).
 *
 * <p>{@link #install(Application)} is called from {@code StudioApplication.attachBaseContext}, which runs before
 * {@code Application.onCreate()} and before any Activity or Service, so a crash anywhere in the app (editor, compiler, dependency
 * download, the :javarun process) is caught. The stack trace is saved and shown on a full-screen page in its own process.
 *
 * <p>Crashes that no Java handler can see (the system killing the app for memory, a native crash, an ANR) are recovered on the next
 * start from {@link ApplicationExitInfo} (Android 11+).
 */
public final class SelfCrashCatcher {
    public static final String LOG_FILE_NAME = "studio_self_crash.log";
    public static final String EXTRA_LOG = "studio_self_crash_log";
    public static final String CRASH_PROCESS_SUFFIX = ":studio_crash";
    private static final String PREFS = "studio_self_crash";
    private static final String KEY_LAST_EXIT = "last_exit_timestamp";
    private static volatile boolean installed;

    private SelfCrashCatcher() { }

    public static void install(final Application app) {
        if (installed) return;
        String process = processName(app);
        if (process != null && process.endsWith(CRASH_PROCESS_SUFFIX)) return;   // the crash page must never catch itself
        installed = true;
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        final String processLabel = process == null ? "?" : process;
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override public void uncaughtException(Thread thread, Throwable error) {
                boolean shown = false;
                try { shown = handleCrash(app, processLabel, thread, error); } catch (Throwable ignored) { }
                if (!shown && previous != null) { previous.uncaughtException(thread, error); return; }
                Process.killProcess(Process.myPid());
                System.exit(10);
            }
        });
        startWatchdog(app, processLabel);
        if (process == null || process.equals(app.getPackageName())) {
            new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override public void run() { try { reportPreviousSilentDeath(app); } catch (Throwable ignored) { } }
            }, 1500);
        }
    }

    /**
     * Freeze detector. The UI thread is asked every second to confirm it is alive; when it has not answered for {@link #FREEZE_MS} the
     * stack of every thread is written to the log (and refreshed every few seconds while the freeze lasts, so the last picture before
     * Android shows "not responding" / kills the app is kept). The crash page opens once per freeze, in its own process.
     */
    private static void startWatchdog(final Application app, final String process) {
        final Handler main = new Handler(Looper.getMainLooper());
        Thread watchdog = new Thread(new Runnable() {
            @Override public void run() {
                final long[] lastBeat = { android.os.SystemClock.uptimeMillis() };
                boolean shown = false;
                long lastDump = 0;
                String episode = null;   // one file per freeze
                while (true) {
                    main.post(new Runnable() { @Override public void run() { lastBeat[0] = android.os.SystemClock.uptimeMillis(); } });
                    long sleepStart = android.os.SystemClock.uptimeMillis();
                    try { Thread.sleep(1000); } catch (InterruptedException stop) { return; }
                    long now = android.os.SystemClock.uptimeMillis();
                    // A one second sleep that took several seconds means the whole process was suspended (for example frozen by the
                    // system while in the background), not that the UI thread hung. Start counting again instead of reporting a freeze.
                    if (now - sleepStart > PROCESS_PAUSE_MS) { lastBeat[0] = now; shown = false; lastDump = 0; episode = null; continue; }
                    long frozenFor = now - lastBeat[0];
                    if (frozenFor < FREEZE_MS) { shown = false; lastDump = 0; episode = null; continue; }
                    if (now - lastDump < 4000) continue;
                    lastDump = now;
                    try {
                        String report = buildFreezeReport(app, process, frozenFor);
                        if (episode == null) episode = newStamp();
                        save(app, report, "freeze", episode);
                        if (!shown) { shown = true; show(app, report); }
                    } catch (Throwable ignored) { }
                }
            }
        }, "studio-freeze-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
    }

    /** 10 s: short stalls (a slow phone, a busy system) are not worth a full-screen report; Android itself shows "not responding" at about 5 s of input lag. */
    private static final long FREEZE_MS = 10000;

    /** A watchdog sleep longer than this (it asks for 1 s) is treated as the process having been paused, not as a UI freeze. */
    private static final long PROCESS_PAUSE_MS = 4000;

    private static String buildFreezeReport(Context app, String process, long frozenFor) {
        StringBuilder text = new StringBuilder();
        text.append("=== STUDIO MOBILE FROZE (UI thread not responding) ===\n");
        text.append("Time: ").append(format(new Date())).append('\n');
        text.append("Package: ").append(app.getPackageName()).append('\n');
        text.append("Process: ").append(process).append('\n');
        text.append("UI thread frozen for: ").append(frozenFor / 1000).append(" s (log refreshed while it lasts)\n");
        text.append("Device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
            .append(" (Android ").append(Build.VERSION.RELEASE).append(", API ").append(Build.VERSION.SDK_INT).append(")\n");
        text.append(memoryInfo(app)).append('\n');
        StackTraceElement[] mainTrace = Looper.getMainLooper().getThread().getStackTrace();
        text.append("--- UI (main) thread stack: where it is stuck ---\n");
        for (StackTraceElement frame : mainTrace) text.append("    at ").append(frame).append('\n');
        text.append("\n--- All other threads ---\n");
        for (java.util.Map.Entry<Thread, StackTraceElement[]> entry : Thread.getAllStackTraces().entrySet()) {
            Thread thread = entry.getKey();
            if (thread == Looper.getMainLooper().getThread()) continue;
            StackTraceElement[] trace = entry.getValue();
            if (trace.length == 0) continue;
            text.append("Thread \"").append(thread.getName()).append("\" (").append(thread.getState()).append(")\n");
            int limit = Math.min(trace.length, 25);
            for (int i = 0; i < limit; i++) text.append("    at ").append(trace[i]).append('\n');
            text.append('\n');
            if (text.length() > 55000) { text.append("... (truncated)\n"); break; }
        }
        return text.toString();
    }

    /** Reads the saved log; used by the crash page when the Intent extra was too large. */
    public static String readSavedLog(Context context) {
        try (InputStream in = new FileInputStream(new File(context.getFilesDir(), LOG_FILE_NAME))) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192]; int count;
            while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
            return out.toString("UTF-8");
        } catch (Exception error) {
            return "No crash log found: " + error;
        }
    }

    private static boolean handleCrash(Context app, String process, Thread thread, Throwable error) throws Exception {
        String report = buildReport(app, process, thread, error);
        save(app, report, "crash", newStamp());
        show(app, report);
        try { Thread.sleep(400); } catch (InterruptedException ignored) { }   // let the system hand the Intent over before this process dies
        return true;
    }

    private static void reportPreviousSilentDeath(Context app) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return;
        ActivityManager manager = (ActivityManager) app.getSystemService(Context.ACTIVITY_SERVICE);
        if (manager == null) return;
        SharedPreferences prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long seen = prefs.getLong(KEY_LAST_EXIT, 0);
        List<ApplicationExitInfo> exits = manager.getHistoricalProcessExitReasons(app.getPackageName(), 0, 5);
        if (exits == null || exits.isEmpty()) return;
        long newest = seen;
        StringBuilder text = new StringBuilder();
        for (ApplicationExitInfo info : exits) {
            if (info.getTimestamp() <= seen) continue;
            newest = Math.max(newest, info.getTimestamp());
            int reason = info.getReason();
            boolean abnormal = reason == ApplicationExitInfo.REASON_LOW_MEMORY || reason == ApplicationExitInfo.REASON_CRASH_NATIVE
                || reason == ApplicationExitInfo.REASON_ANR || reason == ApplicationExitInfo.REASON_SIGNALED
                || reason == ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE || reason == ApplicationExitInfo.REASON_INITIALIZATION_FAILURE
                || reason == ApplicationExitInfo.REASON_UNKNOWN || reason == ApplicationExitInfo.REASON_DEPENDENCY_DIED;
            if (!abnormal) continue;
            text.append("Process: ").append(info.getProcessName()).append('\n');
            text.append("Time: ").append(format(new Date(info.getTimestamp()))).append('\n');
            text.append("Reason: ").append(reasonName(reason)).append(" (code ").append(reason).append(")\n");
            text.append("Status: ").append(info.getStatus()).append('\n');
            text.append("Description: ").append(info.getDescription()).append('\n');
            text.append("Memory (PSS/RSS KB): ").append(info.getPss()).append(" / ").append(info.getRss()).append("\n");
            try {
                InputStream trace = info.getTraceInputStream();
                if (trace != null) {
                    try {
                        ByteArrayOutputStream out = new ByteArrayOutputStream();
                        byte[] buffer = new byte[8192]; int count, total = 0;
                        while ((count = trace.read(buffer)) != -1 && total < 40000) { out.write(buffer, 0, count); total += count; }
                        text.append("\n--- System trace ---\n").append(out.toString("UTF-8")).append('\n');
                    } finally { trace.close(); }
                }
            } catch (Throwable ignored) { }
            text.append('\n');
        }
        prefs.edit().putLong(KEY_LAST_EXIT, newest).apply();
        if (text.length() == 0) return;
        String report = "=== STUDIO MOBILE WAS CLOSED BY THE SYSTEM LAST TIME ===\n"
            + "No Java crash was recorded (typical for running out of memory, a native crash or a freeze).\n\n" + text
            + memoryInfo(app);
        save(app, report, "closed-by-system", newStamp());
        show(app, report);
    }

    private static final int KEEP_REPORTS = 10;
    private static final String REPORT_PREFIX = "studio-mobile-";

    private static String newStamp() { return new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()); }

    /**
     * Saves the report twice: as "latest" inside the app (read by the crash page) and as a time-stamped file in "Studio Mobile/App Crash
     * Logs", where the newest {@value #KEEP_REPORTS} are kept. One freeze that lasts a while keeps rewriting the same file (same stamp), so
     * it never fills the folder.
     */
    private static void save(Context app, String report, String kind, String stamp) {
        try (FileOutputStream out = new FileOutputStream(new File(app.getFilesDir(), LOG_FILE_NAME), false)) {
            out.write(report.getBytes("UTF-8"));
        } catch (Throwable ignored) { }
        try {
            File folder = StudioStorage.appCrashLogsDir(app);
            try (FileOutputStream out = new FileOutputStream(new File(folder, REPORT_PREFIX + kind + "-" + stamp + ".log"), false)) {
                out.write(report.getBytes("UTF-8"));
            }
            File[] all = folder.listFiles();
            if (all != null && all.length > KEEP_REPORTS) {
                java.util.Arrays.sort(all, new java.util.Comparator<File>() {
                    @Override public int compare(File a, File b) { return Long.compare(b.lastModified(), a.lastModified()); }
                });
                for (int i = KEEP_REPORTS; i < all.length; i++) if (all[i].getName().startsWith(REPORT_PREFIX)) all[i].delete();
            }
        } catch (Throwable ignored) { }
    }

    private static void show(Context app, String report) {
        Intent intent = new Intent(app, SelfCrashActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        intent.putExtra(EXTRA_LOG, report.length() > 60000 ? report.substring(0, 60000) : report);
        app.startActivity(intent);
    }

    private static String buildReport(Context app, String process, Thread thread, Throwable error) {
        StringWriter trace = new StringWriter();
        PrintWriter writer = new PrintWriter(trace);
        error.printStackTrace(writer);
        writer.flush();
        StringBuilder text = new StringBuilder();
        text.append("=== STUDIO MOBILE CRASH ===\n");
        text.append("Time: ").append(format(new Date())).append('\n');
        text.append("Package: ").append(app.getPackageName()).append('\n');
        text.append("Process: ").append(process).append('\n');
        text.append("Thread: ").append(thread == null ? "?" : thread.getName()).append('\n');
        text.append("Device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
            .append(" (Android ").append(Build.VERSION.RELEASE).append(", API ").append(Build.VERSION.SDK_INT).append(")\n");
        text.append("Exception: ").append(error.getClass().getName()).append('\n');
        text.append("Message: ").append(error.getMessage()).append("\n");
        text.append(memoryInfo(app)).append('\n');
        text.append("--- Stack trace ---\n").append(trace).append('\n');
        return text.toString();
    }

    private static String memoryInfo(Context app) {
        Runtime rt = Runtime.getRuntime();
        StringBuilder text = new StringBuilder();
        text.append("Heap: used ").append((rt.totalMemory() - rt.freeMemory()) >> 20).append(" MB, committed ")
            .append(rt.totalMemory() >> 20).append(" MB, max ").append(rt.maxMemory() >> 20).append(" MB\n");
        try {
            ActivityManager manager = (ActivityManager) app.getSystemService(Context.ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
            manager.getMemoryInfo(info);
            text.append("Device RAM: free ").append(info.availMem >> 20).append(" MB of ").append(info.totalMem >> 20)
                .append(" MB, low-memory=").append(info.lowMemory).append('\n');
        } catch (Throwable ignored) { }
        return text.toString();
    }

    private static String format(Date date) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(date);
    }

    private static String processName(Application app) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) return Application.getProcessName();
        return null;
    }

    private static String reasonName(int reason) {
        switch (reason) {
            case ApplicationExitInfo.REASON_LOW_MEMORY: return "LOW_MEMORY (killed by the system, out of memory)";
            case ApplicationExitInfo.REASON_CRASH_NATIVE: return "CRASH_NATIVE (native crash)";
            case ApplicationExitInfo.REASON_ANR: return "ANR (app not responding)";
            case ApplicationExitInfo.REASON_SIGNALED: return "SIGNALED (killed by a signal)";
            case ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE: return "EXCESSIVE_RESOURCE_USAGE";
            case ApplicationExitInfo.REASON_INITIALIZATION_FAILURE: return "INITIALIZATION_FAILURE";
            case ApplicationExitInfo.REASON_DEPENDENCY_DIED: return "DEPENDENCY_DIED";
            default: return "UNKNOWN";
        }
    }
}
