package com.example.studiomobile.build.stages;

import com.example.studiomobile.build.BuildLogger;
import com.example.studiomobile.build.BuildProjectFiles;
import com.example.studiomobile.build.BuildStage;
import com.example.studiomobile.build.BuildStageContext;
import com.example.studiomobile.build.InProcessCompilerEngine;
import com.example.studiomobile.build.process.BuildProcessRunner;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** Converts class files and project JARs to DEX with the in-process D8 API. */
public final class DexCompilationStage implements BuildStage {
    private final File tools;
    private final InProcessCompilerEngine compiler;
    public DexCompilationStage(File tools) { this(tools, new BuildProcessRunner()); }
    public DexCompilationStage(File tools, BuildProcessRunner runner) { this.tools = tools; this.compiler = new InProcessCompilerEngine(tools, runner); }
    @Override public String getName() { return "DEX generation (in-process D8)"; }

    @Override public void execute(BuildStageContext context, BuildLogger logger) throws Exception {
        File dexDirectory = new File(context.getWorkingDirectory(), "dex");
        if (!dexDirectory.mkdirs() && !dexDirectory.isDirectory()) throw new IllegalStateException("Could not create DEX directory.");
        File androidJar = com.example.studiomobile.build.AndroidPlatformResolver.resolve(tools, context.getProjectDirectory());
        List<File> programInputs = collectProgramInputs(tools, context);
        int minSdk = readMinSdk(context.getProjectDirectory());
        InProcessCompilerEngine.CompilationOutput result = compiler.dex(programInputs, androidJar, dexDirectory, minSdk,
            logger == null ? null : new InProcessCompilerEngine.DexProgress() { @Override public void report(String message) { logger.onInfo(message); } });
        if (logger != null && !result.getDiagnostics().isEmpty()) logger.onInfo(result.getDiagnostics());
        File classesDex = new File(dexDirectory, "classes.dex");
        if (!result.isSuccessful() || !classesDex.isFile() || classesDex.length() == 0)
            throw new IllegalStateException("In-process D8 failed or did not produce classes.dex. " + result.getDiagnostics());
        context.putOutput("dexDirectory", dexDirectory);
    }

    /**
     * Everything that goes into the DEX: compiled classes, the Kotlin standard library when needed, local JARs and Maven JARs.
     * Shared with {@link CodeShrinkingStage}, so D8 and R8 always see the same program inputs.
     */
    static List<File> collectProgramInputs(File tools, BuildStageContext context) {
        File classes = context.getOutput("classesDirectory");
        if (classes == null || !classes.isDirectory()) throw new IllegalStateException("Compiled class directory is missing.");
        List<File> programInputs = findClassFiles(classes);
        File kotlinStdlib = new File(tools, "kotlin-stdlib.jar");
        List<File> localLibraries = BuildProjectFiles.localLibraries(context.getProjectDirectory());
        List<File> resolvedLibraries = BuildProjectFiles.jarsIn(context.getOutput("dependencyDirectory"));
        // The Kotlin standard library is large; dexing it on a phone is slow and bloats the APK. It is only needed when the
        // project has Kotlin sources, or when it uses libraries (local JARs or Maven artifacts), because those may be written
        // in Kotlin. The Maven resolver deliberately skips kotlin-stdlib, so this copy is the only one that reaches the APK.
        boolean needsKotlinStdlib = usesKotlin(context.getProjectDirectory()) || !localLibraries.isEmpty() || !resolvedLibraries.isEmpty();
        if (needsKotlinStdlib && kotlinStdlib.isFile() && kotlinStdlib.length() > 0) programInputs.add(kotlinStdlib);
        programInputs.addAll(localLibraries);
        programInputs.addAll(resolvedLibraries);
        if (programInputs.isEmpty()) throw new IllegalStateException("No compiled class files or libraries exist; cannot generate DEX.");
        return programInputs;
    }

    static int readMinSdk(File projectDirectory) {
        try { return Integer.parseInt(BuildProjectFiles.readNumericSdkValue(projectDirectory, true, "23")); }
        catch (NumberFormatException error) { throw new IllegalArgumentException("minSdk must be a numeric Android API level.", error); }
    }

    private static boolean usesKotlin(File projectDirectory) {
        File main = BuildProjectFiles.mainDirectory(projectDirectory);
        return !BuildProjectFiles.filesWithExtension(new File(main, "kotlin"), ".kt").isEmpty()
            || !BuildProjectFiles.filesWithExtension(new File(main, "java"), ".kt").isEmpty();
    }

    private static List<File> findClassFiles(File root) { List<File> out = new ArrayList<>(); collect(root, out); return out; }
    private static void collect(File dir, List<File> out) {
        File[] children = dir.listFiles(); if (children == null) return;
        for (File child : children) { if (child.isDirectory()) collect(child, out); else if (child.getName().endsWith(".class")) out.add(child); }
    }
}
