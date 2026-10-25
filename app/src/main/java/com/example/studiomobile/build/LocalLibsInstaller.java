package com.example.studiomobile.build;

import android.content.Context;
import android.content.res.AssetManager;
import com.example.studiomobile.workspace.StudioStorage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
        installMaven(context, assets, log);
        return log.toString().trim();
    }

    private static void installMaven(Context context, AssetManager assets, StringBuilder log) {
        try {
            String[] top = assets.list(ASSET_DIR + "/maven");
            if (top == null || top.length == 0) return;
            File cache = StudioStorage.localLibsDir(context);
            File marker = new File(cache, ".bundled-maven");
            String stamp = String.valueOf(context.getPackageManager().getPackageInfo(context.getPackageName(), 0).lastUpdateTime);
            if (marker.isFile() && stamp.equals(new String(Files.readAllBytes(marker.toPath()), StandardCharsets.UTF_8))) return;
            int copied = copyTree(assets, ASSET_DIR + "/maven", cache);
            Files.write(marker.toPath(), stamp.getBytes(StandardCharsets.UTF_8));
            log.append("Bundled libraries: ").append(copied).append(" file(s) installed\n");
        } catch (Exception e) { log.append("Bundled libraries failed: ").append(e.getMessage()).append('\n'); }
    }

    private static int copyTree(AssetManager assets, String from, File to) throws IOException {
        String[] children = assets.list(from);
        if (children == null) return 0;
        int copied = 0;
        for (String child : children) {
            String path = from + "/" + child;
            File target = new File(to, child);
            String[] nested = assets.list(path);
            if (nested != null && nested.length > 0) { copied += copyTree(assets, path, target); continue; }
            if (target.isFile() && target.length() > 0) continue;
            if (!target.getParentFile().isDirectory() && !target.getParentFile().mkdirs()) throw new IOException("Could not create " + target.getParentFile());
            File temporary = new File(target.getParentFile(), target.getName() + ".partial");
            try (InputStream in = assets.open(path)) { Files.copy(in, temporary.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
            if (!temporary.renameTo(target)) { temporary.delete(); throw new IOException("Could not install " + target); }
            copied++;
        }
        return copied;
    }
}
