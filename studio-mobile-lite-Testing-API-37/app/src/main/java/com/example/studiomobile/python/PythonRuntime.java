package com.example.studiomobile.python;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * An installed CPython for Android.
 *
 * <p>Two parts live in different places because Android 10+ only lets an app execute files from its native library directory:
 * <ul>
 * <li>the interpreter executable ships in the APK as {@code libpythonexe.so} (plus {@code libpython3.x.so}) in {@code nativeLibraryDir};</li>
 * <li>the standard library ({@code lib/python3.x}, {@code lib-dynload}) is downloaded once by {@link PythonProvisioner} into {@code files/python}.</li>
 * </ul>
 */
public final class PythonRuntime {
    public static final String EXECUTABLE_NAME = "libpythonexe.so";
    public final File executable, home, nativeLibraryDirectory;

    private PythonRuntime(File executable, File home, File nativeLibraryDirectory) {
        this.executable = executable; this.home = home; this.nativeLibraryDirectory = nativeLibraryDirectory;
    }

    /** @return the runtime, or null with human-readable reasons added to {@code problems}. */
    public static PythonRuntime locate(File filesDirectory, File nativeLibraryDirectory, List<String> problems) {
        int before = problems.size();
        File executable = new File(nativeLibraryDirectory, EXECUTABLE_NAME);
        if (!executable.isFile() || executable.length() == 0 || !executable.canExecute())
            problems.add("The Python interpreter is not part of this build of Studio Mobile (" + executable + " is missing). Rebuild the app with the Python step of build.yml.");
        File home = new File(filesDirectory, "python");
        if (findStandardLibrary(home) == null) problems.add("The Python standard library is not installed at " + home + ".");
        return problems.size() > before ? null : new PythonRuntime(executable, home, nativeLibraryDirectory);
    }

    /** True when the interpreter is bundled in the APK, whether or not the standard library has been downloaded yet. */
    public static boolean isInterpreterBundled(File nativeLibraryDirectory) {
        File executable = new File(nativeLibraryDirectory, EXECUTABLE_NAME);
        return executable.isFile() && executable.length() > 0 && executable.canExecute();
    }

    /** {@code <home>/lib/python3.N} when it contains os.py, else null. */
    public static File findStandardLibrary(File home) {
        File[] versions = new File(home, "lib").listFiles(file -> file.isDirectory() && file.getName().startsWith("python3"));
        if (versions == null) return null;
        for (File version : versions) if (new File(version, "os.py").isFile()) return version;
        return null;
    }

    public List<String> command(File script) {
        List<String> command = new ArrayList<>();
        command.add(executable.getAbsolutePath()); command.add("-u"); command.add(script.getAbsolutePath());
        return command;
    }

    public Map<String, String> environment(File dataDirectory, File cacheDirectory) {
        Map<String, String> environment = new HashMap<>();
        environment.put("PYTHONHOME", home.getAbsolutePath());
        // libpython3.x.so sits next to the executable in the native library directory; extension modules in lib-dynload link against it.
        environment.put("LD_LIBRARY_PATH", nativeLibraryDirectory.getAbsolutePath() + File.pathSeparator + new File(home, "lib").getAbsolutePath());
        environment.put("PYTHONDONTWRITEBYTECODE", "1");
        environment.put("PYTHONUNBUFFERED", "1");
        environment.put("PYTHONIOENCODING", "utf-8");
        environment.put("HOME", dataDirectory.getAbsolutePath());
        environment.put("TMPDIR", cacheDirectory.getAbsolutePath());
        return environment;
    }
}
