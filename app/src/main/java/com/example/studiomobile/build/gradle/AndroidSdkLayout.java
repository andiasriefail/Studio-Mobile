package com.example.studiomobile.build.gradle;

import com.example.studiomobile.build.BuildLogger;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Presents the platform jars Studio Mobile installed under {@code files/build-tools/platforms/android-<API>/android.jar} as a
 * minimal Android SDK at {@code files/android-sdk}, which is where the Gradle build (through local.properties sdk.dir) looks for them.
 * Nothing is downloaded. Links are used so the jars are not duplicated; a copy is made only when a link cannot be created.
 *
 * <p>A newer android.jar contains every API of the older ones, so a project that asks for an older platform (for example
 * compileSdk 36 while API 37 is installed) is served by the newest installed jar instead of failing. The Android Gradle Plugin
 * only treats a platform or build-tools folder as installed when it contains a package.xml, so that file is written as well.
 */
public final class AndroidSdkLayout {
    private static final Pattern PLATFORM = Pattern.compile("android-(\\d+)");
    private static final Pattern NEEDS_PLATFORM = Pattern.compile("platforms;android-(\\d+)");
    /** AGP's own wording when no platform folder matches compileSdk: "Failed to find target with hash string 'android-36' in: ...". */
    private static final Pattern NEEDS_TARGET = Pattern.compile("Failed to find target with hash string 'android-(\\d+)(\\.\\d+)?'");
    private static final Pattern NEEDS_TOOLS = Pattern.compile("build-tools;(\\d+)(?:\\.(\\d+))?(?:\\.(\\d+))?");
    /** AGP's wording when a registered build-tools folder lacks files: "Installed Build Tools revision 36.0.0 is corrupted" / "Build-tool 36.0.0 is missing AAPT at ...". */
    private static final Pattern NEEDS_TOOLS_REPAIR = Pattern.compile("(?:Build Tools revision|Build-tool) (\\d+)\\.(\\d+)\\.(\\d+) is (?:corrupted|missing)");
    private static final Pattern TOOLS_VERSION = Pattern.compile("(\\d+)\\.(\\d+)\\.(\\d+)");
    /**
     * Files AGP's build-tools check looks for. Only their presence is tested: aapt2 comes from android.aapt2FromMavenOverride and D8/R8
     * from Maven, so these are placeholders. The list is the full set of a real build-tools package, so no revision is reported missing a file.
     */
    private static final String[] TOOL_FILES = {"aapt", "aapt2", "aidl", "apksigner", "bcc_compat", "d8", "dx", "dexdump", "llvm-rs-cc", "split-select", "zipalign", "lld-bin/lld"};
    private static final String[] TOOL_JARS = {"lib/d8.jar", "lib/dx.jar", "lib/apksigner.jar", "core-lambda-stubs.jar"};
    private static final String[] TOOL_DIRECTORIES = {"renderscript/include", "renderscript/clang-include", "renderscript/lib"};
    private static final byte[] EMPTY_ZIP = {0x50, 0x4B, 0x05, 0x06, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};
    /** AGP builds a JDK image from platforms/android-N/core-for-system-modules.jar for compileSdk 34 and higher. */
    private static final int CORE_MODULES_MIN_API = 34;
    private static final String[] CORE_PACKAGES = {"java/", "javax/", "org/w3c/dom/", "org/xml/sax/"};
    private static final Pattern COMPILE_SDK_RELEASE = Pattern.compile("compileSdk\\s*\\{[^}]*?release\\(\\s*(\\d+)");
    private static final Pattern COMPILE_SDK = Pattern.compile("compileSdk(?:Version)?\\s*=?\\s*(\\d+)");
    private static final String XML_HEAD = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><ns2:repository "
        + "xmlns:ns2=\"http://schemas.android.com/repository/android/common/02\" "
        + "xmlns:ns3=\"http://schemas.android.com/repository/android/generic/02\" "
        + "xmlns:ns4=\"http://schemas.android.com/sdk/android/repo/addon2/02\" "
        + "xmlns:ns5=\"http://schemas.android.com/sdk/android/repo/repository2/02\" "
        + "xmlns:ns6=\"http://schemas.android.com/sdk/android/repo/sys-img2/02\" "
        + "xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">";
    private AndroidSdkLayout() { }

