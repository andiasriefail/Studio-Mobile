package com.example.studiomobile.build.dependency;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal TOML reader for Gradle version catalogs. Produces nested {@code LinkedHashMap} tables, {@code ArrayList} arrays and
 * {@code String} values (numbers, booleans and dates stay as their raw text, which a catalog never needs to interpret).
 *
 * Supported: comments, {@code [table]} and {@code [a.b]} headers, dotted keys ({@code version.ref = "x"}), quoted keys, basic /
 * literal / multi-line strings, arrays and inline tables that span several lines, trailing commas.
 * Not supported: {@code [[array of tables]]} (their content is read and dropped). Malformed input throws
 * {@link IllegalArgumentException} with the line number.
 */
final class TomlReader {
    private final String text;
    private int pos;

    private TomlReader(String text) { this.text = text; }

    static Map<String, Object> parse(String source) {
        String normalized = source == null ? "" : source.replace("\r\n", "\n").replace('\r', '\n');
        if (normalized.startsWith("\uFEFF")) normalized = normalized.substring(1);
        return new TomlReader(normalized).document();
    }

    // ---------------------------------------------------------------- document

    private Map<String, Object> document() {
        Map<String, Object> root = new LinkedHashMap<>();
        Map<String, Object> current = root;
        while (true) {
            skipBlank();
            if (atEnd()) return root;
            if (peek() == '[') {
                boolean arrayOfTables = text.startsWith("[[", pos);
                pos += arrayOfTables ? 2 : 1;
                List<String> path = keyPath();
                expect(']');
                if (arrayOfTables) expect(']');
                endOfLine();
                current = arrayOfTables ? new LinkedHashMap<String, Object>() : table(root, path);
            } else {
                List<String> path = keyPath();
                skipSpaces();
                expect('=');
                skipSpaces();
                Object value = value();
                endOfLine();
                assign(current, path, value);
            }
        }
    }

