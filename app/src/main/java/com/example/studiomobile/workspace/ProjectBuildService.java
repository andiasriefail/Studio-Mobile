package com.example.studiomobile.workspace;

import android.content.Context;
import android.os.SystemClock;

import com.example.studiomobile.LanguageUi;
import com.example.studiomobile.build.BuildConfiguration;
import com.example.studiomobile.build.BuildLogger;
import com.example.studiomobile.build.BuildPipeline;
import com.example.studiomobile.build.BuildResult;
import com.example.studiomobile.build.GradleBuildPipeline;
import com.example.studiomobile.build.LocalBuildPipeline;
import com.example.studiomobile.build.LocalLibsInstaller;
import com.example.studiomobile.build.dependency.ChecksumFailure;
import com.example.studiomobile.build.network.NetworkMonitor;
import com.example.studiomobile.build.process.ChildProcessReaper;

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
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

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
    private final List<BuildLogRenderer.Entry> entries = new ArrayList<>();

    private static final int STEP = BuildLogRenderer.STEP, DETAIL = BuildLogRenderer.DETAIL, MESSAGE = BuildLogRenderer.MESSAGE, HEARTBEAT = BuildLogRenderer.HEARTBEAT;

    /** Where the working copies of Gradle projects are kept between builds (inside the app's cache, so "Clear cache" resets them). */
    private static final String WORKSPACES_FOLDER = "studio-workspaces";
    /** While a step is quiet the log gets one "still working" line, rewritten every few seconds, so a long silent step is never mistaken for a frozen one. */
    private static final long HEARTBEAT_EVERY_SECONDS = 5, QUIET_BEFORE_HEARTBEAT_MILLIS = 15_000, CPU_ACTIVE_TICKS = 20;
    private final ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "BuildHeartbeat");
        thread.setDaemon(true);
        return thread;
    });
    private volatile ScheduledFuture<?> heartbeatTask;
    private volatile long lastRealEntryAt;
    private volatile long previousCpuTicks = -1;
    private volatile boolean buildOnline;
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
    /** How long Retry stays available after the connection was lost; nothing runs meanwhile, the window only limits how long the result stays retryable. */
    private static final long RETRY_WINDOW_MILLIS = 5 * 60 * 1000L;
    public static final long RETRY_BUSY = -1, RETRY_EXPIRED = -2, RETRY_OFFLINE = -3;
    private volatile File retryProject;
    private volatile boolean retryBundle;
    private volatile long retryDeadlineElapsed;
    private volatile boolean connectionTimeoutPending;
    private volatile BuildPipeline activePipeline;
    private volatile com.example.studiomobile.shell.BuildScriptHooks activeHooks;
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
        initPathMask(appContext);
    }

    /** The app's private folder as this process sees it; the folder name is never written in code, so a new applicationId changes nothing. */
    private static void initPathMask(Context context) {
        try {
            File parent = context.getFilesDir().getParentFile();
            if (parent == null) return;
            String canonical = null;
            try { canonical = parent.getCanonicalPath(); } catch (IOException ignored) { }
            LogPathMasker.init(parent.getAbsolutePath(), canonical);
        } catch (RuntimeException ignored) { /* the generic patterns still apply */ }
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

    /** True once after a build stopped because the connection was lost: the screen shows the Retry dialog and the next call gives false. */
    public synchronized boolean takeConnectionTimeout() { boolean pending = connectionTimeoutPending; connectionTimeoutPending = false; return pending; }

    /** Seconds left in which Retry is still offered after a lost connection; 0 when there is none or it ran out. */
    public long getRetryRemainingSeconds() {
        long deadline = retryDeadlineElapsed, left = deadline - SystemClock.elapsedRealtime();
        return deadline == 0 || left <= 0 ? 0 : (left + 999) / 1000;
    }

    /**
     * Starts the interrupted build again. Returns the new job id, or {@link #RETRY_BUSY}, {@link #RETRY_EXPIRED} (no window or it ran out)
     * or {@link #RETRY_OFFLINE} (the system does not report a working connection yet; the window stays open).
     */
    public long retry() {
        if (state == State.RUNNING) return RETRY_BUSY;
        File project = retryProject;
        if (project == null || getRetryRemainingSeconds() <= 0) return RETRY_EXPIRED;
        if (!NetworkMonitor.isOnlineNow(appContext)) return RETRY_OFFLINE;
        long id = start(project, retryBundle);   // starting clears the window
        return id < 0 ? RETRY_BUSY : id;
    }

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

    /** The Build screen log without line wrapping (what Copy log copies). */
    public String getDisplayLog() { return getDisplayLog(0); }

    /**
     * The log for the Build screen (see {@link BuildLogRenderer}): every line starts its text to the right of one straight margin rule,
     * repeated details are shown once with their count and total time, and the running step shows its time so far.
     */
    public String getDisplayLog(int columns) {
        synchronized (lock) {
            long end = finishedAtMillis != 0 ? finishedAtMillis : System.currentTimeMillis();
            return BuildLogRenderer.render(entries, startedAtMillis, end, columns);
        }
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

    private static String clock(long millis) { return BuildLogRenderer.clock(millis); }

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
            retryProject = project;
            retryBundle = bundle;
            retryDeadlineElapsed = 0;
            connectionTimeoutPending = false;
        }
        notifyState(State.RUNNING);
        addStep(LanguageUi.BUILD_PREPARING);
        previousCpuTicks = -1;
        buildOnline = true;
        ScheduledFuture<?> oldTask = heartbeatTask;
        if (oldTask != null) oldTask.cancel(false);
        heartbeatTask = ticker.scheduleWithFixedDelay(this::beat, HEARTBEAT_EVERY_SECONDS, HEARTBEAT_EVERY_SECONDS, TimeUnit.SECONDS);
        BuildForegroundService.start(appContext);   // keeps the build alive when the app is left; a failure here never blocks the build
        worker.execute(() -> run(project, bundle));
        return jobId;
    }

    public void cancel() {
        com.example.studiomobile.shell.BuildScriptHooks hooks = activeHooks;
        if (hooks != null) hooks.cancel();
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
            File buildRoot;
            if (canKeepWorkspace(project)) {
                // Gradle route: the working copy stays between builds, so Gradle finds its own outputs and state and redoes only what changed.
                File workspaces = new File(appContext.getCacheDir(), WORKSPACES_FOLDER);
                buildRoot = ProjectWorkspace.folderFor(workspaces, project);
                ProjectWorkspace.evictOldest(workspaces, buildRoot);
                boolean fresh = ProjectWorkspace.ensureStamp(buildRoot, workspaceStamp());
                ProjectWorkspace.sync(project, buildRoot);
                buildRoot.setLastModified(System.currentTimeMillis());
                appendInfo("[internal] Project workspace: " + (fresh ? "new" : "reused") + " (" + buildRoot.getName() + ")");
            } else {
                buildRoot = new File(appContext.getCacheDir(), "user-project-build");
                copiedProject = buildRoot;
                deleteRecursively(buildRoot);
                copyDirectory(project, buildRoot);
            }
            com.example.studiomobile.build.BuildFailureReport.begin(readAppName(buildRoot, project.getName()));   // error log file is named after the app
            // A Flutter project (pubspec.yaml with the Flutter SDK dependency) has its own route; the Android crash catcher is not injected into it.
            com.example.studiomobile.build.flutter.FlutterProjectDetector.Detection flutter = com.example.studiomobile.build.flutter.FlutterProjectDetector.detect(buildRoot);
            if (!flutter.isFlutter() && new DebugModeSettings(appContext).isEnabled()) {
                if (bundle) {
                    // Export AAB is for Google Play: the crash catcher must never be inside a bundle that is published. The saved Debug Mode choice is not changed.
                    appendShown("Debug Mode is off for Export AAB: the crash catcher is not added to the bundle.");
                } else {
                    try { appendInfo(com.example.studiomobile.build.DebugModeInjector.inject(appContext, buildRoot)); }
                    catch (Exception failure) { appendShown("WARNING: Debug Mode could not be added: " + failure.getMessage()); }
                }
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
            if (flutter.isFlutter()) appendInfo("[internal] Build route: Flutter (pubspec.yaml depends on the Flutter SDK)");
            else if (hasBuildFiles) appendInfo("[internal] Build route: " + (useGradle ? "Gradle (" + gradleReason + ")" : "local pipeline (no Gradle-only feature found)"));
            BuildConfiguration configuration = new BuildConfiguration(buildRoot, apkOutput, !hasBuildFiles && !flutter.isFlutter(), bundle);
            buildOnline = !configuration.isOfflineMode();
            BuildPipeline pipeline = flutter.isFlutter() ? new com.example.studiomobile.build.flutter.FlutterBuildPipeline(appContext)
                : useGradle ? new GradleBuildPipeline(appContext) : new LocalBuildPipeline(appContext);
            activePipeline = pipeline;
            failureStage = flutter.isFlutter() ? "FLUTTER BUILD" : useGradle ? "GRADLE BUILD" : "LOCAL BUILD";

            BuildLogger logger = new BuildLogger() {
                @Override public void onInfo(String message) { appendInfo(message); }
                @Override public void onWarning(String message) { appendShown("WARNING: " + message); }   // real warnings and errors are never hidden
                @Override public void onError(String message, Throwable cause) {
                    appendShown("ERROR: " + message);
                    ChecksumFailure failed = ChecksumFailure.find(cause);
                    if (failed != null) checksumFailure = failed;
                }
            };

            BuildResult result = buildWithScripts(pipeline, configuration, buildRoot, logger);
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
                        lastMessage = "The AAB was not created. The build finished without producing a bundle; see Build Logs for the reason.";
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
                if (result.getStatus() == BuildResult.Status.FAILED && lastMessage.startsWith(NetworkMonitor.CONNECTION_TIMEOUT_LABEL)) {
                    retryDeadlineElapsed = SystemClock.elapsedRealtime() + RETRY_WINDOW_MILLIS;   // the screen offers Retry until this moment
                    connectionTimeoutPending = true;
                } else if (result.getStatus() != BuildResult.Status.CANCELLED) appendTechnicalDetails();
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
            ScheduledFuture<?> beatTask = heartbeatTask;
            if (beatTask != null) beatTask.cancel(false);
            activePipeline = null;
            activeHooks = null;
            if (finalState == State.FAILED) saveFailureReport(failureStage, crash);   // every failed build lands in Studio Mobile/Build Error Logs
            long endedAt = System.currentTimeMillis();
            finishedAtMillis = endedAt;
            showOnly("Total build time: " + clock(endedAt - startedAtMillis));   // the whole build, from the first step to the result
            String perPart;
            synchronized (lock) { perPart = BuildLogRenderer.phaseSummary(entries, endedAt); }
            if (!perPart.isEmpty()) showOnly(perPart);   // where the time went, in one line
            synchronized (lock) {
                state = finalState;
                lock.notifyAll();
            }
            notifyState(finalState);
            // The copy of the project is only needed while building; removing it here keeps the app's cache from growing with every build.
            if (copiedProject != null) com.example.studiomobile.build.BuildCacheCleaner.deleteTree(copiedProject);
        }
    }

    /**
     * Runs scripts/pre-build.sh, the build itself, then scripts/post-build.sh (each only when the project has it). A failing script becomes a
     * normal FAILED result with the script's own message, so the build screen shows it like any other build error.
     */
    private BuildResult buildWithScripts(BuildPipeline pipeline, BuildConfiguration configuration, File buildRoot, BuildLogger logger) throws Exception {
        java.util.List<File> scripts = com.example.studiomobile.shell.BuildScriptHooks.find(buildRoot);
        if (scripts.isEmpty()) return pipeline.build(configuration, logger);
        java.util.List<String> problems = new ArrayList<>();
        com.example.studiomobile.shell.ShellRuntime runtime = com.example.studiomobile.shell.ShellRuntime.locate(problems);
        if (runtime == null) return new BuildResult(BuildResult.Status.FAILED, null, String.join(" ", problems));
        com.example.studiomobile.shell.BuildScriptHooks hooks = new com.example.studiomobile.shell.BuildScriptHooks(
            new com.example.studiomobile.shell.ShellScriptRunner(runtime, new File(appContext.getFilesDir(), "shell-home"), appContext.getCacheDir()),
            names -> com.example.studiomobile.build.DownloadPrompt.confirm("Run build scripts?",
                "This project wants to run shell scripts during the build:\n\n" + String.join("\n", names)
                    + "\n\nOnly continue if you trust where this project came from.", "Run"));
        activeHooks = hooks;
        try {
            hooks.runPreBuild(buildRoot, logger);
        } catch (IllegalStateException failure) {
            return new BuildResult(BuildResult.Status.FAILED, null, failure.getMessage());
        }
        BuildResult result = pipeline.build(configuration, logger);
        if (!result.isSuccessful()) return result;
        try {
            hooks.runPostBuild(buildRoot, result.getApkFile(), logger);
        } catch (IllegalStateException failure) {
            return new BuildResult(BuildResult.Status.FAILED, null, failure.getMessage());
        }
        return result;
    }

    /** Writes the full log of a failed build to Studio Mobile/Build Error Logs (one file per app: <app name>-error-logs.txt). Never throws. */
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
        String live = LogPathMasker.mask(BuildProgressTranslator.liveText(message));
        if (live != null) { if (!live.equals(liveLine)) liveSinceMillis = System.currentTimeMillis(); liveLine = live; }
        showProgress(progress.translate(message));
        String detail = BuildProgressTranslator.historyText(message);
        if (detail != null) addDetail(detail);
    }

    /** A message that is already meant for the user (warnings, errors, results): written to both logs unchanged. */
    private void appendShown(String message) {
        appendDetail(message);
        liveSinceMillis = System.currentTimeMillis();
        liveLine = message == null ? "" : LogPathMasker.mask(message);   // warnings and errors are shown as written, minus the app's private folder
        showOnly(message);
    }

    /** Full log only. */
    private void appendDetail(String message) {
        String safe = LogPathMasker.mask(message);
        synchronized (lock) { log.append(safe).append('\n'); lastLogLine = safe; }
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
                BuildLogRenderer.Entry last = entries.get(entries.size() - 1);
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
        String safe = LogPathMasker.mask(text);
        long now = System.currentTimeMillis();
        lastRealEntryAt = now;
        synchronized (lock) { entries.add(new BuildLogRenderer.Entry(kind, safe, now, stamped)); }
        for (Listener listener : listeners) listener.onLogAppended(safe);
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
            || lower.contains("catalog entry") || lower.contains("/data/user/") || lower.contains(LogPathMasker.MARK) || lower.contains("gradle-runtime");
    }

    private void notifyState(State newState) {
        for (Listener listener : listeners) listener.onStateChanged(newState);
    }

    /**
     * One "still working" line under a step that has been quiet for a while, rewritten in place every few seconds. It says whether the
     * build processes are using the CPU, whether the network is gone, or how much has been downloaded, in plain words only.
     */
    private void beat() {
        try {
            if (state != State.RUNNING) return;
            long ticks = ChildProcessReaper.descendantsCpuTicks();
            long before = previousCpuTicks;
            previousCpuTicks = ticks;
            long now = System.currentTimeMillis();
            long quiet = now - lastRealEntryAt;
            if (quiet < QUIET_BEFORE_HEARTBEAT_MILLIS) return;
            String live = liveLine;
            String text;
            if (buildOnline && !NetworkMonitor.isOnlineNow(appContext)) {
                text = "· waiting for the network · " + clock(quiet);
            } else if (live != null && live.contains(" MB")) {
                text = "· " + (live.endsWith("…") ? live.substring(0, live.length() - 1) : live) + " · " + clock(quiet);
            } else {
                text = "· still working · " + clock(quiet);
                if (before >= 0 && ticks >= before) text += ticks - before >= CPU_ACTIVE_TICKS ? " · CPU active" : " · waiting, little activity";
            }
            String safe = LogPathMasker.mask(text);
            synchronized (lock) {
                int count = entries.size();
                BuildLogRenderer.Entry last = count == 0 ? null : entries.get(count - 1);
                if (last != null && last.kind == HEARTBEAT) last.text = safe;
                else entries.add(new BuildLogRenderer.Entry(HEARTBEAT, safe, now, true));
            }
            for (Listener listener : listeners) listener.onLogAppended(safe);
        } catch (RuntimeException ignored) { /* a missed beat is never worth a failed build */ }
    }

    /** True when the Gradle route will be used for this project, so its working copy can be kept between builds. Other routes keep the old per-build copy. */
    private boolean canKeepWorkspace(File project) {
        try {
            if (com.example.studiomobile.build.flutter.FlutterProjectDetector.detect(project).isFlutter()) return false;
            if (!GradleBuildPipeline.isGradleProject(project)) return false;
            return com.example.studiomobile.build.GradleNeedDetector.reasonToUseGradle(project) != null && hasGradleRuntime();
        } catch (RuntimeException unknown) { return false; }
    }

    /** Changes with every update of the app, so a new app version starts from a clean working copy once. */
    private String workspaceStamp() {
        try { return "v1:" + appContext.getPackageManager().getPackageInfo(appContext.getPackageName(), 0).lastUpdateTime; }
        catch (Exception unknown) { return "v1"; }
    }

    /** Removes the saved working copies; the next build of every project is a full build. Refused while a build is running. */
    public boolean cleanWorkspaces() {
        if (state == State.RUNNING) return false;
        ProjectWorkspace.deleteTree(new File(appContext.getCacheDir(), WORKSPACES_FOLDER));
        return true;
    }

    // ------------------------------------------------------------------ helpers (moved from MainActivity)

    private boolean hasGradleRuntime() {
        File files = appContext.getFilesDir();
        // A java launcher shipped in the APK lets the pipeline install Gradle and a JDK from the catalog on first use. The APK carries
        // one per JDK major (lib<tool><major>exe.so, e.g. libjava21exe.so), so any major counts; there is no plain libjavaexe.so.
        File nativeDir = new File(appContext.getApplicationInfo().nativeLibraryDir);
        // The JDK archives are built for API 29 (Android 10) and newer (nlauncher-jre-android-multi, config.env ANDROID_API), so on older phones
        // the launchers cannot start a JVM and the local pipeline must be used instead of failing inside Gradle.
        boolean launcherUsable = android.os.Build.VERSION.SDK_INT >= 29
            && !com.example.studiomobile.build.gradle.JdkLauncherSet.scan(nativeDir).majorsWithJava().isEmpty();
        return launcherUsable
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
