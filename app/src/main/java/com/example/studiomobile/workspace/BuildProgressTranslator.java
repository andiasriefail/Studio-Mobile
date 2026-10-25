package com.example.studiomobile.workspace;

import com.example.studiomobile.LanguageUi;

import java.util.Locale;

/**
 * Turns the build engine's internal progress lines into a short list of plain steps for the Build screen.
 *
 * <p>Warnings and errors are never translated: a line that looks like a problem is passed through exactly as the tools
 * wrote it, because a hidden error is much harder to fix than an ugly one. Everything else that does not start a new step
 * is hidden from the screen (the full log stays available through {@link ProjectBuildService#getLog()}).
 */
final class BuildProgressTranslator {

    /**
     * What the user sees, in build order. The wording describes what the step is for; names of the tools behind it (compilers,
     * resource tools, dexer, platform jars) are deliberately never shown. Warnings and errors are the exception and stay raw.
     */
    static final String[] STEPS = {
        "Checking project structure…",
        "Checking the manifest and build setup…",
        "Preparing libraries…",
        "Starting the build…",
        "Processing resources…",
        "Compiling code…",
        "Running crash check…",
        "Converting code to run on Android…",
        "Packaging the app…",
        "Signing and verifying the app…",
        "Almost done…"
    };
    static final int LAST_STEP = STEPS.length - 1;
    static final int DOWNLOAD_STEP = 2;
    /** The step that covers starting the build engine and configuring the project (on a phone this is often the longest part). */
    static final int START_STEP = 3;
    /** Text of the [step] line the Gradle route writes when it launches the Java runtime; it moves the log to {@link #START_STEP}. */
    static final String ENGINE_START = LanguageUi.BUILD_ENGINE_START;
    static final String PREPARING = "Preparing your project…";
    static final String SUCCESS = "Build successful";

    /**
     * Registered labels of library downloads. The resolver tags every line it writes about a download with one of these, and the Build screen
     * (and the build notification) treat such a line as progress, never as a problem: library names such as "error_prone_annotations" or
     * "kotlin-exceptions" would otherwise look like errors to {@link #looksLikeProblem}. A download that really fails ("-> FAILED") is still shown.
     */
    static final String[] DOWNLOAD_LABELS = {"[maven]", "[download]", "[dns]"};

    /**
     * Tags written by the build pipelines for the screen. "[step] text" is a timed detail line of its own (and the live line),
     * "[progress] text" only updates the live line, and "[internal] text" is bookkeeping that never reaches the screen. Tagged
     * text is written by Studio Mobile in plain words, so it is never mistaken for a problem; warnings and errors are never tagged.
     */
    static final String TAG_STEP = "[step]", TAG_PROGRESS = "[progress]", TAG_INTERNAL = "[internal]";

    /**
     * Friendly detail for a Gradle task: {text, task-name fragments...}. The more specific fragments come first; unlisted tasks stay hidden.
     * Fragments are written without the build variant: "debug" and "release" are removed from the task name first, so the same table
     * reads a debug build, a release build and an AAB export.
     */
    private static final String[][] GRADLE_TASKS = {
        {"Merging resources", "mergeresources", "packageresources"},
        {"Processing resources", "processresources", "generateresources", "mapsourcesetpaths", "compileaidl", "extractdeeplinks", "parselocalresources"},
        {"Merging the manifest", "processmanifest", "processmainmanifest", "createcompatiblescreenmanifests"},
        {"Checking libraries", "checkaarmetadata", "checkduplicateclasses", "prebuild", "checkjetifier"},
        {"Generating helper code", "generatebuildconfig", "generateresvalues", "kapt", "ksp"},
        {"Compiling Kotlin code", "compilekotlin"},
        {"Compiling Java code", "compilejavawithjavac"},
        {"Shrinking and optimizing code", "minifywithr8", "shrinkresources"},
        {"Converting libraries", "mergeextdex", "mergelibdex", "desugarfiledependencies"},
        {"Converting code", "dexbuilder", "mergeprojectdex", "mergedex"},
        {"Merging native libraries", "mergenativelibs", "stripsymbols", "mergenativemetadata"},
        {"Creating the app bundle", "packagebundle", "prebundle"},
        {"Checking the signing setup", "validatesigning", "writesigningconfigversions"},
        {"Signing the app", "signbundle"},
        {"Assembling the app file", ":package", "createapklistingfileredirect"},
        {"Finishing up", ":assemble", ":bundle"},
    };

