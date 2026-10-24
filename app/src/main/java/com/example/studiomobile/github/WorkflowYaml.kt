package com.example.studiomobile.github

class WorkflowInput(
    val key: String,
    val type: String,
    val description: String,
    val required: Boolean,
    val defaultValue: String,
    val options: List<String>
)

class WorkflowSpec(val manual: Boolean, val inputs: List<WorkflowInput>, val state: Int = OK) {
    companion object {
        const val OK = 0
        const val MISSING = 1
        const val UNREADABLE = 2

        val NONE = WorkflowSpec(false, emptyList(), OK)

        fun missing(): WorkflowSpec = WorkflowSpec(false, emptyList(), MISSING)

        fun unreadable(): WorkflowSpec = WorkflowSpec(true, emptyList(), UNREADABLE)
    }
}

object WorkflowYaml {
    private class Line(val indent: Int, val text: String)

    @JvmStatic
    fun parse(text: String): WorkflowSpec {
        val on = onSection(text) ?: return WorkflowSpec.NONE
        var manual = false
        val inputs = ArrayList<WorkflowInput>()
        when (on) {
            is String -> manual = on.trim() == "workflow_dispatch"
            is List<*> -> manual = on.any { it == "workflow_dispatch" }
            is Map<*, *> -> {
                val tree = asMap(on)
                manual = tree.containsKey("workflow_dispatch")
                val dispatch = tree["workflow_dispatch"]
                if (dispatch is Map<*, *>) {
                    val declared = asMap(dispatch)["inputs"]
                    if (declared is Map<*, *>) {
                        for ((key, value) in asMap(declared)) {
                            inputs.add(input(key, if (value is Map<*, *>) asMap(value) else emptyMap()))
                        }
                    }
                }
            }
        }
        return WorkflowSpec(manual, inputs)
    }

    @Suppress("UNCHECKED_CAST")
    private fun asMap(value: Map<*, *>): Map<String, Any?> = value as Map<String, Any?>

    private fun input(key: String, data: Map<String, Any?>): WorkflowInput {
        val type = (data["type"] as? String)?.takeIf { it.isNotEmpty() } ?: "string"
        val options = (data["options"] as? List<*>)?.map { it.toString() } ?: emptyList()
        var fallback = data["default"] as? String ?: ""
        if (type == "boolean") fallback = if (fallback.equals("true", true)) "true" else "false"
        if (type == "choice" && fallback.isEmpty() && options.isNotEmpty()) fallback = options[0]
        val required = (data["required"] as? String)?.equals("true", true) == true
        return WorkflowInput(key, type, data["description"] as? String ?: "", required, fallback, options)
    }

    private fun onSection(text: String): Any? {
        val lines = prep(text)
        var start = -1
        for (i in lines.indices) {
            val line = lines[i]
            if (line.indent != 0) continue
            val colon = findColon(line.text)
            if (colon < 0) continue
            val key = unquote(line.text.substring(0, colon).trim())
            if (key == "on" || key == "true") {
                start = i
                break
            }
        }
        if (start < 0) return null
        var end = lines.size
        for (j in start + 1 until lines.size) {
            if (lines[j].indent == 0) {
                end = j
                break
            }
        }
        val map = parseMap(lines.subList(start, end), intArrayOf(0), 0)
        return map.values.firstOrNull()
    }

    private fun prep(text: String): List<Line> {
        val lines = ArrayList<Line>()
        for (original in text.replace("\r", "").split('\n')) {
            val raw = original.replace("\t", "  ")
            val stripped = raw.trim()
            if (stripped.isEmpty() || stripped.startsWith("#")) {
                lines.add(Line(-1, ""))
                continue
            }
            lines.add(Line(raw.length - raw.trimStart(' ').length, stripped))
        }
        return lines
    }

    private fun isItem(text: String): Boolean = text == "-" || text.startsWith("- ")

    private fun skipBlank(lines: List<Line>, pos: IntArray) {
        while (pos[0] < lines.size && lines[pos[0]].indent == -1) pos[0]++
    }

    private fun parseBlock(lines: List<Line>, pos: IntArray, indent: Int): Any? {
        skipBlank(lines, pos)
        if (pos[0] >= lines.size) return null
        return if (isItem(lines[pos[0]].text)) parseSeq(lines, pos, indent) else parseMap(lines, pos, indent)
    }

    private fun parseSeq(lines: List<Line>, pos: IntArray, indent: Int): List<Any?> {
        val out = ArrayList<Any?>()
        while (true) {
            skipBlank(lines, pos)
            if (pos[0] >= lines.size) break
            val line = lines[pos[0]]
            if (line.indent != indent || !isItem(line.text)) break
            pos[0]++
            val rest = stripComment(line.text.substring(1).trim())
            if (rest.isEmpty()) {
                skipBlank(lines, pos)
                if (pos[0] < lines.size && lines[pos[0]].indent > indent) {
                    out.add(parseBlock(lines, pos, lines[pos[0]].indent))
                } else {
                    out.add("")
                }
            } else {
                out.add(scalar(rest))
            }
        }
        return out
    }

