package com.example.studiomobile.script;

import com.example.studiomobile.build.BuildLogger;
import com.example.studiomobile.build.process.BuildProcessRunner;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs a .js file with a real Node.js binary (built by andiasriefail/node-android-build).
 *
 * <p>Android 10+ only lets an app execute files from its native library directory, so the CI workflow ships {@code bin/node}
 * as {@code libnodeexe.so} inside the APK (one per ABI: arm64-v8a, armeabi-v7a, x86_64, x86). The binary is self-contained
 * ({@code --partly-static}), so nothing has to be downloaded at run time. Unlike Rhino, scripts get the full Node API
 * ({@code require}, {@code fs}, {@code Promise}/async, ES modules, ...).
 */
public final class JavaScriptRunner {
    public static final String EXECUTABLE_NAME = "libnodeexe.so";
    static final String PRELOAD_NAME = "studio-node-preload.js";

    /**
     * Loaded before the user's script ({@code node -r}), so the script itself stays exactly as the user wrote it.
     *
     * <p>On the 32-bit (armeabi-v7a) Node build, an error that nothing catches ends the process with exit code 1 and prints nothing at all, not even
     * the message. console.error works, so these two handlers print the stack that way. A script that installs its own handler keeps full control
     * (we only act when ours is the only listener), and the exit code stays 1 like a normal Node.js crash.
     */
    static final String PRELOAD_SOURCE =
        "'use strict';\n"
        + "function show(error) {\n"
        + "  try { console.error(error && error.stack ? error.stack : String(error)); } catch (ignored) { }\n"
        + "}\n"
        + "process.on('uncaughtException', function (error) {\n"
        + "  if (process.listenerCount('uncaughtException') > 1) return;\n"
        + "  show(error);\n"
        + "  process.exit(1);\n"
        + "});\n"
        + "process.on('unhandledRejection', function (reason) {\n"
        + "  if (process.listenerCount('unhandledRejection') > 1) return;\n"
        + "  show(reason);\n"
        + "  process.exit(1);\n"
        + "});\n";

    private volatile Process activeProcess;
    private final BuildProcessRunner runner = new BuildProcessRunner(new BuildProcessRunner.ProcessObserver() {
        @Override public void onStarted(Process process) { activeProcess = process; }
        @Override public void onFinished(Process process) { if (activeProcess == process) activeProcess = null; }
    });
    private final File nativeLibraryDirectory, dataDirectory, cacheDirectory;

    public JavaScriptRunner(File nativeLibraryDirectory, File dataDirectory, File cacheDirectory) {
        this.nativeLibraryDirectory = nativeLibraryDirectory; this.dataDirectory = dataDirectory; this.cacheDirectory = cacheDirectory;
    }

    /** True when the Node binary for this device's ABI is packaged in the APK. */
    public static boolean isBundled(File nativeLibraryDirectory) {
        File executable = new File(nativeLibraryDirectory, EXECUTABLE_NAME);
        return executable.isFile() && executable.length() > 0 && executable.canExecute();
    }

    /** Stops the running script, if any. */
    public void cancel() { Process process = activeProcess; if (process != null) process.destroyForcibly(); }

    /** @return the process exit code, -1 on timeout or Stop. The working directory is the script's folder. */
    public int run(File script, long timeoutSeconds, BuildLogger logger) throws IOException, InterruptedException {
        if (!isBundled(nativeLibraryDirectory))
            throw new IOException("This build of Studio Mobile does not contain Node.js (" + new File(nativeLibraryDirectory, EXECUTABLE_NAME) + " is missing). Build the app with the Node step of .github/workflows/build.yml.");
        if (!dataDirectory.isDirectory() && !dataDirectory.mkdirs()) throw new IOException("Could not create " + dataDirectory);
        List<String> command = new ArrayList<>();
        command.add(new File(nativeLibraryDirectory, EXECUTABLE_NAME).getAbsolutePath());
        File preload = writePreload();
        if (preload != null) { command.add("--require"); command.add(preload.getAbsolutePath()); }
        command.add(script.getAbsolutePath());
        Map<String, String> environment = new HashMap<>();
        environment.put("HOME", dataDirectory.getAbsolutePath());
        environment.put("TMPDIR", cacheDirectory.getAbsolutePath());
        environment.put("NODE_NO_WARNINGS", "1");
        BuildProcessRunner.Result result = runner.run(command, script.getParentFile(), timeoutSeconds, logger, environment);
        return result.isTimedOut() ? -1 : result.getExitCode();
    }

    /** Writes the preload script into the cache folder. @return the file, or null when it cannot be written (the script then runs without it). */
    private File writePreload() {
        try {
            if (!cacheDirectory.isDirectory() && !cacheDirectory.mkdirs()) return null;
            File file = new File(cacheDirectory, PRELOAD_NAME);
            byte[] wanted = PRELOAD_SOURCE.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            if (!file.isFile() || file.length() != wanted.length || !java.util.Arrays.equals(java.nio.file.Files.readAllBytes(file.toPath()), wanted))
                java.nio.file.Files.write(file.toPath(), wanted);
            return file;
        } catch (IOException | RuntimeException cannotWrite) {
            return null;
        }
    }
}
