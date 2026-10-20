package com.example.studiomobile.diagnostics;

/** Structured compiler/build diagnostic suitable for editor navigation. */
public final class BuildDiagnostic {
    public enum Severity { INFO, WARNING, ERROR }
    private final Severity severity;
    private final String message;
    private final String filePath;
    private final int lineNumber;

    public BuildDiagnostic(Severity severity, String message, String filePath, int lineNumber) {
        if (severity == null) throw new IllegalArgumentException("severity must not be null");
        this.severity = severity;
        this.message = message == null ? "" : message;
        this.filePath = filePath;
        this.lineNumber = Math.max(0, lineNumber);
    }
    public Severity getSeverity() { return severity; }
    public String getMessage() { return message; }
    public String getFilePath() { return filePath; }
    public int getLineNumber() { return lineNumber; }
}
