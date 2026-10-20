package com.example.studiomobile.build.stages;

import com.example.studiomobile.build.BuildLogger;
import com.example.studiomobile.build.BuildProjectFiles;
import com.example.studiomobile.build.BuildStage;
import com.example.studiomobile.build.AnnotationProcessingRunner;
import com.example.studiomobile.build.BuildStageContext;
import com.example.studiomobile.build.ProjectModules;
import com.example.studiomobile.build.ClassFileVersionGuard;
import com.example.studiomobile.build.InProcessCompilerEngine;
import com.example.studiomobile.build.JavaLanguageLevel;
import com.example.studiomobile.build.ModuleImportExpander;
import com.example.studiomobile.build.process.BuildProcessRunner;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** Compiles Kotlin and Java through in-process compiler APIs; no external Java launcher is used. */
public final class SourceCompilationStage implements BuildStage {
    private final File tools;
    private final InProcessCompilerEngine compiler;
    public SourceCompilationStage(File tools) { this(tools, new BuildProcessRunner()); }
    public SourceCompilationStage(File tools, BuildProcessRunner runner) { this.tools = tools; this.compiler = new InProcessCompilerEngine(tools, runner); }
    @Override public String getName() { return "In-process Java/Kotlin compilation"; }

    @Override public void execute(BuildStageContext context, BuildLogger logger) throws Exception {
        File classes = new File(context.getWorkingDirectory(), "classes");
        if (!classes.mkdirs() && !classes.isDirectory()) throw new IllegalStateException("Could not create class output directory: " + classes);
        // All modules (libraries first, app last) are compiled together: R classes of every module come from the app's single link step,
        // so a library cannot be compiled before linking, and one ECJ/Kotlin run over the union keeps cross-module references simple.
        ProjectModules modules = ProjectModules.load(context.getProjectDirectory());
        List<File> kotlin = new ArrayList<>();
        List<File> java = new ArrayList<>();
        List<File> javaRoots = new ArrayList<>();
        for (ProjectModules.Module module : modules.all()) {
            for (File root : module.kotlinRoots()) kotlin.addAll(BuildProjectFiles.filesWithExtension(root, ".kt"));
            for (File root : module.javaRoots()) {
                kotlin.addAll(BuildProjectFiles.filesWithExtension(root, ".kt"));
                java.addAll(BuildProjectFiles.filesWithExtension(root, ".java"));
                javaRoots.add(root);
            }
        }
        File generated = context.getOutput("generatedSources");
        if (generated != null) java.addAll(BuildProjectFiles.filesWithExtension(generated, ".java"));

        File androidJar = com.example.studiomobile.build.AndroidPlatformResolver.resolve(tools, context.getProjectDirectory());
        File kotlinStdlib = new File(tools, "kotlin-stdlib.jar");
        List<File> classpath = new ArrayList<>(); classpath.add(androidJar); classpath.add(kotlinStdlib);
        classpath.addAll(BuildProjectFiles.localLibraries(context.getProjectDirectory()));
        classpath.addAll(BuildProjectFiles.jarsIn(context.getOutput("dependencyDirectory")));
        if (generated != null && generated.isDirectory()) javaRoots.add(generated);
        String hint = context.getAttribute("unsupportedProcessors") == null ? "" : "\n" + context.getAttribute("unsupportedProcessors");

        if (!kotlin.isEmpty()) {
            InProcessCompilerEngine.CompilationOutput result = compiler.compileKotlin(kotlin, javaRoots, androidJar, classpath, classes);
            if (logger != null && !result.getDiagnostics().isEmpty()) logger.onInfo(result.getDiagnostics());
            if (!result.isSuccessful()) throw new IllegalStateException("Kotlin compilation failed. Diagnostics: " + result.getDiagnostics() + hint);
            requireSupportedOutput(classes);
        }
        if (!java.isEmpty()) {
            // Java 25 "import module java.base;" cannot be resolved without a JDK module system: expand it to ordinary package imports first.
            ModuleImportExpander.expandAll(java, androidJar, logger);
            List<File> javaClasspath = new ArrayList<>(classpath); javaClasspath.add(classes);
            java.addAll(runAnnotationProcessors(context, logger, java, androidJar, javaClasspath, !kotlin.isEmpty()));
            InProcessCompilerEngine.CompilationOutput result = compiler.compileJava(java, androidJar, javaClasspath, classes);
            if (logger != null && !result.getDiagnostics().isEmpty()) logger.onInfo(result.getDiagnostics());
            if (!result.isSuccessful()) throw new IllegalStateException("Java compilation failed. Diagnostics: " + result.getDiagnostics() + hint);
            requireNoCertainCrash(result.getDiagnostics());
            requireSupportedOutput(classes);
        }
        if (kotlin.isEmpty() && java.isEmpty() && logger != null)
            logger.onWarning("No Java or Kotlin source files found. APK may contain only resources and manifest.");
        context.putOutput("classesDirectory", classes);
    }

