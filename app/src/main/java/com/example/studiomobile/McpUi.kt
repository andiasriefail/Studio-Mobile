package com.example.studiomobile

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.example.studiomobile.build.BuildToolchainInspector
import com.example.studiomobile.build.LocalLibsInstaller
import com.example.studiomobile.workspace.DebugModeSettings
import com.example.studiomobile.workspace.McpRelayClient
import com.example.studiomobile.workspace.McpRelayService
import com.example.studiomobile.workspace.McpRelayUrl
import com.example.studiomobile.workspace.McpRequestHandler
import com.example.studiomobile.workspace.McpSettings
import com.example.studiomobile.workspace.McpToolRegistry
import com.example.studiomobile.workspace.ProjectFileOperations
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
import org.json.JSONObject

/** The MCP screen: relay address, connect / disconnect, copy the endpoint, setup guides, self test and toolchain status. */
class McpUi(private val host: MainActivity) {
    private val notificationPermission = host.registerForActivityResult(ActivityResultContracts.RequestPermission()) { _: Boolean -> }

    private val relayListener = McpRelayService.Listener { host.runOnUiThread { renderRelay() } }

    /** Called from MainActivity.onStart: follow the connection state of the foreground service. */
    fun onStart() {
        McpRelayService.addListener(relayListener)
        renderRelay()
    }

    /** Called from MainActivity.onStop. */
    fun onStop() { McpRelayService.removeListener(relayListener) }

