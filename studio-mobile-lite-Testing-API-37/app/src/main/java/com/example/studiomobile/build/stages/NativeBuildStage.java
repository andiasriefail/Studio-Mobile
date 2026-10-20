package com.example.studiomobile.build.stages;

import com.example.studiomobile.build.BuildLogger;
import com.example.studiomobile.build.BuildProjectFiles;
import com.example.studiomobile.build.BuildStage;
import com.example.studiomobile.build.BuildStageContext;
import com.example.studiomobile.build.DeviceAbi;
import com.example.studiomobile.build.nativecode.CMakeScriptReader;
import com.example.studiomobile.build.nativecode.NativeDiagnosticParser;
import com.example.studiomobile.build.nativecode.NativeNdkProvisioner;
import com.example.studiomobile.build.nativecode.NativeLibraryRiskAnalyzer;
import com.example.studiomobile.build.nativecode.NativeProject;
import com.example.studiomobile.build.nativecode.NativeToolchain;
import com.example.studiomobile.build.process.BuildProcessRunner;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Compiles the project's C/C++ code (CMake) into {@code lib<name>.so} for every requested ABI and hands the folder to
 * {@link ApkPackagingStage} as the output {@code nativeLibsDirectory}.
 *
 * <p>Two modes: real CMake + Ninja when both are installed next to the NDK; otherwise clang is invoked directly for the CMake subset
 * understood by {@link CMakeScriptReader}. 64-bit libraries are linked with 16 KB page alignment, as Google Play requires.
 * When no NDK is installed the build only continues if prebuilt libraries in {@code src/main/jniLibs} already cover every expected
 * library and ABI; otherwise it stops, because an APK without the library would crash in {@code System.loadLibrary}.
 */
public final class NativeBuildStage implements BuildStage {
    private static final long TIMEOUT_SECONDS = 900;
    private final File tools;
    private final BuildProcessRunner runner;
    private final NativeNdkProvisioner provisioner; // null: never download

    public NativeBuildStage(File tools, BuildProcessRunner runner) { this(tools, runner, null); }
    public NativeBuildStage(File tools, BuildProcessRunner runner, NativeNdkProvisioner provisioner) { this.tools = tools; this.runner = runner; this.provisioner = provisioner; }

    @Override public String getName() { return "Native C/C++ build"; }

    @Override public void execute(BuildStageContext context, BuildLogger logger) throws Exception {
        NativeProject project = NativeProject.detect(context.getProjectDirectory());
        if (project == null) return;
        for (String warning : project.warnings) logger.onWarning(warning);
        if (!project.problems.isEmpty()) throw new IllegalStateException(join("Native project is invalid:", project.problems));

        List<String> toolProblems = new ArrayList<>();
        NativeToolchain toolchain = NativeToolchain.locate(tools, toolProblems);
        if (toolchain == null && uncoveredByPrebuilt(project) != null) {
            // Same check as NativePreflightStage, kept here so the stage is safe when used on its own: never report "No NDK" on a 32-bit device.
            String unsupported = DeviceAbi.require64Bit("compiling C/C++ on the device", DeviceAbi.supportedAbis());
            if (unsupported != null) throw new IllegalStateException(unsupported + "\nFix: build the .so files on GitHub Actions (see the native-build.yml template) and put them in src/main/jniLibs/<abi>/.");
        }
        if (toolchain == null && provisioner != null && needsNdkDownload(project)) {
            // The sysroot is missing and the prebuilt .so files do not cover the project: fetch the NDK once, then look again.
            if (provisioner.install(tools, logger) != null) { toolProblems.clear(); toolchain = NativeToolchain.locate(tools, toolProblems); }
        }
        if (toolchain == null) {
            String uncovered = uncoveredByPrebuilt(project);
            if (uncovered == null) { logger.onWarning("No NDK installed; using the prebuilt libraries in src/main/jniLibs. Native sources were NOT compiled."); return; }
            throw new IllegalStateException(join("Native sources were found but cannot be compiled, and " + uncovered, toolProblems)
                + "\nFix: install an NDK (files/build-tools/ndk), or build the .so elsewhere and put it in src/main/jniLibs/<abi>/ (see the native-build.yml GitHub Actions template).");
        }
        File output = new File(context.getWorkingDirectory(), "native-libs");
        boolean cmakeMode = toolchain.canUseCmake();
        logger.onInfo("Native build: " + (cmakeMode ? "CMake + Ninja" : "direct clang") + ", platform android-" + project.platform + ", ABIs " + project.abis + ".");
        if (!cmakeMode) requireSubset(project);
        for (String abi : project.abis) {
            File abiOutput = new File(output, abi);
            if (!abiOutput.mkdirs() && !abiOutput.isDirectory()) throw new IllegalStateException("Could not create " + abiOutput);
            if (cmakeMode) buildWithCmake(context, toolchain, project, abi, abiOutput, logger);
            else buildWithClang(context, toolchain, project, abi, abiOutput, logger);
            for (String name : project.libraryNames()) {
                File library = new File(abiOutput, "lib" + name + ".so");
                if (!library.isFile() || library.length() == 0) throw new IllegalStateException("Native build finished but " + library.getName() + " was not produced for " + abi + ".");
            }
        }
        context.putOutput("nativeLibsDirectory", output);
        File main = BuildProjectFiles.mainDirectory(context.getProjectDirectory());
        for (String warning : NativeLibraryRiskAnalyzer.findUnimplementedNativeMethods(javaAndKotlinSources(main), cSources(project.cmakeDirectory()))) logger.onWarning(warning);
        logger.onInfo("Native libraries built for " + project.abis.size() + " ABI(s): " + project.libraryNames());
    }