    /**
     * Runs the annotationProcessor classpath (if any) over the Java sources with ECJ and returns the sources it generated.
     * Kotlin sources are not visible to processors that need them as input (that is kapt), but compiled Kotlin classes are on the classpath.
     */
    private List<File> runAnnotationProcessors(BuildStageContext context, BuildLogger logger, List<File> javaSources, File androidJar,
                                               List<File> classpath, boolean hasKotlin) throws Exception {
        List<File> processorJars = context.getFiles("processorJars");
        if (processorJars.isEmpty()) return new ArrayList<>();
        if (logger != null) {
            logger.onInfo("Running annotation processors on " + javaSources.size() + " Java source file(s)…");
            if (hasKotlin) logger.onWarning("Annotation processors only read Java sources; Kotlin classes that need them require kapt, which is not available on-device.");
        }
        File work = new File(context.getWorkingDirectory(), "annotation-processing");
        File generatedDirectory = new File(work, "generated"), resourceDirectory = new File(work, "resources");
        AnnotationProcessingRunner runner = new AnnotationProcessingRunner(tools, new File(tools.getParentFile(), "processor-cache"), compiler);
        AnnotationProcessingRunner.Result result = runner.run(processorJars, javaSources, androidJar, classpath, generatedDirectory, resourceDirectory, work);
        if (!result.diagnostics.isEmpty() && logger != null) logger.onInfo(result.diagnostics);
        if (!result.ran) { if (logger != null) logger.onWarning(result.diagnostics); return new ArrayList<>(); }
        if (!result.successful) throw new IllegalStateException("Annotation processing failed: " + result.diagnostics);
        context.putOutput("processorResources", resourceDirectory);
        if (logger != null) logger.onInfo("Annotation processing (" + result.processorNames.size() + " processor(s)) generated " + result.generatedSources.size() + " source file(s).");
        return result.generatedSources;
    }

    /** Warnings are fine, but a warning that proves a crash (definite null dereference) stops the build. */
    private static void requireNoCertainCrash(String diagnostics) {
        List<String> fatal = com.example.studiomobile.build.CrashRiskAnalyzer.findFatalDiagnostics(diagnostics);
        if (fatal.isEmpty()) return;
        StringBuilder message = new StringBuilder("App would crash at runtime; build stopped, no APK was generated.");
        for (String block : fatal) message.append("\n").append(block);
        throw new IllegalStateException(message.toString());
    }

    /** Stops the build if any compiled class is newer than the supported Java level, so a bad APK is never produced. */
    private static void requireSupportedOutput(File classes) {
        List<File> dirs = new ArrayList<>(); dirs.add(classes);
        List<String> problems = ClassFileVersionGuard.findTooNew(dirs, JavaLanguageLevel.REQUIRED_CLASS_MAJOR);
        if (!problems.isEmpty()) throw new IllegalStateException("Compiled output is not Java " + JavaLanguageLevel.REQUIRED + ": " + problems.get(0));
    }
}
