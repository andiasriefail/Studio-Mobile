package com.example.studiomobile.javarun;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.Process;
import android.os.RemoteException;
import com.example.studiomobile.build.AndroidPlatformResolver;
import com.example.studiomobile.build.BuildLogger;
import com.example.studiomobile.build.BuildProjectFiles;
import com.example.studiomobile.build.ClassFileVersionGuard;
import com.example.studiomobile.build.JavaLanguageLevel;
import com.example.studiomobile.build.InProcessCompilerEngine;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Runs one .java or .kt file inside the app: compiles it with the same in-process ECJ / Kotlin compiler and D8 as a real build, then starts the dex in
 * {@link JavaRunService} (a separate process) and streams its output to the logger. The jars of the project's libs folders are on the compile classpath
 * and are dexed with the program. A .java file may use other classes of its own source root (ECJ compiles those on demand); a .kt file is compiled alone.
 */
public final class JavaFileRunner {
    public static final int TIMED_OUT = -1, STOPPED = -2, ENDED_WITHOUT_RESULT = -3, COMPILE_FAILED = -4;

    private final Context app;
    private final File toolsDirectory;
    private final CountDownLatch finished = new CountDownLatch(1);
    private volatile int pid, exitCode = ENDED_WITHOUT_RESULT;
    private volatile boolean cancelled;
    private final StringBuilder partialLine = new StringBuilder();

    public JavaFileRunner(Context app, File toolsDirectory) { this.app = app.getApplicationContext(); this.toolsDirectory = toolsDirectory; }

    /** Stops the program (its process is killed) or, while compiling, makes the run end as soon as it can. */
    public void cancel() {
        cancelled = true;
        int target = pid;
        if (target > 0) Process.killProcess(target);
        finished.countDown();
    }

    public static String describe(int code, long timeoutSeconds) {
        if (code == TIMED_OUT) return "\n[timed out after " + timeoutSeconds + " s and stopped]";
        if (code == STOPPED) return "\n[stopped]";
        if (code == ENDED_WITHOUT_RESULT) return "\n[process ended without finishing main(): System.exit() was called or the process was killed]";
        if (code == COMPILE_FAILED) return "\n[compilation failed]";
        return "\n[process finished with exit code " + code + "]";
    }

