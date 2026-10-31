package dev.andronix.manager.engine

import org.json.JSONObject
import java.io.File

/**
 * The settings of apps, from the profile's android.apps. Each setting has a key ("perm:android.permission.CAMERA",
 * "op:SYSTEM_ALERT_WINDOW", "battery", "net:POLICY_REJECT_WIFI", ...) and a state read from Android as a short
 * string. The state an app had before the module first changed a setting is kept in original/apps.json, and is
 * written back when the setting leaves the config, and for every app when the module is removed.
 */
class AppSettings(data: File) {
    private val journalFile = File(data, "original/apps.json")
    private val journal: JSONObject = if (journalFile.isFile) JSONObject(journalFile.readText()) else JSONObject()
    private val dumps = HashMap<String, Device.Dump?>()

    /** A setting the config wants: what to call it, the state to write, and whether a state read counts as it. */
    private class Want(val key: String, val label: String, val state: String, val holds: (String) -> Boolean)

    private class Planned(val app: String, val wants: List<Want>?, val giveBack: List<String>)

    private var planned: List<Planned> = emptyList()

    /** Whether the plan changes anything: a setting that differs, one given back, or an app to set up once installed. */
    val changes: Boolean get() = planned.any { p -> p.giveBack.isNotEmpty() || p.wants == null || p.wants.any { !holds(p.app, it) } }

    /**
     * Plans the settings of the profile's [apps]: an app must be installed, or be [installing] in this build. Adds
     * what keeps the build from running to [problems], and says what will change.
     */
    fun plan(apps: JSONObject, installing: Set<String>, removing: Set<String>, problems: MutableList<String>) {
        val plans = ArrayList<Planned>()
        val warnings = ArrayList<String>()
        for (app in apps.keys()) {
            when {
                app in removing -> problems += "android.apps.\"$app\": it leaves android.packages.fdroid, so it would not be installed any more"
                dump(app) != null -> {
                    val wants = wants(app, apps.getJSONObject(app), problems, warnings)
                    plans += Planned(app, wants, journalKeys(app) - wants.map { it.key }.toSet())
                }
                app in installing -> plans += Planned(app, null, emptyList())
                else -> problems += "android.apps.\"$app\": $app is not installed (add it to android.packages.fdroid, or install it)"
            }
        }
        for (app in journal.keys()) {
            if (!apps.has(app)) plans += Planned(app, emptyList(), journalKeys(app))
        }
        planned = plans

        val lines = ArrayList<String>()
        for (p in plans) {
            if (p.wants == null) {
                lines += "  ${p.app}: set up once it is installed"
                continue
            }
            val change = p.wants.filter { !holds(p.app, it) }
            if (change.isNotEmpty()) lines += "  ${p.app}: ${change.joinToString(", ") { it.label }}"
            if (p.giveBack.isNotEmpty()) lines += "  ${p.app}: back as before: ${p.giveBack.joinToString(", ") { describe(it) }}"
            if (change.isEmpty() && p.giveBack.isEmpty() && p.wants.isNotEmpty()) lines += "  ${p.app}: as the config says (${p.wants.size} settings)"
        }
        if (lines.isNotEmpty()) {
            Out.line("App settings:")
            lines.forEach { Out.line(it) }
        }
        warnings.forEach { Out.line("⚠ $it") }
    }

    /** Applies the plan: each setting that differs is changed and read back, and settings that left the config are given back. Returns how many failed. */
    fun apply(apps: JSONObject, removing: Set<String>): Int {
        // Apps were installed and updated since the plan.
        dumps.clear()
        var failed = 0
        for (p in planned) {
            if (p.app in removing) continue
            if (dump(p.app) == null) {
                forget(p.app)
                continue
            }
            // An app installed in this build is planned now; what does not fit it can only be reported.
            val wants = p.wants ?: run {
                val problems = ArrayList<String>()
                val warnings = ArrayList<String>()
                val fresh = wants(p.app, apps.getJSONObject(p.app), problems, warnings)
                warnings.forEach { Out.line("⚠ $it") }
                problems.forEach { Out.line("✘ $it") }
                failed += problems.size
                fresh
            }
            for (want in wants) failed += change(p.app, want)
            for (key in p.giveBack) failed += giveBack(p.app, key)
        }
        return failed
    }

