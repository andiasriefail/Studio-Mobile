package com.example.studiomobile.workspace;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Replaces the app's private folders in log text with {@code <app>}, so the package name and storage layout never reach the screen,
 * Copy Log or an error report. Nothing here names the package: the folders come from the running app, and the patterns cover every form
 * Android uses (/data/user/0/..., /data/data/..., another user's /data/user/10/..., and the app's folder on shared storage).
 */
public final class LogPathMasker {
    public static final String MARK = "<app>";

    private static final Pattern PRIVATE = Pattern.compile("/data/(?:user(?:_de)?/\\d+|data|user)/[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+");
    private static final Pattern SHARED = Pattern.compile("/(?:storage/emulated/\\d+|sdcard|storage/self/primary)/Android/(?:data|media|obb)/[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+");

    private static volatile List<String> literals = Collections.emptyList();

    private LogPathMasker() { }

    /** Folders of this app as the running process sees them (files, cache, their parent); the longest are replaced first. */
    public static void init(String... folders) {
        List<String> list = new ArrayList<>();
        if (folders != null) for (String folder : folders) if (folder != null && folder.length() > 8 && !list.contains(folder)) list.add(folder);
        Collections.sort(list, new Comparator<String>() { @Override public int compare(String a, String b) { return b.length() - a.length(); } });
        literals = list;
    }

    public static String mask(String text) {
        if (text == null || text.isEmpty()) return text;
        String result = text;
        for (String literal : literals) if (result.contains(literal)) result = result.replace(literal, MARK);
        if (result.contains("/data/") || result.contains("/Android/")) {
            result = replace(PRIVATE, result);
            result = replace(SHARED, result);
        }
        return result;
    }

    private static String replace(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.replaceAll(Matcher.quoteReplacement(MARK)) : text;
    }
}
