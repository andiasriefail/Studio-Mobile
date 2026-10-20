package com.example.studiomobile.workspace;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Writes the starting files of a new single-module Android project (Groovy DSL, version catalog, ProGuard rules file,
 * manifest, string/theme resources and one Activity). Pure Java; it never overwrites an existing file and removes
 * everything it wrote if a later write fails.
 *
 * Like Android Studio, the manifest has no {@code package} attribute; the package comes from {@code namespace} in app/build.gradle.
 * (The in-process pipeline injects it before AAPT2 link, see ManifestPackage.)
 */
public final class ProjectScaffoldGenerator {
    private static final Pattern PACKAGE_NAME = Pattern.compile("^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+$");
    private static final String AGP_VERSION = "8.13.1";

    private ProjectScaffoldGenerator() { }

    /**
     * @param projectDirectory existing directory that receives the files
     * @param projectName      used as rootProject.name
     * @param applicationLabel launcher label; falls back to projectName when blank
     * @param packageName      applicationId, namespace and Java package, for example com.example.myapp
     * @return project-relative paths of the files that were created
     */
    public static List<String> generate(File projectDirectory, String projectName, String applicationLabel, String packageName) throws IOException {
        if (projectDirectory == null || !projectDirectory.isDirectory()) throw new IllegalArgumentException("projectDirectory must be an existing directory");
        if (projectName == null || projectName.trim().isEmpty()) throw new IllegalArgumentException("projectName is required");
        if (packageName == null || !PACKAGE_NAME.matcher(packageName).matches()) throw new IllegalArgumentException("Invalid package name: " + packageName);
        String label = applicationLabel == null || applicationLabel.trim().isEmpty() ? projectName.trim() : applicationLabel.trim();

        Map<String, String> files = new LinkedHashMap<>();
        files.put("settings.gradle", settingsGradle(projectName.trim()));
        files.put("build.gradle", ROOT_BUILD_GRADLE);
        files.put("gradle/libs.versions.toml", VERSION_CATALOG);
        files.put("app/build.gradle", appBuildGradle(packageName));
        files.put("app/proguard-rules.pro", PROGUARD_RULES);
        files.put("app/src/main/AndroidManifest.xml", manifest(packageName));
        files.put("app/src/main/java/" + packageName.replace('.', '/') + "/MainActivity.java", mainActivity(packageName));
        files.put("app/src/main/res/values/strings.xml", strings(label));
        files.put("app/src/main/res/values/themes.xml", THEMES);

        List<File> createdFiles = new ArrayList<>();
        List<File> createdDirectories = new ArrayList<>();
        List<String> created = new ArrayList<>();
        try {
            for (Map.Entry<String, String> entry : files.entrySet()) {
                File target = new File(projectDirectory, entry.getKey());
                if (target.exists()) continue;
                makeDirectories(target.getParentFile(), createdDirectories);
                Files.write(target.toPath(), entry.getValue().getBytes(StandardCharsets.UTF_8));
                createdFiles.add(target);
                created.add(entry.getKey());
            }
        } catch (IOException | RuntimeException error) {
            for (int i = createdFiles.size() - 1; i >= 0; i--) createdFiles.get(i).delete();
            for (int i = createdDirectories.size() - 1; i >= 0; i--) createdDirectories.get(i).delete();
            throw error;
        }
        return created;
    }

    private static void makeDirectories(File directory, List<File> createdDirectories) throws IOException {
        if (directory == null || directory.isDirectory()) return;
        makeDirectories(directory.getParentFile(), createdDirectories);
        if (!directory.mkdir() && !directory.isDirectory()) throw new IOException("Could not create folder: " + directory.getName());
        createdDirectories.add(directory);
    }

    // ---------------------------------------------------------------- templates

    private static String settingsGradle(String projectName) {
        return "pluginManagement {\n"
            + "    repositories {\n        google()\n        mavenCentral()\n        gradlePluginPortal()\n    }\n}\n\n"
            + "dependencyResolutionManagement {\n"
            + "    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)\n"
            + "    repositories {\n        google()\n        mavenCentral()\n    }\n}\n\n"
            + "rootProject.name = \"" + groovyString(projectName) + "\"\n"
            + "include ':app'\n";
    }

    private static final String ROOT_BUILD_GRADLE =
        "// Top-level build file. Plugin versions come from gradle/libs.versions.toml.\n"
        + "plugins {\n    alias(libs.plugins.android.application) apply false\n}\n";

