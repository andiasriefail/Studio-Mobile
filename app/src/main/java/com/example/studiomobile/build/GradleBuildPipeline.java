package com.example.studiomobile.build;

import android.content.Context;
import android.os.Build;
import android.os.Looper;
import com.example.studiomobile.build.dependency.BuildToolCatalog;
import com.example.studiomobile.build.gradle.GradleRuntime;
import com.example.studiomobile.build.gradle.GradleRuntimeManager;
import com.example.studiomobile.build.process.BuildProcessRunner;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Runs an Android project's own Gradle build using a locally installed Gradle distribution. */
public final class GradleBuildPipeline implements BuildPipeline {
    private final Context context;
    private volatile boolean cancelled;
    private volatile Process activeProcess;
    private volatile GradleRuntimeManager runtimeManager;

    public GradleBuildPipeline(Context context) {
        this.context = context.getApplicationContext();
        BuildExecutableResolver.registerNativeLibraryDirectory(new File(this.context.getApplicationInfo().nativeLibraryDir));
    }

    @Override public BuildResult build(BuildConfiguration configuration, BuildLogger logger) {
        if (Looper.myLooper() == Looper.getMainLooper())
            throw new IllegalStateException("GradleBuildPipeline.build() must not be called on the main thread; it downloads and installs toolchain components.");
        cancelled = false;
        if (logger == null) logger = new BuildLogger() {
            @Override public void onInfo(String message) { }
            @Override public void onWarning(String message) { }
            @Override public void onError(String message, Throwable cause) { }
        };
        File project = configuration.getProjectDirectory();
        if (!isGradleProject(project)) return failed("This folder does not contain settings.gradle(.kts) or build.gradle(.kts).");
        String levelProblem = JavaLanguageLevel.check(project, logger);
        if (levelProblem != null) return failed(levelProblem);
        File output = configuration.getOutputDirectory();
        if (!output.exists() && !output.mkdirs()) return failed("Could not create APK output directory: " + output);
        logger.onInfo("[step] Checking the build tools");
        GradleRuntimeManager.Resolution resolution = runtimeManager().resolve(project, configuration.isOfflineMode(), new ScreenLogger(logger));
        if (cancelled) return new BuildResult(BuildResult.Status.CANCELLED, null, "Gradle build cancelled.");
        if (!resolution.isUsable()) return failed(resolution.error);
        GradleRuntime runtime = resolution.runtime;
        List<String> command = runtime.buildCommand(configuration.isOfflineMode(), "assembleDebug");
        logger.onInfo("Gradle project detected. Using the project's own Android Gradle Plugin and dependency declarations.");
        logger.onInfo(runtime.runsThroughJava()
            ? "Gradle is started through the packaged java launcher (" + runtime.getJavaLauncher().getAbsolutePath() + ") with JDK " + runtime.getJavaMajor() + " at " + runtime.getJavaHome()
            : "Gradle executable (shell script): " + runtime.getGradleExecutable().getAbsolutePath());
        logger.onInfo("Gradle user home (dependency cache): " + runtime.getGradleUserHome().getAbsolutePath());
        if (!runtime.ensureAndroidUserHome()) return failed("Could not create the Android user home directory: " + runtime.getAndroidUserHome());
        logger.onInfo("Android user home (replaces the unwritable /data/.android): " + runtime.getAndroidUserHome().getAbsolutePath());
        logger.onInfo(configuration.isOfflineMode()
            ? "Dependency resolution: offline; all required plugins and artifacts must already be cached."
            : "Dependency resolution: online; Gradle may download required plugins and libraries into the persistent cache.");
        final BuildLogger screenLog = logger;
        DownloadWatcher watcher = new DownloadWatcher(runtime.getGradleUserHome(), screenLog);
        try {
            logger.onInfo("[step] Starting the build engine");
            watcher.start();
            BuildProcessRunner runner = new BuildProcessRunner(new BuildProcessRunner.ProcessObserver() {
                @Override public void onStarted(Process process) { activeProcess = process; }
                @Override public void onFinished(Process process) { if (activeProcess == process) activeProcess = null; }
            });
            BuildProcessRunner.Result result = runner.run(command, project, 30 * 60, logger, runtime.buildEnvironment());
            if (cancelled) return new BuildResult(BuildResult.Status.CANCELLED, null, "Gradle build cancelled.");
            if (!result.isSuccessful() && !result.isTimedOut()) logger.onWarning("If the log shows checksum or 'Could not read' cache errors, clear the Gradle dependency cache and rebuild online.");
            if (!result.isSuccessful()) return failed(result.isTimedOut()
                ? "Gradle build timed out after 30 minutes. See Build Logs for details."
                : "Gradle build failed (exit code " + result.getExitCode() + "). See Build Logs for the Gradle diagnostic.");
            File apk = findApk(project);
            if (apk == null) return failed("Gradle reported success, but no APK was found under app/build/outputs/apk or build/outputs/apk.");
            File destination = new File(output, "gradle-" + System.currentTimeMillis() + "-" + apk.getName());
            if (!copy(apk, destination)) return failed("Gradle built an APK but Studio Mobile could not copy it to the APK output directory.");
            logger.onInfo("APK artifact found: " + destination.getAbsolutePath());
            logger.onWarning("This result confirms Gradle produced an APK artifact; signature verification is not performed by this Gradle adapter yet.");
            return new BuildResult(BuildResult.Status.SUCCESS, destination, "Gradle build completed and an APK artifact was found.");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return cancelled ? new BuildResult(BuildResult.Status.CANCELLED, null, "Gradle build cancelled.")
                : failed("Gradle execution was interrupted: " + exception.getMessage());
        } catch (Exception exception) {
            logger.onError("Gradle execution error: " + exception.getClass().getSimpleName() + ": " + exception.getMessage(), exception);
            return failed("Could not run Gradle: " + exception.getMessage());
        } finally { activeProcess = null; watcher.finish(); }
    }