    /** Gives every setting the module changed back (the module is being removed). Returns how many failed. */
    fun restoreAll(): Int {
        var failed = 0
        for (app in journal.keys().asSequence().toList()) {
            if (dump(app) == null) {
                forget(app)
                continue
            }
            for (key in journalKeys(app)) failed += giveBack(app, key)
        }
        return failed
    }

    /** Drops what was kept for apps that are no longer on the phone (removed in this build, or by hand). */
    fun forgetRemoved(apps: Collection<String>) = apps.forEach { forget(it) }

    private fun change(app: String, want: Want): Int {
        val now = read(app, want.key)
        if (now == null) {
            Out.line("✘ $app: ${want.label}: cannot read it here")
            return 1
        }
        if (want.holds(now)) return 0
        remember(app, want.key, now)
        write(app, want.key, want.state)
        val after = read(app, want.key)
        if (after != null && want.holds(after)) {
            Out.line("✔ $app: ${want.label}")
            return 0
        }
        Out.line("✘ $app: ${want.label} did not take (it reads $after)")
        return 1
    }

    private fun giveBack(app: String, key: String): Int {
        val original = journal.optJSONObject(app)?.optString(key) ?: return 0
        write(app, key, original)
        val after = read(app, key)
        forgetKey(app, key)
        if (after == original || (key.startsWith("op:") && sameOp(after, original))) {
            Out.line("✔ $app: ${describe(key)} back as before")
            return 0
        }
        Out.line("✘ $app: ${describe(key)} could not go back to $original (it reads $after)")
        return 1
    }

    private fun holds(app: String, want: Want) = read(app, want.key)?.let(want.holds) ?: false

    // --- What the config's options mean, setting by setting.