    private static final String VERSION_CATALOG =
        "# Version catalog. Studio Mobile resolves a library when build.gradle references it as libs.<alias>.\n"
        + "# Not supported yet: libs.bundles.*, BOM/platform() versions, dynamic versions (+), classifiers.\n"
        + "[versions]\n"
        + "agp = \"" + AGP_VERSION + "\"\n\n"
        + "[libraries]\n"
        + "# Example: androidx-appcompat = { module = \"androidx.appcompat:appcompat\", version = \"1.7.0\" }\n\n"
        + "[plugins]\n"
        + "android-application = { id = \"com.android.application\", version.ref = \"agp\" }\n";

    private static String appBuildGradle(String packageName) {
        return "plugins {\n    alias(libs.plugins.android.application)\n}\n\n"
            + "android {\n"
            + "    namespace = \"" + packageName + "\"\n"
            + "    compileSdk = 36\n\n"
            + "    defaultConfig {\n"
            + "        applicationId = \"" + packageName + "\"\n"
            + "        minSdk = 24\n"
            + "        targetSdk = 36\n"
            + "        versionCode = 1\n"
            + "        versionName = \"1.0\"\n"
            + "    }\n\n"
            + "    buildTypes {\n"
            + "        release {\n"
            + "            // Set to true to shrink and obfuscate with R8 (rules: proguard-rules.pro).\n"
            + "            minifyEnabled = false\n"
            + "            proguardFiles getDefaultProguardFile(\"proguard-android-optimize.txt\"), \"proguard-rules.pro\"\n"
            + "        }\n"
            + "    }\n\n"
            + "    compileOptions {\n"
            + "        sourceCompatibility = JavaVersion.VERSION_17\n"
            + "        targetCompatibility = JavaVersion.VERSION_17\n"
            + "    }\n"
            + "}\n\n"
            + "dependencies {\n"
            + "    // Add libraries declared in gradle/libs.versions.toml, for example:\n"
            + "    // implementation(libs.androidx.appcompat)\n"
            + "}\n";
    }

    private static final String PROGUARD_RULES =
        "# Project-specific R8/ProGuard rules. Used when minifyEnabled = true in app/build.gradle.\n"
        + "#\n"
        + "# Keep a class and its members from being renamed or removed:\n"
        + "#-keep class com.example.model.** { *; }\n"
        + "#\n"
        + "# Silence warnings about classes a library references but does not ship:\n"
        + "#-dontwarn com.example.optional.**\n"
        + "#\n"
        + "# Keep readable stack traces (already on when using the default rules); mapping.txt is saved next to the APK:\n"
        + "#-keepattributes SourceFile,LineNumberTable\n";

    private static String manifest(String packageName) {
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
            + "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\">\n\n"
            + "    <application\n"
            + "        android:allowBackup=\"true\"\n"
            + "        android:label=\"@string/app_name\"\n"
            + "        android:theme=\"@style/Theme.App\">\n"
            + "        <activity\n"
            + "            android:name=\".MainActivity\"\n"
            + "            android:exported=\"true\">\n"
            + "            <intent-filter>\n"
            + "                <action android:name=\"android.intent.action.MAIN\" />\n"
            + "                <category android:name=\"android.intent.category.LAUNCHER\" />\n"
            + "            </intent-filter>\n"
            + "        </activity>\n"
            + "    </application>\n\n"
            + "</manifest>\n";
    }

    private static String mainActivity(String packageName) {
        return "package " + packageName + ";\n\n"
            + "import android.app.Activity;\n"
            + "import android.os.Bundle;\n"
            + "import android.view.Gravity;\n"
            + "import android.widget.TextView;\n\n"
            + "public class MainActivity extends Activity {\n"
            + "    @Override\n"
            + "    protected void onCreate(Bundle savedInstanceState) {\n"
            + "        super.onCreate(savedInstanceState);\n"
            + "        TextView label = new TextView(this);\n"
            + "        label.setText(getString(R.string.app_name));\n"
            + "        label.setTextSize(24);\n"
            + "        label.setGravity(Gravity.CENTER);\n"
            + "        setContentView(label);\n"
            + "    }\n"
            + "}\n";
    }

    private static String strings(String label) {
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
            + "<resources>\n"
            + "    <string name=\"app_name\">" + androidString(label) + "</string>\n"
            + "</resources>\n";
    }

    private static final String THEMES =
        "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
        + "<resources>\n"
        + "    <style name=\"Theme.App\" parent=\"android:Theme.Material.Light.DarkActionBar\" />\n"
        + "</resources>\n";

    // ---------------------------------------------------------------- escaping

    /** Escapes a value for a double-quoted Groovy string. */
    static String groovyString(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$").replace("\n", " ");
    }

    /** Escapes a value for an Android string resource (XML text plus aapt2's own quoting rules). */
    static String androidString(String value) {
        String text = value.replace("\\", "\\\\").replace("'", "\\'").replace("\"", "\\\"")
            .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\n", " ");
        if (text.startsWith("@") || text.startsWith("?")) text = "\\" + text;
        return text;
    }
}
