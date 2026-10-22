package com.example.studiomobile.build.gradle;

import android.content.res.AssetManager;
import com.example.studiomobile.build.BuildExecutableResolver;
import com.example.studiomobile.build.BuildLogger;
import com.example.studiomobile.build.NativeToolSelfTest;
import com.example.studiomobile.build.dependency.BuildToolCatalog;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Resolves everything Gradle needs before it starts: a Gradle distribution matching the project, a
 * compatible JDK, a persistent dependency cache, the Android SDK and an Android-runnable AAPT2.
 * Detection comes first; installation is attempted only for components in the verified catalog and
 * never when the build is offline. Every failure returns an actionable message instead of a guess.
 */
public final class GradleRuntimeManager {
    public static final class Resolution {
        public final GradleRuntime runtime; public final String error;
        private Resolution(GradleRuntime runtime, String error) { this.runtime = runtime; this.error = error; }
        public boolean isUsable() { return runtime != null; }
    }
    private final File filesDirectory;
    private final GradleRuntimeLocator locator;
    private final GradleCacheManager cacheManager;
    private final GradleRuntimeProvisioner provisioner;
    private final File externalJavaHome;
    private final GradleCompatibilityMatrix matrix;

    public GradleRuntimeManager(File filesDirectory, BuildToolCatalog catalog, List<String> supportedAbis, File externalJavaHome) {
        this(filesDirectory, catalog, supportedAbis, externalJavaHome, null);
    }

    public GradleRuntimeManager(File filesDirectory, BuildToolCatalog catalog, List<String> supportedAbis, File externalJavaHome, AssetManager assets) {
        this.filesDirectory = filesDirectory; this.locator = new GradleRuntimeLocator(filesDirectory);
        this.cacheManager = new GradleCacheManager(filesDirectory);
        this.provisioner = new GradleRuntimeProvisioner(filesDirectory, catalog, supportedAbis);
        if (assets != null) this.provisioner.setAssets(assets);
        this.externalJavaHome = externalJavaHome;
        this.matrix = loadMatrix(assets);
    }

