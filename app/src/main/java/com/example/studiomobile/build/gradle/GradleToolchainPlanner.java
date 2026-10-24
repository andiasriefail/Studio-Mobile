package com.example.studiomobile.build.gradle;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.TreeSet;

/**
 * Chooses Gradle and JDK TOGETHER, from what the project asks for. Pure logic (no Android, no file access) so it is unit-testable.
 *
 * The project is the authority:
 *  1. its wrapper version (raised to the AGP minimum) is tried first, never silently replaced by "the newest Gradle we have";
 *  2. a different Gradle is used only when the wanted one cannot be obtained or cannot run on any available JDK, and then only
 *     one inside the window the AGP supports (a Gradle outside that window is never picked, it is reported instead);
 *  3. the JDK must be at least what the AGP and the project's Java level need and at most what that Gradle can run on.
 */
public final class GradleToolchainPlanner {
    public static final class Plan {
        public final GradleVersion gradle; public final int jdk; public final boolean viaWrapper;
        public final List<String> notes = new ArrayList<>(); public final List<String> warnings = new ArrayList<>();
        public final String error;
        Plan(GradleVersion gradle, int jdk, boolean viaWrapper, String error) { this.gradle = gradle; this.jdk = jdk; this.viaWrapper = viaWrapper; this.error = error; }
        public boolean isUsable() { return error == null; }
    }

    private GradleToolchainPlanner() { }

    /**
     * @param gradleAvailable Gradle versions installed or downloadable from the verified catalog
     * @param jdkAvailable    JDK majors installed or downloadable from the verified catalog
     * @param wrapperUsable   the project ships gradlew + gradle-wrapper.jar, so the wrapper can fetch its own exact Gradle
     */
    public static Plan plan(GradleProjectRequirements need, GradleCompatibilityMatrix matrix, Collection<GradleVersion> gradleAvailable,
                            Collection<Integer> jdkAvailable, boolean wrapperUsable) {
        int wanted = need.getWantedJdk();
        GradleVersion pinned = need.getRequiredGradleVersion();
        List<String> problems = new ArrayList<>();

        List<GradleVersion> order = new ArrayList<>();
        if (pinned != null) order.add(pinned);
        List<GradleVersion> inWindow = new ArrayList<>(new TreeSet<>(gradleAvailable));
        java.util.Collections.reverse(inWindow);   // newest first
        for (GradleVersion candidate : inWindow) {
            if (pinned != null && candidate.equals(pinned)) continue;
            if (!inWindow(candidate, need)) continue;
            order.add(candidate);
        }

        for (GradleVersion gradle : order) {
            boolean isPinned = gradle.equals(pinned);
            boolean obtainable = gradleAvailable.contains(gradle) || (isPinned && wrapperUsable);
            if (!obtainable) { if (isPinned) problems.add("Gradle " + gradle + " (asked by the project) is not installed, not in the build catalog and the project has no usable wrapper"); continue; }
            int limit = matrix.maximumJdk(gradle);
            int jdk = lowestJdk(jdkAvailable, wanted, limit);
            if (jdk < 0) { problems.add("Gradle " + gradle + " runs on JDK " + wanted + " to " + (limit == Integer.MAX_VALUE ? "any" : String.valueOf(limit)) + ", and no such JDK is available"); continue; }
            Plan plan = new Plan(gradle, jdk, isPinned && !gradleAvailable.contains(gradle), null);
            if (pinned != null && !isPinned) plan.warnings.add("The project asks for Gradle " + pinned + " but it cannot be used here (" + String.join("; ", problems)
                + "). Using Gradle " + gradle + ", which the Android Gradle plugin " + (need.getAgpVersion() == null ? "" : need.getAgpVersion() + " ") + "supports.");
            if (pinned != null && isPinned && need.getWrapperGradleVersion() != null && need.getWrapperGradleVersion().compareTo(pinned) < 0)
                plan.notes.add("The wrapper requests Gradle " + need.getWrapperGradleVersion() + " but the Android Gradle plugin needs " + pinned + "+; using " + pinned + ".");
            if (need.getMaximumGradleExclusive() != null && gradle.compareTo(need.getMaximumGradleExclusive()) >= 0)
                plan.warnings.add("Gradle " + gradle + " is newer than the Android Gradle plugin " + need.getAgpVersion() + " supports (below " + need.getMaximumGradleExclusive() + "). The project asked for it, so it is used as is.");
            plan.notes.add("Toolchain plan: Gradle " + gradle + (plan.viaWrapper ? " (through the project wrapper)" : "") + ", JDK " + jdk
                + " (needs " + wanted + "+: AGP " + need.getMinimumJdk() + (need.getProjectJavaLevel() > 0 ? ", project Java " + need.getProjectJavaLevel() : "") + ").");
            return plan;
        }
        return new Plan(null, -1, false, describeFailure(need, wanted, gradleAvailable, jdkAvailable, problems));
    }

    /** Inside the window the AGP supports: at least its minimum, strictly below the first incompatible release. */
    private static boolean inWindow(GradleVersion candidate, GradleProjectRequirements need) {
        GradleVersion floor = need.getMinimumGradle();
        if (floor != null && candidate.compareTo(floor) < 0) return false;
        GradleVersion ceiling = need.getMaximumGradleExclusive();
        return ceiling == null || candidate.compareTo(ceiling) < 0;
    }

    private static int lowestJdk(Collection<Integer> available, int wanted, int limit) {
        int best = -1;
        for (int major : available) if (major >= wanted && major <= limit && (best < 0 || major < best)) best = major;
        return best;
    }

    private static String describeFailure(GradleProjectRequirements need, int wantedJdk, Collection<GradleVersion> gradle, Collection<Integer> jdk, List<String> problems) {
        StringBuilder text = new StringBuilder();
        int newestJdk = jdk.isEmpty() ? 0 : java.util.Collections.max(jdk);
        if (newestJdk > 0 && need.getProjectJavaLevel() > newestJdk)
            text.append("The project asks for Java ").append(need.getProjectJavaLevel()).append(" (jvmToolchain, JavaLanguageVersion or a source/target level), but the newest JDK available on this device is ")
                .append(newestJdk).append(". Lower the level to ").append(newestJdk).append(" or less. ");
        text.append("No Gradle/JDK combination fits this project.");
        text.append(" Project: Gradle ").append(need.getRequiredGradleVersion() == null ? "unspecified" : need.getRequiredGradleVersion())
            .append(", AGP ").append(need.getAgpVersion() == null ? "unknown" : need.getAgpVersion())
            .append(", JDK ").append(wantedJdk).append("+.");
        if (need.getMaximumGradleExclusive() != null) text.append(" The AGP works with Gradle below ").append(need.getMaximumGradleExclusive()).append('.');
        text.append(" Available Gradle: ").append(gradle.isEmpty() ? "none" : new TreeSet<>(gradle).toString()).append(". Available JDK: ").append(jdk.isEmpty() ? "none" : new TreeSet<>(jdk).toString()).append('.');
        if (!problems.isEmpty()) text.append(" Why: ").append(String.join("; ", problems)).append('.');
        text.append(" Add the missing Gradle or JDK to the build catalog (tools/gradle-jdk.json); the app does not substitute an incompatible one.");
        return text.toString();
    }
}