    /** Same as {@link #ensure(File, File, BuildLogger)} without looking at a project. */
    public static File ensure(File filesDirectory, BuildLogger logger) { return ensure(filesDirectory, null, logger); }

    /**
     * Returns the SDK directory when at least one platform is available in it, otherwise null. Idempotent. When a project is given,
     * the platform its compileSdk asks for is provided by the newest installed jar if that platform itself is not installed.
     */
    public static File ensure(File filesDirectory, File project, BuildLogger logger) {
        File sdk = new File(filesDirectory, "android-sdk");
        File[] installed = new File(filesDirectory, "build-tools/platforms").listFiles();
        if (installed != null) {
            for (File platform : installed) {
                File jar = new File(platform, "android.jar");
                if (!platform.isDirectory() || !PLATFORM.matcher(platform.getName()).matches() || jar.length() == 0) continue;
                File target = new File(sdk, "platforms/" + platform.getName() + "/android.jar");
                String problem = expose(jar, target);
                if (problem != null) logger.onInfo(problem);
                Matcher api = PLATFORM.matcher(platform.getName());
                if (problem == null && api.matches()) {
                    writePlatformMetadata(target.getParentFile(), Integer.parseInt(api.group(1)));
                    provideCoreForSystemModules(target.getParentFile(), Integer.parseInt(api.group(1)), logger);
                }
            }
        }
        repairRegisteredBuildTools(sdk, logger);
        int requested = project == null ? 0 : readCompileSdk(project);
        if (requested > 0) providePlatform(sdk, requested, logger);
        File[] exposed = new File(sdk, "platforms").listFiles();
        if (exposed != null) for (File platform : exposed) if (new File(platform, "android.jar").length() > 0) return sdk;
        return null;
    }

    /**
     * Reads the platforms and build-tools a Gradle failure said were missing ("platforms;android-36", "build-tools;36.0.0") and
     * provides them from what is installed. Returns how many were provided; 0 means nothing could be done.
     */
    public static int provideMissing(File filesDirectory, String gradleOutput, BuildLogger logger) {
        if (gradleOutput == null) return 0;
        File sdk = new File(filesDirectory, "android-sdk");
        int provided = 0;
        Matcher platform = NEEDS_PLATFORM.matcher(gradleOutput);
        java.util.Set<Integer> seenApis = new java.util.HashSet<>();
        while (platform.find()) {
            int api = Integer.parseInt(platform.group(1));
            if (seenApis.add(api) && providePlatform(sdk, api, logger)) provided++;
        }
        Matcher target = NEEDS_TARGET.matcher(gradleOutput);
        java.util.Set<String> seenTargets = new java.util.HashSet<>();
        while (target.find()) {
            int api = Integer.parseInt(target.group(1));
            String minor = target.group(2) == null ? "" : target.group(2);   // "android-36.1" is its own folder name
            if (seenTargets.add(api + minor) && providePlatform(sdk, api, minor, logger)) provided++;
        }
        java.util.Set<String> seenTools = new java.util.HashSet<>();
        for (Pattern pattern : new Pattern[] {NEEDS_TOOLS, NEEDS_TOOLS_REPAIR}) {
            Matcher tools = pattern.matcher(gradleOutput);
            while (tools.find()) {
                int major = Integer.parseInt(tools.group(1));
                int minor = tools.group(2) == null ? 0 : Integer.parseInt(tools.group(2));
                int micro = tools.group(3) == null ? 0 : Integer.parseInt(tools.group(3));
                if (seenTools.add(major + "." + minor + "." + micro) && provideBuildTools(sdk, major, minor, micro, logger)) provided++;
            }
        }
        return provided;
    }

    /** True when platforms/android-<api> is usable afterwards. */
    private static boolean providePlatform(File sdk, int api, BuildLogger logger) { return providePlatform(sdk, api, "", logger); }

