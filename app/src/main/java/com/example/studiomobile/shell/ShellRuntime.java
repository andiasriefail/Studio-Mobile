package com.example.studiomobile.shell;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The shell that every Android phone already has ({@code /system/bin/sh}, mksh with the toybox tools), so nothing is bundled or downloaded.
 *
 * <p>Android 10+ refuses to execute files from an app's data folder, so a script is never run as a program: the system shell is started
 * and given the script as its argument ({@code sh script.sh}). The shell only has to READ the script, which is always allowed, and that is
 * why the script does not need the executable bit. Commands inside the script come from the system ({@code ls}, {@code cp}, {@code sed},
 * {@code zip} is not one of them); programs the script would have to execute from the app's own folders are blocked by Android.
 */
public final class ShellRuntime {
    public static final String SYSTEM_SHELL = "/system/bin/sh";
    private static final String SYSTEM_PATH = "/system/bin:/system/xbin:/vendor/bin";

    public final File shell;

    ShellRuntime(File shell) { this.shell = shell; }

    /** @return the runtime, or null with the reason added to {@code problems}. */
    public static ShellRuntime locate(List<String> problems) { return locate(new File(SYSTEM_SHELL), problems); }

    /** Same, with another shell path (unit tests use the host's /bin/sh). */
    public static ShellRuntime locate(File shell, List<String> problems) {
        if (!shell.isFile() || !shell.canExecute()) {
            problems.add("This device has no shell at " + shell + ", so shell scripts cannot run.");
            return null;
        }
        return new ShellRuntime(shell);
    }

    /** True for the file names the app treats as shell scripts. */
    public static boolean isShellScript(String fileName) {
        String name = fileName.toLowerCase(java.util.Locale.ROOT);
        return name.endsWith(".sh") || name.endsWith(".bash");
    }

    /** {@code sh <script>}. The script path is a separate argument, never pasted into a command string. */
    public List<String> command(File script) {
        List<String> command = new ArrayList<>();
        command.add(shell.getAbsolutePath()); command.add(script.getAbsolutePath());
        return command;
    }

    /** {@code sh -n <script>}: parses the script and runs nothing. */
    public List<String> syntaxCheckCommand(File script) {
        List<String> command = new ArrayList<>();
        command.add(shell.getAbsolutePath()); command.add("-n"); command.add(script.getAbsolutePath());
        return command;
    }

    /**
     * @param dataDirectory HOME for the script
     * @param cacheDirectory TMPDIR for the script
     * @param extra optional extra variables (for example STUDIO_PROJECT_DIR); may be null
     */
    public Map<String, String> environment(File dataDirectory, File cacheDirectory, Map<String, String> extra) {
        Map<String, String> environment = new HashMap<>();
        environment.put("PATH", SYSTEM_PATH);
        environment.put("HOME", dataDirectory.getAbsolutePath());
        environment.put("TMPDIR", cacheDirectory.getAbsolutePath());
        environment.put("STUDIO_MOBILE", "1");
        if (extra != null) environment.putAll(extra);
        return environment;
    }
}
