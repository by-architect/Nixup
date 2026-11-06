package dev.nixup.manager

import android.app.Activity
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * The module's scripts, run as root for the tabs: one at a time, with what it says shown line by line in the tab's
 * output, while the tabs' buttons wait ([changed]); and quiet reads of what the tabs show, on a thread of their own,
 * so a long build does not hold them up.
 */
class Runner(private val activity: Activity, private val changed: (Boolean) -> Unit) {
    private val worker = Executors.newSingleThreadExecutor()
    private val reader = Executors.newSingleThreadExecutor()

    /** Whether a script is running. */
    var running = false
        private set

    /** Runs [command] as root and shows what it says in [out], line by line, and how it ended; then [after], with its exit status. */
    fun run(command: String, starting: String, out: TextView, scroller: ScrollView, after: (Int?) -> Unit = {}) {
        if (running) return
        running = true
        changed(true)
        out.text = ""
        show(out, scroller, starting)
        worker.execute {
            val exit = Root.run(command) { line -> activity.runOnUiThread { show(out, scroller, line) } }
            activity.runOnUiThread {
                show(out, scroller, when (exit) {
                    null -> activity.getString(R.string.no_root)
                    0 -> activity.getString(R.string.done)
                    else -> activity.getString(R.string.failed, exit)
                })
                running = false
                changed(false)
                if (!activity.isDestroyed) after(exit)
            }
        }
    }

    /** Runs [command] as root without showing it ([input]: what it reads): its exit status (null without root) and what it said go to [done]. */
    fun read(command: String, input: String? = null, done: (Int?, String) -> Unit) {
        reader.execute {
            val said = StringBuilder()
            val exit = Root.run(command, input) { said.append(it).append('\n') }
            activity.runOnUiThread { if (!activity.isDestroyed) done(exit, said.toString()) }
        }
    }

    /** Other work off the screen's thread (writing a picked file, reading the apps). */
    fun background(work: () -> Unit) = reader.execute(work)

    fun close() {
        worker.shutdown()
        reader.shutdown()
    }

    private fun show(out: TextView, scroller: ScrollView, line: String) {
        out.append(line + "\n")
        scroller.post { scroller.fullScroll(View.FOCUS_DOWN) }
    }

    companion object {
        /** The engine's answer to the app: the last line of JSON it printed. */
        fun json(said: String): JSONObject? = said.lineSequence().map { it.trim() }.lastOrNull { it.startsWith("{") }
            ?.let { runCatching { JSONObject(it) }.getOrNull() }

        /** What a script said last: why it gave no answer. */
        fun lastLine(said: String): String = said.lineSequence().map { it.trim() }.lastOrNull { it.isNotEmpty() }?.removePrefix("✘ ").orEmpty()
    }
}
