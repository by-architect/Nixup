package dev.andronix.manager.engine

import org.json.JSONObject
import java.io.File
import java.util.TimeZone

/**
 * The phone's own settings, from the profile's android.system: the settings tables (Android's system, secure and
 * global, and on ROMs based on LineageOS its own: what the plain options of android.settings, like
 * android.settings.display.fontScale, come down to, and android.settings.raw), and the ones that take a command: display density, navigation mode, languages, time
 * zone, keyboard, Data Saver and location. Each has a key ("system/font_scale", "lineage.system/status_bar_clock",
 * "density", ...) and a value read as a string, null when it is not set.
 *
 * A setting the phone cannot have (it needs an always-on display, a second refresh rate, a newer Android, a ROM based
 * on LineageOS, ...) is left as it is, with a warning. The value before the module first changed one is kept in
 * original/system.json, and written back when it leaves the config, and for all of them when the module is removed.
 * So are the values that change along with one (Night Light's on and off, by its schedule; the language's property,
 * by the language; the keyboards enabled, by the keyboard): they are given back after it.
 */
class SystemSettings(data: File) {
    private val journalFile = File(data, "original/system.json")
    private val journal: JSONObject = if (journalFile.isFile) JSONObject(journalFile.readText()) else JSONObject()

    /** The settings tables read so far; one is read again once something in it changed. */
    private val tables = HashMap<String, Map<String, String>>()

    /** For each setting a plain option sets, that option (from the profile), for messages. */
    private var labels: Map<String, String> = emptyMap()

    private class Change(val key: String, val value: String?)

    private var planned: List<Change> = emptyList()
    private var giveBack: List<String> = emptyList()

    /**
     * Whether the build has something to do here: a setting that differs, or one that left the config (given back, or
     * only forgotten when it has its value from before again: the user may change it from then on, and keep it).
     */
    val changes: Boolean get() = planned.isNotEmpty() || giveBack.isNotEmpty()