    fun show() {
        host.show(MainActivity.Screen.MCP, R.layout.screen_mcp, true, R.color.cosmic_white)
        val settings = McpSettings(host)
        host.click(R.id.mcp_back) { host.goBack() }
        val view = host.screenView

        val readOnlySwitch = view.findViewById<SwitchMaterial>(R.id.mcp_read_only_switch)
        readOnlySwitch.isChecked = settings.isReadOnly
        readOnlySwitch.setOnCheckedChangeListener { _, checked -> settings.setReadOnly(checked) }

        // The relay URL belongs to the user: typed here, stored on this phone, never built into the app.
        val urlInput = view.findViewById<EditText>(R.id.mcp_relay_url)
        urlInput.setText(settings.relayUrl)
        urlInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) { }
            override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) { }
            override fun afterTextChanged(text: Editable?) { settings.setRelayUrl(text?.toString().orEmpty()); renderEndpoint() }
        })
        val tokenInput = view.findViewById<EditText>(R.id.mcp_relay_token)
        tokenInput.setText(settings.relayToken)
        tokenInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) { }
            override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) { }
            override fun afterTextChanged(text: Editable?) { settings.setRelayToken(text?.toString().orEmpty()) }
        })
        host.click(R.id.mcp_relay_toggle) { toggleRelay(settings, urlInput) }
        host.click(R.id.mcp_endpoint_copy) {
            val endpoint = endpoint()
            if (endpoint != null) {
                val clipboard = host.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText(host.getString(R.string.mcp_endpoint_title), endpoint))
                host.toast(host.getString(R.string.mcp_endpoint_copied))
            }
        }

        for (guide in arrayOf("claude", "chatgpt", "gemini", "code")) {
            val body = view.findViewById<View>(idOf("mcp_guide_${guide}_body"))
            val chevron = view.findViewById<View>(idOf("mcp_guide_${guide}_chevron"))
            view.findViewById<View>(idOf("mcp_guide_${guide}_header")).setOnClickListener {
                val open = body.visibility != View.VISIBLE
                body.visibility = if (open) View.VISIBLE else View.GONE
                chevron.rotation = if (open) 180f else 0f
            }
        }

        host.click(R.id.mcp_self_test) { runSelfTest() }
        host.click(R.id.mcp_toolchain_status) { showToolchainStatus() }
        renderEndpoint()
        renderRelay()
    }

    private fun idOf(name: String): Int = host.resources.getIdentifier(name, "id", host.packageName)

    private fun newRegistry(settings: McpSettings) =
        McpToolRegistry(ProjectFileOperations(host.projectsDir()), settings, host.buildService, host.toolsDir(), null, DebugModeSettings(host))

    private fun appVersionName(): String =
        try { host.packageManager.getPackageInfo(host.packageName, 0).versionName ?: "0.1.0" } catch (ignored: Exception) { "0.1.0" }

    /** The relay address the user typed, normalized, or null when it is empty or not usable. */
    private fun relayBase(): String? {
        if (host.screenView == null) return null
        val input = host.screenView.findViewById<EditText>(R.id.mcp_relay_url) ?: return null
        return McpRelayUrl.normalize(input.text.toString())
    }

    /** The address to paste into Claude, ChatGPT or Gemini, or null until a valid relay URL is entered. */
    private fun endpoint(): String? = relayBase()?.let { McpRelayUrl.aiEndpoint(it) }

    private fun toggleRelay(settings: McpSettings, urlInput: EditText) {
        if (McpRelayService.isRunning()) { McpRelayService.stop(host); return }
        val base = McpRelayUrl.normalize(urlInput.text.toString())
        if (base == null) { host.toast(host.getString(R.string.mcp_relay_url_invalid)); return }
        settings.setRelayUrl(base)
        if (base != urlInput.text.toString()) urlInput.setText(base)
        // The foreground notification is hidden without this permission on Android 13+, but the connection still works.
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(host, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        McpRelayService.start(host)
    }

    /** Shows the address for the AI and fills the guides with it. */
    private fun renderEndpoint() {
        if (host.screen != MainActivity.Screen.MCP || host.screenView == null) return
        val view = host.screenView
        val endpoint = endpoint()
        val text = view.findViewById<TextView>(R.id.mcp_endpoint_text)
        text.text = endpoint ?: host.getString(R.string.mcp_endpoint_empty)
        text.setTextColor(host.color(if (endpoint == null) R.color.cosmic_muted else R.color.cosmic_ink))
        view.findViewById<View>(R.id.mcp_endpoint_actions).visibility = if (endpoint == null) View.GONE else View.VISIBLE
        val shown = endpoint ?: host.getString(R.string.mcp_endpoint_placeholder)
        view.findViewById<TextView>(R.id.mcp_guide_claude_body).text = host.getString(R.string.mcp_guide_claude_body, shown)
        view.findViewById<TextView>(R.id.mcp_guide_chatgpt_body).text = host.getString(R.string.mcp_guide_chatgpt_body, shown)
        view.findViewById<TextView>(R.id.mcp_guide_gemini_body).text = host.getString(R.string.mcp_guide_gemini_body, shown)
        view.findViewById<TextView>(R.id.mcp_guide_code_body).text = host.getString(R.string.mcp_guide_code_body, shown)
    }

    /** Shows the live connection state, taken from the foreground service. */
    private fun renderRelay() {
        if (host.screen != MainActivity.Screen.MCP || host.screenView == null) return
        val view = host.screenView
        val running = McpRelayService.isRunning()
        val detail = McpRelayService.getDetail()
        val label: Int
        val tint: Int
        when (McpRelayService.getState()) {
            McpRelayClient.State.CONNECTING -> { label = R.string.mcp_status_connecting; tint = R.color.cosmic_primary_dark }
            McpRelayClient.State.CONNECTED -> { label = R.string.mcp_status_connected; tint = R.color.cosmic_mint_ink }
            McpRelayClient.State.RECONNECTING -> { label = R.string.mcp_status_reconnecting; tint = R.color.cosmic_peach_ink }
            McpRelayClient.State.FAILED -> { label = R.string.mcp_status_failed; tint = R.color.cosmic_danger }
            else -> { label = R.string.mcp_connection_none; tint = R.color.cosmic_muted }
        }
        val value = view.findViewById<TextView>(R.id.mcp_connection_value)
        value.setText(label)
        value.setTextColor(host.color(tint))
        val detailView = view.findViewById<TextView>(R.id.mcp_status_detail)
        detailView.text = detail
        detailView.visibility = if (detail.isEmpty()) View.GONE else View.VISIBLE
        view.findViewById<TextView>(R.id.mcp_relay_toggle).setText(if (running) R.string.mcp_disconnect else R.string.mcp_connect)
        view.findViewById<View>(R.id.mcp_relay_url).isEnabled = !running
        view.findViewById<View>(R.id.mcp_relay_token).isEnabled = !running
    }

    /** Sends three real MCP messages through the handler so the user can confirm it works on this device. */
    private fun runSelfTest() {
        val settings = McpSettings(host)
        val handler = McpRequestHandler(newRegistry(settings), settings, appVersionName())
        val report = StringBuilder()
        report.append(selfTestStep(handler, "initialize", """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18"}}""")).append('\n')
        report.append(selfTestStep(handler, "tools/list", """{"jsonrpc":"2.0","id":2,"method":"tools/list"}""")).append('\n')
        report.append(selfTestStep(handler, "list_projects", """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"list_projects","arguments":{}}}"""))
        MaterialAlertDialogBuilder(host).setTitle(R.string.mcp_self_test_title).setMessage(report.toString()).setPositiveButton(R.string.ok, null).show()
    }

    private fun selfTestStep(handler: McpRequestHandler, label: String, request: String): String {
        try {
            val response = handler.handle(request) ?: return "$label: no response"
            val json = JSONObject(response)
            if (json.has("error")) return "$label: FAILED - " + json.getJSONObject("error").optString("message")
            val result = json.getJSONObject("result")
            if (result.has("tools")) return "$label: OK, " + result.getJSONArray("tools").length() + " tools"
            if (result.has("protocolVersion")) return "$label: OK, protocol " + result.getString("protocolVersion")
            if (result.optBoolean("isError", false)) return "$label: tool returned an error"
            return "$label: OK"
        } catch (e: Exception) {
            return "$label: FAILED - " + e.message
        }
    }

    private fun showToolchainStatus() {
        host.io.execute {
            LocalLibsInstaller.install(host)
            val missing = BuildToolchainInspector(host).inspect()
            val shown = if (missing.isEmpty()) LanguageUi.TOOLCHAIN_OK
                        else LanguageUi.TOOLCHAIN_MISSING + android.text.TextUtils.join("\n\n- ", missing)
            host.runOnUiThread {
                MaterialAlertDialogBuilder(host).setTitle(LanguageUi.TOOLCHAIN_TITLE).setMessage(shown).setPositiveButton(R.string.ok, null).show()
            }
        }
    }
}
