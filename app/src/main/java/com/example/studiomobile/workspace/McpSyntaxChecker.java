package com.example.studiomobile.workspace;

import com.example.studiomobile.build.BuildLogger;
import com.example.studiomobile.build.DeviceAbi;
import com.example.studiomobile.build.nativecode.NativeToolchain;
import com.example.studiomobile.build.process.BuildProcessRunner;
import com.example.studiomobile.dart.DartRuntime;
import com.example.studiomobile.python.PythonRuntime;

import org.mozilla.javascript.Context;
import org.mozilla.javascript.RhinoException;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks the syntax of ONE file for the {@code check_syntax} MCP tool. Nothing is ever executed: Python is only compiled, Dart is only
 * compiled to kernel (never started), C/C++ runs {@code clang -fsyntax-only}, JavaScript is only parsed.
 *
 * <p>Python, Dart and C/C++ use the arm64-v8a binaries that ship with the app, so on any other phone they answer
 * {@code NOT CHECKED} with the reason. JavaScript (Rhino) works on every ABI. Every answer starts with {@code language=} and
 * {@code RESULT:} (OK, SYNTAX ERRORS or NOT CHECKED) so an AI can read it without guessing.
 */
public final class McpSyntaxChecker {
    public static final String SUPPORTED_FILES = ".py, .dart, .js, .c, .cc, .cpp, .cxx, .h, .hpp";

    private static final long TIMEOUT_SECONDS = 60;
    private static final int MAX_LINES = 40;
    private static final int ANDROID_PLATFORM = 24;
    private static final Pattern DART_RESULT = Pattern.compile("^result (\\S+)$");
    private static final String PYTHON_CODE = "import sys\nsource = open(sys.argv[1], 'rb').read()\ncompile(source, sys.argv[1], 'exec', dont_inherit=True)\n";
    private static final String ARM64_ONLY = " Python, Dart and on-device C/C++ run only on arm64-v8a phones. Do not look for another way to check this file; "
        + "tell the user, and ask them to build or run it on a supported phone.";

    private final File toolsDirectory;
    private final File nativeLibraryDirectory; // null when unknown
    private final List<String> abis;
    private final File filesDirectory;
    private final File cacheDirectory;

    public McpSyntaxChecker(File toolsDirectory, File nativeLibraryDirectory) {
        this(toolsDirectory, nativeLibraryDirectory, DeviceAbi.supportedAbis());
    }

    McpSyntaxChecker(File toolsDirectory, File nativeLibraryDirectory, List<String> abis) {
        this.toolsDirectory = toolsDirectory;
        this.nativeLibraryDirectory = nativeLibraryDirectory;
        this.abis = abis;
        File files = toolsDirectory.getParentFile();
        this.filesDirectory = files == null ? toolsDirectory : files;
        File appData = filesDirectory.getParentFile();
        this.cacheDirectory = new File(appData == null ? filesDirectory : appData, "cache");
    }

    /** Which languages this phone supports, for get_toolchain_info. */
    public static String languageSupport(File nativeLibraryDirectory, List<String> abis) {
        boolean arm64 = DeviceAbi.supportsArm64(abis);
        StringBuilder text = new StringBuilder();
        text.append("Device ABIs: ").append(abis == null || abis.isEmpty() ? "unknown" : String.join(", ", abis)).append('\n');
        text.append("- Java and JavaScript: work on every ABI.\n");
        text.append("- Python, Dart and on-device C/C++: arm64-v8a only. This phone: ")
            .append(arm64 ? "supported" : "NOT supported (do not suggest them; C/C++ can be built on GitHub Actions into src/main/jniLibs)").append(".\n");
        if (arm64 && nativeLibraryDirectory != null) {
            text.append("- Python interpreter in this app: ").append(PythonRuntime.isInterpreterBundled(nativeLibraryDirectory) ? "bundled" : "missing").append('\n');
            text.append("- Dart runtime in this app: ").append(DartRuntime.isBundled(nativeLibraryDirectory) ? "bundled" : "missing").append('\n');
        }
        return text.toString();
    }

