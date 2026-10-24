package com.example.studiomobile

import android.app.Dialog
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.PersistableBundle
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import com.example.studiomobile.build.stages.ApkSigningStage
import com.example.studiomobile.workspace.ProjectKeys
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.KeyStore
import java.util.Locale

/**
 * Everything about signing keys, one key per project: the Keystore page (view, copy, import, export, change)
 * and the key dialogs that Export AAB also uses.
 */
class KeystoreUi(private val host: MainActivity) {
    private var pendingExportSource: File? = null
    private var keyProject: File? = null              // the project whose key is shown, imported or replaced
    private var afterKeyImport: (() -> Unit)? = null
    private var shownKey: ProjectKeys.Details? = null
    private var keyPasswordVisible = false
    private var pendingImportAlias = ""
    private var pendingImportStorePassword = ""
    private var pendingImportKeyPassword = ""

    private val pickKeystore = host.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) importKeystore(uri, pendingImportAlias, pendingImportStorePassword, pendingImportKeyPassword)
    }
    private val exportKeystoreLauncher = host.registerForActivityResult(ActivityResultContracts.CreateDocument("application/x-pkcs12")) { uri: Uri? ->
        if (uri != null) exportKeystoreTo(uri)
    }

    private fun keys() = host.keys()
    private fun debugKeyFile() = File(host.toolsDir(), ApkSigningStage.DEBUG_KEYSTORE_FILE)

    /** Key dialogs show secrets too, so their windows are protected from screenshots and screen recording as well. */
    fun secureDialog(dialog: Dialog) {
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    private fun ensureDefaultKey(afterReady: (() -> Unit)?) {
        host.io.execute {
            try {
                ApkSigningStage.ensureDebugKeyStore(host.toolsDir(), null)
                if (afterReady != null) host.runOnUiThread { afterReady() }
            } catch (e: Exception) {
                host.runOnUiThread { host.toast(LanguageUi.KEY_DEFAULT_FAILED + e.message) }
            }
        }
    }

    /** Asks for alias and password. Alias and store password are required; the key password is optional. */
    private fun credentialsDialog(title: String, message: String, aliasPrefill: String, positive: String, handler: (String, String, String) -> Unit) {
        val view = host.layoutInflater.inflate(R.layout.dialog_keystore_import, null)
        val alias = view.findViewById<EditText>(R.id.import_alias)
        val storePassword = view.findViewById<EditText>(R.id.import_store_password)
        val keyPassword = view.findViewById<EditText>(R.id.import_key_password)
        alias.setText(aliasPrefill)
        val dialog = MaterialAlertDialogBuilder(host).setTitle(title).setMessage(message).setView(view)
            .setNegativeButton(R.string.cancel, null).setPositiveButton(positive, null).create()
        secureDialog(dialog)
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val a = alias.text.toString().trim()
                val sp = storePassword.text.toString()
                val kp = keyPassword.text.toString()
                if (a.isEmpty()) { alias.error = LanguageUi.ALIAS_REQUIRED; return@setOnClickListener }
                if (sp.isEmpty()) { storePassword.error = LanguageUi.PASSWORD_REQUIRED; return@setOnClickListener }
                dialog.dismiss()
                handler(a, sp, kp)
            }
        }
        dialog.show()
    }

    // ---- the Keystore page of one project: the key it is signed with, plus Copy, Import, Export and Change

    fun show(project: File) {
        keyProject = project
        shownKey = null
        keyPasswordVisible = false
        host.show(MainActivity.Screen.KEYSTORE, R.layout.screen_keystore, true, R.color.cosmic_white)
        host.screenView.findViewById<TextView>(R.id.keystore_project).text = project.name
        host.click(R.id.keystore_back) { host.goBack() }
        host.click(R.id.key_copy) { shownKey?.let { copyKey(it) } }
        host.click(R.id.key_export) { exportKeystore(project) }
        host.click(R.id.key_import) { importKeystoreFlow(project) { show(project) } }
        host.click(R.id.key_change) {
            showKeyForm(project, LanguageUi.KEY_CHANGE_TITLE, LanguageUi.KEY_CHANGE_FORM_MESSAGE) { host.toast(LanguageUi.KEY_CHANGED); show(project) }
        }
        host.click(R.id.keystore_password_toggle) { keyPasswordVisible = !keyPasswordVisible; bindKeyDetails() }
        host.io.execute {
            try {
                val details = keys().details(project.name, host.toolsDir())
                host.runOnUiThread {
                    if (host.screen == MainActivity.Screen.KEYSTORE && project == keyProject) { shownKey = details; bindKeyDetails() }
                }
            } catch (e: Exception) {
                host.runOnUiThread { host.toast(LanguageUi.KEY_READ_FAILED + e.message) }
            }
        }
    }

    private fun bindKeyDetails() {
        val d = shownKey
        if (host.screen != MainActivity.Screen.KEYSTORE || d == null) return
        val hidden = "\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022"
        val view = host.screenView
        view.findViewById<TextView>(R.id.keystore_active).text = d.name
        view.findViewById<TextView>(R.id.keystore_type).text = if (d.isDefault) LanguageUi.KEY_TYPE_DEFAULT else LanguageUi.KEY_TYPE_CUSTOM
        view.findViewById<TextView>(R.id.keystore_alias).text = d.alias
        view.findViewById<TextView>(R.id.keystore_store_password).text = if (keyPasswordVisible) d.storePassword else hidden
        view.findViewById<TextView>(R.id.keystore_key_password).text = if (keyPasswordVisible) d.keyPassword else hidden
        view.findViewById<TextView>(R.id.keystore_owner).text = d.subject
        view.findViewById<TextView>(R.id.keystore_valid).text = d.validUntil
        view.findViewById<TextView>(R.id.keystore_sha256).text = d.sha256
        view.findViewById<TextView>(R.id.keystore_password_toggle).text = if (keyPasswordVisible) LanguageUi.HIDE else LanguageUi.SHOW
    }

    private fun copyKey(details: ProjectKeys.Details) {
        val clipboard = host.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(host.getString(R.string.keystore), details.toClipboardText())
        if (Build.VERSION.SDK_INT >= 33) {
            val extras = PersistableBundle()
            extras.putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)   // keeps keyboards and clipboard previews from showing the secrets
            clip.description.extras = extras
        }
        clipboard.setPrimaryClip(clip)
        host.toast(LanguageUi.KEY_COPIED)
    }

    // ---- export the key file of a project: ask alias + password, verify them, then pick the destination

    private fun exportKeystore(project: File) {
        val store = keys()
        val name = project.name
        val custom = store.isCustom(name)
        val properties = if (custom) store.read(name) else null
        if (custom && properties == null) { host.toast(LanguageUi.KEY_NO_FILE); return }
        val source = if (custom) store.keystoreFile(name) else debugKeyFile()
        if (!source.isFile) {
            if (!custom) { ensureDefaultKey { exportKeystore(project) }; return }
            host.toast(LanguageUi.KEY_NO_FILE)
            return
        }
        val alias = if (custom) properties!!.getProperty("alias", "") else ApkSigningStage.DEBUG_KEY_ALIAS
        val message = if (custom) LanguageUi.KEY_EXPORT_MESSAGE else LanguageUi.KEY_EXPORT_MESSAGE_DEBUG
        val fileName = host.safeName(name, "key") + (if (custom) "" else "-debug") + ".p12"
        credentialsDialog(LanguageUi.KEY_EXPORT_TITLE, message, alias, LanguageUi.KEY_EXPORT_ACTION) { a, sp, kp ->
            host.io.execute {
                try {
                    val keyStore = KeyStore.getInstance("PKCS12")
                    FileInputStream(source).use { keyStore.load(it, sp.toCharArray()) }
                    if (keyStore.getKey(a, (if (kp.isEmpty()) sp else kp).toCharArray()) == null) throw IOException("alias not found")
                    host.runOnUiThread { pendingExportSource = source; exportKeystoreLauncher.launch(fileName) }
                } catch (e: Exception) {
                    host.runOnUiThread { host.toast(LanguageUi.KEY_VERIFY_FAILED) }
                }
            }
        }
    }

    private fun exportKeystoreTo(uri: Uri) {
        val source = pendingExportSource
        if (source == null || !source.isFile) return
        host.io.execute {
            try {
                FileInputStream(source).use { input ->
                    (host.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("Could not open the destination")).use { output ->
                        host.copyStream(input, output)
                    }
                }
                host.runOnUiThread { host.toast(LanguageUi.KEY_EXPORTED) }
            } catch (e: Exception) {
                host.runOnUiThread { host.toast(LanguageUi.KEY_EXPORT_FAILED + e.message) }
            }
        }
    }

    // ---- import a key file into one project: ask alias + password first, then pick the file

    fun importKeystoreFlow(project: File, afterImport: (() -> Unit)?) {
        credentialsDialog(LanguageUi.KEY_IMPORT_TITLE, LanguageUi.KEY_IMPORT_MESSAGE, "", LanguageUi.CHOOSE_FILE) { a, sp, kp ->
            keyProject = project
            afterKeyImport = afterImport
            pendingImportAlias = a
            pendingImportStorePassword = sp
            pendingImportKeyPassword = kp
            pickKeystore.launch(arrayOf("*/*"))
        }
    }

    private fun importKeystore(uri: Uri, alias: String, storePassword: String, keyPasswordInput: String) {
        val project = keyProject ?: return
        val after = afterKeyImport
        val displayName = host.displayName(uri)
        val keyPassword = if (keyPasswordInput.isEmpty()) storePassword else keyPasswordInput
        host.io.execute {
            val temporary = File(host.cacheDir, "import-keystore.tmp")
            try {
                (host.contentResolver.openInputStream(uri) ?: throw IOException("Could not read the selected file")).use { input ->
                    FileOutputStream(temporary).use { output -> host.copyStream(input, output) }
                }
                val store = KeyStore.getInstance("PKCS12")
                FileInputStream(temporary).use { store.load(it, storePassword.toCharArray()) }
                if (store.getKey(alias, keyPassword.toCharArray()) == null) throw IOException("No private key was found for this alias and password.")
                val base = if (displayName.lowercase(Locale.ROOT).endsWith(".p12")) displayName.substring(0, displayName.length - 4) else displayName
                keys().install(project.name, temporary, host.safeName(base, "key"), alias, storePassword, keyPassword)
                host.runOnUiThread { host.toast(LanguageUi.KEY_IMPORTED); after?.invoke() }
            } catch (e: Exception) {
                host.runOnUiThread { host.toast(LanguageUi.KEY_IMPORT_FAILED + e.message) }
            } finally {
                pendingImportStorePassword = ""
                pendingImportKeyPassword = ""
                temporary.delete()
            }
        }
    }

    // ---- change key: a form card that creates a new key for the project

    /**
     * The form is one scrolling card: the explanation, all fields and the Cancel / Change Key buttons are inside the same ScrollView
     * (dialog_key_form.xml). A normal AlertDialog keeps its buttons outside the scroll area, and on a short window or with the
     * keyboard open they ended up squeezed or hidden, so they could not be tapped. Here they can always be scrolled into view.
     */
    fun showKeyForm(project: File, title: String, message: String, onChanged: () -> Unit) {
        val view = host.layoutInflater.inflate(R.layout.dialog_key_form, null)
        view.findViewById<TextView>(R.id.form_message).text = message
        val alias = view.findViewById<EditText>(R.id.form_alias)
        val password = view.findViewById<EditText>(R.id.form_password)
        val confirm = view.findViewById<EditText>(R.id.form_password_confirm)
        val company = view.findViewById<EditText>(R.id.form_company)
        val organization = view.findViewById<EditText>(R.id.form_organization)
        val unit = view.findViewById<EditText>(R.id.form_unit)
        val city = view.findViewById<EditText>(R.id.form_city)
        val state = view.findViewById<EditText>(R.id.form_state)
        val country = view.findViewById<EditText>(R.id.form_country)
        val cancelButton = view.findViewById<Button>(R.id.form_cancel)
        val confirmButton = view.findViewById<Button>(R.id.form_confirm)
        confirmButton.text = LanguageUi.KEY_CHANGE_ACTION
        val dialog = MaterialAlertDialogBuilder(host).setTitle(title).setView(view).create()
        secureDialog(dialog)
        // The window shrinks above the keyboard instead of sliding under it.
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        cancelButton.setOnClickListener { dialog.dismiss() }
        confirmButton.setOnClickListener {
            val a = alias.text.toString().trim()
            val pw = password.text.toString()
            val co = company.text.toString().trim()
            val org = organization.text.toString().trim()
            val ou = unit.text.toString().trim()
            val ci = city.text.toString().trim()
            val st = state.text.toString().trim()
            val cc = country.text.toString().trim()
            if (a.isEmpty()) { alias.error = LanguageUi.ALIAS_REQUIRED; alias.requestFocus(); return@setOnClickListener }
            if (pw.length < 6) { password.error = LanguageUi.PASSWORD_SHORT; password.requestFocus(); return@setOnClickListener }
            if (pw != confirm.text.toString()) { confirm.error = LanguageUi.PASSWORD_MISMATCH; confirm.requestFocus(); return@setOnClickListener }
            if (co.isEmpty()) { company.error = LanguageUi.COMPANY_REQUIRED; company.requestFocus(); return@setOnClickListener }
            if (org.isEmpty()) { organization.error = LanguageUi.ORGANIZATION_REQUIRED; organization.requestFocus(); return@setOnClickListener }
            if (!cc.matches(Regex("[A-Za-z]{2}"))) { country.error = LanguageUi.COUNTRY_INVALID; country.requestFocus(); return@setOnClickListener }
            dialog.dismiss()
            host.toast(LanguageUi.KEY_CREATING)
            host.io.execute {
                try {
                    keys().generate(project.name, a, pw, co, org, ou, ci, st, cc)
                    host.runOnUiThread { onChanged() }
                } catch (e: Exception) {
                    host.runOnUiThread { host.toast(LanguageUi.KEY_CREATE_FAILED + e.message) }
                }
            }
        }
        dialog.show()
    }
}