    /**
     * @param minor "" or ".1" for a folder named android-36.1
     * An installed jar of the same or a newer API is used first. When only older jars exist the newest of them is used anyway, with a
     * warning: the build then starts instead of stopping at once, and only code that needs the missing newer APIs can fail.
     */
    private static boolean providePlatform(File sdk, int api, String minor, BuildLogger logger) {
        String name = "android-" + api + minor;
        File dir = new File(sdk, "platforms/" + name);
        File jar = new File(dir, "android.jar");
        if (jar.length() > 0) { writePlatformMetadata(dir, api); provideCoreForSystemModules(dir, api, logger); return true; }
        int bestApi = 0; File best = null;
        File[] platforms = new File(sdk, "platforms").listFiles();
        if (platforms != null) {
            for (File platform : platforms) {
                Matcher match = PLATFORM.matcher(platform.getName());
                File candidate = new File(platform, "android.jar");
                if (match.matches() && candidate.length() > 0 && Integer.parseInt(match.group(1)) > bestApi) { bestApi = Integer.parseInt(match.group(1)); best = candidate; }
            }
        }
        if (best == null) { logger.onWarning("Platform " + name + " is needed, but no Android platform jar is installed in Studio Mobile."); return false; }
        try {
            String problem = expose(best.toPath().toRealPath().toFile(), jar);
            if (problem != null) { logger.onInfo(problem); return false; }
        } catch (IOException exception) { logger.onInfo("Platform " + name + " was not prepared (" + exception.getClass().getSimpleName() + ")."); return false; }
        writePlatformMetadata(dir, api);
        provideCoreForSystemModules(dir, api, logger);
        if (bestApi >= api) logger.onInfo("Platform " + name + " is served by the installed android-" + bestApi + " jar.");
        else logger.onWarning("Platform " + name + " is not installed. The older android-" + bestApi + " jar is used instead; code that needs newer Android APIs will not compile.");
        return true;
    }

    /**
     * Registers build-tools/<version> and fills it with every file AGP expects. True when something was written, so a caller knows a
     * new build can behave differently; false when the folder was already complete.
     */
    private static boolean provideBuildTools(File sdk, int major, int minor, int micro, BuildLogger logger) {
        String version = major + "." + minor + "." + micro;
        File dir = new File(sdk, "build-tools/" + version);
        if (!dir.isDirectory() && !dir.mkdirs()) return false;
        try {
            boolean changed = false;
            File packageXml = new File(dir, "package.xml");
            if (!packageXml.isFile()) {
                write(packageXml, XML_HEAD + "<localPackage path=\"build-tools;" + version + "\" obsolete=\"false\">"
                    + "<type-details xsi:type=\"ns3:genericDetailsType\"/><revision><major>" + major + "</major><minor>" + minor + "</minor><micro>" + micro
                    + "</micro></revision><display-name>Android SDK Build-Tools " + major + "</display-name></localPackage></ns2:repository>");
                changed = true;
            }
            File source = new File(dir, "source.properties");
            if (!source.isFile()) {
                write(source, "Pkg.Desc=Android SDK Build-Tools " + major + "\nPkg.Revision=" + version + "\nPkg.Path=build-tools;" + version + "\n");
                changed = true;
            }
            if (fillTools(dir)) changed = true;
            if (changed) logger.onInfo("Build-tools " + version + " is registered in the private SDK (the native tools of this device are used instead).");
            return changed;
        } catch (IOException exception) { logger.onInfo("Build-tools " + version + " was not prepared (" + exception.getClass().getSimpleName() + ")."); return false; }
    }

