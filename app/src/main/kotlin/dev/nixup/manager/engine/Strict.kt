package dev.nixup.manager.engine

import org.json.JSONObject
import java.io.File

/**
 * android.strict: the phone has the config's apps and no others. A build removes each app of the main user that is not
 * one of the system's (installed by hand, from a store, sideloaded) when no list of android.packages has it, nor
 * android.strict.keep, nor the config needs it otherwise (a user's androidApp, DAVx⁵ for services.davx5). It is hidden
 * from the user (`pm hide`), as Android's device policies remove an app: gone for the user (no icon, it cannot run or be
 * found by other apps), but kept on the phone as it was, with its data and all Android keeps of it (permissions, special
 * access, notification settings, accounts, its installer of record). An uninstall would lose those, and a reinstall can be
 * refused (a phone maker's own preloads, as One UI's). The apps it hid are listed in hidden, so it brings them back
 * (`pm unhide`) exactly as they were when strict is off again, when keep or a list names one, and every one of them when
 * the module is removed. Nixup's app and the root solution's always stay, and the launcher and the keyboard in use are
 * refused, as debloat refuses them (the apps that came with the phone are debloat's).
 */
class Strict(data: File, spec: JSONObject? = null, needed: Set<String> = emptySet()) {
    private val hiddenFile = File(data, "hidden")

    /** With strict on ([spec]), the apps it leaves besides the lists': keep's, and the ones the config needs ([needed]); null while it is off. */
    val keep: Set<String>? = spec?.let { json -> json.optJSONArray("keep")?.let { list -> (0 until list.length()).map { list.getString(it) } }.orEmpty().toSet() + needed }

    private var removals: List<String> = emptyList()
    private var comebacks: List<String> = emptyList()

    /** The apps it hid that are no longer hidden (installed again, or unhidden by hand) or no longer on the phone. */
    private var stale: List<String> = emptyList()

    /** The apps this build removes. */
    val removing: Set<String> get() = removals.toSet()

    /** The apps this build brings back. */
    val bringingBack: Set<String> get() = comebacks.toSet()

    val changes: Boolean get() = removals.isNotEmpty() || comebacks.isNotEmpty() || stale.isNotEmpty()

    /**
     * What an app it hid is (its version and keys), while it is: a list that has the app now plans from that, as an
     * update of it once it is back (it stays the user's app, not one the module installed).
     */
    fun hidden(id: String): Device.Installed? = if (id in readHidden() && Device.hiddenForUser(id)) Device.installed(id, hidden = true) else null

