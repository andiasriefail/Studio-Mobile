package com.example.studiomobile.build.dependency;

import java.io.IOException;

/**
 * A downloaded library file failed its checksum check, or the project's checksum file is unusable. The build stops, and the app shows
 * this to the user in a dialog ({@link #title()} and {@link #details()}); {@link #getMessage()} is the one-line version for the log.
 */
public final class ChecksumFailure extends IOException {
    public enum Kind {
        /** The SHA-256 the user pinned in gradle/checksums.properties does not match the file. */
        PIN_MISMATCH,
        /** The file does not match the .sha1 its own server publishes: damaged or changed in transit. */
        SERVER_SHA1_MISMATCH,
        /** The server publishes no .sha1 and the user pinned no SHA-256, so the file cannot be verified. */
        SERVER_SHA1_MISSING,
        /** gradle/checksums.properties has a line that is not valid. */
        PIN_FILE_INVALID
    }

    private final Kind kind;
    private final String coordinate, fileName, repository, expected, actual, receivedSha256;

    private ChecksumFailure(Kind kind, String message, String coordinate, String fileName, String repository, String expected, String actual, String receivedSha256) {
        super(message);
        this.kind = kind; this.coordinate = coordinate; this.fileName = fileName; this.repository = repository;
        this.expected = expected; this.actual = actual; this.receivedSha256 = receivedSha256;
    }

    static ChecksumFailure pinMismatch(String coordinate, String fileName, String source, String pinned, String actual) {
        return new ChecksumFailure(Kind.PIN_MISMATCH,
            "SHA-256 of " + fileName + " (" + coordinate + ") from " + source + " does not match gradle/checksums.properties. Expected " + pinned + ", received " + actual + ". The file was not used.",
            coordinate, fileName, source, pinned, actual, actual);
    }

    static ChecksumFailure serverMismatch(String coordinate, String fileName, String repository, String expectedSha1, String actualSha1, String sha256) {
        return new ChecksumFailure(Kind.SERVER_SHA1_MISMATCH,
            "SHA-1 mismatch for " + repository + fileName + " (expected " + expectedSha1 + ", got " + actualSha1 + "). The file was not used.",
            coordinate, fileName, repository, expectedSha1, actualSha1, sha256);
    }

    static ChecksumFailure serverMissing(String coordinate, String fileName, String repository, String sha256) {
        return new ChecksumFailure(Kind.SERVER_SHA1_MISSING,
            "No published SHA-1 for " + repository + fileName + " and no SHA-256 pinned in gradle/checksums.properties; refusing to use an unverifiable file.",
            coordinate, fileName, repository, null, null, sha256);
    }

    static ChecksumFailure invalidPinFile(String problem) {
        return new ChecksumFailure(Kind.PIN_FILE_INVALID, "gradle/checksums.properties is not valid: " + problem, null, null, null, null, null, null);
    }

    public Kind kind() { return kind; }
    public String coordinate() { return coordinate; }
    public String fileName() { return fileName; }
    public String repository() { return repository; }
    public String expected() { return expected; }
    public String actual() { return actual; }
    /** SHA-256 of the file that was received, or null when none was downloaded. */
    public String receivedSha256() { return receivedSha256; }

    /** The line to put in gradle/checksums.properties for the received file, or null when there is none. */
    public String pinLine() { return coordinate != null && receivedSha256 != null ? coordinate + "=" + receivedSha256 : null; }

    public String title() {
        switch (kind) {
            case PIN_MISMATCH: return "SHA-256 does not match";
            case SERVER_SHA1_MISMATCH: return "Downloaded file is damaged";
            case SERVER_SHA1_MISSING: return "File cannot be verified";
            default: return "Checksum file is not valid";
        }
    }

    /** Plain-text explanation for the dialog: what failed, the values, and what the user can do. */
    public String details() {
        StringBuilder out = new StringBuilder();
        if (kind == Kind.PIN_FILE_INVALID) return getMessage() + "\n\nFix that line, then build again.";
        out.append("Library: ").append(coordinate).append('\n').append("File: ").append(fileName).append('\n').append("From: ").append(repository).append("\n\n");
        switch (kind) {
            case PIN_MISMATCH:
                out.append("Pinned SHA-256:\n").append(expected).append("\n\nReceived SHA-256:\n").append(actual).append("\n\n")
                   .append("The file was not used. Compare the SHA-256 with the one on the release page. If they differ, the file may have been changed. ")
                   .append("If the release page shows the received value and you trust it, update the line in gradle/checksums.properties.");
                break;
            case SERVER_SHA1_MISMATCH:
                out.append("SHA-1 published by the server:\n").append(expected).append("\n\nSHA-1 of the file:\n").append(actual).append("\n\n")
                   .append("The file was not used. Try again; if it keeps failing, the repository may be serving a damaged or changed file.");
                break;
            default:
                out.append("This repository publishes no checksum for the file, and gradle/checksums.properties has no SHA-256 for it.\n\n")
                   .append("Received SHA-256:\n").append(receivedSha256).append("\n\n")
                   .append("Compare it with the SHA-256 on the release page. If it is the same, add this line to gradle/checksums.properties and build again:\n")
                   .append(pinLine());
        }
        return out.toString();
    }

    /** Finds a ChecksumFailure in the cause chain of a build error, or null. */
    public static ChecksumFailure find(Throwable error) {
        for (int depth = 0; error != null && depth < 20; depth++, error = error.getCause()) if (error instanceof ChecksumFailure) return (ChecksumFailure) error;
        return null;
    }
}
