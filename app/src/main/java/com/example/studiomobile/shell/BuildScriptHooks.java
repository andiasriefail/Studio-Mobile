package com.example.studiomobile.shell;

import com.example.studiomobile.build.BuildLogger;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shell scripts that a project ships in its {@code scripts/} folder and that run around the build:
 * <ul>
 *   <li>{@code scripts/pre-build.sh}: before anything is compiled (generate sources, copy assets, write version files ...);</li>
 *   <li>{@code scripts/post-build.sh}: after the APK was built; {@code STUDIO_APK} holds the path of the finished APK.</li>
 * </ul>
 * Both run in the build copy of the project, never in the user's own project folder, with the project root as working directory.
 * A project without these files is not affected. A script that exits with a non-zero code fails the build.
 *
 * <p>The scripts come with the project (possibly from an imported ZIP), so the person is asked once per build before the first one runs.
 */
public final class BuildScriptHooks {
    public static final String PRE_BUILD = "scripts/pre-build.sh";
    public static final String POST_BUILD = "scripts/post-build.sh";
    public static final long TIMEOUT_SECONDS = 600;

    /** Asks whether the scripts may run. Returns false to skip them (the build then stops, because the project expects them). */
    public interface Approval { boolean allow(List<String> scripts); }

    private final ShellScriptRunner runner;
    private final Approval approval;
    private volatile boolean approved;

    public BuildScriptHooks(ShellScriptRunner runner, Approval approval) { this.runner = runner; this.approval = approval; }

    public void cancel() { runner.cancel(); }

    /** The hook scripts that exist in this project, in run order. */
    public static List<File> find(File project) {
        List<File> found = new ArrayList<>();
        for (String name : new String[] { PRE_BUILD, POST_BUILD }) {
            File script = new File(project, name);
            if (script.isFile()) found.add(script);
        }
        return found;
    }

    /** Runs scripts/pre-build.sh when present. @throws IllegalStateException when it fails, times out or was not approved. */
    public void runPreBuild(File project, BuildLogger logger) throws Exception {
        run(project, PRE_BUILD, null, logger);
    }

    /** Runs scripts/post-build.sh when present, with STUDIO_APK set. @throws IllegalStateException when it fails, times out or was not approved. */
    public void runPostBuild(File project, File apk, BuildLogger logger) throws Exception {
        Map<String, String> extra = new LinkedHashMap<>();
        if (apk != null) extra.put("STUDIO_APK", apk.getAbsolutePath());
        run(project, POST_BUILD, extra, logger);
    }

    private void run(File project, String name, Map<String, String> extra, BuildLogger logger) throws Exception {
        File script = new File(project, name);
        if (!script.isFile()) return;
        if (!approved) {
            List<String> names = new ArrayList<>();
            for (File file : find(project)) names.add(project.toPath().relativize(file.toPath()).toString());
            if (approval != null && !approval.allow(Collections.unmodifiableList(names)))
                throw new IllegalStateException("The build scripts (" + String.join(", ", names) + ") were not allowed to run, so the build was stopped.");
            approved = true;
        }
        Map<String, String> environment = new LinkedHashMap<>();
        environment.put("STUDIO_PROJECT_DIR", project.getAbsolutePath());
        if (extra != null) environment.putAll(extra);
        logger.onInfo("Running " + name + "…");
        int code = runner.run(script, project, environment, TIMEOUT_SECONDS, logger);
        if (code == -1) throw new IllegalStateException(name + " timed out after " + TIMEOUT_SECONDS + " seconds.");
        if (code != 0) throw new IllegalStateException(name + " failed with exit code " + code + ". Fix the errors listed above.");
    }
}
