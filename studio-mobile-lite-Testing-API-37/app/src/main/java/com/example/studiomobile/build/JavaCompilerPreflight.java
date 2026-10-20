package com.example.studiomobile.build;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Compiles a tiny probe (records, sealed types, lambdas, text blocks) at the supported Java level with the exact same engine and android.jar used for the real build.
 * If the compiler cannot produce class files of that level on this device, the build is rejected up front
 * instead of failing (or producing a broken APK) deep inside the pipeline.
 */
public final class JavaCompilerPreflight {
    private JavaCompilerPreflight() { }

    private static final String PROBE =
            "public class Probe {\n"
          + "    record Pair(int a, String b) { }\n"
          + "    sealed interface Shape permits Dot { }\n"
          + "    record Dot() implements Shape { }\n"
          + "    static Runnable lambda(String x) { Runnable r = () -> System.out.println(x); return r; }\n"
          + "    static java.util.function.Function<String, Integer> ref() { return String::length; }\n"
          + "    static String pick(Object o, int n) {\n"
          + "        var label = switch (n) { case 1 -> \"one\"; default -> \"other\"; };\n"
          + "        String block = \"\"\"\n"
          + "            text\"\"\";\n"
          + "        if (o instanceof String s) return s + label + block + new Pair(n, s);\n"
          + "        return label + block;\n"
          + "    }\n"
          + "}\n";

    /** @return null when Java compilation at the supported level works, otherwise a human-readable reason. */
    public static String check(File toolsDirectory, File projectDirectory, File scratch) {
        File androidJar;
        try {
            androidJar = AndroidPlatformResolver.resolve(toolsDirectory, projectDirectory);
        } catch (RuntimeException e) {
            return String.valueOf(e.getMessage());
        }
        try {
            File src = new File(scratch, "src");
            File out = new File(scratch, "classes");
            if (!src.isDirectory() && !src.mkdirs()) return "Could not create preflight directory: " + src;
            if (!out.isDirectory() && !out.mkdirs()) return "Could not create preflight directory: " + out;
            File probe = new File(src, "Probe.java");
            try (Writer w = new OutputStreamWriter(new FileOutputStream(probe), StandardCharsets.UTF_8)) { w.write(PROBE); }

            List<File> classpath = new ArrayList<>();
            classpath.add(androidJar);
            InProcessCompilerEngine.CompilationOutput result = new InProcessCompilerEngine(toolsDirectory, null)
                    .compileJava(Collections.singletonList(probe), androidJar, classpath, out);
            if (!result.isSuccessful())
                return "The Java " + JavaLanguageLevel.REQUIRED + " compiler self-test failed: " + trim(result.getDiagnostics());

            File compiled = new File(out, "Probe.class");
            if (!compiled.isFile()) return "The Java " + JavaLanguageLevel.REQUIRED + " compiler self-test produced no Probe.class.";
            try (InputStream in = new java.io.FileInputStream(compiled)) {
                int major = ClassFileVersionGuard.readMajor(in);
                if (major != JavaLanguageLevel.REQUIRED_CLASS_MAJOR)
                    return "The compiler produced Java " + ClassFileVersionGuard.javaVersion(major)
                            + " bytecode instead of Java " + JavaLanguageLevel.REQUIRED + ".";
            }
            return null;
        } catch (Exception | LinkageError e) {
            return "The Java " + JavaLanguageLevel.REQUIRED + " compiler self-test could not run: "
                    + e.getClass().getName() + ": " + e.getMessage();
        }
    }

    private static String trim(String text) {
        String value = text == null ? "" : text.trim();
        return value.length() <= 2000 ? value : value.substring(0, 2000) + "…";
    }
}
