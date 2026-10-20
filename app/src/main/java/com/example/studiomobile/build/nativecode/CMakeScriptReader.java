package com.example.studiomobile.build.nativecode;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Reads the small subset of CMake that typical Android JNI projects use, so Studio Mobile can compile them with clang
 * directly when no cmake/ninja binary is installed. Anything outside the subset is recorded in {@link #unsupported}
 * instead of being guessed; the caller then asks for a real CMake instead of producing a wrong library.
 *
 * <p>Supported: project, cmake_minimum_required, set (plain values), find_library, add_library(SHARED|MODULE),
 * target_link_libraries, include_directories, target_include_directories, add_compile_options, target_compile_options,
 * add_definitions, target_compile_definitions, message.
 */
public final class CMakeScriptReader {
    /** One shared library declared with add_library. */
    public static final class Target {
        public final String name;
        public final List<String> sources = new ArrayList<>();
        public final List<String> libraries = new ArrayList<>();
        public final List<String> includeDirectories = new ArrayList<>();
        public final List<String> compileOptions = new ArrayList<>();
        Target(String name) { this.name = name; }
    }

    private static final Set<String> IGNORED = new HashSet<>(Arrays.asList(
        "cmake_minimum_required", "project", "message", "enable_language", "set_target_properties", "cmake_policy"));

    public final Map<String, Target> targets = new LinkedHashMap<>();
    public final List<String> unsupported = new ArrayList<>();
    public final List<String> globalIncludeDirectories = new ArrayList<>();
    public final List<String> globalCompileOptions = new ArrayList<>();
    private final Map<String, List<String>> variables = new HashMap<>();

    private CMakeScriptReader() { }

    public static CMakeScriptReader read(File cmakeLists) throws IOException {
        String text = new String(Files.readAllBytes(cmakeLists.toPath()), StandardCharsets.UTF_8);
        return parse(text);
    }

    public static CMakeScriptReader parse(String text) {
        CMakeScriptReader reader = new CMakeScriptReader();
        for (String name : new String[] {"CMAKE_CURRENT_SOURCE_DIR", "CMAKE_SOURCE_DIR", "PROJECT_SOURCE_DIR", "CMAKE_CURRENT_LIST_DIR"})
            reader.variables.put(name, Arrays.asList("."));
        reader.run(stripComments(text));
        return reader;
    }

    /** Names of every shared library target, in declaration order. */
    public List<String> libraryNames() { return new ArrayList<>(targets.keySet()); }

    private void run(String text) {
        int index = 0, length = text.length();
        while (index < length) {
            char c = text.charAt(index);
            if (!(Character.isLetter(c) || c == '_')) { index++; continue; }
            int start = index;
            while (index < length && (Character.isLetterOrDigit(text.charAt(index)) || text.charAt(index) == '_')) index++;
            String command = text.substring(start, index).toLowerCase(Locale.ROOT);
            int look = index;
            while (look < length && Character.isWhitespace(text.charAt(look))) look++;
            if (look >= length || text.charAt(look) != '(') continue;
            int end = matchingParenthesis(text, look);
            if (end < 0) { unsupported.add("unterminated " + command + "( ... )"); return; }
            handle(command, split(text.substring(look + 1, end)));
            index = end + 1;
        }
    }

    private void handle(String command, List<String> raw) {
        if (IGNORED.contains(command)) return;
        List<String> args = expand(raw);
        switch (command) {
            case "set": handleSet(args); break;
            case "find_library": if (args.size() >= 2) variables.put(args.get(0), Arrays.asList(args.get(1))); break;
            case "add_library": handleAddLibrary(args); break;
            case "target_link_libraries": handleTargetList(args, "libraries"); break;
            case "include_directories": globalIncludeDirectories.addAll(strip(args, "SYSTEM", "BEFORE", "AFTER")); break;
            case "target_include_directories": handleTargetList(args, "includeDirectories"); break;
            case "add_compile_options": globalCompileOptions.addAll(args); break;
            case "add_definitions": globalCompileOptions.addAll(args); break;
            case "target_compile_options": handleTargetList(args, "compileOptions"); break;
            case "target_compile_definitions": handleDefinitions(args); break;
            default: unsupported.add(command + "()");
        }
    }

    private void handleSet(List<String> args) {
        if (args.isEmpty()) return;
        String name = args.get(0);
        if (name.startsWith("CMAKE_C_FLAGS") || name.startsWith("CMAKE_CXX_FLAGS")) {
            for (int i = 1; i < args.size(); i++) for (String flag : args.get(i).trim().split("\\s+")) if (!flag.isEmpty() && !flag.equals("CACHE")) globalCompileOptions.add(flag);
            return;
        }
        if (name.startsWith("CMAKE_")) return; // standards, build type and similar: clang defaults are used
        List<String> values = new ArrayList<>(args.subList(1, args.size()));
        values.remove("CACHE"); values.remove("PARENT_SCOPE");
        variables.put(name, values);
    }

    private void handleAddLibrary(List<String> args) {
        if (args.size() < 2) { unsupported.add("add_library() without a type"); return; }
        String name = args.get(0), type = args.get(1).toUpperCase(Locale.ROOT);
        if (!type.equals("SHARED") && !type.equals("MODULE")) { unsupported.add("add_library(" + name + " " + args.get(1) + " ...): only SHARED libraries can be built directly"); return; }
        Target target = new Target(name);
        target.sources.addAll(args.subList(2, args.size()));
        targets.put(name, target);
    }

    private void handleTargetList(List<String> args, String field) {
        if (args.isEmpty()) return;
        Target target = targets.get(args.get(0));
        if (target == null) { unsupported.add("target settings for unknown target '" + args.get(0) + "'"); return; }
        List<String> values = strip(args.subList(1, args.size()), "PRIVATE", "PUBLIC", "INTERFACE", "SYSTEM", "BEFORE", "AFTER");
        if (field.equals("libraries")) {
            for (String value : values) {
                if (targets.containsKey(value)) unsupported.add("target '" + target.name + "' links to project target '" + value + "'");
                else target.libraries.add(value);
            }
        } else if (field.equals("includeDirectories")) target.includeDirectories.addAll(values);
        else target.compileOptions.addAll(values);
    }

    private void handleDefinitions(List<String> args) {
        if (args.isEmpty()) return;
        Target target = targets.get(args.get(0));
        if (target == null) { unsupported.add("target_compile_definitions for unknown target '" + args.get(0) + "'"); return; }
        for (String definition : strip(args.subList(1, args.size()), "PRIVATE", "PUBLIC", "INTERFACE")) target.compileOptions.add(definition.startsWith("-D") ? definition : "-D" + definition);
    }

    private static List<String> strip(List<String> values, String... keywords) {
        List<String> result = new ArrayList<>(values);
        result.removeAll(Arrays.asList(keywords));
        return result;
    }

    private List<String> expand(List<String> raw) {
        List<String> result = new ArrayList<>();
        for (String token : raw) {
            if (token.startsWith("${") && token.endsWith("}") && token.indexOf("${", 2) < 0) {
                List<String> value = variables.get(token.substring(2, token.length() - 1));
                if (value == null) { unsupported.add("unresolved variable " + token); continue; }
                result.addAll(value);
                continue;
            }
            String text = token;
            int open;
            while ((open = text.indexOf("${")) >= 0) {
                int close = text.indexOf('}', open);
                if (close < 0) break;
                List<String> value = variables.get(text.substring(open + 2, close));
                if (value == null || value.isEmpty()) { unsupported.add("unresolved variable " + text.substring(open, close + 1)); text = text.substring(0, open) + text.substring(close + 1); }
                else text = text.substring(0, open) + value.get(0) + text.substring(close + 1);
            }
            if (text.contains("$<")) { unsupported.add("generator expression " + text); continue; }
            result.add(text);
        }
        return result;
    }

    static String stripComments(String text) {
        StringBuilder out = new StringBuilder(text.length());
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"' && (i == 0 || text.charAt(i - 1) != '\\')) quoted = !quoted;
            if (c == '#' && !quoted) { while (i < text.length() && text.charAt(i) != '\n') i++; if (i < text.length()) out.append('\n'); continue; }
            out.append(c);
        }
        return out.toString();
    }

    private static int matchingParenthesis(String text, int open) {
        int depth = 0; boolean quoted = false;
        for (int i = open; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"' && text.charAt(i - 1) != '\\') quoted = !quoted;
            if (quoted) continue;
            if (c == '(') depth++;
            else if (c == ')' && --depth == 0) return i;
        }
        return -1;
    }

    private static List<String> split(String body) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder(); boolean quoted = false, any = false;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '"') { quoted = !quoted; any = true; continue; }
            if (Character.isWhitespace(c) && !quoted) { if (any || current.length() > 0) tokens.add(current.toString()); current.setLength(0); any = false; continue; }
            current.append(c);
        }
        if (any || current.length() > 0) tokens.add(current.toString());
        return tokens;
    }
}
