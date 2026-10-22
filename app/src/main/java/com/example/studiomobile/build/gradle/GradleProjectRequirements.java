package com.example.studiomobile.build.gradle;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What the PROJECT asks for: its Gradle wrapper version, its Android Gradle Plugin, and the Java level it compiles with.
 * Studio Mobile follows these values; it never substitutes whatever Gradle or JDK the app happens to ship.
 */
public final class GradleProjectRequirements {
    private final GradleVersion wrapperVersion;   // exact version in gradle-wrapper.properties, or null
    private final GradleVersion agpVersion;       // null when it cannot be read statically
    private final GradleVersion requiredGradle;   // wrapper version raised to the AGP minimum; null when nothing is asked
    private final GradleVersion minimumGradle;       // lowest Gradle the AGP supports, or null
    private final GradleVersion maximumGradleExclusive;
    private final int minimumJdk;                 // what the AGP needs to run
    private final int projectJavaLevel;           // highest Java level the project declares, 0 when none
    private final boolean agpKnown;

    private GradleProjectRequirements(GradleVersion wrapper, GradleVersion agp, GradleVersion required, GradleVersion minGradle, GradleVersion maxBefore, int jdk, int javaLevel, boolean known) {
        this.wrapperVersion = wrapper; this.agpVersion = agp; this.requiredGradle = required; this.minimumGradle = minGradle; this.maximumGradleExclusive = maxBefore;
        this.minimumJdk = jdk; this.projectJavaLevel = javaLevel; this.agpKnown = known;
    }
    public GradleVersion getWrapperGradleVersion() { return wrapperVersion; }
    public GradleVersion getAgpVersion() { return agpVersion; }
    public GradleVersion getRequiredGradleVersion() { return requiredGradle; }
    /** Lowest Gradle the AGP supports, or null when unknown. */
    public GradleVersion getMinimumGradle() { return minimumGradle; }
    /** Exclusive upper bound of the Gradle versions this AGP works with, or null when unknown. */
    public GradleVersion getMaximumGradleExclusive() { return maximumGradleExclusive; }
    public int getMinimumJdk() { return minimumJdk; }
    public int getProjectJavaLevel() { return projectJavaLevel; }
    /** The JDK the build needs: enough for the AGP and for the Java level the project compiles with. */
    public int getWantedJdk() { return Math.max(minimumJdk, projectJavaLevel); }
    public boolean isAgpKnown() { return agpKnown; }

    private static final Pattern DISTRIBUTION = Pattern.compile("gradle-([0-9][0-9A-Za-z.\\-]*?)-(?:bin|all)\\.zip");
    private static final Pattern CATALOG_AGP = Pattern.compile("(?m)^\\s*agp\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern PLUGIN_AGP = Pattern.compile("com\\.android\\.(?:application|library)['\"]?\\)?\\s*(?:version)?\\s*['\"]([0-9][^'\"]*)['\"]");
    private static final Pattern CLASSPATH_AGP = Pattern.compile("com\\.android\\.tools\\.build:gradle:([0-9][^'\"\\s)]*)");
    private static final Pattern JAVA_VERSION = Pattern.compile("JavaVersion\\.VERSION_(\\d+)(?:_(\\d+))?");
    private static final Pattern COMPATIBILITY = Pattern.compile("(?:source|target)Compatibility\\s*(?:=\\s*)?['\"]?(\\d+(?:\\.\\d+)?)['\"]?");
    private static final Pattern TOOLCHAIN = Pattern.compile("jvmToolchain\\s*\\(\\s*(\\d+)\\s*\\)");
    private static final Pattern LANGUAGE_VERSION = Pattern.compile("JavaLanguageVersion\\.of\\(\\s*(\\d+)\\s*\\)");

    public static GradleProjectRequirements read(File project, GradleCompatibilityMatrix matrix) {
        GradleVersion wrapper = GradleVersion.parse(find(new File(project, "gradle/wrapper/gradle-wrapper.properties"), DISTRIBUTION));
        GradleVersion agp = GradleVersion.parse(readAgp(project));
        GradleCompatibilityMatrix.Requirement rule = matrix.forAgp(agp);
        GradleVersion required = wrapper;
        if (rule.minimumGradle != null && (required == null || required.compareTo(rule.minimumGradle) < 0)) required = rule.minimumGradle;
        return new GradleProjectRequirements(wrapper, agp, required, rule.minimumGradle, rule.maximumGradleExclusive, rule.minimumJdk, readJavaLevel(project), rule.knownAgp);
    }

    private static String readAgp(File project) {
        String value = find(new File(project, "gradle/libs.versions.toml"), CATALOG_AGP);
        String[] scripts = {"build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts"};
        for (String script : scripts) {
            if (value != null) break;
            File file = new File(project, script);
            value = find(file, PLUGIN_AGP);
            if (value == null) value = find(file, CLASSPATH_AGP);
        }
        return value;
    }

    /** Highest Java level declared in the root and module build scripts (compileOptions, jvmToolchain, JavaLanguageVersion). */
    private static int readJavaLevel(File project) {
        int highest = 0;
        java.util.List<File> scripts = new java.util.ArrayList<>();
        for (String name : new String[] {"build.gradle", "build.gradle.kts"}) scripts.add(new File(project, name));
        File[] children = project.listFiles();
        if (children != null) for (File child : children) if (child.isDirectory())
            for (String name : new String[] {"build.gradle", "build.gradle.kts"}) scripts.add(new File(child, name));
        for (File script : scripts) {
            if (!script.isFile() || script.length() > 1024 * 1024) continue;
            String text;
            try { text = new String(Files.readAllBytes(script.toPath()), StandardCharsets.UTF_8); } catch (Exception ignored) { continue; }
            highest = Math.max(highest, level(JAVA_VERSION.matcher(text), true));
            highest = Math.max(highest, level(COMPATIBILITY.matcher(text), false));
            highest = Math.max(highest, level(TOOLCHAIN.matcher(text), false));
            highest = Math.max(highest, level(LANGUAGE_VERSION.matcher(text), false));
        }
        return highest;
    }

    private static int level(Matcher matcher, boolean enumStyle) {
        int highest = 0;
        while (matcher.find()) {
            try {
                String first = matcher.group(1);
                int value;
                if (enumStyle && "1".equals(first) && matcher.groupCount() > 1 && matcher.group(2) != null) value = Integer.parseInt(matcher.group(2));   // VERSION_1_8
                else if (first.startsWith("1.")) value = Integer.parseInt(first.substring(2));                                                    // "1.8"
                else value = Integer.parseInt(first.contains(".") ? first.substring(0, first.indexOf('.')) : first);
                highest = Math.max(highest, value);
            } catch (NumberFormatException ignored) { }
        }
        return highest;
    }

    private static String find(File file, Pattern pattern) {
        if (!file.isFile() || file.length() > 1024 * 1024) return null;
        try {
            Matcher matcher = pattern.matcher(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
            return matcher.find() ? matcher.group(1) : null;
        } catch (Exception ignored) { return null; }
    }
}
