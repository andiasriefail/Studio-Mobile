package com.example.studiomobile.python;

import com.example.studiomobile.build.BuildLogger;
import com.example.studiomobile.build.process.BuildProcessRunner;
import java.io.File;

/** Runs one .py file with the bundled interpreter and streams stdout/stderr to the logger. */
public final class PythonScriptRunner {
    private volatile Process activeProcess;
    private final BuildProcessRunner runner = new BuildProcessRunner(new BuildProcessRunner.ProcessObserver() {
        @Override public void onStarted(Process process) { activeProcess = process; }
        @Override public void onFinished(Process process) { if (activeProcess == process) activeProcess = null; }
    });
    private final PythonRuntime runtime;
    private final File dataDirectory, cacheDirectory;

    public PythonScriptRunner(PythonRuntime runtime, File dataDirectory, File cacheDirectory) {
        this.runtime = runtime; this.dataDirectory = dataDirectory; this.cacheDirectory = cacheDirectory;
    }

    /** Stops the running script, if any. */
    public void cancel() { Process process = activeProcess; if (process != null) process.destroyForcibly(); }

    /** @return the process exit code, -1 on timeout. The working directory is the script's folder. */
    public int run(File script, long timeoutSeconds, BuildLogger logger) throws Exception {
        if (!dataDirectory.isDirectory() && !dataDirectory.mkdirs()) throw new java.io.IOException("Could not create " + dataDirectory);
        BuildProcessRunner.Result result = runner.run(runtime.command(script), script.getParentFile(), timeoutSeconds, logger, runtime.environment(dataDirectory, cacheDirectory));
        return result.isTimedOut() ? -1 : result.getExitCode();
    }
}