    private fun wants(app: String, settings: JSONObject, problems: MutableList<String>, warnings: MutableList<String>): List<Want> {
        val dump = dump(app) ?: return emptyList()
        val wants = ArrayList<Want>()

        settings.optJSONObject("permissions")?.let { permissions ->
            for (group in permissions.keys()) {
                if (group == "location" || group == "preciseLocation") continue
                val members = GROUPS[group] ?: continue
                permissionWants(app, dump, "permissions.$group", members.associateWith { access(permissions.get(group)) }, warnings, wants)
            }
            if (permissions.has("location")) {
                val location = permissions.get("location")
                val precise = permissions.opt("preciseLocation") as? Boolean
                val (foreground, background) = when (location) {
                    false -> DENY to DENY
                    "ask" -> ASK to ASK
                    "always" -> ALLOW to ALLOW
                    else -> ALLOW to DENY
                }
                val fine = if (foreground == ALLOW && precise == false) DENY else foreground
                permissionWants(
                    app, dump, "permissions.location",
                    mapOf(COARSE to foreground, FINE to fine, BACKGROUND to background), warnings, wants,
                )
            }
        }

        settings.optString("battery").takeIf { it.isNotEmpty() }?.let { battery ->
            val (_, system) = Device.batteryExempt()
            if (app in system && battery != "unrestricted") {
                problems += "android.apps.\"$app\".battery: the system keeps $app unrestricted, so it cannot be \"$battery\""
            } else {
                wants += Want("battery", "battery: $battery", if (battery == "unrestricted") "1" else "0") { it == (if (battery == "unrestricted") "1" else "0") }
                wants += opWant("op:RUN_ANY_IN_BACKGROUND", "background running ${if (battery == "restricted") "restricted" else "allowed"}",
                    if (battery == "restricted") "ignore" else "allow")
            }
        }

        settings.optJSONObject("network")?.let { network ->
            for (option in network.keys()) {
                val (flag, blockedWhen) = NETWORK[option] ?: continue
                val allowed = network.getBoolean(option)
                if (Device.networkFlag(flag) == null || Device.uid(app) == null) {
                    problems += "android.apps.\"$app\".network.$option: this system has no such switch ($flag)"
                    continue
                }
                val bit = if (allowed == blockedWhen) "1" else "0"
                wants += Want("net:$flag", "network.$option ${if (allowed) "on" else "off"}", bit) { it == bit }
            }
        }

        settings.optJSONObject("access")?.let { access ->
            // Restricted settings first: Android refuses notification access to a sideloaded app until they are allowed.
            for (option in access.keys().asSequence().sortedBy { it != "restrictedSettings" }) {
                val allowed = access.getBoolean(option)
                when (option) {
                    "notificationAccess", "accessibility" -> {
                        val action = if (option == "notificationAccess") LISTENER_ACTION else ACCESSIBILITY_ACTION
                        if (Device.services(app, action).isEmpty()) {
                            if (allowed) warnings += "$app has no ${describe(option)} service: access.$option is left out"
                            continue
                        }
                        val state = if (allowed) "1" else "0"
                        wants += Want(option, "access.$option ${if (allowed) "on" else "off"}", state) { it == state }
                    }
                    else -> {
                        val (op, permission) = ACCESS[option] ?: continue
                        if (permission != null && permission !in dump.requested) {
                            if (allowed) warnings += "$app does not ask for $permission: access.$option is left out"
                            continue
                        }
                        if (Device.appOp(app, op) == null) {
                            problems += "android.apps.\"$app\".access.$option: this Android version has no $op"
                            continue
                        }
                        wants += opWant("op:$op", "access.$option ${if (allowed) "on" else "off"}", if (allowed) "allow" else "deny")
                    }
                }
            }
        }

        if (settings.has("pauseIfUnused")) {
            val pause = settings.getBoolean("pauseIfUnused")
            wants += opWant("op:AUTO_REVOKE_PERMISSIONS_IF_UNUSED", "pauseIfUnused ${if (pause) "on" else "off"}", if (pause) "allow" else "ignore")
        }
        if (settings.has("enabled")) {
            val enabled = settings.getBoolean("enabled")
            wants += Want("enabled", if (enabled) "enabled" else "disabled", if (enabled) "1" else "3") {
                (it == "0" || it == "1") == enabled
            }
        }
        if (settings.has("paused")) {
            val paused = settings.getBoolean("paused")
            wants += Want("suspended", if (paused) "paused" else "not paused", if (paused) "1" else "0") { it == (if (paused) "1" else "0") }
        }
        if (settings.has("openLinks")) {
            val open = settings.getBoolean("openLinks")
            if (Device.opensLinks(app) == null) {
                if (open) warnings += "$app supports no web links: openLinks is left out"
            } else {
                wants += Want("links", "openLinks ${if (open) "on" else "off"}", if (open) "1" else "0") { it == (if (open) "1" else "0") }
            }
        }
        settings.optString("language").takeIf { it.isNotEmpty() }?.let { language ->
            val tags = if (language == "system") "" else language
            wants += Want("language", "language ${language}", tags) { it.equals(tags, ignoreCase = true) }
        }
        settings.optJSONArray("defaultFor")?.let { roles ->
            for (i in 0 until roles.length()) {
                val role = ROLES[roles.getString(i)] ?: continue
                wants += Want("role:$role", "default ${roles.getString(i)}", app) { it == app }
            }
        }
        return wants
    }

    /** The permissions of one option that the app asks for, each with the state wanted. */
    private fun permissionWants(
        app: String, dump: Device.Dump, option: String, states: Map<String, String>,
        warnings: MutableList<String>, wants: MutableList<Want>,
    ) {
        val asked = states.filterKeys { it in dump.runtime }
        if (asked.isEmpty()) {
            if (states.values.any { it == ALLOW }) warnings += "$app does not ask for ${option.substringAfter('.')}: $option is left out"
            return
        }
        for ((permission, state) in asked) {
            val word = when (state) { ALLOW -> "allowed"; DENY -> "denied"; else -> "ask" }
            wants += Want("perm:$permission", "$option: ${permission.substringAfterLast('.')} $word", state) { now ->
                val (granted, userSet, userFixed) = now.split(',')
                when (state) {
                    ALLOW -> granted == "1" && userFixed == "0"
                    DENY -> granted == "0" && userFixed == "1"
                    else -> granted == "0" && userSet == "0" && userFixed == "0"
                }
            }
        }
    }

