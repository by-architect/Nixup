package dev.andronix.manager.engine

import org.json.JSONObject
import java.io.File
import java.util.TimeZone

/**
 * The phone's own settings, from the profile's android.system: Android's settings tables (what the plain options,
 * like android.display.fontScale, come down to, and android.rawSettings), and the ones that take a command: display
 * density, navigation mode, languages, time zone, Data Saver and location. Each has a key ("system/font_scale",
 * "density", ...) and a value read as a string, null when it is not set. The value before the module first changed
 * one is kept in original/system.json, and written back when it leaves the config, and for all of them when the
 * module is removed. So are the values that change along with one (Night Light's on and off, by its schedule; the
 * language's property, by the language): they are given back after it.
 */
class SystemSettings(data: File) {
    private val journalFile = File(data, "original/system.json")
    private val journal: JSONObject = if (journalFile.isFile) JSONObject(journalFile.readText()) else JSONObject()

    /** The settings tables read so far; one is read again once something in it changed. */
    private val tables = HashMap<String, Map<String, String>>()

    private class Change(val key: String, val value: String?)

    private var planned: List<Change> = emptyList()
    private var giveBack: List<String> = emptyList()

    /**
     * Whether the build has something to do here: a setting that differs, or one that left the config (given back, or
     * only forgotten when it has its value from before again: the user may change it from then on, and keep it).
     */
    val changes: Boolean get() = planned.isNotEmpty() || giveBack.isNotEmpty()

    /** Plans the profile's android.system: says what will change, and adds what keeps the build from running to [problems]. */
    fun plan(system: JSONObject?, problems: MutableList<String>) {
        val wanted = wanted(system)
        val plans = ArrayList<Change>()
        val warnings = ArrayList<String>()
        for ((key, value) in wanted) {
            when (val why = check(key, value)) {
                null -> if (!same(key, read(key), value)) plans += Change(key, value)
                else -> if (key == "timeZone") problems += why else warnings += why
            }
        }
        planned = plans
        // A value that changes along with a setting of the config is the config's while that setting is.
        val held = wanted.keys.flatMap { COMPANIONS[it] ?: emptyList() }.toSet()
        giveBack = journal.keys().asSequence().filter { it !in wanted && it !in held }.toList().sortedBy(::rank)

        val lines = ArrayList<String>()
        for (change in plans) lines += "  ${label(change.key)}: ${shown(read(change.key))} -> ${shown(change.value)}"
        for (key in giveBack) {
            if (!same(key, read(key), original(key))) lines += "  ${label(key)}: back as before (${shown(original(key))})"
        }
        if (lines.isEmpty() && wanted.isNotEmpty()) lines += "  as the config says (${wanted.size} settings)"
        if (lines.isNotEmpty()) {
            Out.line("System settings:")
            lines.forEach { Out.line(it) }
        }
        warnings.forEach { Out.line("⚠ $it") }
    }

    /** Applies the plan: each setting that differs is changed and read back, and those that left the config are given back. Returns how many failed. */
    fun apply(): Int {
        tables.clear()
        var failed = 0
        for (change in planned) failed += change(change.key, change.value)
        for (key in giveBack) failed += giveBack(key)
        return failed
    }

    /** Gives every setting the module changed back (the module is being removed). Returns how many failed. */
    fun restoreAll(): Int {
        var failed = 0
        for (key in journal.keys().asSequence().toList().sortedBy(::rank)) failed += giveBack(key)
        return failed
    }

    /**
     * The order settings go back in: those that take a command first (the time zone before automatic time zone, as
     * they were set the other way round), then the tables, then what changes along with them (Night Light's on and
     * off once its schedule is gone).
     */
    private fun rank(key: String) = when {
        key in COMPANION_KEYS -> 2
        key in COMMANDS -> 0
        else -> 1
    }

    private fun change(key: String, value: String?): Int {
        val now = read(key)
        if (same(key, now, value)) return 0
        remember(key, now)
        for (companion in COMPANIONS[key] ?: emptyList()) remember(companion, read(companion))
        if (!write(key, value)) {
            Out.line("✘ ${label(key)}: Android did not take ${shown(value)}")
            return 1
        }
        val after = settled(key, value)
        if (same(key, after, value)) {
            Out.line("✔ ${label(key)}: ${shown(now)} -> ${shown(value)}")
            return 0
        }
        Out.line("✘ ${label(key)}: set to ${shown(value)}, it reads ${shown(after)}")
        return 1
    }

    private fun giveBack(key: String): Int {
        if (!known(key)) {
            forget(key)
            return 0
        }
        val original = original(key)
        if (same(key, read(key), original)) {
            forget(key)
            return 0
        }
        val after = if (write(key, original)) settled(key, original) else read(key)
        if (same(key, after, original)) {
            forget(key)
            Out.line("✔ ${label(key)}: back as before (${shown(original)})")
            return 0
        }
        // Kept, so the next build (or the module's removal) tries again.
        Out.line("✘ ${label(key)}: could not go back to ${shown(original)} (it reads ${shown(after)})")
        return 1
    }

    /** The value read back after a change; some (the language) take a moment, so it is read again for up to 3 seconds. */
    private fun settled(key: String, value: String?): String? {
        var now = read(key)
        repeat(6) {
            if (same(key, now, value)) return now
            Thread.sleep(500)
            tables.clear()
            now = read(key)
        }
        return now
    }

