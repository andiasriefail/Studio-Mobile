package com.example.studiomobile.build;

import com.android.tools.r8.D8;
import com.android.tools.r8.D8Command;
import com.android.tools.r8.OutputMode;
import org.eclipse.jdt.core.compiler.batch.BatchCompiler;
import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Arrays;
import com.example.studiomobile.build.process.BuildProcessRunner;

/** Invokes compiler APIs in the Android app process; it never launches a java executable. */
public final class InProcessCompilerEngine {
    private final File toolsDirectory;
    private final BuildProcessRunner processRunner;

    public InProcessCompilerEngine() { this(null, new BuildProcessRunner()); }
    public InProcessCompilerEngine(File toolsDirectory, BuildProcessRunner processRunner) {
        this.toolsDirectory = toolsDirectory;
        this.processRunner = processRunner == null ? new BuildProcessRunner() : processRunner;
    }

    public static final class CompilationOutput {
        private final boolean successful;
        private final String diagnostics;
        CompilationOutput(boolean successful, String diagnostics) {
            this.successful = successful;
            this.diagnostics = diagnostics == null ? "" : diagnostics;
        }
        public boolean isSuccessful() { return successful; }
        public String getDiagnostics() { return diagnostics; }
    }

    public CompilationOutput compileJava(List<File> sourceFiles, File androidJar,
            List<File> classpathEntries, File outputDirectory) throws Exception {
        return compileJava(sourceFiles, androidJar, classpathEntries, outputDirectory, JavaLanguageLevel.REQUIRED);
    }

    public CompilationOutput compileJava(List<File> sourceFiles, File androidJar,
            List<File> classpathEntries, File outputDirectory, int javaLevel) throws Exception {
        if (sourceFiles == null || sourceFiles.isEmpty()) return new CompilationOutput(true, "No Java source files.");
        requireFile(androidJar, "Android platform android.jar");
        if (!outputDirectory.isDirectory() && !outputDirectory.mkdirs())
            throw new IllegalStateException("Could not create Java class output directory: " + outputDirectory);

        List<String> args = new ArrayList<>();
        // ECJ rejects -bootclasspath at compliance 9+, so android.jar is placed
        // first on the regular classpath instead; D8 desugars the classes for the app's minSdk.
        String level = JavaLanguageLevel.flag(javaLevel);
        args.add("-source"); args.add(level);
        args.add("-target"); args.add(level);
        args.add("-encoding"); args.add("UTF-8");
        args.add("-proc:none");
        args.add("-warn:+nullDereference"); // definite null access is reported, then stopped by CrashRiskAnalyzer
        List<File> compileClasspath = new ArrayList<>();
        compileClasspath.add(androidJar);
        File compileStubs = CompileStubs.extract(outputDirectory.getParentFile());
        if (compileStubs != null) compileClasspath.add(compileStubs);
        if (classpathEntries != null) for (File entry : classpathEntries) if (entry != null && !entry.equals(androidJar)) compileClasspath.add(entry);
        args.add("-classpath"); args.add(joinClasspath(compileClasspath));
        args.add("-d"); args.add(outputDirectory.getAbsolutePath());
        for (File source : sourceFiles) { requireFile(source, "Java source"); args.add(source.getAbsolutePath()); }

        StringWriter stdout = new StringWriter();
        StringWriter stderr = new StringWriter();
        String diagnostics;
        try {
            boolean ok = BatchCompiler.compile(args.toArray(new String[0]),
                    new PrintWriter(stdout, true), new PrintWriter(stderr, true), null);
            diagnostics = stdout.toString() + stderr.toString();
            if (ok && containsClass(outputDirectory)) return new CompilationOutput(true, diagnostics);
            if (ok) diagnostics += "\nECJ returned success but produced no .class files.";
            diagnostics = "ECJ in-process API returned compilation failure (Java " + level + "). " + diagnostics;
        } catch (Exception | LinkageError error) {
            diagnostics = "ECJ in-process API unavailable: " + error.getClass().getName() + ": " + error.getMessage() + stackSummary(error, 10);
        }
        CompilationOutput fallback = compileJavaFallback(args, sourceFiles, androidJar, classpathEntries, outputDirectory, diagnostics, level);
        if (fallback != null) return fallback;
        return new CompilationOutput(false, diagnostics + "\nNo usable Java compiler fallback found. Expected executable build-tools/ecj or build-tools/javac.");
    }

