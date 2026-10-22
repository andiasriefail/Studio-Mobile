package com.example.studiomobile.build.gradle;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which JDK launchers the APK really contains. tools/resolve_gradle_jdk.py packages the tools of EVERY JDK major as
 * {@code lib<tool><major>exe.so} (libjava21exe.so, libjavac21exe.so, ...) in the native library directory, and the build only
 * works with a JDK whose launchers are there: Android 10+ refuses to run anything else from app storage.
 *
 * <p>The JDK is chosen by the project (AGP, Java level, Gradle window), never by this class. It only answers "does JDK N have its
 * launchers", so the planner is not given a JDK that could never be started. No major is ever substituted for another.
 */
public final class JdkLauncherSet {
    /** Same list as "launcherTools" in tools/gradle-jdk.json (a unit test keeps them equal). */
    public static final String[] TOOLS = {"java", "javac", "jmod", "jar", "jlink"};
    /** AGP's JdkImageTransform (compileSdk 34 and higher) starts these next to java. */
    public static final String[] IMAGE_TOOLS = {"javac", "jmod", "jlink"};
    /** First compileSdk whose android.jar needs the JDK image (see AndroidSdkLayout.CORE_MODULES_MIN_API). */
    public static final int IMAGE_MIN_COMPILE_SDK = 34;

    private static final Pattern NAME = Pattern.compile("lib(java|javac|jmod|jar|jlink)(\\d+)exe\\.so");

    private final Map<Integer, Set<String>> toolsByMajor = new TreeMap<>();

    private JdkLauncherSet() { }

    /** File name of one launcher, exactly as resolve_gradle_jdk.py writes it: ("javac", 21) -> libjavac21exe.so. */
    public static String fileName(String tool, int major) { return "lib" + tool + major + "exe.so"; }

    /** Reads the native library directory. Only files that can really be started count (same test as BuildExecutableResolver). */
    public static JdkLauncherSet scan(File nativeLibraryDirectory) {
        java.util.List<String> names = new ArrayList<>();
        File[] files = nativeLibraryDirectory == null ? null : nativeLibraryDirectory.listFiles();
        if (files != null) for (File file : files) if (file.isFile() && file.canExecute() && file.length() > 0) names.add(file.getName());
        return of(names);
    }

    /** Builds the set from plain file names (pure, used by scan and by tests). Names that are not launchers are ignored. */
    public static JdkLauncherSet of(Collection<String> fileNames) {
        JdkLauncherSet set = new JdkLauncherSet();
        for (String name : fileNames) {
            Matcher matcher = NAME.matcher(name);
            if (!matcher.matches()) continue;
            int major;
            try { major = Integer.parseInt(matcher.group(2)); } catch (NumberFormatException ignored) { continue; }
            Set<String> tools = set.toolsByMajor.get(major);
            if (tools == null) { tools = new TreeSet<>(); set.toolsByMajor.put(major, tools); }
            tools.add(matcher.group(1));
        }
        return set;
    }

    public boolean isEmpty() { return toolsByMajor.isEmpty(); }

    /** Majors that have at least the java launcher (enough to start Gradle and the JVM). */
    public Set<Integer> majorsWithJava() { return majorsWith(new String[] {"java"}); }

    /** Majors that also have javac, jmod and jlink, which compileSdk 34+ needs. */
    public Set<Integer> majorsForImageBuilds() {
        String[] needed = new String[IMAGE_TOOLS.length + 1];
        needed[0] = "java";
        System.arraycopy(IMAGE_TOOLS, 0, needed, 1, IMAGE_TOOLS.length);
        return majorsWith(needed);
    }

    /** The tools from {@link #TOOLS} that this major does not have. Empty when it is complete. */
    public Set<String> missingTools(int major) {
        Set<String> missing = new LinkedHashSet<>();
        Set<String> have = toolsByMajor.get(major);
        for (String tool : TOOLS) if (have == null || !have.contains(tool)) missing.add(tool);
        return missing;
    }

    /** Keeps only the candidate majors that are in {@code allowed}. The order of a sorted input is kept. */
    public static Set<Integer> restrict(Collection<Integer> candidates, Set<Integer> allowed) {
        Set<Integer> kept = new TreeSet<>();
        for (Integer major : candidates) if (allowed.contains(major)) kept.add(major);
        return kept;
    }

    /** Human readable, for the build log: "JDK 17: java; JDK 21: jar/java/javac/jlink/jmod". */
    public String describe() {
        if (toolsByMajor.isEmpty()) return "none";
        StringBuilder text = new StringBuilder();
        for (Map.Entry<Integer, Set<String>> entry : toolsByMajor.entrySet()) {
            if (text.length() > 0) text.append("; ");
            text.append("JDK ").append(entry.getKey()).append(": ").append(String.join("/", entry.getValue()));
        }
        return text.toString();
    }

    private Set<Integer> majorsWith(String[] needed) {
        Set<Integer> result = new TreeSet<>();
        for (Map.Entry<Integer, Set<String>> entry : toolsByMajor.entrySet()) {
            boolean all = true;
            for (String tool : needed) if (!entry.getValue().contains(tool)) { all = false; break; }
            if (all) result.add(entry.getKey());
        }
        return result;
    }
}
