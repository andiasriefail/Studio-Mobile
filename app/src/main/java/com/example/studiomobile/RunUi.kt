package com.example.studiomobile

import android.graphics.Typeface
import android.os.Build
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.example.studiomobile.build.BuildLogger
import com.example.studiomobile.build.DeviceAbi
import com.example.studiomobile.build.dependency.BuildToolCatalog
import com.example.studiomobile.dart.DartProvisioner
import com.example.studiomobile.dart.DartRuntime
import com.example.studiomobile.dart.DartScriptRunner
import com.example.studiomobile.javarun.JavaFileRunner
import com.example.studiomobile.python.PythonProvisioner
import com.example.studiomobile.python.PythonRuntime
import com.example.studiomobile.python.PythonScriptRunner
import com.example.studiomobile.script.JavaScriptRunner
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/** The "Run with ..." actions of the file menu: JavaScript, Dart, Python, Java and Kotlin, with a live output window and a Stop button. */
class RunUi(private val host: MainActivity) {
    private val scriptExecutor = Executors.newSingleThreadExecutor()
    private val javaExecutor = Executors.newSingleThreadExecutor()

    /** One Run at a time: the first keeps the gate, a second request is cancelled on the spot (the runners share compile folders). */
    private val runGate = RunGate()

    /** A scrolling output dialog with a Stop button; the logger may be called from any thread. */
    private inner class RunConsole(title: String, onStop: () -> Unit) {
        val logger: BuildLogger

        init {
            val output = TextView(host)
            output.typeface = Typeface.MONOSPACE
            output.textSize = 12f
            output.setTextIsSelectable(true)
            output.setPadding(host.dp(16f), host.dp(8f), host.dp(16f), host.dp(8f))
            output.setTextColor(host.color(R.color.cosmic_ink))
            val scroll = ScrollView(host)
            scroll.addView(output)
            val dialog = MaterialAlertDialogBuilder(host).setTitle(title).setView(scroll)
                .setPositiveButton(LanguageUi.CLOSE, null).setNegativeButton("Stop", null).create()
            dialog.show()
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener { onStop() }
            logger = object : BuildLogger {
                private fun add(text: String) {
                    host.runOnUiThread {
                        if (output.length() < 200000) output.append(text + "\n")
                        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
                    }
                }
                override fun onInfo(message: String) = add(message)
                override fun onWarning(message: String) = add("warning: $message")
                override fun onError(message: String, cause: Throwable?) = add(message)
            }
        }
    }

    private fun beginRun(): RunGate.Ticket? {
        val ticket = runGate.tryAcquire()
        if (ticket == null) host.toast("Another Run is still active, so this one was cancelled. Press Stop on the first one to run again.")
        return ticket
    }

    /** Opens the output window; if that fails the gate is freed again, otherwise Run would stay locked. */
    private fun openConsole(ticket: RunGate.Ticket, title: String, onStop: () -> Unit): RunConsole {
        try { return RunConsole(title, onStop) }
        catch (error: RuntimeException) { ticket.release(); throw error }
    }

    /** True (and a message) when the open file still has unsaved edits, so Run would use old code. */
    private fun unsaved(file: File): Boolean {
        if (host.dirty && file == host.openedFile) { host.toast("Save the file before running it"); return true }
        return false
    }

    private fun readCatalog(): BuildToolCatalog =
        host.assets.open("build-tools/catalog.json").use { BuildToolCatalog.readVerified(it, BuildToolCatalog.BUNDLED_CATALOG_SHA256) }

    private fun finished(code: Int, timedOutText: String = "\n[timed out or stopped]"): String =
        if (code == -1) timedOutText else "\n[process finished with exit code $code]"

    /** Runs a .js file with the bundled Node.js binary (libnodeexe.so) and shows its output live. */
    fun runJavaScript(file: File) {
        if (unsaved(file)) return
        val ticket = beginRun() ?: return
        val runner = JavaScriptRunner(File(host.applicationInfo.nativeLibraryDir), File(host.filesDir, "node-home"), host.cacheDir)
        val logger = openConsole(ticket, file.name) { runner.cancel() }.logger
        scriptExecutor.execute {
            try {
                logger.onInfo("$ node " + file.name)
                logger.onInfo(finished(runner.run(file, 120, logger)))
            } catch (error: Exception) {
                logger.onError("Could not run JavaScript: " + error.message, error)
            } finally {
                ticket.release()
            }
        }
    }