    /**
     * Wraps the logger while the toolchain is prepared. The raw lines (paths, licences, checksums) go to the full log tagged as
     * internal, so they never show on the screen; a short plain-words step is written beside them. Warnings and errors pass unchanged.
     */
    private static final class ScreenLogger implements BuildLogger {
        private final BuildLogger delegate;
        ScreenLogger(BuildLogger delegate) { this.delegate = delegate; }
        @Override public void onInfo(String message) {
            if (message == null) return;
            delegate.onInfo("[internal] " + message);
            String friendly = friendly(message);
            if (friendly != null) delegate.onInfo(friendly);
        }
        @Override public void onWarning(String message) { delegate.onWarning(message); }
        @Override public void onError(String message, Throwable cause) { delegate.onError(message, cause); }
        private static String friendly(String message) {
            if (message.startsWith("Project requirements")) return "[step] Reading the project requirements";
            if (message.startsWith("Unpacking bundled")) return "[step] Installing the build tools";
            if (message.startsWith("Downloading ")) return "[step] Downloading the build tools";
            if (message.contains(" MiB downloaded")) return "[progress] Downloading the build tools · " + message.substring(message.lastIndexOf(": ") + 2).replace(" downloaded", "");
            if (message.startsWith("Ready:")) return "[step] Build tools ready";
            if (message.startsWith("java launcher self-test passed")) return "[step] Verifying the build tools";
            if (message.startsWith("Dependency preflight")) return "[step] Checking cached libraries";
            return null;
        }
    }

    /** Watches the Gradle dependency cache while Gradle runs and reports real download activity (Gradle itself prints nothing for it). */
    private static final class DownloadWatcher extends Thread {
        private final File modules; private final BuildLogger logger; private volatile boolean stopped;
        DownloadWatcher(File gradleUserHome, BuildLogger logger) { super("GradleDownloadWatcher"); setDaemon(true); this.modules = new File(gradleUserHome, "caches/modules-2"); this.logger = logger; }
        void finish() { stopped = true; interrupt(); }
        @Override public void run() {
            long base = size(modules), last = base, reported = base; int quiet = 0; boolean active = false;
            while (!stopped) {
                try { Thread.sleep(4000); } catch (InterruptedException interrupted) { return; }
                long now = size(modules);
                if (now > last) {
                    quiet = 0;
                    if (!active) { active = true; logger.onInfo("[step] Downloading libraries"); }
                    if (now - reported >= 256 * 1024) { reported = now; logger.onInfo("[progress] Downloading libraries · " + megabytes(now - base) + " so far"); }
                } else if (++quiet >= 2) active = false;
                last = now;
            }
        }
        private static String megabytes(long bytes) { return String.format(java.util.Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0)); }
        private static long size(File file) {
            if (file.isFile()) return file.length();
            File[] children = file.listFiles();
            if (children == null) return 0;
            long total = 0;
            for (File child : children) total += size(child);
            return total;
        }
    }

    public static boolean isGradleProject(File project) {
        if (project == null || !project.isDirectory()) return false;
        return new File(project, "settings.gradle").isFile() || new File(project, "settings.gradle.kts").isFile()
            || new File(project, "build.gradle").isFile() || new File(project, "build.gradle.kts").isFile();
    }

    private GradleRuntimeManager runtimeManager() {
        if (runtimeManager == null) {
            BuildToolCatalog catalog = null;
            try { catalog = BuildToolCatalog.readVerified(context.getAssets().open("build-tools/catalog.json"), BuildToolCatalog.BUNDLED_CATALOG_SHA256); }
            catch (Exception ignored) { /* no trusted catalog: detection still works, automatic install is disabled */ }
            String javaHome = System.getenv("JAVA_HOME");
            runtimeManager = new GradleRuntimeManager(context.getFilesDir(), catalog, java.util.Arrays.asList(Build.SUPPORTED_ABIS),
                javaHome == null ? null : new File(javaHome), context.getAssets());
        }
        return runtimeManager;
    }

    private static File findApk(File project) {
        List<File> apks = new ArrayList<>();
        collectApks(new File(project, "app/build/outputs/apk"), apks);
        if (apks.isEmpty()) collectApks(new File(project, "build/outputs/apk"), apks);
        if (apks.isEmpty()) return null;
        Collections.sort(apks, new Comparator<File>() {
            @Override public int compare(File left, File right) { return Long.compare(right.lastModified(), left.lastModified()); }
        });
        for (File apk : apks) if (apk.isFile() && apk.length() > 0) return apk;
        return null;
    }
    private static void collectApks(File directory, List<File> output) {
        File[] children = directory.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (child.isDirectory()) collectApks(child, output);
            else if (child.isFile() && child.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".apk")) output.add(child);
        }
    }
    private static boolean copy(File source, File target) {
        try (java.io.InputStream in = new java.io.FileInputStream(source); java.io.OutputStream out = new java.io.FileOutputStream(target)) {
            byte[] buffer = new byte[32768]; int count;
            while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
            out.flush(); return target.isFile() && target.length() == source.length() && target.length() > 0;
        } catch (Exception ignored) { return false; }
    }
    private static BuildResult failed(String message) { return new BuildResult(BuildResult.Status.FAILED, null, message); }
    @Override public void cancel() { cancelled = true; GradleRuntimeManager manager = runtimeManager; if (manager != null) manager.cancel(); Process process = activeProcess; if (process != null) process.destroy(); }
}
