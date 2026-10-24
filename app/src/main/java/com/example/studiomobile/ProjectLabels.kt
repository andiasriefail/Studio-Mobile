package com.example.studiomobile

import com.example.studiomobile.build.BuildProjectFiles
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * What the Home project list shows for one project: a title and, under it, a second line.
 *
 *  - Title: the project name. If the project has no name, the application name instead.
 *  - Second line: the application name; when that is missing, the package name; when that is missing too, nothing.
 *    (With no project name the title is the application name, so the second line is the package name.)
 *
 * The application name is read the way Android Studio does, not only from strings.xml:
 *  1. `android:label` of `<application>` in AndroidManifest.xml. Plain text is used as it is.
 *  2. `@string/xxx` is looked up in the XML files of `res/values` (the name does not have to be `app_name`).
 *  3. No usable label in the manifest: try the string named `app_name`.
 *  4. Nothing found: empty.
 * Not handled: `resValue` in Gradle and per-language `values-xx` translations.
 */
object ProjectLabels {
    private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    private const val MAX_REFERENCE_DEPTH = 5
    private val STRING_REFERENCE = Regex("""^@(?:\+)?string/([A-Za-z0-9_.]+)$""")
    private val NAMESPACE = Regex("""\bnamespace\s*(?:=\s*)?["']([A-Za-z0-9_.]+)["']""")

    class Line(val title: String, val subtitle: String)

    /** Pure decision, no disk access. Any argument may be null or blank. */
    @JvmStatic
    fun resolve(projectName: String?, appName: String?, packageName: String?): Line {
        val project = projectName.orEmpty().trim()
        val app = appName.orEmpty().trim()
        val pkg = packageName.orEmpty().trim()
        if (project.isNotEmpty()) {
            val second = listOf(app, pkg).firstOrNull { it.isNotEmpty() && it != project }.orEmpty()
            return Line(project, second)
        }
        if (app.isNotEmpty()) return Line(app, if (pkg != app) pkg else "")
        return Line(pkg, "")
    }

    /** Reads the application name and the package of [project] from disk and applies [resolve]. Call it off the UI thread. */
    @JvmStatic
    fun describe(project: File): Line = resolve(project.name, appName(project), packageName(project))

    /** The package / applicationId, or null when the project does not declare one (no fallback to the project name). */
    @JvmStatic
    fun packageName(project: File): String? {
        BuildProjectFiles.readApplicationId(project, null)?.let { return it }
        try {
            com.example.studiomobile.build.ManifestMerger.readPackage(BuildProjectFiles.manifest(project))?.let { return it }
        } catch (ignored: Exception) { }
        return gradleNamespace(project)
    }

    private fun gradleNamespace(project: File): String? {
        val module = BuildProjectFiles.moduleDirectory(project)
        val gradle = File(module, "build.gradle").takeIf { it.isFile } ?: File(module, "build.gradle.kts").takeIf { it.isFile } ?: return null
        return try { NAMESPACE.find(gradle.readText())?.groupValues?.get(1) } catch (ignored: Exception) { null }
    }

    /** The application name, or null when none of the sources has one. */
    @JvmStatic
    fun appName(project: File): String? {
        val strings = try { readStrings(File(BuildProjectFiles.mainDirectory(project), "res/values")) } catch (ignored: Exception) { emptyMap() }
        val label = try { manifestLabel(BuildProjectFiles.manifest(project)) } catch (ignored: Exception) { null }
        if (label != null) {
            val reference = STRING_REFERENCE.matchEntire(label)
            if (reference == null) {
                if (isUsable(label)) return label                                   // plain text written straight into the manifest
            } else {
                lookup(strings, reference.groupValues[1])?.let { return it }       // @string/xxx, whatever xxx is called
            }
        }
        return lookup(strings, "app_name")
    }

    private fun isUsable(text: String): Boolean = text.isNotBlank() && !text.startsWith("@") && !text.contains("\${")

    /** Follows `@string/other` chains (a few levels at most) until real text is found. */
    private fun lookup(strings: Map<String, String>, name: String): String? {
        var value = strings[name] ?: return null
        var depth = 0
        while (depth++ < MAX_REFERENCE_DEPTH) {
            val next = STRING_REFERENCE.matchEntire(value.trim()) ?: break
            value = strings[next.groupValues[1]] ?: return null
        }
        return value.takeIf { isUsable(it) }
    }

    private fun parse(file: File): org.w3c.dom.Document {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        try { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) } catch (ignored: Exception) { }
        return factory.newDocumentBuilder().parse(file)
    }

    private fun manifestLabel(manifest: File): String? {
        if (!manifest.isFile) return null
        val applications = parse(manifest).getElementsByTagName("application")
        if (applications.length == 0) return null
        val value = (applications.item(0) as Element).getAttributeNS(ANDROID_NS, "label").trim()
        return value.ifEmpty { null }
    }

    /** Every `<string>` in the XML files of res/values (any file name, default language only). */
    private fun readStrings(valuesDir: File): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        val files = valuesDir.listFiles { f -> f.isFile && f.name.endsWith(".xml") }?.sortedBy { it.name } ?: return result
        // strings.xml first, so it wins when another file repeats a name (sortedBy is stable)
        for (file in files.sortedBy { if (it.name == "strings.xml") 0 else 1 }) {
            try {
                val nodes = parse(file).getElementsByTagName("string")
                for (i in 0 until nodes.length) {
                    val node = nodes.item(i) as Element
                    val name = node.getAttribute("name")
                    if (name.isNotEmpty() && name !in result) result[name] = unescape(node.textContent)
                }
            } catch (ignored: Exception) { }
        }
        return result
    }

    /** Android string escapes: \' \" \n \\ \uXXXX, and one pair of surrounding quotes. */
    private fun unescape(raw: String): String {
        var text = raw.trim()
        if (text.length >= 2 && text.startsWith("\"") && text.endsWith("\"")) text = text.substring(1, text.length - 1)
        val out = StringBuilder()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\\' && i + 1 < text.length) {
                val n = text[i + 1]
                when {
                    n == 'n' || n == 't' -> { out.append(' '); i += 2 }
                    n == 'u' && i + 6 <= text.length && text.substring(i + 2, i + 6).all { it.isLetterOrDigit() } -> {
                        out.append(text.substring(i + 2, i + 6).toIntOrNull(16)?.toChar() ?: '?'); i += 6
                    }
                    else -> { out.append(n); i += 2 }
                }
            } else { out.append(c); i++ }
        }
        return out.toString().trim()
    }
}
