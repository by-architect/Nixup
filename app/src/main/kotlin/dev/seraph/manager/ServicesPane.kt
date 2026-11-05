package dev.seraph.manager

import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject

/**
 * The Services tab: the last build's services (its systemd.services units: services.tailscale, services.openssh, the
 * config's own), how each is, and a start, stop or restart by hand, as systemctl does, which lasts until the next
 * build or restart; and each one's log. Its search box keeps the services with what is typed in their name, what they
 * are, or how they are ("failed", "running"). Through the module's units.sh, as root.
 */
class ServicesPane(private val activity: MainActivity, root: View, private val runner: Runner) {
    private val list: LinearLayout = root.findViewById(R.id.services_list)
    private val output: TextView = root.findViewById(R.id.services_output)
    private val scroll: ScrollView = root.findViewById(R.id.services_scroll)
    /** The rows' start, stop and restart buttons, which wait while a script runs (a log can always be read). */
    private val actions = mutableListOf<Button>()
    /** The services last read, each as units.sh says it. */
    private var units: List<JSONObject> = emptyList()
    private var query = ""

    init {
        Ui.onSearch(root.findViewById(R.id.services_search)) {
            query = it
            show()
        }
    }

    /** What the tab shows, read again. */
    fun load() {
        runner.read(Root.MODULE + "sh \$d/units.sh app list --json 2>&1") { exit, said ->
            val json = Runner.json(said)?.optJSONArray("units")
            units = json?.let { array -> (0 until array.length()).map { array.getJSONObject(it) } } ?: emptyList()
            when {
                exit == null -> problem(activity.getString(R.string.no_root))
                json == null -> problem(Runner.lastLine(said))
                units.isEmpty() -> problem(activity.getString(R.string.services_none))
                else -> show()
            }
        }
    }

    private fun problem(text: String) {
        list.removeAllViews()
        actions.clear()
        list.addView(Ui.note(activity, text))
    }

    /** The services, those with what the search box has. */
    private fun show() {
        if (units.isEmpty()) return
        list.removeAllViews()
        actions.clear()
        val found = units.filter { Ui.matches(query, it.getString("name"), it.optString("description"), line(it)) }
        if (found.isEmpty()) list.addView(Ui.noMatch(activity, query))
        found.forEach { list.addView(row(it)) }
        setRunning(runner.running)
    }

    /** How a service is: running since when, failed, ...; and its timer's next run. */
    private fun line(unit: JSONObject): String {
        val state = unit.optString("state")
        val since = unit.optLong("since")
        val shown = activity.getString(
            when (state) {
                "running" -> R.string.state_running
                "listening" -> R.string.state_listening
                "restarting" -> R.string.state_restarting
                "waiting" -> R.string.state_waiting
                "done" -> R.string.state_done
                "failed" -> R.string.state_failed
                "not run" -> R.string.state_not_run
                "skipped" -> R.string.state_skipped
                else -> R.string.state_stopped
            },
        )
        return (if (since > 0 && state != "stopped" && state != "not run") activity.getString(R.string.service_state_since, shown, Ui.ago(since)) else shown) +
            (if (!unit.optBoolean("wanted", true) && !unit.has("timer") && !unit.has("path")) activity.getString(R.string.state_not_wanted) else "") +
            (unit.optLong("next").takeIf { it > 0 }?.let { activity.getString(R.string.state_next_run, Ui.date(it)) } ?: "")
    }

    private fun row(unit: JSONObject): View {
        val name = unit.getString("name")
        val state = unit.optString("state")
        val once = unit.optString("type") == "oneshot"
        val line = line(unit)
        val buttons = if (once) {
            listOf(Ui.button(activity, activity.getString(R.string.service_run)) { act("start", name, R.string.service_run) })
        } else {
            listOf(
                Ui.button(activity, activity.getString(R.string.service_restart)) { act("restart", name, R.string.service_restart) },
                if (state == "running" || state == "restarting" || state == "waiting") Ui.button(activity, activity.getString(R.string.service_stop)) { act("stop", name, R.string.service_stop) }
                else Ui.button(activity, activity.getString(R.string.service_start)) { act("start", name, R.string.service_start) },
            )
        }
        actions += buttons
        val log = Ui.button(activity, activity.getString(R.string.service_log)) { log(name) }
        return Ui.row(activity, name, listOf(line, unit.optString("description")).filter { it.isNotBlank() }.joinToString("\n"), buttons + log, below = true)
    }

    private fun act(action: String, name: String, label: Int) {
        runner.run(
            Root.MODULE + "sh \$d/units.sh app $action ${ConfigSource.quote(name)} 2>&1",
            activity.getString(R.string.service_acting, name, activity.getString(label).lowercase()), output, scroll,
        ) { load() }
    }

    private fun log(name: String) {
        val show = Ui.textDialog(activity, name, activity.getString(R.string.reading))
        runner.read(Root.MODULE + "sh \$d/units.sh app log ${ConfigSource.quote(name)} 300 2>&1") { exit, said ->
            show(if (exit == null) activity.getString(R.string.no_root) else said.ifBlank { activity.getString(R.string.log_empty) })
        }
    }

    fun setRunning(on: Boolean) = actions.forEach { it.isEnabled = !on }
}