    private Map<String, Object> table(Map<String, Object> root, List<String> path) {
        Map<String, Object> current = root;
        for (String segment : path) current = child(current, segment);
        return current;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> child(Map<String, Object> parent, String key) {
        Object existing = parent.get(key);
        if (existing == null) { Map<String, Object> created = new LinkedHashMap<>(); parent.put(key, created); return created; }
        if (existing instanceof Map) return (Map<String, Object>) existing;
        throw error("'" + key + "' is defined twice with different types");
    }

    private void assign(Map<String, Object> map, List<String> path, Object value) {
        Map<String, Object> target = map;
        for (int i = 0; i < path.size() - 1; i++) target = child(target, path.get(i));
        target.put(path.get(path.size() - 1), value);
    }

    // ---------------------------------------------------------------- keys

    private List<String> keyPath() {
        List<String> path = new ArrayList<>();
        while (true) {
            skipSpaces();
            path.add(keySegment());
            skipSpaces();
            if (!atEnd() && peek() == '.') { pos++; continue; }
            return path;
        }
    }

    private String keySegment() {
        if (atEnd()) throw error("Expected a key");
        char c = peek();
        if (c == '"') return basicString();
        if (c == '\'') return literalString();
        int start = pos;
        while (!atEnd() && (Character.isLetterOrDigit(peek()) || peek() == '_' || peek() == '-')) pos++;
        if (start == pos) throw error("Expected a key but found '" + c + "'");
        return text.substring(start, pos);
    }

    // ---------------------------------------------------------------- values

    private Object value() {
        if (atEnd()) throw error("Expected a value");
        char c = peek();
        if (c == '"') return basicString();
        if (c == '\'') return literalString();
        if (c == '[') return array();
        if (c == '{') return inlineTable();
        return bareValue();
    }

    private List<Object> array() {
        pos++;
        List<Object> list = new ArrayList<>();
        while (true) {
            skipBlank();
            if (atEnd()) throw error("Unterminated array");
            if (peek() == ']') { pos++; return list; }
            list.add(value());
            skipBlank();
            if (!atEnd() && peek() == ',') pos++;
            else if (atEnd() || peek() != ']') throw error("Expected ',' or ']' in array");
        }
    }

    private Map<String, Object> inlineTable() {
        pos++;
        Map<String, Object> map = new LinkedHashMap<>();
        while (true) {
            skipBlank();
            if (atEnd()) throw error("Unterminated inline table");
            if (peek() == '}') { pos++; return map; }
            List<String> path = keyPath();
            skipSpaces();
            expect('=');
            skipBlank();
            assign(map, path, value());
            skipBlank();
            if (!atEnd() && peek() == ',') pos++;
            else if (atEnd() || peek() != '}') throw error("Expected ',' or '}' in inline table");
        }
    }

    private String bareValue() {
        int start = pos;
        while (!atEnd()) {
            char c = peek();
            if (c == ',' || c == ']' || c == '}' || c == '#' || Character.isWhitespace(c)) break;
            pos++;
        }
        if (start == pos) throw error("Expected a value but found '" + peek() + "'");
        return text.substring(start, pos);
    }

    // ---------------------------------------------------------------- strings

    private String basicString() {
        if (text.startsWith("\"\"\"", pos)) return multiLine("\"\"\"", true);
        pos++;
        StringBuilder out = new StringBuilder();
        while (true) {
            if (atEnd() || peek() == '\n') throw error("Unterminated string");
            char c = text.charAt(pos++);
            if (c == '"') return out.toString();
            if (c == '\\') out.append(escape()); else out.append(c);
        }
    }

    private String literalString() {
        if (text.startsWith("'''", pos)) return multiLine("'''", false);
        pos++;
        int end = text.indexOf('\'', pos);
        int newline = text.indexOf('\n', pos);
        if (end < 0 || (newline >= 0 && newline < end)) throw error("Unterminated string");
        String value = text.substring(pos, end);
        pos = end + 1;
        return value;
    }

    private String multiLine(String delimiter, boolean escapes) {
        pos += 3;
        if (!atEnd() && peek() == '\n') pos++;
        StringBuilder out = new StringBuilder();
        while (true) {
            if (atEnd()) throw error("Unterminated multi-line string");
            if (text.startsWith(delimiter, pos)) { pos += 3; return out.toString(); }
            char c = text.charAt(pos++);
            if (escapes && c == '\\') {
                if (!atEnd() && (peek() == '\n' || peek() == ' ' || peek() == '\t')) { // line-ending backslash trims the whitespace that follows
                    while (!atEnd() && Character.isWhitespace(peek())) pos++;
                } else out.append(escape());
            } else out.append(c);
        }
    }

    private String escape() {
        if (atEnd()) throw error("Unterminated escape sequence");
        char c = text.charAt(pos++);
        switch (c) {
            case 'n': return "\n";
            case 't': return "\t";
            case 'r': return "\r";
            case 'b': return "\b";
            case 'f': return "\f";
            case '"': return "\"";
            case '\\': return "\\";
            case 'u': return unicode(4);
            case 'U': return unicode(8);
            default: throw error("Invalid escape sequence \\" + c);
        }
    }

    private String unicode(int digits) {
        if (pos + digits > text.length()) throw error("Invalid unicode escape");
        try {
            int codePoint = Integer.parseInt(text.substring(pos, pos + digits), 16);
            pos += digits;
            return new String(Character.toChars(codePoint));
        } catch (IllegalArgumentException invalid) { throw error("Invalid unicode escape"); }
    }

    // ---------------------------------------------------------------- scanning helpers

    private boolean atEnd() { return pos >= text.length(); }
    private char peek() { return text.charAt(pos); }

    private void expect(char c) {
        if (atEnd() || peek() != c) throw error("Expected '" + c + "'");
        pos++;
    }

    private void skipSpaces() { while (!atEnd() && (peek() == ' ' || peek() == '\t')) pos++; }

    /** Skips whitespace, blank lines and comments. */
    private void skipBlank() {
        while (!atEnd()) {
            char c = peek();
            if (c == '#') { while (!atEnd() && peek() != '\n') pos++; }
            else if (Character.isWhitespace(c)) pos++;
            else return;
        }
    }

    private void endOfLine() {
        skipSpaces();
        if (!atEnd() && peek() == '#') { while (!atEnd() && peek() != '\n') pos++; }
        if (!atEnd() && peek() != '\n') throw error("Unexpected text after value: '" + peek() + "'");
    }

    private IllegalArgumentException error(String message) {
        int line = 1;
        for (int i = 0; i < pos && i < text.length(); i++) if (text.charAt(i) == '\n') line++;
        return new IllegalArgumentException("TOML line " + line + ": " + message);
    }
}
