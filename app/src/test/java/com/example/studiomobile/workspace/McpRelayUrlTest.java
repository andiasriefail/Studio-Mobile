package com.example.studiomobile.workspace;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class McpRelayUrlTest {
    @Test public void keepsPlainHttpsAddress() {
        assertEquals("https://relay.example.com/s/abc", McpRelayUrl.normalize("https://relay.example.com/s/abc"));
    }

    @Test public void stripsTrailingSlashAndMcpSuffix() {
        assertEquals("https://relay.example.com/s/abc", McpRelayUrl.normalize("  https://relay.example.com/s/abc/mcp/ "));
    }

    @Test public void addsHttpsWhenSchemeIsMissing() {
        assertEquals("https://relay.example.com/s/abc", McpRelayUrl.normalize("relay.example.com/s/abc"));
    }

    @Test public void keepsPort() {
        assertEquals("https://relay.example.com:8443/s/abc", McpRelayUrl.normalize("https://relay.example.com:8443/s/abc//"));
    }

    @Test public void rejectsCleartextAndOddInput() {
        assertNull(McpRelayUrl.normalize("http://relay.example.com/s/abc"));
        assertNull(McpRelayUrl.normalize("ftp://relay.example.com/s/abc"));
        assertNull(McpRelayUrl.normalize("https://relay.example.com/s/abc?x=1"));
        assertNull(McpRelayUrl.normalize("https://user:pass@relay.example.com/s/abc"));
        assertNull(McpRelayUrl.normalize("https://relay.example.com/s/a b"));
        assertNull(McpRelayUrl.normalize(""));
        assertNull(McpRelayUrl.normalize(null));
    }

    @Test public void buildsAiEndpoint() {
        assertEquals("https://relay.example.com/s/abc/mcp", McpRelayUrl.aiEndpoint("https://relay.example.com/s/abc"));
    }
}
