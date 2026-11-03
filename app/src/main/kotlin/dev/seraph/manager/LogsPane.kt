package dev.seraph.manager

import android.app.AlertDialog
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.format.DateUtils
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The Logs tab: what Seraph did and what happened, newest first, by day, from its journal of events (see the module's
 * notify.sh): each build, update, backup and git pull as one entry with how it ended and how long it took, each
 * service that stopped or ran again, and Tailscale's connection. A filter shows one kind, or the problems alone; an
 * entry opens its own log (the run's, or the service's); Files opens the logs themselves.
 */
class LogsPane(private val activity: MainActivity, root: View, private val runner: Runner) {
    private val filters: LinearLayout = root.findViewById(R.id.logs_filters)
    private val status: TextView = root.findViewById(R.id.logs_status)
    private val list: LinearLayout = root.findViewById(R.id.logs_list)
    private var events: List<Shown> = emptyList()
    private var filter = 0

    /**
     * An entry: its event, how long its run took (its start and end are one entry), and for a start with no end,
     * whether it is still running, or was broken off (a restart, a kill: something newer of the same kind came since,
     * or it has gone on for 3 hours).
     */
    private class Shown(val event: Event, val took: Long?, val running: Boolean, val brokenOff: Boolean = false)

    private val kinds = listOf(
        R.string.logs_all to { _: Event -> true },
        R.string.logs_builds to { e: Event -> e.kind == "build" || e.kind == "git" },
        R.string.logs_updates to { e: Event -> e.kind == "update" },
        R.string.logs_backups to { e: Event -> e.kind == "backup" },
        R.string.logs_services to { e: Event -> e.kind == "service" || e.kind == "connection" },
        R.string.logs_problems to { e: Event -> e.problem },
    )

    init {
        kinds.forEachIndexed { i, (label, _) ->
            filters.addView(Ui.button(activity, activity.getString(label)) { filter = i; show() })
        }
        root.findViewById<Button>(R.id.logs_refresh).setOnClickListener { load() }
        root.findViewById<Button>(R.id.logs_files).setOnClickListener { files() }
    }

    fun load() {
        runner.read("tail -n 800 $EVENTS 2>/dev/null") { exit, said ->
            if (exit == null) {
                list.removeAllViews()
                list.addView(Ui.note(activity, activity.getString(R.string.no_root)))
                return@read
            }
            events = pair(said.lineSequence().mapNotNull { Event.of(it.trim()) }.toList())
            show()
        }
    }

    /** A run's start and its end as one entry, with how long it took; a start with no end yet is running. */
    private fun pair(all: List<Event>): List<Shown> {
        val ends = all.filter { it.level != "start" && it.run.isNotEmpty() }.groupBy { it.run }
        val starts = all.filter { it.level == "start" && it.run.isNotEmpty() }.associateBy { it.run }
        val newest = all.groupBy { it.key }.mapValues { (_, events) -> events.last() }
        return all.mapNotNull { event ->
            when {
                event.level == "start" -> if (event.run.isNotEmpty() && ends.containsKey(event.run)) null else {
                    val broken = newest[event.key] !== event || System.currentTimeMillis() - event.time > 3 * DateUtils.HOUR_IN_MILLIS
                    Shown(event, null, !broken, brokenOff = broken)
                }
                else -> Shown(event, starts[event.run]?.takeIf { event.run.isNotEmpty() && ends[event.run]?.last() === event }?.let { event.time - it.time }, false)
            }
        }.reversed()
    }

    private fun show() {
        filters.childCount.let { count ->
            for (i in 0 until count) (filters.getChildAt(i) as Button).apply {
                setTypeface(null, if (i == filter) Typeface.BOLD else Typeface.NORMAL)
                alpha = if (i == filter) 1f else 0.6f
            }
        }
        list.removeAllViews()
        val keep = kinds[filter].second
        val shown = events.filter { keep(it.event) }
        val problems = events.count { it.event.problem && System.currentTimeMillis() - it.event.time < DateUtils.DAY_IN_MILLIS }
        status.text = when {
            events.isEmpty() -> activity.getString(R.string.logs_none)
            problems > 0 -> activity.resources.getQuantityString(R.plurals.logs_problems_today, problems, problems)
            else -> activity.getString(R.string.logs_fine)
        }
        if (shown.isEmpty() && events.isNotEmpty()) list.addView(Ui.note(activity, activity.getString(R.string.logs_none_here)))
        var day = ""
        for (entry in shown) {
            val thisDay = Logs.day(activity, entry.event.time)
            if (thisDay != day) {
                day = thisDay
                list.addView(Ui.heading(activity, day))
            }
            list.addView(row(entry))
        }
    }

