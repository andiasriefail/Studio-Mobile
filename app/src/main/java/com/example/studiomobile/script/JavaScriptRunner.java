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
        command.add(script.getAbsolutePath());
        Map<String, String> environment = new HashMap<>();
        environment.put("HOME", dataDirectory.getAbsolutePath());
        environment.put("TMPDIR", cacheDirectory.getAbsolutePath());
        environment.put("NODE_NO_WARNINGS", "1");
        BuildProcessRunner.Result result = runner.run(command, script.getParentFile(), timeoutSeconds, logger, environment);
        return result.isTimedOut() ? -1 : result.getExitCode();
    }
}