    private fun opWant(key: String, label: String, mode: String): Want = Want(key, label, "$mode,$mode") { now ->
        val effective = effectiveOp(now)
        when (mode) {
            "allow" -> effective == "allow" || (effective == "default" && key.removePrefix("op:") in ALLOWED_BY_DEFAULT)
            "ignore" -> effective == "ignore"
            else -> effective == "deny" || effective == "ignore"
        }
    }

    private fun access(value: Any): String = when (value) {
        true -> ALLOW
        false -> DENY
        else -> ASK
    }

    // --- Reading and writing a setting's state, by its key.

    private fun read(app: String, key: String): String? = when {
        key.startsWith("perm:") -> dump(app)?.runtime?.get(key.removePrefix("perm:"))?.let {
            "${bit(it.granted)},${bit(it.userSet)},${bit(it.userFixed)}"
        }
        key.startsWith("op:") -> Device.appOp(app, key.removePrefix("op:"))?.let { "${it.first},${it.second}" }
        key == "battery" -> bit(app in Device.batteryExempt().first)
        key.startsWith("net:") -> {
            val flag = Device.networkFlag(key.removePrefix("net:"))
            val uid = Device.uid(app)
            val policy = uid?.let { Device.uidPolicy(it) }
            if (flag == null || policy == null) null else bit(policy and flag != 0)
        }
        key == "enabled" -> dump(app)?.enabled?.toString()
        key == "suspended" -> dump(app)?.let { bit(it.suspended) }
        key == "links" -> Device.opensLinks(app)?.let { bit(it) }
        key == "language" -> Device.appLanguages(app)
        key.startsWith("role:") -> Device.roleHolders(key.removePrefix("role:")).joinToString(",")
        key == "notificationAccess" -> components(app, LISTENER_ACTION, "enabled_notification_listeners")
        key == "accessibility" -> components(app, ACCESSIBILITY_ACTION, "enabled_accessibility_services")
        else -> null
    }

    private fun write(app: String, key: String, state: String) {
        dumps.remove(app)
        when {
            key.startsWith("perm:") -> {
                val (granted, userSet, userFixed) = state.split(',')
                Device.setPermission(app, key.removePrefix("perm:"), granted == "1", userSet == "1", userFixed == "1")
            }
            key.startsWith("op:") -> {
                val (uidMode, packageMode) = state.split(',')
                Device.setAppOp(app, key.removePrefix("op:"), uidMode, packageMode)
            }
            key == "battery" -> Device.setBatteryExempt(app, state == "1")
            key.startsWith("net:") -> {
                val flag = Device.networkFlag(key.removePrefix("net:"))
                val uid = Device.uid(app)
                if (flag != null && uid != null) Device.setUidPolicyFlag(uid, flag, state == "1")
            }
            key == "enabled" -> Device.setEnabledState(app, state.toInt())
            key == "suspended" -> Device.setSuspended(app, state == "1")
            key == "links" -> Device.setOpensLinks(app, state == "1")
            key == "language" -> Device.setAppLanguages(app, state)
            key.startsWith("role:") -> {
                val role = key.removePrefix("role:")
                val holders = state.split(',').filter { it.isNotEmpty() }
                if (holders.isEmpty()) Device.setRoleHolder(role, null) else holders.forEach { Device.setRoleHolder(role, it) }
            }
            key == "notificationAccess" -> Device.services(app, LISTENER_ACTION).forEach { Device.setNotificationListener(it, state == "1") }
            key == "accessibility" -> {
                val ours = Device.services(app, ACCESSIBILITY_ACTION).toSet()
                val others = enabledComponents("enabled_accessibility_services").filter { it !in ours }
                val enabled = if (state == "1") others + ours else others
                Device.putSecureSetting("enabled_accessibility_services", enabled.joinToString(":"))
                Device.putSecureSetting("accessibility_enabled", if (enabled.isEmpty()) "0" else "1")
            }
        }
    }