    /**
     * Plans the apps to remove, the main user's that no list has ([listed]) nor [keep], but for those going already as
     * they left the config ([leaving]: the module's own), and the apps to bring back. [homeApp] is the app the config
     * makes the home app and [keyboardApp] the one whose keyboard it sets. Says what will change, and adds what keeps the
     * build from running to [problems]. (The settings the config gives an app it removes are told of by AppSettings.)
     */
    fun plan(listed: Set<String>, leaving: Set<String>, homeApp: String?, keyboardApp: String?, problems: MutableList<String>) {
        val users = Device.userApps()
        val toRemove = ArrayList<String>()
        val warnings = ArrayList<String>()
        if (keep != null) {
            val stays = ROOT_APPS + NIXUP + listOfNotNull(Device.hiddenMagiskApp())
            val admins = Device.deviceAdmins()
            val launchers = runCatching { Device.roleHolders("android.app.role.HOME") }.getOrNull()
            val keyboard = Device.secureSetting("default_input_method").substringBefore('/')
            val why = "no list of android.packages has it, nor android.strict.keep"
            for (id in users.sorted()) {
                if (id in listed || id in keep || id in leaving || id in stays) continue
                val refused = when {
                    id == homeApp -> "android.apps.\"$id\".defaultFor: $id is to be the home app, and strict would remove it ($why): list it, or add it to android.strict.keep"
                    launchers.orEmpty().contains(id) && homeApp == null ->
                        "android.strict: $id is the launcher in use, and strict would remove it ($why): list it, add it to android.strict.keep, or make another app the home app (android.apps.<app>.defaultFor = [ \"home\" ])"
                    id == keyboardApp -> "android.settings.keyboard.default: it is $id's keyboard, and strict would remove $id ($why): list it, or add it to android.strict.keep"
                    id == keyboard && keyboardApp == null ->
                        "android.strict: $id's keyboard is the one in use, and strict would remove it ($why): list it, add it to android.strict.keep, or set another keyboard (android.settings.keyboard.default)"
                    else -> null
                }
                when {
                    refused != null -> problems += refused
                    id in admins -> warnings += "android.strict: $id is a device admin app, which Android does not let go, so it is left: add it to android.strict.keep, " +
                        "or let it go in Settings → Security → Device admin apps"
                    else -> toRemove += id
                }
            }
            // (Without the launcher in use known, one of them may be it: none goes, rather than leave the phone without one.)
            if (launchers == null && toRemove.isNotEmpty()) {
                warnings += "android.strict: which app is the launcher in use cannot be read here, so strict removes nothing this time (${toRemove.joinToString(", ")})"
                toRemove.clear()
            }
        }
        val hidden = readHidden()
        val stillHidden = hidden.filter { Device.hiddenForUser(it) }
        removals = toRemove
        comebacks = stillHidden.filter { keep == null || it in listed || it in keep }
        stale = hidden - stillHidden.toSet()

        val lines = ArrayList<String>()
        if (removals.isNotEmpty()) lines += "  to remove (${removals.size}, on no list; hidden for this user, they stay on the phone as they are): ${removals.joinToString(", ")}"
        if (comebacks.isNotEmpty()) {
            val why = if (keep == null) "strict is off" else "a list or android.strict.keep has them now"
            lines += "  to bring back (${comebacks.size}, as they were: $why): ${comebacks.joinToString(", ")}"
        }
        if (stale.isNotEmpty()) lines += "  no longer hidden by it (${stale.size}, installed again or unhidden by hand, or gone): ${stale.joinToString(", ")}"
        if (lines.isNotEmpty()) {
            Out.line("Strict:")
            lines.forEach { Out.line(it) }
        }
        warnings.forEach { Out.line("⚠ $it") }
    }

    /** Brings back the apps planned, before the installs (a list that has one now may update it after). Returns how many failed. */
    fun bringBack(): Int {
        if (stale.isNotEmpty()) writeHidden(readHidden() - stale.toSet())
        return comebacks.count { !unhide(it) }
    }

    /** Removes the apps planned. Returns how many could not be removed. */
    fun remove(): Int = removals.count { !hide(it) }

    /** Brings back every app it hid (the module is being removed); one no longer hidden is let go. Returns how many failed. */
    fun restoreAll(): Int {
        val (hidden, stale) = readHidden().partition { Device.hiddenForUser(it) }
        if (stale.isNotEmpty()) writeHidden(readHidden() - stale.toSet())
        return hidden.count { !unhide(it) }
    }

    private fun hide(id: String): Boolean {
        // Listed first: an app hidden is never one the module has lost track of.
        writeHidden(readHidden() + id)
        Device.hide(id)
        // By what the phone has after, not by what pm said.
        if (Device.hiddenForUser(id) && !Device.installedForUser(id)) {
            Out.line("✔ Removed $id for this user (hidden: it stays on the phone as it is, with its data; android.strict.keep, a list, or strict off brings it back)")
            return true
        }
        writeHidden(readHidden() - id)
        Out.line("✘ Could not remove $id")
        return false
    }

    private fun unhide(id: String): Boolean {
        Device.unhide(id)
        if (!Device.installedForUser(id)) {
            Out.line("✘ Could not bring $id back")
            return false
        }
        writeHidden(readHidden() - id)
        Out.line("✔ $id back as it was")
        return true
    }

    private fun readHidden(): List<String> = if (hiddenFile.isFile) hiddenFile.readLines().filter { it.isNotBlank() } else emptyList()

    private fun writeHidden(ids: List<String>) {
        if (ids.isEmpty()) hiddenFile.delete() else writeWhole(hiddenFile, ids.distinct().joinToString("") { "$it\n" })
    }

    companion object {
        private const val NIXUP = "dev.nixup.manager"

        /** The root solutions' own apps (Magisk and its forks, KernelSU and its forks, APatch), which strict always leaves. */
        private val ROOT_APPS = setOf(
            "com.topjohnwu.magisk", "io.github.vvb2060.magisk", "io.github.huskydg.magisk",
            "me.weishu.kernelsu", "com.rifsxd.ksunext", "com.sukisu.ultra", "me.bmax.apatch",
        )
    }
}