    /** Rules are checked in order, so the more specific (later) stages come first. Same variant-free fragments as above, plus the local pipeline's stage names. */
    private static final String[][] RULES = {
        {"4", "packageresources"},
        {"9", "apk signing", "signing and verification", "signbundle"},
        {"8", "native c/c++ build", "apk packaging", "apk alignment", "app bundle packaging", "packagebundle", ":bundle", ":package", ":assemble"},
        {"7", "dex generation", "code shrinking", "dexbuilder", "mergeprojectdex", "minifywithr8"},
        {"6", "crash check"},
        {"5", "dart compilation", "java/kotlin compilation", "compilekotlin", "compilejavawithjavac"},
        {"4", "resource compilation", "resource linking", "generated sources", "jsx/typescript", "aapt", "mergeresources", "processresources"},
        {"3", "single-use daemon", "starting a gradle daemon", "> configure project"},
        {"2", "dependency resolution", "project modules"},
        {"1", "java level", "toolchain", "verifying java", "manifest", "device check for native"},
        {"0", "validating project structure", "gradle project detected"},
    };

    /** What the build is doing right now, per stage, for the live line and the notification. First match wins. */
    private static final String[][] ACTIVITY = {
        {"dependency resolution", "Preparing libraries…"},
        {"project modules", "Reading project modules…"},
        {"device check for native", "Checking device compatibility…"},
        {"jsx/typescript", "Transforming scripts…"},
        {"resource compilation", "Processing resources…"},
        {"resource linking", "Processing resources…"},
        {"generated sources", "Generating helper code…"},
        {"java/kotlin compilation", "Compiling code…"},
        {"crash check", "Running crash check…"},
        {"dex generation", "Converting code to run on Android…"},
        {"code shrinking", "Shrinking and optimizing code…"},
        {"native c/c++ build", "Building native libraries…"},
        {"apk packaging", "Packaging the app…"},
        {"apk alignment", "Optimizing the package…"},
        {"apk signing", "Signing and verifying the app…"},
        {"app bundle packaging", "Creating the app bundle…"},
        {"validating project structure", "Checking project structure…"},
        {"java level", "Checking the build setup…"},
        {"toolchain", "Checking the build setup…"},
        {"verifying java", "Checking the build setup…"},
    };

    private static final java.util.regex.Pattern GET_URL = java.util.regex.Pattern.compile("\\bGET\\s+(\\S+)");

    /**
     * Friendly text for the "right now" line (Build screen and notification), or null when this line has nothing worth showing and the
     * previous text should stay. Problems and failed downloads are returned exactly as written; everything else about the tools
     * (paths, jar names of the platform, tool names, diagnostics) is hidden.
     */
    static String liveText(String raw) {
        if (raw == null) return null;
        String lower = raw.toLowerCase(Locale.ROOT).trim();
        String tagged = tagged(raw, TAG_STEP);
        if (tagged != null) return tagged.isEmpty() ? null : tagged + "…";
        tagged = tagged(raw, TAG_PROGRESS);
        if (tagged != null) return tagged.isEmpty() ? null : tagged;
        if (tagged(raw, TAG_INTERNAL) != null) return null;
        if (isKnownNotice(lower)) return null;
        if (lower.startsWith("[dex]")) return dexActivity(raw.trim());
        if (isDownloadLabel(lower)) {
            if (lower.contains("-> failed")) return raw.trim();
            java.util.regex.Matcher get = GET_URL.matcher(raw);
            if (get.find()) {
                String url = get.group(1);
                int slash = url.lastIndexOf('/');
                String file = slash >= 0 ? url.substring(slash + 1) : url;
                int query = file.indexOf('?');
                if (query >= 0) file = file.substring(0, query);
                return file.isEmpty() ? "Downloading libraries…" : "Downloading " + file + "…";
            }
            if (lower.contains("repositories")) return "Connecting to library servers…";
            if (lower.contains("resolved")) return "Libraries ready";
            if (lower.contains("declared") || lower.contains("processor")) return "Reading the library list…";
            return null;
        }
        if (looksLikeProblem(lower)) return raw.trim();
        String gradle = gradleText(raw);
        if (gradle != null) return gradle + "…";
        for (String[] rule : ACTIVITY) if (lower.contains(rule[0])) return rule[1];
        return null;
    }

