package dev.seraph.manager

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Two tabs. Build: the config to build (see [ConfigSource]): a file picked wherever the user keeps it, a new one made
 * from the module's default, or a flake's address typed in; built with the module's build.sh (as root); and its folder
 * opened.
 * Updates: the config's apps that have a newer version, checked and installed with the module's update.sh.
 */
class MainActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var tabs: List<Pair<Button, View>>
    private lateinit var build: Button
    private lateinit var source: TextView
    private lateinit var lastBuild: TextView
    private lateinit var configButtons: List<Button>
    private lateinit var openFolder: Button
    private lateinit var output: TextView
    private lateinit var scroll: ScrollView
    private lateinit var updatesStatus: TextView
    private lateinit var checkUpdates: Button
    private lateinit var updateAll: Button
    private lateinit var updatesList: LinearLayout
    private lateinit var updatesOutput: TextView
    private lateinit var updatesScroll: ScrollView
    private var running = false
    private var currentSource: String? = null
    private var builtOn: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        build = findViewById(R.id.build)
        output = findViewById(R.id.output)
        scroll = findViewById(R.id.scroll)
        updatesStatus = findViewById(R.id.updates_status)
        checkUpdates = findViewById(R.id.check_updates)
        updateAll = findViewById(R.id.update_all)
        updatesList = findViewById(R.id.updates_list)
        updatesOutput = findViewById(R.id.updates_output)
        updatesScroll = findViewById(R.id.updates_scroll)
        tabs = listOf(
            findViewById<Button>(R.id.tab_build) to findViewById(R.id.build_pane),
            findViewById<Button>(R.id.tab_updates) to findViewById(R.id.updates_pane),
        )
        tabs.forEachIndexed { i, (button, _) -> button.setOnClickListener { select(i) } }
        build.setOnClickListener { build() }
        source = findViewById(R.id.source)
        lastBuild = findViewById(R.id.last_build)
        openFolder = findViewById(R.id.open_folder)
        openFolder.setOnClickListener { openFolder() }
        configButtons = listOf(
            findViewById<Button>(R.id.pick_file).apply { setOnClickListener { pickFile() } },
            findViewById<Button>(R.id.new_config).apply { setOnClickListener { newConfig() } },
            findViewById<Button>(R.id.type_source).apply { setOnClickListener { typeSource() } },
        )
        loadSource()
        checkUpdates.setOnClickListener {
            runRoot(Root.MODULE + "sh \$d/update.sh app check 2>&1", R.string.checking, updatesOutput, updatesScroll) { loadUpdates() }
        }
        updateAll.setOnClickListener { update(emptyList()) }
        padForSystemBars(findViewById(R.id.root))

        Notifications.channels(this)
        UpdateJob.schedule(this)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        showTab(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        showTab(intent)
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }

    /** A notification opens the Updates tab. */
    private fun showTab(intent: Intent?) = select(if (intent?.getStringExtra(EXTRA_TAB) == TAB_UPDATES) 1 else 0)

    private fun select(index: Int) {
        tabs.forEachIndexed { i, (button, pane) ->
            pane.visibility = if (i == index) View.VISIBLE else View.GONE
            button.setTypeface(null, if (i == index) Typeface.BOLD else Typeface.NORMAL)
            button.alpha = if (i == index) 1f else 0.6f
        }
        if (index == 1) loadUpdates()
    }

    /** Runs a module script as root and shows what it says, line by line, then [after]. */
    private fun runRoot(command: String, starting: Int, out: TextView, scroller: ScrollView, after: () -> Unit = {}) {
        if (running) return
        setRunning(true)
        out.text = ""
        show(out, scroller, getString(starting))
        worker.execute {
            val exit = Root.run(command) { line -> runOnUiThread { show(out, scroller, line) } }
            runOnUiThread {
                show(out, scroller, when (exit) {
                    null -> getString(R.string.no_root)
                    0 -> getString(R.string.done)
                    else -> getString(R.string.failed, exit)
                })
                setRunning(false)
                after()
            }
        }
    }

    private fun setRunning(on: Boolean) {
        running = on
        build.isEnabled = !on
        configButtons.forEach { it.isEnabled = !on }
        checkUpdates.isEnabled = !on
        updateAll.isEnabled = !on
        for (i in 0 until updatesList.childCount) updatesList.getChildAt(i).findViewWithTag<Button>(UPDATE_BUTTON)?.isEnabled = !on
    }

    private fun show(out: TextView, scroller: ScrollView, line: String) {
        out.append(line + "\n")
        scroller.post { scroller.fullScroll(View.FOCUS_DOWN) }
    }

    // --- The config: kept by the module in /data/adb/seraph/source (see ConfigSource).

    /** The config a build reads (as the module's config_source finds it, so a phone set up before it could be picked shows its own), and the last build's. */
    private fun loadSource() {
        worker.execute {
            val said = mutableMapOf<String, String>()
            Root.run(
                Root.MODULE + "if [ -f \$d/common.sh ]; then MODDIR=\$d; . \$d/common.sh > /dev/null 2>&1; s=\$(config_source); " +
                    "else s=\$(head -n 1 ${ConfigSource.FILE} 2>/dev/null); fi; echo \"source=\$s\"; " +
                    "g=\$(cat /data/adb/seraph/generations/last 2>/dev/null); echo \"generation=\$g\"; " +
                    "[ -n \"\$g\" ] && echo \"built=\$(head -n 1 /data/adb/seraph/generations/\$g/source 2>/dev/null)\" && " +
                    "echo \"on=\$(head -n 1 /data/adb/seraph/generations/\$g/built-on 2>/dev/null)\"",
            ) { line -> line.indexOf('=').takeIf { it > 0 }?.let { said[line.substring(0, it)] = line.substring(it + 1) } }
            runOnUiThread { showSource(said["source"].orEmpty(), said["generation"].orEmpty(), said["built"].orEmpty(), said["on"].orEmpty()) }
        }
    }

    private fun showSource(picked: String, generation: String, built: String, on: String) {
        currentSource = picked.ifEmpty { null }
        builtOn = on.ifEmpty { null }
        val storage = getString(R.string.phone_storage)
        source.text = when {
            picked.isEmpty() -> getString(R.string.source_none)
            !ConfigSource.isFile(picked) -> getString(R.string.source_address, picked)
            picked.substringBefore('#').endsWith("/flake.nix") -> getString(R.string.source_flake, ConfigSource.shown(picked, storage))
            else -> getString(R.string.source_file, ConfigSource.shown(picked, storage))
        }
        lastBuild.text = when {
            generation.isEmpty() || generation == "0" -> getString(R.string.no_build_yet)
            built.isEmpty() -> getString(R.string.last_build_unknown, generation)
            on.isNotEmpty() -> getString(R.string.last_build_on, generation, built, on)
            else -> getString(R.string.last_build, generation, ConfigSource.shown(built, storage))
        }
        openFolder.isEnabled = currentSource?.let { ConfigSource.folderDocument(it) } != null
    }

    /**
     * Build: the picked config, built on the phone. When the last build came from a PC (seraph-rebuild), whose config
     * the phone may not have, it can instead be applied again as it is.
     */
    private fun build() {
        val pc = builtOn
        val buildHere = { runRoot(Root.MODULE + "sh \$d/build.sh app 2>&1", R.string.building, output, scroll) { loadSource() } }
        if (pc == null) {
            buildHere()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.built_elsewhere_title, pc))
            .setMessage(R.string.built_elsewhere_message)
            .setPositiveButton(R.string.apply_again) { _, _ ->
                runRoot(Root.MODULE + "sh \$d/build.sh app again 2>&1", R.string.building, output, scroll) { loadSource() }
            }
            .setNeutralButton(R.string.build_here) { _, _ -> buildHere() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Pick file: the system's file picker, for a configuration.nix or a flake.nix wherever it is. */
    private fun pickFile() = startForResult(
        Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"),
        PICK_FILE,
    )

    /** New: the system's file picker makes a configuration.nix where the user says, with the module's default config in it. */
    private fun newConfig() = startForResult(
        Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            // octet-stream: the picker keeps the name as it is (text/plain would add .txt).
            .setType("application/octet-stream").putExtra(Intent.EXTRA_TITLE, "configuration.nix"),
        NEW_CONFIG,
    )

    private fun startForResult(intent: Intent, request: Int) = try {
        startActivityForResult(intent, request)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(this, R.string.no_folder_app, Toast.LENGTH_LONG).show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) return
        when (requestCode) {
            PICK_FILE -> savePicked(uri)
            NEW_CONFIG -> writeNew(uri)
        }
    }

    /** A picked document as the path root reads it at, kept as the config. */
    private fun savePicked(uri: Uri) {
        worker.execute {
            val path = ConfigSource.pathOf(uri) ?: ConfigSource.mediaId(uri)?.let { id ->
                val said = StringBuilder()
                Root.run(ConfigSource.mediaPathCommand(id)) { said.append(it).append('\n') }
                ConfigSource.mediaPathIn(said.toString())
            }
            runOnUiThread {
                when {
                    path == null -> toast(getString(R.string.not_a_phone_file))
                    !path.endsWith(".nix") -> toast(getString(R.string.not_nix))
                    else -> saveSource(path)
                }
            }
        }
    }

    /** The module's default config, written into the new document, which then is the config. */
    private fun writeNew(uri: Uri) {
        worker.execute {
            val template = StringBuilder()
            val exit = Root.run(Root.MODULE + "cat \$d/config/configuration.nix") { template.append(it).append('\n') }
            val written = exit == 0 && template.isNotEmpty() && try {
                contentResolver.openOutputStream(uri, "wt")?.use { it.write(template.toString().toByteArray()) } != null
            } catch (e: Exception) {
                false
            }
            runOnUiThread {
                when {
                    exit != 0 || template.isEmpty() -> toast(getString(R.string.no_template))
                    !written -> toast(getString(R.string.not_written))
                    else -> savePicked(uri)
                }
            }
        }
    }

    /** Type it: a flake's address (github:you/phone), or a file's path, either with #name; what is there now to edit. */
    private fun typeSource() {
        val field = EditText(this).apply {
            setSingleLine()
            hint = getString(R.string.type_source_hint)
            setText(currentSource.orEmpty())
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val box = FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(field)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.type_source_title)
            .setView(box)
            .setPositiveButton(R.string.ok) { _, _ -> saveSource(field.text.toString().trim()) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Keeps [picked] as the config, once root can read a file of it; the module's build reads it from then on. */
    private fun saveSource(picked: String) {
        if (!ConfigSource.isValid(picked)) {
            toast(getString(R.string.source_invalid))
            return
        }
        val where = picked.substringBefore('#')
        val check = if (ConfigSource.isFile(picked)) {
            val card = if (where.startsWith("/storage/") && !where.startsWith("/storage/emulated/")) "/mnt/media_rw/" + where.removePrefix("/storage/") else where
            "{ [ -f ${ConfigSource.quote(where)} ] || [ -f ${ConfigSource.quote(card)} ]; } || exit 3; "
        } else {
            ""
        }
        worker.execute {
            val exit = Root.run(
                check + "mkdir -p /data/adb/seraph && printf '%s\\n' ${ConfigSource.quote(picked)} > ${ConfigSource.FILE} && rm -f /data/adb/seraph/build-from",
            )
            runOnUiThread {
                when (exit) {
                    0 -> loadSource()
                    3 -> toast(getString(R.string.not_readable, where))
                    else -> toast(getString(R.string.no_root))
                }
            }
        }
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    // --- Updates.

    private fun update(ids: List<String>) {
        // The names come from updates.json; only package names go into the command.
        val apps = ids.filter { PACKAGE_NAME.matches(it) }.joinToString(" ")
        runRoot(Root.MODULE + "sh \$d/update.sh app now $apps 2>&1", R.string.updating, updatesOutput, updatesScroll) { loadUpdates() }
    }

    /** The last check's result (updates.json, kept by the module), read as root. */
    private fun loadUpdates() {
        worker.execute {
            val text = StringBuilder()
            Root.run("cat /data/adb/seraph/updates.json 2>/dev/null") { text.append(it) }
            runOnUiThread { showUpdates(text.toString()) }
        }
    }

    private fun showUpdates(saved: String) {
        updatesList.removeAllViews()
        val json = try {
            JSONObject(saved)
        } catch (e: Exception) {
            updatesStatus.setText(R.string.updates_not_checked)
            updateAll.isEnabled = false
            return
        }
        val checked = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(Date(json.optLong("checked")))
        val auto = json.optJSONObject("autoUpdate")
        val repos = auto?.keys()?.asSequence()?.joinToString(", ") { repo ->
            getString(R.string.auto_repo, repo, getString(if (auto.optBoolean(repo)) R.string.auto_on else R.string.auto_off))
        } ?: ""
        updatesStatus.text = getString(R.string.updates_status, checked, repos)
        val updates = json.optJSONArray("updates")
        if (updates == null || updates.length() == 0) {
            updatesList.addView(TextView(this).apply { setText(R.string.up_to_date) })
            updateAll.isEnabled = false
            return
        }
        for (i in 0 until updates.length()) {
            val update = updates.getJSONObject(i)
            val id = update.getString("id")
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 8, 0, 8)
            }
            row.addView(
                TextView(this).apply {
                    text = getString(
                        R.string.update_line, update.getString("name"), update.getString("from"), update.getString("to"),
                        size(update.getLong("size")), update.optString("repo"),
                    )
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                },
            )
            row.addView(
                Button(this).apply {
                    setText(R.string.update)
                    tag = UPDATE_BUTTON
                    isEnabled = !running
                    setOnClickListener { update(listOf(id)) }
                },
            )
            updatesList.addView(row)
        }
        updateAll.isEnabled = !running
    }

    private fun size(bytes: Long): String = when {
        bytes < 0 -> getString(R.string.size_unknown)
        bytes >= 1L shl 20 -> String.format(Locale.ROOT, "%.1f MiB", bytes / 1048576.0)
        else -> "${(bytes + 1023) / 1024} KiB"
    }

    // --- The config's folder.

    /** Opens the folder of the picked config in the app the user picks: the Files app or another file manager. */
    private fun openFolder() {
        val folder = currentSource?.let { ConfigSource.folderDocument(it) } ?: return
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(folder, DocumentsContract.Document.MIME_TYPE_DIR)
        if (view.resolveActivity(packageManager) == null) {
            Toast.makeText(this, R.string.no_folder_app, Toast.LENGTH_LONG).show()
            return
        }
        startActivity(Intent.createChooser(view, getString(R.string.open_folder_with)))
    }

    /** The app draws behind the status and navigation bars (edge to edge), so its content keeps clear of them. */
    private fun padForSystemBars(root: View) {
        val pad = root.paddingLeft
        root.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            view.setPadding(pad + bars.left, pad + bars.top, pad + bars.right, pad + bars.bottom)
            insets
        }
    }

    companion object {
        const val EXTRA_TAB = "tab"
        const val TAB_UPDATES = "updates"
        private const val UPDATE_BUTTON = "update"
        private const val PICK_FILE = 1
        private const val NEW_CONFIG = 2
        private val PACKAGE_NAME = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
    }
}
