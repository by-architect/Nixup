package dev.nixup.manager.engine

import android.system.Os
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * An app of the config installed with another signing key than its source's builds (installed by hand from another
 * store, F-Droid's own build where the developer's is listed, ...), reinstalled from its source with its data kept.
 * Android lets no build with another key update an app, and takes none over the data an uninstall kept
 * (`pm uninstall -k` keeps the old key with it), so its data goes aside first: its folders (but their caches) and its
 * APKs into reinstall/<app>, with what Android keeps of it besides (its runtime permissions, accounts, default-app roles,
 * keyboard, battery setting and enabled state). Then it is uninstalled, the source's build installed, and its data put
 * back as the new install's own (see AppData), as a restore from backups puts it back. When the new build does not
 * install, the app comes back from its own APKs as it was. It stays the user's app (not the module's to remove).
 *
 * What an app keeps in Android's keystore (the keys its encrypted settings may be locked with) goes with the uninstall,
 * so such an app may ask to sign in again. The plan checks first that it can be done ([check]): the app is the user's
 * (not one that came with the phone), no other user (an app clone, a work profile) has it, the source's build is not
 * older (its data could be newer than that version reads), and there is room for its data aside ([room]).
 */
class Reinstall(data: File) {
    private val aside = File(data, "reinstall")

    /** The room the largest of the planned apps takes aside (its data and APKs: one app at a time). */
    var room: Long = 0
        private set

    /**
     * Whether [id] (as it is: [installed]) can be reinstalled from [source] at version [code] (null: known once its APK
     * is here, and checked then); adds why not to [problems].
     */
    fun check(id: String, installed: Device.Installed, code: Long?, source: String, problems: MutableList<String>): Boolean {
        val why = "$id is installed with another signing key than the builds of $source"
        val others = Device.otherUsers(id)
        val refused = when {
            Device.preinstalled(id)?.system == true -> "$why, and came with the phone, so Android cannot replace it: take it out of the config (or debloat it)"
            others.isNotEmpty() ->
                "$why, and user ${others.joinToString(", ")} (an app clone, a work profile) has it too: Nixup reinstalls an app for the main user alone, so remove it there first"
            code != null && code < installed.versionCode ->
                "$why, and its version (${installed.versionName}, code ${installed.versionCode}) is newer than theirs (code $code): its data could be newer than that version reads, so it is not reinstalled until $source has one as new"
            !File(AppData.path("data", id)).isDirectory ->
                "$why, and its data folder is out of sight here (an app's view of the phone, which hides the other apps' data), so it cannot be kept"
            else -> null
        }
        if (refused != null) {
            problems += refused
            return false
        }
        room = maxOf(room, size(id))
        return true
    }

    /**
     * Before [id]'s new build is installed: its data and APKs aside, then the app uninstalled. Null when done; else what
     * went wrong, and the app is as it was.
     */
    fun prepare(id: String): String? {
        val folder = File(aside, id)
        deleteTree(folder)
        // (Its accounts, with their passwords: as Android's own databases, root's alone to read.)
        val accounts = Accounts.read(id) ?: return "its accounts cannot be read here, and Android removes them with the app: it is left as it is"
        val apks = Device.run("pm", "path", "--user", "0", id).second.lineSequence().map { it.trim() }
            .filter { it.startsWith("package:") && it.endsWith(".apk") }.map { File(it.removePrefix("package:")) }.toList()
        if (apks.isEmpty()) return "its APKs are not found, so it could not come back if the new build did not install: it is left as it is"
        // A copy whole, of an app that is not running.
        Device.run("am", "force-stop", "--user", "0", id)
        try {
            File(folder, APKS).mkdirs()
            for (apk in apks) {
                val to = apk.copyTo(File(File(folder, APKS), apk.name))
                if (to.length() != apk.length()) throw IOException("${apk.name} was not copied whole")
            }
            for (part in AppData.FOLDERS) {
                val path = File(AppData.path(part, id))
                if (!path.isDirectory) continue
                val to = File(folder, part).apply { mkdirs() }
                for (entry in path.listFiles().orEmpty()) {
                    if (skipped(part, entry)) continue
                    if (Device.run("cp", "-a", entry.path, File(to, entry.name).path).first != 0) throw IOException("${entry.path} could not be copied (is the phone's storage full?)")
                }
            }
            val dump = Device.dump(id)
            val keyboard = Device.secureSetting("default_input_method").takeIf { it.substringBefore('/') == id }
            val info = JSONObject()
                .put("installer", Device.installerOf(id) ?: "")
                .put("permissions", AppData.permissions(id))
                .put("enabled", dump?.enabled ?: 0)
                .put("battery", id in Device.batteryExempt().first)
                .put("roles", JSONArray(ROLES.filter { role -> runCatching { id in Device.roleHolders(role) }.getOrDefault(false) }))
                .put("keyboard", keyboard ?: "")
            File(folder, INFO).writeText(info.toString())
            if (accounts.length() > 0) File(folder, ACCOUNTS).apply { writeText(accounts.toString()); Os.chmod(path, "600".toInt(8)) }
        } catch (e: Exception) {
            deleteTree(folder)
            return "its data could not be kept aside (${e.message}): it is left as it is"
        }
        Device.run("pm", "uninstall", "--user", "0", id)
        if (Device.installedForUser(id)) {
            deleteTree(folder)
            return "it could not be uninstalled: it is left as it is"
        }
        // Gone whole, its key with it: else no build with another key installs.
        if (Device.known(id)) {
            val back = undo(id)
            return "Android still keeps it (and its key) after uninstalling it" + if (back) ", so it is back as it was" else ""
        }
        return null
    }

