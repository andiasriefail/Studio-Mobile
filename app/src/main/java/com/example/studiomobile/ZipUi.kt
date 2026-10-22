package com.example.studiomobile

import android.net.Uri
import android.provider.DocumentsContract
import android.text.InputType
import android.view.View
import android.widget.EditText
import androidx.activity.result.contract.ActivityResultContracts
import androidx.documentfile.provider.DocumentFile
import com.example.studiomobile.workspace.ProjectZipImporter
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Everything about ZIP files and folders on the Home screen: import a ZIP (with password), import a folder, export a project as ZIP. */
class ZipUi(private val host: MainActivity) {
    private val pickZip = host.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) importZip(uri, null, false)
    }

    /** The project waiting for the user to choose where its ZIP is saved. */
    private var exportProject: File? = null

    /** System "save as" picker: the user chooses the folder and file name, so the ZIP can go anywhere. */
    private val pickZipExport = host.registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri: Uri? ->
        val project = exportProject
        exportProject = null
        if (uri != null && project != null) exportProjectZip(project, uri)
    }

    private val pickFolder = host.registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) importFolder(uri)
    }

    fun chooseZip() { pickZip.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) }

    fun chooseFolder() { pickFolder.launch(null) }

    /** Project menu > Export ZIP: first the "save as" picker, then the file is written. */
    fun exportZip(project: File) {
        exportProject = project
        pickZipExport.launch(project.name + ".zip")
    }

    /** Asks for the ZIP password, then imports again. [wrong] shows that the previous password did not work. */
    private fun askPassword(zipUri: Uri, wrong: Boolean) {
        val view = host.layoutInflater.inflate(R.layout.dialog_input, null)
        val input = view.findViewById<EditText>(R.id.dialog_input)
        input.hint = LanguageUi.ZIP_PASSWORD_HINT
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        input.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        MaterialAlertDialogBuilder(host).setTitle(LanguageUi.ZIP_PASSWORD_TITLE)
            .setMessage(if (wrong) LanguageUi.ZIP_PASSWORD_WRONG else LanguageUi.ZIP_PASSWORD_MESSAGE)
            .setView(view)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(LanguageUi.ZIP_PASSWORD_IMPORT) { _, _ ->
                val password = CharArray(input.length())
                input.text.getChars(0, password.size, password, 0)   // the password is not trimmed and never stored as a String
                importZip(zipUri, password, true)
            }.show()
    }

    private fun importZip(zipUri: Uri, password: CharArray?, passwordGiven: Boolean) {
        val zipName = host.displayName(zipUri)
        val base = if (zipName.lowercase(Locale.ROOT).endsWith(".zip")) zipName.substring(0, zipName.length - 4) else zipName
        val projectName = host.safeName(base, "project")
        if (!passwordGiven) host.toast(LanguageUi.IMPORTING)
        host.io.execute {
            val dest = host.uniqueFile(host.projectsDir(), projectName)
            try {
                if (!dest.mkdirs()) throw IOException("Could not create " + dest.name)
                val opener = ProjectZipImporter.SourceOpener {
                    host.contentResolver.openInputStream(zipUri) ?: throw IOException("Could not open the ZIP.")
                }
                val sink = object : ProjectZipImporter.Sink {
                    override fun ensureDirectory(relativePath: String) {
                        val dir = File(dest, relativePath)
                        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Could not create folder: $relativePath")
                    }

                    override fun openFile(relativePath: String): OutputStream {
                        val file = File(dest, relativePath)
                        val parent = file.parentFile
                        if (parent != null && !parent.isDirectory && !parent.mkdirs()) throw IOException("Could not create folder for: $relativePath")
                        return FileOutputStream(file)
                    }
                }
                ProjectZipImporter.importZip(opener, sink, ProjectZipImporter.ProgressListener { _, _ -> }, password)
                host.runOnUiThread { host.toast(LanguageUi.PROJECT_IMPORTED); host.refreshProjects() }
            } catch (problem: ProjectZipImporter.PasswordRequiredException) {
                host.deleteRecursively(dest)
                host.runOnUiThread { askPassword(zipUri, false) }
            } catch (problem: ProjectZipImporter.WrongPasswordException) {
                host.deleteRecursively(dest)
                host.runOnUiThread { askPassword(zipUri, true) }
            } catch (e: Exception) {
                host.deleteRecursively(dest)
                host.runOnUiThread { host.toast(LanguageUi.IMPORT_FAILED + e.message) }
            } finally {
                if (password != null) password.fill('\u0000')
            }
        }
    }

    /** Writes the project into the ZIP file the user picked. Build output and IDE caches are left out; a half-written file is removed on failure. */
    private fun exportProjectZip(project: File, target: Uri) {
        host.toast(LanguageUi.EXPORTING_ZIP)
        host.io.execute {
            try {
                (host.contentResolver.openOutputStream(target, "w") ?: throw IOException("Could not open the file you chose.")).use { raw ->
                    ZipOutputStream(BufferedOutputStream(raw)).use { zip -> addToZip(zip, project, "") }
                }
                host.runOnUiThread { host.toast(LanguageUi.ZIP_EXPORTED) }
            } catch (e: Exception) {
                try { DocumentsContract.deleteDocument(host.contentResolver, target) } catch (ignored: Exception) { }
                host.runOnUiThread { host.toast(LanguageUi.ZIP_EXPORT_FAILED + e.message) }
            }
        }
    }

    private fun addToZip(zip: ZipOutputStream, directory: File, prefix: String) {
        val children = directory.listFiles() ?: return
        children.sort()
        for (child in children) {
            val name = child.name
            if ((skipWhenCopying(name) && name != "outputs") || name == ".git") continue   // build output, caches and git history stay out of the ZIP
            if (Files.isSymbolicLink(child.toPath())) continue
            val path = prefix + name
            if (child.isDirectory) {
                zip.putNextEntry(ZipEntry("$path/")); zip.closeEntry()
                addToZip(zip, child, "$path/")
            } else if (child.isFile) {
                zip.putNextEntry(ZipEntry(path))
                FileInputStream(child).use { input -> host.copyStream(input, zip) }
                zip.closeEntry()
            }
        }
    }

    private fun importFolder(treeUri: Uri) {
        val tree = DocumentFile.fromTreeUri(host, treeUri)
        if (tree == null || !tree.isDirectory) { host.toast(LanguageUi.IMPORT_FAILED + "folder not available"); return }
        val projectName = host.safeName(tree.name ?: "project", "project")
        host.toast(LanguageUi.IMPORTING)
        host.io.execute {
            val dest = host.uniqueFile(host.projectsDir(), projectName)
            try {
                copyDocumentTree(tree, dest)
                host.runOnUiThread { host.toast(LanguageUi.PROJECT_IMPORTED); host.refreshProjects() }
            } catch (e: Exception) {
                host.deleteRecursively(dest)
                host.runOnUiThread { host.toast(LanguageUi.IMPORT_FAILED + e.message) }
            }
        }
    }

    private fun skipWhenCopying(name: String): Boolean =
        name == ".git" || name == ".gradle" || name == "build" || name == "outputs" || name == ".idea"

    private fun copyDocumentTree(source: DocumentFile, destination: File) {
        if (source.isDirectory) {
            if (!destination.exists() && !destination.mkdirs()) throw IOException("Could not create directory: " + destination.name)
            for (child in source.listFiles()) {
                val childName = child.name
                if (childName == null || skipWhenCopying(childName)) continue
                val target = File(destination, childName)
                if (!target.canonicalPath.startsWith(destination.canonicalPath + File.separator)) throw IOException("Unsafe file name in folder.")
                copyDocumentTree(child, target)
            }
            return
        }
        val input: InputStream = host.contentResolver.openInputStream(source.uri) ?: throw IOException("Could not read " + source.name)
        input.use { `in` -> FileOutputStream(destination).use { output -> host.copyStream(`in`, output) } }
    }
}