    /** The text after the given tag, or null when the line does not start with it. */
    private static String tagged(String raw, String tag) {
        String text = raw.trim();
        if (!text.regionMatches(true, 0, tag, 0, tag.length())) return null;
        return text.substring(tag.length()).trim();
    }

    /** Plain-words description of a line of Gradle's own output (daemon start, project configuration, tasks), or null to hide it. */
    private static String gradleText(String raw) {
        String text = raw.trim();
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.startsWith("starting a gradle daemon") || lower.contains("single-use daemon")) return "Preparing the build engine";
        if (lower.startsWith("> configure project")) return "Configuring the project";
        if (lower.startsWith("downloading http")) {
            java.util.regex.Matcher url = GET_URL_ANY.matcher(text);
            if (url.find()) {
                String file = url.group(1);
                int query = file.indexOf('?');
                if (query >= 0) file = file.substring(0, query);
                if (!file.isEmpty()) return "Downloading " + file;
            }
            return "Downloading libraries";
        }
        if (lower.startsWith("> task :")) {
            String task = withoutVariant(lower);
            for (String[] rule : GRADLE_TASKS)
                for (int i = 1; i < rule.length; i++) if (task.contains(rule[i])) return rule[0];
        }
        return null;
    }
    private static final java.util.regex.Pattern GET_URL_ANY = java.util.regex.Pattern.compile("(?i)^downloading\\s+\\S*/([^/\\s]+)");

    /** True for the fixed step labels (and the first "Preparing" line), so the log can tell a step from a detail or a message. */
    static boolean isStepLabel(String line) {
        if (line == null) return false;
        if (line.equals(PREPARING)) return true;
        for (String step : STEPS) if (line.equals(step)) return true;
        return false;
    }

    /**
     * Detail line shown under the current step in the Build log (library downloads and DEX parts), or null when the step label alone
     * already says it. It never repeats a step label, so "Optimizing code for Android…" appears once. Failed downloads are not
     * returned here: they are problems and go through {@link #translate} unchanged.
     */
    static String historyText(String raw) {
        if (raw == null) return null;
        String lower = raw.toLowerCase(Locale.ROOT).trim();
        String tagged = tagged(raw, TAG_STEP);
        if (tagged != null) return tagged.isEmpty() ? null : tagged;
        if (tagged(raw, TAG_PROGRESS) != null || tagged(raw, TAG_INTERNAL) != null) return null;
        if (isKnownNotice(lower)) return null;
        if (lower.startsWith("[dex]")) {
            java.util.regex.Matcher m = DEX_LINE.matcher(raw.trim());
            if (m.matches()) return m.group(1).equals("start") ? "Part " + m.group(2) + "/" + m.group(3) + " · " + m.group(4) : null;
            return lower.contains("merging") ? "Combining all parts" : null;
        }
        if (isDownloadLabel(lower)) return lower.contains("-> failed") ? null : liveText(raw);
        if (looksLikeProblem(lower)) return null;
        return gradleText(raw);
    }

    private int reached = -1;

    /** @return the lines to show for this internal line (possibly several steps at once), or null to hide it */
    synchronized String translate(String raw) {
        if (raw == null) return null;
        String lower = raw.toLowerCase(Locale.ROOT).trim();
        String stepTag = tagged(raw, TAG_STEP);
        if (stepTag != null) return stepTag.equals(ENGINE_START) ? advanceTo(START_STEP) : null;   // only the engine start is also a step of its own
        if (tagged(raw, TAG_PROGRESS) != null || tagged(raw, TAG_INTERNAL) != null) return null;   // written for the screen already; never a problem, never a new step
        if (isKnownNotice(lower)) return null;   // stays in the full log, never on the screen
        if (lower.startsWith("[dex]")) return null;   // progress of the DEX step: only the live line uses it (a JAR name may contain "error")
        if (isDownloadLabel(lower)) return lower.contains("-> failed") ? raw : advanceTo(DOWNLOAD_STEP);
        if (looksLikeProblem(lower)) return raw;
        int step = stepOf(lower);
        return step < 0 ? null : advanceTo(step);
    }

    /**
     * Moves forward to the given step (never backwards) and returns that step's line, or null if nothing new. Steps that were skipped are
     * not listed: a route that never runs a stage (no crash check, no separate engine start) must not show it with 0:00.
     */
    synchronized String advanceTo(int step) {
        if (step <= reached) return null;
        reached = step;
        return STEPS[step];
    }

    /** The step reached so far (0-based), or -1 before the first one. */
    synchronized int reachedStep() { return reached; }

    /** Plain-language name of the step reached so far. */
    synchronized String currentLabel() { return reached < 0 ? PREPARING : STEPS[reached]; }

    private static final java.util.regex.Pattern DEX_LINE = java.util.regex.Pattern.compile("^\\[dex]\\s+(start|done)\\s+(\\d+)/(\\d+)\\s+(.*?)(?:\\s+\\(\\d+ ms\\))?$");

    /** "[dex] start 3/13 poi.jar" becomes "Optimizing code for Android… 2 of 13 parts done · now: poi.jar". */
    private static String dexActivity(String line) {
        String base = "Converting code to run on Android…";
        java.util.regex.Matcher m = DEX_LINE.matcher(line);
        if (m.matches()) {
            int n = Integer.parseInt(m.group(2)), total = Integer.parseInt(m.group(3));
            if (m.group(1).equals("start")) return base + " " + (n - 1) + " of " + total + " parts done · now: " + m.group(4);
            return base + " " + n + " of " + total + " parts done";
        }
        if (line.toLowerCase(Locale.ROOT).contains("merging")) return base + " all parts done · combining";
        return null;
    }

    static boolean isDownloadLabel(String lower) {
        for (String label : DOWNLOAD_LABELS) if (lower.startsWith(label)) return true;
        return false;
    }

    private static int stepOf(String lower) {
        String line = withoutVariant(lower);
        for (String[] rule : RULES) {
            for (int i = 1; i < rule.length; i++) if (line.contains(rule[i])) return Integer.parseInt(rule[0]);
        }
        return -1;
    }

    /** "> task :app:mergeDebugResources" and "...mergeReleaseResources" both become "...mergeresources", so one table serves every variant. */
    static String withoutVariant(String lower) { return lower.replace("debug", "").replace("release", ""); }

    /** Tool output arrives as plain lines, so errors and warnings have to be recognised by their wording. */
    /**
     * Notices Gradle and AGP print because of how Studio Mobile starts Gradle, which the reader cannot act on: the "experimental" warning
     * for the aapt2 override (it also prints the app's private path), its "Add android.sync.suppressAgpWarnings=..." follow-up line, and
     * the daemon environment note. Matched narrowly, so every other warning is still shown.
     */
    static boolean isKnownNotice(String lower) {
        if (lower == null) return false;
        if (lower.contains("aapt2frommavenoverride") && lower.contains("experimental")) return true;
        // Gradle cannot hand the phone's environment variables to its daemon ("no native integration with this operating environment"); harmless here.
        if (lower.contains("unable to set daemon's environment variables")) return true;
        return lower.contains("suppressagpwarnings=unsupported_project_option_use");
    }

    static boolean looksLikeProblem(String lower) {
        if (lower.contains("using the in-process")) return false;   // a normal fallback notice (zipalign), not a problem
        return lower.contains("error") || lower.contains("exception") || lower.contains("warning")
            || lower.contains("failed") || lower.contains("failure") || lower.contains("what went wrong")
            || lower.contains("could not") || lower.contains("cannot ") || lower.contains("unresolved")
            || lower.startsWith("e: ") || lower.startsWith("w: ");
    }
}
