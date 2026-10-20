package com.example.studiomobile.compat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Stand-in for java.lang.Runtime.Version, which Android does not have.
 *
 * ECJ calls Runtime.version() and Runtime.Version methods. The Gradle task patchEcj (app/build.gradle) rewrites the ECJ jar so that
 * Runtime.version() becomes {@link #current()} and every reference to java.lang.Runtime$Version becomes this class.
 * It only exists inside the Studio Mobile app itself; it never ends up in the APKs that Studio Mobile builds for users.
 */
public final class RuntimeVersion implements Comparable<RuntimeVersion> {
    /** Java language level the app compiles every project as (see JavaLanguageLevel.REQUIRED). */
    public static final int FEATURE = com.example.studiomobile.build.JavaLanguageLevel.REQUIRED;

    private static final RuntimeVersion CURRENT = new RuntimeVersion(Collections.singletonList(FEATURE));

    private final List<Integer> numbers;

    private RuntimeVersion(List<Integer> numbers) {
        this.numbers = Collections.unmodifiableList(new ArrayList<>(numbers));
    }

    /** Replacement for the static method Runtime.version(). */
    public static RuntimeVersion current() { return CURRENT; }

    /** Replacement for Runtime.Version.parse(String): accepts "21", "17.0.2", "21.0.1+12", "22-ea" (build and pre-release parts are ignored). */
    public static RuntimeVersion parse(String text) {
        if (text == null) throw new NullPointerException("version string is null");
        int end = text.length();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '-' || c == '+') { end = i; break; }
        }
        String core = text.substring(0, end);
        if (core.isEmpty()) throw new IllegalArgumentException("Invalid version string: '" + text + "'");
        List<Integer> parts = new ArrayList<>();
        for (String piece : core.split("\\.", -1)) {
            if (piece.isEmpty() || (piece.length() > 1 && piece.charAt(0) == '0'))
                throw new IllegalArgumentException("Invalid version string: '" + text + "'");
            try {
                parts.add(Integer.valueOf(piece));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid version string: '" + text + "'", e);
            }
        }
        return new RuntimeVersion(parts);
    }

    private int at(int index) { return index < numbers.size() ? numbers.get(index) : 0; }

    public int feature() { return at(0); }
    public int interim() { return at(1); }
    public int update() { return at(2); }
    public int patch() { return at(3); }
    /** @deprecated same as {@link #feature()}, like Runtime.Version.major(). */
    @Deprecated public int major() { return feature(); }
    /** @deprecated same as {@link #interim()}, like Runtime.Version.minor(). */
    @Deprecated public int minor() { return interim(); }
    /** @deprecated same as {@link #update()}, like Runtime.Version.security(). */
    @Deprecated public int security() { return update(); }
    public List<Integer> version() { return numbers; }
    public Optional<String> pre() { return Optional.empty(); }
    public Optional<Integer> build() { return Optional.empty(); }
    public Optional<String> optional() { return Optional.empty(); }

    @Override public int compareTo(RuntimeVersion other) {
        int size = Math.max(numbers.size(), other.numbers.size());
        for (int i = 0; i < size; i++) {
            int difference = Integer.compare(at(i), other.at(i));
            if (difference != 0) return difference;
        }
        return 0;
    }

    public int compareToIgnoreOptional(RuntimeVersion other) { return compareTo(other); }

    @Override public boolean equals(Object other) { return other instanceof RuntimeVersion && compareTo((RuntimeVersion) other) == 0; }
    public boolean equalsIgnoreOptional(Object other) { return equals(other); }

    @Override public int hashCode() {
        int hash = 1;
        for (int number : numbers) hash = 31 * hash + number;
        return hash;
    }

    @Override public String toString() {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < numbers.size(); i++) { if (i > 0) text.append('.'); text.append(numbers.get(i)); }
        return text.toString();
    }
}
