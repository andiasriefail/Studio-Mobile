package com.example.studiomobile.build;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the code-shrinking settings of the app module from build.gradle / build.gradle.kts.
 * This is NOT a Gradle interpreter: it understands the common literal forms of
 * {@code buildTypes { debug|release { minifyEnabled ...; proguardFiles ... } }} and reports everything else as a warning.
 *
 * Studio Mobile produces one APK per build, so shrinking is on when EITHER the debug or the release build type enables minify.
 * Rule files are taken from the build types that enable it.
 */
public final class ProguardSettings {
    private static final List<String> VARIANTS = Arrays.asList("debug", "release");
    private static final Pattern BUILD_TYPES = Pattern.compile("\\bbuildTypes\\s*\\{");
    private static final Pattern MINIFY = Pattern.compile("\\b(?:minifyEnabled|isMinifyEnabled)\\s*[=(]?\\s*(true|false)\\b");
    private static final Pattern SHRINK_RESOURCES = Pattern.compile("\\b(?:shrinkResources|isShrinkResources)\\s*[=(]?\\s*true\\b");
    private static final Pattern PROGUARD_FILES = Pattern.compile("\\b(?:proguardFiles|setProguardFiles)\\b");
    private static final Pattern DEFAULT_FILE = Pattern.compile("getDefaultProguardFile\\s*\\(\\s*[\"']([^\"']+)[\"']\\s*\\)");
    private static final Pattern LITERAL = Pattern.compile("[\"']([^\"']+)[\"']");

    /** Studio Mobile's own equivalent of the SDK's proguard-android-optimize.txt (the SDK file is not available on device). */
    static final List<String> BUILT_IN_RULES = Collections.unmodifiableList(Arrays.asList(
        "-keepattributes SourceFile,LineNumberTable,*Annotation*,Signature,InnerClasses,EnclosingMethod",
        "-renamesourcefileattribute SourceFile",
        "-keepclasseswithmembernames class * { native <methods>; }",
        "-keepclassmembers enum * { public static **[] values(); public static ** valueOf(java.lang.String); }",
        "-keepclassmembers class * implements android.os.Parcelable { public static final ** CREATOR; }",
        "-keepclassmembers class * implements java.io.Serializable {"
            + " static final long serialVersionUID; private static final java.io.ObjectStreamField[] serialPersistentFields;"
            + " private void writeObject(java.io.ObjectOutputStream); private void readObject(java.io.ObjectInputStream);"
            + " java.lang.Object writeReplace(); java.lang.Object readResolve(); }",
        "-keepclassmembers class * extends android.app.Activity { public void *(android.view.View); }"));

    private final boolean minifyEnabled;
    private final List<File> ruleFiles;
    private final List<File> missingRuleFiles;
    private final List<String> builtInRules;
    private final List<String> warnings;

    private ProguardSettings(boolean minifyEnabled, List<File> ruleFiles, List<File> missingRuleFiles, List<String> builtInRules, List<String> warnings) {
        this.minifyEnabled = minifyEnabled;
        this.ruleFiles = Collections.unmodifiableList(ruleFiles);
        this.missingRuleFiles = Collections.unmodifiableList(missingRuleFiles);
        this.builtInRules = Collections.unmodifiableList(builtInRules);
        this.warnings = Collections.unmodifiableList(warnings);
    }

    public boolean isMinifyEnabled() { return minifyEnabled; }
    /** Existing rule files declared in proguardFiles, in declaration order. */
    public List<File> getRuleFiles() { return ruleFiles; }
    /** Rule files declared in proguardFiles that do not exist. A build must fail when this is not empty. */
    public List<File> getMissingRuleFiles() { return missingRuleFiles; }
    /** Rules that stand in for getDefaultProguardFile(...); empty when the project does not ask for them. */
    public List<String> getBuiltInRules() { return builtInRules; }
    public List<String> getWarnings() { return warnings; }

