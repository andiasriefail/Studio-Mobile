package com.example.studiomobile.build;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;

/** Writes durable, privacy-conscious diagnostics without replacing the original exception. */
public final class BuildFailureReport {
    private BuildFailureReport() { }

    public static File write(File outputDirectory, String stage, Throwable failure, String detail) {
        return write(outputDirectory, stage, failure, detail, "");
    }

    public static File write(File outputDirectory, String stage, Throwable failure, String detail, String transcript) {
        if (outputDirectory == null) return null;
        try {
            if (!outputDirectory.isDirectory() && !outputDirectory.mkdirs()) return null;
            // Only the latest error log is kept.
            File[] old = outputDirectory.listFiles();
            if (old != null) for (File previous : old) if (previous.isFile()) previous.delete();
            File report = new File(outputDirectory, "loggeror.txt");
            StringWriter stackBuffer = new StringWriter();
            if (failure != null) {
                try (PrintWriter stackWriter = new PrintWriter(stackBuffer)) { failure.printStackTrace(stackWriter); }
            }
            try (Writer writer = new OutputStreamWriter(new FileOutputStream(report), StandardCharsets.UTF_8)) {
                writer.write("Studio Mobile Build Diagnostics\n");
                writer.write("Build ID: " + UUID.randomUUID() + "\n");
                writer.write("Timestamp: " + new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.ROOT).format(new Date()) + "\n");
                writer.write("Stage: " + safe(stage) + "\n");
                writer.write("Device OS: " + System.getProperty("os.name", "unknown") + " " + System.getProperty("os.version", "unknown") + "\n");
                writer.write("Java runtime: " + System.getProperty("java.version", "unknown") + "\n\n");
                writer.write("Summary:\n" + safe(detail) + "\n\n");
                writer.write("Build log from start to failure:\n");
                writer.write(transcript == null || transcript.isEmpty() ? "(No build transcript captured.)\n" : transcript);
                writer.write("\nOriginal exception and cause chain:\n");
                writer.write(failure == null ? "No exception object was supplied.\n" : stackBuffer.toString());
                writer.write("\nDiagnostic hints:\n");
                String diagnostic = (safe(detail) + "\n" + stackBuffer.toString()).toLowerCase(Locale.ROOT);
                if (diagnostic.contains("org.apache.poi") || diagnostic.contains("apache poi") || diagnostic.contains("poi-ooxml")) {
                    writer.write("Apache POI detected. Apache POI's desktop-oriented components may require APIs/classes not available in Android. Check the first NoClassDefFoundError, ClassNotFoundException, UnsupportedClassVersionError, or missing XMLBeans/commons dependency above. Use Android-compatible POI dependencies and include all required transitive JARs; a plain POI JAR alone is often insufficient.\n");
                }
                if (diagnostic.contains("unknownhost") || diagnostic.contains("dns lookup") || diagnostic.contains("unable to resolve host")) {
                    writer.write("Network/DNS failure detected. Verify internet connectivity, Private DNS/VPN settings, and repository availability. The app does not override Android system DNS.\n");
                }
                if (diagnostic.contains("outofmemoryerror") || diagnostic.contains("failed to allocate")) {
                    writer.write("Memory pressure detected. Close other apps and reduce dependency size; large downloads should be streamed and installed atomically rather than duplicated in memory.\n");
                }
                if (diagnostic.contains("/data/.android") || diagnostic.contains("accessdeniedexception")) {
                    writer.write("Access denied while Gradle/AGP used the default user home (~/.android). Studio Mobile now sets ANDROID_USER_HOME and user.home to app-private storage; if this still appears, check the 'Android user home' line near the top of the log.\n");
                }
                if (diagnostic.contains("checksum") || diagnostic.contains("could not read")) {
                    writer.write("Possible corrupted Gradle dependency cache. Clear the Gradle dependency cache and rebuild online.\n");
                }
                writer.write("\nNote: review this report before sharing it; compiler output may contain project paths.\n");
            }
            return report.isFile() && report.length() > 0 ? report : null;
        } catch (Exception reportFailure) {
            // Diagnostics must never mask the original build failure.
            return null;
        }
    }

    private static String safe(String value) { return value == null ? "(none)" : value; }
}
