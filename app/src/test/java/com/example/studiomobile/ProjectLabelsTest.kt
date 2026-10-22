package com.example.studiomobile

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProjectLabelsTest {
    private fun project(manifestLabel: String?, strings: String?, name: String = "My Project"): File {
        val root = File(Files.createTempDirectory("labels").toFile(), name).also { it.mkdirs() }
        val main = File(root, "src/main").also { it.mkdirs() }
        val label = if (manifestLabel == null) "" else " android:label=\"$manifestLabel\""
        File(main, "AndroidManifest.xml").writeText(
            "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\" package=\"com.example.demo\">" +
                "<application$label/></manifest>")
        if (strings != null) {
            File(main, "res/values").mkdirs()
            File(main, "res/values/strings.xml").writeText("<resources>$strings</resources>")
        }
        return root
    }

    @Test fun plainTextInTheManifestIsUsedDirectly() {
        assertEquals("Halo App", ProjectLabels.appName(project("Halo App", null)))
    }

    @Test fun stringReferenceCanHaveAnyName() {
        assertEquals("Judul", ProjectLabels.appName(project("@string/title_app", "<string name=\"title_app\">Judul</string>")))
    }

    @Test fun referenceChainIsFollowed() {
        val strings = "<string name=\"a\">@string/b</string><string name=\"b\">Akhir</string>"
        assertEquals("Akhir", ProjectLabels.appName(project("@string/a", strings)))
    }

    @Test fun withoutManifestLabelAppNameIsTried() {
        assertEquals("Dari strings", ProjectLabels.appName(project(null, "<string name=\"app_name\">Dari strings</string>")))
    }

    @Test fun nothingFoundMeansNull() {
        assertNull(ProjectLabels.appName(project(null, null)))
    }

    @Test fun escapesAreRemoved() {
        assertEquals("It's me", ProjectLabels.appName(project("@string/app_name", "<string name=\"app_name\">It\\'s me</string>")))
    }

    @Test fun projectNameThenAppNameThenPackage() {
        val full = ProjectLabels.resolve("Proj", "App", "com.x.y")
        assertEquals("Proj", full.title); assertEquals("App", full.subtitle)
        val noApp = ProjectLabels.resolve("Proj", null, "com.x.y")
        assertEquals("Proj", noApp.title); assertEquals("com.x.y", noApp.subtitle)
        val onlyProject = ProjectLabels.resolve("Proj", null, null)
        assertEquals("Proj", onlyProject.title); assertEquals("", onlyProject.subtitle)
    }

    @Test fun noProjectNameUsesAppNameAndPackage() {
        val line = ProjectLabels.resolve("", "App", "com.x.y")
        assertEquals("App", line.title); assertEquals("com.x.y", line.subtitle)
    }

    @Test fun sameTextIsNotShownTwice() {
        assertEquals("", ProjectLabels.resolve("Same", "Same", null).subtitle)
    }

    @Test fun describeReadsTheDisk() {
        val line = ProjectLabels.describe(project("@string/app_name", "<string name=\"app_name\">Studio</string>", "Folder"))
        assertEquals("Folder", line.title); assertEquals("Studio", line.subtitle)
    }
}