    /** First frames of the throwable and its causes, so a failure inside ECJ shows which ECJ class hit the missing platform API. */
    static String stackSummary(Throwable error, int maxFrames) {
        StringBuilder text = new StringBuilder();
        int depth = 0;
        for (Throwable t = error; t != null && depth < 3; t = t.getCause(), depth++) {
            if (depth > 0) text.append("\nCaused by: ").append(t.getClass().getName()).append(": ").append(t.getMessage());
            StackTraceElement[] frames = t.getStackTrace();
            for (int i = 0; i < frames.length && i < maxFrames; i++) text.append("\n  at ").append(frames[i]);
            if (t.getCause() == t) break;
        }
        return text.toString();
    }

    /** Runs D8 directly through its public API, not through a java process. */
    public CompilationOutput dex(List<File> programFiles, File androidJar, File outputDirectory,
            int minSdk) throws Exception {
        return dex(programFiles, Arrays.asList(androidJar), outputDirectory, minSdk);
    }

    /** Same as above with several library jars (android.jar plus, for annotation processors, the bundled javax API jar). */
    public CompilationOutput dex(List<File> programFiles, List<File> libraryFiles, File outputDirectory,
            int minSdk) throws Exception {
        return dex(programFiles, libraryFiles, outputDirectory, minSdk, null);
    }

    /** Receives short progress messages while DEX is generated, for example "[dex] start 3/13 poi-5.2.5.jar". */
    public interface DexProgress { void report(String message); }

    public CompilationOutput dex(List<File> programFiles, File androidJar, File outputDirectory, int minSdk, DexProgress progress) throws Exception {
        return dex(programFiles, Arrays.asList(androidJar), outputDirectory, minSdk, progress);
    }

    public CompilationOutput dex(List<File> programFiles, List<File> libraryFiles, File outputDirectory,
            int minSdk, DexProgress progress) throws Exception {
        File androidJar = libraryFiles == null || libraryFiles.isEmpty() ? null : libraryFiles.get(0);
        if (programFiles == null || programFiles.isEmpty())
            throw new IllegalArgumentException("D8 needs at least one class/JAR input.");
        requireFile(androidJar, "Android platform android.jar");
        if (minSdk < 1) throw new IllegalArgumentException("minSdk must be a positive API level.");
        if (!outputDirectory.isDirectory() && !outputDirectory.mkdirs())
            throw new IllegalStateException("Could not create DEX output directory: " + outputDirectory);

        // Never let D8 success be inferred from artifacts left by a previous build.
        deleteDexFiles(outputDirectory);
        D8Command.Builder builder = D8Command.builder()
                .setMinApiLevel(minSdk)
                .setOutput(outputDirectory.toPath(), OutputMode.DexIndexed)
                .addLibraryFiles(androidJar.toPath());
        for (File library : libraryFiles.subList(1, libraryFiles.size())) { requireFile(library, "D8 library input"); builder.addLibraryFiles(library.toPath()); }
        for (File program : programFiles) {
            requireFile(program, "D8 program input");
            builder.addProgramFiles(program.toPath());
        }
        // D8 defaults to one worker per CPU core. On a phone every worker holds parts of the program in memory at once, and the app
        // heap is only 256 MB, so a big library such as Apache POI filled the heap, the garbage collector ran non-stop and the whole
        // app froze (Android's "not responding"). Two workers are slower but keep the peak memory low.
        java.util.concurrent.ExecutorService workers = java.util.concurrent.Executors.newFixedThreadPool(2, new java.util.concurrent.ThreadFactory() {
            @Override public Thread newThread(Runnable task) { Thread thread = new Thread(task, "studio-d8-worker"); thread.setDaemon(true); return thread; }
        });
        try {
            System.gc();   // free what the Java compile step left behind before D8 starts
            if (shouldSplit(programFiles)) {
                try {
                    dexInStages(programFiles, libraryFiles, outputDirectory, minSdk, workers, progress);
                } catch (com.android.tools.r8.CompilationFailedException stagedFailure) {
                    // The low-memory route is an optimisation: if it fails for any reason other than memory, try the normal single run.
                    if (hasCause(stagedFailure, OutOfMemoryError.class)) throw stagedFailure;
                    deleteDexFiles(outputDirectory);
                    System.gc();
                    D8.run(builder.build(), workers);
                }
            } else D8.run(builder.build(), workers);
            if (containsDex(outputDirectory)) return new CompilationOutput(true, "D8 in-process API succeeded.");
            throw new IllegalStateException("D8 API returned without producing a DEX file.");
        } catch (OutOfMemoryError outOfMemory) {
            Runtime rt = Runtime.getRuntime();
            throw new IllegalStateException("D8 ran out of memory (heap max " + (rt.maxMemory() >> 20) + " MB). The project's libraries are too large to convert to DEX in one go on this device.", outOfMemory);
        } catch (Exception | LinkageError error) {
            String primaryError = error.getClass().getName() + ": " + String.valueOf(error.getMessage());
            CompilationOutput fallback = runD8Fallback(programFiles, androidJar, outputDirectory, minSdk, primaryError);
            if (fallback != null && fallback.isSuccessful()) return fallback;
            throw new IllegalStateException("In-process D8 failed and executable fallback was unavailable or failed. " +
                    primaryError + (fallback == null ? "" : "\\nFallback: " + fallback.getDiagnostics()), error);
        } finally {
            workers.shutdownNow();
        }
    }

