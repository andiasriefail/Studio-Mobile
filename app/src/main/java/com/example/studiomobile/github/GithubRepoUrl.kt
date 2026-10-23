package com.example.studiomobile.github

class GithubRepoRef(val owner: String, val repo: String, val refHint: String)

object GithubRepoUrl {
    private val ownerPattern = Regex("^[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})$")
    private val repoPattern = Regex("^[A-Za-z0-9_.-]{1,100}$")
    private val sshPattern = Regex("^git@github\\.com:([^/\\s]+)/([^/\\s]+?)(?:\\.git)?/?$", RegexOption.IGNORE_CASE)
    private val schemePattern = Regex("^[A-Za-z][A-Za-z0-9+.-]*://")

    @JvmStatic
    fun parse(input: String): GithubRepoRef? {
        var text = input.trim().trim('"', '\'', '<', '>').trim()
        if (text.isEmpty()) return null
        val ssh = sshPattern.matchEntire(text)
        if (ssh != null) return build(ssh.groupValues[1], ssh.groupValues[2], "")
        text = schemePattern.replace(text, "")
        val cut = text.indexOfAny(charArrayOf('?', '#'))
        if (cut >= 0) text = text.substring(0, cut)
        val segments = text.split('/').filter { it.isNotEmpty() }.toMutableList()
        if (segments.isEmpty()) return null
        val first = segments[0].lowercase()
        if (first == "github.com" || first == "www.github.com") {
            segments.removeAt(0)
        } else if (segments[0].contains('.')) {
            return null
        }
        if (segments.size < 2) return null
        var hint = ""
        if (segments.size > 3 && segments[2] == "tree") hint = segments.drop(3).joinToString("/")
        return build(segments[0], segments[1], hint)
    }

    private fun build(owner: String, rawRepo: String, hint: String): GithubRepoRef? {
        val repo = rawRepo.removeSuffix(".git")
        if (!ownerPattern.matches(owner)) return null
        if (!repoPattern.matches(repo) || repo == "." || repo == "..") return null
        return GithubRepoRef(owner, repo, hint)
    }
}
