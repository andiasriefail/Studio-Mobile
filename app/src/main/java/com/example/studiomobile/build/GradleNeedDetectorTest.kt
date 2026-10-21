package com.example.studiomobile.build

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GradleNeedDetectorTest {
    @get:Rule val temp = TemporaryFolder()

    private fun write(path: String, text: String): File {
        val file = File(temp.root, path)
        file.parentFile.mkdirs()
        file.writeText(text)
        return file
    }

    private val catalog = """
        [versions]
        agp = "8.13.1"
        [plugins]
        android-application = { id = "com.android.application", version.ref = "agp" }
        google-services = { id = "com.google.gms.google-services", version = "4.4.2" }
    """.trimIndent()

    @Test fun plainAgpProjectStaysLocal() {   // the Java25Test layout
        write("gradle/libs.versions.toml", catalog)
        write("build.gradle", "plugins {\n    alias(libs.plugins.android.application) apply false\n}\n")
        write("app/build.gradle", "plugins {\n    alias(libs.plugins.android.application)\n}\nandroid { namespace = 'a.b' }\n")
        write("settings.gradle", "include ':app'\n")
        assertNull(GradleNeedDetector.reasonToUseGradle(temp.root))
    }

    @Test fun libraryDeclarationsDoNotForceGradle() {
        write("build.gradle", "plugins { id 'com.android.application' version '9.4.1' apply false }")
        write("app/build.gradle", "plugins { id 'com.android.application' }\ndependencies { implementation 'androidx.core:core:1.13.0' }")
        assertNull(GradleNeedDetector.reasonToUseGradle(temp.root))
    }

    @Test fun androidStudioWrapperForcesGradle() {
        write("gradle/wrapper/gradle-wrapper.properties", "distributionUrl=gradle-8.13-bin.zip")
        assertNotNull(GradleNeedDetector.reasonToUseGradle(temp.root))
    }

    @Test fun extraPluginForcesGradle() {
        write("app/build.gradle", "plugins {\n id 'com.android.application'\n id 'com.google.gms.google-services'\n}")
        assertTrue(GradleNeedDetector.reasonToUseGradle(temp.root)!!.contains("google-services"))
    }

    @Test fun extraPluginThroughCatalogAliasForcesGradle() {
        write("gradle/libs.versions.toml", catalog)
        write("app/build.gradle", "plugins { alias(libs.plugins.google.services) }")
        assertTrue(GradleNeedDetector.reasonToUseGradle(temp.root)!!.contains("google-services"))
    }

    @Test fun unreadableAliasErrsTowardsGradle() {
        write("app/build.gradle", "plugins { alias(libs.plugins.mystery) }")
        assertNotNull(GradleNeedDetector.reasonToUseGradle(temp.root))
    }

    @Test fun kotlinAndroidStaysLocal() {
        write("app/build.gradle.kts", "plugins {\n id(\"com.android.application\")\n kotlin(\"android\")\n}")
        assertNull(GradleNeedDetector.reasonToUseGradle(temp.root))
    }

    @Test fun applyFromBuildSrcIncludeBuildAndTasksForceGradle() {
        write("app/build.gradle", "apply from: 'extra.gradle'")
        assertEquals("build.gradle uses 'apply from'", GradleNeedDetector.reasonToUseGradle(temp.root))
    }

    @Test fun buildSrcForcesGradle() {
        File(temp.root, "buildSrc").mkdirs()
        assertNotNull(GradleNeedDetector.reasonToUseGradle(temp.root))
    }

    @Test fun commentsAreIgnored() {
        write("app/build.gradle", "plugins { id 'com.android.application' }\n// id 'com.google.gms.google-services'\n/* apply from: 'x' */\n")
        assertNull(GradleNeedDetector.reasonToUseGradle(temp.root))
    }
}
