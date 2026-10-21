package com.example.studiomobile.build

import java.io.File

/**
 * Decides whether a project really needs Gradle. Everything else is built by the local pipeline
 * (ECJ + D8 + aapt2 inside the app), which needs no JDK, no Gradle and no plugin download.
 *
 * [reasonToUseGradle] returns the first reason found, or null when the local pipeline is enough.
 * Detection is static and best-effort; when something cannot be read it errs towards Gradle.
 */
object GradleNeedDetector {
    /** Plugins the local pipeline understands itself. Any other plugin id needs Gradle. */
    private val LOCAL_PLUGINS = setOf(
        "com.android.application", "com.android.library",
        "org.jetbrains.kotlin.android", "kotlin-android", "android",
        "java", "java-library", "org.jetbrains.kotlin.jvm"
    )

    private val PLUGIN_ID = Regex("""\bid\s*\(?\s*["']([A-Za-z0-9_.\-]+)["']""")
    private val APPLY_PLUGIN = Regex("""apply\s+plugin\s*:\s*["']([A-Za-z0-9_.\-]+)["']""")
    private val KOTLIN_SHORT = Regex("""\bkotlin\s*\(\s*["']([A-Za-z0-9_.\-]+)["']\s*\)""")
    private val ALIAS = Regex("""alias\s*\(\s*libs\.plugins\.([A-Za-z0-9_.]+?)\s*\)""")
    private val APPLY_FROM = Regex("""apply\s*(\(\s*)?from\s*[:=]""")
    private val CUSTOM_TASK = Regex("""(?m)^\s*(tasks\.register\s*[(<]|tasks\.create\s*\(|task\s+[A-Za-z_(])""")
    private val INCLUDE_BUILD = Regex("""\bincludeBuild\s*\(""")
    private val TOML_PLUGIN_TABLE = Regex("""^\s*([A-Za-z0-9_.\-]+)\s*=\s*\{[^}]*\bid\s*=\s*"([^"]+)"""")
    private val TOML_PLUGIN_STRING = Regex("""^\s*([A-Za-z0-9_.\-]+)\s*=\s*"([^":"]+):[^"]*"""")
    private val BLOCK_COMMENT = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)
    private val LINE_COMMENT = Regex("""(?m)(^|[^:])//.*$""")

    @JvmStatic
    fun reasonToUseGradle(project: File): String? {
        if (File(project, "gradlew").isFile || File(project, "gradle/wrapper/gradle-wrapper.properties").isFile)
            return "the project has its own Gradle wrapper (an Android Studio project)"
        for (name in arrayOf("buildSrc", "build-logic"))
            if (File(project, name).isDirectory) return "the project has a '$name' folder"

        val catalogPlugins = readCatalogPlugins(File(project, "gradle/libs.versions.toml"))
        for (script in scripts(project)) {
            val text = stripComments(script.readText())
            if (APPLY_FROM.containsMatchIn(text)) return "${script.name} uses 'apply from'"
            if (INCLUDE_BUILD.containsMatchIn(text)) return "${script.name} uses includeBuild"
            if (CUSTOM_TASK.containsMatchIn(text)) return "${script.name} defines a custom Gradle task"
            for (id in pluginIds(text)) if (id !in LOCAL_PLUGINS) return "plugin '$id' needs Gradle"
            for (match in ALIAS.findAll(text)) {
                val key = normalize(match.groupValues[1])
                val id = catalogPlugins[key] ?: return "plugin alias 'libs.plugins.${match.groupValues[1]}' could not be read"
                if (id !in LOCAL_PLUGINS) return "plugin '$id' needs Gradle"
            }
        }
        return null
    }

    private fun pluginIds(text: String): List<String> =
        PLUGIN_ID.findAll(text).map { it.groupValues[1] }.toList() +
            APPLY_PLUGIN.findAll(text).map { it.groupValues[1] }.toList() +
            KOTLIN_SHORT.findAll(text).map { "org.jetbrains.kotlin." + it.groupValues[1] }.toList()

    /** Root and settings scripts plus the build script of every direct sub-folder (the modules). */
    private fun scripts(project: File): List<File> {
        val found = ArrayList<File>()
        val names = arrayOf("build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts")
        for (name in names) File(project, name).takeIf { it.isFile }?.let { found.add(it) }
        project.listFiles()?.filter { it.isDirectory }?.forEach { module ->
            for (name in arrayOf("build.gradle", "build.gradle.kts")) File(module, name).takeIf { it.isFile }?.let { found.add(it) }
        }
        return found.filter { it.length() <= 1024 * 1024 }
    }

    private fun readCatalogPlugins(toml: File): Map<String, String> {
        if (!toml.isFile) return emptyMap()
        val plugins = HashMap<String, String>()
        var inPlugins = false
        for (line in toml.readLines()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("[")) { inPlugins = trimmed == "[plugins]"; continue }
            if (!inPlugins || trimmed.startsWith("#")) continue
            val match = TOML_PLUGIN_TABLE.find(line) ?: TOML_PLUGIN_STRING.find(line) ?: continue
            plugins[normalize(match.groupValues[1])] = match.groupValues[2]
        }
        return plugins
    }

    private fun normalize(key: String) = key.replace('-', '.').replace('_', '.')

    private fun stripComments(text: String) =
        text.replace(BLOCK_COMMENT, "").replace(LINE_COMMENT) { it.groupValues[1] }
}