    private boolean needsNdkDownload(NativeProject project) {
        File[] hosts = new File(tools, "ndk/toolchains/llvm/prebuilt").listFiles(File::isDirectory);
        boolean sysrootPresent = hosts != null && hosts.length > 0 && new File(hosts[0], "sysroot/usr/include").isDirectory();
        return !sysrootPresent && uncoveredByPrebuilt(project) != null;
    }

    private void requireSubset(NativeProject project) {
        CMakeScriptReader script = project.script;
        if (script == null || script.targets.isEmpty()) throw new IllegalStateException("No add_library(<name> SHARED ...) found in " + project.cmakeFile.getName() + ".");
        if (!script.unsupported.isEmpty())
            throw new IllegalStateException(join("CMakeLists.txt uses CMake features that need a real CMake, and no cmake/ninja is installed next to the NDK:", script.unsupported));
    }

    private void buildWithCmake(BuildStageContext context, NativeToolchain toolchain, NativeProject project, String abi, File abiOutput, BuildLogger logger) throws Exception {
        File buildDirectory = new File(context.getWorkingDirectory(), "native-cmake/" + abi);
        if (!buildDirectory.mkdirs() && !buildDirectory.isDirectory()) throw new IllegalStateException("Could not create " + buildDirectory);
        Map<String, String> environment = new HashMap<>(); environment.put("ANDROID_NDK_HOME", toolchain.ndkRoot.getAbsolutePath());
        List<String> configure = new ArrayList<>();
        configure.add(toolchain.cmake.getAbsolutePath()); configure.add("-G"); configure.add("Ninja");
        configure.add("-S"); configure.add(project.cmakeDirectory().getAbsolutePath()); configure.add("-B"); configure.add(buildDirectory.getAbsolutePath());
        configure.add("-DCMAKE_TOOLCHAIN_FILE=" + toolchain.cmakeToolchainFile.getAbsolutePath());
        configure.add("-DANDROID_ABI=" + abi); configure.add("-DANDROID_PLATFORM=android-" + project.platform);
        configure.add("-DANDROID_NDK=" + toolchain.ndkRoot.getAbsolutePath()); configure.add("-DCMAKE_MAKE_PROGRAM=" + toolchain.ninja.getAbsolutePath());
        configure.add("-DCMAKE_BUILD_TYPE=Debug"); configure.add("-DCMAKE_LIBRARY_OUTPUT_DIRECTORY=" + abiOutput.getAbsolutePath());
        if (NativeToolchain.is64Bit(abi)) configure.add("-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON");
        run(configure, buildDirectory, environment, logger, abi, "CMake configure");
        List<String> build = new ArrayList<>(); build.add(toolchain.cmake.getAbsolutePath()); build.add("--build"); build.add(buildDirectory.getAbsolutePath());
        run(build, buildDirectory, environment, logger, abi, "CMake build");
    }

