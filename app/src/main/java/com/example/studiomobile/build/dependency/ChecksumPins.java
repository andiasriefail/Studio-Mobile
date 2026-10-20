package com.example.studiomobile.build.dependency;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * SHA-256 values the user pinned for library files, read from {@code gradle/checksums.properties} in the project. One line per library:
 * <pre>
 * # copied from the release page
 * com.github.User:Repo:1.0=2f5bec7a83d8...(64 hex characters)
 * </pre>
 * The pinned value is the SHA-256 of the library's main file (the .jar, or the .aar for Android libraries). A pin comes from outside the
 * server that serves the file, so a file that does not match is never used, whichever repository it came from. A leading "sha256:" is accepted.
 */
public final class ChecksumPins {
    public static final String FILE = "gradle/checksums.properties";
    private static final Pattern HEX = Pattern.compile("[0-9a-f]{64}");
    private static final ChecksumPins EMPTY = new ChecksumPins(Collections.<String, String>emptyMap());

    private final Map<String, String> pins;
    private ChecksumPins(Map<String, String> pins) { this.pins = pins; }

    public static ChecksumPins empty() { return EMPTY; }

    public static ChecksumPins load(File projectDirectory) throws IOException {
        File file = new File(projectDirectory, FILE);
        if (!file.isFile()) return EMPTY;
        return parse(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
    }

    static ChecksumPins parse(String text) throws ChecksumFailure {
        Map<String, String> pins = new LinkedHashMap<>();
        String[] lines = text.split("\r?\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            int hash = line.indexOf('#');
            if (hash >= 0) line = line.substring(0, hash);
            line = line.trim();
            if (line.isEmpty()) continue;
            int equals = line.indexOf('=');
            if (equals < 0) throw ChecksumFailure.invalidPinFile("line " + (i + 1) + " has no '='. Write group:artifact:version=sha256.");
            String coordinate = line.substring(0, equals).trim();
            String value = line.substring(equals + 1).trim().toLowerCase(Locale.ROOT);
            if (value.startsWith("sha256:")) value = value.substring(7).trim();
            String[] parts = coordinate.split(":", -1);
            if (parts.length != 3 || parts[0].isEmpty() || parts[1].isEmpty() || parts[2].isEmpty())
                throw ChecksumFailure.invalidPinFile("line " + (i + 1) + " must start with group:artifact:version.");
            if (!HEX.matcher(value).matches())
                throw ChecksumFailure.invalidPinFile("line " + (i + 1) + ": the SHA-256 for " + coordinate + " must be exactly 64 hexadecimal characters.");
            String earlier = pins.put(coordinate, value);
            if (earlier != null && !earlier.equals(value))
                throw ChecksumFailure.invalidPinFile("line " + (i + 1) + ": " + coordinate + " is listed twice with different values.");
        }
        return new ChecksumPins(Collections.unmodifiableMap(pins));
    }

    /** Lowercase SHA-256 pinned for group:artifact:version, or null. */
    public String get(String coordinate) { return coordinate == null ? null : pins.get(coordinate); }
    public int size() { return pins.size(); }
    public boolean isEmpty() { return pins.isEmpty(); }
}
