package com.example.studiomobile.workspace;

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
        "Processing resources…",
        "Compiling code…",
        "Running crash check…",
        "Optimizing code for Android…",
        "Packaging the app…",
        "Signing and verifying the app…",
        "Almost done…"
    };
    static final int LAST_STEP = STEPS.length - 1;
    static final int DOWNLOAD_STEP = 2;
    static final String PREPARING = "Preparing your project…";
    static final String SUCCESS = "Build successful";

    /**
     * Registered labels of library downloads. The resolver tags every line it writes about a download with one of these, and the Build screen
     * (and the build notification) treat such a line as progress, never as a problem: library names such as "error_prone_annotations" or
     * "kotlin-exceptions" would otherwise look like errors to {@link #looksLikeProblem}. A download that really fails ("-> FAILED") is still shown.
     */
    static final String[] DOWNLOAD_LABELS = {"[maven]", "[download]", "[dns]"};

    /** Rules are checked in order, so the more specific (later) stages come first. */
    private static final String[][] RULES = {
        {"3", "packagedebugresources"},
        {"8", "apk signing", "signing and verification", "app bundle packaging"},
        {"7", "native c/c++ build", "apk packaging", "apk alignment", "packagedebug", "assembledebug"},
        {"6", "dex generation", "code shrinking", "dexbuilder", "mergeprojectdex", "mergeextdex"},
        {"5", "crash check"},
        {"4", "dart compilation", "java/kotlin compilation", "compiledebugkotlin", "compiledebugjavawithjavac"},
        {"3", "resource compilation", "resource linking", "generated sources", "jsx/typescript", "aapt", "mergedebugresources", "processdebugresources"},
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
        {"dex generation", "Optimizing code for Android…"},
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
        for (String[] rule : ACTIVITY) if (lower.contains(rule[0])) return rule[1];
        return null;
    }

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
        if (lower.startsWith("[dex]")) {
            java.util.regex.Matcher m = DEX_LINE.matcher(raw.trim());
            if (m.matches()) return m.group(1).equals("start") ? "Part " + m.group(2) + "/" + m.group(3) + " · " + m.group(4) : null;
            return lower.contains("merging") ? "Combining all parts" : null;
        }
        if (isDownloadLabel(lower)) return lower.contains("-> failed") ? null : liveText(raw);
        return null;
    }

    private int reached = -1;

    /** @return the lines to show for this internal line (possibly several steps at once), or null to hide it */
    synchronized String translate(String raw) {
        if (raw == null) return null;
        String lower = raw.toLowerCase(Locale.ROOT).trim();
        if (lower.startsWith("[dex]")) return null;   // progress of the DEX step: only the live line uses it (a JAR name may contain "error")
        if (isDownloadLabel(lower)) return lower.contains("-> failed") ? raw : advanceTo(DOWNLOAD_STEP);
        if (looksLikeProblem(lower)) return raw;
        int step = stepOf(lower);
        return step < 0 ? null : advanceTo(step);
    }

    /** Moves forward to the given step (never backwards) and returns the new step lines, or null if nothing new. */
    synchronized String advanceTo(int step) {
        if (step <= reached) return null;
        StringBuilder out = new StringBuilder();
        for (int i = reached + 1; i <= step; i++) {
            if (out.length() > 0) out.append('\n');
            out.append(STEPS[i]);
        }
        reached = step;
        return out.toString();
    }

    /** The step reached so far (0-based), or -1 before the first one. */
    synchronized int reachedStep() { return reached; }

    /** Plain-language name of the step reached so far. */
    synchronized String currentLabel() { return reached < 0 ? PREPARING : STEPS[reached]; }

    private static final java.util.regex.Pattern DEX_LINE = java.util.regex.Pattern.compile("^\\[dex]\\s+(start|done)\\s+(\\d+)/(\\d+)\\s+(.*?)(?:\\s+\\(\\d+ ms\\))?$");

    /** "[dex] start 3/13 poi.jar" becomes "Optimizing code for Android… 2 of 13 parts done · now: poi.jar". */
    private static String dexActivity(String line) {
        String base = "Optimizing code for Android…";
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
        for (String[] rule : RULES) {
            for (int i = 1; i < rule.length; i++) if (lower.contains(rule[i])) return Integer.parseInt(rule[0]);
        }
        return -1;
    }

    /** Tool output arrives as plain lines, so errors and warnings have to be recognised by their wording. */
    static boolean looksLikeProblem(String lower) {
        if (lower.contains("using the in-process")) return false;   // a normal fallback notice (zipalign), not a problem
        return lower.contains("error") || lower.contains("exception") || lower.contains("warning")
            || lower.contains("failed") || lower.contains("failure") || lower.contains("what went wrong")
            || lower.contains("could not") || lower.contains("cannot ") || lower.contains("unresolved")
            || lower.startsWith("e: ") || lower.startsWith("w: ");
    }
}