    /**
     * Creates the placeholder files and folders a real build-tools package has. AGP calls a build-tools folder "corrupted" when one is
     * absent ("Build-tool 36.0.0 is missing AAPT at .../aapt"), and a folder holding only package.xml fails that check. Existing files
     * are never replaced. Returns true when anything was created.
     */
    private static boolean fillTools(File dir) throws IOException {
        boolean created = false;
        for (String name : TOOL_FILES) {
            File file = new File(dir, name);
            if (file.exists()) continue;
            if (file.getParentFile() != null && !file.getParentFile().isDirectory() && !file.getParentFile().mkdirs()) continue;
            write(file, "#!/system/bin/sh\necho 'Studio Mobile: " + name + " is provided natively on this device' >&2\nexit 1\n");
            //noinspection ResultOfMethodCallIgnored
            file.setExecutable(true, false);
            created = true;
        }
        for (String name : TOOL_JARS) {
            File file = new File(dir, name);
            if (file.exists()) continue;
            if (file.getParentFile() != null && !file.getParentFile().isDirectory() && !file.getParentFile().mkdirs()) continue;
            Files.write(file.toPath(), EMPTY_ZIP);
            created = true;
        }
        for (String name : TOOL_DIRECTORIES) {
            File folder = new File(dir, name);
            if (!folder.isDirectory() && folder.mkdirs()) created = true;
        }
        return created;
    }

    /**
     * Completes build-tools folders left behind by an earlier version that wrote only package.xml. Runs before every Gradle start, so
     * such a folder is repaired up front instead of costing a failed build first.
     */
    private static void repairRegisteredBuildTools(File sdk, BuildLogger logger) {
        File[] folders = new File(sdk, "build-tools").listFiles();
        if (folders == null) return;
        for (File folder : folders) {
            Matcher version = TOOLS_VERSION.matcher(folder.getName());
            if (!folder.isDirectory() || !version.matches() || !new File(folder, "package.xml").isFile()) continue;
            try { if (fillTools(folder)) logger.onInfo("Build-tools " + folder.getName() + " was completed in the private SDK."); }
            catch (IOException exception) { logger.onInfo("Build-tools " + folder.getName() + " was not completed (" + exception.getClass().getSimpleName() + ")."); }
        }
    }

    /**
     * AGP needs platforms/android-N/core-for-system-modules.jar for compileSdk 34+ (the JDK image javac compiles against) and stops with
     * NoSuchFileException when it is absent. Studio Mobile keeps only android.jar, which already holds the Java core API stubs, so the
     * file is built from the java/, javax/ and XML API classes in it. Written once per platform folder, through a temporary file.
     */
    private static void provideCoreForSystemModules(File platformDir, int api, BuildLogger logger) {
        if (api < CORE_MODULES_MIN_API) return;
        File target = new File(platformDir, "core-for-system-modules.jar");
        if (target.length() > 0) return;
        File source;
        try { source = new File(platformDir, "android.jar").toPath().toRealPath().toFile(); }
        catch (IOException exception) { return; }
        if (source.length() == 0) return;
        File partial = new File(platformDir, "core-for-system-modules.jar.partial");
        int copied = 0;
        try (ZipFile zip = new ZipFile(source); ZipOutputStream out = new ZipOutputStream(new FileOutputStream(partial))) {
            byte[] buffer = new byte[16 * 1024];
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (entry.isDirectory() || !name.endsWith(".class") || name.endsWith("module-info.class") || !isCorePackage(name)) continue;
                out.putNextEntry(new ZipEntry(name));
                try (InputStream in = zip.getInputStream(entry)) {
                    for (int read = in.read(buffer); read >= 0; read = in.read(buffer)) out.write(buffer, 0, read);
                }
                out.closeEntry();
                copied++;
            }
        } catch (IOException exception) {
            //noinspection ResultOfMethodCallIgnored
            partial.delete();
            logger.onInfo("core-for-system-modules.jar was not prepared for " + platformDir.getName() + " (" + exception.getClass().getSimpleName() + ").");
            return;
        }
        if (copied == 0) { //noinspection ResultOfMethodCallIgnored
            partial.delete(); return; }
        try {
            Files.move(partial.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            logger.onInfo("core-for-system-modules.jar prepared for " + platformDir.getName() + " (" + copied + " core classes taken from android.jar).");
        } catch (IOException exception) {
            //noinspection ResultOfMethodCallIgnored
            partial.delete();
            logger.onInfo("core-for-system-modules.jar was not prepared for " + platformDir.getName() + " (" + exception.getClass().getSimpleName() + ").");
        }
    }