    /** Rules come from assets/build-tools/gradle-compat.json; without that file nothing is cross-checked (never guessed). */
    private static GradleCompatibilityMatrix loadMatrix(AssetManager assets) {
        if (assets == null) return GradleCompatibilityMatrix.empty();
        try (java.io.InputStream input = assets.open("build-tools/gradle-compat.json")) {
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int count;
            while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
            return GradleCompatibilityMatrix.parse(new String(bytes.toByteArray(), java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception exception) { return GradleCompatibilityMatrix.empty(); }
    }
    public GradleCacheManager getCacheManager() { return cacheManager; }
    public void cancel() { provisioner.cancel(); }

    public Resolution resolve(File project, boolean offline, BuildLogger logger) {
        GradleProjectRequirements need = GradleProjectRequirements.read(project, matrix);
        logger.onInfo("Project requirements: Gradle " + (need.getRequiredGradleVersion() == null ? "unspecified" : need.getRequiredGradleVersion())
            + ", AGP " + (need.getAgpVersion() == null ? "unknown" : need.getAgpVersion()) + ", JDK " + need.getWantedJdk() + "+" + (need.getProjectJavaLevel() > 0 ? " (project Java " + need.getProjectJavaLevel() + ")" : "") + ".");
        if (need.getAgpVersion() != null && !need.isAgpKnown()) logger.onInfo("Android Gradle plugin " + need.getAgpVersion() + " is not in the app's compatibility table; Gradle/JDK versions were not cross-checked.");
        if (!cacheManager.prepare(logger)) return fail("Could not prepare the Gradle user home directory.");

        // 1+2. Gradle and JDK are chosen TOGETHER from what the project asks for (wrapper, AGP window, Java level), see GradleToolchainPlanner.
        File wrapperScript = new File(project, "gradlew"), wrapperJar = new File(project, "gradle/wrapper/gradle-wrapper.jar");
        boolean wrapperUsable = wrapperScript.isFile() && wrapperJar.isFile();
        Map<GradleVersion, String> catalogGradle = offline ? new java.util.HashMap<GradleVersion, String>() : provisioner.gradleComponentIds();
        Set<GradleVersion> gradleAvailable = new TreeSet<>(catalogGradle.keySet());
        for (GradleRuntimeLocator.GradleInstall candidate : locator.findGradleInstalls()) if (candidate.version != null) gradleAvailable.add(candidate.version);
        Set<Integer> jdkAvailable = new TreeSet<>();
        if (!offline) jdkAvailable.addAll(provisioner.jdkComponentMajors());
        for (GradleRuntimeLocator.JdkInstall candidate : locator.findJdkInstalls(externalJavaHome)) jdkAvailable.add(candidate.major);
        GradleToolchainPlanner.Plan plan = planWithLaunchers(project, need, gradleAvailable, jdkAvailable, wrapperUsable, logger);
        if (!plan.isUsable()) return fail(plan.error);
        for (String note : plan.notes) logger.onInfo(note);
        for (String warning : plan.warnings) logger.onWarning(warning);
        if (need.getAgpVersion() != null && !need.isAgpKnown()) logger.onInfo("Android Gradle plugin " + need.getAgpVersion() + " is not in the compatibility data (gradle-compat.json); only the rules that exist were applied.");

        // Gradle distribution
        File gradleExecutable = null; GradleVersion gradleVersion = null; boolean wrapper = false; File bootstrapJar = null; String mainClass = null;
        GradleRuntimeLocator.GradleInstall install = installedGradle(plan.gradle);
        if (install == null && !plan.viaWrapper) {
            String id = catalogGradle.get(plan.gradle);
            if (id != null) { File home = provisioner.install(id, new File(filesDirectory, "gradle-runtime/" + id), "bin/gradle", logger); if (home != null) install = installedGradle(plan.gradle); }
        }
        if (install != null) {
            gradleExecutable = install.executable; gradleVersion = install.version;
            bootstrapJar = GradleRuntimeLocator.findLauncherJar(install.home); mainClass = "org.gradle.launcher.GradleMain";
        } else if (wrapperUsable) {
            gradleExecutable = wrapperScript; wrapper = true; bootstrapJar = wrapperJar; mainClass = "org.gradle.wrapper.GradleWrapperMain";
            logger.onInfo("Gradle " + plan.gradle + " is taken from the project's own wrapper, which downloads it into the Gradle user home.");
        }
        if (gradleExecutable == null) return fail("Gradle " + plan.gradle + " could not be installed"
            + (offline ? "; automatic download is disabled in offline mode." : " from the verified build catalog, and the project has no usable wrapper."));

        // JDK: the planner already checked it against the AGP minimum, the project's Java level and the Gradle maximum
        GradleRuntimeLocator.JdkInstall jdk = installedJdk(plan.jdk);
        if (jdk == null && !offline) {
            File home = provisioner.install("jdk-" + plan.jdk, new File(filesDirectory, "jdk/jdk-" + plan.jdk), "lib/server/libjvm.so", logger);
            if (home != null) jdk = installedJdk(plan.jdk);
        }
        if (jdk == null) return fail("JDK " + plan.jdk + " is needed for this project but could not be installed"
            + (offline ? " (offline mode)." : " from the verified build catalog.") + " It would live under " + new File(filesDirectory, "jdk") + "/jdk-" + plan.jdk + ".");

        // Launchers always belong to the JDK that was chosen (lib<tool><major>exe.so), never to some other major.
        File javaLauncher = launcherFor("java", jdk.major);
        if (javaLauncher == null) logger.onWarning("No packaged java launcher (" + JdkLauncherSet.fileName("java", jdk.major) + ") in the native library directory; Gradle will be started through its shell script, which Android 10+ refuses to run from app storage.");

        // 3. Android SDK and AAPT2
        if (javaLauncher != null) {
            // bin/java inside the JDK tree points at the launcher so that Gradle can start the child JVM it forks.
            String linkProblem = JdkLauncherLink.ensure(jdk.home, javaLauncher);
            if (linkProblem != null) logger.onWarning(linkProblem);
            // AGP (JdkImageTransform, compileSdk 34+) starts javac, jmod and jlink from <jdk>/bin; Gradle may start jar. Like bin/java, none of
            // them can run from app storage (error 13), so each one is linked to a copy packaged in the native library directory.
            for (String tool : new String[] {"javac", "jmod", "jar", "jlink"}) {
                File toolLauncher = launcherFor(tool, jdk.major);   // same lookup (and same executable check) as the java launcher
                if (toolLauncher != null) {
                    String toolProblem = JdkLauncherLink.ensureTool(jdk.home, tool, toolLauncher);
                    if (toolProblem != null) logger.onWarning(toolProblem);
                } else logger.onWarning("No packaged " + tool + " launcher (" + JdkLauncherSet.fileName(tool, jdk.major) + ") for JDK " + jdk.major + " in the native library directory; projects with compileSdk 34 or higher can fail when the build starts " + tool + ".");
            }
            String selfTest = NativeToolSelfTest.checkJava(javaLauncher, jdk.home);
            if (selfTest != null) return fail(selfTest);
            logger.onInfo("java launcher self-test passed (" + javaLauncher.getName() + ").");
        }
        File sdk = new File(filesDirectory, "android-sdk");
        // Studio Mobile keeps android.jar under build-tools/platforms; the Gradle build expects an SDK folder, so present it as one.
        File androidSdk = AndroidSdkLayout.ensure(filesDirectory, project, logger);
        if (androidSdk == null) {
            logger.onInfo("No platform jar found under " + sdk + "/platforms, so sdk.dir is not set for this build.");
            logger.onWarning("The Android SDK files are not installed on this device.");
        }
        File aapt2 = BuildExecutableResolver.resolve(new File(filesDirectory, "build-tools"), "aapt2");
        if (aapt2 == null) {
            logger.onInfo("No native resource tool for this device was found; the default one from Maven targets desktop systems and is unlikely to run here.");
            logger.onWarning("A required build tool is not installed on this device.");
        } else aapt2 = Aapt2Link.ensure(filesDirectory, aapt2, logger);   // AGP only accepts a file named exactly "aapt2"
        if (androidSdk != null) ensureLocalProperties(project, androidSdk, logger);

        // 4. Dependency cache readiness
        GradleDependencyPreflight.Report report = new GradleDependencyPreflight(cacheManager.getModulesDirectory()).inspect(project);
        logger.onInfo("Dependency preflight: " + report.declared.size() + " declared, " + report.missing.size() + " not in cache, " + report.unverifiable.size() + " unverifiable (dynamic versions).");
        if (offline && !report.isOfflineReady()) {
            StringBuilder message = new StringBuilder("Offline build is not possible; these declared artifacts are not in the Gradle cache:");
            for (int index = 0; index < Math.min(20, report.missing.size()); index++) message.append("\n  - ").append(report.missing.get(index));
            if (report.missing.size() > 20) message.append("\n  ... and ").append(report.missing.size() - 20).append(" more");
            message.append("\nThe check covers direct declarations only; transitive dependencies are resolved by Gradle. Run one online build to populate the cache.");
            return fail(message.toString());
        }
        if (offline && !cacheManager.hasCachedDependencies()) return fail("Offline build requested but the Gradle dependency cache is empty. Run one online build first.");
        logger.onInfo("Using JDK " + jdk.major + " at " + jdk.home + (wrapper ? " with the project wrapper." : " with Gradle " + gradleVersion + "."));
        return new Resolution(new GradleRuntime(gradleExecutable, gradleVersion, jdk.home, jdk.major, cacheManager.getGradleUserHome(), androidSdk, aapt2, wrapper, javaLauncher, bootstrapJar, mainClass), null);
    }

    /**
     * Plans Gradle and JDK exactly like GradleToolchainPlanner always did (the project decides: AGP, Java level, Gradle window; the lowest JDK
     * that satisfies them), but only from JDK majors whose launchers are packaged in the APK. A JDK without its lib&lt;tool&gt;&lt;major&gt;exe.so can
     * be installed and still never start on Android 10+, so choosing it would only move the failure to later. Nothing is forced to one major:
     * with launchers for 17, 21 and 25 a project that needs 17 gets 17, one that needs 25 gets 25.
     *
     * <p>Order: (1) a JDK with java + javac + jmod + jlink when compileSdk is 34+ or unreadable (AGP builds a JDK image with them),
     * (2) a JDK with at least java, (3) the unrestricted plan, so devices or builds without any packaged launcher behave as before and the
     * existing warnings explain what is missing.
     */
    private GradleToolchainPlanner.Plan planWithLaunchers(File project, GradleProjectRequirements need, Set<GradleVersion> gradleAvailable,
                                                          Set<Integer> jdkAvailable, boolean wrapperUsable, BuildLogger logger) {
        GradleToolchainPlanner.Plan open = GradleToolchainPlanner.plan(need, matrix, gradleAvailable, jdkAvailable, wrapperUsable);
        JdkLauncherSet launchers = JdkLauncherSet.scan(BuildExecutableResolver.registeredNativeLibraryDirectory());
        if (launchers.isEmpty()) return open;
        logger.onInfo("Packaged JDK launchers: " + launchers.describe() + ".");
        int compileSdk = AndroidSdkLayout.readCompileSdk(project);
        boolean needsImageTools = compileSdk <= 0 || compileSdk >= JdkLauncherSet.IMAGE_MIN_COMPILE_SDK;   // a variable compileSdk is treated as modern
        GradleToolchainPlanner.Plan plan = null;
        if (needsImageTools) plan = GradleToolchainPlanner.plan(need, matrix, gradleAvailable, JdkLauncherSet.restrict(jdkAvailable, launchers.majorsForImageBuilds()), wrapperUsable);
        if (plan == null || !plan.isUsable()) plan = GradleToolchainPlanner.plan(need, matrix, gradleAvailable, JdkLauncherSet.restrict(jdkAvailable, launchers.majorsWithJava()), wrapperUsable);
        if (!plan.isUsable()) return open;   // no JDK with launchers fits the project; keep the old behavior and its warnings
        if (open.isUsable() && (open.jdk != plan.jdk || !open.gradle.equals(plan.gradle)))
            plan.notes.add("JDK " + open.jdk + " with Gradle " + open.gradle + " would fit the project, but the APK has no launchers for JDK " + open.jdk
                + "; using JDK " + plan.jdk + " with Gradle " + plan.gradle + ", which has them.");
        return plan;
    }

    /** Native launcher packaged for exactly this JDK major (java 17 -> libjava17exe.so). Null when that major has none; no other major is substituted. */
    private static File launcherFor(String tool, int major) { return BuildExecutableResolver.resolve(null, tool + major + "exe"); }

    /** The installed Gradle with exactly this version (the planner decided which version; no "closest" guessing here). */
    private GradleRuntimeLocator.GradleInstall installedGradle(GradleVersion exact) {
        for (GradleRuntimeLocator.GradleInstall candidate : locator.findGradleInstalls()) if (candidate.version != null && candidate.version.compareTo(exact) == 0) return candidate;
        return null;
    }
    private GradleRuntimeLocator.JdkInstall installedJdk(int major) {
        for (GradleRuntimeLocator.JdkInstall candidate : locator.findJdkInstalls(externalJavaHome)) if (candidate.major == major) return candidate;
        return null;
    }

    /** Creates local.properties only when absent so a user's own sdk.dir is never overwritten. */
    private static void ensureLocalProperties(File project, File sdk, BuildLogger logger) {
        File file = new File(project, "local.properties");
        if (file.exists()) return;
        try (FileWriter writer = new FileWriter(file)) { writer.write("sdk.dir=" + sdk.getAbsolutePath().replace("\\", "\\\\") + "\n"); logger.onInfo("Created local.properties with sdk.dir in the private workspace copy."); }
        catch (IOException exception) { logger.onWarning("Could not write local.properties: " + exception.getMessage()); }
    }
    private static Resolution fail(String message) { return new Resolution(null, message); }
}
