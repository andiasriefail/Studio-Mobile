package com.example.studiomobile.github

import java.util.Locale

object GithubFormat {
    @JvmStatic
    fun bytes(value: Long): String {
        if (value < 1024) return "$value B"
        val units = arrayOf("KB", "MB", "GB")
        var size = value.toDouble()
        var index = -1
        while (size >= 1024 && index < units.size - 1) {
            size /= 1024
            index++
        }
        return String.format(Locale.US, "%.1f %s", size, units[index])
    }

    @JvmStatic
    fun duration(millis: Long): String {
        val total = maxOf(0L, millis / 1000)
        val hours = total / 3600
        val minutes = total % 3600 / 60
        val seconds = total % 60
        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }
    }

    @JvmStatic
    fun safeName(value: String): String = value.replace(Regex("[^A-Za-z0-9._-]"), "_")
}
