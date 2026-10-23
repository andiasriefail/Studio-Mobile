package com.example.studiomobile.github

object GithubLogText {
    private val stamp = Regex("^\\uFEFF?\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?Z ?")
    private val ansi = Regex("\u001B\\[[0-9;]*[A-Za-z]")

    @JvmStatic
    fun line(raw: String): String? {
        val text = ansi.replace(stamp.replace(raw, ""), "")
        if (text.startsWith("##[endgroup]") || text.startsWith("##[debug]")) return null
        if (text.startsWith("##[group]")) return "▶ " + text.removePrefix("##[group]")
        if (text.startsWith("##[error]")) return "✕ " + text.removePrefix("##[error]")
        if (text.startsWith("##[warning]")) return "! " + text.removePrefix("##[warning]")
        if (text.startsWith("##[command]")) return "\$ " + text.removePrefix("##[command]")
        return text
    }
}
