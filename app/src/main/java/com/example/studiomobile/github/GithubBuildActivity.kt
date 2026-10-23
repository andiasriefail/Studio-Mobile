package com.example.studiomobile.github

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.example.studiomobile.R
import com.example.studiomobile.workspace.StudioStorage
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.zip.ZipFile

private class DownloadState {
    @Volatile var done = 0L
    @Volatile var total = 0L
    @Volatile var running = false
    @Volatile var cancel = false
    @Volatile var failed = ""
    @Volatile var file: File? = null
    @Volatile var hasApk = false
}

class GithubBuildActivity : AppCompatActivity() {
    private lateinit var views: GithubViews
    private lateinit var api: GithubApi
    private lateinit var root: LinearLayout
    private lateinit var header: LinearLayout
    private lateinit var titleView: TextView
    private lateinit var actionView: TextView
    private lateinit var body: FrameLayout

    private val io: ExecutorService = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())

    private var page = PAGE_URL
    private var navigation = 0
    private var loadingRepo = false
    private var urlText = ""
    private var urlError = ""

    private var owner = ""
    private var repo = ""
    private var repoInfo: RepoInfo? = null
    private var branches: List<String> = emptyList()
    private var tags: List<String> = emptyList()
    private var workflows: List<WorkflowInfo> = emptyList()
    private val specs = ConcurrentHashMap<String, WorkflowSpec>()
    private var ref = ""
    private var selected: WorkflowInfo? = null
    private var values = LinkedHashMap<String, String>()

    private var currentRun: RunInfo? = null
    private var currentJobs: List<JobInfo> = emptyList()
    private var logText = ""
    private var runError = ""
    private var artifacts: List<ArtifactInfo> = emptyList()
    private val downloads = HashMap<Long, DownloadState>()
    private var savedLog: File? = null
    private var finalizedRun = 0L
    private var pollCount = 0
    private var pendingInstall: File? = null

    private var runTitleView: TextView? = null
    private var runMetaView: TextView? = null
    private var runStatusView: TextView? = null
    private var runTimeView: TextView? = null
    private var runSpinner: ProgressBar? = null
    private var runErrorView: TextView? = null
    private var stepsBox: LinearLayout? = null
    private var logView: TextView? = null
    private var logScroll: ScrollView? = null
    private var stopButton: TextView? = null
    private var githubButton: TextView? = null
    private var artifactBox: LinearLayout? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val token = GithubTokenStore.load(this)
        if (token == null) {
            finish()
            return
        }
        api = GithubApi(token)
        views = GithubViews(this)
        buildFrame()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                goBack()
            }
        })
        showUrlPage()
    }

    override fun onResume() {
        super.onResume()
        val file = pendingInstall
        if (file != null && packageManager.canRequestPackageInstalls()) {
            pendingInstall = null
            launchInstall(file)
        }
    }

    override fun onDestroy() {
        navigation++
        for (state in downloads.values) state.cancel = true
        io.shutdownNow()
        super.onDestroy()
    }

    private fun buildFrame() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundResource(R.drawable.bg_screen_light)

        header = LinearLayout(this)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        header.setBackgroundResource(R.drawable.bg_cosmic_header)
        header.setPadding(views.dp(16), views.dp(40), views.dp(16), views.dp(16))

        val back = ImageView(this)
        back.setImageResource(R.drawable.ic_back)
        back.imageTintList = ColorStateList.valueOf(Color.WHITE)
        back.setPadding(views.dp(8), views.dp(8), views.dp(8), views.dp(8))
        back.setOnClickListener { goBack() }
        header.addView(back, LinearLayout.LayoutParams(views.dp(40), views.dp(40)))

        titleView = views.text("", 20f, R.color.cosmic_white, true)
        titleView.maxLines = 1
        titleView.ellipsize = android.text.TextUtils.TruncateAt.END
        val titleParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        titleParams.marginStart = views.dp(8)
        header.addView(titleView, titleParams)

        actionView = views.text("", 13f, R.color.cosmic_white, true)
        actionView.setBackgroundResource(R.drawable.bg_chip_mcp)
        actionView.setPadding(views.dp(14), views.dp(8), views.dp(14), views.dp(8))
        actionView.visibility = View.GONE
        header.addView(actionView)

        body = FrameLayout(this)
        root.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            header.setPadding(views.dp(16) + bars.left, bars.top + views.dp(12), views.dp(16) + bars.right, views.dp(16))
            body.setPadding(bars.left, 0, bars.right, maxOf(bars.bottom, ime.bottom))
            WindowInsetsCompat.CONSUMED
        }
    }

    private fun setPage(view: View) {
        body.removeAllViews()
        body.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun pageColumn(): LinearLayout {
        val column = views.box()
        column.setPadding(views.dp(20), views.dp(20), views.dp(20), views.dp(28))
        return column
    }

    private fun scrolled(content: View): ScrollView {
        val scroll = ScrollView(this)
        scroll.isFillViewport = true
        scroll.addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return scroll
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    }

    private fun message(error: Exception): String =
        if (error is GithubApiException) error.message ?: "GitHub reported an error." else "Connection problem. Check your internet and try again."

    private fun goBack() {
        navigation++
        when (page) {
            PAGE_RUN -> if (workflows.isNotEmpty()) showSelectPage() else showUrlPage()
            PAGE_SELECT -> showUrlPage()
            else -> if (loadingRepo) showUrlPage() else finish()
        }
    }

    private fun confirmDeleteToken() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete token")
            .setMessage("The saved token will be removed from this device.")
            .setPositiveButton("Delete") { _, _ ->
                GithubTokenStore.delete(this)
                finish()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showUrlPage(loading: Boolean = false) {
        page = PAGE_URL
        loadingRepo = loading
        titleView.text = "Github Build"
        actionView.text = "Delete Token"
        actionView.visibility = View.VISIBLE
        actionView.setOnClickListener { confirmDeleteToken() }

        val content = pageColumn()
        content.addView(
            views.text(
                "Paste the address of a GitHub repository. Studio Mobile reads its workflows, lets you pick the branch and inputs, runs the workflow on GitHub and downloads the result.",
                14f,
                R.color.cosmic_muted
            )
        )
        val input = views.input("https://github.com/owner/repo")
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        input.imeOptions = EditorInfo.IME_ACTION_GO
        input.setText(urlText)
        input.isEnabled = !loading
        content.addView(input, views.params(top = 16))

        if (urlError.isNotEmpty()) content.addView(views.text(urlError, 13f, R.color.cosmic_danger), views.params(top = 10))

        if (loading) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.addView(views.spinner(), LinearLayout.LayoutParams(views.dp(28), views.dp(28)))
            val label = views.text("Reading the repository...", 14f, R.color.cosmic_muted)
            val labelParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            labelParams.marginStart = views.dp(12)
            row.addView(label, labelParams)
            content.addView(row, views.params(top = 20))
        } else {
            val build = {
                urlText = input.text.toString().trim()
                startLoad()
            }
            input.setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_GO) {
                    build()
                    true
                } else {
                    false
                }
            }
            content.addView(views.button("Build", true) { build() }, views.params(top = 16))
            val prefs = getSharedPreferences(GithubTokenStore.PREFS, MODE_PRIVATE)
            val lastRun = prefs.getLong("last_run", 0L)
            if (lastRun != 0L) {
                val label = "Resume last run: ${prefs.getString("last_owner", "")}/${prefs.getString("last_repo", "")} #${prefs.getInt("last_number", 0)}"
                val resume = views.text(label, 14f, R.color.cosmic_primary_dark, true)
                resume.paintFlags = resume.paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
                resume.setPadding(views.dp(4), views.dp(14), views.dp(4), views.dp(14))
                resume.setOnClickListener { resumeLast() }
                content.addView(resume, views.params(top = 12))
            }
        }
        setPage(scrolled(content))
    }

    private fun startLoad() {
        val parsed = GithubRepoUrl.parse(urlText)
        if (parsed == null) {
            urlError = "Enter a GitHub repository address, for example https://github.com/owner/repo"
            showUrlPage()
            return
        }
        urlError = ""
        owner = parsed.owner
        repo = parsed.repo
        val token = ++navigation
        showUrlPage(true)
        val ownerNow = owner
        val repoNow = repo
        io.execute {
            try {
                val info = api.repo(ownerNow, repoNow)
                val branchList = api.branches(ownerNow, repoNow)
                val tagList = try {
                    api.tags(ownerNow, repoNow)
                } catch (e: IOException) {
                    emptyList()
                }
                val flows = api.workflows(ownerNow, repoNow)
                if (flows.isEmpty()) throw GithubApiException(0, "This repository has no GitHub Actions workflows.")
                val start = if (parsed.refHint.isNotEmpty() && (parsed.refHint in branchList || parsed.refHint in tagList)) parsed.refHint else info.defaultBranch
                for (workflow in flows) fetchSpec(workflow, start)
                main.post {
                    if (token == navigation) {
                        repoInfo = info
                        branches = branchList
                        tags = tagList
                        workflows = flows
                        ref = start
                        selected = firstRunnable()
                        values = LinkedHashMap()
                        applyDefaults()
                        showSelectPage()
                    }
                }
            } catch (e: Exception) {
                main.post {
                    if (token == navigation) {
                        urlError = message(e)
                        showUrlPage()
                    }
                }
            }
        }
    }

    private fun specKey(workflow: WorkflowInfo, at: String): String = "${workflow.id}@$at"

    private fun fetchSpec(workflow: WorkflowInfo, at: String): WorkflowSpec {
        val key = specKey(workflow, at)
        val cached = specs[key]
        if (cached != null) return cached
        val spec = try {
            WorkflowYaml.parse(api.workflowFile(owner, repo, workflow.path, at))
        } catch (e: GithubApiException) {
            if (e.status == 404) WorkflowSpec.missing() else WorkflowSpec.unreadable()
        } catch (e: IOException) {
            return WorkflowSpec.unreadable()
        }
        specs[key] = spec
        return spec
    }

    private fun runnable(workflow: WorkflowInfo): Boolean {
        if (workflow.state != "active") return false
        val spec = specs[specKey(workflow, ref)] ?: return false
        return spec.state == WorkflowSpec.UNREADABLE || (spec.state == WorkflowSpec.OK && spec.manual)
    }

    private fun note(workflow: WorkflowInfo): String {
        if (workflow.state != "active") return "Disabled on GitHub"
        val spec = specs[specKey(workflow, ref)] ?: return ""
        return when {
            spec.state == WorkflowSpec.MISSING -> "Not found on $ref"
            spec.state == WorkflowSpec.UNREADABLE -> "Inputs unavailable: the token needs Contents (read)"
            !spec.manual -> "No manual trigger (workflow_dispatch)"
            else -> ""
        }
    }

    private fun firstRunnable(): WorkflowInfo? = workflows.firstOrNull { runnable(it) }

    private fun applyDefaults() {
        val workflow = selected ?: return
        val spec = specs[specKey(workflow, ref)] ?: return
        val next = LinkedHashMap<String, String>()
        for (input in spec.inputs) {
            val old = values[input.key]
            next[input.key] = if (old != null && (input.options.isEmpty() || old in input.options)) old else input.defaultValue
        }
        values = next
    }

    private fun showLoading(text: String) {
        page = PAGE_SELECT
        actionView.visibility = View.GONE
        val column = LinearLayout(this)
        column.orientation = LinearLayout.VERTICAL
        column.gravity = Gravity.CENTER
        column.addView(views.spinner(), LinearLayout.LayoutParams(views.dp(40), views.dp(40)))
        column.addView(views.text(text, 14f, R.color.cosmic_muted), views.params(top = 16))
        setPage(column)
    }

    private fun showSelectPage() {
        page = PAGE_SELECT
        titleView.text = "$owner/$repo"
        actionView.visibility = View.GONE

        val content = pageColumn()
        val info = repoInfo
        if (info != null) {
            val visibility = if (info.isPrivate) "Private" else "Public"
            content.addView(views.text("$visibility  •  default branch ${info.defaultBranch}", 13f, R.color.cosmic_muted))
        }
        content.addView(views.label("Use workflow from"))
        content.addView(views.button("⎇  $ref  ▾", false) { showRefPicker() }, views.params())
        content.addView(views.label("Workflows"))
        for (workflow in workflows) content.addView(workflowRow(workflow), views.params(top = 8))

        val current = selected
        if (current != null) renderInputs(content, current)

        val bar = views.box()
        bar.setPadding(views.dp(20), views.dp(12), views.dp(20), views.dp(16))
        val run = views.button("Run workflow", true) { runSelected() }
        run.alpha = if (current != null && runnable(current)) 1f else 0.45f
        bar.addView(run, views.params())

        val container = LinearLayout(this)
        container.orientation = LinearLayout.VERTICAL
        container.addView(scrolled(content), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        container.addView(bar, views.params())
        setPage(container)
    }

    private fun workflowRow(workflow: WorkflowInfo): View {
        val isSelected = selected?.id == workflow.id
        val ok = runnable(workflow)
        val card = views.card(isSelected)
        val title = views.text(workflow.name.ifEmpty { workflow.path }, 15f, if (ok) R.color.cosmic_ink else R.color.cosmic_muted, true)
        card.addView(title)
        card.addView(views.text(workflow.path, 12f, R.color.cosmic_muted), views.params(top = 2))
        val hint = note(workflow)
        if (hint.isNotEmpty()) card.addView(views.text(hint, 12f, R.color.cosmic_peach_ink), views.params(top = 6))
        card.isClickable = true
        card.setOnClickListener {
            if (ok) {
                selected = workflow
                applyDefaults()
                showSelectPage()
            } else {
                toast(hint.ifEmpty { "This workflow cannot be run manually." })
            }
        }
        return card
    }

    private fun renderInputs(content: LinearLayout, workflow: WorkflowInfo) {
        val spec = specs[specKey(workflow, ref)] ?: return
        if (spec.state == WorkflowSpec.UNREADABLE) {
            content.addView(views.text("The workflow file could not be read, so it will run without inputs.", 13f, R.color.cosmic_muted), views.params(top = 12))
            return
        }
        if (spec.inputs.isEmpty()) return
        content.addView(views.label("Inputs"))
        for (input in spec.inputs) content.addView(inputView(input), views.params(top = 12))
    }

    private fun inputView(input: WorkflowInput): View {
        val box = views.box()
        val title = (if (input.description.isNotEmpty()) input.description else input.key) + if (input.required) " *" else ""
        when (input.type) {
            "boolean" -> {
                val check = CheckBox(this)
                check.text = title
                check.setTextColor(views.res(R.color.cosmic_ink))
                check.buttonTintList = ColorStateList.valueOf(views.res(R.color.cosmic_primary))
                check.isChecked = values[input.key] == "true"
                check.setOnCheckedChangeListener { _, on -> values[input.key] = if (on) "true" else "false" }
                box.addView(check)
            }
            "choice" -> {
                box.addView(views.text(title, 14f, R.color.cosmic_ink, true))
                val pick = views.button("", false) { }
                pick.text = (values[input.key] ?: input.defaultValue) + "  ▾"
                pick.setOnClickListener { chooseOption(input, pick) }
                box.addView(pick, views.params(top = 6))
            }
            else -> {
                box.addView(views.text(title, 14f, R.color.cosmic_ink, true))
                val field = views.input(input.key)
                if (input.type == "number") {
                    field.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
                }
                field.setText(values[input.key] ?: input.defaultValue)
                field.addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                    }

                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    }

                    override fun afterTextChanged(s: Editable?) {
                        values[input.key] = s?.toString() ?: ""
                    }
                })
                box.addView(field, views.params(top = 6))
            }
        }
        if (input.description.isNotEmpty()) box.addView(views.text(input.key, 12f, R.color.cosmic_muted), views.params(top = 4))
        return box
    }

    private fun chooseOption(input: WorkflowInput, pick: TextView) {
        val options = input.options.toTypedArray()
        val current = options.indexOf(values[input.key] ?: input.defaultValue)
        MaterialAlertDialogBuilder(this)
            .setTitle(if (input.description.isNotEmpty()) input.description else input.key)
            .setSingleChoiceItems(options, current) { dialog, which ->
                values[input.key] = options[which]
                pick.text = options[which] + "  ▾"
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showRefPicker() {
        val info = repoInfo ?: return
        var showTags = false
        var query = ""
        val shown = ArrayList<String>()

        val box = views.box()
        box.setPadding(views.dp(20), views.dp(8), views.dp(20), 0)
        val search = views.input("Find a branch or tag")
        box.addView(search, views.params())
        val tabs = LinearLayout(this)
        tabs.orientation = LinearLayout.HORIZONTAL
        val branchTab = views.text("Branches", 14f, R.color.cosmic_primary_dark, true)
        val tagTab = views.text("Tags", 14f, R.color.cosmic_muted, true)
        branchTab.setPadding(views.dp(4), views.dp(14), views.dp(20), views.dp(10))
        tagTab.setPadding(views.dp(4), views.dp(14), views.dp(20), views.dp(10))
        tabs.addView(branchTab)
        tabs.addView(tagTab)
        box.addView(tabs, views.params())

        val list = ListView(this)
        val adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, shown) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val row = super.getView(position, convertView, parent) as TextView
                val name = getItem(position) ?: ""
                row.text = when {
                    name == ref -> "✓  $name"
                    !showTags && name == info.defaultBranch -> "$name   (default)"
                    else -> name
                }
                row.setTextColor(views.res(R.color.cosmic_ink))
                return row
            }
        }
        list.adapter = adapter
        box.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, views.dp(320)))

        fun refresh() {
            shown.clear()
            val source = if (showTags) tags else branches
            shown.addAll(source.filter { it.contains(query, true) })
            adapter.notifyDataSetChanged()
            branchTab.setTextColor(views.res(if (showTags) R.color.cosmic_muted else R.color.cosmic_primary_dark))
            tagTab.setTextColor(views.res(if (showTags) R.color.cosmic_primary_dark else R.color.cosmic_muted))
        }
        branchTab.setOnClickListener {
            showTags = false
            refresh()
        }
        tagTab.setOnClickListener {
            showTags = true
            refresh()
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
            }

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
            }

            override fun afterTextChanged(s: Editable?) {
                query = s?.toString() ?: ""
                refresh()
            }
        })
        refresh()

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Switch branches/tags")
            .setView(box)
            .setNegativeButton("Close", null)
            .create()
        list.setOnItemClickListener { _, _, position, _ ->
            val name = shown[position]
            dialog.dismiss()
            chooseRef(name)
        }
        dialog.show()
    }

    private fun chooseRef(name: String) {
        if (name == ref) return
        ref = name
        val token = ++navigation
        showLoading("Reading workflows on $name...")
        io.execute {
            for (workflow in workflows) fetchSpec(workflow, name)
            main.post {
                if (token == navigation) {
                    val current = selected
                    if (current == null || !runnable(current)) selected = firstRunnable()
                    applyDefaults()
                    showSelectPage()
                }
            }
        }
    }

    private fun runSelected() {
        val workflow = selected
        if (workflow == null || !runnable(workflow)) {
            toast("Choose a workflow that can be run manually.")
            return
        }
        val spec = specs[specKey(workflow, ref)] ?: return
        val missing = spec.inputs.firstOrNull { it.required && (values[it.key] ?: "").isBlank() }
        if (missing != null) {
            toast("Fill in: " + missing.description.ifEmpty { missing.key })
            return
        }
        val payload = LinkedHashMap<String, String>()
        for (input in spec.inputs) {
            val value = values[input.key] ?: input.defaultValue
            if (value.isNotEmpty() || input.type == "boolean") payload[input.key] = value
        }
        startRun(workflow, payload)
    }

    private fun resetRun() {
        currentRun = null
        currentJobs = emptyList()
        logText = ""
        runError = ""
        artifacts = emptyList()
        for (state in downloads.values) state.cancel = true
        downloads.clear()
        savedLog = null
        finalizedRun = 0L
        pollCount = 0
    }

    private fun startRun(workflow: WorkflowInfo, payload: Map<String, String>) {
        val token = ++navigation
        resetRun()
        showRunPage()
        val ownerNow = owner
        val repoNow = repo
        val refNow = ref
        io.execute {
            try {
                val known = api.recentRunIds(ownerNow, repoNow, workflow.id)
                api.dispatch(ownerNow, repoNow, workflow.id, refNow, payload)
                var found: RunInfo? = null
                var tries = 0
                while (found == null && tries < 30 && token == navigation) {
                    Thread.sleep(2000)
                    found = api.newRun(ownerNow, repoNow, workflow.id, known)
                    tries++
                }
                val result = found
                main.post {
                    if (token == navigation) {
                        if (result == null) {
                            runError = "The workflow was started, but its run could not be found. Open GitHub to check."
                            updateRunUi()
                        } else {
                            onRunFound(result, token)
                        }
                    }
                }
            } catch (e: Exception) {
                main.post {
                    if (token == navigation) {
                        runError = message(e)
                        updateRunUi()
                    }
                }
            }
        }
    }

    private fun resumeLast() {
        val prefs = getSharedPreferences(GithubTokenStore.PREFS, MODE_PRIVATE)
        val id = prefs.getLong("last_run", 0L)
        if (id == 0L) return
        owner = prefs.getString("last_owner", "") ?: ""
        repo = prefs.getString("last_repo", "") ?: ""
        workflows = emptyList()
        val token = ++navigation
        resetRun()
        showRunPage()
        val ownerNow = owner
        val repoNow = repo
        io.execute {
            try {
                val run = api.run(ownerNow, repoNow, id)
                main.post {
                    if (token == navigation) onRunFound(run, token)
                }
            } catch (e: Exception) {
                main.post {
                    if (token == navigation) {
                        runError = message(e)
                        updateRunUi()
                    }
                }
            }
        }
    }

    private fun remember(run: RunInfo) {
        getSharedPreferences(GithubTokenStore.PREFS, MODE_PRIVATE).edit()
            .putString("last_owner", owner)
            .putString("last_repo", repo)
            .putLong("last_run", run.id)
            .putInt("last_number", run.number)
            .apply()
    }

    private fun onRunFound(run: RunInfo, token: Int) {
        currentRun = run
        remember(run)
        updateRunUi()
        schedulePoll(token, 0)
        startTicker(token)
    }

    private fun schedulePoll(token: Int, delay: Long) {
        main.postDelayed({
            if (token == navigation && page == PAGE_RUN) io.execute { poll(token) }
        }, delay)
    }

    private fun startTicker(token: Int) {
        main.postDelayed({
            if (token == navigation && page == PAGE_RUN) {
                val run = currentRun
                if (run != null && run.status != "completed") {
                    runTimeView?.text = GithubFormat.duration(System.currentTimeMillis() - run.startedAt)
                }
                startTicker(token)
            }
        }, 1000)
    }

    private fun activeJob(jobs: List<JobInfo>): JobInfo? =
        jobs.firstOrNull { it.status == "in_progress" } ?: jobs.firstOrNull { it.conclusion == "failure" } ?: jobs.lastOrNull()

    private fun poll(token: Int) {
        val first = currentRun ?: return
        val ownerNow = owner
        val repoNow = repo
        try {
            val run = api.run(ownerNow, repoNow, first.id)
            val jobs = api.jobs(ownerNow, repoNow, run.id)
            val active = activeJob(jobs)
            pollCount++
            val log = if (active != null && active.status != "queued" && (pollCount % 2 == 1 || run.status == "completed")) {
                api.jobLogTail(ownerNow, repoNow, active.id, 400)
            } else {
                null
            }
            val list = if (run.status == "completed" && run.conclusion == "success") api.artifacts(ownerNow, repoNow, run.id) else null
            main.post {
                if (token == navigation) applyPoll(run, jobs, log, list)
            }
            if (run.status != "completed") schedulePoll(token, 3000)
        } catch (e: GithubApiException) {
            main.post {
                if (token == navigation) {
                    runError = message(e)
                    updateRunUi()
                }
            }
            if (e.status != 401 && e.status != 404) schedulePoll(token, if (e.status == 403 || e.status == 429) 20000L else 5000L)
        } catch (e: IOException) {
            main.post {
                if (token == navigation) {
                    runError = "Connection problem, retrying..."
                    updateRunUi()
                }
            }
            schedulePoll(token, 5000)
        }
    }

    private fun applyPoll(run: RunInfo, jobs: List<JobInfo>, log: String?, list: List<ArtifactInfo>?) {
        currentRun = run
        currentJobs = jobs
        if (log != null) logText = log
        runError = ""
        if (list != null && artifacts.isEmpty()) {
            artifacts = list
            val usable = list.filter { !it.expired }
            if (usable.size == 1) startDownload(usable[0])
        }
        if (run.status == "completed" && run.conclusion != "success" && finalizedRun != run.id) {
            finalizedRun = run.id
            saveFailureLog(run, jobs)
        }
        remember(run)
        updateRunUi()
        refreshArtifacts()
    }

    private fun saveFailureLog(run: RunInfo, jobs: List<JobInfo>) {
        val job = jobs.firstOrNull { it.conclusion == "failure" } ?: jobs.lastOrNull() ?: return
        val ownerNow = owner
        val repoNow = repo
        val name = GithubFormat.safeName(repoNow) + "-run" + run.number + ".log"
        io.execute {
            try {
                val target = File(StudioStorage.errorLogsDir(this), name)
                api.saveJobLog(ownerNow, repoNow, job.id, target)
                savedLog = target
                main.post { refreshArtifacts() }
            } catch (e: Exception) {
            }
        }
    }

    private fun statusLabel(run: RunInfo?): String {
        if (run == null) return if (runError.isEmpty()) "Starting" else "Stopped"
        if (run.status == "completed") {
            return when (run.conclusion) {
                "success" -> "Succeeded"
                "failure" -> "Failed"
                "cancelled" -> "Cancelled"
                "timed_out" -> "Timed out"
                "skipped" -> "Skipped"
                else -> run.conclusion.replaceFirstChar { it.uppercase() }.ifEmpty { "Finished" }
            }
        }
        return if (run.status == "in_progress") "Running" else "Queued"
    }

    private fun statusColor(run: RunInfo?): Int {
        if (run == null) return if (runError.isEmpty()) R.color.cosmic_primary else R.color.cosmic_danger
        if (run.status == "completed") {
            return when (run.conclusion) {
                "success" -> R.color.cosmic_mint_ink
                "failure", "timed_out" -> R.color.cosmic_danger
                else -> R.color.cosmic_muted
            }
        }
        return if (run.status == "in_progress") R.color.cosmic_primary else R.color.cosmic_muted
    }

    private fun showRunPage() {
        page = PAGE_RUN
        titleView.text = "$owner/$repo"
        actionView.visibility = View.GONE

        val content = pageColumn()
        val statusCard = views.card()
        val title = views.text("", 17f, R.color.cosmic_ink, true)
        val meta = views.text("", 13f, R.color.cosmic_muted)
        val statusRow = LinearLayout(this)
        statusRow.orientation = LinearLayout.HORIZONTAL
        statusRow.gravity = Gravity.CENTER_VERTICAL
        val spinner = views.spinner()
        val status = views.text("", 15f, R.color.cosmic_primary, true)
        val time = views.text("", 14f, R.color.cosmic_muted)
        statusRow.addView(spinner, LinearLayout.LayoutParams(views.dp(20), views.dp(20)))
        val statusParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        statusParams.marginStart = views.dp(10)
        statusRow.addView(status, statusParams)
        statusRow.addView(time)
        statusCard.addView(title)
        statusCard.addView(meta, views.params(top = 4))
        statusCard.addView(statusRow, views.params(top = 12))
        content.addView(statusCard, views.params())

        val error = views.text("", 13f, R.color.cosmic_danger)
        content.addView(error, views.params(top = 10))

        content.addView(views.label("Steps"))
        val stepsCard = views.card()
        val steps = views.box()
        stepsCard.addView(steps)
        content.addView(stepsCard, views.params())

        content.addView(views.label("Log"))
        val scroll = ScrollView(this)
        scroll.setBackgroundResource(R.drawable.bg_log_box)
        scroll.setPadding(views.dp(14), views.dp(12), views.dp(14), views.dp(12))
        val log = views.mono()
        scroll.addView(log)
        scroll.setOnTouchListener { v, _ ->
            v.parent.requestDisallowInterceptTouchEvent(true)
            false
        }
        content.addView(scroll, views.params(height = views.dp(300)))

        val buttons = LinearLayout(this)
        buttons.orientation = LinearLayout.HORIZONTAL
        val stop = views.button("Stop", false) { stopRun() }
        val open = views.button("Open on GitHub", false) { openOnGithub() }
        val stopParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        stopParams.marginEnd = views.dp(6)
        val openParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        openParams.marginStart = views.dp(6)
        buttons.addView(stop, stopParams)
        buttons.addView(open, openParams)
        content.addView(buttons, views.params(top = 14))

        val results = views.box()
        content.addView(results, views.params())

        runTitleView = title
        runMetaView = meta
        runStatusView = status
        runTimeView = time
        runSpinner = spinner
        runErrorView = error
        stepsBox = steps
        logView = log
        logScroll = scroll
        stopButton = stop
        githubButton = open
        artifactBox = results
        setPage(scrolled(content))
        updateRunUi()
        refreshArtifacts()
    }

    private fun stepGlyph(status: String, conclusion: String): String {
        if (status == "completed") {
            return when (conclusion) {
                "success" -> "✓"
                "failure", "timed_out" -> "✕"
                "cancelled" -> "⊘"
                else -> "–"
            }
        }
        return if (status == "in_progress") "●" else "○"
    }

    private fun stepColor(status: String, conclusion: String): Int {
        if (status == "completed") {
            return when (conclusion) {
                "success" -> R.color.cosmic_mint_ink
                "failure", "timed_out" -> R.color.cosmic_danger
                else -> R.color.cosmic_muted
            }
        }
        return if (status == "in_progress") R.color.cosmic_primary else R.color.cosmic_muted
    }

    private fun updateRunUi() {
        if (page != PAGE_RUN) return
        val run = currentRun
        runTitleView?.text = if (run == null) {
            if (runError.isEmpty()) "Starting workflow..." else "Could not start the workflow"
        } else {
            "#${run.number}  ${run.title.ifEmpty { run.name }}"
        }
        runMetaView?.text = if (run == null) "$owner/$repo" else "${run.name}  •  ${run.branch}"
        runStatusView?.text = statusLabel(run)
        runStatusView?.setTextColor(views.res(statusColor(run)))
        val active = if (run == null) runError.isEmpty() else run.status != "completed"
        runSpinner?.visibility = if (active) View.VISIBLE else View.GONE
        if (run != null) {
            val end = if (run.status == "completed") run.updatedAt else System.currentTimeMillis()
            runTimeView?.text = GithubFormat.duration(end - run.startedAt)
        }
        runErrorView?.text = runError
        runErrorView?.visibility = if (runError.isEmpty()) View.GONE else View.VISIBLE
        stopButton?.visibility = if (run != null && run.status != "completed") View.VISIBLE else View.GONE
        githubButton?.visibility = if (run != null && run.htmlUrl.isNotEmpty()) View.VISIBLE else View.GONE

        val steps = stepsBox
        if (steps != null) {
            steps.removeAllViews()
            if (currentJobs.isEmpty()) {
                steps.addView(views.text("Waiting for a runner...", 13f, R.color.cosmic_muted))
            }
            for (job in currentJobs) {
                if (currentJobs.size > 1) {
                    val jobLine = views.text(stepGlyph(job.status, job.conclusion) + "  " + job.name, 14f, stepColor(job.status, job.conclusion), true)
                    steps.addView(jobLine, views.params(top = 8))
                }
                for (step in job.steps) {
                    val line = views.text(stepGlyph(step.status, step.conclusion) + "  " + step.name, 13f, stepColor(step.status, step.conclusion))
                    line.setPadding(if (currentJobs.size > 1) views.dp(16) else 0, 0, 0, 0)
                    steps.addView(line, views.params(top = 6))
                }
            }
        }

        val log = logView
        val scroll = logScroll
        if (log != null && scroll != null) {
            val child = scroll.getChildAt(0)
            val atBottom = child == null || child.bottom - (scroll.height + scroll.scrollY) < views.dp(48)
            val next = if (logText.isNotEmpty()) logText else if (run != null) "Waiting for log output..." else ""
            if (log.text.toString() != next) {
                log.text = next
                if (atBottom) scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
            }
        }
    }

    private fun stopRun() {
        val run = currentRun ?: return
        val ownerNow = owner
        val repoNow = repo
        stopButton?.text = "Stopping..."
        io.execute {
            try {
                api.cancel(ownerNow, repoNow, run.id)
            } catch (e: Exception) {
                main.post { toast(message(e)) }
            }
        }
    }

    private fun openOnGithub() {
        val run = currentRun ?: return
        if (run.htmlUrl.isEmpty()) return
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(run.htmlUrl)))
    }

    private fun refreshArtifacts() {
        if (page != PAGE_RUN) return
        val box = artifactBox ?: return
        box.removeAllViews()
        val run = currentRun ?: return
        if (run.status != "completed") return
        if (run.conclusion != "success") {
            val saved = savedLog
            if (saved != null) {
                box.addView(views.text("Log saved in Build Error Logs: ${saved.name}", 13f, R.color.cosmic_muted), views.params(top = 14))
            }
            return
        }
        box.addView(views.label("Result"))
        if (artifacts.isEmpty()) {
            box.addView(views.text("This run produced no files.", 13f, R.color.cosmic_muted))
            return
        }
        for (artifact in artifacts) box.addView(artifactRow(artifact), views.params(top = 8))
    }

    private fun artifactRow(artifact: ArtifactInfo): View {
        val card = views.card()
        card.addView(views.text(artifact.name, 15f, R.color.cosmic_ink, true))
        card.addView(views.text(GithubFormat.bytes(artifact.size), 13f, R.color.cosmic_muted), views.params(top = 2))
        if (artifact.expired) {
            card.addView(views.text("Expired on GitHub", 13f, R.color.cosmic_danger), views.params(top = 8))
            return card
        }
        val state = downloads[artifact.id]
        if (state != null && state.running) {
            val label = "Downloading ${GithubFormat.bytes(state.done)} / ${GithubFormat.bytes(state.total)}"
            card.addView(views.text(label, 13f, R.color.cosmic_primary), views.params(top = 8))
            card.addView(views.button("Cancel", false) { state.cancel = true }, views.params(top = 10))
            return card
        }
        val saved = state?.file
        if (state != null && saved != null) {
            card.addView(views.text("Saved in Github Application Zip: ${saved.name}", 13f, R.color.cosmic_mint_ink), views.params(top = 8))
            if (state.hasApk) card.addView(views.button("Install", true) { install(saved) }, views.params(top = 10))
            return card
        }
        if (state != null && state.failed.isNotEmpty()) {
            card.addView(views.text(state.failed, 13f, R.color.cosmic_danger), views.params(top = 8))
        }
        card.addView(views.button("Download", true) { startDownload(artifact) }, views.params(top = 10))
        return card
    }

    private fun startDownload(artifact: ArtifactInfo) {
        val run = currentRun ?: return
        val state = downloads.getOrPut(artifact.id) { DownloadState() }
        if (state.running) return
        state.running = true
        state.cancel = false
        state.failed = ""
        state.done = 0L
        state.total = artifact.size
        state.file = null
        val ownerNow = owner
        val repoNow = repo
        refreshArtifacts()
        io.execute {
            var lastPost = 0L
            try {
                val name = GithubFormat.safeName("$repoNow-run${run.number}-${artifact.name}") + ".zip"
                val target = File(StudioStorage.githubZipDir(this), name)
                api.downloadArtifact(ownerNow, repoNow, artifact.id, target, { done, total ->
                    state.done = done
                    if (total > 0) state.total = total
                    val now = System.currentTimeMillis()
                    if (now - lastPost > 300) {
                        lastPost = now
                        main.post { refreshArtifacts() }
                    }
                }, { state.cancel })
                state.hasApk = zipHasApk(target)
                state.file = target
            } catch (e: Exception) {
                state.failed = message(e)
            }
            state.running = false
            main.post { refreshArtifacts() }
        }
    }

    private fun zipHasApk(file: File): Boolean = try {
        ZipFile(file).use { zip -> zip.entries().asSequence().any { !it.isDirectory && it.name.endsWith(".apk", true) } }
    } catch (e: Exception) {
        false
    }

    private fun install(zip: File) {
        io.execute {
            try {
                val apk = extractApk(zip)
                main.post { launchInstall(apk) }
            } catch (e: Exception) {
                main.post { toast("Could not open the APK: " + (e.message ?: "unknown error")) }
            }
        }
    }

    private fun extractApk(zip: File): File {
        val dir = File(filesDir, "built-apks")
        dir.mkdirs()
        return ZipFile(zip).use { archive ->
            val entry = archive.entries().asSequence().firstOrNull { !it.isDirectory && it.name.endsWith(".apk", true) }
                ?: throw IOException("There is no APK inside the ZIP.")
            val out = File(dir, "github-" + File(entry.name).name)
            archive.getInputStream(entry).use { input -> out.outputStream().use { input.copyTo(it) } }
            out
        }
    }

    private fun launchInstall(apk: File) {
        if (!packageManager.canRequestPackageInstalls()) {
            pendingInstall = apk
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW)
        intent.setDataAndType(uri, "application/vnd.android.package-archive")
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }

    companion object {
        private const val PAGE_URL = 0
        private const val PAGE_SELECT = 1
        private const val PAGE_RUN = 2
    }
}
