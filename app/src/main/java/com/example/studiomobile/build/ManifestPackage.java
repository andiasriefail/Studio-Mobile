package com.example.studiomobile.build;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Modern Android Studio projects declare the app package as {@code namespace} in build.gradle and leave {@code package} out of
 * AndroidManifest.xml. AAPT2 refuses such a manifest ("<manifest> must have a 'package' attribute"), so, like AGP does, the package
 * is injected before linking: {@code namespace}, or {@code applicationId} when the project has no namespace.
 * A manifest that already has a package attribute is used unchanged.
 */
public final class ManifestPackage {
    private static final Pattern NAMESPACE = Pattern.compile("\\bnamespace\\s*(?:=\\s*)?\\(?\\s*[\"']([A-Za-z0-9_.]+)[\"']");
    private static final Pattern MANIFEST_TAG = Pattern.compile("<manifest\\b");

    private ManifestPackage() { }

    /** The namespace literal of the module Gradle file, or null. */
    public static String readNamespace(File project) {
        File module = BuildProjectFiles.moduleDirectory(project);
        File script = new File(module, "build.gradle");
        if (!script.isFile()) script = new File(module, "build.gradle.kts");
        if (!script.isFile()) return null;
        try {
            Matcher matcher = NAMESPACE.matcher(new String(Files.readAllBytes(script.toPath()), StandardCharsets.UTF_8));
            return matcher.find() ? matcher.group(1) : null;
        } catch (IOException unreadable) { return null; }
    }

    /** The package that class names in the manifest are relative to: manifest attribute, else namespace, else applicationId (or null). */
    public static String effectivePackage(File project, File manifest) {
        try {
            if (manifest != null && manifest.isFile()) {
                String declared = ManifestMerger.readPackage(manifest);
                if (declared != null) return declared;
            }
        } catch (IOException | RuntimeException unreadable) { /* fall through to the Gradle file */ }
        String namespace = readNamespace(project);
        return namespace != null ? namespace : BuildProjectFiles.readApplicationId(project, null);
    }

    /**
     * Returns {@code manifest} itself when it declares a package, otherwise a copy written to {@code workDirectory}
     * with {@code package="..."} added to the root element.
     */
    public static File withPackage(File manifest, File project, File workDirectory) throws IOException {
        if (ManifestMerger.readPackage(manifest) != null) return manifest;
        return withPackage(manifest, effectivePackage(project, manifest), workDirectory);
    }

    /**
     * Same as {@link #withPackage(File, File, File)} but with an explicit package (for example the namespace of a library module),
     * instead of one looked up in the project's Gradle file.
     */
    public static File withPackage(File manifest, String packageName, File workDirectory) throws IOException {
        if (ManifestMerger.readPackage(manifest) != null) return manifest;
        if (packageName == null)
            throw new IllegalStateException("AndroidManifest.xml has no package attribute and the module build file declares neither namespace nor applicationId.");
        String source = new String(Files.readAllBytes(manifest.toPath()), StandardCharsets.UTF_8);
        Matcher root = MANIFEST_TAG.matcher(source);
        if (!root.find()) throw new IllegalStateException("AndroidManifest.xml has no <manifest> element.");
        String patched = source.substring(0, root.end()) + " package=\"" + packageName + "\"" + source.substring(root.end());
        if (!workDirectory.isDirectory() && !workDirectory.mkdirs()) throw new IOException("Could not create " + workDirectory);
        File copy = new File(workDirectory, "AndroidManifest.xml");
        Files.write(copy.toPath(), patched.getBytes(StandardCharsets.UTF_8));
        return copy;
    }
}