    private fun parseMap(lines: List<Line>, pos: IntArray, indent: Int): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        while (true) {
            skipBlank(lines, pos)
            if (pos[0] >= lines.size) break
            val line = lines[pos[0]]
            if (line.indent != indent || isItem(line.text)) break
            val colon = findColon(line.text)
            if (colon < 0) {
                pos[0]++
                continue
            }
            val key = unquote(line.text.substring(0, colon).trim())
            val value = stripComment(line.text.substring(colon + 1).trim())
            pos[0]++
            if (value.isEmpty()) {
                skipBlank(lines, pos)
                if (pos[0] < lines.size && lines[pos[0]].indent > indent) {
                    out[key] = parseBlock(lines, pos, lines[pos[0]].indent)
                } else if (pos[0] < lines.size && lines[pos[0]].indent == indent && isItem(lines[pos[0]].text)) {
                    out[key] = parseSeq(lines, pos, indent)
                } else {
                    out[key] = ""
                }
            } else if (value[0] == '|' || value[0] == '>') {
                val buffer = ArrayList<String>()
                while (pos[0] < lines.size && (lines[pos[0]].indent == -1 || lines[pos[0]].indent > indent)) {
                    if (lines[pos[0]].indent != -1) buffer.add(lines[pos[0]].text)
                    pos[0]++
                }
                out[key] = if (value[0] == '>') buffer.joinToString(" ") else buffer.joinToString("\n")
            } else {
                out[key] = scalar(value)
            }
        }
        return out
    }

    private fun scalar(value: String): Any {
        val v = value.trim()
        if (v.startsWith("[")) {
            val end = v.lastIndexOf(']')
            val inner = if (end > 0) v.substring(1, end) else v.substring(1)
            return splitTop(inner, ',').filter { it.isNotBlank() }.map { unquote(it) }
        }
        if (v.startsWith("{")) return LinkedHashMap<String, Any?>()
        return unquote(v)
    }

    private fun unquote(value: String): String {
        val s = value.trim()
        if (s.length >= 2 && s.first() == '"' && s.last() == '"') {
            val body = s.substring(1, s.length - 1)
            val out = StringBuilder()
            var i = 0
            while (i < body.length) {
                val c = body[i]
                if (c == '\\' && i + 1 < body.length) {
                    i++
                    when (val next = body[i]) {
                        'n' -> out.append('\n')
                        't' -> out.append('\t')
                        else -> out.append(next)
                    }
                } else {
                    out.append(c)
                }
                i++
            }
            return out.toString()
        }
        if (s.length >= 2 && s.first() == '\'' && s.last() == '\'') {
            return s.substring(1, s.length - 1).replace("''", "'")
        }
        return s
    }

    private fun stripComment(s: String): String {
        val out = StringBuilder()
        var quote: Char? = null
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (quote != null) {
                out.append(c)
                if (c == '\\' && quote == '"' && i + 1 < s.length) {
                    out.append(s[i + 1])
                    i++
                } else if (c == quote) {
                    quote = null
                }
            } else if ((c == '"' || c == '\'') && (i == 0 || " [{,:-".indexOf(s[i - 1]) >= 0)) {
                quote = c
                out.append(c)
            } else if (c == '#' && (i == 0 || s[i - 1] == ' ')) {
                break
            } else {
                out.append(c)
            }
            i++
        }
        return out.toString().trimEnd()
    }

    private fun splitTop(s: String, separator: Char): List<String> {
        val parts = ArrayList<String>()
        val current = StringBuilder()
        var quote: Char? = null
        var depth = 0
        for (c in s) {
            if (quote != null) {
                current.append(c)
                if (c == quote) quote = null
            } else if (c == '"' || c == '\'') {
                quote = c
                current.append(c)
            } else if (c == '[' || c == '{') {
                depth++
                current.append(c)
            } else if (c == ']' || c == '}') {
                depth--
                current.append(c)
            } else if (c == separator && depth == 0) {
                parts.add(current.toString())
                current.setLength(0)
            } else {
                current.append(c)
            }
        }
        parts.add(current.toString())
        return parts
    }

    private fun findColon(s: String): Int {
        var quote: Char? = null
        for (i in s.indices) {
            val c = s[i]
            if (quote != null) {
                if (c == quote) quote = null
            } else if ((c == '"' || c == '\'') && i == 0) {
                quote = c
            } else if (c == ':' && (i + 1 == s.length || s[i + 1] == ' ')) {
                return i
            }
        }
        return -1
    }
}