    private void buildWithClang(BuildStageContext context, NativeToolchain toolchain, NativeProject project, String abi, File abiOutput, BuildLogger logger) throws Exception {
        String target = NativeToolchain.triple(abi) + project.platform;
        for (CMakeScriptReader.Target library : project.script.targets.values()) {
            File objects = new File(context.getWorkingDirectory(), "native-obj/" + abi + "/" + library.name);
            if (!objects.mkdirs() && !objects.isDirectory()) throw new IllegalStateException("Could not create " + objects);
            List<String> objectFiles = new ArrayList<>(); boolean hasCpp = false; int index = 0;
            for (String source : library.sources) {
                String lower = source.toLowerCase(Locale.ROOT);
                boolean cpp = lower.endsWith(".cpp") || lower.endsWith(".cc") || lower.endsWith(".cxx");
                if (!(cpp || lower.endsWith(".c") || lower.endsWith(".s"))) continue; // headers listed in add_library
                File file = new File(project.cmakeDirectory(), source);
                if (!file.isFile()) throw new IllegalStateException("Source listed in add_library(" + library.name + ") does not exist: " + source);
                hasCpp |= cpp;
                File object = new File(objects, (index++) + "_" + file.getName() + ".o");
                List<String> command = base(toolchain, target, false);
                command.add("-c"); command.add("-fPIC"); command.add("-O2"); command.add("-DANDROID"); command.add("-Wall");
                command.add("-ffunction-sections"); command.add("-fdata-sections"); command.add("-funwind-tables"); command.add("-fstack-protector-strong");
                command.add("-I" + project.cmakeDirectory().getAbsolutePath());
                for (String directory : project.script.globalIncludeDirectories) command.add("-I" + resolve(project.cmakeDirectory(), directory));
                for (String directory : library.includeDirectories) command.add("-I" + resolve(project.cmakeDirectory(), directory));
                command.addAll(project.script.globalCompileOptions); command.addAll(library.compileOptions);
                command.add(file.getAbsolutePath()); command.add("-o"); command.add(object.getAbsolutePath());
                run(command, project.cmakeDirectory(), null, logger, abi, "Compile " + source);
                objectFiles.add(object.getAbsolutePath());
            }
            if (objectFiles.isEmpty()) throw new IllegalStateException("add_library(" + library.name + ") lists no compilable sources (.c, .cc, .cpp, .cxx, .S).");
            List<String> link = base(toolchain, target, hasCpp);
            link.add("-shared"); link.add("-fuse-ld=lld");
            if (toolchain.linker != null) link.add("--ld-path=" + toolchain.linker.getAbsolutePath());
            if (hasCpp) link.add("-static-libstdc++");
            link.add("-Wl,-soname,lib" + library.name + ".so"); link.add("-Wl,--build-id=sha1"); link.add("-Wl,-z,noexecstack");
            link.add("-Wl,-z,relro"); link.add("-Wl,-z,now"); link.add("-Wl,--gc-sections"); link.add("-Wl,--no-undefined"); link.add("-Wl,--strip-all");
            if (NativeToolchain.is64Bit(abi)) { link.add("-Wl,-z,max-page-size=16384"); link.add("-Wl,-z,common-page-size=16384"); }
            link.addAll(objectFiles);
            for (String name : library.libraries) link.add(name.startsWith("-") || name.contains("/") ? name : "-l" + name);
            link.add("-o"); link.add(new File(abiOutput, "lib" + library.name + ".so").getAbsolutePath());
            run(link, abiOutput, null, logger, abi, "Link lib" + library.name + ".so");
        }
    }

