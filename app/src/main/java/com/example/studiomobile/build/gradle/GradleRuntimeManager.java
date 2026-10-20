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
import java.util.List;

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

    public GradleRuntimeManager(File filesDirectory, BuildToolCatalog catalog, List<String> supportedAbis, File externalJavaHome) {
        this(filesDirectory, catalog, supportedAbis, externalJavaHome, null);
    }

    public GradleRuntimeManager(File filesDirectory, BuildToolCatalog catalog, List<String> supportedAbis, File externalJavaHome, AssetManager assets) {
        this.filesDirectory = filesDirectory; this.locator = new GradleRuntimeLocator(filesDirectory);
        this.cacheManager = new GradleCacheManager(filesDirectory);
        this.provisioner = new GradleRuntimeProvisioner(filesDirectory, catalog, supportedAbis);
        if (assets != null) this.provisioner.setAssets(assets);
        this.externalJavaHome = externalJavaHome;
    }
    public GradleCacheManager getCacheManager() { return cacheManager; }
    public void cancel() { provisioner.cancel(); }

    public Resolution resolve(File project, boolean offline, BuildLogger logger) {
        GradleProjectRequirements need = GradleProjectRequirements.read(project);
        logger.onInfo("Project requirements: Gradle " + (need.getRequiredGradleVersion() == null ? "unspecified" : need.getRequiredGradleVersion())
            + ", AGP " + (need.getAgpVersion() == null ? "unknown" : need.getAgpVersion()) + ", JDK " + need.getMinimumJdk() + "+.");
        if (need.getAgpVersion() != null && !need.isAgpKnown()) logger.onInfo("Android Gradle plugin " + need.getAgpVersion() + " is not in the app's compatibility table; Gradle/JDK versions were not cross-checked.");
        if (need.getWrapperGradleVersion() != null && need.getRequiredGradleVersion() != null && need.getWrapperGradleVersion().compareTo(need.getRequiredGradleVersion()) < 0)
            logger.onInfo("The wrapper requests Gradle " + need.getWrapperGradleVersion() + " but the Android Gradle plugin " + need.getAgpVersion() + " needs " + need.getRequiredGradleVersion() + "+; using the newer version.");
        if (!cacheManager.prepare(logger)) return fail("Could not prepare the Gradle user home directory.");

        // 1. Gradle distribution
        File gradleExecutable = null; GradleVersion gradleVersion = null; boolean wrapper = false; File bootstrapJar = null; String mainClass = null;
        File javaLauncher = BuildExecutableResolver.resolve(null, "javaexe");
        GradleRuntimeLocator.GradleInstall install = chooseGradle(need.getRequiredGradleVersion());
        if (install == null && !offline && need.getRequiredGradleVersion() != null) {
            String id = "gradle-" + need.getRequiredGradleVersion();
            File home = provisioner.install(id, new File(filesDirectory, "gradle-runtime/gradle-" + need.getRequiredGradleVersion()), "bin/gradle", logger);
            if (home != null) install = chooseGradle(need.getRequiredGradleVersion());
        }
        if (install != null) {
            gradleExecutable = install.executable; gradleVersion = install.version;
            bootstrapJar = GradleRuntimeLocator.findLauncherJar(install.home); mainClass = "org.gradle.launcher.GradleMain";
        } else {
            File script = new File(project, "gradlew"), jar = new File(project, "gradle/wrapper/gradle-wrapper.jar");
            if (script.isFile() && jar.isFile() && (script.canExecute() || javaLauncher != null)) {
                gradleExecutable = script; wrapper = true; bootstrapJar = jar; mainClass = "org.gradle.wrapper.GradleWrapperMain";
                logger.onWarning("No local Gradle distribution; falling back to the project wrapper, which downloads Gradle into the Gradle user home.");
            }
        }
        if (javaLauncher == null) logger.onWarning("No packaged java launcher (libjavaexe.so) in the native library directory; Gradle will be started through its shell script, which Android 10+ refuses to run from app storage.");
        if (gradleExecutable == null) return fail("No usable Gradle distribution"
            + (need.getRequiredGradleVersion() == null ? "" : " (needs " + need.getRequiredGradleVersion() + ")")
            + ". Install one at " + new File(filesDirectory, "gradle-runtime/gradle-" + (need.getRequiredGradleVersion() == null ? "<version>" : need.getRequiredGradleVersion()) + "/bin/gradle")
            + (offline ? "; automatic download is disabled in offline mode." : " or publish a matching 'gradle-<version>' entry in the verified build catalog."));

        // 2. JDK compatible with both AGP (minimum) and Gradle (maximum)
        GradleVersion effective = gradleVersion != null ? gradleVersion : need.getRequiredGradleVersion();
        int min = need.getMinimumJdk(), max = GradleCompatibilityMatrix.maximumJdk(effective);
        GradleRuntimeLocator.JdkInstall jdk = chooseJdk(min, max);
        if (jdk == null && !offline) {
            // The catalog may carry a newer JDK than the AGP minimum (for example jdk-21 for an AGP that needs 17+).
            int wanted = provisioner.findJdkMajor(min, max);
            if (wanted > 0) {
                File home = provisioner.install("jdk-" + wanted, new File(filesDirectory, "jdk/jdk-" + wanted), "lib/server/libjvm.so", logger);
                if (home != null) jdk = chooseJdk(min, max);
            } else logger.onWarning("The build catalog has no JDK between " + min + " and " + max + " for this device's ABI.");
        }
        if (jdk == null) return fail("No JDK between " + min + " and " + max + " found. Install a JDK that runs on this device under "
            + new File(filesDirectory, "jdk") + "/jdk-" + min + " (it must contain bin/java and a 'release' file). A desktop JDK is not guaranteed to run on Android.");

        // 3. Android SDK and AAPT2
        if (javaLauncher != null) {
            // bin/java inside the JDK tree points at the launcher so that Gradle can start the child JVM it forks.
            String linkProblem = JdkLauncherLink.ensure(jdk.home, javaLauncher);
            if (linkProblem != null) logger.onWarning(linkProblem);
            String selfTest = NativeToolSelfTest.checkJava(javaLauncher, jdk.home);
            if (selfTest != null) return fail(selfTest);
            logger.onInfo("java launcher self-test passed (" + javaLauncher.getName() + ").");
        }
        File sdk = new File(filesDirectory, "android-sdk");
        // Studio Mobile keeps android.jar under build-tools/platforms; the Gradle build expects an SDK folder, so present it as one.
        File androidSdk = AndroidSdkLayout.ensure(filesDirectory, logger);
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

    /** Same major version, at least the required version, closest match first (avoids silent major upgrades). */
    private GradleRuntimeLocator.GradleInstall chooseGradle(GradleVersion required) {
        GradleRuntimeLocator.GradleInstall best = null;
        for (GradleRuntimeLocator.GradleInstall candidate : locator.findGradleInstalls()) {
            if (candidate.version == null) { if (required == null && best == null) best = candidate; continue; }
            if (required != null && (candidate.version.getMajor() != required.getMajor() || candidate.version.compareTo(required) < 0)) continue;
            if (best == null || best.version == null || candidate.version.compareTo(best.version) < 0) best = candidate;
        }
        return best;
    }
    private GradleRuntimeLocator.JdkInstall chooseJdk(int min, int max) {
        GradleRuntimeLocator.JdkInstall best = null;
        for (GradleRuntimeLocator.JdkInstall candidate : locator.findJdkInstalls(externalJavaHome))
            if (candidate.major >= min && candidate.major <= max && (best == null || candidate.major < best.major)) best = candidate;
        return best;
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