    public static ProguardSettings read(File project) {
        List<String> warnings = new ArrayList<>();
        File module = BuildProjectFiles.moduleDirectory(project);
        File script = new File(module, "build.gradle");
        if (!script.isFile()) script = new File(module, "build.gradle.kts");
        if (!script.isFile()) return disabled(warnings);
        String text;
        try { text = stripComments(new String(Files.readAllBytes(script.toPath()), StandardCharsets.UTF_8)); }
        catch (IOException error) {
            warnings.add("Could not read " + script.getName() + " (" + error.getMessage() + "). Code shrinking is off.");
            return disabled(warnings);
        }
        String buildTypes = blockAfter(text, BUILD_TYPES);
        if (buildTypes == null) return disabled(warnings);

        boolean minify = false, useDefaults = false, optimize = false;
        Set<File> found = new LinkedHashSet<>();
        List<File> missing = new ArrayList<>();
        for (String variant : VARIANTS) {
            Pattern header = Pattern.compile("(?:\\b" + variant + "\\b|\\b(?:getByName|named|maybeCreate|create)\\s*\\(\\s*[\"']" + variant + "[\"']\\s*\\))\\s*\\{");
            String body = blockAfter(buildTypes, header);
            if (body == null) continue;
            boolean enabled = false;
            Matcher minifyMatcher = MINIFY.matcher(body);
            while (minifyMatcher.find()) enabled = "true".equals(minifyMatcher.group(1));
            if (!enabled) continue;
            minify = true;
            if (SHRINK_RESOURCES.matcher(body).find())
                warnings.add("shrinkResources is not supported yet and is ignored (" + variant + ").");
            Matcher files = PROGUARD_FILES.matcher(body);
            while (files.find()) {
                String arguments = readArguments(body, files.end());
                Matcher defaults = DEFAULT_FILE.matcher(arguments);
                while (defaults.find()) {
                    String name = defaults.group(1);
                    if (name.equals("proguard-android-optimize.txt") || name.equals("proguard-defaults.txt")) { useDefaults = true; optimize = true; }
                    else if (name.equals("proguard-android.txt")) useDefaults = true;
                    else warnings.add("Unknown default ProGuard file '" + name + "' is ignored.");
                }
                String rest = DEFAULT_FILE.matcher(arguments).replaceAll(" ");
                Matcher literal = LITERAL.matcher(rest);
                while (literal.find()) {
                    File file = new File(literal.group(1));
                    if (!file.isAbsolute()) file = new File(module, literal.group(1));
                    if (file.isFile()) found.add(file); else if (!missing.contains(file)) missing.add(file);
                }
            }
        }
        List<String> builtIn = new ArrayList<>();
        if (minify && useDefaults) {
            builtIn.addAll(BUILT_IN_RULES);
            if (!optimize) builtIn.add("-dontoptimize");
        }
        return new ProguardSettings(minify, new ArrayList<>(found), missing, builtIn, warnings);
    }

    private static ProguardSettings disabled(List<String> warnings) {
        return new ProguardSettings(false, new ArrayList<File>(), new ArrayList<File>(), new ArrayList<String>(), warnings);
    }

    /** Removes // and block comments while leaving string literals (for example URLs) untouched. */
    static String stripComments(String source) {
        StringBuilder out = new StringBuilder(source.length());
        char quote = 0;
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            if (quote != 0) {
                out.append(c);
                if (c == '\\' && i + 1 < source.length()) out.append(source.charAt(++i));
                else if (c == quote) quote = 0;
                continue;
            }
            if (c == '"' || c == '\'') { quote = c; out.append(c); continue; }
            if (c == '/' && i + 1 < source.length()) {
                char next = source.charAt(i + 1);
                if (next == '/') {
                    while (i < source.length() && source.charAt(i) != '\n') i++;
                    if (i < source.length()) out.append('\n');
                    continue;
                }
                if (next == '*') {
                    int end = source.indexOf("*/", i + 2);
                    if (end < 0) break;
                    i = end + 1;
                    out.append(' ');
                    continue;
                }
            }
            out.append(c);
        }
        return out.toString();
    }

    /** Text between the braces of the first block whose header matches (the header must end with an opening brace), or null. */
    private static String blockAfter(String text, Pattern header) {
        Matcher matcher = header.matcher(text);
        while (matcher.find()) {
            int open = matcher.end() - 1;
            int close = matchingBrace(text, open);
            if (close > open) return text.substring(open + 1, close);
        }
        return null;
    }

    private static int matchingBrace(String text, int open) {
        int depth = 0;
        char quote = 0;
        for (int i = open; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote != 0) { if (c == '\\') i++; else if (c == quote) quote = 0; continue; }
            if (c == '"' || c == '\'') quote = c;
            else if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return i;
        }
        return -1;
    }

    /** Arguments of a call: the balanced (...) group, or for a Groovy command call the line(s) joined by trailing commas. */
    private static String readArguments(String text, int from) {
        int i = from;
        while (i < text.length() && (text.charAt(i) == ' ' || text.charAt(i) == '\t')) i++;
        if (i < text.length() && text.charAt(i) == '(') {
            int depth = 0;
            char quote = 0;
            for (int j = i; j < text.length(); j++) {
                char c = text.charAt(j);
                if (quote != 0) { if (c == '\\') j++; else if (c == quote) quote = 0; continue; }
                if (c == '"' || c == '\'') quote = c;
                else if (c == '(') depth++;
                else if (c == ')' && --depth == 0) return text.substring(i + 1, j);
            }
            return text.substring(i + 1);
        }
        int end = from;
        while (true) {
            int newline = text.indexOf('\n', end);
            if (newline < 0) return text.substring(from);
            if (!text.substring(end, newline).trim().endsWith(",")) return text.substring(from, newline);
            end = newline + 1;
        }
    }
}
