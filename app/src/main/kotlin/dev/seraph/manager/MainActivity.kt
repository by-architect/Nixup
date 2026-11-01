package dev.seraph.manager

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.View
import android.view.WindowInsets
import android.widget.Button
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
 * Two tabs. Build: builds the phone config with the module's build.sh (as root), and opens the config folder.
 * Updates: the config's apps that have a newer version, checked and installed with the module's update.sh.
 */
class MainActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var tabs: List<Pair<Button, View>>
    private lateinit var build: Button
    private lateinit var output: TextView
    private lateinit var scroll: ScrollView
    private lateinit var updatesStatus: TextView
    private lateinit var checkUpdates: Button
    private lateinit var updateAll: Button
    private lateinit var updatesList: LinearLayout
    private lateinit var updatesOutput: TextView
    private lateinit var updatesScroll: ScrollView
    private var running = false

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
        build.setOnClickListener { runRoot(Root.MODULE + "sh \$d/build.sh app 2>&1", R.string.building, output, scroll) }
        findViewById<Button>(R.id.open_folder).setOnClickListener { openFolder() }
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
        checkUpdates.isEnabled = !on
        updateAll.isEnabled = !on
        for (i in 0 until updatesList.childCount) updatesList.getChildAt(i).findViewWithTag<Button>(UPDATE_BUTTON)?.isEnabled = !on
    }

    private fun show(out: TextView, scroller: ScrollView, line: String) {
        out.append(line + "\n")
        scroller.post { scroller.fullScroll(View.FOCUS_DOWN) }
    }

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

    // --- The config folder.

    /** Opens /sdcard/Seraph in the app the user picks: the Files app or another file manager. */
    private fun openFolder() {
        val folder = DocumentsContract.buildDocumentUri(STORAGE_DOCUMENTS, "primary:Seraph")
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
        private const val STORAGE_DOCUMENTS = "com.android.externalstorage.documents"
        private val PACKAGE_NAME = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
    }
}
