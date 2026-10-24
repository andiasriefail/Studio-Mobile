package com.example.studiomobile.shell;

import com.example.studiomobile.build.BuildLogger;
import com.example.studiomobile.build.process.BuildProcessRunner;
import java.io.File;
import java.io.IOException;
import java.util.Map;

/** Runs one .sh file with the system shell and streams stdout/stderr to the logger. */
public final class ShellScriptRunner {
    private volatile Process activeProcess;
    private final BuildProcessRunner runner = new BuildProcessRunner(new BuildProcessRunner.ProcessObserver() {
        @Override public void onStarted(Process process) { activeProcess = process; }
        @Override public void onFinished(Process process) { if (activeProcess == process) activeProcess = null; }
    });
    private final ShellRuntime runtime;
    private final File dataDirectory, cacheDirectory;

    public ShellScriptRunner(ShellRuntime runtime, File dataDirectory, File cacheDirectory) {
        this.runtime = runtime; this.dataDirectory = dataDirectory; this.cacheDirectory = cacheDirectory;
    }

    /** Stops the running script, if any. */
    public void cancel() { Process process = activeProcess; if (process != null) process.destroyForcibly(); }

    /** Runs with the script's own folder as working directory. @return the exit code, -1 on timeout. */
    public int run(File script, long timeoutSeconds, BuildLogger logger) throws Exception {
        return run(script, script.getParentFile(), null, timeoutSeconds, logger);
    }

    /** @param workingDirectory where the script starts; @param extraEnvironment extra variables or null. @return the exit code, -1 on timeout. */
    public int run(File script, File workingDirectory, Map<String, String> extraEnvironment, long timeoutSeconds, BuildLogger logger) throws Exception {
        if (!dataDirectory.isDirectory() && !dataDirectory.mkdirs()) throw new IOException("Could not create " + dataDirectory);
        if (!cacheDirectory.isDirectory() && !cacheDirectory.mkdirs()) throw new IOException("Could not create " + cacheDirectory);
        BuildProcessRunner.Result result = runner.run(runtime.command(script), workingDirectory, timeoutSeconds, logger,
            runtime.environment(dataDirectory, cacheDirectory, extraEnvironment));
        return result.isTimedOut() ? -1 : result.getExitCode();
    }
}
