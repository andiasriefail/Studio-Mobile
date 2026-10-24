package com.example.studiomobile

import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File

/**
 * Project menu > Export AAB (for Google Play). Google Play needs the developer's own key, so a project that still
 * uses the Studio Mobile default key is asked to create or import one first (using the dialogs of [KeystoreUi]).
 */
class AabExportUi(private val host: MainActivity, private val keystore: KeystoreUi) {

    fun start(project: File) {
        if (host.buildService.isBuilding) { host.toast(LanguageUi.BUILD_BUSY); return }
        if (host.keys().isCustom(project.name)) { startBuild(project); return }
        // Still the Studio Mobile default key: ask for a key first.
        keystore.showKeyForm(project, LanguageUi.AAB_KEY_TITLE, LanguageUi.AAB_KEY_MESSAGE) { showReadyDialog(project) }
    }

    private fun showReadyDialog(project: File) {
        val dialog: AlertDialog = MaterialAlertDialogBuilder(host).setTitle(LanguageUi.AAB_READY_TITLE).setMessage(LanguageUi.AAB_READY_MESSAGE)
            .setPositiveButton(LanguageUi.EXPORT_AAB) { _, _ -> startBuild(project) }
            .setNegativeButton(LanguageUi.KEY_IMPORT_TITLE) { _, _ -> keystore.importKeystoreFlow(project) { showReadyDialog(project) } }
            .setNeutralButton(R.string.cancel, null).create()
        keystore.secureDialog(dialog)
        dialog.show()
    }

    private fun startBuild(project: File) {
        if (host.buildService.isBuilding) { host.toast(LanguageUi.BUILD_BUSY); return }
        host.currentProject = project
        host.openedFile = null
        host.openTabs.clear()
        host.expanded.clear()
        host.bundleNext = true
        host.showBuild(true)
    }
}
