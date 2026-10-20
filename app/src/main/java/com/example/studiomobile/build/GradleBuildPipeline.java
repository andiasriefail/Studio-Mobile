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
        GradleRuntimeManager.Resolution resolution = runtimeManager().resolve(project, configuration.isOfflineMode(), logger);
        if (cancelled) return new BuildResult(BuildResult.Status.CANCELLED, null, "Gradle build cancelled.");
        if (!resolution.isUsable()) return failed(resolution.error);
        GradleRuntime runtime = resolution.runtime;
        List<String> command = runtime.buildCommand(configuration.isOfflineMode(), "assembleDebug");
        logger.onInfo("Gradle project detected. Using the project's own Android Gradle Plugin and dependency declarations.");
        logger.onInfo(runtime.runsThroughJava()
            ? "Gradle is started through the packaged java launcher (" + runtime.getJavaLauncher().getAbsolutePath() + ") with JDK " + runtime.getJavaMajor() + " at " + runtime.getJavaHome()
            : "Gradle executable (shell script): " + runtime.getGradleExecutable().getAbsolutePath());
        logger.onInfo("Gradle user home (dependency cache): " + runtime.getGradleUserHome().getAbsolutePath());
        logger.onInfo(configuration.isOfflineMode()
            ? "Dependency resolution: offline; all required plugins and artifacts must already be cached."
            : "Dependency resolution: online; Gradle may download required plugins and libraries into the persistent cache.");
        try {
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
        } finally { activeProcess = null; }
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
