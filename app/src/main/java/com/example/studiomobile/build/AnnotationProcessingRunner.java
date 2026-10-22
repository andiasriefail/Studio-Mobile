package com.example.studiomobile.build;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.lang.reflect.Constructor;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;

/**
 * Runs Java annotation processors (Dagger, AutoValue, Glide, ButterKnife, MapStruct, ...) on the phone.
 *
 * How it works, because neither javac nor a JVM class loader exists on Android:
 * <ol>
 *   <li>The processor jars (annotationProcessor dependencies and their transitive jars) are converted to DEX with D8 and zipped,
 *       together with their non-class resources, into one read-only archive that is cached on disk.</li>
 *   <li>The archive is loaded with {@code dalvik.system.DexClassLoader}; processors are found through
 *       {@code META-INF/services/javax.annotation.processing.Processor} and instantiated.</li>
 *   <li>ECJ's {@code javax.tools.JavaCompiler} implementation is run with {@code -proc:only}; the instantiated processors are handed
 *       over with {@code CompilationTask.setProcessors}, so no class loading happens inside ECJ. Generated sources land in
 *       {@code generatedSourceDirectory} and are compiled by the normal Java stage afterwards.</li>
 * </ol>
 * The {@code javax.annotation.processing / javax.lang.model / javax.tools} API classes that Android lacks are bundled with the app
 * (assets/build-tools/java-compiler-api.jar, compiled into Studio Mobile and also used as the D8 library).
 *
 * On a desktop JVM (unit tests) the same code runs: no DexClassLoader exists, so a URLClassLoader over the original jars is used and
 * the JDK compiler stands in for ECJ.
 *
 * Limits: processors that need javac internals (Lombok), native code (Room's sqlite-jdbc verification) or Kotlin metadata tooling
 * are not expected to work; kapt and KSP are separate Kotlin compiler plugins and are not run here.
 */
public final class AnnotationProcessingRunner {
    private static final String PROCESSOR_TYPE = "javax.annotation.processing.Processor";
    private static final String SERVICE = "META-INF/services/javax.annotation.processing.Processor";
    private static final String ECJ_COMPILER = "org.eclipse.jdt.internal.compiler.tool.EclipseCompiler";
    /** Lowest device API the app supports; processor DEX is generated for it. */
    private static final int PROCESSOR_DEX_API = 26;

    public static final class Result {
        public final boolean ran, successful;
        public final String diagnostics;
        public final List<File> generatedSources;
        public final List<String> processorNames;
        Result(boolean ran, boolean successful, String diagnostics, List<File> generatedSources, List<String> processorNames) {
            this.ran = ran; this.successful = successful; this.diagnostics = diagnostics == null ? "" : diagnostics;
            this.generatedSources = generatedSources; this.processorNames = processorNames;
        }
    }

    private final File toolsDirectory;
    private final File cacheDirectory;
    private final InProcessCompilerEngine dexEngine;
    private final JavaCompiler compilerOverride;

    public AnnotationProcessingRunner(File toolsDirectory, File cacheDirectory, InProcessCompilerEngine dexEngine) { this(toolsDirectory, cacheDirectory, dexEngine, null); }

    /** {@code compilerOverride} lets tests pass the JDK compiler; production passes null and ECJ is used. */
    AnnotationProcessingRunner(File toolsDirectory, File cacheDirectory, InProcessCompilerEngine dexEngine, JavaCompiler compilerOverride) {
        this.toolsDirectory = toolsDirectory; this.cacheDirectory = cacheDirectory; this.dexEngine = dexEngine; this.compilerOverride = compilerOverride;
    }

    /**
     * @param processorJars         every jar on the annotationProcessor classpath (processors and what they depend on)
     * @param javaSources           all Java sources to analyse (project, R, BuildConfig, bindings)
     * @param classpath             compile classpath of the project (android.jar first) plus compiled Kotlin classes
     * @param generatedSourceDir    receives the generated .java files
     * @param resourceOutputDir     receives generated non-source files (META-INF/services/..., schemas)
     */
    public Result run(List<File> processorJars, List<File> javaSources, File androidJar, List<File> classpath,
                      File generatedSourceDir, File resourceOutputDir, File workDirectory) throws Exception {
        return run(processorJars, javaSources, androidJar, classpath, generatedSourceDir, resourceOutputDir, workDirectory, JavaLanguageLevel.REQUIRED);
    }

