package dev.nixup.manager.engine

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import kotlin.math.ceil
import kotlin.random.Random

/**
 * The config's timers (systemd.timers, with the one a unit's startAt makes; see nix/units.nix): each starts its unit at
 * its times, as systemd's timers do. A timer of the last build runs as a loop of its own ([LOOP]), started by the build
 * and at boot, that asks the engine for its next time ([next]), waits for it and then starts its unit ([Units.fire]): a
 * oneshot runs, a simple unit starts (one running already is left as it is). Its times are systemd's: OnCalendar
 * (systemd.time(7)'s calendar events, see [Calendar]), OnBootSec and OnStartupSec (after the phone started), OnActiveSec
 * (after the timer started), OnUnitActiveSec and OnUnitInactiveSec (after it last started its unit, or that unit's last
 * run ended), with Persistent (a time missed while the phone was off, or the timer not there, comes at once) and
 * RandomizedDelaySec. Its folder ($DATA/timers/<name>) keeps its spec, when it started (active), when it last started
 * its unit (last), its next time (next), whether it has none left until the next boot (done) and what each start
 * said (log).
 *
 * The loop looks at the clock every 30 seconds: a phone asleep runs nothing, and a time that passed while it slept
 * comes once it wakes, as with systemd's timers without WakeSystem. AccuracySec and the like are taken.
 *
 * A timer with a job (system.autoUpgrade's nixos-upgrade) starts no unit: at its time it runs
 * one of the module's scripts (see Units.fire), so it is there with or without services.
 */
class Timers(private val data: File) {
    private val dir = File(data, "timers")

    private var wanted: Map<String, JSONObject> = emptyMap()
    private var toStop: List<String> = emptyList()
    private var toStart: List<String> = emptyList()
    private var leaving: List<String> = emptyList()
    private var toWrite: List<String> = emptyList()
    private var had: Map<String, JSONObject> = emptyMap()
    private var active: Set<String> = emptySet()

    /** Whether the plan changes anything. */
    val changes get() = toStop.isNotEmpty() || toStart.isNotEmpty() || leaving.isNotEmpty() || toWrite.isNotEmpty()

    /** The units the planned timers start, each with its timers' names. */
    fun unitsStarted(): Map<String, List<String>> = started(wanted)

    /** The units the last build's timers start, each with its timers' names. */
    fun unitsStartedNow(): Map<String, List<String>> = started(applied())

    private fun started(timers: Map<String, JSONObject>) =
        timers.entries.filter { it.value.optBoolean("wanted") }.groupBy({ it.value.optString("unit") }, { it.key })

    /** Plans the timers of a profile ([timers]: its android.timers), for the [units] it has, and checks their times. */
    fun plan(timers: JSONObject?, units: Set<String>, problems: MutableList<String>) {
        wanted = timers?.let { json -> json.keys().asSequence().associateWith { json.getJSONObject(it) } } ?: emptyMap()
        for ((name, timer) in wanted) problems += check(name, timer)
        had = applied()
        leaving = had.keys.filter { it !in wanted }
        active = wanted.filter { (_, timer) -> timer.optBoolean("wanted") && (isJob(timer) || timer.optString("unit") in units) }.keys
        val changed = wanted.keys.filter { name -> had[name]?.toString()?.let { it != wanted.getValue(name).toString() } == true }
        toStop = had.keys.filter { (it in leaving || it !in active || it in changed) && loopPid(it) != null }
        // One whose loop ended with no time left (done) is left until the next boot, as systemd leaves an elapsed timer.
        toStart = active.filter { it in changed || had[it] == null || (loopPid(it) == null && !File(dir, "$it/done").isFile) }
        toWrite = wanted.keys.filter { it !in active && (had[it] == null || it in changed) }
    }

    /** What the plan does with each timer, after the services' plan. */
    fun report() {
        if (wanted.isEmpty() && had.isEmpty()) return
        Out.line("Timers:")
        for (name in (wanted.keys + had.keys).sorted()) {
            val timer = wanted[name] ?: had.getValue(name)
            val what = when {
                name in leaving -> "to remove (it left the config)"
                name !in active -> if (loopPid(name) != null) "to stop (nothing wants it now)" else "not started (nothing wants it)"
                name in toStart -> "to start" + (runCatching { next(name, timer, random = false) }.getOrNull()?.let { ", next ${Out.date(it * 1000)}" } ?: "")
                loopPid(name) == null && File(dir, "$name/done").isFile -> "no time left until the next boot"
                else -> "waiting" + (nextTime(name)?.let { ", next ${Out.date(it)}" } ?: "")
            }
            Out.line("  $name (starts ${timer.optString("unit")}): $what")
        }
    }

