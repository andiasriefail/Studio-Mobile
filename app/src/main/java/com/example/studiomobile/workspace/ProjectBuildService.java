package com.example.studiomobile.workspace;

import android.content.Context;

import com.example.studiomobile.LanguageUi;
import com.example.studiomobile.build.BuildConfiguration;
import com.example.studiomobile.build.BuildLogger;
import com.example.studiomobile.build.BuildPipeline;
import com.example.studiomobile.build.BuildResult;
import com.example.studiomobile.build.GradleBuildPipeline;
import com.example.studiomobile.build.LocalBuildPipeline;
import com.example.studiomobile.build.LocalLibsInstaller;
import com.example.studiomobile.build.dependency.ChecksumFailure;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Runs project builds without any dependency on Android UI classes.
 *
 * <p>The screen and, later, the MCP server both call this one class, so a build started by either
 * caller shows the same state and log. It owns its own worker thread, so a running build survives
 * the Activity being recreated or destroyed. Listener callbacks arrive on the worker thread.
 *
 * <p>While a build runs, {@link BuildForegroundService} holds a foreground service with a progress notification (and a wake lock), so
 * Android keeps the process and the worker thread alive when the app is left or the screen turns off.
 */
public final class ProjectBuildService {

    public enum State { IDLE, RUNNING, SUCCESS, FAILED, CANCELLED }

    /** Receives live updates. Implementations must switch to the UI thread themselves. */
    public interface Listener {
        void onLogAppended(String line);
        void onStateChanged(State state);
    }

    private static volatile ProjectBuildService instance;

    public static ProjectBuildService get(Context context) {
        ProjectBuildService local = instance;
        if (local == null) {
            synchronized (ProjectBuildService.class) {
                local = instance;
                if (local == null) {
                    local = new ProjectBuildService(context.getApplicationContext());
                    instance = local;
                }
            }
        }
        return local;
    }

    private final Context appContext;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final Object lock = new Object();
    /** Everything the engine reported (for MCP and for copying). */
    private final StringBuilder log = new StringBuilder();
    /**
     * What the Build screen shows: every step, library download and DEX part with the time it started and how long it took, plus
     * warnings and errors exactly as the tools wrote them. Nothing is ever removed while the build runs. Guarded by {@link #lock}.
     */
    private final List<Entry> entries = new ArrayList<>();

    private static final int STEP = 0, DETAIL = 1, MESSAGE = 2;

    private static final class Entry {
        final int kind;
        final String text;
        final long at;
        /** False for the continuation lines of a multi-line message: they get no time stamp. */
        final boolean stamped;
        Entry(int kind, String text, long at, boolean stamped) { this.kind = kind; this.text = text; this.at = at; this.stamped = stamped; }
    }
    private volatile BuildProgressTranslator progress = new BuildProgressTranslator();

    private volatile State state = State.IDLE;
    private volatile File lastApk;
    private volatile File lastAab;
    private volatile boolean lastWasBundle;
    private volatile String lastAppName = "";
    private volatile String lastMessage = "";
    /** Set when a library failed its checksum check; the screen shows it once in a dialog (see {@link #takeChecksumFailure()}). */
    private volatile ChecksumFailure checksumFailure;
    private volatile String projectName = "";
    private volatile BuildPipeline activePipeline;
    private volatile String lastLogLine = "";
    /** Friendly "what is happening now" text for the Build screen and the notification; never contains tool names. */
    private volatile String liveLine = "";
    private volatile long liveSinceMillis;
    private volatile long startedAtMillis;
    private volatile long finishedAtMillis;
    private long jobCounter;
    private long activeJobId;

    private ProjectBuildService(Context appContext) {
        this.appContext = appContext;
    }

    // ------------------------------------------------------------------ public API

    public void addListener(Listener listener) { if (listener != null) listeners.addIfAbsent(listener); }

    public void removeListener(Listener listener) { listeners.remove(listener); }

    public boolean isBuilding() { return state == State.RUNNING; }

    public State getState() { return state; }

    public File getLastApk() { return lastApk; }

    /** The exported bundle of the last "Export AAB" build, or null. */
    public File getLastAab() { return lastAab; }

