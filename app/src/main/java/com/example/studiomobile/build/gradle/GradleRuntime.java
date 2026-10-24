package com.example.studiomobile.build.gradle;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A fully resolved, runnable Gradle environment: executable, JDK, Gradle user home and optional SDK/AAPT2. */
public final class GradleRuntime {
    private final File gradleExecutable;
    private final GradleVersion gradleVersion;   // null for a project wrapper whose version is read from properties
    private final File javaHome;
    private final int javaMajor;
    private final File gradleUserHome;
    private final File androidSdk;               // may be null
    private final File aapt2Override;            // may be null
    private final boolean projectWrapper;
    private final File javaLauncher;             // lib java<major>exe .so of the chosen JDK in the native library directory; null when not packaged
    private final File bootstrapJar;             // gradle-launcher-<v>.jar or gradle-wrapper.jar; null when the script must be used
    private final String mainClass;

    GradleRuntime(File gradleExecutable, GradleVersion gradleVersion, File javaHome, int javaMajor, File gradleUserHome,
                  File androidSdk, File aapt2Override, boolean projectWrapper, File javaLauncher, File bootstrapJar, String mainClass) {
        this.gradleExecutable = gradleExecutable; this.gradleVersion = gradleVersion; this.javaHome = javaHome; this.javaMajor = javaMajor;
        this.gradleUserHome = gradleUserHome; this.androidSdk = androidSdk; this.aapt2Override = aapt2Override; this.projectWrapper = projectWrapper;
        this.javaLauncher = javaLauncher; this.bootstrapJar = bootstrapJar; this.mainClass = mainClass;
    }
    public File getJavaLauncher() { return javaLauncher; }

    /** True when Gradle is started through the packaged java launcher instead of the bin/gradle (or gradlew) shell script. */
    public boolean runsThroughJava() { return javaLauncher != null && bootstrapJar != null && mainClass != null; }

    /**
     * The command that starts Gradle. Android 10+ refuses to execute scripts and binaries from app-private storage, so the
     * script is bypassed: the java launcher (shipped as a native library) starts the same main class the script would.
     */
    public List<String> buildCommand(boolean offline, String task) {
        List<String> command = new ArrayList<>();
        if (runsThroughJava()) {
            command.add(javaLauncher.getAbsolutePath());
            command.add("-Xmx512m");
            command.add("-Dfile.encoding=UTF-8");
            command.add("-Dorg.gradle.appname=" + (projectWrapper ? "gradlew" : "gradle"));
            command.add("-classpath"); command.add(bootstrapJar.getAbsolutePath());
            command.add(mainClass);
        } else command.add(gradleExecutable.getAbsolutePath());
        command.addAll(buildArguments(offline));
        command.add(task);
        return command;
    }
    public File getGradleExecutable() { return gradleExecutable; }
    public GradleVersion getGradleVersion() { return gradleVersion; }
    public File getJavaHome() { return javaHome; }
    public int getJavaMajor() { return javaMajor; }
    public File getGradleUserHome() { return gradleUserHome; }

    /**
     * Writable replacement for ~/.android. On Android the JVM reports user.home as "/data" (not writable), so the Android Gradle
     * Plugin failed with AccessDeniedException: /data/.android while applying 'com.android.application'.
     */
    public File getAndroidUserHome() {
        File parent = gradleUserHome.getParentFile();
        return new File(parent != null ? parent : gradleUserHome, "android-home");
    }

    /** Creates the Android user home (debug keystore, prefs, analytics) before Gradle starts. */
    public boolean ensureAndroidUserHome() {
        File home = getAndroidUserHome();
        File temp = getTempDirectory();
        if (!temp.isDirectory()) temp.mkdirs();   // a failure here shows up as the build's own temp-file error
        return home.isDirectory() || home.mkdirs();
    }