    /** What is wrong with [timer]'s times: an OnCalendar or time span systemd would not take. */
    private fun check(name: String, timer: JSONObject): List<String> {
        val problems = mutableListOf<String>()
        // A job's times are its option's (system.autoUpgrade.dates, ...), a timer's its timerConfig's.
        val option = timer.string("option")
        val what = option ?: "systemd.timers.$name.timerConfig"
        for (expression in timer.strings("onCalendar")) {
            runCatching { Calendar.parse(expression) }.onFailure { problems += "$what.${if (option != null) "dates" else "OnCalendar"}: \"$expression\": ${it.message}" }
        }
        for ((key, field) in SPANS) {
            val text = timer.string(field) ?: continue
            if (span(text) == null) problems += "$what.${if (option != null) "randomizedDelaySec" else key}: \"$text\" is not a time span like 30s, 5min or 1h 30min"
        }
        return problems
    }

    /** Carries the plan out: the timers that changed or left stop, the new and changed ones start. */
    fun apply(): Int {
        toStop.forEach { stop(it) }
        for (name in leaving) {
            deleteTree(File(dir, name))
            Out.line("✔ timer $name: removed (it left the config)")
        }
        // One nothing wants is kept as it is, so the last build has it.
        for (name in toWrite) File(dir, name).apply { mkdirs() }.resolve("spec.json").writeText(wanted.getValue(name).toString())
        var failed = 0
        for (name in toStart) {
            val timer = wanted.getValue(name)
            val folder = File(dir, name).apply { mkdirs() }
            folder.resolve("spec.json").writeText(timer.toString())
            File(folder, "done").delete()
            if (launch(name)) {
                val next = runCatching { next(name, timer, random = false) }.getOrNull()
                Out.line("✔ timer $name: started" + (next?.let { "; it starts ${timer.optString("unit")} next at ${Out.date(it * 1000)}" } ?: "; it has no time left until the next boot"))
            } else {
                failed++
            }
        }
        return failed
    }

    /** At boot: the last build's timers start again, the ones done before too (their boot times are new). */
    fun start(units: Set<String>) {
        for ((name, timer) in applied()) {
            File(dir, "$name/done").delete()
            File(dir, "$name/next").delete()
            if (timer.optBoolean("wanted") && (isJob(timer) || timer.optString("unit") in units)) launch(name)
        }
    }

    /** The module is being removed: the timers stop, and their folders go. */
    fun removeAll() {
        applied().keys.forEach { stop(it) }
        deleteTree(dir)
    }

    /** The last build's timers, as the app and units.sh list them. */
    fun list(): JSONArray {
        val list = JSONArray()
        for ((name, timer) in applied().toSortedMap()) {
            list.put(
                JSONObject().put("name", name).put("unit", timer.optString("unit")).put("description", timer.optString("description"))
                    .put("wanted", timer.optBoolean("wanted")).put("running", loopPid(name) != null).put("done", File(dir, "$name/done").isFile)
                    .put("next", nextTime(name) ?: 0L).put("last", lastTime(name) ?: 0L),
            )
        }
        return list
    }

    /** The next time the loop of [name] waits for (epoch ms), while it runs. */
    fun nextTime(name: String): Long? = if (loopPid(name) == null) null else number(File(dir, "$name/next"))?.let { it * 1000 }

    /** When [name] last started its unit (epoch ms). */
    fun lastTime(name: String): Long? = number(File(dir, "$name/last"))?.let { it * 1000 }

    /** The last build's timer [name] (its spec), if there is one. */
    fun spec(name: String): JSONObject? = applied()[name]

    /**
     * The next time timer [name] of the last build starts its unit, in epoch seconds (now, when one was missed), with
     * its random delay; null when it has none left (until the next boot). For its loop (the engine's timer-next).
     */
    fun next(name: String): Long? = next(name, applied()[name] ?: throw Stop("there is no timer $name in the last build", 2), random = true)

