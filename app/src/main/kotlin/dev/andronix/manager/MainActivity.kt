package dev.andronix.manager

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.IOException
import java.util.concurrent.Executors

/** Builds the phone config with the Magisk module's build.sh (as root), and opens the config folder. */
class MainActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var build: Button
    private lateinit var output: TextView
    private lateinit var scroll: ScrollView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        build = findViewById(R.id.build)
        output = findViewById(R.id.output)
        scroll = findViewById(R.id.scroll)
        build.setOnClickListener { build() }
        findViewById<Button>(R.id.open_folder).setOnClickListener { openFolder() }
        padForSystemBars(findViewById(R.id.root))
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }

    /** Runs build.sh as root and shows what it says, line by line. */
    private fun build() {
        build.isEnabled = false
        output.text = ""
        show(getString(R.string.building))
        worker.execute {
            val result = try {
                val process = ProcessBuilder("su", "-c", BUILD).redirectErrorStream(true).start()
                process.inputStream.bufferedReader().forEachLine { line -> runOnUiThread { show(line) } }
                val exit = process.waitFor()
                if (exit == 0) getString(R.string.done) else getString(R.string.failed, exit)
            } catch (e: IOException) {
                getString(R.string.no_root)
            }
            runOnUiThread {
                show(result)
                build.isEnabled = true
            }
        }
    }

    private fun show(line: String) {
        output.append(line + "\n")
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    /** Opens /sdcard/Andronix in the app the user picks: the Files app or another file manager. */
    private fun openFolder() {
        val folder = DocumentsContract.buildDocumentUri(STORAGE_DOCUMENTS, "primary:Andronix")
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
        private const val STORAGE_DOCUMENTS = "com.android.externalstorage.documents"

        /** The newest build.sh: the module's update waiting for a restart, else the running module. */
        private const val BUILD = "d=/data/adb/modules_update/andronix; [ -f \$d/build.sh ] || d=/data/adb/modules/andronix; " +
            "sh \$d/build.sh app 2>&1"
    }
}