    // --- The settings, key by key.

    /** What the profile sets, in the order it is set: the tables first (automatic time zone off before the time zone is set), then the commands. */
    private fun wanted(system: JSONObject?): LinkedHashMap<String, String?> {
        val wanted = LinkedHashMap<String, String?>()
        if (system == null) return wanted
        system.optJSONObject("settings")?.let { tables ->
            for (table in TABLES) {
                val settings = tables.optJSONObject(table) ?: continue
                for (name in settings.keys()) wanted["$table/$name"] = settings.get(name).toString()
            }
        }
        for (key in COMMANDS) if (system.has(key) && !system.isNull(key)) wanted[key] = system.get(key).toString()
        return wanted
    }

    private fun known(key: String) = key in COMMANDS || key.substringBefore('/') in TABLES || key.startsWith("prop/")

    /** Why the phone cannot have the value: null when it can. */
    private fun check(key: String, value: String?): String? = when (key) {
        "timeZone" -> if (value in TimeZone.getAvailableIDs()) null else "android.time.timeZone: this phone has no time zone \"$value\""
        "navigation" -> Device.navigationModes().keys.let { modes ->
            if (value in modes) null
            else "android.navigation.mode: this phone has no ${shown(value)} navigation (it has ${modes.joinToString(", ") { shown(it) }}), so it is left as it is"
        }
        "dataSaver" -> if (Device.dataSaver() == null) "android.network.dataSaver: this phone does not say whether Data Saver is on, so it is left as it is" else null
        "location" -> if (Device.location() == null) "android.location.enable: this phone does not say whether location is on, so it is left as it is" else null
        else -> null
    }

    private fun read(key: String): String? = when (key) {
        "density" -> Device.density()
        "navigation" -> Device.navigationModes().entries.firstOrNull { it.value }?.key
        "language" -> Device.languages().ifEmpty { null }
        "timeZone" -> Device.timeZone().ifEmpty { null }
        "dataSaver" -> Device.dataSaver()?.toString()
        "location" -> Device.location()?.toString()
        else -> if (key.startsWith("prop/")) Device.property(key.substringAfter('/')).ifEmpty { null } else {
            val table = key.substringBefore('/')
            tables.getOrPut(table) { Device.settings(table) }[key.substringAfter('/')]
        }
    }

    private fun write(key: String, value: String?): Boolean = when (key) {
        "density" -> Device.setDensity(value)
        "navigation" -> value != null && Device.setNavigationMode(value)
        "language" -> value != null && Device.setLanguages(value)
        "timeZone" -> value != null && Device.setTimeZone(value)
        "dataSaver" -> value != null && Device.setDataSaver(value == "true")
        "location" -> value != null && Device.setLocation(value == "true")
        else -> if (key.startsWith("prop/")) Device.setProperty(key.substringAfter('/'), value ?: "") else {
            val table = key.substringBefore('/')
            val ok = Device.putSetting(table, key.substringAfter('/'), value)
            tables.remove(table)
            if (ok && key == "system/time_12_24") Device.tellTimeFormat(value != "12")
            ok
        }
    }

    /** Values that mean the same: 1.0 and 1 for numbers, tr-tr and tr-TR for languages. */
    private fun same(key: String, a: String?, b: String?): Boolean {
        if (a == b) return true
        if (a == null || b == null) return false
        if (key == "language") return a.equals(b, ignoreCase = true)
        val x = a.toDoubleOrNull()
        val y = b.toDoubleOrNull()
        return x != null && y != null && x == y
    }

    private fun label(key: String) = LABELS[key] ?: key

    private fun shown(value: String?) = when (value) {
        null -> "not set"
        "gestural" -> "gestures"
        "threebutton" -> "three buttons"
        "twobutton" -> "two buttons"
        else -> value
    }

    // --- What the module changed, and what it was before.

    private fun original(key: String): String? = if (journal.isNull(key)) null else journal.getString(key)

    private fun remember(key: String, value: String?) {
        if (journal.has(key)) return
        journal.put(key, value ?: JSONObject.NULL)
        save()
    }

    private fun forget(key: String) {
        if (journal.remove(key) != null) save()
    }

    private fun save() {
        journalFile.parentFile?.mkdirs()
        val fresh = File(journalFile.path + ".new")
        fresh.writeText(journal.toString(1))
        fresh.renameTo(journalFile)
    }

    companion object {
        private val TABLES = listOf("global", "secure", "system")
        private val COMMANDS = listOf("density", "navigation", "language", "timeZone", "dataSaver", "location")
        /** Values that change along with a setting: Night Light's on and off by its schedule, the language's property (empty: the ROM's own) by the language. */
        private val COMPANIONS = mapOf(
            "secure/night_display_auto_mode" to listOf("secure/night_display_activated"),
            "secure/night_display_custom_start_time" to listOf("secure/night_display_activated"),
            "secure/night_display_custom_end_time" to listOf("secure/night_display_activated"),
            "language" to listOf("prop/persist.sys.locale"),
        )
        private val COMPANION_KEYS = COMPANIONS.values.flatten().toSet()

        private val LABELS = mapOf(
            "density" to "display density", "navigation" to "navigation", "language" to "language",
            "timeZone" to "time zone", "dataSaver" to "Data Saver", "location" to "location",
        )
    }
}
