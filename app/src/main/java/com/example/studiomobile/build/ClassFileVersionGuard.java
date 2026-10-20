package com.example.studiomobile.build;

import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Reads class-file headers to reject bytecode newer than the supported Java level before it reaches D8 / the APK. */
public final class ClassFileVersionGuard {
    private ClassFileVersionGuard() { }

    public static int javaVersion(int major) { return major - 44; }

    /** Returns one message per jar/directory that holds classes with a major version above {@code maxMajor}. */
    public static List<String> findTooNew(Collection<File> inputs, int maxMajor) {
        List<String> problems = new ArrayList<>();
        if (inputs == null) return problems;
        for (File input : inputs) {
            if (input == null || !input.exists()) continue;
            try {
                String problem = input.isDirectory() ? scanDirectory(input, input, maxMajor) : scanJar(input, maxMajor);
                if (problem != null) problems.add(problem);
            } catch (IOException e) {
                problems.add(input.getName() + ": could not be read (" + e.getMessage() + ").");
            }
        }
        return problems;
    }

    /** Major version of one class file, or -1 when the stream is not a class file. */
    public static int readMajor(InputStream stream) throws IOException {
        DataInputStream in = new DataInputStream(stream);
        if (in.readInt() != 0xCAFEBABE) return -1;
        in.readUnsignedShort(); // minor
        return in.readUnsignedShort();
    }

    private static String scanJar(File jar, int maxMajor) throws IOException {
        if (!jar.getName().endsWith(".jar") && !jar.getName().endsWith(".zip")) return null;
        try (ZipFile zip = new ZipFile(jar)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                // Multi-release overlays and module descriptors are not packaged into the APK by D8.
                if (entry.isDirectory() || !name.endsWith(".class") || name.startsWith("META-INF/versions/")
                        || name.endsWith("module-info.class")) continue;
                try (InputStream in = zip.getInputStream(entry)) {
                    int major = readMajor(in);
                    if (major > maxMajor) return describe(jar.getName(), name, major, maxMajor);
                }
            }
        }
        return null;
    }

    private static String scanDirectory(File root, File dir, int maxMajor) throws IOException {
        File[] children = dir.listFiles();
        if (children == null) return null;
        for (File child : children) {
            if (child.isDirectory()) {
                String nested = scanDirectory(root, child, maxMajor);
                if (nested != null) return nested;
            } else if (child.getName().endsWith(".class") && !child.getName().equals("module-info.class")) {
                try (InputStream in = new java.io.FileInputStream(child)) {
                    int major = readMajor(in);
                    if (major > maxMajor) return describe(root.getName(), root.toURI().relativize(child.toURI()).getPath(), major, maxMajor);
                }
            }
        }
        return null;
    }

    private static String describe(String container, String entry, int major, int maxMajor) {
        return container + " contains " + entry + " compiled for Java " + javaVersion(major)
                + ", but only Java " + javaVersion(maxMajor) + " is supported. Use a Java " + javaVersion(maxMajor)
                + "-compatible version of this library.";
    }
}