    public Result run(List<File> processorJars, List<File> javaSources, File androidJar, List<File> classpath,
                      File generatedSourceDir, File resourceOutputDir, File workDirectory, int javaLevel) throws Exception {
        List<String> names = discoverProcessorNames(processorJars);
        if (names.isEmpty()) return new Result(false, true, "No annotation processors found in the annotationProcessor classpath.", Collections.<File>emptyList(), names);
        for (File directory : new File[] { generatedSourceDirectory(generatedSourceDir), resourceOutputDir, workDirectory })
            if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Could not create " + directory);

        ClassLoader loader = createProcessorClassLoader(processorJars, androidJar, workDirectory);
        // javax.annotation.processing.Processor is deliberately not referenced as a compile-time type: with the JDK image Gradle builds
        // from android.jar (--system=none), that package is not resolvable from the bundled API jar. It is used reflectively instead.
        final Class<?> processorType = Class.forName(PROCESSOR_TYPE, false, AnnotationProcessingRunner.class.getClassLoader());
        List<Object> processors = new ArrayList<>();
        StringBuilder problems = new StringBuilder();
        for (String name : names) {
            try {
                Class<?> type = Class.forName(name, true, loader);
                Constructor<?> constructor = type.getDeclaredConstructor();
                constructor.setAccessible(true);
                Object instance = constructor.newInstance();
                if (!processorType.isInstance(instance)) throw new ClassCastException(name + " does not implement " + PROCESSOR_TYPE);
                processors.add(instance);
            } catch (Throwable failure) {
                problems.append("Annotation processor ").append(name).append(" could not be loaded: ").append(failure).append('\n');
            }
        }
        if (processors.isEmpty()) return new Result(true, false, problems.toString(), Collections.<File>emptyList(), names);

        JavaCompiler compiler = compilerOverride != null ? compilerOverride : newCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        StringWriter console = new StringWriter();
        File scratchClasses = new File(workDirectory, "apt-classes");
        scratchClasses.mkdirs();
        boolean success;
        try (StandardJavaFileManager files = compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
            // Same classpath trick as the normal compile: java.lang.invoke stubs after android.jar so Java 17 sources resolve.
            List<File> effectiveClasspath = new ArrayList<>(classpath);
            File stubs = CompileStubs.extract(workDirectory);
            if (stubs != null) effectiveClasspath.add(Math.min(1, effectiveClasspath.size()), stubs);
            List<String> options = new ArrayList<>(Arrays.asList(
                "-proc:only", "-source", JavaLanguageLevel.flag(javaLevel), "-target", JavaLanguageLevel.flag(javaLevel),
                "-encoding", "UTF-8", "-nowarn",
                "-classpath", joinClasspath(effectiveClasspath),
                "-d", scratchClasses.getAbsolutePath(),
                "-s", generatedSourceDir.getAbsolutePath()));
            Iterable<? extends JavaFileObject> units = files.getJavaFileObjectsFromFiles(javaSources);
            JavaCompiler.CompilationTask task = compiler.getTask(console, files, diagnostics, options, null, units);
            // CompilationTask.setProcessors(Iterable<? extends Processor>), called reflectively (see processorType above).
            JavaCompiler.CompilationTask.class.getMethod("setProcessors", Iterable.class).invoke(task, processors);
            Boolean result = task.call();
            success = result != null && result;
        } catch (RuntimeException failure) {
            // A processor that throws surfaces as a RuntimeException out of call(); keep the cause visible.
            return new Result(true, false, problems + "Annotation processing crashed: " + describe(failure) + "\n" + format(diagnostics, console), Collections.<File>emptyList(), names);
        }
        copyNonClassFiles(scratchClasses, resourceOutputDir);
        List<File> generated = BuildProjectFiles.filesWithExtension(generatedSourceDir, ".java");
        return new Result(true, success, problems + format(diagnostics, console), generated, names);
    }