    /** Runs a .dart file: compiled to kernel by the Dart front end, then executed on the Dart VM (64-bit devices only). */
    fun runDart(file: File) {
        if (unsaved(file)) return
        val ticket = beginRun() ?: return
        val running = AtomicReference<DartScriptRunner?>()
        val installing = AtomicReference<DartProvisioner?>()
        val logger = openConsole(ticket, file.name) { running.get()?.cancel(); installing.get()?.cancel() }.logger
        scriptExecutor.execute {
            try {
                val unsupported = DeviceAbi.require64Bit("Dart", DeviceAbi.supportedAbis())
                if (unsupported != null) { logger.onError(unsupported, null); return@execute }
                val filesDirectory = host.filesDir
                val nativeDirectory = File(host.applicationInfo.nativeLibraryDir)
                if (!DartRuntime.isBundled(nativeDirectory)) {
                    logger.onError("This build of Studio Mobile does not contain the Dart runtime. Run the workflow once with build_dart, then build the APK again.", null)
                    return@execute
                }
                if (!DartRuntime.isSdkInstalled(File(filesDirectory, "dart-sdk"))) {
                    val provisioner = DartProvisioner(filesDirectory, readCatalog(), Build.SUPPORTED_ABIS.toList())
                    installing.set(provisioner)
                    if (provisioner.install(logger) == null) return@execute
                }
                val problems = ArrayList<String>()
                val runtime = DartRuntime.locate(filesDirectory, nativeDirectory, problems)
                if (runtime == null) { for (problem in problems) logger.onError(problem, null); return@execute }
                val runner = DartScriptRunner(runtime, File(filesDirectory, "dart-home"), host.cacheDir)
                running.set(runner)
                logger.onInfo("$ dart " + file.name)
                logger.onInfo(finished(runner.run(file, 180, logger)))
            } catch (error: Exception) {
                logger.onError("Could not run Dart: $error", error)
            } finally {
                ticket.release()
            }
        }
    }

    /** Runs a .py file with the bundled CPython and shows its output live. The standard library is downloaded once on first use. */
    fun runPython(file: File) {
        if (unsaved(file)) return
        val ticket = beginRun() ?: return
        val running = AtomicReference<PythonScriptRunner?>()
        val installing = AtomicReference<PythonProvisioner?>()
        val logger = openConsole(ticket, file.name) { running.get()?.cancel(); installing.get()?.cancel() }.logger
        scriptExecutor.execute {
            try {
                val filesDirectory = host.filesDir
                val nativeDirectory = File(host.applicationInfo.nativeLibraryDir)
                val unsupported = DeviceAbi.require64Bit("Python", DeviceAbi.supportedAbis())
                if (unsupported != null) { logger.onError(unsupported, null); return@execute }
                if (!PythonRuntime.isInterpreterBundled(nativeDirectory)) {
                    logger.onError("This build of Studio Mobile does not contain the Python interpreter (libpythonexe.so). Build the app with the Python step of .github/workflows/build.yml.", null)
                    return@execute
                }
                if (PythonRuntime.findStandardLibrary(File(filesDirectory, "python")) == null) {
                    val provisioner = PythonProvisioner(filesDirectory, readCatalog(), Build.SUPPORTED_ABIS.toList())
                    installing.set(provisioner)
                    if (provisioner.install(logger) == null) return@execute
                }
                val problems = ArrayList<String>()
                val runtime = PythonRuntime.locate(filesDirectory, nativeDirectory, problems)
                if (runtime == null) { for (problem in problems) logger.onError(problem, null); return@execute }
                val runner = PythonScriptRunner(runtime, File(filesDirectory, "python-home"), host.cacheDir)
                running.set(runner)
                logger.onInfo("$ python " + file.name)
                logger.onInfo(finished(runner.run(file, 120, logger), "\n[timed out after 120 s]"))
            } catch (error: Exception) {
                logger.onError("Could not run Python: $error", error)
            } finally {
                ticket.release()
            }
        }
    }

    /** Compiles a .java or .kt file (in-process ECJ or Kotlin compiler, then D8) and runs its main() in a separate process, showing the output live. */
    fun runJava(file: File) {
        if (unsaved(file)) return
        val timeoutSeconds = 120L
        val ticket = beginRun() ?: return
        val running = AtomicReference<JavaFileRunner?>()
        val logger = openConsole(ticket, file.name) { running.get()?.cancel() }.logger
        javaExecutor.execute {
            try {
                val runner = JavaFileRunner(host.applicationContext, host.toolsDir())
                running.set(runner)
                val code = runner.run(file, host.currentProject, timeoutSeconds, logger)
                logger.onInfo(JavaFileRunner.describe(code, timeoutSeconds))
            } catch (error: Exception) {
                logger.onError("Could not run: " + error.message, error)
            } finally {
                ticket.release()
            }
        }
    }
}
