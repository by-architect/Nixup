package dev.nixup.manager.engine

import org.json.JSONObject
import java.io.File

/**
 * Apps that came with the phone, removed: android.apps.<app>.debloat = true. Each is uninstalled for the main user with
 * its data kept (`pm uninstall -k --user 0`); the phone keeps it, so `cmd package install-existing` brings it back as
 * it was, with the enabled state it had, kept in original/debloat.json: when debloat is taken out again, and every one of
 * them when the module is removed. Parts Android needs are refused, and so are the launcher and the keyboard in use.
 */
class Debloat(data: File) {
    private val journalFile = File(data, "original/debloat.json")
    private val journal: JSONObject = if (journalFile.isFile) JSONObject(journalFile.readText()) else JSONObject()

    private var remove: List<String> = emptyList()
    private var bringBack: List<String> = emptyList()

    /** The apps this build removes. */
    val removing: Set<String> get() = remove.toSet()

    val changes: Boolean get() = remove.isNotEmpty() || bringBack.isNotEmpty()

    /**
     * Plans the apps to remove ([wanted]: those with debloat = true); [homeApp] is the app the config makes the home app (it
     * can take over from a launcher being removed). Says what will change, and adds what keeps the build from running to
     * [problems].
     */
    fun plan(wanted: List<String>, homeApp: String?, problems: MutableList<String>) {
        val warnings = ArrayList<String>()
        val toRemove = ArrayList<String>()
        val removed = ArrayList<String>()
        val home = Device.roleHolders("android.app.role.HOME").toSet()
        val keyboard = Device.secureSetting("default_input_method").substringBefore('/')
        for (id in wanted) {
            val app = Device.preinstalled(id)
            when {
                app == null -> warnings += "android.apps.\"$id\".debloat: this phone has no $id, so it is passed over"
                !app.system -> warnings += "android.apps.\"$id\".debloat: $id did not come with the phone, so it is left (uninstall it by hand to have it gone)"
                !app.installed -> removed += id
                else -> {
                    val refused = when {
                        app.core != null -> "android.apps.\"$id\".debloat: $id is a part of Android itself (${app.core}), so it cannot be removed"
                        PROTECTED.any { id == it || (it.endsWith(".") && id.startsWith(it)) } -> "android.apps.\"$id\".debloat: $id is a part Android needs, so it cannot be removed"
                        OVERLAY.containsMatchIn(id) -> "android.apps.\"$id\".debloat: $id is an overlay of the system (it changes how others look or work), so it cannot be removed"
                        id in home && (homeApp == null || homeApp == id) ->
                            "android.apps.\"$id\".debloat: $id is the launcher in use: make another app the home app first (android.apps.<app>.defaultFor = [ \"home\" ])"
                        id == keyboard -> "android.apps.\"$id\".debloat: $id is the keyboard in use: switch to another keyboard first"
                        else -> null
                    }
                    if (refused != null) problems += refused else toRemove += id
                }
            }
        }
        remove = toRemove
        bringBack = journal.keys().asSequence().filter { it !in wanted }.toList()

        val lines = ArrayList<String>()
        if (toRemove.isNotEmpty()) lines += "  to remove (${toRemove.size}, for this user; their data is kept): ${toRemove.joinToString(", ")}"
        if (bringBack.isNotEmpty()) lines += "  to bring back (${bringBack.size}, debloat is no longer set): ${bringBack.joinToString(", ")}"
        if (removed.isNotEmpty()) lines += "  removed already (${removed.size}): ${removed.joinToString(", ")}"
        if (lines.isNotEmpty()) {
            Out.line("Debloat:")
            lines.forEach { Out.line(it) }
        }
        warnings.forEach { Out.line("⚠ $it") }
    }

    /** Removes the apps planned and brings back those that left the list. Returns how many failed. */
    fun apply(): Int {
        var failed = 0
        for (id in remove) {
            remember(id, Device.dump(id)?.enabled ?: 0)
            if (Device.uninstallForUser(id) && Device.preinstalled(id)?.installed == false) {
                Out.line("✔ Removed $id for this user (its data is kept: taking debloat out again brings it back)")
            } else {
                Out.line("✘ Could not remove $id")
                failed++
            }
        }
        for (id in bringBack) failed += bringBack(id)
        return failed
    }

    /** Brings back every app the module removed (the module is being removed). Returns how many failed. */
    fun restoreAll(): Int = journal.keys().asSequence().toList().sumOf { bringBack(it) }

    private fun bringBack(id: String): Int {
        val app = Device.preinstalled(id)
        // The phone no longer has it (a ROM update took it out): there is nothing to bring back.
        if (app == null) {
            forget(id)
            return 0
        }
        if (!app.installed && !Device.installExisting(id)) {
            Out.line("✘ Could not bring $id back")
            return 1
        }
        val enabled = journal.optJSONObject(id)?.optInt("enabled", 0) ?: 0
        if (enabled != 0 && Device.dump(id)?.enabled != enabled) Device.setEnabledState(id, enabled)
        forget(id)
        Out.line("✔ $id back as before")
        return 0
    }

    private fun remember(id: String, enabled: Int) {
        if (journal.has(id)) return
        journal.put(id, JSONObject().put("enabled", enabled))
        save()
    }

    private fun forget(id: String) {
        if (journal.remove(id) != null) save()
    }

    private fun save() {
        journalFile.parentFile?.mkdirs()
        val fresh = File(journalFile.path + ".new")
        fresh.writeText(journal.toString(1))
        fresh.renameTo(journalFile)
    }

    companion object {
        /** Apps Android needs that do not run as a system user (those are refused anyway); a name ending with a dot covers all under it. */
        private val PROTECTED = listOf(
            "com.android.systemui", "com.android.settings", "com.android.phone", "com.android.shell",
            "com.android.packageinstaller", "com.google.android.packageinstaller",
            "com.android.permissioncontroller", "com.google.android.permissioncontroller",
            "com.android.documentsui", "com.google.android.documentsui", "com.android.externalstorage",
            "com.android.intentresolver", "com.android.webview", "com.google.android.webview",
            "com.android.providers.", "com.google.android.providers.", "com.android.networkstack", "com.google.android.networkstack",
            "com.android.networkstack.", "com.google.android.networkstack.", "dev.nixup.manager",
        )

        /** The names of a ROM's resource overlays (they change how another app or Android looks or works). */
        private val OVERLAY = Regex("""auto_generated_rro|\.overlay(\.|$)|\.resources$""")
    }
}
