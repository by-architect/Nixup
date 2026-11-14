package dev.nixup.manager.engine

import android.system.Os
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.lang.reflect.InvocationTargetException

/**
 * What the earlier services.davx5 changed in the DAVx⁵ app, given back: its setting "preselect collections", which
 * those builds set (original/davx5.json keeps what it was). Nixup syncs calendars and contacts itself now, without
 * DAVx⁵ (services.caldav, see Caldav; services.davx5 is its old name, nix/davx5.nix), so the first build after that
 * gives it back, as does the module's removal. The accounts those builds added stay in DAVx⁵, as the user's.
 */
class Davx5(private val data: File) {
    private val journalFile = File(data, "original/davx5.json")

    private var giveBack = false

    /** Whether the plan changes anything. */
    val changes: Boolean get() = giveBack

    fun plan() {
        giveBack = journalFile.isFile && Device.versionCode(APP) != null
        if (giveBack) Out.line("DAVx⁵: its \"preselect collections\" back as before (Nixup syncs without DAVx⁵ now: services.caldav)")
    }

    /** Applies the plan; returns how many things did not take. */
    fun apply(): Int = if (giveBack) restore() else 0

    /** Gives "preselect collections" back as it was before Nixup set it. */
    fun restore(): Int {
        if (!journalFile.isFile) return 0
        if (Device.versionCode(APP) == null) {
            journalFile.delete()
            return 0
        }
        val original = runCatching { JSONObject(journalFile.readText()) }.getOrNull()
            ?.let { if (it.isNull("preselect")) null else it.optInt("preselect") }
        val stopped = stop()
        val done = writePreference(original)
        if (stopped) unstop()
        if (!done) {
            Out.line("✘ DAVx⁵: \"preselect collections\" could not go back as before")
            return 1
        }
        journalFile.delete()
        Out.line("✔ DAVx⁵: \"preselect collections\" back as before")
        return 0
    }

    /** Whether DAVx⁵ has a process running. */
    private fun running() = Device.run("pidof", APP).let { (status, said) -> status == 0 && said.isNotBlank() }

    /**
     * Ends DAVx⁵, so that its preferences can be written (it keeps them in memory, and would write them back): ended in
     * the background if it can be, else force-stopped. True when it was force-stopped (see [unstop]).
     */
    private fun stop(): Boolean {
        if (!running()) return false
        Device.killInBackground(APP)
        if (!running()) return false
        Device.run("am", "force-stop", APP)
        return true
    }

    /**
     * Lets DAVx⁵ off its stopped state, which a force-stop leaves it in: Android runs none of its jobs and syncs until
     * it starts again (Android's package manager, as root).
     */
    private fun unstop() {
        runCatching {
            val packages = Class.forName("android.app.ActivityThread").getMethod("getPackageManager").invoke(null)!!
            packages.javaClass.getMethod("setPackageStoppedState", String::class.java, Boolean::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                .invoke(packages, APP, false, 0)
        }.onFailure { Out.line("  (DAVx⁵ could not be let off its stopped state: ${(it as? InvocationTargetException)?.targetException?.message ?: it.message})") }
    }

    // --- DAVx⁵'s setting "preselect collections" (its shared preferences).

    private val prefsFile = File(APP_DATA, "shared_prefs/${APP}_preferences.xml")

    /** The preferences' text as DAVx⁵ reads it (a backup Android left from a write that broke off takes the file's place). */
    private fun prefsText(): String? = listOf(File(prefsFile.path + ".bak"), prefsFile).firstOrNull { it.isFile }?.readText()

    /** Writes "preselect collections" ([value]; null: not set) into DAVx⁵'s preferences, which DAVx⁵ must not be running to keep. */
    private fun writePreference(value: Int?): Boolean = try {
        val uid = Device.uid(APP) ?: throw IllegalStateException("DAVx⁵ is not installed")
        val label = Device.run("stat", "-c", "%C", APP_DATA.path).second.trim()
        val text = prefsText() ?: "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n</map>\n"
        val entry = value?.let { "<int name=\"preselect_collections\" value=\"$it\" />" }
        val updated = when {
            PRESELECT_ENTRY.containsMatchIn(text) ->
                if (entry != null) PRESELECT_ENTRY.replace(text, entry) else PRESELECT_LINE.replace(text, "")
            entry == null -> text
            text.contains("<map />") -> text.replace("<map />", "<map>\n    $entry\n</map>")
            else -> text.replace("</map>", "    $entry\n</map>")
        }
        val dir = prefsFile.parentFile!!
        if (!dir.isDirectory) dir.mkdirs()
        val partial = File(dir, ".${prefsFile.name}.nixup")
        partial.writeText(updated)
        // DAVx⁵'s own, as if it had written it: its uid and group, rw-rw----, its folder's label.
        Os.chown(partial.path, uid, uid)
        Os.chmod(partial.path, "660".toInt(8))
        if (label.startsWith("u:")) Device.run("chcon", label, partial.path)
        if (!partial.renameTo(prefsFile)) throw IOException("could not put it in $prefsFile")
        File(prefsFile.path + ".bak").delete()
        true
    } catch (e: Exception) {
        Out.line("  (DAVx⁵'s preferences: ${e.javaClass.simpleName}: ${e.message})")
        false
    }

    companion object {
        const val APP = "at.bitfire.davdroid"
        private val APP_DATA = File("/data/data/$APP")
        private val PRESELECT_ENTRY = Regex("""<int\s+name="preselect_collections"\s+value="(-?\d+)"\s*/>""")
        private val PRESELECT_LINE = Regex("""\n?[ \t]*<int\s+name="preselect_collections"\s+value="-?\d+"\s*/>""")
    }
}
