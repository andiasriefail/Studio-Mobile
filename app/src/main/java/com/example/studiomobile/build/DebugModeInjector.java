package com.example.studiomobile.build;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Adds the crash catcher to the build copy of a project when Debug Mode is on.
 *
 * <p>Only the temporary build copy is changed, never the user's own project. Two classes are added
 * (a ContentProvider that installs the crash handler before Application.onCreate, and a full-screen
 * crash page) and both are declared in the copied manifest.
 */
public final class DebugModeInjector {
    static final String PACKAGE_PATH = "com/studiomobile/debugcatcher";
    static final String PROVIDER_CLASS = "com.studiomobile.debugcatcher.StudioDebugProvider";
    static final String ACTIVITY_CLASS = "com.studiomobile.debugcatcher.StudioCrashActivity";
    private static final String MARKER = "studio-mobile-debug-mode";
    private static final String[] TEMPLATES = { "StudioDebugProvider", "StudioCrashActivity" };

    private DebugModeInjector() { }

    /**
     * @param buildCopy the temporary copy of the project that is about to be built
     * @return a short message for the build log
     */
    public static String inject(Context context, File buildCopy) throws IOException {
        File manifest = BuildProjectFiles.manifest(buildCopy);
        if (!manifest.isFile()) return "Debug Mode skipped: AndroidManifest.xml was not found.";
        String xml = new String(Files.readAllBytes(manifest.toPath()), StandardCharsets.UTF_8);
        if (xml.contains(MARKER)) return "Debug Mode is already present in this project.";

        String applicationId = BuildProjectFiles.readApplicationId(buildCopy, null);
        if (applicationId == null) applicationId = ManifestMerger.readPackage(manifest);
        if (applicationId == null || applicationId.isEmpty()) return "Debug Mode skipped: the package name could not be determined.";

        String patched = addManifestEntries(xml, applicationId + ".studiodebug");
        if (patched == null) return "Debug Mode skipped: no <application> element found in AndroidManifest.xml.";

        File sourceFolder = new File(BuildProjectFiles.mainDirectory(buildCopy), "java/" + PACKAGE_PATH);
        if (!sourceFolder.isDirectory() && !sourceFolder.mkdirs()) throw new IOException("Could not create " + sourceFolder);
        for (String name : TEMPLATES) {
            writeAsset(context, "debug-mode/" + name + ".java.txt", new File(sourceFolder, name + ".java"));
        }
        Files.write(manifest.toPath(), patched.getBytes(StandardCharsets.UTF_8));
        return "Debug Mode on: crash catcher added to this build (your project files are unchanged).";
    }

    /** Inserts the provider and the crash activity as the last children of &lt;application&gt;. Returns null when there is none. */
    static String addManifestEntries(String xml, String authority) {
        String entries =
            "\n        <!-- " + MARKER + " -->\n"
            + "        <provider android:name=\"" + PROVIDER_CLASS + "\" android:authorities=\"" + authority + "\""
            + " android:exported=\"false\" android:initOrder=\"2147483647\" />\n"
            + "        <activity android:name=\"" + ACTIVITY_CLASS + "\" android:exported=\"false\""
            + " android:process=\":studio_debug\" android:taskAffinity=\"\" android:excludeFromRecents=\"true\""
            + " android:theme=\"@android:style/Theme.Material.Light.NoActionBar\" />\n    ";
        int close = xml.lastIndexOf("</application>");
        if (close >= 0) return xml.substring(0, close) + entries + xml.substring(close);
        Matcher open = Pattern.compile("<application\\b[^>]*?/>").matcher(xml);
        if (open.find()) {
            String tag = open.group();
            String opened = tag.substring(0, tag.length() - 2).replaceAll("\\s+$", "") + ">";
            return xml.substring(0, open.start()) + opened + entries + "</application>" + xml.substring(open.end());
        }
        return null;
    }

    private static void writeAsset(Context context, String assetPath, File target) throws IOException {
        try (InputStream in = context.getAssets().open(assetPath); FileOutputStream out = new FileOutputStream(target)) {
            byte[] buffer = new byte[8192]; int count;
            while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
        }
    }
}
