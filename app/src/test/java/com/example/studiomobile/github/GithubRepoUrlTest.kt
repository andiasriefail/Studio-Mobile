package com.example.studiomobile.github

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class GithubRepoUrlTest {
    private fun ok(input: String): GithubRepoRef {
        val parsed = GithubRepoUrl.parse(input)
        assertNotNull(input, parsed)
        return parsed!!
    }

    @Test
    fun fullAddress() {
        val ref = ok("https://github.com/andiasriefail/Studio-Mobile")
        assertEquals("andiasriefail", ref.owner)
        assertEquals("Studio-Mobile", ref.repo)
        assertEquals("", ref.refHint)
    }

    @Test
    fun withoutSchemeAndWithGitSuffix() {
        val ref = ok("github.com/a/b.git")
        assertEquals("a", ref.owner)
        assertEquals("b", ref.repo)
    }

    @Test
    fun sshAndShorthand() {
        assertEquals("b", ok("git@github.com:a/b.git").repo)
        assertEquals("b", ok("a/b").repo)
    }

    @Test
    fun treeAddressGivesBranchHint() {
        assertEquals("feature/x", ok("https://github.com/a/b/tree/feature/x").refHint)
    }

    @Test
    fun trailingSlashQueryAndSpaces() {
        assertEquals("b", ok("  https://github.com/a/b/?tab=readme  ").repo)
    }

    @Test
    fun rejectsOtherHostsAndIncompleteAddresses() {
        assertNull(GithubRepoUrl.parse("https://gitlab.com/a/b"))
        assertNull(GithubRepoUrl.parse("https://github.com/a"))
        assertNull(GithubRepoUrl.parse(""))
        assertNull(GithubRepoUrl.parse("https://github.com/-bad/repo"))
    }
}