    /**
     * After [id]'s new build is installed: its data put back as the new install's own, and what Android kept of it
     * besides. Null when done; else what went wrong (its data is still aside, in reinstall/<app>).
     */
    fun finish(id: String): String? {
        val said = putBack(id) ?: return null.also { deleteTree(File(aside, id)) }
        return "$said: its data is kept in ${File(aside, id)}"
    }

    /** When the new build did not install: the app back from its own APKs, with its data. Whether it is. */
    fun undo(id: String): Boolean {
        val folder = File(aside, id)
        val apks = File(folder, APKS).listFiles().orEmpty().filter { it.name.endsWith(".apk") }.sortedBy { it.name != "base.apk" }
        val installer = info(id).optString("installer").takeIf { it.isNotEmpty() && Device.installedForUser(it) }
        val said = if (apks.isEmpty()) "its APKs were not kept" else Device.installAll(apks, installer)
        if (said != null || !Device.installedForUser(id)) {
            Out.line("✘ $id could not come back as it was (${said ?: "it is not installed after"}): its data and APKs are kept in $folder")
            return false
        }
        val restored = putBack(id)
        if (restored != null) {
            Out.line("✘ $id is back, but $restored: its data is kept in $folder")
            return false
        }
        deleteTree(folder)
        Out.line("  $id is back as it was, with its data")
        return true
    }

    /** Puts [id]'s data back, as the app installed now has its folders, and what Android kept of it. Null when done, else what went wrong. */
    private fun putBack(id: String): String? {
        val folder = File(aside, id)
        val uid = Device.uid(id) ?: return "it is not installed"
        if (!AppData.settled(id)) return "Android has not made its folders"
        Device.run("am", "force-stop", "--user", "0", id)
        for (part in AppData.FOLDERS) {
            val from = File(folder, part)
            if (!from.isDirectory) continue
            val path = AppData.path(part, id)
            val made = AppData.madeAs(part, path)
            if (!File(path).isDirectory && !File(path).mkdirs()) return "its folder $path cannot be made"
            for (entry in from.listFiles().orEmpty()) {
                val target = File(path, entry.name)
                deleteTree(target)
                if (!AppData.move(entry, target)) return "${entry.name} could not be put into $path (is the phone's storage full?)"
            }
            AppData.handOver(path, uid, made)
            // A folder of the shared storage as Android makes it (owner, group, modes, storage quota), where its storage is
            // FUSE; sdcardfs gives the app's files their owners by itself.
            if (!made.own) Device.fixupAppDir(AppData.shown(path))
        }
        val info = info(id)
        info.optJSONObject("permissions")?.let { AppData.grant(id, it) }
        val enabled = info.optInt("enabled", 0)
        if (enabled != 0) Device.setEnabledState(id, enabled)
        if (info.optBoolean("battery")) Device.setBatteryExempt(id, true)
        info.optJSONArray("roles")?.let { roles -> (0 until roles.length()).forEach { Device.setRoleHolder(roles.getString(it), id) } }
        info.optString("keyboard").takeIf { it.isNotEmpty() }?.let { Device.setKeyboard(it) }
        val accounts = File(folder, ACCOUNTS).takeIf { it.isFile }?.let { runCatching { JSONArray(it.readText()) }.getOrNull() }
        val lost = accounts?.let { Accounts.restore(id, uid, it).lost } ?: 0
        // Stopped for the move (and an install leaves it stopped too): its syncs and jobs start it again, as before.
        Device.letOffStopped(id)
        return if (lost > 0) "${if (lost == 1) "1 of its accounts" else "$lost of its accounts"} did not come back (see above)" else null
    }

    private fun info(id: String): JSONObject = runCatching { JSONObject(File(File(aside, id), INFO).readText()) }.getOrDefault(JSONObject())

    /** What of an app's folder is not kept: the caches Android makes, and the link an older Android puts to its native libraries. */
    private fun skipped(part: String, entry: File) = when (part) {
        "data", "deviceData" -> entry.name in AppData.CACHES || (part == "data" && entry.name == "lib" && AppData.libLink(entry.parentFile!!))
        "storage" -> entry.name == "cache"
        else -> false
    }

    /** About how much of [id] goes aside: its folders and its APKs. */
    private fun size(id: String): Long {
        val paths = AppData.FOLDERS.map { AppData.path(it, id) }.filter { File(it).isDirectory } +
            Device.run("pm", "path", "--user", "0", id).second.lineSequence().map { it.trim().removePrefix("package:") }.filter { it.endsWith(".apk") }
        return paths.sumOf { path -> Device.run("du", "-s", "-k", path).second.trim().substringBefore('\t').substringBefore(' ').toLongOrNull()?.times(1024) ?: 0L }
    }

    companion object {
        private const val APKS = "apk"
        private const val INFO = "info.json"
        private const val ACCOUNTS = "accounts.json"

        /** The default-app roles an app may hold (android.apps.<app>.defaultFor's), which Android takes from it as it goes. */
        private val ROLES = listOf(
            "android.app.role.HOME", "android.app.role.BROWSER", "android.app.role.DIALER", "android.app.role.SMS",
            "android.app.role.ASSISTANT", "android.app.role.CALL_SCREENING",
        )
    }
}
