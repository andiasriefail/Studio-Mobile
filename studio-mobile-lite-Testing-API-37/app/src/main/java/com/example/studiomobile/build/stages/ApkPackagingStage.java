package com.example.studiomobile.build.stages;

import com.example.studiomobile.build.BuildLogger;
import com.example.studiomobile.build.BuildProjectFiles;
import com.example.studiomobile.build.BuildStage;
import com.example.studiomobile.build.BuildStageContext;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Packages the AAPT2 resource APK, the DEX files, native libraries and Java resources into an unsigned APK.
 *
 * <p>Sources, in priority order (the first entry with a given name wins; the app always beats its libraries):
 * the resource APK, the DEX files, the C/C++ libraries built by {@code NativeBuildStage} ({@code nativeLibsDirectory}), {@code src/main/jniLibs/<abi>/*.so}, {@code src/main/resources/**}, the {@code jni/<abi>/*.so}
 * of every extracted AAR, and finally the non-class files of the local and resolved dependency JARs.
 * {@code META-INF/services/*} files with the same name are merged instead of overwritten.
 * {@code .so} files are written uncompressed (STORED); {@link com.example.studiomobile.build.ZipAligner} aligns them afterwards.
 * Excluded names match the {@code packaging.resources.excludes} list of this project's build.gradle.
 */
public final class ApkPackagingStage implements BuildStage {
    private static final String SERVICES_PREFIX = "META-INF/services/";
    private static final List<String> SUPPORTED_ABIS = Arrays.asList("armeabi-v7a", "arm64-v8a", "x86", "x86_64", "riscv64");

    @Override public String getName() { return "APK packaging"; }

    @Override public void execute(BuildStageContext context, BuildLogger logger) throws Exception {
        File resourceApk = context.getOutput("resourceApk"), dexDirectory = context.getOutput("dexDirectory");
        if (resourceApk == null || !resourceApk.isFile()) throw new IllegalStateException("Linked resource APK is missing.");
        if (dexDirectory == null || !dexDirectory.isDirectory()) throw new IllegalStateException("DEX output directory is missing.");
        File unsignedApk = new File(context.getWorkingDirectory(), "unsigned.apk");
        Packer packer = new Packer(logger);
        try (ZipOutputStream output = new ZipOutputStream(new FileOutputStream(unsignedApk))) {
            packer.copyResourceApk(output, resourceApk);
            packer.addDexFiles(output, dexDirectory);
            File main = BuildProjectFiles.mainDirectory(context.getProjectDirectory());
            // Libraries compiled from C/C++ by NativeBuildStage win over any stale prebuilt copy in src/main/jniLibs.
            File builtNativeLibraries = context.getOutput("nativeLibsDirectory");
            if (builtNativeLibraries != null && builtNativeLibraries.isDirectory()) packer.addNativeLibraries(output, builtNativeLibraries, "native-libs");
            packer.addNativeLibraries(output, new File(main, "jniLibs"), "jniLibs");
            // A Dart program packed by DartAppStage: the Dart VM as lib/arm64-v8a/libdartvm.so and the program as assets/dart/main.dill.
            File dartNative = context.getOutput(DartAppStage.NATIVE_OUTPUT_KEY), dartPackage = context.getOutput(DartAppStage.PACKAGE_OUTPUT_KEY);
            if (dartNative != null && dartNative.isDirectory()) packer.addNativeLibraries(output, dartNative, "dart");
            if (dartPackage != null && dartPackage.isDirectory()) packer.addResourceTree(output, dartPackage);
            packer.addResourceTree(output, new File(main, "resources"));
            for (com.example.studiomobile.build.ProjectModules.Module library : com.example.studiomobile.build.ProjectModules.load(context.getProjectDirectory()).libraries)
                packer.addResourceTree(output, new File(library.mainDirectory(), "resources"));
            File processorResources = context.getOutput("processorResources");
            if (processorResources != null) packer.addResourceTree(output, processorResources);
            for (File aar : context.getFiles("aarDirectories")) packer.addNativeLibraries(output, new File(aar, "jni"), aar.getName() + "/jni");
            List<File> jars = new ArrayList<>(BuildProjectFiles.localLibraries(context.getProjectDirectory()));
            jars.addAll(BuildProjectFiles.jarsIn(context.getOutput("dependencyDirectory")));
            for (File jar : jars) packer.addJarResources(output, jar);
            packer.writeMergedServices(output);
        }
        if (!unsignedApk.isFile() || unsignedApk.length() == 0) throw new IllegalStateException("APK packaging produced no output.");
        context.putOutput("unsignedApk", unsignedApk);
        if (logger != null) {
            logger.onInfo("Packaged " + packer.nativeCount + " native librar" + (packer.nativeCount == 1 ? "y" : "ies") + ", " + packer.resourceCount + " resource file(s), "
                + packer.services.size() + " merged service file(s); skipped " + packer.duplicateCount + " duplicate entr" + (packer.duplicateCount == 1 ? "y" : "ies") + ".");
            logger.onInfo("Created unsigned APK archive: " + unsignedApk.getAbsolutePath());
        }
    }

    /** True for entries that must never be copied from a dependency or from src/main/resources. Names are normalized first. */
    static boolean isExcluded(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith("/") || lower.endsWith(".class")) return true;
        if (lower.startsWith("meta-inf/versions/") || lower.startsWith("license/")) return true;
        if (lower.startsWith("meta-inf/")) {
            String rest = lower.substring("meta-inf/".length());
            if (rest.indexOf('/') < 0) {
                if (rest.equals("manifest.mf") || rest.equals("dependencies")) return true;
                if (rest.endsWith(".sf") || rest.endsWith(".rsa") || rest.endsWith(".dsa") || rest.endsWith(".kotlin_module")) return true;
                if (rest.startsWith("license") || rest.startsWith("notice")) return true;
            }
        }
        return false;
    }

    /** Converts to forward slashes and rejects names that could escape the archive root. */
    static String safeName(String raw) throws IOException {
        String name = raw.replace('\\', '/');
        if (name.startsWith("/")) throw new IOException("Unsafe archive entry name (absolute path): " + raw);
        for (String segment : name.split("/")) if (segment.equals("..")) throw new IOException("Unsafe archive entry name (contains ..): " + raw);
        return name;
    }

    private static final class Packer {
        final Set<String> names = new HashSet<>();
        final Map<String, LinkedHashSet<String>> services = new LinkedHashMap<>();
        final BuildLogger logger; int nativeCount, resourceCount, duplicateCount;
        final byte[] buffer = new byte[32768];
        Packer(BuildLogger logger) { this.logger = logger; }

        void copyResourceApk(ZipOutputStream output, File resourceApk) throws IOException {
            try (ZipFile resources = new ZipFile(resourceApk)) {
                Enumeration<? extends ZipEntry> entries = resources.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    if (entry.isDirectory()) continue;
                    String name = safeName(entry.getName());
                    if (!names.add(name)) throw new IOException("Duplicate resource APK entry: " + name);
                    ZipEntry copy = new ZipEntry(name);
                    // resources.arsc must stay uncompressed for apps targeting API 30+; keep whatever AAPT2 chose.
                    if (entry.getMethod() == ZipEntry.STORED) { copy.setMethod(ZipEntry.STORED); copy.setSize(entry.getSize()); copy.setCompressedSize(entry.getSize()); copy.setCrc(entry.getCrc()); }
                    output.putNextEntry(copy);
                    try (InputStream input = resources.getInputStream(entry)) { copyStream(input, output); }
                    output.closeEntry();
                }
            }
        }

        void addDexFiles(ZipOutputStream output, File dexDirectory) throws IOException {
            File[] dexFiles = dexDirectory.listFiles((directory, name) -> name.matches("classes(?:[2-9]|[1-9][0-9]+)?\\.dex"));
            if (dexFiles == null || dexFiles.length == 0) throw new IOException("No DEX files were generated.");
            Arrays.sort(dexFiles, (a, b) -> a.getName().compareTo(b.getName()));
            for (File dex : dexFiles) {
                if (!names.add(dex.getName())) throw new IOException("Duplicate APK entry: " + dex.getName());
                addDeflated(output, dex.getName(), dex);
            }
        }

        /** Adds {@code <directory>/<abi>/*.so} as {@code lib/<abi>/*.so}, uncompressed. */
        void addNativeLibraries(ZipOutputStream output, File directory, String label) throws IOException {
            File[] abiDirectories = directory.listFiles();
            if (abiDirectories == null) return;
            Arrays.sort(abiDirectories);
            for (File abiDirectory : abiDirectories) {
                if (!abiDirectory.isDirectory()) continue;
                String abi = abiDirectory.getName();
                if (!SUPPORTED_ABIS.contains(abi)) { if (logger != null) logger.onWarning("Ignoring native libraries in " + label + "/" + abi + ": not a supported ABI (" + SUPPORTED_ABIS + ")."); continue; }
                File[] libraries = abiDirectory.listFiles((dir, name) -> name.endsWith(".so"));
                if (libraries == null) continue;
                Arrays.sort(libraries);
                for (File library : libraries) {
                    if (!library.isFile()) continue;
                    String name = safeName("lib/" + abi + "/" + library.getName());
                    if (!names.add(name)) { duplicateCount++; continue; }
                    addStored(output, name, library); nativeCount++;
                }
            }
        }

        /** Adds every file below {@code src/main/resources} at the archive root. */
        void addResourceTree(ZipOutputStream output, File root) throws IOException {
            if (root.isDirectory()) walk(output, root, "");
        }
        private void walk(ZipOutputStream output, File directory, String prefix) throws IOException {
            File[] children = directory.listFiles();
            if (children == null) return;
            Arrays.sort(children);
            for (File child : children) {
                String name = safeName(prefix + child.getName());
                if (child.isDirectory()) { walk(output, child, name + "/"); continue; }
                if (!child.isFile() || isExcluded(name)) continue;
                if (name.startsWith(SERVICES_PREFIX)) { collectService(name, readAll(new FileInputStream(child))); continue; }
                if (!names.add(name)) { duplicateCount++; continue; }
                addDeflated(output, name, child); resourceCount++;
            }
        }

        /** Adds the non-class files of one JAR (META-INF/services are collected for merging). */
        void addJarResources(ZipOutputStream output, File jar) throws IOException {
            try (ZipFile zip = new ZipFile(jar)) {
                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    if (entry.isDirectory()) continue;
                    String name = safeName(entry.getName());
                    if (isExcluded(name)) continue;
                    if (name.startsWith(SERVICES_PREFIX)) { try (InputStream in = zip.getInputStream(entry)) { collectService(name, readAll(in)); } continue; }
                    if (!names.add(name)) { duplicateCount++; continue; }
                    output.putNextEntry(new ZipEntry(name));
                    try (InputStream in = zip.getInputStream(entry)) { copyStream(in, output); }
                    output.closeEntry(); resourceCount++;
                }
            }
        }

        private void collectService(String name, byte[] content) {
            LinkedHashSet<String> lines = services.get(name);
            if (lines == null) { lines = new LinkedHashSet<>(); services.put(name, lines); }
            for (String line : new String(content, StandardCharsets.UTF_8).split("\\r?\\n")) { String trimmed = line.trim(); if (!trimmed.isEmpty()) lines.add(trimmed); }
        }

        void writeMergedServices(ZipOutputStream output) throws IOException {
            for (Map.Entry<String, LinkedHashSet<String>> service : services.entrySet()) {
                if (!names.add(service.getKey())) { duplicateCount++; continue; }
                output.putNextEntry(new ZipEntry(service.getKey()));
                output.write((String.join("\n", service.getValue()) + "\n").getBytes(StandardCharsets.UTF_8));
                output.closeEntry();
            }
        }

        private void addDeflated(ZipOutputStream output, String name, File file) throws IOException {
            output.putNextEntry(new ZipEntry(name));
            try (InputStream in = new FileInputStream(file)) { copyStream(in, output); }
            output.closeEntry();
        }

        /** STORED entries need size and CRC up front, so the file is read twice. */
        private void addStored(ZipOutputStream output, String name, File file) throws IOException {
            CRC32 crc = new CRC32(); long size = 0;
            try (InputStream in = new FileInputStream(file)) { int count; while ((count = in.read(buffer)) != -1) { crc.update(buffer, 0, count); size += count; } }
            ZipEntry entry = new ZipEntry(name);
            entry.setMethod(ZipEntry.STORED); entry.setSize(size); entry.setCompressedSize(size); entry.setCrc(crc.getValue());
            output.putNextEntry(entry);
            try (InputStream in = new FileInputStream(file)) { copyStream(in, output); }
            output.closeEntry();
        }

        private void copyStream(InputStream in, ZipOutputStream out) throws IOException { int count; while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count); }
        private byte[] readAll(InputStream in) throws IOException {
            try (InputStream stream = in) { ByteArrayOutputStream out = new ByteArrayOutputStream(); int count; while ((count = stream.read(buffer)) != -1) out.write(buffer, 0, count); return out.toByteArray(); }
        }
    }
}
