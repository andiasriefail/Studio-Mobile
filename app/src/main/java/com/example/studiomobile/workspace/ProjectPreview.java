package com.example.studiomobile.workspace;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.content.res.Resources;
import com.example.studiomobile.build.BuildConfiguration;
import com.example.studiomobile.build.BuildExecutableResolver;
import com.example.studiomobile.build.BuildLogger;
import com.example.studiomobile.build.BuildProjectFiles;
import com.example.studiomobile.build.BuildStageContext;
import com.example.studiomobile.build.GradleBuildPipeline;
import com.example.studiomobile.build.ManifestPackage;
import com.example.studiomobile.build.process.BuildProcessRunner;
import com.example.studiomobile.build.dependency.DownloadMemoryPolicy;
import com.example.studiomobile.build.dependency.MavenResolver;
import com.example.studiomobile.build.stages.DependencyResolutionStage;
import com.example.studiomobile.build.stages.ResourceCompilationStage;
import com.example.studiomobile.build.stages.ResourceLinkingStage;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Prepares a project for the preview page without building an APK.
 *
 * Two kinds of content are recognised and can be combined:
 *  - layout XML: only the AAPT2 steps run (compile + link, the same stages the build uses, no Java, DEX or signing).
 *    The resulting resources.ap_ is loaded as {@link Resources} so the layout is inflated with the project's own theme and styles.
 *  - assets HTML: the project's assets folder is served to a real WebView by {@link PreviewRenderer}.
 * Java code of the project is never executed; the Activity source is only read to find status bar / navigation bar settings.
 * Call {@link #prepare} on a background thread.
 */
public final class ProjectPreview {
    private ProjectPreview() { }

    public enum Mode { LAYOUT, WEB, NONE }

    public interface Progress { void status(String text); boolean cancelled(); }

    public static final class Cancelled extends RuntimeException { public Cancelled() { super("Preview was cancelled."); } }

    private static final class NotCached extends IOException { NotCached(String url) { super("Not bundled: " + url); } }

    /** Everything the UI thread needs to draw one screen of the preview. */
    public static final class Prepared {
        public Mode mode = Mode.NONE;
        public String title = "", modeLabel = "";
        public File assetsDir; public String startPage;
        public Resources resources; public Resources.Theme theme; public int layoutId;
        public PreviewBars bars = new PreviewBars();
        public String warning, error;
        public boolean landscape; public String orientationInfo;
        /** External page (https) that the project loads in its WebView, and whether the code lets media start without a tap. */
        public String startUrl; public boolean autoplay;
        /** Shared by every screen of the project; used to open the next Activity. */
        Session session;
        /** Simple name of the Activity this screen belongs to (null when unknown). */
        public String className;
        /** What a tap on a view does, by view id name (from setOnClickListener in the Activity code). */
        Map<String, PreviewLinks.Action> links = Collections.emptyMap();
        /** Actions of android:onClick views, in layout order. */
        List<PreviewLinks.Action> onClickActions = Collections.emptyList();
        /** Changes onCreate/onStart/onResume always make to views (setVisibility, setText, ...), applied before the first frame. */
        List<PreviewLinks.Effect> startEffects = Collections.emptyList();
        /** Like a real WebView: JavaScript and DOM storage only work when the project's code switches them on. */
        public boolean javaScript = true, domStorage = true;
        /** The code loads the page as file:///android_asset/..., so the preview does the same (fetch() fails there too, as on the device). */
        public boolean fileScheme;
        public String[] packages = new String[0];
    }

    /** Facts about the project that do not change from one screen to the next. */
    static final class Session {
        File project, resDir, assets; String manifest = ""; List<File> sources = new ArrayList<>();
        Resources resources; String[] packages = new String[0]; int targetSdk = 36; String projectName = "";
        boolean strictWeb, codeEnablesJavaScript, codeEnablesDomStorage;
    }

    private static final Pattern ACTIVITY_TAG = Pattern.compile("<activity\\b[^>]*>");
    private static final Pattern NAME_ATTR = Pattern.compile("android:name\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern THEME_ATTR = Pattern.compile("android:theme\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern LABEL_ATTR = Pattern.compile("android:label\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern APPLICATION_TAG = Pattern.compile("<application\\b[^>]*>");
    private static final Pattern ORIENTATION_ATTR = Pattern.compile("android:screenOrientation\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern SET_ORIENTATION = Pattern.compile("setRequestedOrientation\\s*\\(([^;]*)\\)\\s*;");
    private static final Pattern LOAD_URL = Pattern.compile("loadUrl\\s*\\(\\s*\"(https?://[^\"\\s]+)\"");
    private static final Pattern AUTOPLAY = Pattern.compile("setMediaPlaybackRequiresUserGesture\\s*\\(\\s*false\\s*\\)");
    private static final Pattern JS_ON = Pattern.compile("setJavaScriptEnabled\\s*\\(\\s*true\\s*\\)|\\bjavaScriptEnabled\\s*=\\s*true");
    private static final Pattern DOM_ON = Pattern.compile("setDomStorageEnabled\\s*\\(\\s*true\\s*\\)|\\bdomStorageEnabled\\s*=\\s*true");
    private static final Pattern FETCH_USE = Pattern.compile("\\bfetch\\s*\\(|XMLHttpRequest");
    private static final Pattern LOAD_ASSET = Pattern.compile("loadUrl\\s*\\(\\s*\"file:///android_asset/([^\"?#]+)");

    public static Prepared prepare(Context context, File project, File openedFile) { return prepare(context, project, openedFile, null); }

    public static Prepared prepare(Context context, File project, File openedFile, Progress progress) {
        Prepared p = new Prepared();
        File main = BuildProjectFiles.mainDirectory(project);
        File assets = new File(main, "assets"), resDir = new File(main, "res");
        List<File> sources = new ArrayList<>();
        collectSources(new File(main, "java"), sources); collectSources(new File(main, "kotlin"), sources);
        String manifest = read(BuildProjectFiles.manifest(project));

        // ---- what to show
        String layoutName = null, page = null;
        boolean openedHtml = false;
        if (openedFile != null && openedFile.isFile()) {
            String inRes = relativeTo(resDir, openedFile), inAssets = relativeTo(assets, openedFile);
            if (inRes != null && inRes.startsWith("layout") && inRes.endsWith(".xml")) layoutName = stripExtension(openedFile.getName());
            else if (inAssets != null && (inAssets.endsWith(".html") || inAssets.endsWith(".htm"))) { page = inAssets; openedHtml = true; }
        }
        File launcher = null;
        if (layoutName == null && page == null) {
            launcher = launcherActivityFile(manifest, sources);
            String used = launcher == null ? null : firstContentLayout(launcher);
            if (used != null && layoutFile(resDir, used) != null) layoutName = used;
        }
        String externalUrl = findExternalUrl(launcher != null ? launcher : launcherActivityFile(manifest, sources), sources);
        String startPage = page != null ? page : guessStartPage(sources, assets, externalUrl != null);
        String startUrl = null;
        if (layoutName == null && page == null) {
            if (startPage != null) page = startPage;
            else if (externalUrl != null) startUrl = externalUrl;
            else layoutName = defaultLayout(resDir);
        }
        if (layoutName == null && page == null && startUrl == null) {
            p.error = nothingToPreview(sources);
            return p;
        }
        boolean layoutMode = layoutName != null;
        File layoutSource = layoutMode ? layoutFile(resDir, layoutName) : null;
        boolean layoutHasWebView = layoutSource != null && read(layoutSource).contains("WebView");
        if (layoutMode && startPage == null && layoutHasWebView && externalUrl != null) startUrl = externalUrl;
        // Studio Mobile does not allow plain http, so such a page is explained instead of loaded.
        boolean insecure = startUrl != null && startUrl.toLowerCase(java.util.Locale.ROOT).startsWith("http://");
        p.mode = layoutMode ? Mode.LAYOUT : Mode.WEB;
        if (layoutMode) {
            p.title = layoutName + ".xml";
            p.modeLabel = layoutHasWebView && startPage != null ? "Layout XML + WebView assets"
                : layoutHasWebView && startUrl != null ? "Layout XML + WebView URL" : "Layout XML";
        } else if (startUrl != null) {
            String host = android.net.Uri.parse(startUrl).getHost();
            p.title = host != null ? host : startUrl; p.modeLabel = "External URL";
        } else { p.title = page; p.modeLabel = "Assets HTML"; }
        if (startPage != null && (!layoutMode || layoutHasWebView)) { p.assetsDir = assets; p.startPage = startPage; }
        if (startUrl != null && !insecure) p.startUrl = startUrl;
        if (insecure) {
            String text = "The page " + startUrl + " uses http://, which Studio Mobile does not allow. Use https:// to preview it.";
            if (!layoutMode) { p.error = text; p.mode = Mode.NONE; return p; }
            p.warning = text;
        }
        p.autoplay = enablesAutoplay(sources);

        // ---- the Activity whose code is read for bar settings
        File activity = layoutMode ? activityUsingLayout(sources, layoutName)
            : startUrl != null ? activityLoadingUrl(sources, startUrl) : activityLoadingPage(sources, startPage);
        if (activity == null) activity = launcher != null ? launcher : launcherActivityFile(manifest, sources);

        // ---- resources (AAPT2 only)
        String[] packages = new String[0];
        Resources resources = null; String buildProblem = null;
        if (layoutMode || resDir.isDirectory()) {
            try {
                File apk = buildResources(context, project, progress);
                String[] foundPackage = new String[1];
                resources = loadResources(context, apk, foundPackage);
                packages = candidatePackages(project, foundPackage[0]);
            } catch (Cancelled cancelled) { p.error = cancelled.getMessage(); return p; }
            catch (Exception failure) { buildProblem = String.valueOf(failure.getMessage() == null ? failure : failure.getMessage()); }
        }
        if (resources == null && layoutMode) { p.error = "The layout could not be prepared.\n\n" + buildProblem; return p; }
        if (resources == null) {
            resources = Resources.getSystem(); packages = new String[] {"android"};
            if (buildProblem != null) p.warning = "Resources could not be built, so the bars use default values.\n" + buildProblem;
        }
        p.resources = resources; p.packages = packages;

        Session session = new Session();
        session.project = project; session.resDir = resDir; session.assets = assets; session.manifest = manifest; session.sources = sources;
        session.resources = resources; session.packages = packages; session.projectName = project.getName();
        session.targetSdk = 36;
        try { session.targetSdk = Integer.parseInt(BuildProjectFiles.readNumericSdkValue(project, false, "36")); } catch (NumberFormatException ignored) { }
        // Opening an .html file directly means "show me this page", so the page gets a working WebView; with an app screen the code decides.
        session.strictWeb = !openedHtml;
        for (File source : sources) {
            String text = read(source);
            if (JS_ON.matcher(text).find()) session.codeEnablesJavaScript = true;
            if (DOM_ON.matcher(text).find()) session.codeEnablesDomStorage = true;
        }
        p.session = session;

        applyScreen(p, session, activity, layoutMode ? layoutName : null, layoutMode);
        if (p.error != null) return p;
        if (p.landscape) p.modeLabel += " · Landscape";
        applyWebSettings(p, session, startPage);
        return p;
    }

    /**
     * The screen reached by startActivity from another screen: its layout, theme, bars and tap targets.
     * Returns null when the class is not an Activity of the project (for example a system screen).
     */
    public static Prepared navigate(Prepared from, String className) {
        Session s = from == null ? null : from.session;
        if (s == null || className == null) return null;
        File activity = null;
        for (File source : s.sources) if (stripExtension(source.getName()).equals(className)) { activity = source; break; }
        if (activity == null) return null;
        Prepared p = new Prepared();
        p.session = s; p.resources = s.resources; p.packages = s.packages; p.autoplay = from.autoplay;
        String layoutName = firstContentLayout(activity);
        if (layoutName != null && layoutFile(s.resDir, layoutName) != null) {
            p.mode = Mode.LAYOUT; p.title = layoutName + ".xml"; p.modeLabel = "Layout XML";
            applyScreen(p, s, activity, layoutName, true);
            if (p.error != null) return p;
            boolean webView = read(layoutFile(s.resDir, layoutName)).contains("WebView");
            if (webView) {
                String page = pageLoadedBy(activity, s.assets);
                String url = findExternalUrl(activity, Collections.singletonList(activity));
                if (page != null) { p.assetsDir = s.assets; p.startPage = page; p.modeLabel = "Layout XML + WebView assets"; applyWebSettings(p, s, page); }
                else if (url != null && !url.toLowerCase(java.util.Locale.ROOT).startsWith("http://")) { p.startUrl = url; p.modeLabel = "Layout XML + WebView URL"; applyWebSettings(p, s, null); }
            }
        } else {
            String page = pageLoadedBy(activity, s.assets), url = findExternalUrl(activity, Collections.singletonList(activity));
            if (page == null && url == null) {
                p.error = className + " builds its screen in Java code, which is not run in the preview. Use Build to see it.";
                return p;
            }
            p.mode = Mode.WEB;
            if (page != null) { p.assetsDir = s.assets; p.startPage = page; p.title = page; p.modeLabel = "Assets HTML"; }
            else if (url.toLowerCase(java.util.Locale.ROOT).startsWith("http://")) { p.error = "The page " + url + " uses http://, which Studio Mobile does not allow. Use https:// to preview it."; return p; }
            else { p.startUrl = url; String host = android.net.Uri.parse(url).getHost(); p.title = host != null ? host : url; p.modeLabel = "External URL"; }
            applyScreen(p, s, activity, null, false);
            if (p.error != null) return p;
            applyWebSettings(p, s, page);
        }
        if (p.landscape) p.modeLabel += " · Landscape";
        return p;
    }

    /** Orientation, theme, bars and tap targets of one Activity. */
    private static void applyScreen(Prepared p, Session s, File activity, String layoutName, boolean layoutMode) {
        String[] orientation = detectOrientation(s.manifest, activity);
        if (orientation != null) { p.landscape = orientation[0].equals("landscape"); p.orientationInfo = orientation[1]; }

        // ---- theme: the Activity's own android:theme, else the application's
        Resources resources = s.resources; String[] packages = s.packages;
        String themeRef = themeReference(s.manifest, activity);
        int themeId = themeRef == null ? 0 : styleId(resources, themeRef, packages);
        if (themeRef != null && themeId == 0) {
            String note = "Theme " + themeRef + " was not found in the compiled resources, so the Android default theme is used here. Colors and bars will differ from the build.";
            p.warning = p.warning == null ? note : p.warning + "\n" + note;
        }
        Resources.Theme theme = resources.newTheme();
        theme.applyStyle(themeId != 0 ? themeId : android.R.style.Theme_DeviceDefault, true);
        p.theme = theme;
        if (layoutMode) {
            p.layoutId = findIdentifier(resources, layoutName, "layout", packages);
            if (p.layoutId == 0) { p.error = "Layout " + layoutName + " was not found in the compiled resources."; p.mode = Mode.NONE; return; }
        }
        String themeName = themeRef == null ? null : themeRef.substring(themeRef.indexOf('/') + 1);
        PreviewBars.CodeScan code = PreviewBars.scan(activity, resources, theme, packages);
        p.bars = PreviewBars.resolve(resources, theme, themeName, code, s.targetSdk, activityLabel(s.manifest, activity, resources, packages, s.projectName), packages);

        // ---- what a tap does (open another Activity, Toast, finish)
        p.className = activity == null ? null : stripExtension(activity.getName());
        if (activity != null) {
            List<String> names = new ArrayList<>();
            for (File source : s.sources) names.add(source.getName());
            Set<String> activities = PreviewLinks.simpleNames(names);
            String layoutText = layoutMode ? read(layoutFile(s.resDir, layoutName)) : "";
            String text = read(activity);
            p.links = PreviewLinks.scan(text, layoutText, activities);
            p.onClickActions = PreviewLinks.onClickActions(text, layoutText, activities);
            p.startEffects = PreviewLinks.startupEffects(text, layoutText);
        }
    }

    /** JavaScript, DOM storage and how the page is loaded follow the project's code, like in the real app. */
    private static void applyWebSettings(Prepared p, Session s, String startPage) {
        p.javaScript = !s.strictWeb || s.codeEnablesJavaScript;
        p.domStorage = !s.strictWeb || s.codeEnablesDomStorage;
        p.fileScheme = false;
        if (!s.strictWeb || startPage == null || p.assetsDir == null) return;
        boolean viaFile = false;
        for (File source : s.sources) if (read(source).contains("file:///android_asset/" + startPage)) { viaFile = true; break; }
        p.fileScheme = viaFile;
        List<String> notes = new ArrayList<>();
        if (!p.javaScript) notes.add("The code never calls setJavaScriptEnabled(true), so JavaScript is off here like on the device.");
        if (viaFile && usesFetch(s.assets)) notes.add("The page is loaded from file:///android_asset/, so fetch() and XMLHttpRequest to local files fail, exactly as in the built app. Use WebViewAssetLoader (https://appassets.androidplatform.net) to make them work.");
        if (!notes.isEmpty()) { String text = String.join("\n", notes); p.warning = p.warning == null ? text : p.warning + "\n" + text; }
    }

    private static boolean usesFetch(File assets) {
        File[] children = assets.listFiles();
        if (children == null) return false;
        for (File child : children) {
            if (child.isDirectory()) { if (usesFetch(child)) return true; continue; }
            String name = child.getName().toLowerCase(java.util.Locale.ROOT);
            if ((name.endsWith(".js") || name.endsWith(".html") || name.endsWith(".htm") || name.endsWith(".mjs")) && child.length() < 512 * 1024 && FETCH_USE.matcher(read(child)).find()) return true;
        }
        return false;
    }

    private static String pageLoadedBy(File activity, File assets) {
        Matcher m = LOAD_ASSET.matcher(read(activity));
        while (m.find()) if (new File(assets, m.group(1)).isFile()) return m.group(1);
        return null;
    }

    // ------------------------------------------------------------------ AAPT2

    private static File buildResources(Context context, File project, Progress progress) throws Exception {
        File saved = new File(new File(context.getFilesDir(), "preview-apk"), resourceKey(context, project) + ".ap_");
        if (saved.isFile() && saved.length() > 0) { saved.setLastModified(System.currentTimeMillis()); return saved; }
        File cache = context.getCacheDir();
        File[] old = cache.listFiles((dir, name) -> name.startsWith("preview-"));
        File work = new File(cache, "preview-" + System.currentTimeMillis());
        if (!work.mkdirs()) throw new IllegalStateException("Could not create " + work);
        if (old != null) for (File previous : old) deleteRecursively(previous);

        final StringBuilder log = new StringBuilder();
        BuildLogger logger = new BuildLogger() {
            @Override public void onInfo(String message) { log.append(message).append('\n'); }
            @Override public void onWarning(String message) { log.append(message).append('\n'); }
            @Override public void onError(String message, Throwable cause) { log.append(message).append('\n'); }
        };
        File tools = new File(context.getFilesDir(), "build-tools");
        BuildExecutableResolver.registerNativeLibraryDirectory(new File(context.getApplicationInfo().nativeLibraryDir));
        BuildConfiguration configuration = new BuildConfiguration(project, new File(work, "out"), !GradleBuildPipeline.isGradleProject(project));
        BuildStageContext stageContext = new BuildStageContext(configuration, work);
        BuildProcessRunner runner = new BuildProcessRunner();
        try {
            if (progress != null) progress.status("Preparing libraries...");
            resolveLibraries(context, stageContext, logger, log, progress);
            if (progress != null) progress.status("Compiling resources...");
            new ResourceCompilationStage(tools, runner, new File(context.getFilesDir(), "preview-compiled")).execute(stageContext, logger);
            if (progress != null) progress.status("Linking resources...");
            new ResourceLinkingStage(tools, runner).execute(stageContext, logger);
        } catch (Cancelled cancelled) {
            throw cancelled;
        } catch (Exception failure) {
            throw new IllegalStateException(failure.getMessage() + "\n\n" + lastLines(log.toString(), 14), failure);
        }
        File apk = stageContext.getOutput("resourceApk");
        if (apk == null || !apk.isFile()) throw new IllegalStateException("AAPT2 produced no resources.\n\n" + lastLines(log.toString(), 14));
        return keep(apk, saved);
    }

    private static File keep(File apk, File saved) {
        File directory = saved.getParentFile();
        File temporary = new File(directory, saved.getName() + ".tmp");
        try {
            if (!directory.isDirectory() && !directory.mkdirs()) return apk;
            java.nio.file.Files.copy(apk.toPath(), temporary.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            if (!temporary.renameTo(saved)) { temporary.delete(); return apk; }
        } catch (IOException failure) {
            temporary.delete();
            return apk;
        }
        File[] all = directory.listFiles((dir, name) -> name.endsWith(".ap_"));
        if (all != null && all.length > 4) {
            Arrays.sort(all, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
            for (int i = 4; i < all.length; i++) all[i].delete();
        }
        return saved;
    }

    private static String resourceKey(Context context, File project) {
        long updated = 0;
        try { updated = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).lastUpdateTime; } catch (Exception ignored) { }
        StringBuilder text = new StringBuilder();
        text.append(updated).append('\n').append(project.getAbsolutePath()).append('\n');
        fingerprintWalk(project, project, text, true);
        return digest(text.toString());
    }

    public static String fingerprint(File project, File opened) {
        StringBuilder text = new StringBuilder();
        text.append(opened == null ? "" : opened.getAbsolutePath()).append('\n');
        fingerprintWalk(project, project, text, false);
        return digest(text.toString());
    }

    private static void fingerprintWalk(File root, File directory, StringBuilder out, boolean resourcesOnly) {
        File[] children = directory.listFiles();
        if (children == null) return;
        Arrays.sort(children);
        boolean moduleRoot = directory.equals(root) || new File(directory, "build.gradle").isFile() || new File(directory, "build.gradle.kts").isFile();
        for (File child : children) {
            String name = child.getName();
            if (child.isDirectory()) {
                if (name.equals(".gradle") || name.equals(".git") || name.equals(".idea") || name.equals("node_modules") || name.equals(".cxx")) continue;
                if (moduleRoot && name.equals("build")) continue;
                fingerprintWalk(root, child, out, resourcesOnly);
                continue;
            }
            String relative = child.getAbsolutePath().substring(root.getAbsolutePath().length()).replace('\\', '/');
            if (resourcesOnly && !affectsResources(relative, name)) continue;
            out.append(relative).append('|').append(child.length()).append('|').append(child.lastModified()).append('\n');
        }
    }

    private static boolean affectsResources(String relative, String name) {
        if (relative.contains("/res/")) return true;
        if (name.endsWith(".aar")) return true;
        return name.equals("AndroidManifest.xml") || name.equals("build.gradle") || name.equals("build.gradle.kts")
            || name.equals("settings.gradle") || name.equals("settings.gradle.kts") || name.equals("gradle.properties")
            || name.equals("libs.versions.toml") || name.equals("checksums.properties");
    }

    private static String digest(String text) {
        try {
            byte[] hash = java.security.MessageDigest.getInstance("SHA-1").digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException failure) {
            return Integer.toHexString(text.hashCode()) + "-" + text.length();
        }
    }

    private static void resolveLibraries(Context context, BuildStageContext stageContext, BuildLogger logger, StringBuilder log, Progress progress) {
        File cache = StudioStorage.localLibsDir(context);
        long started = System.currentTimeMillis();
        try {
            new DependencyResolutionStage(cache, new OfflineFetcher()).execute(stageContext, logger);
            return;
        } catch (Exception offline) {
            if (!causedBy(offline, NotCached.class)) { log.append("Libraries were not resolved: ").append(offline.getMessage()).append('\n'); return; }
        } finally {
            dropFreshMarkers(cache, started - 1000);
        }
        if (progress != null) progress.status("These libraries are not bundled in Studio Mobile. Downloading them once...");
        try {
            new DependencyResolutionStage(cache, new ProgressFetcher(new MavenResolver.HttpsFetcher(), progress)).execute(stageContext, logger);
        } catch (Cancelled cancelled) {
            throw cancelled;
        } catch (Exception online) {
            if (causedBy(online, Cancelled.class)) throw new Cancelled();
            log.append("Libraries were not resolved: ").append(online.getMessage()).append('\n');
        }
    }

    private static boolean causedBy(Throwable failure, Class<? extends Throwable> type) {
        for (Throwable t = failure; t != null; t = t.getCause()) if (type.isInstance(t)) return true;
        return false;
    }

    private static void dropFreshMarkers(File directory, long since) {
        File[] children = directory.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (child.isDirectory()) dropFreshMarkers(child, since);
            else if (child.getName().endsWith(".missing") && child.lastModified() >= since) child.delete();
        }
    }

    private static final class OfflineFetcher implements MavenResolver.Fetcher {
        private final Set<String> jarMisses = new HashSet<>();

        @Override public byte[] get(String url, long maxBytes) throws IOException {
            if (url.endsWith(".module") || url.endsWith(".sha1")) return null;
            if (url.endsWith(".jar")) { jarMisses.add(url.substring(0, url.length() - 4)); return null; }
            if (url.endsWith(".aar") && jarMisses.contains(url.substring(0, url.length() - 4))) return null;
            throw new NotCached(url);
        }
    }

    private static final class ProgressFetcher implements MavenResolver.Fetcher {
        private final MavenResolver.Fetcher inner; private final Progress progress; private int count;

        ProgressFetcher(MavenResolver.Fetcher inner, Progress progress) { this.inner = inner; this.progress = progress; }

        private void announce(String url) {
            if (Thread.currentThread().isInterrupted() || (progress != null && progress.cancelled())) throw new Cancelled();
            if (progress != null && !url.endsWith(".sha1")) progress.status("Downloading libraries (" + (++count) + ")\n" + url.substring(url.lastIndexOf('/') + 1));
        }

        @Override public byte[] get(String url, long maxBytes) throws IOException { announce(url); return inner.get(url, maxBytes); }

        @Override public String download(String url, File target, long maxBytes, DownloadMemoryPolicy policy) throws IOException { announce(url); return inner.download(url, target, maxBytes, policy); }
    }

    /** resources.ap_ as Resources. The package manager route is the supported one; the AssetManager route is the fallback. */
    private static Resources loadResources(Context context, File apk, String[] packageOut) throws Exception {
        PackageManager manager = context.getPackageManager();
        try {
            PackageInfo info = manager.getPackageArchiveInfo(apk.getAbsolutePath(), 0);
            if (info != null && info.applicationInfo != null) {
                ApplicationInfo applicationInfo = info.applicationInfo;
                applicationInfo.sourceDir = apk.getAbsolutePath(); applicationInfo.publicSourceDir = apk.getAbsolutePath();
                Resources resources = manager.getResourcesForApplication(applicationInfo);
                packageOut[0] = info.packageName;
                return resources;
            }
        } catch (Exception | LinkageError ignored) { /* try the AssetManager route */ }
        Constructor<AssetManager> constructor = AssetManager.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        AssetManager assets = constructor.newInstance();
        Method add = AssetManager.class.getDeclaredMethod("addAssetPath", String.class);
        add.setAccessible(true);
        Object cookie = add.invoke(assets, apk.getAbsolutePath());
        if (cookie instanceof Integer && (Integer) cookie == 0) throw new IllegalStateException("The compiled resources could not be loaded by Android.");
        Resources base = context.getResources();
        return new Resources(assets, base.getDisplayMetrics(), base.getConfiguration());
    }

    private static String[] candidatePackages(File project, String fromApk) {
        List<String> names = new ArrayList<>();
        if (fromApk != null) names.add(fromApk);
        String effective = ManifestPackage.effectivePackage(project, BuildProjectFiles.manifest(project));
        if (effective != null && !names.contains(effective)) names.add(effective);
        String applicationId = BuildProjectFiles.readApplicationId(project, null);
        if (applicationId != null && !names.contains(applicationId)) names.add(applicationId);
        return names.toArray(new String[0]);
    }

    /**
     * Resource id by name. resources.arsc keeps a name exactly as written in the XML ("Theme.App" stays "Theme.App"); only the generated R class
     * turns dots into underscores. So the name is tried as written first, then with underscores (names written with underscores in code).
     */
    static int findIdentifier(Resources resources, String name, String type, String[] packages) {
        String underscored = name.replace('.', '_');
        String[] candidates = underscored.equals(name) ? new String[] {name} : new String[] {name, underscored};
        for (String entry : candidates) {
            for (String pkg : packages) { if (pkg == null) continue; int id = resources.getIdentifier(entry, type, pkg); if (id != 0) return id; }
            int id = resources.getIdentifier(entry, type, null);
            if (id != 0) return id;
        }
        return 0;
    }

    private static int styleId(Resources resources, String reference, String[] packages) {
        String body = reference.trim();
        if (body.startsWith("@android:style/")) return frameworkStyle(resources, body.substring("@android:style/".length()));
        if (body.startsWith("@style/")) return findIdentifier(resources, body.substring("@style/".length()), "style", packages);
        if (body.startsWith("@")) { int slash = body.indexOf("/"); if (slash > 0 && body.substring(1, slash).endsWith(":style")) return frameworkStyle(resources, body.substring(slash + 1)); }
        return 0;
    }

    private static int frameworkStyle(Resources resources, String name) {
        int id = resources.getIdentifier(name, "style", "android");
        if (id == 0) id = resources.getIdentifier(name.replace('.', '_'), "style", "android");
        if (id == 0) id = Resources.getSystem().getIdentifier(name, "style", "android");
        if (id == 0) id = Resources.getSystem().getIdentifier(name.replace('.', '_'), "style", "android");
        return id;
    }

    // ------------------------------------------------------------------ manifest and sources

    /** android:theme of the Activity, or of the application when the Activity has none. */
    private static String themeReference(String manifest, File activity) {
        if (activity != null) {
            String simple = stripExtension(activity.getName());
            Matcher tag = ACTIVITY_TAG.matcher(manifest);
            while (tag.find()) {
                Matcher name = NAME_ATTR.matcher(tag.group());
                if (!name.find() || !(name.group(1).equals(simple) || name.group(1).endsWith("." + simple))) continue;
                Matcher theme = THEME_ATTR.matcher(tag.group());
                if (theme.find()) return theme.group(1);
            }
        }
        Matcher application = APPLICATION_TAG.matcher(manifest);
        if (application.find()) { Matcher theme = THEME_ATTR.matcher(application.group()); if (theme.find()) return theme.group(1); }
        return null;
    }

    /** What the action bar shows as title: android:label of the Activity, else of the application, else the project name. */
    private static String activityLabel(String manifest, File activity, Resources resources, String[] packages, String fallback) {
        if (activity != null) {
            String simple = stripExtension(activity.getName());
            Matcher tag = ACTIVITY_TAG.matcher(manifest);
            while (tag.find()) {
                Matcher name = NAME_ATTR.matcher(tag.group());
                if (!name.find() || !(name.group(1).equals(simple) || name.group(1).endsWith("." + simple))) continue;
                Matcher label = LABEL_ATTR.matcher(tag.group());
                if (label.find()) return labelText(label.group(1), resources, packages, fallback);
                break;
            }
        }
        return appLabel(manifest, resources, packages, fallback);
    }

    private static String labelText(String value, Resources resources, String[] packages, String fallback) {
        if (value.startsWith("@string/")) {
            int id = findIdentifier(resources, value.substring("@string/".length()), "string", packages);
            try { if (id != 0) return resources.getString(id); } catch (RuntimeException ignored) { }
            return fallback;
        }
        return value;
    }

    private static String appLabel(String manifest, Resources resources, String[] packages, String fallback) {
        Matcher application = APPLICATION_TAG.matcher(manifest);
        if (!application.find()) return fallback;
        Matcher label = LABEL_ATTR.matcher(application.group());
        return label.find() ? labelText(label.group(1), resources, packages, fallback) : fallback;
    }

    /**
     * {"landscape" or "portrait", where it was found}, or null when the project does not fix the orientation.
     * The manifest is read first, then the Activity source: code runs after the manifest, so setRequestedOrientation wins.
     */
    private static String[] detectOrientation(String manifest, File activity) {
        if (activity == null) return null;
        String[] found = null;
        String simple = stripExtension(activity.getName());
        Matcher tag = ACTIVITY_TAG.matcher(manifest);
        while (tag.find()) {
            Matcher name = NAME_ATTR.matcher(tag.group());
            if (!name.find() || !(name.group(1).equals(simple) || name.group(1).endsWith("." + simple))) continue;
            Matcher value = ORIENTATION_ATTR.matcher(tag.group());
            if (value.find()) {
                String kind = orientationKind(value.group(1));
                if (kind != null) found = new String[] { kind, "Landscape: screenOrientation=\"" + value.group(1) + "\" in AndroidManifest.xml" };
            }
            break;
        }
        String text = read(activity);
        Matcher call = SET_ORIENTATION.matcher(text);
        String last = null; int at = -1;
        while (call.find()) { last = call.group(1); at = call.start(); }
        if (last != null) {
            String kind = orientationKind(last);
            if (kind != null) found = new String[] { kind, "Landscape: setRequestedOrientation in " + activity.getName() + " line " + lineOf(text, at) };
        }
        return found;
    }

    private static String orientationKind(String value) {
        String v = value.toLowerCase();
        if (v.contains("landscape")) return "landscape";
        if (v.contains("portrait")) return "portrait";
        return null;
    }

    private static int lineOf(String text, int index) {
        int line = 1;
        for (int i = 0; i < index && i < text.length(); i++) if (text.charAt(i) == '\n') line++;
        return line;
    }

    /** Message for a project with no layout XML and no assets page. */
    private static String nothingToPreview(List<File> sources) {
        String how = "Add a layout in res/layout or an index.html in src/main/assets to preview it.";
        if (sources.isEmpty()) return "Nothing to preview.\n" + how;
        String[][] kinds = { {"GLSurfaceView", "OpenGL (GLSurfaceView)"}, {"SurfaceView", "SurfaceView"},
            {"WebView", "a WebView whose URL is not written as text in loadUrl(\"...\")"},
            {"setContentView(new ", "views created in code"}, {"extends View", "a custom View"}, {"Canvas", "Canvas drawing"} };
        String detected = null;
        search:
        for (String[] kind : kinds) {
            for (File source : sources) {
                if (read(source).contains(kind[0])) { detected = kind[1] + " in " + source.getName(); break search; }
            }
        }
        return "No layout XML or assets page was found, so this screen is probably built in Java code."
            + (detected == null ? "" : "\n\nFound: " + detected + ".")
            + "\n\nJava is not run in the preview, so this screen can only be checked after Build.\n\n" + how;
    }

    private static File launcherActivityFile(String manifest, List<File> sources) {
        Matcher tag = ACTIVITY_TAG.matcher(manifest);
        while (tag.find()) {
            int end = manifest.indexOf("</activity>", tag.end());
            String block = manifest.substring(tag.start(), end < 0 ? manifest.length() : end);
            if (!block.contains("android.intent.action.MAIN") || !block.contains("android.intent.category.LAUNCHER")) continue;
            Matcher name = NAME_ATTR.matcher(tag.group());
            if (!name.find()) continue;
            String full = name.group(1); String simple = full.substring(full.lastIndexOf('.') + 1);
            for (File source : sources) if (stripExtension(source.getName()).equals(simple)) return source;
        }
        return null;
    }

    private static String firstContentLayout(File source) {
        Matcher m = Pattern.compile("setContentView\\s*\\(\\s*R\\s*\\.\\s*layout\\s*\\.\\s*(\\w+)").matcher(read(source));
        return m.find() ? m.group(1) : null;
    }

    private static File activityUsingLayout(List<File> sources, String layoutName) {
        Pattern pattern = Pattern.compile("setContentView\\s*\\(\\s*R\\s*\\.\\s*layout\\s*\\.\\s*" + Pattern.quote(layoutName) + "\\b");
        for (File source : sources) if (pattern.matcher(read(source)).find()) return source;
        return null;
    }

    private static File activityLoadingPage(List<File> sources, String page) {
        if (page == null) return null;
        for (File source : sources) if (read(source).contains("file:///android_asset/" + page)) return source;
        return null;
    }

    /** The page the project's code loads, else index.html (unless the code loads an external URL instead), else null. */
    private static String guessStartPage(List<File> sources, File assets, boolean codeLoadsExternalUrl) {
        for (File source : sources) {
            Matcher m = LOAD_ASSET.matcher(read(source));
            while (m.find()) if (new File(assets, m.group(1)).isFile()) return m.group(1);
        }
        if (codeLoadsExternalUrl) return null;
        return new File(assets, "index.html").isFile() ? "index.html" : null;
    }

    /** First loadUrl("http...") written as text in the code, the launcher Activity first. Null when the URL is not a literal. */
    private static String findExternalUrl(File preferred, List<File> sources) {
        List<File> order = new ArrayList<>();
        if (preferred != null) order.add(preferred);
        for (File source : sources) if (!source.equals(preferred)) order.add(source);
        for (File source : order) { Matcher m = LOAD_URL.matcher(read(source)); if (m.find()) return m.group(1); }
        return null;
    }

    private static File activityLoadingUrl(List<File> sources, String url) {
        for (File source : sources) if (read(source).contains(url)) return source;
        return null;
    }

    /** True when some code calls setMediaPlaybackRequiresUserGesture(false), so media may start without a tap. */
    private static boolean enablesAutoplay(List<File> sources) {
        for (File source : sources) if (AUTOPLAY.matcher(read(source)).find()) return true;
        return false;
    }

    private static File layoutFile(File resDir, String name) {
        File file = new File(new File(resDir, "layout"), name + ".xml");
        if (file.isFile()) return file;
        File[] variants = resDir.listFiles((dir, n) -> n.startsWith("layout"));
        if (variants != null) for (File variant : variants) { File candidate = new File(variant, name + ".xml"); if (candidate.isFile()) return candidate; }
        return null;
    }

    private static String defaultLayout(File resDir) {
        File[] files = new File(resDir, "layout").listFiles((dir, name) -> name.endsWith(".xml"));
        if (files == null || files.length == 0) return null;
        Arrays.sort(files);
        for (File file : files) if (file.getName().equals("activity_main.xml")) return "activity_main";
        return stripExtension(files[0].getName());
    }

    private static void collectSources(File directory, List<File> out) {
        File[] children = directory.listFiles();
        if (children == null) return;
        Arrays.sort(children);
        for (File child : children) {
            if (child.isDirectory()) collectSources(child, out);
            else if (child.getName().endsWith(".java") || child.getName().endsWith(".kt")) out.add(child);
        }
    }

    // ------------------------------------------------------------------ small helpers

    private static String relativeTo(File root, File file) {
        try {
            String base = root.getCanonicalPath() + File.separator, path = file.getCanonicalPath();
            return path.startsWith(base) ? path.substring(base.length()).replace(File.separatorChar, '/') : null;
        } catch (java.io.IOException unreadable) { return null; }
    }

    private static String stripExtension(String name) { int dot = name.lastIndexOf('.'); return dot < 0 ? name : name.substring(0, dot); }

    private static String read(File file) {
        try { return file != null && file.isFile() ? new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8) : ""; }
        catch (Exception unreadable) { return ""; }
    }

    private static String lastLines(String text, int count) {
        String[] lines = text.trim().split("\n");
        List<String> tail = new ArrayList<>(Arrays.asList(lines));
        if (tail.size() > count) tail = tail.subList(tail.size() - count, tail.size());
        return String.join("\n", tail);
    }

    private static void deleteRecursively(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteRecursively(child);
        file.delete();
    }
}
