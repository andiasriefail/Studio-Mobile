package com.example.studiomobile.github

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GithubTextTest {
    @Test
    fun logLinesLoseTimestampsAndMarkers() {
        assertEquals("hello", GithubLogText.line("2026-10-24T08:00:00.1234567Z hello"))
        assertEquals("▶ Run tests", GithubLogText.line("2026-10-24T08:00:00.1234567Z ##[group]Run tests"))
        assertEquals("✕ failed", GithubLogText.line("2026-10-24T08:00:00Z ##[error]failed"))
        assertNull(GithubLogText.line("2026-10-24T08:00:00Z ##[endgroup]"))
        assertEquals("plain", GithubLogText.line("\u001B[32mplain\u001B[0m"))
    }

    @Test
    fun sizesAndDurations() {
        assertEquals("512 B", GithubFormat.bytes(512))
        assertEquals("1.5 KB", GithubFormat.bytes(1536))
        assertEquals("2.0 MB", GithubFormat.bytes(2L * 1024 * 1024))
        assertEquals("00:05", GithubFormat.duration(5000))
        assertEquals("1:02:03", GithubFormat.duration(3723000))
    }

    @Test
    fun safeNames() {
        assertEquals("Studio-Mobile-run7-apk_debug", GithubFormat.safeName("Studio-Mobile-run7-apk debug"))
    }
}