    /**
     * Plans the profile's android.system ([installing]: the apps this build installs, whose keyboard or autofill
     * service is set once they are there): says what will change, warns of what the phone cannot have, and adds what
     * keeps the build from running to [problems].
     */
    fun plan(system: JSONObject?, installing: Set<String>, problems: MutableList<String>) {
        labels = system?.optJSONObject("labels")?.let { l -> l.keys().asSequence().associateWith { l.getString(it) } } ?: emptyMap()
        val wanted = wanted(system)
        val plans = ArrayList<Change>()
        val warnings = LinkedHashSet<String>()
        var managed = 0
        for ((key, value) in wanted) {
            val missing = missing(key) ?: unsettable(key)
            val why = missing ?: check(key, value, installing)
            when {
                why == null -> {
                    managed++
                    if (!same(key, read(key), value)) plans += Change(key, value)
                }
                key == "timeZone" && missing == null -> problems += "${option(key)}: $why"
                else -> warnings += "${option(key)}: $why, so it is left as it is"
            }
        }
        planned = plans
        // A value that changes along with a setting of the config is the config's while that setting is.
        val held = wanted.keys.flatMap { COMPANIONS[it] ?: emptyList() }.toSet()
        giveBack = journal.keys().asSequence().filter { it !in wanted && it !in held }.toList().sortedBy(::rank)

        val lines = ArrayList<String>()
        for (change in plans) lines += "  ${label(change.key)}: ${shown(read(change.key))} -> ${shown(change.value)}"
        for (key in giveBack) {
            if (known(key) && missing(key) == null && !same(key, read(key), original(key))) lines += "  ${label(key)}: back as before (${shown(original(key))})"
        }
        if (lines.isEmpty() && managed > 0) lines += "  as the config says ($managed settings)"
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

    /** Says, for each setting that needs something of the phone, whether this phone has it (the engine's support command). */
    fun report() {
        Out.line("Android ${release(Device.sdk)} (API ${Device.sdk}); ${if (Device.lineage) "based on LineageOS" else "not based on LineageOS"}")
        for ((key, needs) in NEEDS.entries.sortedBy { it.key }) {
            val why = needs.firstNotNullOfOrNull { it.missing }
            Out.line(if (why == null) "✔ $key" else "✘ $key: $why")
        }
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
        // Not one this engine knows (any more), or the phone cannot have it now (another ROM): nothing to give back.
        if (!known(key) || missing(key) != null) {
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

    /** Why the phone cannot have this setting at all (what it needs: a feature, a newer Android, LineageOS), or null. */
    private fun missing(key: String): String? = needs(key).firstNotNullOfOrNull { it.missing }

    /**
     * Why the module may not set this one: a LineageOS setting this phone has no value for yet could never be given
     * back (LineageOS settings cannot be deleted safely; see Device.putLineageSetting). Null when it may.
     */
    private fun unsettable(key: String): String? =
        if (key.startsWith("lineage.") && !journal.has(key) && read(key) == null) "this phone has no value for it yet, and a LineageOS setting cannot be taken out again (set it once in Settings first)" else null

    /** Why the phone cannot have this value (a time zone or a keyboard it does not know, ...), or null when it can. */
    private fun check(key: String, value: String?, installing: Set<String>): String? = when (key) {
        "timeZone" -> if (value in TimeZone.getAvailableIDs()) null else "this phone has no time zone \"$value\""
        "navigation" -> Device.navigationModes().keys.let { modes ->
            if (value in modes) null else "this phone has no ${shown(value)} navigation (it has ${modes.joinToString(", ") { shown(it) }})"
        }
        "dataSaver" -> if (Device.dataSaver() == null) "this phone does not say whether Data Saver is on" else null
        "location" -> if (Device.location() == null) "this phone does not say whether location is on" else null
        "keyboard" -> value?.substringBefore('/').let { app ->
            if (value in Device.inputMethods() || app in installing) null else "this phone has no keyboard $value (is its app installed?)"
        }
        "secure/autofill_service" -> value?.substringBefore('/').let { app ->
            if (app != null && (app in installing || Device.versionCode(app) != null)) null else "$app is not installed"
        }
        else -> null
    }

    private fun read(key: String): String? = when (key) {
        "density" -> Device.density()
        "navigation" -> Device.navigationModes().entries.firstOrNull { it.value }?.key
        "language" -> Device.languages().ifEmpty { null }
        "timeZone" -> Device.timeZone().ifEmpty { null }
        "keyboard" -> read("secure/default_input_method")
        "dataSaver" -> Device.dataSaver()?.toString()
        "location" -> Device.location()?.toString()
        else -> if (key.startsWith("prop/")) Device.property(key.substringAfter('/')).ifEmpty { null } else {
            val table = key.substringBefore('/')
            tables.getOrPut(table) {
                if (table.startsWith("lineage.")) Device.lineageSettings(table.removePrefix("lineage.")) else Device.settings(table)
            }[key.substringAfter('/')]
        }
    }

    private fun write(key: String, value: String?): Boolean = when (key) {
        "density" -> Device.setDensity(value)
        "navigation" -> value != null && Device.setNavigationMode(value)
        "language" -> value != null && Device.setLanguages(value)
        "timeZone" -> value != null && Device.setTimeZone(value)
        "keyboard" -> (value != null && Device.setKeyboard(value)).also { tables.remove("secure") }
        "dataSaver" -> value != null && Device.setDataSaver(value == "true")
        "location" -> value != null && Device.setLocation(value == "true")
        else -> if (key.startsWith("prop/")) Device.setProperty(key.substringAfter('/'), value ?: "") else {
            val table = key.substringBefore('/')
            val name = key.substringAfter('/')
            val ok = if (table.startsWith("lineage.")) Device.putLineageSetting(table.removePrefix("lineage."), name, value)
            else Device.putSetting(table, name, value)
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

    /** The option that sets the setting, for warnings. */
    private fun option(key: String) = labels[key] ?: LABELS[key] ?: key

    /** The option and the setting, for what changes. */
    private fun label(key: String) = labels[key]?.let { "$it ($key)" } ?: LABELS[key] ?: key

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

    /** What a setting needs of the phone: [missing] says why the phone does not have it, or is null when it does (asked once). */
    private class Need(check: () -> String?) {
        val missing: String? by lazy(check)
    }

    companion object {
        private val TABLES = listOf("global", "secure", "system", "lineage.global", "lineage.secure", "lineage.system")
        private val COMMANDS = listOf("density", "navigation", "language", "timeZone", "keyboard", "dataSaver", "location")

        /** Values that change along with a setting: Night Light's on and off by its schedule, the language's property (empty: the ROM's own) by the language, the keyboards enabled by the keyboard. */
        private val COMPANIONS = mapOf(
            "secure/night_display_auto_mode" to listOf("secure/night_display_activated"),
            "secure/night_display_custom_start_time" to listOf("secure/night_display_activated"),
            "secure/night_display_custom_end_time" to listOf("secure/night_display_activated"),
            "language" to listOf("prop/persist.sys.locale"),
            "keyboard" to listOf("secure/enabled_input_methods"),
        )
        private val COMPANION_KEYS = COMPANIONS.values.flatten().toSet()

        private val LABELS = mapOf(
            "density" to "android.settings.display.density", "navigation" to "android.settings.navigation.mode",
            "language" to "android.settings.language", "timeZone" to "android.settings.time.timeZone",
            "keyboard" to "android.settings.keyboard.default", "dataSaver" to "android.settings.network.dataSaver",
            "location" to "android.settings.location.enable",
        )

        private fun release(sdk: Int) = mapOf(30 to "11", 31 to "12", 32 to "12L", 33 to "13", 34 to "14", 35 to "15", 36 to "16")[sdk] ?: "API $sdk"

        private fun sdk(level: Int) = Need {
            if (Device.sdk >= level) null else "it needs Android ${release(level)} or newer (this phone has Android ${release(Device.sdk)})"
        }

        private fun config(name: String, what: String) = Need { if (Device.configBool(name) == true) null else "this phone has no $what" }
        private fun configText(name: String, what: String) = Need { if (!Device.configString(name).isNullOrEmpty()) null else "this phone has no $what" }
        private fun configList(name: String, what: String) = Need { if ((Device.configIntArray(name)?.size ?: 0) > 0) null else "this phone has no $what" }
        private fun feature(name: String, what: String) = Need { if (Device.hasFeature(name)) null else "this phone has no $what" }
        private fun sensor(type: Int, what: String) = Need { if (Device.hasSensor(type)) null else "this phone has no $what" }
        private fun property(name: String, value: String, what: String) = Need { if (Device.property(name) == value) null else "this phone has no $what" }
        private fun service(name: String, what: String) = Need { if (Device.hasService(name)) null else "this phone has no $what" }

        private val lineage = Need { if (Device.lineage) null else "this ROM is not based on LineageOS (it has no LineageOS settings)" }
        private val vibrator = Need { if (Device.hasVibrator()) null else "this phone has no vibrator" }
        private val refreshRates = Need { if (Device.refreshRates().size > 1) null else "this phone's screen has one refresh rate" }
        private val nightLight = config("config_nightDisplayAvailable", "Night Light")
        private val screenSaver = config("config_dreamsSupported", "screen saver")
        private val oneHanded = property("ro.support_one_handed_mode", "true", "one-handed mode")
        private val flashlight = feature("android.hardware.camera.flash", "flashlight")
        private val chargingControl = service("vendor.lineage.health.IChargingControl/default", "charging control")

        /** What each setting needs of the phone, beyond being in the settings table it is in. */
        private val NEEDS: Map<String, List<Need>> = mapOf(
            "secure/font_weight_adjustment" to listOf(sdk(31)),
            "system/peak_refresh_rate" to listOf(refreshRates),
            "system/min_refresh_rate" to listOf(refreshRates),
            "system/display_color_mode" to listOf(configList("config_availableColorModes", "colour modes")),
            "secure/doze_always_on" to listOf(config("config_dozeAlwaysOnDisplayAvailable", "always-on display")),
            "secure/doze_enabled" to listOf(configText("config_dozeComponent", "ambient display")),
            "secure/double_tap_to_wake" to listOf(config("config_supportDoubleTapWake", "double-tap to wake")),
            "secure/wake_gesture_enabled" to listOf(sensor(23, "lift-to-wake sensor")),
            "secure/night_display_auto_mode" to listOf(nightLight),
            "secure/night_display_custom_start_time" to listOf(nightLight),
            "secure/night_display_custom_end_time" to listOf(nightLight),
            "secure/night_display_color_temperature" to listOf(nightLight),
            "secure/screensaver_enabled" to listOf(screenSaver),
            "secure/screensaver_activate_on_sleep" to listOf(screenSaver),
            "secure/screensaver_activate_on_dock" to listOf(screenSaver),
            "system/haptic_feedback_enabled" to listOf(vibrator),
            "secure/charging_vibration_enabled" to listOf(vibrator),
            "system/vibrate_when_ringing" to listOf(vibrator),
            "system/apply_ramping_ringer" to listOf(sdk(33), vibrator),
            "system/keyboard_vibration_enabled" to listOf(sdk(34), vibrator),
            "system/notification_light_pulse" to listOf(config("config_intrusiveNotificationLed", "notification light")),
            "system/camera_flash_notification" to listOf(sdk(34), flashlight),
            "system/screen_flash_notification" to listOf(sdk(34)),
            "secure/lockscreen_show_wallet" to listOf(sdk(31)),
            "secure/lockscreen_show_controls" to listOf(sdk(31)),
            "secure/lock_screen_show_qr_code_scanner" to listOf(sdk(33), configText("config_defaultQrCodeComponent", "QR code scanner")),
            "secure/media_controls_lock_screen" to listOf(sdk(33)),
            "secure/one_handed_mode_enabled" to listOf(sdk(31), oneHanded),
            "secure/swipe_bottom_to_notification_enabled" to listOf(sdk(31), oneHanded),
            "global/power_button_long_press" to listOf(sdk(31)),
            "global/key_chord_power_volume_up" to listOf(sdk(31)),
            "secure/volume_hush_gesture" to listOf(config("config_volumeHushGestureEnabled", "prevent-ringing shortcut")),
            "global/disable_window_blurs" to listOf(sdk(31)),
            "global/mobile_data_always_on" to listOf(feature("android.hardware.telephony", "mobile network")),
            "lineage.system/proximity_on_wake" to listOf(lineage, sensor(8, "proximity sensor")),
            "lineage.system/torch_long_press_power_gesture" to listOf(lineage, flashlight),
            "lineage.system/torch_long_press_power_timeout" to listOf(lineage, flashlight),
            "lineage.system/charging_control_enabled" to listOf(lineage, chargingControl),
            "lineage.system/charging_control_mode" to listOf(lineage, chargingControl),
            "lineage.system/charging_control_charging_limit" to listOf(lineage, chargingControl),
            "lineage.system/charging_control_start_time" to listOf(lineage, chargingControl),
            "lineage.system/charging_control_target_time" to listOf(lineage, chargingControl),
        )

        /** What a setting needs: what is listed for it; every one of LineageOS's needs a ROM based on it. */
        private fun needs(key: String): List<Need> = NEEDS[key] ?: if (key.startsWith("lineage.")) listOf(lineage) else emptyList()
    }
}
