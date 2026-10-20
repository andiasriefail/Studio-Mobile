package com.example.studiomobile.workspace;

import java.net.URI;
import java.util.Locale;

/**
 * Turns what the user typed into the one relay address the app talks to.
 *
 * <p>The user owns the relay, so the address is never fixed in the app. Pure Java so it can be unit tested.
 * Accepted: {@code https://relay.example.com/s/SECRET}, the same with a trailing slash, with the
 * {@code /mcp} suffix the AI uses, or without the scheme. Rejected: plain {@code http://}
 * (the app does not allow cleartext), user info, query strings and fragments.
 */
public final class McpRelayUrl {
    private McpRelayUrl() { }

    /** Returns the normalized base address (https, no trailing slash, no /mcp), or null when it cannot be used. */
    public static String normalize(String raw) {
        if (raw == null) return null;
        String text = raw.trim();
        if (text.isEmpty() || text.chars().anyMatch(Character::isWhitespace)) return null;
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.startsWith("http://")) return null;
        if (!lower.startsWith("https://")) {
            if (lower.contains("://")) return null;
            text = "https://" + text;
        }
        URI uri;
        try { uri = new URI(text); } catch (Exception e) { return null; }
        if (uri.getHost() == null || uri.getHost().isEmpty()) return null;
        if (uri.getUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) return null;
        String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        path = stripTrailingSlashes(path);
        if (path.toLowerCase(Locale.ROOT).endsWith("/mcp")) path = stripTrailingSlashes(path.substring(0, path.length() - 4));
        String authority = uri.getRawAuthority();
        return "https://" + authority + path;
    }

    /** The address the user pastes into Claude, ChatGPT or Gemini. */
    public static String aiEndpoint(String base) { return base + "/mcp"; }

    private static String stripTrailingSlashes(String value) {
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == '/') end--;
        return value.substring(0, end);
    }
}