    private static File generatedSourceDirectory(File directory) { return directory; }

    // -------------------------------------------------------------------------------------------------------------------------

    static List<String> discoverProcessorNames(List<File> jars) throws IOException {
        Set<String> names = new LinkedHashSet<>();
        for (File jar : jars) {
            if (!jar.isFile()) continue;
            try (ZipFile zip = new ZipFile(jar)) {
                ZipEntry entry = zip.getEntry(SERVICE);
                if (entry == null) continue;
                try (InputStream in = zip.getInputStream(entry)) {
                    for (String line : new String(readAll(in), StandardCharsets.UTF_8).split("\\r?\\n")) {
                        int hash = line.indexOf('#');
                        String name = (hash >= 0 ? line.substring(0, hash) : line).trim();
                        if (!name.isEmpty()) names.add(name);
                    }
                }
            }
        }
        return new ArrayList<>(names);
    }

    private ClassLoader createProcessorClassLoader(List<File> jars, File androidJar, File workDirectory) throws Exception {
        ClassLoader parent = AnnotationProcessingRunner.class.getClassLoader();
        Class<?> dexLoader = null;
        try { dexLoader = Class.forName("dalvik.system.DexClassLoader"); } catch (ClassNotFoundException notAndroid) { /* desktop JVM */ }
        if (dexLoader == null) {
            List<URL> urls = new ArrayList<>();
            for (File jar : jars) urls.add(jar.toURI().toURL());
            return new URLClassLoader(urls.toArray(new URL[0]), parent);
        }
        File archive = dexArchive(jars, androidJar);
        File optimized = new File(workDirectory, "odex");
        optimized.mkdirs();
        // Android 14+ refuses to load code from a writable file.
        if (archive.canWrite()) archive.setReadOnly();
        Constructor<?> constructor = dexLoader.getConstructor(String.class, String.class, String.class, ClassLoader.class);
        return (ClassLoader) constructor.newInstance(archive.getAbsolutePath(), optimized.getAbsolutePath(), null, parent);
    }

