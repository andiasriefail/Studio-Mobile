package com.example.studiomobile.build.gradle;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detects Gradle distributions and JDKs already installed under the app-private files directory.
 *
 * Layout (all under filesDir):
 *   build-tools/gradle/bin/gradle, gradle/bin/gradle            legacy, unversioned
 *   gradle-runtime/gradle-<version>/bin/gradle                  versioned (preferred)
 *   jdk/jdk-<major>[...]/bin/java  (+ release file)              JDK candidates
 */
public final class GradleRuntimeLocator {
    public static final class GradleInstall {
        public final File home; public final File executable; public final GradleVersion version;
        GradleInstall(File home, File executable, GradleVersion version) { this.home = home; this.executable = executable; this.version = version; }
    }
    public static final class JdkInstall {
        public final File home; public final int major;
        JdkInstall(File home, int major) { this.home = home; this.major = major; }
    }
    private static final Pattern GRADLE_JAR = Pattern.compile("gradle-(?:launcher|core)-([0-9][0-9.]*)\\.jar");
    private static final Pattern JAVA_VERSION = Pattern.compile("JAVA_VERSION=\"?([0-9][0-9._]*)");

    private final File filesDirectory;
    public GradleRuntimeLocator(File filesDirectory) { this.filesDirectory = filesDirectory; }

    public List<GradleInstall> findGradleInstalls() {
        List<GradleInstall> found = new ArrayList<>();
        File[] versioned = new File(filesDirectory, "gradle-runtime").listFiles();
        if (versioned != null) for (File dir : versioned) add(found, dir);
        add(found, new File(filesDirectory, "build-tools/gradle"));
        add(found, new File(filesDirectory, "gradle"));
        Collections.sort(found, new Comparator<GradleInstall>() {
            @Override public int compare(GradleInstall a, GradleInstall b) {
                if (a.version == null) return b.version == null ? 0 : 1;
                if (b.version == null) return -1;
                return b.version.compareTo(a.version);
            }
        });
        return found;
    }

    private void add(List<GradleInstall> output, File home) {
        File executable = new File(home, "bin/gradle");
        if (!executable.isFile() || !executable.canExecute() || executable.length() == 0) return;
        output.add(new GradleInstall(home, executable, readGradleVersion(home)));
    }

    /** The jar whose main class starts Gradle (what bin/gradle puts on the classpath), or null when the distribution has none. */
    public static File findLauncherJar(File gradleHome) {
        File[] libs = new File(gradleHome, "lib").listFiles();
        if (libs != null) for (File lib : libs) if (lib.isFile() && lib.getName().matches("gradle-launcher-[0-9][0-9.]*\\.jar")) return lib;
        return null;
    }

    /** Reads the version from lib/gradle-launcher-<v>.jar; falls back to the directory name gradle-<v>. */
    static GradleVersion readGradleVersion(File home) {
        File[] libs = new File(home, "lib").listFiles();
        if (libs != null) for (File lib : libs) {
            Matcher matcher = GRADLE_JAR.matcher(lib.getName());
            if (matcher.matches()) return GradleVersion.parse(matcher.group(1));
        }
        Matcher name = Pattern.compile("^gradle-([0-9][0-9.]*)").matcher(home.getName());
        return name.find() ? GradleVersion.parse(name.group(1)) : null;
    }

    public List<JdkInstall> findJdkInstalls(File externalJavaHome) {
        List<JdkInstall> found = new ArrayList<>();
        File[] children = new File(filesDirectory, "jdk").listFiles();
        if (children != null) for (File dir : children) addJdk(found, dir);
        if (externalJavaHome != null) addJdk(found, externalJavaHome);
        Collections.sort(found, new Comparator<JdkInstall>() {
            @Override public int compare(JdkInstall a, JdkInstall b) { return Integer.compare(b.major, a.major); }
        });
        return found;
    }

    /**
     * A JDK is accepted when it has a readable 'release' file and the VM library. bin/java is not required: on Android 10+
     * the launcher runs from the native library directory (see BuildExecutableResolver), not from this tree.
     */
    private void addJdk(List<JdkInstall> output, File home) {
        File vm = new File(home, "lib/server/libjvm.so");
        File java = new File(home, "bin/java");
        boolean hasVm = vm.isFile() && vm.length() > 0;
        boolean hasLegacyJava = java.isFile() && java.canExecute() && java.length() > 0;
        if (!hasVm && !hasLegacyJava) return;
        int major = readJdkMajor(home);
        if (major > 0) output.add(new JdkInstall(home, major));
    }

    /** Parses JAVA_VERSION from the JDK 'release' file; returns -1 when unreadable so unknown JDKs are never trusted. */
    static int readJdkMajor(File home) {
        File release = new File(home, "release");
        if (!release.isFile()) return -1;
        try (BufferedReader reader = new BufferedReader(new FileReader(release))) {
            String line;
            while ((line = reader.readLine()) != null) {
                Matcher matcher = JAVA_VERSION.matcher(line);
                if (!matcher.find()) continue;
                String version = matcher.group(1);
                if (version.startsWith("1.")) return Integer.parseInt(version.substring(2, 3));
                int end = 0; while (end < version.length() && Character.isDigit(version.charAt(end))) end++;
                return Integer.parseInt(version.substring(0, end));
            }
        } catch (Exception ignored) { }
        return -1;
    }
}