    /** Libraries above this total size are dexed one JAR at a time (see {@link #dexInStages}). */
    private static final long SPLIT_THRESHOLD_BYTES = 3L * 1024 * 1024;

    private static boolean shouldSplit(List<File> programFiles) {
        long jarBytes = 0; int jars = 0;
        for (File file : programFiles) if (file.getName().endsWith(".jar")) { jars++; jarBytes += file.length(); }
        return jars >= 2 && jarBytes >= SPLIT_THRESHOLD_BYTES;
    }

    /**
     * Low-memory DEX: the compiled classes and each library JAR are dexed on their own (intermediate output), then the small
     * intermediate DEX files are merged. D8 never holds more than one JAR's program in memory, so the peak is a fraction of
     * dexing everything in one run. The other JARs are given as classpath so Java 8+ features still desugar correctly, and
     * global synthetics are passed along so the merge has everything it needs.
     */
    private void dexInStages(List<File> programFiles, List<File> libraryFiles, File outputDirectory, int minSdk,
            java.util.concurrent.ExecutorService workers, DexProgress progress) throws Exception {
        File stageRoot = new File(outputDirectory.getParentFile() == null ? outputDirectory : outputDirectory.getParentFile(), "dex-stages");
        deleteTree(stageRoot);
        if (!stageRoot.mkdirs()) throw new IllegalStateException("Could not create " + stageRoot);

        List<File> jars = new ArrayList<>();
        List<File> others = new ArrayList<>();   // .class files from the project
        for (File file : programFiles) { if (file.getName().endsWith(".jar")) jars.add(file); else others.add(file); }

        List<File> intermediateDex = new ArrayList<>();
        List<File> globalSynthetics = new ArrayList<>();
        int index = 0;
        final int total = jars.size() + (others.isEmpty() ? 0 : 1);
        if (!others.isEmpty()) {
            long began = System.currentTimeMillis();
            say(progress, "[dex] start " + (index + 1) + "/" + total + " project classes");
            runStage(others, jars, libraryFiles, new File(stageRoot, "s" + index++), minSdk, workers, intermediateDex, globalSynthetics);
            say(progress, "[dex] done " + index + "/" + total + " project classes (" + (System.currentTimeMillis() - began) + " ms)");
        }
        for (File jar : jars) {
            long began = System.currentTimeMillis();
            say(progress, "[dex] start " + (index + 1) + "/" + total + " " + jar.getName());
            List<File> classpath = new ArrayList<>(jars); classpath.remove(jar);
            runStage(java.util.Collections.singletonList(jar), classpath, libraryFiles, new File(stageRoot, "s" + index++), minSdk, workers, intermediateDex, globalSynthetics);
            say(progress, "[dex] done " + index + "/" + total + " " + jar.getName() + " (" + (System.currentTimeMillis() - began) + " ms)");
            System.gc();
        }
        say(progress, "[dex] merging " + total + " parts");
        if (intermediateDex.isEmpty()) throw new IllegalStateException("Staged D8 produced no intermediate DEX files.");

        D8Command.Builder merge = D8Command.builder()
                .setMinApiLevel(minSdk)
                .setOutput(outputDirectory.toPath(), OutputMode.DexIndexed)
                .addLibraryFiles(libraryFiles.get(0).toPath());
        for (File library : libraryFiles.subList(1, libraryFiles.size())) merge.addLibraryFiles(library.toPath());
        for (File dex : intermediateDex) merge.addProgramFiles(dex.toPath());
        for (File synthetic : globalSynthetics) merge.addGlobalSyntheticsFiles(synthetic.toPath());
        D8.run(merge.build(), workers);
        deleteTree(stageRoot);
    }