    private fun next(name: String, timer: JSONObject, random: Boolean): Long? {
        val now = System.currentTimeMillis() / 1000
        val folder = File(dir, name)
        val last = number(File(folder, "last"))
        val active = number(File(folder, "active")) ?: now
        val boot = bootTime(now)
        val times = mutableListOf<Long>()
        // Its calendar events: the first after its last start (a Persistent one's, from before it started too), else
        // after it started.
        val base = if (last != null && (timer.optBoolean("persistent") || last >= active)) last else active
        for (expression in timer.strings("onCalendar")) Calendar.parse(expression).next(base, ZoneId.systemDefault())?.let { times += it }
        // Once after the phone started (or the timer did), unless it started its unit since.
        for ((field, from) in listOf("onBoot" to boot, "onStartup" to boot, "onActive" to active)) {
            val at = timer.string(field)?.let { span(it) }?.let { from + it } ?: continue
            if (last == null || last < at) times += at
        }
        // After it last started its unit, or after that unit's last run ended, since the phone started.
        timer.string("onUnitActive")?.let { span(it) }?.let { delay -> if (last != null && last >= boot) times += last + delay }
        timer.string("onUnitInactive")?.let { span(it) }?.let { delay ->
            val ended = File(data, "units/${timer.optString("unit")}/status").takeIf { it.isFile }?.lastModified()?.div(1000) ?: last
            if (ended != null && ended >= boot) times += ended + delay
        }
        val first = times.minOrNull() ?: return null
        val delay = timer.string("randomizedDelay")?.let { span(it) } ?: 0L
        // FixedRandomDelay: the same delay each time (one of its own, from the phone's machine id and its name, as
        // systemd's); a plan's look at it has none.
        val fixed = timer.optBoolean("fixedRandomDelay") && delay > 0
        return maxOf(first, now) + when {
            fixed -> Math.floorMod((machineId() + name).hashCode().toLong(), delay)
            random && delay > 0 -> Random.nextLong(delay)
            else -> 0L
        }
    }

    /** Starts the loop of [name] (see [LOOP]), below the apps on the screen; whether it runs. */
    private fun launch(name: String): Boolean {
        val module = Notify.module ?: run {
            Out.line("✘ timer $name: the module's folder is not known (the engine runs from it), so it cannot start")
            return false
        }
        stop(name)
        val folder = File(dir, name).apply { mkdirs() }
        File(folder, "active").writeText("${System.currentTimeMillis() / 1000}\n")
        File(folder, "next").delete()
        val busybox = Units.busybox()
        val background = File(data, "units/background.sh")
        val command = listOf(busybox, "setsid", "sh", "-c", LOOP, MARK + name, File(module, "app.apk").path, folder.path, name)
        return runCatching {
            ProcessBuilder(if (background.isFile) listOf(busybox, "sh", background.path, busybox, "10") + command else command)
                .directory(folder).redirectInput(File("/dev/null")).redirectOutput(File("/dev/null")).redirectError(File("/dev/null"))
                .apply {
                    listOf("NIXUP_RUN", "NIXUP_KIND", "NIXUP_QUIET").forEach { environment().remove(it) }
                    environment()["NIXUP_BY"] = "timer"
                }.start()
            var tries = 0
            while (loopPid(name) == null && tries++ < 25) Thread.sleep(200)
            loopPid(name) != null
        }.getOrElse { false }.also { if (!it) Out.line("✘ timer $name: its loop did not start") }
    }

    private fun stop(name: String) {
        loopPid(name)?.let { Device.run("kill", "-TERM", "$it") }
        File(dir, "$name/loop.pid").delete()
        File(dir, "$name/next").delete()
    }

    /** The last build's timers, by name. */
    private fun applied(): Map<String, JSONObject> = dir.listFiles().orEmpty().filter { it.isDirectory }.mapNotNull { folder ->
        runCatching { folder.name to JSONObject(File(folder, "spec.json").readText()) }.getOrNull()
    }.toMap()

    /** The view's machine id (Etc writes it), for a fixed random delay. */
    private fun machineId(): String = runCatching { File(data, "etc/machine-id").readText().trim() }.getOrDefault("")

    /** Its loop's process, while it runs. */
    private fun loopPid(name: String): Int? = number(File(dir, "$name/loop.pid"))?.toInt()
        ?.takeIf { pid -> runCatching { File("/proc/$pid/cmdline").readText().replace('\u0000', ' ') }.getOrDefault("").contains(MARK + name) }

    private fun number(file: File): Long? = runCatching { file.readText().trim().toLong() }.getOrNull()

    /** When the phone started, in epoch seconds (its uptime counts the time it slept too). */
    private fun bootTime(now: Long): Long =
        now - (runCatching { File("/proc/uptime").readText().substringBefore(' ').toDouble().toLong() }.getOrNull() ?: 0L)

