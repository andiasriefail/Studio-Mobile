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

    private val problem = Regex("(?i)(error:|\\berror\\b.*\\b(failed|cannot|could not|not found)|FAILURE:|FAILED|what went wrong|exception|fatal:)")

    @JvmStatic
    fun errors(text: String, limit: Int): String {
        if (text.isEmpty()) return ""
        val hits = ArrayList<String>()
        for (line in text.split('\n')) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            if (trimmed.startsWith("✕") || problem.containsMatchIn(trimmed)) hits.add(trimmed)
        }
        val useful = hits.filter { !it.startsWith("✕ Process completed with exit code") }
        val chosen = if (useful.isNotEmpty()) useful else hits
        return chosen.takeLast(limit).joinToString("\n")
    }
}
