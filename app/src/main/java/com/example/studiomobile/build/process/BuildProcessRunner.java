package com.example.studiomobile.build.process;

import com.example.studiomobile.build.BuildLogger;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Executes a packaged local tool without a shell and streams output to the build log. */
public final class BuildProcessRunner {
    public interface ProcessObserver { void onStarted(Process process); void onFinished(Process process); }
    public static final class Result {
        private final int exitCode; private final boolean timedOut;
        Result(int exitCode, boolean timedOut) { this.exitCode=exitCode; this.timedOut=timedOut; }
        public int getExitCode() { return exitCode; }
        public boolean isTimedOut() { return timedOut; }
        public boolean isSuccessful() { return !timedOut && exitCode==0; }
    }
    private final ProcessObserver observer;
    public BuildProcessRunner() { this(null); }
    public BuildProcessRunner(ProcessObserver observer) { this.observer=observer; }

    public Result run(List<String> command, File workingDirectory, long timeoutSeconds, BuildLogger logger) throws IOException, InterruptedException {
        return run(command, workingDirectory, timeoutSeconds, logger, null);
    }

    /** Same as above, with extra environment variables (for example JAVA_HOME and GRADLE_USER_HOME). */
    public Result run(List<String> command, File workingDirectory, long timeoutSeconds, BuildLogger logger, java.util.Map<String,String> environment) throws IOException, InterruptedException {
        if(command==null||command.isEmpty())throw new IllegalArgumentException("Build command cannot be empty.");
        if(workingDirectory==null||!workingDirectory.isDirectory())throw new IllegalArgumentException("Build working directory does not exist.");
        if(timeoutSeconds<1)throw new IllegalArgumentException("Timeout must be positive.");
        List<String> safeCommand=new ArrayList<>(command); ProcessBuilder builder=new ProcessBuilder(safeCommand);
        builder.directory(workingDirectory); builder.redirectErrorStream(true); builder.environment().put("LANG","C.UTF-8"); if(environment!=null)builder.environment().putAll(environment);
        Process process=builder.start(); if(observer!=null)observer.onStarted(process);
        StreamCollector collector=new StreamCollector(process,logger); Thread outputThread=new Thread(collector,"BuildToolOutputCollector"); outputThread.setDaemon(true); outputThread.start();
        try {
            boolean completed=process.waitFor(timeoutSeconds,TimeUnit.SECONDS);
            if(!completed){process.destroy();if(!process.waitFor(2,TimeUnit.SECONDS))process.destroyForcibly();}
            outputThread.join(3000);
            if(!completed){if(logger!=null)logger.onError("Build tool timed out after "+timeoutSeconds+" seconds.",null);return new Result(-1,true);}
            int exitCode=process.exitValue(); if(exitCode!=0&&logger!=null)logger.onError("Build tool exited with code "+exitCode+".",null);
            return new Result(exitCode,false);
        } finally {
            if(process.isAlive()){process.destroy();if(process.isAlive())process.destroyForcibly();}
            if(observer!=null)observer.onFinished(process);
        }
    }
    private static final class StreamCollector implements Runnable {
        private final Process process; private final BuildLogger logger;
        StreamCollector(Process process,BuildLogger logger){this.process=process;this.logger=logger;}
        @Override public void run(){try(BufferedReader reader=new BufferedReader(new InputStreamReader(process.getInputStream(),StandardCharsets.UTF_8))){String line;while((line=reader.readLine())!=null)if(logger!=null)logger.onInfo(line);}catch(IOException exception){if(logger!=null)logger.onWarning("Could not read build tool output: "+exception.getMessage());}}
    }
}