    private static List<String> base(NativeToolchain toolchain, String target, boolean cppDriver) {
        List<String> command = new ArrayList<>();
        command.add(toolchain.clang.getAbsolutePath());
        if (cppDriver) command.add("--driver-mode=g++");
        command.add("--target=" + target); command.add("--sysroot=" + toolchain.sysroot.getAbsolutePath());
        if (toolchain.resourceDirectory != null && !toolchain.clang.getAbsolutePath().startsWith(toolchain.prebuilt.getAbsolutePath()))
            command.add("-resource-dir=" + toolchain.resourceDirectory.getAbsolutePath());
        return command;
    }

    private void run(List<String> command, File directory, Map<String, String> environment, BuildLogger logger, String abi, String step) throws Exception {
        Recorder recorder = new Recorder(logger);
        logger.onInfo("[" + abi + "] " + step);
        BuildProcessRunner.Result result = runner.run(command, directory, TIMEOUT_SECONDS, recorder, environment);
        if (result.isSuccessful()) return;
        if (result.isTimedOut()) throw new IllegalStateException("[" + abi + "] " + step + " timed out after " + TIMEOUT_SECONDS + " seconds.");
        String errors = NativeDiagnosticParser.summarizeErrors(NativeDiagnosticParser.parse(recorder.text()), 12);
        throw new IllegalStateException("[" + abi + "] " + step + " failed (exit " + result.getExitCode() + ")." + (errors.isEmpty() ? "\n" + recorder.tail(15) : errors));
    }

    /** Prebuilt-library check: null when src/main/jniLibs already holds every expected library for every ABI, else the reason it does not. */
    static String uncoveredByPrebuilt(NativeProject project) {
        Set<String> names = project.libraryNames();
        if (names.isEmpty()) return "the library names could not be determined from CMakeLists.txt.";
        for (String abi : project.abis) for (String name : names)
            if (!new File(project.jniLibsDirectory(), abi + "/lib" + name + ".so").isFile()) return "src/main/jniLibs/" + abi + "/lib" + name + ".so does not exist.";
        return null;
    }

    private static List<File> javaAndKotlinSources(File main) {
        List<File> files = new ArrayList<>();
        for (String folder : new String[] {"java", "kotlin"}) { files.addAll(BuildProjectFiles.filesWithExtension(new File(main, folder), ".java")); files.addAll(BuildProjectFiles.filesWithExtension(new File(main, folder), ".kt")); }
        return files;
    }

    private static List<File> cSources(File directory) {
        List<File> files = new ArrayList<>();
        for (String extension : new String[] {".c", ".cc", ".cpp", ".cxx", ".h", ".hpp"}) files.addAll(BuildProjectFiles.filesWithExtension(directory, extension));
        return files;
    }

    private static String resolve(File base, String path) { File file = new File(path); return file.isAbsolute() ? path : new File(base, path).getAbsolutePath(); }
    private static String join(String title, List<String> items) { StringBuilder text = new StringBuilder(title); for (String item : items) text.append("\n- ").append(item); return text.toString(); }

    /** Forwards tool output to the build log while keeping it for error parsing. */
    private static final class Recorder implements BuildLogger {
        private final BuildLogger target; private final StringBuilder lines = new StringBuilder();
        Recorder(BuildLogger target) { this.target = target; }
        @Override public synchronized void onInfo(String message) { lines.append(message).append('\n'); target.onInfo(message); }
        @Override public void onWarning(String message) { target.onWarning(message); }
        @Override public void onError(String message, Throwable cause) { target.onError(message, cause); }
        synchronized String text() { return lines.toString(); }
        synchronized String tail(int count) { String[] all = lines.toString().split("\n"); StringBuilder text = new StringBuilder(); for (int i = Math.max(0, all.length - count); i < all.length; i++) text.append(all[i]).append('\n'); return text.toString().trim(); }
    }
  }
          
