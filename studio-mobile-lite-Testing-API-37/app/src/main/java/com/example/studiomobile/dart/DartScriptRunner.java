package com.example.studiomobile.dart;

import com.example.studiomobile.build.BuildLogger;
import com.example.studiomobile.build.process.BuildProcessRunner;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Compiles one .dart file to kernel with the Dart front end, then runs it on the Dart VM, streaming the program output. */
public final class DartScriptRunner {
    private static final Pattern RESULT = Pattern.compile("^result (\\S+)$");
    private volatile Process activeProcess;
    private final BuildProcessRunner runner = new BuildProcessRunner(new BuildProcessRunner.ProcessObserver() {
        @Override public void onStarted(Process process) { activeProcess = process; }
        @Override public void onFinished(Process process) { if (activeProcess == process) activeProcess = null; }
    });
    private final DartRuntime runtime;
    private final File dataDirectory, cacheDirectory;

    public DartScriptRunner(DartRuntime runtime, File dataDirectory, File cacheDirectory) { this.runtime = runtime; this.dataDirectory = dataDirectory; this.cacheDirectory = cacheDirectory; }

    public void cancel() { Process process = activeProcess; if (process != null) process.destroyForcibly(); }

    /** @return the exit code of the program, 1 when it does not compile, -1 on timeout. */
    public int run(File script, long timeoutSeconds, BuildLogger logger) throws Exception {
        File work = new File(cacheDirectory, "dart-build");
        if (!work.isDirectory() && !work.mkdirs()) throw new java.io.IOException("Could not create " + work);
        File dill = new File(work, script.getName() + ".dill");
        int compiled = compile(script, dill, timeoutSeconds, logger);
        if (compiled != 0) return compiled;
        BuildProcessRunner.Result result = runner.run(runtime.runCommand(dill), script.getParentFile(), timeoutSeconds, logger, runtime.environment(dataDirectory, cacheDirectory));
        return result.isTimedOut() ? -1 : result.getExitCode();
    }

    /**
     * Compiles one .dart file (and what it imports) to a self-contained kernel file without running it. The build of an APK uses this to
     * pack the program into the app.
     *
     * @return 0 on success, 1 when the code does not compile (the diagnostics and an error are sent to the logger), -1 on timeout.
     */
    public int compile(File script, File dill, long timeoutSeconds, BuildLogger logger) throws Exception {
        if (!dataDirectory.isDirectory() && !dataDirectory.mkdirs()) throw new java.io.IOException("Could not create " + dataDirectory);
        File parent = dill.getAbsoluteFile().getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new java.io.IOException("Could not create " + parent);
        dill.delete();
        final List<String> lines = new ArrayList<>();
        BuildLogger collector = new BuildLogger() {
            @Override public void onInfo(String message) { lines.add(message); }
            @Override public void onWarning(String message) { lines.add(message); }
            @Override public void onError(String message, Throwable cause) { lines.add(message); }
        };
        BuildProcessRunner.Result compile = runner.run(runtime.compileCommand(script, dill), script.getParentFile(), timeoutSeconds, collector, runtime.environment(dataDirectory, cacheDirectory));
        if (compile.isTimedOut()) return -1;
        // frontend_server prints diagnostics, then "result <id>" and a final "<id> <dill> <error count>" line.
        String boundary = null; int errors = compile.getExitCode() == 0 ? 0 : 1; List<String> diagnostics = new ArrayList<>();
        for (String line : lines) {
            Matcher match = RESULT.matcher(line.trim());
            if (match.matches()) { boundary = match.group(1); continue; }
            if (boundary != null && line.startsWith(boundary)) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length >= 3) try { errors = Math.max(errors, Integer.parseInt(parts[parts.length - 1])); } catch (NumberFormatException ignored) { }
                continue;
            }
            diagnostics.add(line);
        }
        if (errors > 0 || !dill.isFile()) {
            for (String line : diagnostics) logger.onInfo(line);
            if (diagnostics.isEmpty()) for (String line : lines) logger.onInfo(line);
            logger.onError("Dart compilation failed" + (compile.getExitCode() != 0 ? " (exit code " + compile.getExitCode() + ")" : "") + ".", null);
            return 1;
        }
        return 0;
    }
}