    /** @param project the project folder (used to find include folders); @param display the path shown to the AI */
    public String check(File project, File file, String display) throws IOException {
        String language = languageOf(file.getName());
        if (language == null)
            return report("unknown", "NOT CHECKED", "No syntax checker for " + display + ". Supported files: " + SUPPORTED_FILES
                + ". Java, Kotlin, XML and Gradle files are only checked by the user's Build.");
        try {
            switch (language) {
                case "javascript": return checkJavaScript(file, display);
                case "python": return checkPython(file, display);
                case "dart": return checkDart(file, display);
                default: return checkNative(project, file, display, language);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("The syntax check was interrupted.");
        }
    }

    static String languageOf(String fileName) {
        String name = fileName.toLowerCase(Locale.ROOT);
        if (name.endsWith(".py")) return "python";
        if (name.endsWith(".dart")) return "dart";
        if (name.endsWith(".js")) return "javascript";
        if (name.endsWith(".c")) return "c";
        if (name.endsWith(".cc") || name.endsWith(".cpp") || name.endsWith(".cxx")) return "c++";
        if (name.endsWith(".h")) return "c-header";
        if (name.endsWith(".hpp") || name.endsWith(".hh")) return "c++-header";
        return null;
    }

    // ------------------------------------------------------------------ JavaScript (Rhino, every ABI)

    private String checkJavaScript(File file, String display) throws IOException {
        String source = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        Context context = Context.enter();
        try {
            context.setOptimizationLevel(-1);
            context.setLanguageVersion(Context.VERSION_ES6);
            context.compileString(source, display, 1, null);
            return report("javascript", "OK", "No syntax errors found. The file was only parsed, not run." + jsNote());
        } catch (RhinoException error) {
            return report("javascript", "SYNTAX ERRORS", display + ":" + error.lineNumber() + ": " + error.details() + jsNote());
        } catch (RuntimeException error) {
            return report("javascript", "NOT CHECKED", "The parser failed: " + error);
        } finally {
            Context.exit();
        }
    }

    private static String jsNote() {
        return "\nChecked with Rhino (ES6), the same engine as Run in the app. import/export modules and the newest syntax are not supported by it.";
    }

    // ------------------------------------------------------------------ Python (arm64-v8a)

    private String checkPython(File file, String display) throws IOException, InterruptedException {
        String blocked = gate("python", "the Python syntax check");
        if (blocked != null) return blocked;
        List<String> problems = new ArrayList<>();
        PythonRuntime runtime = PythonRuntime.locate(filesDirectory, nativeLibraryDirectory, problems);
        if (runtime == null)
            return report("python", "NOT CHECKED", join(problems) + "\nThe Python standard library is downloaded the first time the user runs a .py file in the app. Ask the user to run one once.");
        File data = new File(filesDirectory, "python-home");
        if (!data.isDirectory() && !data.mkdirs()) throw new IOException("Could not create the Python data folder.");
        if (!cacheDirectory.isDirectory()) cacheDirectory.mkdirs();
        List<String> command = new ArrayList<>(Arrays.asList(runtime.executable.getAbsolutePath(), "-u", "-c", PYTHON_CODE, file.getAbsolutePath()));
        Collector output = new Collector();
        BuildProcessRunner.Result result = new BuildProcessRunner().run(command, file.getParentFile(), TIMEOUT_SECONDS, output, runtime.environment(data, cacheDirectory));
        return finish("python", file, display, result, output, "");
    }

    // ------------------------------------------------------------------ Dart (arm64-v8a)

    private String checkDart(File file, String display) throws IOException, InterruptedException {
        String blocked = gate("dart", "the Dart syntax check");
        if (blocked != null) return blocked;
        List<String> problems = new ArrayList<>();
        DartRuntime runtime = DartRuntime.locate(filesDirectory, nativeLibraryDirectory, problems);
        if (runtime == null)
            return report("dart", "NOT CHECKED", join(problems) + "\nThe Dart SDK is downloaded the first time the user runs a .dart file in the app. Ask the user to run one once.");
        File work = new File(cacheDirectory, "dart-syntax");
        File data = new File(filesDirectory, "dart-home");
        if ((!work.isDirectory() && !work.mkdirs()) || (!data.isDirectory() && !data.mkdirs())) throw new IOException("Could not create the Dart work folders.");
        File dill = new File(work, file.getName() + ".dill");
        dill.delete();
        Collector output = new Collector();
        BuildProcessRunner.Result result = new BuildProcessRunner().run(runtime.compileCommand(file, dill), file.getParentFile(), TIMEOUT_SECONDS, output, runtime.environment(data, cacheDirectory));
        boolean produced = dill.isFile();
        dill.delete();
        if (result.isTimedOut()) return report("dart", "NOT CHECKED", "The checker timed out after " + TIMEOUT_SECONDS + " seconds.");

        // frontend_server prints diagnostics, then "result <id>" and a final "<id> <dill> <error count>" line.
        String boundary = null;
        int errors = result.getExitCode() == 0 ? 0 : 1;
        StringBuilder diagnostics = new StringBuilder();
        for (String line : output.text().split("\n")) {
            Matcher match = DART_RESULT.matcher(line.trim());
            if (match.matches()) { boundary = match.group(1); continue; }
            if (boundary != null && line.startsWith(boundary)) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length >= 3) try { errors = Math.max(errors, Integer.parseInt(parts[parts.length - 1])); } catch (NumberFormatException ignored) { }
                continue;
            }
            if (!line.trim().isEmpty()) diagnostics.append(line).append('\n');
        }
        String note = "\nThe Dart front end also reports type and import errors, not only syntax.";
        if (errors > 0 || !produced) {
            String text = clean(diagnostics.toString(), file, display);
            if (text.contains("package:")) note += " Errors about package: imports can come from a missing package config (pub get), not from a typo.";
            return report("dart", "SYNTAX ERRORS", (text.isEmpty() ? "The Dart compiler failed with exit code " + result.getExitCode() + " without a message." : text) + note);
        }
        return report("dart", "OK", "No errors found. The program was compiled to kernel but not started." + note);
    }

    // ------------------------------------------------------------------ C and C++ (arm64-v8a, NDK clang)

    private String checkNative(File project, File file, String display, String language) throws IOException, InterruptedException {
        String blocked = gate(language, "the C/C++ syntax check");
        if (blocked != null) return blocked;
        List<String> problems = new ArrayList<>();
        NativeToolchain toolchain = NativeToolchain.locate(toolsDirectory, problems);
        if (toolchain == null)
            return report(language, "NOT CHECKED", join(problems) + "\nThe NDK is downloaded the first time the user builds a project that has C/C++ code.");
        List<String> command = new ArrayList<>();
        command.add(toolchain.clang.getAbsolutePath());
        if (language.startsWith("c++")) command.add("--driver-mode=g++");
        command.add("--target=" + NativeToolchain.triple("arm64-v8a") + ANDROID_PLATFORM);
        command.add("--sysroot=" + toolchain.sysroot.getAbsolutePath());
        if (toolchain.resourceDirectory != null && !toolchain.clang.getAbsolutePath().startsWith(toolchain.prebuilt.getAbsolutePath()))
            command.add("-resource-dir=" + toolchain.resourceDirectory.getAbsolutePath());
        command.add("-fsyntax-only"); command.add("-DANDROID"); command.add("-Wno-pragma-once-outside-header");
        command.add("-x"); command.add(language);
        for (File directory : includeDirectories(project, file)) command.add("-I" + directory.getAbsolutePath());
        command.add(file.getAbsolutePath());
        Collector output = new Collector();
        BuildProcessRunner.Result result = new BuildProcessRunner().run(command, file.getParentFile(), TIMEOUT_SECONDS, output, null);
        String note = language.endsWith("header") ? "\nA header is checked on its own, so an error about a missing type may only mean it needs another header first." : "";
        return finish(language, file, display, result, output, note);
    }

    /** The file's folder and every parent up to the project folder, plus an 'include' folder inside each when there is one. */
    private static Set<File> includeDirectories(File project, File file) {
        Set<File> directories = new LinkedHashSet<>();
        File directory = file.getParentFile();
        for (int level = 0; directory != null && level < 8; level++) {
            directories.add(directory);
            File include = new File(directory, "include");
            if (include.isDirectory()) directories.add(include);
            if (directory.equals(project)) break;
            directory = directory.getParentFile();
        }
        return directories;
    }

    // ------------------------------------------------------------------ helpers

    /** @return a NOT CHECKED report when this phone or this app build cannot run the tool, else null. */
    private String gate(String language, String feature) {
        String unsupported = DeviceAbi.requireArm64(feature, abis);
        if (unsupported != null) return report(language, "NOT CHECKED", unsupported + ARM64_ONLY);
        if (nativeLibraryDirectory == null && !language.startsWith("c"))
            return report(language, "NOT CHECKED", "The app's native library folder is unknown here, so the interpreter cannot be located.");
        return null;
    }

    private String finish(String language, File file, String display, BuildProcessRunner.Result result, Collector output, String note) {
        if (result.isTimedOut()) return report(language, "NOT CHECKED", "The checker timed out after " + TIMEOUT_SECONDS + " seconds.");
        if (result.isSuccessful()) return report(language, "OK", "No syntax errors found. The file was not run." + note);
        String text = clean(output.text(), file, display);
        return report(language, "SYNTAX ERRORS", (text.isEmpty() ? "The checker exited with code " + result.getExitCode() + " without a message." : text) + note);
    }

    private static String report(String language, String status, String body) {
        return "language=" + language + "\nRESULT: " + status + "\n" + body;
    }

    /** Shows the project-relative path instead of the phone's absolute path, and keeps the answer short. */
    private static String clean(String text, File file, String display) {
        String shown = text.replace(file.getAbsolutePath(), display);
        File parent = file.getParentFile();
        if (parent != null) shown = shown.replace(parent.getAbsolutePath() + File.separator, "");
        String[] lines = shown.trim().split("\n");
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < Math.min(lines.length, MAX_LINES); i++) out.append(lines[i]).append('\n');
        if (lines.length > MAX_LINES) out.append("... (").append(lines.length - MAX_LINES).append(" more lines)\n");
        return out.toString().trim();
    }

    private static String join(List<String> items) {
        StringBuilder text = new StringBuilder();
        for (String item : items) { if (text.length() > 0) text.append('\n'); text.append(item); }
        return text.toString();
    }

    /** Collects the tool's output; the process runner's own exit and timeout notices are ignored because the result carries them. */
    private static final class Collector implements BuildLogger {
        private final StringBuilder text = new StringBuilder();
        @Override public synchronized void onInfo(String message) { if (text.length() < 100_000) text.append(message).append('\n'); }
        @Override public void onWarning(String message) { }
        @Override public void onError(String message, Throwable cause) { }
        synchronized String text() { return text.toString(); }
    }
}
