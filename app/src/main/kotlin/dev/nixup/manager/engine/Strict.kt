package dev.nixup.manager.engine

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * android.strict: the phone has the config's apps and no others. A build removes each app of the main user that is not
 * one of the system's (installed by hand, from a store, sideloaded) when no list of android.packages has it, nor
 * android.strict.keep, nor the config needs it otherwise (a user's androidApp, DAVx⁵ for services.davx5). It goes as an
 * app that left the config goes, its data kept (`pm uninstall -k`), and with a copy of its APKs in strict/<app>, and
 * what Android drops with it (its installer of record, enabled state and runtime permissions) in original/strict.json:
 * so it comes back as it was, from that copy and with the data Android kept, when strict is off again, when keep or a
 * list names it, and every one of them when the module is removed. Nixup's app and the root solution's always stay, and
 * the launcher and the keyboard in use are refused, as debloat refuses them (the apps that came with the phone are
 * debloat's).
 */
class Strict(private val data: File, spec: JSONObject? = null, needed: Set<String> = emptySet()) {
    private val journalFile = File(data, "original/strict.json")
    private val journal: JSONObject = if (journalFile.isFile) JSONObject(journalFile.readText()) else JSONObject()

    /** The copies of the apps' APKs, a folder each. */
    private val copies = File(data, "strict")

    /** With strict on ([spec]), the apps it leaves besides the lists': keep's, and the ones the config needs ([needed]); null while it is off. */
    val keep: Set<String>? = spec?.let { it.optJSONArray("keep").strings().toSet() + needed }

    private var removals: List<String> = emptyList()
    private var comebacks: List<String> = emptyList()

    /** The apps it removed that are on the phone again (installed by hand since): their copies are old. */
    private var again: List<String> = emptyList()

    /** The apps this build removes. */
    val removing: Set<String> get() = removals.toSet()

    /** The apps this build brings back. */
    val bringingBack: Set<String> get() = comebacks.toSet()

    val changes: Boolean get() = removals.isNotEmpty() || comebacks.isNotEmpty() || again.isNotEmpty()

    /**
     * What an app it removed was (its version and keys), while its copy is here: a list that has the app now plans from
     * that, as an update of it once it is back (it stays the user's app, not one the module installed).
     */
    fun copyOf(id: String): Device.Installed? {
        val entry = journal.optJSONObject(id) ?: return null
        if (copy(id, entry) == null) return null
        return Device.Installed(entry.optLong("versionCode"), entry.optString("versionName"), entry.optJSONArray("signers").strings().toSet())
    }

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
        var total = 0L
        var biggest = 0L
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
                if (refused != null) {
                    problems += refused
                    continue
                }
                if (id in admins) {
                    warnings += "android.strict: $id is a device admin app, which Android does not uninstall, so it is left: add it to android.strict.keep, " +
                        "or let it go in Settings → Security → Device admin apps"
                    continue
                }
                val size = Device.apkFiles(id).sumOf { it.length() }
                if (size <= 0) {
                    warnings += "android.strict: $id's APKs are not here (an archived app?), so no copy could bring it back: it is left"
                    continue
                }
                toRemove += id
                total += size
                biggest = maxOf(biggest, size)
            }
            // (Without the launcher in use known, one of them may be it: none goes, rather than leave the phone without one.)
            if (launchers == null && toRemove.isNotEmpty()) {
                warnings += "android.strict: which app is the launcher in use cannot be read here, so strict removes nothing this time (${toRemove.joinToString(", ")})"
                toRemove.clear()
            }
            // The copies are made one app at a time, and each app's own APKs go with it.
            val free = Device.freeBytes(data)
            if (toRemove.isNotEmpty() && free < biggest + Installer.RESERVE) {
                problems += "android.strict: not enough free space for the copies of the APKs of the apps it removes, one at a time: " +
                    "about ${Out.size(biggest + Installer.RESERVE)} is needed, ${Out.size(free)} is free"
            }
        }
        val removed = journal.keys().asSequence().toList()
        removals = toRemove
        comebacks = removed.filter { it !in users && (keep == null || it in listed || it in keep) }
        again = removed.filter { it in users && it !in toRemove }

        val lines = ArrayList<String>()
        if (removals.isNotEmpty()) {
            lines += "  to remove (${removals.size}, on no list; their data is kept, and a copy of their APKs, ${Out.size(total)}): ${removals.joinToString(", ")}"
        }
        if (comebacks.isNotEmpty()) {
            val why = if (keep == null) "strict is off" else "a list or android.strict.keep has them now"
            lines += "  to bring back (${comebacks.size}, as they were: $why): ${comebacks.joinToString(", ")}"
        }
        if (again.isNotEmpty()) lines += "  on the phone again (${again.size}, installed since; their copies go): ${again.joinToString(", ")}"
        if (lines.isNotEmpty()) {
            Out.line("Strict:")
            lines.forEach { Out.line(it) }
        }
        warnings.forEach { Out.line("⚠ $it") }
    }

    /**
     * Brings back the apps planned, from their copies, before the installs (a list that has one now may update it after);
     * drops the copies of those on the phone again. Returns how many failed.
     */
    fun bringBack(): Int {
        again.forEach { forget(it) }
        // (A copy that lost its record: one whose making was cut off.)
        copies.listFiles().orEmpty().filter { !journal.has(it.name) }.forEach { deleteTree(it) }
        return bringBack(comebacks)
    }

    /** Removes the apps planned, each once a copy of its APKs is kept. Returns how many could not be removed. */
    fun remove(): Int = removals.count { !removeOne(it) }

    /** Brings back every app it removed (the module is being removed). Returns how many failed. */
    fun restoreAll(): Int {
        val users = Device.userApps()
        val removed = journal.keys().asSequence().toList()
        removed.filter { it in users }.forEach { forget(it) }
        return bringBack(removed.filter { it !in users })
    }

    private fun bringBack(ids: List<String>): Int {
        // An app that installed others comes first, so it is their installer of record again.
        val installers = ids.mapNotNull { journal.optJSONObject(it)?.optString("installer") }.toSet()
        return ids.sortedBy { it !in installers }.sumOf { bringBackOne(it) }
    }

    private fun bringBackOne(id: String): Int {
        val entry = journal.optJSONObject(id) ?: JSONObject()
        val apks = copy(id, entry)
        // Its installer of record again, when that is on the phone.
        val installer = entry.optString("installer").takeIf { it.isNotEmpty() && Device.installedForUser(it) }
        val said = when {
            apks != null -> Device.installAll(apks, installer)
            // (No copy: the phone may still have the app, for another user.)
            Device.installExisting(id) -> null
            else -> {
                forget(id)
                Out.line("✘ $id cannot come back: its copy is gone (its data is still on the phone: installing it again brings it back)")
                return 1
            }
        }
        if (said != null || !Device.installedForUser(id)) {
            Out.line("✘ Could not bring $id back" + (said?.let { ": $it" } ?: ""))
            return 1
        }
        // What Android dropped with it, as it was.
        val enabled = entry.optInt("enabled", 0)
        if (enabled != 0 && Device.dump(id)?.enabled != enabled) Device.setEnabledState(id, enabled)
        grant(id, entry.optJSONObject("permissions"))
        forget(id)
        Out.line("✔ $id back as it was, with its data")
        return 0
    }

    private fun removeOne(id: String): Boolean {
        val apks = Device.apkFiles(id)
        val installed = Device.installed(id)
        val dump = Device.dump(id)
        val folder = File(copies, id)
        // The copy first, whole (into a folder of its own, then put in place): the app goes only once it can come back.
        val fresh = File(copies, ".$id.new")
        try {
            if (apks.isEmpty()) throw IOException("its APKs are not here")
            deleteTree(fresh)
            if (!fresh.mkdirs()) throw IOException("cannot make $fresh")
            for (apk in apks) {
                val to = apk.copyTo(File(fresh, apk.name))
                if (to.length() != apk.length()) throw IOException("${apk.name} was not copied whole")
            }
            deleteTree(folder)
            if (!fresh.renameTo(folder)) throw IOException("cannot put it in $folder")
        } catch (e: Exception) {
            deleteTree(fresh)
            Out.line("✘ Could not remove $id: no copy of its APKs could be kept (${e.message})")
            return false
        }
        val permissions = JSONObject()
        dump?.runtime?.forEach { (name, p) -> if (p.granted) permissions.put(name, JSONObject().put("userSet", p.userSet).put("userFixed", p.userFixed)) }
        remember(
            id,
            JSONObject()
                .put("versionCode", installed?.versionCode ?: 0)
                .put("versionName", installed?.versionName ?: "")
                .put("signers", JSONArray(installed?.signers.orEmpty().toList()))
                .put("installer", Device.installerOf(id) ?: "")
                .put("enabled", dump?.enabled ?: 0)
                .put("permissions", permissions)
                .put("apks", JSONArray(apks.map { it.name })),
        )
        Device.uninstallForUser(id)
        // By what the phone has after, not by what pm said: the copy goes only while the app is still here.
        if (!Device.installedForUser(id)) {
            Out.line("✔ Removed $id (its data is kept, and a copy of its APKs: android.strict.keep, a list, or strict off brings it back)")
            return true
        }
        forget(id)
        Out.line("✘ Could not remove $id")
        return false
    }

    /** The copy of the app's APKs, its base APK first; null when it is not all here. */
    private fun copy(id: String, entry: JSONObject): List<File>? =
        entry.optJSONArray("apks").strings().map { File(File(copies, id), it) }.takeIf { files -> files.isNotEmpty() && files.all { it.isFile } }

    /** Grants [id] the runtime permissions it had ([had]) that it has not now, with the user's flags. */
    private fun grant(id: String, had: JSONObject?) {
        if (had == null) return
        val dump = Device.dump(id) ?: return
        for (name in had.keys()) {
            val was = had.optJSONObject(name) ?: continue
            if (dump.runtime[name]?.granted != false) continue
            Device.setPermission(id, name, true, was.optBoolean("userSet"), was.optBoolean("userFixed"))
        }
    }

    private fun remember(id: String, entry: JSONObject) {
        journal.put(id, entry)
        save()
    }

    /** Drops what was kept of the app: its record and its copy. */
    private fun forget(id: String) {
        if (journal.remove(id) != null) save()
        deleteTree(File(copies, id))
    }

    private fun save() {
        journalFile.parentFile?.mkdirs()
        writeWhole(journalFile, journal.toString(1))
    }

    private fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).map { getString(it) }

    companion object {
        private const val NIXUP = "dev.nixup.manager"

        /** The root solutions' own apps (Magisk and its forks, KernelSU and its forks, APatch), which strict always leaves. */
        private val ROOT_APPS = setOf(
            "com.topjohnwu.magisk", "io.github.vvb2060.magisk", "io.github.huskydg.magisk",
            "me.weishu.kernelsu", "com.rifsxd.ksunext", "com.sukisu.ultra", "me.bmax.apatch",
        )
    }
}
