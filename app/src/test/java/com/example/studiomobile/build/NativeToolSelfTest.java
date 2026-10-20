package com.example.studiomobile.build;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/** Proves that a native tool can actually be started on this device, with a diagnosis when it cannot. */
public final class NativeToolSelfTest {
    private NativeToolSelfTest() { }

    /** Returns null when the tool starts; otherwise a human-readable reason. Runs "<tool> version". */
    public static String check(File tool) {
        try {
            Process process = new ProcessBuilder(tool.getAbsolutePath(), "version").redirectErrorStream(true).start();
            try { process.getInputStream().close(); } catch (IOException ignored) { }
            if (!process.waitFor(10, TimeUnit.SECONDS)) { process.destroyForcibly(); return tool.getName() + " did not finish within 10 seconds."; }
            return null; // any exit code means the binary started; "version" support differs between tools
        } catch (IOException exception) {
            String message = String.valueOf(exception.getMessage());
            if (message.contains("error=13") || message.contains("Permission denied"))
                return tool.getName() + " exists but the OS refused to execute it (Permission denied). On Android 10+ apps targeting API 29+ cannot run files from app-private storage; package the tool as lib" + stripPrefix(tool.getName()) + ".so under jniLibs (extractNativeLibs=true) or target API 28.";
            if (message.contains("error=8") || message.contains("Exec format error"))
                return tool.getName() + " was built for a different CPU architecture than this device.";
            return tool.getName() + " could not be started: " + message;
        } catch (InterruptedException exception) { Thread.currentThread().interrupt(); return "Interrupted while testing " + tool.getName() + "."; }
    }
    /**
     * Proves that the packaged java launcher can start a JVM from the given JDK tree. Runs "<launcher> -version" with JAVA_HOME and
     * NLAUNCHER_JAVA_HOME set to the JDK home. Returns null on success, otherwise a human-readable reason.
     */
    public static String checkJava(File javaLauncher, File jdkHome) {
        try {
            ProcessBuilder builder = new ProcessBuilder(javaLauncher.getAbsolutePath(), "-version").redirectErrorStream(true);
            builder.environment().put("JAVA_HOME", jdkHome.getAbsolutePath());
            builder.environment().put("NLAUNCHER_JAVA_HOME", jdkHome.getAbsolutePath());
            Process process = builder.start();
            if (!process.waitFor(30, TimeUnit.SECONDS)) { process.destroyForcibly(); return javaLauncher.getName() + " -version did not finish within 30 seconds."; }
            String output = readTail(process.getInputStream());
            if (process.exitValue() == 0) return null;
            return javaLauncher.getName() + " -version failed with exit code " + process.exitValue() + " using JDK " + jdkHome + ": " + output;
        } catch (IOException exception) {
            String message = String.valueOf(exception.getMessage());
            if (message.contains("error=13") || message.contains("Permission denied"))
                return javaLauncher.getName() + " exists but the OS refused to execute it (Permission denied). It must be shipped under jniLibs with extractNativeLibs=true.";
            if (message.contains("error=8") || message.contains("Exec format error"))
                return javaLauncher.getName() + " was built for a different CPU architecture than this device.";
            return javaLauncher.getName() + " could not be started: " + message;
        } catch (InterruptedException exception) { Thread.currentThread().interrupt(); return "Interrupted while testing " + javaLauncher.getName() + "."; }
    }

    private static String readTail(InputStream in) {
        try {
            byte[] data = new byte[4096];
            int length = 0, n;
            while (length < data.length && (n = in.read(data, length, data.length - length)) > 0) length += n;
            return new String(data, 0, length, StandardCharsets.UTF_8).trim();
        } catch (IOException ignored) { return ""; }
    }

    private static String stripPrefix(String name) { return name.startsWith("lib") && name.endsWith(".so") ? name.substring(3, name.length() - 3) : name; }
}