    /** A zip holding classes*.dex (from D8) plus the non-class resources of all processor jars, cached by content fingerprint. */
    private File dexArchive(List<File> jars, File androidJar) throws Exception {
        if (!cacheDirectory.isDirectory() && !cacheDirectory.mkdirs()) throw new IOException("Could not create " + cacheDirectory);
        File archive = new File(cacheDirectory, "processors-" + fingerprint(jars) + ".jar");
        if (archive.isFile() && archive.length() > 0) return archive;

        File dexDirectory = new File(cacheDirectory, "dex-" + archive.getName() + ".tmp");
        deleteTree(dexDirectory);
        List<File> libraries = new ArrayList<>();
        libraries.add(androidJar);
        File api = new File(toolsDirectory, "java-compiler-api.jar");
        if (api.isFile() && api.length() > 0) libraries.add(api);
        InProcessCompilerEngine.CompilationOutput dex = dexEngine.dex(jars, libraries, dexDirectory, PROCESSOR_DEX_API);
        if (!dex.isSuccessful()) throw new IllegalStateException("Could not convert the annotation processors to DEX: " + dex.getDiagnostics());

        File temporary = new File(cacheDirectory, archive.getName() + ".tmp");
        Set<String> written = new HashSet<>();
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(temporary))) {
            File[] dexFiles = dexDirectory.listFiles((parent, name) -> name.endsWith(".dex"));
            if (dexFiles == null || dexFiles.length == 0) throw new IllegalStateException("D8 produced no DEX for the annotation processors.");
            Arrays.sort(dexFiles);
            for (File dexFile : dexFiles) { out.putNextEntry(new ZipEntry(dexFile.getName())); written.add(dexFile.getName()); Files.copy(dexFile.toPath(), out); out.closeEntry(); }
            for (File jar : jars) {
                try (ZipFile zip = new ZipFile(jar)) {
                    Enumeration<? extends ZipEntry> entries = zip.entries();
                    while (entries.hasMoreElements()) {
                        ZipEntry entry = entries.nextElement();
                        String name = entry.getName();
                        if (entry.isDirectory() || name.endsWith(".class") || name.startsWith("META-INF/MANIFEST") || name.endsWith(".SF") || name.endsWith(".RSA") || name.endsWith(".DSA")) continue;
                        if (name.contains("..") || name.startsWith("/") || !written.add(name)) continue;
                        out.putNextEntry(new ZipEntry(name));
                        try (InputStream in = zip.getInputStream(entry)) { copy(in, out); }
                        out.closeEntry();
                    }
                }
            }
        }
        deleteTree(dexDirectory);
        Files.move(temporary.toPath(), archive.toPath(), StandardCopyOption.REPLACE_EXISTING);
        return archive;
    }

    private static String fingerprint(List<File> jars) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update("processors-v1".getBytes(StandardCharsets.UTF_8));
        for (File jar : jars) digest.update((jar.getName() + ":" + jar.length() + ":" + jar.lastModified() + ";").getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        byte[] bytes = digest.digest();
        for (int i = 0; i < 12; i++) hex.append(String.format("%02x", bytes[i]));
        return hex.toString();
    }

    private JavaCompiler newCompiler() throws Exception {
        try {
            return (JavaCompiler) Class.forName(ECJ_COMPILER).getDeclaredConstructor().newInstance();
        } catch (ClassNotFoundException | LinkageError missing) {
            try { // Desktop JVM fallback; ToolProvider is deliberately not part of the bundled API jar, hence reflection.
                Object compiler = Class.forName("javax.tools.ToolProvider").getMethod("getSystemJavaCompiler").invoke(null);
                if (compiler != null) return (JavaCompiler) compiler;
            } catch (ReflectiveOperationException | LinkageError ignored) { }
            throw new IllegalStateException("No javax.tools.JavaCompiler implementation available (ECJ's " + ECJ_COMPILER + " was not found): " + missing);
        }
    }

    private static String format(DiagnosticCollector<JavaFileObject> collector, StringWriter console) {
        StringBuilder out = new StringBuilder();
        for (Diagnostic<? extends JavaFileObject> d : collector.getDiagnostics()) {
            if (d.getKind() == Diagnostic.Kind.NOTE && d.getMessage(null).isEmpty()) continue;
            out.append(d.getKind().name().toLowerCase(java.util.Locale.ROOT)).append(": ");
            if (d.getSource() != null) out.append(d.getSource().getName()).append(':').append(d.getLineNumber()).append(": ");
            out.append(d.getMessage(null)).append('\n');
        }
        out.append(console.toString());
        return out.toString();
    }

    private static String describe(Throwable failure) {
        Throwable root = failure;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        return failure + (root == failure ? "" : " (caused by " + root + ")");
    }

    private static String joinClasspath(List<File> files) {
        StringBuilder out = new StringBuilder();
        for (File file : files) {
            if (file == null || !file.exists()) continue;
            if (out.length() > 0) out.append(File.pathSeparator);
            out.append(file.getAbsolutePath());
        }
        return out.toString();
    }

    private static void copyNonClassFiles(File from, File to) throws IOException {
        File[] children = from.listFiles();
        if (children == null) return;
        for (File child : children) {
            File target = new File(to, child.getName());
            if (child.isDirectory()) { copyNonClassFiles(child, target); continue; }
            if (child.getName().endsWith(".class")) continue;
            if (!to.isDirectory() && !to.mkdirs()) throw new IOException("Could not create " + to);
            Files.copy(child.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void deleteTree(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        file.delete();
    }

    private static byte[] readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        copy(in, out);
        return out.toByteArray();
    }

    private static void copy(InputStream in, java.io.OutputStream out) throws IOException {
        byte[] buffer = new byte[32768];
        for (int read; (read = in.read(buffer)) > 0; ) out.write(buffer, 0, read);
    }
}