    private static boolean isCorePackage(String entryName) {
        for (String prefix : CORE_PACKAGES) if (entryName.startsWith(prefix)) return true;
        return false;
    }

    /**
     * package.xml and build.prop are what the Android Gradle Plugin reads to accept a folder as an installed platform. Without a valid
     * package.xml the platform is skipped ("package.xml parsing problem"), without build.prop it is rejected ("Build properties not
     * found"), and either way the build stops with "Failed to find target with hash string 'android-NN'". An earlier version wrote a
     * base-extension element that the plugin's parser does not know, so such a file left by an older run is rewritten here.
     */
    private static void writePlatformMetadata(File dir, int api) {
        File xml = new File(dir, "package.xml");
        try {
            boolean stale = !xml.isFile() || new String(Files.readAllBytes(xml.toPath()), StandardCharsets.UTF_8).contains("base-extension");
            if (stale) {
                write(xml, XML_HEAD + "<localPackage path=\"platforms;android-" + api + "\" obsolete=\"false\">"
                    + "<type-details xsi:type=\"ns5:platformDetailsType\"><api-level>" + api + "</api-level><layoutlib api=\"15\"/></type-details>"
                    + "<revision><major>1</major></revision><display-name>Android SDK Platform " + api + "</display-name></localPackage></ns2:repository>");
            }
            File source = new File(dir, "source.properties");
            if (!source.isFile() || stale) write(source, "Pkg.Desc=Android SDK Platform " + api + "\nPkg.Revision=1\nPkg.Path=platforms;android-" + api
                + "\nAndroidVersion.ApiLevel=" + api + "\nLayoutlib.Api=15\n");
            File props = new File(dir, "build.prop");
            if (!props.isFile()) write(props, "ro.build.version.sdk=" + api + "\nro.build.version.codename=REL\nro.build.version.release="
                + (api >= 33 ? api - 20 : api) + "\nro.build.version.incremental=studio-mobile\nro.build.id=studio-mobile\nro.product.cpu.abi=arm64-v8a\n");
        } catch (IOException ignored) { /* the build reports a missing platform by itself */ }
    }

    private static void write(File file, String text) throws IOException { Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8)); }

    /** The compileSdk the project declares in its Gradle scripts, or 0 when it cannot be read statically. */
    static int readCompileSdk(File project) {
        String[] scripts = {"app/build.gradle", "app/build.gradle.kts", "build.gradle", "build.gradle.kts"};
        for (String script : scripts) {
            File file = new File(project, script);
            if (!file.isFile() || file.length() > 1024 * 1024) continue;
            try {
                String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
                Matcher release = COMPILE_SDK_RELEASE.matcher(text);
                if (release.find()) return Integer.parseInt(release.group(1));
                Matcher plain = COMPILE_SDK.matcher(text);
                if (plain.find()) return Integer.parseInt(plain.group(1));
            } catch (Exception ignored) { /* try the next script */ }
        }
        return 0;
    }

    /** Returns null on success, otherwise a message for the full log. */
    private static String expose(File source, File target) {
        try {
            File parent = target.getParentFile();
            if (!parent.isDirectory() && !parent.mkdirs()) return "Platform folder was not created: " + parent;
            if (Files.isSymbolicLink(target.toPath()) && Files.readSymbolicLink(target.toPath()).equals(source.toPath())) return null;
            if (!Files.isSymbolicLink(target.toPath()) && target.isFile() && target.length() == source.length()) return null;
            Files.deleteIfExists(target.toPath());
            try { Files.createSymbolicLink(target.toPath(), source.toPath()); }
            catch (IOException | UnsupportedOperationException linkFailed) { Files.copy(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING); }
            return null;
        } catch (IOException exception) {
            return "Platform jar was not prepared in " + target.getParentFile().getName() + " (" + exception.getClass().getSimpleName() + ").";
        }
    }
}