    /**
     * A calendar event of systemd.time(7), as OnCalendar and startAt take it: "[weekdays] [year-month-day]
     * [hour:minute[:second]] [time zone]", each part a value, a list (1,15), a range (Mon..Fri, 1..5), a repetition
     * (*:0/15, 8..18/2) or anything (*); or one of minutely, hourly, daily, weekly, monthly, quarterly, semiannually,
     * yearly (annually). Without a time zone, the phone's.
     */
    class Calendar private constructor(
        private val weekdays: Set<Int>?,
        private val years: Field, private val months: Field, private val days: Field,
        private val hours: Field, private val minutes: Field, private val seconds: Field,
        private val zone: ZoneId?,
    ) {
        /** The first time after [after] (epoch seconds) it names, in its time zone or else [zone]; null: none. */
        fun next(after: Long, zone: ZoneId): Long? {
            val z = this.zone ?: zone
            val start = Instant.ofEpochSecond(after + 1).atZone(z)
            var date = start.toLocalDate()
            var from: LocalTime? = start.toLocalTime().withNano(0)
            while (date.year <= LAST_YEAR) {
                when {
                    !years.has(date.year) -> date = LocalDate.of(date.year + 1, 1, 1)
                    !months.has(date.monthValue) -> date = date.withDayOfMonth(1).plusMonths(1)
                    !days.has(date.dayOfMonth) || (weekdays != null && date.dayOfWeek.value !in weekdays) -> date = date.plusDays(1)
                    else -> {
                        var time = from ?: LocalTime.MIDNIGHT
                        while (true) {
                            val found = firstTime(time) ?: break
                            // (A time a clock change skips, or has twice, may come before [after].)
                            val at = ZonedDateTime.of(date, found, z).toEpochSecond()
                            if (at > after) return at
                            if (found == LAST_SECOND) break
                            time = found.plusSeconds(1)
                        }
                        date = date.plusDays(1)
                    }
                }
                from = null
            }
            return null
        }

        /** The first time of a day it names, at [from] or later. */
        private fun firstTime(from: LocalTime): LocalTime? {
            for (hour in from.hour..23) {
                if (!hours.has(hour)) continue
                for (minute in (if (hour == from.hour) from.minute else 0)..59) {
                    if (!minutes.has(minute)) continue
                    for (second in (if (hour == from.hour && minute == from.minute) from.second else 0)..59) {
                        if (seconds.has(second)) return LocalTime.of(hour, minute, second)
                    }
                }
            }
            return null
        }

        /** One part of a calendar event: the numbers it takes (null: all). */
        private class Field(private val values: Set<Int>?) {
            fun has(number: Int) = values == null || number in values
        }

        companion object {
            private const val LAST_YEAR = 2199
            private val LAST_SECOND = LocalTime.of(23, 59, 59)
            private val SHORTHANDS = mapOf(
                "minutely" to "*-*-* *:*:00", "hourly" to "*-*-* *:00:00", "daily" to "*-*-* 00:00:00",
                "monthly" to "*-*-01 00:00:00", "weekly" to "Mon *-*-* 00:00:00", "yearly" to "*-01-01 00:00:00",
                "annually" to "*-01-01 00:00:00", "quarterly" to "*-01,04,07,10-01 00:00:00",
                "semiannually" to "*-01,07-01 00:00:00", "semi-annually" to "*-01,07-01 00:00:00",
            )
            private val WEEKDAYS = listOf("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday")

            /** [expression] as a calendar event; an IllegalArgumentException says what is wrong with it. */
            fun parse(expression: String): Calendar {
                var words = expression.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
                if (words.isEmpty()) throw IllegalArgumentException("it is empty")
                var zone: ZoneId? = null
                if (words.size > 1 && isZone(words.last())) {
                    val name = words.last()
                    zone = ZoneId.of(if (name.equals("UTC", ignoreCase = true)) "UTC" else name)
                    words = words.dropLast(1)
                }
                SHORTHANDS[words.joinToString(" ").lowercase(Locale.ROOT)]?.let { words = it.split(' ') }
                var weekdays: Set<Int>? = null
                if (words.first().first().isLetter()) {
                    weekdays = weekdays(words.first())
                    words = words.drop(1)
                }
                var date: String? = null
                var time: String? = null
                for (word in words) {
                    when {
                        ':' in word && time == null -> time = word
                        '-' in word && date == null && ':' !in word -> date = word
                        else -> throw IllegalArgumentException("\"$word\" is neither a date (2026-12-24, *-*-01) nor a time (07:30, *:0/15)")
                    }
                }
                if (date?.contains('~') == true) throw IllegalArgumentException("days counted from the month's end (~) are not on the phone")
                val dateParts = (date ?: "*-*-*").split('-')
                val (year, month, day) = when (dateParts.size) {
                    3 -> dateParts
                    2 -> listOf("*") + dateParts
                    else -> throw IllegalArgumentException("\"$date\" is not a date: year-month-day, or month-day")
                }
                val timeParts = (time ?: "00:00:00").split(':')
                val (hour, minute, second) = when (timeParts.size) {
                    3 -> timeParts
                    2 -> timeParts + "00"
                    else -> throw IllegalArgumentException("\"$time\" is not a time: hour:minute, or hour:minute:second")
                }
                return Calendar(
                    weekdays,
                    field(year, 1970, LAST_YEAR, "year", years = true), field(month, 1, 12, "month"), field(day, 1, 31, "day"),
                    field(hour, 0, 23, "hour"), field(minute, 0, 59, "minute"), field(second, 0, 59, "second"), zone,
                )
            }

            private fun isZone(word: String) = word.first().isLetter() && runCatching { weekdays(word) }.isFailure &&
                (word.equals("UTC", ignoreCase = true) || runCatching { ZoneId.of(word) }.isSuccess)

            /** "Mon", "Sat,Sun", "Mon..Fri" (or Mon-Fri), as java.time numbers them: Monday 1 to Sunday 7. */
            private fun weekdays(text: String): Set<Int> = text.split(',').flatMap { item ->
                val range = item.split("..", "-")
                when (range.size) {
                    1 -> listOf(weekday(range[0]))
                    2 -> {
                        val (from, to) = weekday(range[0]) to weekday(range[1])
                        if (from > to) throw IllegalArgumentException("\"$item\": a range of days goes from Monday to Sunday")
                        (from..to).toList()
                    }
                    else -> throw IllegalArgumentException("\"$item\" is not a day of the week, or a range of them")
                }
            }.toSet()

            private fun weekday(text: String): Int {
                val lower = text.lowercase(Locale.ROOT)
                val index = WEEKDAYS.indexOfFirst { it == lower || (lower.length == 3 && it.startsWith(lower)) }
                if (index < 0) throw IllegalArgumentException("\"$text\" is not a day of the week (Mon, Tue, ..., or the whole name)")
                return index + 1
            }

            /** One part ([what]) of a date or time: *, a number, a list, a range (a..b), each with a repetition (/n). */
            private fun field(text: String, min: Int, max: Int, what: String, years: Boolean = false): Field {
                if (text == "*") return Field(null)
                val values = sortedSetOf<Int>()
                for (item in text.split(',')) {
                    val parts = item.split('/')
                    if (parts.size > 2 || parts[0].isEmpty()) throw IllegalArgumentException("\"$item\" is not a $what")
                    val step = parts.getOrNull(1)?.let { it.toIntOrNull()?.takeIf { step -> step > 0 } ?: throw IllegalArgumentException("\"$item\": its repetition is not a whole number above 0") }
                    val (from, to) = when {
                        parts[0] == "*" -> min to max
                        ".." in parts[0] -> parts[0].split("..").let { range ->
                            if (range.size != 2) throw IllegalArgumentException("\"$item\" is not a range of ${what}s")
                            number(range[0], what, years) to number(range[1], what, years)
                        }
                        else -> number(parts[0], what, years).let { it to if (step != null) max else it }
                    }
                    if (from < min || to > max || from > to) throw IllegalArgumentException("\"$item\": a $what is from $min to $max")
                    for (value in from..to step (step ?: 1)) values += value
                }
                return Field(values)
            }

            /** A number of a date or time: a year of two digits is from 1970 to 2069, as systemd takes it. */
            private fun number(text: String, what: String, years: Boolean): Int {
                val whole = if (what == "second" && '.' in text && text.substringAfter('.').all { it == '0' }) text.substringBefore('.') else text
                val value = whole.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }?.toIntOrNull()
                    ?: throw IllegalArgumentException("\"$text\" is not a $what" + if (what == "second" && '.' in text) " (parts of a second are not on the phone)" else "")
                return if (years && whole.length <= 2) (if (value < 70) 2000 + value else 1900 + value) else value
            }
        }
    }

    companion object {
        private const val MARK = "nixup-timer:"

        /** Whether [timer] runs a script of the module (a job) rather than starting a unit. */
        fun isJob(timer: JSONObject): Boolean = timer.optJSONObject("job") != null

        /** The time spans of a timer: its key in timerConfig, and in the profile. */
        private val SPANS = listOf(
            "OnBootSec" to "onBoot", "OnStartupSec" to "onStartup", "OnActiveSec" to "onActive", "OnUnitActiveSec" to "onUnitActive",
            "OnUnitInactiveSec" to "onUnitInactive", "RandomizedDelaySec" to "randomizedDelay",
        )

        private val SPAN = Regex("\\s*([0-9]+(?:\\.[0-9]+)?)\\s*([A-Za-zµ]*)")
        private val SPAN_UNITS = mapOf(
            "usec" to 0.000001, "us" to 0.000001, "µs" to 0.000001, "msec" to 0.001, "ms" to 0.001,
            "seconds" to 1.0, "second" to 1.0, "sec" to 1.0, "s" to 1.0, "" to 1.0,
            "minutes" to 60.0, "minute" to 60.0, "min" to 60.0, "m" to 60.0,
            "hours" to 3600.0, "hour" to 3600.0, "hr" to 3600.0, "h" to 3600.0,
            "days" to 86400.0, "day" to 86400.0, "d" to 86400.0, "weeks" to 604800.0, "week" to 604800.0, "w" to 604800.0,
            "months" to 2629800.0, "month" to 2629800.0, "M" to 2629800.0, "years" to 31557600.0, "year" to 31557600.0, "y" to 31557600.0,
        )

        /** A systemd time span (systemd.time(7): 90, "30s", "5min", "1h 30min", "2d") in whole seconds; null: not one. */
        fun span(text: String): Long? {
            var total = 0.0
            var at = 0
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return null
            while (at < trimmed.length) {
                val match = SPAN.find(trimmed, at)?.takeIf { it.range.first == at } ?: return null
                total += match.groupValues[1].toDouble() * (SPAN_UNITS[match.groupValues[2]] ?: return null)
                at = match.range.last + 1
                while (at < trimmed.length && trimmed[at] == ' ') at++
            }
            return ceil(total).toLong()
        }

        /**
         * A timer's loop: asks the engine for its next time (timer-next: nothing when it has none left, until the next
         * boot; when the engine fails, it asks again in a minute), waits for it looking at the clock every 30 s, and has
         * the engine start its unit (timer-fire), again and again. Its log is set aside past 256 KB. Arguments: the
         * module's app.apk, the timer's folder, its name.
         */
        private val LOOP = """
            apk=${'$'}1 folder=${'$'}2 name=${'$'}3
            echo ${'$'}${'$'} > "${'$'}folder/loop.pid"
            engine() { CLASSPATH="${'$'}apk" /system/bin/app_process /system/bin dev.nixup.manager.engine.Main "${'$'}@"; }
            while :; do
              [ -f "${'$'}folder/log" ] && [ "${'$'}(stat -c %s "${'$'}folder/log")" -gt 262144 ] && mv -f "${'$'}folder/log" "${'$'}folder/log.old"
              next=${'$'}(engine timer-next "${'$'}name" 2>> "${'$'}folder/log" < /dev/null) || next="failed${'$'}{next:+: ${'$'}next}"
              case ${'$'}next in
                "") : > "${'$'}folder/done"; rm -f "${'$'}folder/next" "${'$'}folder/loop.pid"; exit 0 ;;
                *[!0-9]*) echo "${'$'}(date '+%F %T') ${'$'}name: its next time is not known (${'$'}next): asked again in a minute" >> "${'$'}folder/log"; sleep 60 & wait ${'$'}!; continue ;;
              esac
              echo "${'$'}next" > "${'$'}folder/next"
              while now=${'$'}(date +%s); [ "${'$'}now" -lt "${'$'}next" ]; do
                left=${'$'}((next - now))
                [ "${'$'}left" -gt 30 ] && left=30
                sleep "${'$'}left" & wait ${'$'}!
              done
              date +%s > "${'$'}folder/last"
              echo "${'$'}(date '+%F %T') ${'$'}name: its time" >> "${'$'}folder/log"
              engine timer-fire "${'$'}name" >> "${'$'}folder/log" 2>&1 < /dev/null
            done
        """.trimIndent()
    }
}
