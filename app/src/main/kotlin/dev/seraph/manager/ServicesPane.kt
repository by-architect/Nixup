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
 * build or restart; and each one's log. Through the module's units.sh, as root.
 */
class ServicesPane(private val activity: MainActivity, root: View, private val runner: Runner) {
    private val list: LinearLayout = root.findViewById(R.id.services_list)
    private val output: TextView = root.findViewById(R.id.services_output)
    private val scroll: ScrollView = root.findViewById(R.id.services_scroll)
    /** The rows' start, stop and restart buttons, which wait while a script runs (a log can always be read). */
    private val actions = mutableListOf<Button>()

    /** What the tab shows, read again. */
    fun load() {
        runner.read(Root.MODULE + "sh \$d/units.sh app list --json 2>&1") { exit, said ->
            val units = Runner.json(said)?.optJSONArray("units")
            list.removeAllViews()
            actions.clear()
            when {
                exit == null -> list.addView(Ui.note(activity, activity.getString(R.string.no_root)))
                units == null -> list.addView(Ui.note(activity, Runner.lastLine(said)))
                units.length() == 0 -> list.addView(Ui.note(activity, activity.getString(R.string.services_none)))
                else -> for (i in 0 until units.length()) list.addView(row(units.getJSONObject(i)))
            }
            setRunning(runner.running)
        }
    }

    private fun row(unit: JSONObject): View {
        val name = unit.getString("name")
        val state = unit.optString("state")
        val once = unit.optString("type") == "oneshot"
        val since = unit.optLong("since")
        val shown = activity.getString(
            when (state) {
                "running" -> R.string.state_running
                "restarting" -> R.string.state_restarting
                "done" -> R.string.state_done
                "failed" -> R.string.state_failed
                "not run" -> R.string.state_not_run
                else -> R.string.state_stopped
            },
        )
        val line = (if (since > 0 && state != "stopped" && state != "not run") activity.getString(R.string.service_state_since, shown, Ui.ago(since)) else shown) +
            (if (!unit.optBoolean("wanted", true)) activity.getString(R.string.state_not_wanted) else "")
        val buttons = if (once) {
            listOf(Ui.button(activity, activity.getString(R.string.service_run)) { act("start", name, R.string.service_run) })
        } else {
            listOf(
                Ui.button(activity, activity.getString(R.string.service_restart)) { act("restart", name, R.string.service_restart) },
                if (state == "running" || state == "restarting") Ui.button(activity, activity.getString(R.string.service_stop)) { act("stop", name, R.string.service_stop) }
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
