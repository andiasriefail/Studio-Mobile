package com.example.studiomobile.build;

import android.content.Context;
import android.os.Build;
import android.os.Looper;
import com.example.studiomobile.build.dependency.BuildToolCatalog;
import com.example.studiomobile.build.gradle.AndroidSdkLayout;
import com.example.studiomobile.build.gradle.GradleRuntime;
import com.example.studiomobile.build.gradle.GradleRuntimeManager;
import com.example.studiomobile.build.network.ConnectionWatchdog;
import com.example.studiomobile.build.network.NetworkMonitor;
import com.example.studiomobile.build.process.BuildProcessRunner;
import com.example.studiomobile.build.process.ChildProcessReaper;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Runs an Android project's own Gradle build using a locally installed Gradle distribution. */
public final class GradleBuildPipeline implements BuildPipeline {
    /** Gradle task that produces the bundle of an Export AAB build. Release, because Google Play refuses debuggable bundles. */
    static final String BUNDLE_TASK = "bundleRelease";

    private final Context context;
    private volatile boolean cancelled;
    private volatile Process activeProcess;
    private volatile GradleRuntimeManager runtimeManager;
    /** Set by the connection watchdog when a working connection was lost during an online build. */
    private volatile boolean connectionLost;
    /** The child processes that existed before Gradle started; everything new after it belongs to this build. */
    private volatile ChildProcessReaper.Snapshot baseline;

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
        if (!resolution.isUsable()) {
            logger.onInfo("[internal] " + resolution.error);   // the exact cause stays in the full log and the error report
            return failed(plainReason(resolution.error));
        }
        GradleRuntime runtime = resolution.runtime;
        List<String> command = runtime.buildCommand(configuration.isOfflineMode(), "assembleDebug");
        // Export AAB: the debug APK is still built (it is the file the Build screen reports) and the bundle comes from the same Gradle run.
        // The bundle is the RELEASE variant: bundleDebug is debuggable and Google Play refuses debuggable bundles.
        if (configuration.isBundleRequested()) command.add(BUNDLE_TASK);
        final boolean projectKey = addInjectedSigning(command, logger);
        if (configuration.isBundleRequested()) {
            logger.onInfo("Export AAB: building the release bundle (" + BUNDLE_TASK + "). The release build type of your Gradle files is used (minify, resources, signing).");
            if (!projectKey) logger.onWarning("No key is set for this project. The bundle is signed only if your build.gradle defines a release signingConfig; "
                + "otherwise it is left unsigned and Google Play will refuse it. Open Keystore and choose your key.");
        }
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
        NetworkMonitor monitor = null;
        ConnectionWatchdog watchdog = null;
        try {
            logger.onInfo("[step] Starting the build engine");
            watcher.start();
            connectionLost = false;
            ChildProcessReaper.killStaleGradleDaemons();   // a daemon left by an earlier cancelled build must not compete with this one
            baseline = ChildProcessReaper.snapshot();
            if (!configuration.isOfflineMode()) {
                monitor = new NetworkMonitor(context);
                monitor.start();
                watchdog = new ConnectionWatchdog(monitor, new Runnable() { @Override public void run() { connectionLost = true; stopBuildProcesses(); } });
                watchdog.start();
            }
            BuildProcessRunner runner = new BuildProcessRunner(new BuildProcessRunner.ProcessObserver() {
                @Override public void onStarted(Process process) { activeProcess = process; }
                @Override public void onFinished(Process process) { if (activeProcess == process) activeProcess = null; }
            });
            final StringBuilder captured = new StringBuilder();
            BuildLogger capturing = new BuildLogger() {
                private void keep(String message) { synchronized (captured) { if (message != null && captured.length() < 2_000_000) captured.append(message).append('\n'); } }
                @Override public void onInfo(String message) { keep(message); screenLog.onInfo(message); }
                @Override public void onWarning(String message) { keep(message); screenLog.onWarning(message); }
                @Override public void onError(String message, Throwable cause) { keep(message); screenLog.onError(message, cause); }
            };
            BuildProcessRunner.Result result = runner.run(command, project, 30 * 60, capturing, runtime.buildEnvironment());
            if (connectionLost) return connectionTimeout();
            if (!cancelled && !result.isSuccessful() && !result.isTimedOut()) {
                String firstOutput; synchronized (captured) { firstOutput = captured.toString(); }
                // The project asked for an Android platform or build-tools folder that is not installed. A newer installed platform can
                // stand in for it, so it is provided from what the app already has and the build is started once more.
                // AGP words it either as "Failed to install the following Android SDK packages" or, when the SDK folder simply has no
                // matching platform, "Failed to find target with hash string 'android-36'". Both are answered the same way.
                if ((firstOutput.contains("Failed to install the following Android SDK packages") || firstOutput.contains("Failed to find target with hash string")
                            || firstOutput.contains("Failed to find Build Tools revision")
                            || firstOutput.contains("is corrupted. Remove and install again") || (firstOutput.contains("Build-tool ") && firstOutput.contains(" is missing ")))
                        && AndroidSdkLayout.provideMissing(context.getFilesDir(), firstOutput, new ScreenLogger(logger)) > 0) {
                    ChildProcessReaper.killNew(baseline);   // nothing of the failed run may still be alive when the second run starts
                    logger.onInfo("[step] Preparing the Android SDK");
                    logger.onWarning("The project asked for Android SDK packages that are not installed. They were provided from the installed platform; building again.");
                    logger.onInfo("[step] Starting the build engine");
                    result = runner.run(command, project, 30 * 60, logger, runtime.buildEnvironment());
                }
            }
            if (connectionLost) return connectionTimeout();
            if (cancelled) return new BuildResult(BuildResult.Status.CANCELLED, null, "Gradle build cancelled.");
            if (!result.isSuccessful() && !result.isTimedOut()) logger.onWarning("If the log shows checksum or 'Could not read' cache errors, clear the Gradle dependency cache and rebuild online.");
            if (!result.isSuccessful()) return failed(result.isTimedOut()
                ? "Gradle build timed out after 30 minutes. See Build Logs for details."
                : "Gradle build failed (exit code " + result.getExitCode() + "). See Build Logs for the Gradle diagnostic.");
            File apk = findApk(project);
            if (apk == null) return failed("Gradle reported success, but no APK was found under app/build/outputs/apk or build/outputs/apk.");
            File destination = new File(output, "gradle-" + System.currentTimeMillis() + "-" + apk.getName());
            if (!copy(apk, destination)) return failed("Gradle built an APK but Studio Mobile could not copy it to the APK output directory.");
            if (configuration.isBundleRequested()) {
                File aab = findBundle(project);
                File bundleTarget = new File(output, BuildConfiguration.BUNDLE_FILE_NAME);
                if (aab == null) return failed("Gradle finished, but no .aab was found under <module>/build/outputs/bundle/release. "
                    + "Check that the app module applies the com.android.application plugin.");
                if (!copy(aab, bundleTarget)) return failed("Gradle built an AAB but Studio Mobile could not copy it to the output directory.");
                logger.onInfo("AAB artifact found: " + aab.getAbsolutePath());
            }
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
        } finally {
            if (watchdog != null) watchdog.finish();
            if (monitor != null) monitor.stop();
            if (baseline != null) ChildProcessReaper.killNew(baseline);   // no Gradle JVM is left behind, whatever way the build ended
            activeProcess = null;
            watcher.finish();
        }
    }

    /** Stops the Gradle launcher and every process it started (the forked daemon), not only the launcher. */
    private void stopBuildProcesses() {
        ChildProcessReaper.Snapshot before = baseline;
        if (before != null) ChildProcessReaper.killNew(before);
        Process process = activeProcess;
        if (process != null) { process.destroy(); if (process.isAlive()) process.destroyForcibly(); }
    }

    private static BuildResult connectionTimeout() {
        return failed(NetworkMonitor.CONNECTION_TIMEOUT_LABEL + " The internet connection was lost while the build was running. The build was stopped and its "
            + "processes were cleaned up; libraries that were already downloaded are kept. Press Retry when you are back online.");
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
        @Override public void onWarning(String message) {
            // Notes about the app's own tool catalog are for the log file; the screen gets the plain-words result instead.
            if (message != null && message.contains("catalog entry")) delegate.onInfo("[internal] " + message); else delegate.onWarning(message);
        }
        @Override public void onError(String message, Throwable cause) { delegate.onError(message, cause); }
        /** Components of the app's own catalog (Gradle, JDK, NDK, Python) are deliberately not named on the screen. */
        private static String friendly(String message) {
            if (message.startsWith("Project requirements")) return "[step] Reading the project requirements";
            if (message.contains(" MiB downloaded")) return "[progress] Downloading the build tools · " + message.substring(message.lastIndexOf(": ") + 2).replace(" MiB downloaded", " MB");
            if (message.startsWith("Downloading ")) return "[step] Downloading the build tools";
            if (message.startsWith("Unpacking bundled")) return "[step] Installing the build tools";
            if (message.startsWith("Ready:")) return "[step] Build tools ready";
            if (message.startsWith("java launcher self-test passed")) return "[step] Verifying the build tools";
            if (message.startsWith("Dependency preflight")) return "[step] Checking cached libraries";
            return null;
        }
    }

    /**
     * Watches the Gradle dependency cache while Gradle runs and reports real download activity (Gradle itself prints nothing for it).
     * Every library that appears in the cache gets its own timed line with its name (group:artifact:version).
     */
    private static final class DownloadWatcher extends Thread {
        private static final int MAX_LINES_PER_CHECK = 6;
        private final File modules; private final File coordinates; private final BuildLogger logger; private volatile boolean stopped;
        private final java.util.Set<String> seen = new java.util.HashSet<>();
        DownloadWatcher(File gradleUserHome, BuildLogger logger) {
            super("GradleDownloadWatcher"); setDaemon(true);
            this.modules = new File(gradleUserHome, "caches/modules-2"); this.coordinates = new File(modules, "files-2.1"); this.logger = logger;
        }
        void finish() { stopped = true; interrupt(); }
        @Override public void run() {
            long base = size(modules), last = base, reported = base;
            scan(new java.util.ArrayList<String>());   // what is already cached is not a download
            String latest = null;
            while (!stopped) {
                try { Thread.sleep(2000); } catch (InterruptedException interrupted) { return; }
                java.util.List<String> fresh = new java.util.ArrayList<>();
                scan(fresh);
                for (int i = 0; i < fresh.size() && i < MAX_LINES_PER_CHECK; i++) logger.onInfo("[step] " + describe(fresh.get(i)));
                if (fresh.size() > MAX_LINES_PER_CHECK) logger.onInfo("[step] Downloaded " + (fresh.size() - MAX_LINES_PER_CHECK) + " more libraries");
                if (!fresh.isEmpty()) latest = fresh.get(fresh.size() - 1);
                long now = size(modules);
                if (now > last && now - reported >= 256 * 1024) {
                    reported = now;
                    logger.onInfo("[progress] Downloading libraries · " + megabytes(now - base) + " so far" + (latest == null ? "" : " · last: " + latest));
                }
                last = now;
            }
        }
        /** "Downloaded plugin x" for Gradle plugins (including the Android Gradle Plugin), "Downloaded x" for every other library. */
        private static String describe(String coordinate) {
            String[] part = coordinate.split(":");
            boolean plugin = part.length == 3 && (part[1].endsWith(".gradle.plugin") || (part[0].equals("com.android.tools.build") && part[1].equals("gradle")));
            return (plugin ? "Downloaded plugin " : "Downloaded ") + coordinate;
        }

        /** Adds every group:artifact:version folder that was not seen before to the list. */
        private void scan(java.util.List<String> fresh) {
            File[] groups = coordinates.listFiles();
            if (groups == null) return;
            for (File group : groups) {
                File[] artifacts = group.listFiles();
                if (artifacts == null) continue;
                for (File artifact : artifacts) {
                    File[] versions = artifact.listFiles();
                    if (versions == null) continue;
                    for (File version : versions) {
                        String key = group.getName() + ":" + artifact.getName() + ":" + version.getName();
                        if (seen.add(key)) fresh.add(key);
                    }
                }
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
    /**
     * The key chosen on the Keystore page is written to build-tools/custom-signing.properties before every build. The in-process engine
     * reads it itself; Gradle only knows a key it is told about, so it gets the same key through the Android Gradle plugin's injected
     * signing properties (the ones Android Studio's "Generate Signed Bundle" uses). Returns true when a project key was passed.
     */
    private boolean addInjectedSigning(List<String> command, BuildLogger logger) {
        File config = new File(new File(context.getFilesDir(), "build-tools"), "custom-signing.properties");
        if (!config.isFile()) return false;
        java.util.Properties properties = new java.util.Properties();
        try (java.io.InputStream in = new java.io.FileInputStream(config)) { properties.load(in); }
        catch (java.io.IOException failure) { logger.onWarning("The key of this project could not be read: " + failure.getMessage()); return false; }
        String store = properties.getProperty("keystore", ""), alias = properties.getProperty("alias", "");
        String storePassword = properties.getProperty("storePassword", ""), keyPassword = properties.getProperty("keyPassword", storePassword);
        if (!new File(store).isFile() || alias.isEmpty()) { logger.onWarning("The key chosen for this project is incomplete or its file is missing; the Gradle build uses its own signing."); return false; }
        command.add("-Pandroid.injected.signing.store.file=" + store);
        command.add("-Pandroid.injected.signing.store.password=" + storePassword);
        command.add("-Pandroid.injected.signing.key.alias=" + alias);
        command.add("-Pandroid.injected.signing.key.password=" + keyPassword);
        command.add("-Pandroid.injected.signing.store.type=pkcs12");
        logger.onInfo("Signing with the key chosen for this project.");
        return true;
    }

    /**
     * The bundle Gradle just built, from any module: {@code <module>/build/outputs/bundle/<variant>/*.aab}. The release bundle wins over
     * any other variant; among equals the newest file wins. The build runs on a fresh copy of the project, so nothing old can be found.
     */
    private static File findBundle(File project) {
        List<File> found = new ArrayList<>();
        collectBundleOutputs(project, 0, found);
        File best = null; boolean bestIsRelease = false;
        for (File file : found) {
            if (!file.isFile() || file.length() == 0) continue;
            boolean release = file.getAbsolutePath().replace('\\', '/').contains("/bundle/release/");
            if (best == null || (release && !bestIsRelease) || (release == bestIsRelease && file.lastModified() > best.lastModified())) { best = file; bestIsRelease = release; }
        }
        return best;
    }

    /** Looks for build/outputs/bundle folders in the project root and its modules (a few levels deep; source and cache folders are skipped). */
    private static void collectBundleOutputs(File directory, int depth, List<File> output) {
        File[] children = directory.listFiles();
        if (children == null || depth > 4) return;
        for (File child : children) {
            if (!child.isDirectory()) continue;
            String name = child.getName();
            if (name.equals("build")) { collectByExtension(new File(child, "outputs/bundle"), ".aab", output); continue; }
            if (name.equals("src") || name.equals("node_modules") || name.startsWith(".")) continue;
            collectBundleOutputs(child, depth + 1, output);
        }
    }

    private static void collectByExtension(File directory, String extension, List<File> output) {
        File[] children = directory.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (child.isDirectory()) collectByExtension(child, extension, output);
            else if (child.isFile() && child.getName().toLowerCase(java.util.Locale.ROOT).endsWith(extension)) output.add(child);
        }
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
    /** Screen wording for a toolchain problem: no tool names, versions of the app's catalog or storage paths. */
    private static String plainReason(String error) {
        if (error == null) return "The build tools could not be prepared.";
        if (error.startsWith("No usable Gradle distribution")) return "This project needs a build engine version that is not available on this device yet.";
        if (error.startsWith("No JDK between")) return "This device does not have a Java runtime that fits this project yet.";
        if (error.contains("/data/user/") || error.contains("catalog")) return "The build tools could not be prepared on this device.";
        return error;
    }
    private static BuildResult failed(String message) { return new BuildResult(BuildResult.Status.FAILED, null, message); }
    @Override public void cancel() { cancelled = true; GradleRuntimeManager manager = runtimeManager; if (manager != null) manager.cancel(); stopBuildProcesses(); }
}