    private static void say(DexProgress progress, String message) { if (progress != null) progress.report(message); }

    private static void runStage(List<File> program, List<File> classpath, List<File> libraryFiles, File stageDir, int minSdk,
            java.util.concurrent.ExecutorService workers, List<File> intermediateDex, List<File> globalSynthetics) throws Exception {
        if (!stageDir.mkdirs()) throw new IllegalStateException("Could not create " + stageDir);
        // R8 only recognises a global-synthetics file by its extension: ".zip" is accepted, other names (".bin", ".globals", ".jar") are rejected
        // with "Invalid global synthetics provider does not specify its content kind".
        File synthetics = new File(stageDir, "globals.zip");
        D8Command.Builder stage = D8Command.builder()
                .setMinApiLevel(minSdk)
                .setIntermediate(true)
                .setOutput(stageDir.toPath(), OutputMode.DexIndexed)
                .setGlobalSyntheticsOutput(synthetics.toPath())
                .addLibraryFiles(libraryFiles.get(0).toPath());
        for (File library : libraryFiles.subList(1, libraryFiles.size())) stage.addLibraryFiles(library.toPath());
        for (File file : program) stage.addProgramFiles(file.toPath());
        for (File file : classpath) if (file.getName().endsWith(".jar")) stage.addClasspathFiles(file.toPath());
        D8.run(stage.build(), workers);
        File[] produced = stageDir.listFiles();
        if (produced != null) {
            java.util.Arrays.sort(produced);
            for (File file : produced) if (file.getName().endsWith(".dex")) intermediateDex.add(file);
        }
        if (synthetics.isFile() && synthetics.length() > 0) globalSynthetics.add(synthetics);
    }