    /** "1" when all of the app's services for [action] are in the secure setting [key], else "0". */
    private fun components(app: String, action: String, key: String): String {
        val ours = Device.services(app, action)
        return bit(ours.isNotEmpty() && enabledComponents(key).containsAll(ours))
    }

    private fun enabledComponents(key: String) =
        Device.secureSetting(key).split(':').filter { it.isNotBlank() }.map { Device.fullComponent(it) }

    private fun dump(app: String): Device.Dump? = dumps.getOrPut(app) { Device.dump(app) }

    private fun effectiveOp(state: String?): String {
        val (uidMode, packageMode) = (state ?: "default,default").split(',')
        return if (uidMode != "default") uidMode else packageMode
    }

    private fun sameOp(a: String?, b: String) = a != null && effectiveOp(a) == effectiveOp(b)

    private fun describe(key: String): String = when {
        key.startsWith("perm:") -> key.substringAfterLast('.')
        key.startsWith("op:") -> key.removePrefix("op:")
        key.startsWith("net:") -> key.removePrefix("net:")
        key.startsWith("role:") -> "default " + key.substringAfterLast('.').lowercase()
        key == "notificationAccess" -> "notification access"
        key == "accessibility" -> "accessibility"
        else -> key
    }

    private fun bit(on: Boolean) = if (on) "1" else "0"

    // --- What was kept: original/apps.json, written as soon as it changes.

    private fun journalKeys(app: String): List<String> = journal.optJSONObject(app)?.keys()?.asSequence()?.toList() ?: emptyList()

    private fun remember(app: String, key: String, state: String) {
        val entries = journal.optJSONObject(app) ?: JSONObject().also { journal.put(app, it) }
        if (entries.has(key)) return
        entries.put(key, state)
        save()
    }

    private fun forgetKey(app: String, key: String) {
        val entries = journal.optJSONObject(app) ?: return
        entries.remove(key)
        if (entries.length() == 0) journal.remove(app)
        save()
    }

    private fun forget(app: String) {
        if (journal.remove(app) != null) save()
    }

    private fun save() {
        journalFile.parentFile?.mkdirs()
        val fresh = File(journalFile.path + ".new")
        fresh.writeText(journal.toString(1))
        fresh.renameTo(journalFile)
    }