    private fun row(entry: Shown): View {
        val event = entry.event
        val context: Context = activity
        val dot = TextView(context).apply {
            text = if (entry.brokenOff) "!" else Logs.symbol(event.level, entry.running)
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            setTypeface(typeface, Typeface.BOLD)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(context.getColor(Logs.color(if (entry.brokenOff) "warn" else if (entry.running) "start" else event.level)))
            }
        }
        val size = Ui.dp(context, 28)
        val texts = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply {
                text = event.title
                setTextAppearance(android.R.style.TextAppearance_DeviceDefault_Medium)
            })
            if (event.text.isNotEmpty()) addView(TextView(context).apply {
                text = event.text
                maxLines = 3
                ellipsize = TextUtils.TruncateAt.END
                setTextAppearance(android.R.style.TextAppearance_DeviceDefault_Small)
            })
            addView(TextView(context).apply {
                text = if (entry.brokenOff) Logs.meta(context, event, null, false) + " · " + context.getString(R.string.logs_broken_off)
                else Logs.meta(context, event, entry.took, entry.running)
                alpha = 0.7f
                setTextAppearance(android.R.style.TextAppearance_DeviceDefault_Small)
            })
        }
        val time = TextView(context).apply {
            text = Logs.clock(event.time)
            alpha = 0.7f
            setTextAppearance(android.R.style.TextAppearance_DeviceDefault_Small)
        }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, Ui.dp(context, 8), 0, Ui.dp(context, 8))
            addView(dot, LinearLayout.LayoutParams(size, size).apply { marginEnd = Ui.dp(context, 12); topMargin = Ui.dp(context, 2) })
            addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(time, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = Ui.dp(context, 8) })
            isClickable = true
            isFocusable = true
            context.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground)).let {
                background = it.getDrawable(0)
                it.recycle()
            }
            setOnClickListener { details(entry) }
        }
        return row
    }

    /** An entry in full: when, who started it, how long it took; and its log. */
    private fun details(entry: Shown) {
        val event = entry.event
        val lines = listOfNotNull(
            event.text.takeIf { it.isNotEmpty() },
            "",
            activity.getString(R.string.logs_when, SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date(event.time))),
            Logs.by(activity, event.by)?.let { activity.getString(R.string.logs_by, it) },
            entry.took?.let { activity.getString(R.string.logs_took, Logs.duration(it)) },
            activity.getString(R.string.logs_kind, Logs.kind(activity, event.kind)),
        )
        val log = logOf(event)
        val dialog = AlertDialog.Builder(activity)
            .setTitle(event.title)
            .setMessage(lines.joinToString("\n").trim())
            .setPositiveButton(R.string.close, null)
        if (log != null) dialog.setNeutralButton(R.string.logs_show_log) { _, _ -> open(log.first, log.second) }
        dialog.show()
    }

    /** The log of an entry: its run's, else its service's. */
    private fun logOf(event: Event): Pair<String, String>? = when {
        RUN.matches(event.run) -> event.title to "$LOGS/${event.run}.log"
        event.key.startsWith("service:") && UNIT.matches(event.key.removePrefix("service:")) ->
            event.key.removePrefix("service:").let { it to "/data/adb/seraph/units/$it/log" }
        event.kind == "connection" -> "tailscaled-watch" to "/data/adb/seraph/units/tailscaled-watch/log"
        else -> null
    }

    private fun open(title: String, path: String) {
        val show = Ui.textDialog(activity, title, activity.getString(R.string.reading), colored = true)
        runner.read("tail -n 3000 ${ConfigSource.quote(path)} 2>&1") { exit, said ->
            show(if (exit == null) activity.getString(R.string.no_root) else said.ifBlank { activity.getString(R.string.log_empty) })
        }
    }

    /** The logs themselves: the module's (every build, update and backup) and each service's. */
    private fun files() {
        runner.read(
            "for f in /data/adb/seraph/apply.log /data/adb/seraph/apply.log.old /data/adb/seraph/units/*/log; do " +
                "[ -f \"\$f\" ] && printf '%s\\t%s\\t%s\\n' \"\$f\" \"\$(stat -c %s \"\$f\")\" \"\$(stat -c %Y \"\$f\")\"; done",
        ) { exit, said ->
            if (exit == null) return@read
            val found = said.lineSequence().map { it.split('\t') }.filter { it.size == 3 }.toList()
            if (found.isEmpty()) {
                Ui.textDialog(activity, activity.getString(R.string.logs_files), activity.getString(R.string.log_empty))
                return@read
            }
            val names = found.map { (path, size, time) ->
                val name = when {
                    path.endsWith("/apply.log") -> activity.getString(R.string.logs_module_log)
                    path.endsWith("/apply.log.old") -> activity.getString(R.string.logs_module_log_old)
                    else -> activity.getString(R.string.logs_service_log, path.removePrefix("/data/adb/seraph/units/").removeSuffix("/log"))
                }
                activity.getString(R.string.logs_file_item, name, Logs.size(size.toLongOrNull() ?: 0), Ui.ago((time.toLongOrNull() ?: 0) * 1000))
            }
            AlertDialog.Builder(activity)
                .setTitle(R.string.logs_files)
                .setItems(names.toTypedArray()) { _, i -> open(names[i].substringBefore('\n'), found[i][0]) }
                .setNegativeButton(R.string.close, null)
                .show()
        }
    }

    companion object {
        private const val EVENTS = "/data/adb/seraph/events"
        private const val LOGS = "/data/adb/seraph/logs"
        private val RUN = Regex("[0-9]{8}-[0-9]{6}-[a-z]+-[0-9]+")
        private val UNIT = Regex("[A-Za-z0-9_.:-]+")
    }
}

