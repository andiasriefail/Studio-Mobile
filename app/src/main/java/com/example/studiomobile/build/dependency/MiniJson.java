package com.example.studiomobile.build.dependency;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Tiny JSON reader (objects, arrays, strings, numbers, booleans, null) so Gradle .module files parse the same on any JVM. */
public final class MiniJson {
    private final String text; private int pos;
    private MiniJson(String text) { this.text = text; }
    public static Object parse(String text) {
        MiniJson parser = new MiniJson(text); parser.skip();
        Object value = parser.value(); parser.skip();
        if (parser.pos != text.length()) throw new IllegalArgumentException("Trailing JSON content at " + parser.pos);
        return value;
    }
    private Object value() {
        skip(); if (pos >= text.length()) throw new IllegalArgumentException("Unexpected end of JSON");
        char c = text.charAt(pos);
        if (c == '{') return object();
        if (c == '[') return array();
        if (c == '"') return string();
        if (text.startsWith("true", pos)) { pos += 4; return Boolean.TRUE; }
        if (text.startsWith("false", pos)) { pos += 5; return Boolean.FALSE; }
        if (text.startsWith("null", pos)) { pos += 4; return null; }
        int start = pos; while (pos < text.length() && "+-0123456789.eE".indexOf(text.charAt(pos)) >= 0) pos++;
        if (start == pos) throw new IllegalArgumentException("Unexpected character '" + c + "' at " + pos);
        return text.substring(start, pos);
    }
    private Map<String, Object> object() {
        Map<String, Object> map = new LinkedHashMap<>(); pos++; skip();
        if (text.charAt(pos) == '}') { pos++; return map; }
        while (true) {
            skip(); String key = string(); skip(); expect(':'); map.put(key, value()); skip();
            char c = text.charAt(pos++); if (c == '}') return map; if (c != ',') throw new IllegalArgumentException("Expected , or } at " + pos);
        }
    }
    private List<Object> array() {
        List<Object> list = new ArrayList<>(); pos++; skip();
        if (text.charAt(pos) == ']') { pos++; return list; }
        while (true) {
            list.add(value()); skip();
            char c = text.charAt(pos++); if (c == ']') return list; if (c != ',') throw new IllegalArgumentException("Expected , or ] at " + pos);
        }
    }
    private String string() {
        expect('"'); StringBuilder out = new StringBuilder();
        while (pos < text.length()) {
            char c = text.charAt(pos++);
            if (c == '"') return out.toString();
            if (c == '\\') {
                char e = text.charAt(pos++);
                switch (e) {
                    case 'n': out.append('\n'); break; case 't': out.append('\t'); break; case 'r': out.append('\r'); break;
                    case 'b': out.append('\b'); break; case 'f': out.append('\f'); break;
                    case 'u': out.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16)); pos += 4; break;
                    default: out.append(e);
                }
            } else out.append(c);
        }
        throw new IllegalArgumentException("Unterminated JSON string");
    }
    private void expect(char c) { if (pos >= text.length() || text.charAt(pos) != c) throw new IllegalArgumentException("Expected '" + c + "' at " + pos); pos++; }
    private void skip() { while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) pos++; }
}
