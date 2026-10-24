package com.example.studiomobile.build;

import android.content.Context;
import android.content.res.AssetManager;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Installs the jars the developer bundled into the app at src/main/assets/local_libs/ into files/build-tools,
 * where the build pipeline looks for them. Nothing is downloaded and nothing is asked from the user.
 *
 * Accepted file names in local_libs/:
 *   android.jar | android-37.jar | platform-37.0_r02.zip   (API level = first number after "android-" / "platform-", default DEFAULT_API)
 *   kotlin-stdlib-2.4.21.jar (any kotlin-stdlib*.jar, not -sources/-common/-jdk*)
 * Already installed files are left alone (a bundled file is only copied if the destination is missing).
 */
public final class LocalLibsInstaller {
    public static final String ASSET_DIR = "local_libs";
    public static final int DEFAULT_API = 37;
    private static final Pattern API = Pattern.compile("(?:android|platform)-(\\d+)");
    private LocalLibsInstaller() { }

    /** Returns a human-readable log of what was installed or skipped. Safe to call repeatedly. */
    public static String install(Context context) {
        StringBuilder log = new StringBuilder();
        File root = new File(context.getFilesDir(), "build-tools");
        AssetManager assets = context.getAssets();
        String[] names;
        try { names = assets.list(ASSET_DIR); } catch (IOException e) { return "Could not read bundled local_libs: " + e.getMessage(); }
        if (names == null || names.length == 0) return "No bundled local_libs found in the app (put files in app/src/main/assets/local_libs/ and rebuild Studio Mobile).";
        if (!root.isDirectory() && !root.mkdirs()) return "Could not create app-private build-tools directory: " + root;
        for (String name : names) {
            String lower = name.toLowerCase(java.util.Locale.ROOT);
            if (name.startsWith(".") || lower.endsWith(".partial") || lower.endsWith(".tmp")) {
                log.append("Skipped incomplete/temporary asset: ").append(name).append('\n');
                continue;
            }
            try {
                if (lower.endsWith(".jar") || lower.endsWith(".zip")) {
                    if (lower.startsWith("kotlin-stdlib") && lower.endsWith(".jar")) {
                        if (lower.contains("sources") || lower.contains("common") || lower.contains("jdk")) continue;
                        if (new File(root, "kotlin-stdlib.jar").length() > 0) { log.append("kotlin-stdlib: already installed\n"); continue; }
                        try (InputStream in = assets.open(ASSET_DIR + "/" + name)) { log.append(ToolchainImporter.importKotlinStdlib(in, root).summary).append('\n'); }
                    } else if (lower.startsWith("android") || lower.startsWith("platform")) {
                        Matcher matcher = API.matcher(lower);
                        int api = matcher.find() ? Integer.parseInt(matcher.group(1)) : DEFAULT_API;
                        if (new File(root, "platforms/android-" + api + "/android.jar").length() > 0) { log.append("android.jar API ").append(api).append(": already installed\n"); continue; }
                        try (InputStream in = assets.open(ASSET_DIR + "/" + name)) { log.append(ToolchainImporter.importAndroidPlatform(in, api, root).summary).append('\n'); }
                    }
                }
            } catch (Exception e) { log.append("local_libs/").append(name).append(" failed: ").append(e.getMessage()).append('\n'); }
        }
        return log.toString().trim();
    }
}