/** How events and logs are shown: the color and sign of each level, names, times, and a log with its lines marked. */
object Logs {
    fun color(level: String) = when (level) {
        "ok" -> R.color.level_ok
        "error" -> R.color.level_error
        "warn" -> R.color.level_warn
        "start" -> R.color.level_start
        else -> R.color.level_info
    }

    fun symbol(level: String, running: Boolean) = when {
        running -> "…"
        level == "ok" -> "✓"
        level == "error" -> "✕"
        level == "warn" -> "!"
        else -> "i"
    }

    fun kind(context: Context, kind: String) = context.getString(
        when (kind) {
            "build" -> R.string.kind_build
            "update" -> R.string.kind_update
            "backup" -> R.string.kind_backup
            "service" -> R.string.kind_service
            "connection" -> R.string.kind_connection
            "git" -> R.string.kind_git
            else -> R.string.kind_other
        },
    )

    /** Who started it, as people say it; null when nobody is to say. */
    fun by(context: Context, by: String): String? = when (by) {
        "" -> null
        "app" -> context.getString(R.string.by_app)
        "job" -> context.getString(R.string.by_job)
        "boot" -> context.getString(R.string.by_boot)
        "action" -> context.getString(R.string.by_action)
        "resume" -> context.getString(R.string.by_resume)
        "shell" -> context.getString(R.string.by_shell)
        "adb", "ssh" -> context.getString(R.string.by_remote, by)
        else -> by
    }

    /** The line under an entry: its kind, who started it, how long it took (or that it is running). */
    fun meta(context: Context, event: Event, took: Long?, running: Boolean) = listOfNotNull(
        kind(context, event.kind),
        by(context, event.by),
        if (running) context.getString(R.string.logs_running) else took?.let { duration(it) },
    ).joinToString(" · ")

    fun duration(millis: Long): String {
        val seconds = (millis / 1000).coerceAtLeast(0)
        return when {
            seconds < 60 -> "$seconds s"
            seconds < 3600 -> "${seconds / 60} min ${seconds % 60} s"
            else -> "${seconds / 3600} h ${seconds % 3600 / 60} min"
        }
    }

    fun clock(time: Long): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(time))

    /** Today, Yesterday, or the date. */
    fun day(context: Context, time: Long): String = when {
        DateUtils.isToday(time) -> context.getString(R.string.logs_today)
        DateUtils.isToday(time + DateUtils.DAY_IN_MILLIS) -> context.getString(R.string.logs_yesterday)
        else -> DateUtils.formatDateTime(context, time, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_SHOW_YEAR)
    }

    fun size(bytes: Long): String = when {
        bytes >= 1L shl 20 -> String.format(Locale.ROOT, "%.1f MiB", bytes / 1048576.0)
        else -> "${(bytes + 1023) / 1024} KiB"
    }

    /** A log with its lines marked: done (✔) green, failed (✘, FAILED) red, a warning (!) amber, a step (==) bold. */
    fun colored(context: Context, text: String): CharSequence {
        val out = SpannableStringBuilder()
        for (line in text.lineSequence()) {
            val start = out.length
            out.append(line).append('\n')
            val body = line.substringAfter("] ", line).trimStart()
            val span = when {
                body.startsWith("✘") || body.startsWith("FAILED") || body.startsWith("error:") -> ForegroundColorSpan(context.getColor(R.color.level_error))
                body.startsWith("✔") -> ForegroundColorSpan(context.getColor(R.color.level_ok))
                body.startsWith("!") || body.startsWith("warning:") -> ForegroundColorSpan(context.getColor(R.color.level_warn))
                body.startsWith("==") -> StyleSpan(Typeface.BOLD)
                else -> null
            }
            span?.let { out.setSpan(it, start, out.length - 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
        }
        return out
    }
}