    companion object {
        private const val ALLOW = "1,1,0"
        private const val DENY = "0,1,1"
        private const val ASK = "0,0,0"

        private const val COARSE = "android.permission.ACCESS_COARSE_LOCATION"
        private const val FINE = "android.permission.ACCESS_FINE_LOCATION"
        private const val BACKGROUND = "android.permission.ACCESS_BACKGROUND_LOCATION"

        /** App-ops whose "default" mode lets the app do it. */
        private val ALLOWED_BY_DEFAULT = setOf("RUN_ANY_IN_BACKGROUND", "AUTO_REVOKE_PERMISSIONS_IF_UNUSED", "PICTURE_IN_PICTURE", "CHANGE_WIFI_STATE")

        private const val LISTENER_ACTION = "android.service.notification.NotificationListenerService"
        private const val ACCESSIBILITY_ACTION = "android.accessibilityservice.AccessibilityService"

        private fun android(vararg names: String) = names.map { if (it.contains('.')) it else "android.permission.$it" }

        /** The runtime permissions behind each of the config's permissions (only those an app asks for are changed). */
        private val GROUPS = mapOf(
            "camera" to android("CAMERA"),
            "microphone" to android("RECORD_AUDIO"),
            "contacts" to android("READ_CONTACTS", "WRITE_CONTACTS", "GET_ACCOUNTS"),
            "calendar" to android("READ_CALENDAR", "WRITE_CALENDAR"),
            "phone" to android(
                "READ_PHONE_STATE", "READ_PHONE_NUMBERS", "CALL_PHONE", "ANSWER_PHONE_CALLS", "USE_SIP",
                "ACCEPT_HANDOVER", "com.android.voicemail.permission.ADD_VOICEMAIL",
            ),
            "callLogs" to android("READ_CALL_LOG", "WRITE_CALL_LOG", "PROCESS_OUTGOING_CALLS"),
            "sms" to android("SEND_SMS", "RECEIVE_SMS", "READ_SMS", "RECEIVE_WAP_PUSH", "RECEIVE_MMS", "READ_CELL_BROADCASTS"),
            "nearbyDevices" to android("BLUETOOTH_SCAN", "BLUETOOTH_CONNECT", "BLUETOOTH_ADVERTISE", "NEARBY_WIFI_DEVICES", "UWB_RANGING", "RANGING"),
            "physicalActivity" to android("ACTIVITY_RECOGNITION"),
            "bodySensors" to android(
                "BODY_SENSORS", "BODY_SENSORS_BACKGROUND", "android.permission.health.READ_HEART_RATE",
                "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND",
            ),
            "notifications" to android("POST_NOTIFICATIONS"),
            "photosAndVideos" to android("READ_MEDIA_IMAGES", "READ_MEDIA_VIDEO", "READ_MEDIA_VISUAL_USER_SELECTED"),
            "musicAndAudio" to android("READ_MEDIA_AUDIO"),
            "files" to android("READ_EXTERNAL_STORAGE", "WRITE_EXTERNAL_STORAGE"),
            "mediaLocation" to android("ACCESS_MEDIA_LOCATION"),
        )

        /** The app-op behind each special access, and the permission an app must ask for to have it (null: none). */
        private val ACCESS = mapOf(
            "displayOverOtherApps" to ("SYSTEM_ALERT_WINDOW" to "android.permission.SYSTEM_ALERT_WINDOW"),
            "modifySystemSettings" to ("WRITE_SETTINGS" to "android.permission.WRITE_SETTINGS"),
            "installUnknownApps" to ("REQUEST_INSTALL_PACKAGES" to "android.permission.REQUEST_INSTALL_PACKAGES"),
            "allFilesAccess" to ("MANAGE_EXTERNAL_STORAGE" to "android.permission.MANAGE_EXTERNAL_STORAGE"),
            "usageAccess" to ("GET_USAGE_STATS" to "android.permission.PACKAGE_USAGE_STATS"),
            "alarmsAndReminders" to ("SCHEDULE_EXACT_ALARM" to "android.permission.SCHEDULE_EXACT_ALARM"),
            "pictureInPicture" to ("PICTURE_IN_PICTURE" to null),
            "mediaManagement" to ("MANAGE_MEDIA" to "android.permission.MANAGE_MEDIA"),
            "fullScreenNotifications" to ("USE_FULL_SCREEN_INTENT" to "android.permission.USE_FULL_SCREEN_INTENT"),
            "turnScreenOn" to ("TURN_SCREEN_ON" to "android.permission.TURN_SCREEN_ON"),
            "wifiControl" to ("CHANGE_WIFI_STATE" to "android.permission.CHANGE_WIFI_STATE"),
            "restrictedSettings" to ("ACCESS_RESTRICTED_SETTINGS" to null),
        )

        /** The uid policy bit behind each network option, and whether the bit is set when the option is true (allowed) or false. */
        private val NETWORK = mapOf(
            "backgroundData" to ("POLICY_REJECT_METERED_BACKGROUND" to false),
            "unrestrictedData" to ("POLICY_ALLOW_METERED_BACKGROUND" to true),
            "wifi" to ("POLICY_REJECT_WIFI" to false),
            "mobile" to ("POLICY_REJECT_CELLULAR" to false),
            "vpn" to ("POLICY_REJECT_VPN" to false),
        )

        private val ROLES = mapOf(
            "browser" to "android.app.role.BROWSER",
            "dialer" to "android.app.role.DIALER",
            "sms" to "android.app.role.SMS",
            "home" to "android.app.role.HOME",
            "assistant" to "android.app.role.ASSISTANT",
            "callScreening" to "android.app.role.CALL_SCREENING",
        )
    }
}
