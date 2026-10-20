package com.example.studiomobile.build.nativecode;

import com.example.studiomobile.build.BuildProjectFiles;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Describes the C/C++ part of a project: where CMakeLists.txt is, which ABIs to build and which libraries it should produce. */
public final class NativeProject {
    public static final List<String> ALL_ABIS = Collections.unmodifiableList(Arrays.asList("arm64-v8a", "armeabi-v7a", "x86_64", "x86"));
    /** NDK r27+ supports API 21 and above. */
    public static final int LOWEST_PLATFORM = 21;

    private static final Pattern CMAKE_PATH = Pattern.compile("externalNativeBuild\\s*\\{\\s*cmake\\s*\\{\\s*path\\s*(?:=\\s*)?(?:file\\(\\s*)?[\"']([^\"']+)[\"']");
    private static final Pattern QUOTED = Pattern.compile("[\"']([A-Za-z0-9_-]+)[\"']");

    public final File moduleDirectory;
    public final File cmakeFile;
    public final List<String> abis;
    public final int platform;
    public final CMakeScriptReader script;
    public final List<String> problems;
    public final List<String> warnings;

    private NativeProject(File moduleDirectory, File cmakeFile, List<String> abis, int platform, CMakeScriptReader script, List<String> problems, List<String> warnings) {
        this.moduleDirectory = moduleDirectory; this.cmakeFile = cmakeFile; this.abis = abis; this.platform = platform; this.script = script;
        this.problems = problems; this.warnings = warnings;
    }

    public File cmakeDirectory() { return cmakeFile.getParentFile(); }
    public File jniLibsDirectory() { return new File(moduleDirectory, "src/main/jniLibs"); }

    /** Library names the project should produce (add_library SHARED names); empty when they cannot be determined. */
    public Set<String> libraryNames() {
        return script == null ? Collections.<String>emptySet() : new LinkedHashSet<>(script.libraryNames());
    }

    /** @return null when the project has no native code at all. */
    public static NativeProject detect(File project) {
        File module = BuildProjectFiles.moduleDirectory(project);
        String gradle = readGradle(module);
        Matcher declared = CMAKE_PATH.matcher(gradle);
        File cppDirectory = new File(module, "src/main/cpp");
        File cmake = declared.find() ? new File(module, declared.group(1)) : new File(cppDirectory, "CMakeLists.txt");
        boolean hasNativeSources = !BuildProjectFiles.filesWithExtension(cppDirectory, ".c").isEmpty() || !BuildProjectFiles.filesWithExtension(cppDirectory, ".cpp").isEmpty()
            || !BuildProjectFiles.filesWithExtension(cppDirectory, ".cc").isEmpty();
        File ndkBuild = new File(module, "src/main/jni/Android.mk");
        List<String> problems = new ArrayList<>(), warnings = new ArrayList<>();
        if (!cmake.isFile() && !hasNativeSources && gradle.indexOf("externalNativeBuild") < 0) {
            if (ndkBuild.isFile()) { warnings.add("src/main/jni/Android.mk found: ndk-build is not supported, only CMake. Native code in it will not be compiled."); return new NativeProject(module, cmake, Collections.<String>emptyList(), LOWEST_PLATFORM, null, problems, warnings); }
            return null;
        }
        CMakeScriptReader script = null;
        if (!cmake.isFile()) problems.add("Native sources exist but CMakeLists.txt was not found at " + relative(module, cmake) + ".");
        else {
            try { script = CMakeScriptReader.read(cmake); }
            catch (IOException error) { problems.add("Could not read " + relative(module, cmake) + ": " + error.getMessage()); }
            if (script != null && script.targets.isEmpty() && script.unsupported.isEmpty()) problems.add(relative(module, cmake) + " declares no add_library(<name> SHARED ...), so no .so can be produced.");
        }
        List<String> abis = readAbis(gradle, warnings);
        int minSdk = parseInt(BuildProjectFiles.readNumericSdkValue(project, true, String.valueOf(LOWEST_PLATFORM)), LOWEST_PLATFORM);
        return new NativeProject(module, cmake, abis, Math.max(minSdk, LOWEST_PLATFORM), script, problems, warnings);
    }

    static List<String> readAbis(String gradle, List<String> warnings) {
        Set<String> found = new LinkedHashSet<>();
        int from = 0;
        while ((from = gradle.indexOf("abiFilters", from)) >= 0) {
            int end = gradle.indexOf('\n', from); if (end < 0) end = gradle.length();
            Matcher matcher = QUOTED.matcher(gradle.substring(from, end));
            while (matcher.find()) {
                String abi = matcher.group(1);
                if (ALL_ABIS.contains(abi)) found.add(abi); else warnings.add("abiFilters lists '" + abi + "', which the native build does not support (supported: " + ALL_ABIS + ").");
            }
            from = end;
        }
        return found.isEmpty() ? ALL_ABIS : new ArrayList<>(found);
    }

    private static String readGradle(File module) {
        File file = new File(module, "build.gradle");
        if (!file.isFile()) file = new File(module, "build.gradle.kts");
        if (!file.isFile()) return "";
        try { return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8); } catch (IOException ignored) { return ""; }
    }

    private static int parseInt(String value, int fallback) { try { return Integer.parseInt(value); } catch (NumberFormatException error) { return fallback; } }
    private static String relative(File base, File file) { String path = file.getAbsolutePath(), root = base.getAbsolutePath() + File.separator; return path.startsWith(root) ? path.substring(root.length()) : path; }
}