    /** @return the exit code (0 or 1), or one of the negative constants. The program's output goes to {@code logger} line by line. */
    public int run(File source, File projectDirectory, long timeoutSeconds, BuildLogger logger) throws Exception {
        String fileName = source.getName();
        boolean kotlin = fileName.toLowerCase(Locale.ROOT).endsWith(".kt");
        String text = new String(Files.readAllBytes(source.toPath()), StandardCharsets.UTF_8);
        String base = fileName.substring(0, fileName.lastIndexOf('.'));
        String packageName = JavaSourceInfo.packageName(text);
        List<String> classNames = kotlin ? JavaSourceInfo.kotlinCandidates(text, base) : JavaSourceInfo.candidates(text, base);
        File root = JavaSourceInfo.sourceRoot(source, packageName);

        // Jars of the project's libs folders: compiled against, and dexed into the program so the run finds them.
        List<File> libraries = new ArrayList<>(BuildProjectFiles.localLibraries(projectDirectory));
        List<String> tooNew = ClassFileVersionGuard.findTooNew(libraries, JavaLanguageLevel.REQUIRED_CLASS_MAJOR);
        if (!tooNew.isEmpty()) { for (String problem : tooNew) logger.onError(problem, null); return COMPILE_FAILED; }

        File work = new File(app.getCacheDir(), "java-run");
        deleteTree(work);
        File classes = new File(work, "classes"), dexDirectory = new File(work, "dex");
        if (!classes.mkdirs() || !dexDirectory.mkdirs()) throw new IOException("Could not create " + work);

        File androidJar = AndroidPlatformResolver.resolve(toolsDirectory, projectDirectory);
        InProcessCompilerEngine engine = new InProcessCompilerEngine(toolsDirectory, null);
        logger.onInfo("Compiling " + fileName + " ...");
        List<File> extraProgramJars = new ArrayList<>(libraries);
        InProcessCompilerEngine.CompilationOutput compiled;
        if (kotlin) {
            File stdlib = new File(toolsDirectory, "kotlin-stdlib.jar");
            if (!stdlib.isFile() || stdlib.length() == 0) { logger.onError("kotlin-stdlib.jar is not installed, so Kotlin cannot run. Restart the app so it installs the toolchain.", null); return COMPILE_FAILED; }
            List<File> classpath = new ArrayList<>(); classpath.add(androidJar); classpath.add(stdlib); classpath.addAll(libraries);
            boolean libsHaveStdlib = false;
            for (File library : libraries) if (library.getName().toLowerCase(Locale.ROOT).startsWith("kotlin-stdlib")) libsHaveStdlib = true;
            if (!libsHaveStdlib) extraProgramJars.add(stdlib);   // the program needs the Kotlin runtime next to it
            compiled = engine.compileKotlin(Collections.singletonList(source), null, androidJar, classpath, classes);
        } else {
            List<File> classpath = new ArrayList<>(); classpath.add(root); classpath.addAll(libraries);
            compiled = engine.compileJava(Collections.singletonList(source), androidJar, classpath, classes);
        }
        if (!compiled.getDiagnostics().isEmpty()) logger.onInfo(compiled.getDiagnostics().trim());
        if (!compiled.isSuccessful()) return COMPILE_FAILED;
        if (cancelled) return STOPPED;

        List<File> programFiles = new ArrayList<>();
        collectClasses(classes, programFiles);
        programFiles.addAll(extraProgramJars);
        engine.dex(programFiles, androidJar, dexDirectory, 26);
        File[] dexFiles = dexDirectory.listFiles((dir, name) -> name.endsWith(".dex"));
        if (dexFiles == null || dexFiles.length == 0) throw new IOException("D8 produced no dex file.");
        Arrays.sort(dexFiles);
        String[] dexPaths = new String[dexFiles.length];
        for (int i = 0; i < dexFiles.length; i++) dexPaths[i] = dexFiles[i].getAbsolutePath();
        if (cancelled) return STOPPED;

        HandlerThread thread = new HandlerThread("java-run-output");
        thread.start();
        Handler replies = new Handler(thread.getLooper());
        Messenger replyTo = new Messenger(new Handler(thread.getLooper(), message -> {
            if (message.what == JavaRunService.MSG_STARTED) pid = message.arg1;
            else if (message.what == JavaRunService.MSG_OUT) emit(logger, message.getData().getString(JavaRunService.KEY_TEXT, ""));
            else if (message.what == JavaRunService.MSG_DONE) { exitCode = message.arg1; finished.countDown(); }
            return true;
        }));
        ServiceConnection connection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                try {
                    Message request = Message.obtain(null, JavaRunService.MSG_RUN);
                    Bundle data = new Bundle();
                    data.putStringArray(JavaRunService.KEY_DEX, dexPaths);
                    data.putStringArray(JavaRunService.KEY_CLASSES, classNames.toArray(new String[0]));
                    data.putStringArray(JavaRunService.KEY_ARGS, new String[0]);
                    request.setData(data);
                    request.replyTo = replyTo;
                    new Messenger(binder).send(request);
                } catch (RemoteException gone) { finished.countDown(); }
            }
            @Override public void onServiceDisconnected(ComponentName name) { finished.countDown(); }
            @Override public void onBindingDied(ComponentName name) { finished.countDown(); }
        };
        boolean bound = false;
        try {
            bound = app.bindService(new Intent(app, JavaRunService.class), connection, Context.BIND_AUTO_CREATE);
            if (!bound) throw new IOException("Could not start the Java runner process.");
            logger.onInfo((kotlin ? "$ kotlin " : "$ java ") + classNames.get(0));
            boolean done = finished.await(timeoutSeconds, TimeUnit.SECONDS);
            int target = pid;
            if (!done && target > 0) Process.killProcess(target);
            CountDownLatch drained = new CountDownLatch(1);   // output that arrived just before the process ended is still queued
            replies.post(drained::countDown);
            drained.await(1, TimeUnit.SECONDS);
            synchronized (partialLine) { if (partialLine.length() > 0) { logger.onInfo(partialLine.toString()); partialLine.setLength(0); } }
            if (cancelled) return STOPPED;
            return done ? exitCode : TIMED_OUT;
        } finally {
            if (bound) { try { app.unbindService(connection); } catch (RuntimeException alreadyGone) { /* the process ended first */ } }
            thread.quitSafely();
        }
    }

    /** The logger adds a line break to every message, so program output is cut into whole lines first. */
    private void emit(BuildLogger logger, String chunk) {
        synchronized (partialLine) {
            partialLine.append(chunk.replace("\r\n", "\n"));
            int newline;
            while ((newline = partialLine.indexOf("\n")) >= 0) {
                logger.onInfo(partialLine.substring(0, newline));
                partialLine.delete(0, newline + 1);
            }
        }
    }

    private static void collectClasses(File directory, List<File> out) {
        File[] children = directory.listFiles();
        if (children == null) return;
        for (File child : children) { if (child.isDirectory()) collectClasses(child, out); else if (child.getName().endsWith(".class")) out.add(child); }
    }

    private static void deleteTree(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        file.delete();
    }
}
