package com.example.studiomobile.build.nativecode;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Finds native-code mistakes that always crash at runtime (missing library) or very likely do (JNI function without a C definition). */
public final class NativeLibraryRiskAnalyzer {
    private static final Pattern LOAD_LIBRARY = Pattern.compile("System\\s*\\.\\s*loadLibrary\\s*\\(\\s*\"([^\"]+)\"\\s*\\)");
    private static final Pattern PACKAGE = Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)");
    private static final Pattern JAVA_NATIVE = Pattern.compile("\\bnative\\s+[\\w.<>\\[\\]]+\\s+(\\w+)\\s*\\(");
    private static final Pattern KOTLIN_EXTERNAL = Pattern.compile("\\bexternal\\s+fun\\s+(\\w+)\\s*\\(");
    private static final Pattern KOTLIN_CLASS = Pattern.compile("\\b(?:class|object)\\s+(\\w+)");
    /** Libraries every Android device provides; loadLibrary on them needs nothing in the APK. */
    private static final Set<String> SYSTEM_LIBRARIES = new HashSet<>(Arrays.asList(
        "c", "m", "dl", "log", "android", "z", "jnigraphics", "OpenSLES", "EGL", "GLESv1_CM", "GLESv2", "GLESv3", "mediandk", "aaudio", "vulkan", "nativewindow", "camera2ndk"));
    private NativeLibraryRiskAnalyzer() { }

    /** Names (without lib/.so) of every .so below {@code <directory>/<abi>/}. */
    public static Set<String> libraryNamesIn(File directory) {
        Set<String> names = new LinkedHashSet<>();
        File[] abis = directory == null ? null : directory.listFiles(File::isDirectory);
        if (abis == null) return names;
        for (File abi : abis) {
            File[] libraries = abi.listFiles((dir, name) -> name.startsWith("lib") && name.endsWith(".so"));
            if (libraries != null) for (File library : libraries) names.add(library.getName().substring(3, library.getName().length() - 3));
        }
        return names;
    }

    /** One message per System.loadLibrary("x") whose library is neither packaged nor provided by Android. */
    public static List<String> findMissingLibraries(List<File> sources, Set<String> packagedLibraries) {
        List<String> problems = new ArrayList<>(); Set<String> reported = new HashSet<>();
        for (File source : sources) {
            Matcher matcher = LOAD_LIBRARY.matcher(read(source));
            while (matcher.find()) {
                String name = matcher.group(1);
                if (SYSTEM_LIBRARIES.contains(name) || packagedLibraries.contains(name) || !reported.add(name)) continue;
                problems.add(source.getName() + " calls System.loadLibrary(\"" + name + "\") but no lib" + name + ".so is in the APK (add it to src/main/jniLibs/<abi>/ or build it from src/main/cpp). It would crash with UnsatisfiedLinkError.");
            }
        }
        return problems;
    }

    /** Warnings for Java/Kotlin native methods that have no Java_... definition in the given C/C++ files. Silent when RegisterNatives is used. */
    public static List<String> findUnimplementedNativeMethods(List<File> managedSources, List<File> nativeSources) {
        StringBuilder nativeText = new StringBuilder();
        for (File file : nativeSources) nativeText.append(read(file)).append('\n');
        List<String> warnings = new ArrayList<>();
        if (nativeText.length() == 0 || nativeText.indexOf("RegisterNatives") >= 0) return warnings;
        String haystack = nativeText.toString();
        for (File source : managedSources) {
            String text = read(source); Matcher pkg = PACKAGE.matcher(text);
            String packageName = pkg.find() ? pkg.group(1) : "";
            boolean kotlin = source.getName().endsWith(".kt");
            String className;
            if (kotlin) { Matcher declared = KOTLIN_CLASS.matcher(text); if (!declared.find()) continue; className = declared.group(1); }
            else className = source.getName().replaceFirst("\\.java$", "");
            Matcher method = (kotlin ? KOTLIN_EXTERNAL : JAVA_NATIVE).matcher(text);
            while (method.find()) {
                String symbol = "Java_" + mangle(packageName.isEmpty() ? className : packageName + "." + className) + "_" + mangle(method.group(1));
                if (!haystack.contains(symbol)) warnings.add(source.getName() + ": native method '" + method.group(1) + "' has no C function named " + symbol + "; calling it will throw UnsatisfiedLinkError.");
            }
        }
        return warnings;
    }

    /** JNI name mangling for ASCII identifiers: '.' becomes '_', '_' becomes '_1', '$' becomes '_00024'. */
    static String mangle(String name) {
        StringBuilder out = new StringBuilder();
        for (char c : name.toCharArray()) {
            if (c == '.') out.append('_');
            else if (c == '_') out.append("_1");
            else if (c == '$') out.append("_00024");
            else out.append(c);
        }
        return out.toString();
    }

    private static String read(File file) {
        try { return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8); } catch (IOException error) { return ""; }
    }
}