    /**
     * Folder for java.io.tmpdir. The JDK's default, /tmp, does not exist or is not writable for an app, so any build step that creates a
     * temporary file (AGP's generateDebugGlobalSynthetics, D8, R8) fails with AccessDeniedException: /tmp/... .
     */
    public File getTempDirectory() {
        File parent = gradleUserHome.getParentFile();
        return new File(parent != null ? parent : gradleUserHome, "tmp");
    }
    public File getAndroidSdk() { return androidSdk; }
    public boolean isProjectWrapper() { return projectWrapper; }

    /** Environment variables for the Gradle process. Only values that actually exist are exported. */
    public Map<String, String> buildEnvironment() {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("JAVA_HOME", javaHome.getAbsolutePath());
        env.put("GRADLE_USER_HOME", gradleUserHome.getAbsolutePath());
        if (androidSdk != null) { env.put("ANDROID_HOME", androidSdk.getAbsolutePath()); env.put("ANDROID_SDK_ROOT", androidSdk.getAbsolutePath()); }
        File androidUserHome = getAndroidUserHome();
        env.put("ANDROID_USER_HOME", androidUserHome.getAbsolutePath());
        env.put("HOME", androidUserHome.getParentFile() != null ? androidUserHome.getParentFile().getAbsolutePath() : androidUserHome.getAbsolutePath());
        String tempOption = "-Djava.io.tmpdir=" + getTempDirectory().getAbsolutePath();
        env.put("TMPDIR", getTempDirectory().getAbsolutePath());
        env.put("GRADLE_OPTS", "-Dfile.encoding=UTF-8 -Duser.home=" + androidUserHome.getParentFile().getAbsolutePath() + " " + tempOption);
        if (javaLauncher != null) {
            // The launcher lives in the native library directory, so it cannot find the JDK relative to itself.
            env.put("NLAUNCHER_JAVA_HOME", javaHome.getAbsolutePath());
            String nativeDir = javaLauncher.getParentFile().getAbsolutePath();
            env.put("LD_LIBRARY_PATH", nativeDir + ":" + new File(javaHome, "lib/server").getAbsolutePath() + ":" + new File(javaHome, "lib").getAbsolutePath());
            // Android 10+ cannot exec <jdk>/lib/jspawnhelper from app-private storage, so the default POSIX_SPAWN launch mechanism
            // fails with exit code 127 for every child process (uname, the probe JVM, the forked Gradle daemon). FORK does not
            // need the helper. JAVA_TOOL_OPTIONS is inherited by every JVM Gradle starts, so the client, daemon and probes are covered.
            env.put("JAVA_TOOL_OPTIONS", "-Djdk.lang.Process.launchMechanism=FORK -Duser.home=" + androidUserHome.getParentFile().getAbsolutePath() + " " + tempOption);
        } else env.put("JAVA_TOOL_OPTIONS", tempOption);
        return Collections.unmodifiableMap(env);
    }

    /** Gradle command-line arguments that make dependency and toolchain behaviour explicit. */
    public List<String> buildArguments(boolean offline) {
        List<String> args = new ArrayList<>();
        args.add("--no-daemon");
        args.add("--stacktrace");
        args.add("--console=plain");   // one line per task and per configured project, so the Build screen can show progress
        args.add("-Duser.home=" + (getAndroidUserHome().getParentFile() != null ? getAndroidUserHome().getParentFile().getAbsolutePath() : getAndroidUserHome().getAbsolutePath()));
        args.add("--gradle-user-home"); args.add(gradleUserHome.getAbsolutePath());
        // Maps a jvmToolchain(N) request lower than this JDK to this JDK (see ToolchainInitScript); written by GradleRuntimeManager.
        File toolchainScript = new File(gradleUserHome, ToolchainInitScript.FILE_NAME);
        if (toolchainScript.isFile()) { args.add("-I"); args.add(toolchainScript.getAbsolutePath()); }
        args.add("-Dorg.gradle.java.home=" + javaHome.getAbsolutePath());
        // AGP normally downloads a Linux/x86-64 aapt2 from Maven, which cannot run on Android/ARM.
        if (aapt2Override != null) args.add("-Pandroid.aapt2FromMavenOverride=" + aapt2Override.getAbsolutePath());
        if (offline) args.add("--offline");
        return args;
    }
}
