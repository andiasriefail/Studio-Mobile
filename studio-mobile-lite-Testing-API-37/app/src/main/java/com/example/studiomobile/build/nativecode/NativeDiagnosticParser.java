package com.example.studiomobile.build.nativecode;

import com.example.studiomobile.diagnostics.BuildDiagnostic;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Turns clang, lld and CMake output into structured diagnostics that the editor can jump to. */
public final class NativeDiagnosticParser {
    private static final Pattern COMPILER = Pattern.compile("^(.+?):(\\d+):(?:(\\d+):)?\\s*(fatal error|error|warning|note):\\s*(.*)$");
    private static final Pattern LINKER = Pattern.compile("^(?:\\S*ld(?:\\.lld)?|\\S*clang[\\w.+-]*):\\s*(error|warning):\\s*(.*)$");
    private static final Pattern CMAKE = Pattern.compile("^CMake (Error|Warning) at (\\S+?):(\\d+)\\b.*$");
    private NativeDiagnosticParser() { }

    public static List<BuildDiagnostic> parse(String output) {
        List<BuildDiagnostic> result = new ArrayList<>();
        if (output == null) return result;
        for (String line : output.split("\\r?\\n")) {
            Matcher compiler = COMPILER.matcher(line);
            if (compiler.matches()) { result.add(new BuildDiagnostic(severity(compiler.group(4)), compiler.group(5).trim(), compiler.group(1), Integer.parseInt(compiler.group(2)))); continue; }
            Matcher cmake = CMAKE.matcher(line);
            if (cmake.matches()) { result.add(new BuildDiagnostic(severity(cmake.group(1)), "CMake " + cmake.group(1).toLowerCase(), cmake.group(2), Integer.parseInt(cmake.group(3)))); continue; }
            Matcher linker = LINKER.matcher(line);
            if (linker.matches()) result.add(new BuildDiagnostic(severity(linker.group(1)), linker.group(2).trim(), null, 0));
        }
        return result;
    }

    /** One line per error, at most {@code limit} lines: "file:line: message". */
    public static String summarizeErrors(List<BuildDiagnostic> diagnostics, int limit) {
        StringBuilder text = new StringBuilder(); int shown = 0, total = 0;
        for (BuildDiagnostic diagnostic : diagnostics) {
            if (diagnostic.getSeverity() != BuildDiagnostic.Severity.ERROR) continue;
            total++;
            if (shown >= limit) continue;
            text.append("\n- ");
            if (diagnostic.getFilePath() != null) text.append(diagnostic.getFilePath()).append(':').append(diagnostic.getLineNumber()).append(": ");
            text.append(diagnostic.getMessage()); shown++;
        }
        if (total > shown) text.append("\n- … and ").append(total - shown).append(" more error(s). See the build log.");
        return text.toString();
    }

    private static BuildDiagnostic.Severity severity(String word) {
        String value = word.toLowerCase();
        if (value.contains("error")) return BuildDiagnostic.Severity.ERROR;
        if (value.equals("warning")) return BuildDiagnostic.Severity.WARNING;
        return BuildDiagnostic.Severity.INFO;
    }
}