    private static boolean hasCause(Throwable error, Class<? extends Throwable> type) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) if (type.isInstance(cause)) return true;
        return false;
    }

    private static void deleteTree(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        file.delete();
    }

    /** Invokes the embeddable Kotlin CLI in-process. Diagnostics are captured for the build log. */
    public CompilationOutput compileKotlin(List<File> kotlinSources, List<File> javaSourceRoots,
            File androidJar, List<File> classpathEntries, File outputDirectory) throws Exception {
        return compileKotlin(kotlinSources, javaSourceRoots, androidJar, classpathEntries, outputDirectory, JavaLanguageLevel.REQUIRED);
    }

    public CompilationOutput compileKotlin(List<File> kotlinSources, List<File> javaSourceRoots,
            File androidJar, List<File> classpathEntries, File outputDirectory, int javaLevel) throws Exception {
        if (kotlinSources == null || kotlinSources.isEmpty()) return new CompilationOutput(true, "No Kotlin source files.");
        requireFile(androidJar, "Android platform android.jar");
        if (!outputDirectory.isDirectory() && !outputDirectory.mkdirs())
            throw new IllegalStateException("Could not create Kotlin class output directory: " + outputDirectory);

        // Kotlin runs before Java in this pipeline; remove stale class outputs before compiling.
        clearClassFiles(outputDirectory);
        List<String> args = new ArrayList<>();
        args.add("-no-stdlib"); args.add("-no-reflect");
        // On Android the compiler is dexed, so PathUtil cannot find its own class as a jar resource
        // ("Resource not found: /org/jetbrains/kotlin/utils/PathUtil.class"). An explicit -kotlin-home skips that lookup.
        File kotlinHome = new File(toolsDirectory != null ? toolsDirectory : outputDirectory, toolsDirectory != null ? "kotlin-home" : ".kotlin-home");
        kotlinHome.mkdirs();
        args.add("-kotlin-home"); args.add(kotlinHome.getAbsolutePath());
        args.add("-jvm-target"); args.add(JavaLanguageLevel.flag(javaLevel));
        // Kotlin 2.4.x (checked with 2.4.21) compiles `when` with several `is` checks to invokedynamic (SwitchBootstraps.typeSwitch) at JVM 21+, which Android does not have.
        // Keep the classic instanceof chain so the DEX never depends on it.
        args.add("-Xwhen-expressions=inline");
        args.add("-classpath"); args.add(joinClasspath(classpathEntries));
        args.add("-d"); args.add(outputDirectory.getAbsolutePath());
        if (javaSourceRoots != null) for (File root : javaSourceRoots)
            if (root != null && root.isDirectory()) args.add("-Xjava-source-roots=" + root.getAbsolutePath());
        for (File source : kotlinSources) { requireFile(source, "Kotlin source"); args.add(source.getAbsolutePath()); }

        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        java.io.PrintStream stream = new java.io.PrintStream(output, true, "UTF-8");
        try {
            org.jetbrains.kotlin.cli.jvm.K2JVMCompiler compiler = new org.jetbrains.kotlin.cli.jvm.K2JVMCompiler();
            org.jetbrains.kotlin.cli.common.ExitCode result = compiler.exec(stream, args.toArray(new String[0]));
            stream.flush();
            boolean ok = result == org.jetbrains.kotlin.cli.common.ExitCode.OK;
            String diagnostics = output.toString("UTF-8");
            if (ok && containsClass(outputDirectory)) return new CompilationOutput(true, diagnostics);
            if (ok) diagnostics += "\nKotlin compiler returned OK but produced no .class files.";
            CompilationOutput fallback = compileKotlinFallback(args, outputDirectory,
                    "Kotlin in-process compiler returned " + result + ": " + diagnostics);
            if (fallback != null && fallback.isSuccessful()) return fallback;
            return new CompilationOutput(false, "Kotlin in-process compiler returned " + result + ": " +
                    diagnostics + (fallback == null ? "" : "\\nFallback: " + fallback.getDiagnostics()));
        } catch (Exception | LinkageError error) {
            stream.flush();
            String primary = error.getClass().getName() + ": " + String.valueOf(error.getMessage()) +
                    "\\n" + output.toString("UTF-8");
            CompilationOutput fallback = compileKotlinFallback(args, outputDirectory, primary);
            if (fallback != null && fallback.isSuccessful()) return fallback;
            throw new IllegalStateException("In-process Kotlin compiler failed and fallback was unavailable or failed: " +
                    primary + (fallback == null ? "" : "\\nFallback: " + fallback.getDiagnostics()), error);
        } finally { stream.close(); }
    }

    private CompilationOutput compileKotlinFallback(List<String> args, File outputDirectory, String primaryError) {
        if (toolsDirectory == null) return null;
        File executable = BuildExecutableResolver.resolve(toolsDirectory, "kotlinc");
        if (executable == null) return null;
        try {
            clearClassFiles(outputDirectory);
            List<String> command = new ArrayList<>(); command.add(executable.getAbsolutePath()); command.addAll(args);
            BuildProcessRunner.Result result = processRunner.run(command, outputDirectory, 240, null);
            if (result.isSuccessful() && containsClass(outputDirectory))
                return new CompilationOutput(true, "Kotlin executable fallback succeeded. Primary API issue: " + primaryError);
            return new CompilationOutput(false, "kotlinc fallback failed with exit code " + result.getExitCode() +
                    (result.isTimedOut() ? " (timed out)." : "."));
        } catch (Exception fallbackError) {
            return new CompilationOutput(false, "Kotlin fallback failed: " + fallbackError.getClass().getName() + ": " + fallbackError.getMessage());
        }
    }


    private CompilationOutput compileJavaFallback(List<String> originalArgs, List<File> sourceFiles,
            File androidJar, List<File> classpathEntries, File outputDirectory, String primaryError, String level) {
        if (toolsDirectory == null) return null;
        File ecj = new File(toolsDirectory, "ecj");
        File javac = new File(toolsDirectory, "javac");
        File executable = ecj.isFile() && ecj.canExecute() ? ecj : (javac.isFile() && javac.canExecute() ? javac : null);
        if (executable == null) return null;
        try {
            clearClassFiles(outputDirectory);
            List<String> command = new ArrayList<>();
            command.add(executable.getAbsolutePath());
            if (executable.getName().equals("javac")) {
                command.add("-source"); command.add(level);
                command.add("-target"); command.add(level);
                command.add("-proc:none");
                command.add("-classpath"); command.add(joinClasspath(classpathEntries));
                command.add("-d"); command.add(outputDirectory.getAbsolutePath());
                for (File source : sourceFiles) command.add(source.getAbsolutePath());
            } else {
                command.addAll(originalArgs);
                // ECJ CLI does not accept the BatchCompiler API's separate argument array semantics differently.
            }
            BuildProcessRunner.Result result = processRunner.run(command,
                    outputDirectory, 180, null);
            if (result.isSuccessful() && containsClass(outputDirectory))
                return new CompilationOutput(true, "Java compiler fallback succeeded using " + executable.getName() +
                        ". Primary API issue: " + primaryError);
            return new CompilationOutput(false, "Fallback " + executable.getName() + " failed with exit code " +
                    result.getExitCode() + (result.isTimedOut() ? " (timed out)." : "."));
        } catch (Exception error) {
            return new CompilationOutput(false, "Java fallback failed: " + error.getClass().getName() + ": " + error.getMessage());
        }
    }

    private CompilationOutput runD8Fallback(List<File> programFiles, File androidJar, File outputDirectory,
            int minSdk, String primaryError) {
        if (toolsDirectory == null) return null;
        File executable = BuildExecutableResolver.resolve(toolsDirectory, "d8");
        if (executable == null) return null;
        try {
            deleteDexFiles(outputDirectory);
            List<String> command = new ArrayList<>();
            command.add(executable.getAbsolutePath());
            command.add("--min-api"); command.add(String.valueOf(minSdk));
            command.add("--lib"); command.add(androidJar.getAbsolutePath());
            command.add("--output"); command.add(outputDirectory.getAbsolutePath());
            for (File program : programFiles) command.add(program.getAbsolutePath());
            BuildProcessRunner.Result result = processRunner.run(command, outputDirectory, 240, null);
            if (result.isSuccessful() && containsDex(outputDirectory))
                return new CompilationOutput(true, "D8 executable fallback succeeded. Primary API issue: " + primaryError);
            return new CompilationOutput(false, "D8 executable fallback failed with exit code " +
                    result.getExitCode() + (result.isTimedOut() ? " (timed out)." : "."));
        } catch (Exception error) {
            return new CompilationOutput(false, "D8 executable fallback failed: " + error.getClass().getName() + ": " + error.getMessage());
        }
    }

    private static boolean containsClass(File directory) { return containsExtension(directory, ".class"); }
    private static boolean containsDex(File directory) { return containsExtension(directory, ".dex"); }
    private static boolean containsExtension(File directory, String extension) {
        File[] files = directory.listFiles();
        if (files == null) return false;
        for (File file : files) {
            if (file.isDirectory() && containsExtension(file, extension)) return true;
            if (file.isFile() && file.getName().endsWith(extension) && file.length() > 0) return true;
        }
        return false;
    }
    private static void clearClassFiles(File directory) { deleteByExtension(directory, ".class"); }
    private static void deleteDexFiles(File directory) { deleteByExtension(directory, ".dex"); }
    private static void deleteByExtension(File directory, String extension) {
        File[] files = directory.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (file.isDirectory()) deleteByExtension(file, extension);
            else if (file.getName().endsWith(extension)) file.delete();
        }
    }

    private static String joinClasspath(List<File> files) {
        StringBuilder result = new StringBuilder();
        if (files != null) for (File file : files) {
            if (file == null || !file.exists()) continue;
            if (result.length() > 0) result.append(File.pathSeparator);
            result.append(file.getAbsolutePath());
        }
        return result.toString();
    }

    private static void requireFile(File file, String description) {
        if (file == null || !file.isFile() || file.length() == 0)
            throw new IllegalArgumentException(description + " is missing or empty: " + file);
    }
}