    /** True when the last build was started as an AAB export. */
    public boolean wasBundle() { return lastWasBundle; }

    /** App name used for the file names of the last build (the app_name string, or the project name). */
    public String getLastAppName() { return lastAppName; }

    public String getLastMessage() { return lastMessage; }

    /** The checksum failure that stopped the last build, or null. Returns it once: the screen shows the dialog and the next call gives null. */
    public synchronized ChecksumFailure takeChecksumFailure() {
        ChecksumFailure taken = checksumFailure;
        checksumFailure = null;
        return taken;
    }

    public String getProjectName() { return projectName; }

    public long getActiveJobId() { synchronized (lock) { return activeJobId; } }

    /** Whole log of the current or most recent build. */
    public String getLog() { synchronized (lock) { return log.toString(); } }

    /**
     * The log for the Build screen, one line per step or detail: "[time since start] text  (how long it took)". The running step
     * shows its time so far. Steps keep their lines for the whole build, so the time of every earlier step stays visible.
     */
    public String getDisplayLog() {
        synchronized (lock) {
            long now = System.currentTimeMillis();
            long start = startedAtMillis;
            long end = finishedAtMillis != 0 ? finishedAtMillis : now;
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < entries.size(); i++) {
                Entry e = entries.get(i);
                if (out.length() > 0) out.append('\n');
                if (!e.stamped) { out.append("         ").append(e.text); continue; }
                out.append('[').append(clock(e.at - start)).append("] ");
                if (e.kind == DETAIL) out.append("  ");
                out.append(e.text);
                if (e.kind != MESSAGE) out.append("  (").append(clock(nextStart(i, end) - e.at)).append(')');
            }
            return out.toString();
        }
    }

    /** A step lasts until the next step starts; a detail lasts until the next step or detail starts. Falls back to the end time. */
    private long nextStart(int index, long fallback) {
        int kind = entries.get(index).kind;
        for (int j = index + 1; j < entries.size(); j++) {
            int other = entries.get(j).kind;
            if (other == STEP || (other == DETAIL && kind == DETAIL)) return entries.get(j).at;
        }
        return fallback;
    }

    /** Log text from the given character offset, so a poller can fetch only what is new. */
    public String getLogFrom(int offset) {
        synchronized (lock) {
            int start = Math.max(0, Math.min(offset, log.length()));
            return log.substring(start);
        }
    }

    public int getLogLength() { synchronized (lock) { return log.length(); } }

    /** Most recent log line, a cheap way to tell what a running build is doing. */
    public String getLastLogLine() { return lastLogLine; }

    /** What the build is doing right now, in plain words (the Build screen and the notification show this, not the raw line). */
    public String getLiveLine() { return liveLine; }

    /** The live line plus a running clock: time on this activity, and total build time. Empty when there is nothing to show. */
    public String getLiveStatus() {
        String line = liveLine;
        if (line == null || line.isEmpty() || !isBuilding()) return "";
        long now = System.currentTimeMillis();
        return line + "  ·  " + clock(now - liveSinceMillis) + "  (total " + clock(now - startedAtMillis) + ")";
    }

    private static String clock(long millis) {
        long seconds = Math.max(0, millis / 1000);
        return seconds >= 3600 ? String.format(java.util.Locale.ROOT, "%d:%02d:%02d", seconds / 3600, (seconds / 60) % 60, seconds % 60)
            : String.format(java.util.Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60);
    }

    /** Plain-language name of the step the build has reached ("Preparing libraries…"), for the notification. */
    public String getProgressLabel() { return progress.currentLabel(); }

    /** 0-based index of the step reached so far, or -1 before the first one. */
    public int getProgressStep() { return progress.reachedStep(); }

    /** How many steps a build has, so a progress bar can be drawn. */
    public int getProgressStepCount() { return BuildProgressTranslator.STEPS.length; }

    /** Wall-clock time the current or last build started, in milliseconds. */
    public long getStartedAtMillis() { return startedAtMillis; }

    /** Seconds since the build started; stops counting when it finishes. */
    public long getElapsedSeconds() {
        long start = startedAtMillis;
        if (start == 0) return 0;
        long end = finishedAtMillis != 0 ? finishedAtMillis : System.currentTimeMillis();
        return Math.max(0, (end - start) / 1000);
    }

    /**
     * Blocks until the build is no longer running or the timeout passes, whichever is first.
     * One call replaces many polls, so a client spends one round trip instead of dozens.
     */
    public State awaitFinish(long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + Math.max(0, timeoutMillis);
        synchronized (lock) {
            while (state == State.RUNNING) {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) break;
                lock.wait(left);
            }
            return state;
        }
    }

    /** Only the lines that explain a failure, capped, so a client does not have to read the whole log. */
    public String getErrorSummary(int maxLines) {
        String[] lines = getLog().split("\n");
        List<String> picked = new ArrayList<>();
        for (String line : lines) {
            String lower = line.toLowerCase(java.util.Locale.ROOT);
            if (lower.startsWith("error") || lower.contains(" error ") || lower.contains("error:") || lower.contains("exception")
                    || lower.startsWith("build failed") || lower.startsWith("build cancelled") || lower.startsWith("warning: ") && lower.contains("unsupported")) {
                picked.add(line.length() > 300 ? line.substring(0, 300) + "..." : line);
            }
        }
        if (picked.isEmpty()) return "";
        int from = Math.max(0, picked.size() - maxLines);
        StringBuilder out = new StringBuilder();
        if (from > 0) out.append("[").append(from).append(" earlier error lines skipped]\n");
        for (int i = from; i < picked.size(); i++) out.append(picked.get(i)).append('\n');
        return out.toString().trim();
    }

    /** The last few lines of the log. */
    public String getLogTail(int maxLines) {
        String[] lines = getLog().split("\n");
        int from = Math.max(0, lines.length - Math.max(1, maxLines));
        StringBuilder out = new StringBuilder();
        for (int i = from; i < lines.length; i++) out.append(lines[i].length() > 300 ? lines[i].substring(0, 300) + "..." : lines[i]).append('\n');
        return out.toString().trim();
    }

    /**
     * Starts a build of the given project folder.
     *
     * @return the new job id, or -1 when a build is already running
     */
    public long start(File project) { return start(project, false); }

    /** @param bundle also create an Android App Bundle and copy it to Studio Mobile/Export AAB */
    public long start(File project, boolean bundle) {
        final long jobId;
        synchronized (lock) {
            if (state == State.RUNNING) return -1;
            jobCounter++;
            activeJobId = jobId = jobCounter;
            log.setLength(0);
            entries.clear();
            progress = new BuildProgressTranslator();
            state = State.RUNNING;
            lastApk = null;
            lastAab = null;
            lastWasBundle = bundle;
            lastMessage = "";
            checksumFailure = null;
            lastLogLine = "";
            liveLine = "";
            liveSinceMillis = System.currentTimeMillis();
            startedAtMillis = System.currentTimeMillis();
            finishedAtMillis = 0;
            projectName = project.getName();
        }
        notifyState(State.RUNNING);
        addStep(LanguageUi.BUILD_PREPARING);
        BuildForegroundService.start(appContext);   // keeps the build alive when the app is left; a failure here never blocks the build
        worker.execute(() -> run(project, bundle));
        return jobId;
    }

    public void cancel() {
        BuildPipeline pipeline = activePipeline;
        if (pipeline != null) pipeline.cancel();
    }

    // ------------------------------------------------------------------ build execution

    private void run(File project, boolean bundle) {
        State finalState = State.FAILED;
        File copiedProject = null;
        Throwable crash = null;
        String failureStage = "BUILD";
        try {
            appendDetail(LanguageUi.BUILD_TOOLCHAIN + "\n" + LocalLibsInstaller.install(appContext));   // internal: stays out of the screen log
            File buildRoot = new File(appContext.getCacheDir(), "user-project-build");
            copiedProject = buildRoot;
            deleteRecursively(buildRoot);
            copyDirectory(project, buildRoot);
            if (new DebugModeSettings(appContext).isEnabled()) {
                try { appendInfo(com.example.studiomobile.build.DebugModeInjector.inject(appContext, buildRoot)); }
                catch (Exception failure) { appendShown("WARNING: Debug Mode could not be added: " + failure.getMessage()); }
            }

            try { new ProjectKeys(appContext.getFilesDir()).applyToBuild(new File(appContext.getFilesDir(), "build-tools"), project.getName()); }
            catch (IOException failure) { appendShown("WARNING: could not apply the key of this project: " + failure.getMessage()); }

            File apkOutput = new File(appContext.getFilesDir(), "built-apks");
            File[] oldApks = apkOutput.listFiles();
            if (oldApks != null) for (File old : oldApks) deleteRecursively(old);

            boolean hasBuildFiles = GradleBuildPipeline.isGradleProject(buildRoot);
            // Gradle only when the project really needs it (Android Studio project, extra plugins, ...); otherwise the local pipeline builds it.
            String gradleReason = hasBuildFiles ? com.example.studiomobile.build.GradleNeedDetector.reasonToUseGradle(buildRoot) : null;
            boolean useGradle = hasBuildFiles && gradleReason != null && hasGradleRuntime();
            if (hasBuildFiles) appendInfo("[internal] Build route: " + (useGradle ? "Gradle (" + gradleReason + ")" : "local pipeline (no Gradle-only feature found)"));
            BuildConfiguration configuration = new BuildConfiguration(buildRoot, apkOutput, !hasBuildFiles, bundle);
            BuildPipeline pipeline = useGradle ? new GradleBuildPipeline(appContext) : new LocalBuildPipeline(appContext);
            activePipeline = pipeline;
            failureStage = useGradle ? "GRADLE BUILD" : "LOCAL BUILD";

            BuildLogger logger = new BuildLogger() {
                @Override public void onInfo(String message) { appendInfo(message); }
                @Override public void onWarning(String message) { appendShown("WARNING: " + message); }   // real warnings and errors are never hidden
                @Override public void onError(String message, Throwable cause) {
                    appendShown("ERROR: " + message);
                    ChecksumFailure failed = ChecksumFailure.find(cause);
                    if (failed != null) checksumFailure = failed;
                }
            };

            BuildResult result = pipeline.build(configuration, logger);
            if (result.isSuccessful()) {
                String appName = readAppName(buildRoot, project.getName());
                lastAppName = appName;
                lastApk = renameApk(result.getApkFile(), appName);
                lastMessage = result.getMessage();
                showProgress(progress.advanceTo(BuildProgressTranslator.LAST_STEP));
                boolean succeeded = true;
                if (bundle) {
                    File built = new File(apkOutput, BuildConfiguration.BUNDLE_FILE_NAME);
                    if (!built.isFile()) {
                        succeeded = false;
                        lastMessage = "The AAB was not created. Export AAB needs the built-in build engine; projects built with Gradle are not supported yet.";
                        appendShown("ERROR: " + lastMessage);
                    } else {
                        File exported = copyTo(built, new File(StudioStorage.exportAabDir(appContext), appName + ".aab"));
                        lastAab = exported;
                        appendDetail(LanguageUi.BUILD_SUCCESS + exported.getAbsolutePath());
                        showOnly(BuildProgressTranslator.SUCCESS + "\nAAB saved to: " + exported.getAbsolutePath());
                    }
                } else {
                    appendDetail(LanguageUi.BUILD_SUCCESS + lastApk.getAbsolutePath());
                    showOnly(BuildProgressTranslator.SUCCESS + "\nAPK ready: " + lastApk.getName());
                }
                finalState = succeeded ? State.SUCCESS : State.FAILED;
            } else {
                lastMessage = result.getMessage();
                appendShown("BUILD " + result.getStatus() + ": " + result.getMessage());
                if (result.getStatus() != BuildResult.Status.CANCELLED) appendTechnicalDetails();
                finalState = result.getStatus() == BuildResult.Status.CANCELLED ? State.CANCELLED : State.FAILED;
            }
        } catch (Throwable error) {
            // Throwable, not Exception: compiler engines can fail with OutOfMemoryError or
            // NoClassDefFoundError on a device, and the state must never stay stuck on RUNNING.
            crash = error;
            lastMessage = String.valueOf(error.getMessage());
            java.io.StringWriter stackText = new java.io.StringWriter();
            error.printStackTrace(new java.io.PrintWriter(stackText));
            appendShown("ERROR: Build stopped by " + error.getClass().getName() + ": " + error.getMessage() + "\n" + stackText);
        } finally {
            activePipeline = null;
            if (finalState == State.FAILED) saveFailureReport(failureStage, crash);   // every failed build lands in Studio Mobile/Build Error Logs
            long endedAt = System.currentTimeMillis();
            finishedAtMillis = endedAt;
            showOnly("Total build time: " + clock(endedAt - startedAtMillis));   // the whole build, from the first step to the result
            synchronized (lock) {
                state = finalState;
                lock.notifyAll();
            }
            notifyState(finalState);
            // The copy of the project is only needed while building; removing it here keeps the app's cache from growing with every build.
            if (copiedProject != null) com.example.studiomobile.build.BuildCacheCleaner.deleteTree(copiedProject);
        }
    }

    /** Writes the full log of a failed build to Studio Mobile/Build Error Logs (only the latest is kept). Never throws. */
    private void saveFailureReport(String stage, Throwable crash) {
        try {
            String detail = lastMessage == null || lastMessage.isEmpty() ? "Build failed." : lastMessage;
            File report = com.example.studiomobile.build.BuildFailureReport.write(StudioStorage.errorLogsDir(appContext), stage, crash, detail, getLog());
            if (report != null) appendShown("Full diagnostic report saved to: " + report.getAbsolutePath());
            else appendShown("WARNING: Could not save the diagnostic report to Build Error Logs.");
        } catch (Throwable ignored) { /* diagnostics must never hide the real build failure */ }
    }

    /** Reads the app_name string of the project; falls back to the project folder name. The result is safe as a file name. */
    private static String readAppName(File buildRoot, String fallback) {
        String name = null;
        try {
            File module = com.example.studiomobile.build.BuildProjectFiles.moduleDirectory(buildRoot);
            File strings = new File(com.example.studiomobile.build.BuildProjectFiles.mainDirectory(buildRoot), "res/values/strings.xml");
            if (!strings.isFile()) strings = new File(module, "src/main/res/values/strings.xml");
            if (strings.isFile()) {
                String text = new String(java.nio.file.Files.readAllBytes(strings.toPath()), java.nio.charset.StandardCharsets.UTF_8);
                java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("<string\\s+name=\"app_name\"[^>]*>([^<]+)</string>").matcher(text);
                if (matcher.find()) name = matcher.group(1).trim();
            }
        } catch (Exception ignored) { }
        String cleaned = name == null ? "" : name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        if (cleaned.isEmpty() || cleaned.equals(".") || cleaned.equals("..")) cleaned = fallback.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        return cleaned.isEmpty() ? "app" : cleaned;
    }

    /** Renames app-debug.apk to <app name>.apk inside the private build folder. Keeps the old name when the rename fails. */
    private static File renameApk(File apk, String appName) {
        File target = new File(apk.getParentFile(), appName + ".apk");
        if (target.equals(apk)) return apk;
        if (target.exists() && !target.delete()) return apk;
        return apk.renameTo(target) ? target : apk;
    }

    private static File copyTo(File source, File target) throws IOException {
        File parent = target.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("Could not create " + parent);
        try (InputStream in = new FileInputStream(source); OutputStream out = new FileOutputStream(target)) {
            byte[] buffer = new byte[32768]; int count;
            while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
        }
        return target;
    }

    // Every line goes to the full log; only some of them reach the screen.

    /** A progress line from the engine: shown only when it starts a new simple step (or when it looks like a warning or error). */
    private void appendInfo(String message) {
        appendDetail(message);
        String live = BuildProgressTranslator.liveText(message);
        if (live != null) { if (!live.equals(liveLine)) liveSinceMillis = System.currentTimeMillis(); liveLine = live; }
        showProgress(progress.translate(message));
        String detail = BuildProgressTranslator.historyText(message);
        if (detail != null) addDetail(detail);
    }

    /** A message that is already meant for the user (warnings, errors, results): written to both logs unchanged. */
    private void appendShown(String message) {
        appendDetail(message);
        liveSinceMillis = System.currentTimeMillis();
        liveLine = message == null ? "" : message;   // warnings and errors are shown exactly as written
        showOnly(message);
    }

    /** Full log only. */
    private void appendDetail(String message) {
        synchronized (lock) { log.append(message).append('\n'); lastLogLine = message; }
    }

    /** Lines from the translator: a fixed step label starts a new timed step, anything else (a warning or error) is shown as it is. */
    private void showProgress(String lines) {
        if (lines == null) return;
        for (String line : lines.split("\n")) {
            if (BuildProgressTranslator.isStepLabel(line)) addStep(line); else showOnly(line);
        }
    }

    private void addStep(String label) { add(STEP, label, true); }

    /** A detail under the current step. The same text twice in a row is not repeated. */
    private void addDetail(String text) {
        synchronized (lock) {
            if (!entries.isEmpty()) {
                Entry last = entries.get(entries.size() - 1);
                if (last.kind == DETAIL && last.text.equals(text)) return;
            }
        }
        add(DETAIL, text, true);
    }

    /** A message for the reader (warning, error, result). Several lines are stamped once, on the first line. */
    private void showOnly(String message) {
        String[] lines = message.split("\n", -1);
        for (int i = 0; i < lines.length; i++) add(MESSAGE, lines[i], i == 0);
    }

    private void add(int kind, String text, boolean stamped) {
        synchronized (lock) { entries.add(new Entry(kind, text, System.currentTimeMillis(), stamped)); }
        for (Listener listener : listeners) listener.onLogAppended(text);
    }

    /** After a failed build the screen also gets the engine's last lines, so the real cause is never out of sight. */
    private void appendTechnicalDetails() {
        // Same lines for APK and AAB builds. Bookkeeping that is written for the log file only (tags, toolchain and catalog notes,
        // file system paths of the app) is left out; what remains is what the build tool itself reported.
        List<String> keep = new ArrayList<>();
        for (String line : getLog().split("\n")) if (!isInternalLine(line)) keep.add(line);
        int from = Math.max(0, keep.size() - 80);
        StringBuilder out = new StringBuilder("\n--- Technical details (last " + (keep.size() - from) + " lines) ---\n");
        for (int i = from; i < keep.size(); i++) out.append(keep.get(i)).append('\n');
        showOnly(out.toString().trim());
    }

    private static boolean isInternalLine(String line) {
        String lower = line.trim().toLowerCase(java.util.Locale.ROOT);
        return lower.isEmpty() || lower.startsWith("[internal]") || lower.startsWith("[step]") || lower.startsWith("[progress]")
            || lower.startsWith("toolchain install") || lower.startsWith("android.jar api") || lower.startsWith("kotlin-stdlib")
            || lower.contains("catalog entry") || lower.contains("/data/user/") || lower.contains("gradle-runtime");
    }

    private void notifyState(State newState) {
        for (Listener listener : listeners) listener.onStateChanged(newState);
    }

    // ------------------------------------------------------------------ helpers (moved from MainActivity)

    private boolean hasGradleRuntime() {
        File files = appContext.getFilesDir();
        // The java launcher shipped in the APK lets the pipeline install Gradle and a JDK from the catalog on first use.
        File nativeDir = new File(appContext.getApplicationInfo().nativeLibraryDir);
        return new File(nativeDir, "libjavaexe.so").isFile()
            || new File(files, "build-tools/gradle/bin/gradle").isFile()
            || new File(files, "gradle/bin/gradle").isFile()
            || new File(files, "gradle-runtime").isDirectory();
    }

    private static boolean skipWhenCopying(String name) {
        return name.equals(".git") || name.equals(".gradle") || name.equals("build") || name.equals("outputs") || name.equals(".idea");
    }

    private static void copyDirectory(File source, File destination) throws IOException {
        if (source.isDirectory()) {
            if (!destination.exists() && !destination.mkdirs()) throw new IOException("Could not create directory: " + destination);
            File[] children = source.listFiles();
            if (children != null) {
                for (File child : children) {
                    if (!skipWhenCopying(child.getName())) copyDirectory(child, new File(destination, child.getName()));
                }
            }
            return;
        }
        try (InputStream input = new FileInputStream(source); OutputStream output = new FileOutputStream(destination)) {
            byte[] buffer = new byte[32768];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
        }
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteRecursively(child);
        }
        file.delete();
    }
}
