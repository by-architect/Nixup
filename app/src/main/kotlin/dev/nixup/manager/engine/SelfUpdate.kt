package dev.nixup.manager.engine

import org.json.JSONObject
import java.io.File
import java.util.zip.ZipFile

/**
 * Updates of Nixup itself. Its module's update.json (module.prop's updateJson, the latest GitHub release's, which the
 * Magisk app reads too) is checked with the apps' updates, every 6 hours, and a newer version is told in a
 * notification and shown in the Updates tab ([check]); it is installed by hand from there ([install]): its zip is
 * downloaded and checked first (Nixup's module, newer than the one in use, and its app signed with the key of the app
 * installed, so no one else's zip goes in), then installed with the root solution's own command (Magisk's, KernelSU's,
 * APatch's), and used from the next restart, as any module update is.
 */
class SelfUpdate(private val data: File) {
    private val file = File(data, "nixup-update.json")
    private val module = Notify.module ?: throw Stop("the module's folder is not known here (run from the module's scripts)")
    private val prop = File(module, "module.prop").readLines().associate { it.substringBefore('=') to it.substringAfter('=', "") }
    private val version = prop["version"].orEmpty()
    private val code = prop["versionCode"]?.toLongOrNull() ?: 0L
    /** Where update.json is: module.prop's updateJson (NIXUP_UPDATE_JSON instead, for a test). */
    private val updateJson = System.getenv("NIXUP_UPDATE_JSON")?.takeIf { it.isNotEmpty() } ?: prop["updateJson"].orEmpty()

    /** Looks for a newer Nixup, and keeps what it found for the Updates tab; a newer one is told once. */
    fun check(): Int {
        if (updateJson.isEmpty()) throw Stop("this module has no updateJson in its module.prop: it is updated by hand")
        val json = JSONObject(String(read(updateJson, 64 shl 10)))
        val latest = json.optLong("versionCode")
        val name = json.optString("version")
        val before = runCatching { JSONObject(file.readText()) }.getOrNull()
        val newer = latest > code
        val told = before?.optLong("told") ?: 0L
        file.writeText(
            JSONObject().put("checked", System.currentTimeMillis()).put("version", version).put("versionCode", code)
                .put("latest", json).put("newer", newer).put("told", if (newer && told != latest) latest else told).toString(),
        )
        if (!newer) {
            Out.line("Nixup $version is the newest")
            return 0
        }
        Out.line("Nixup $name is out (this one is $version): the Updates tab installs it, as the Magisk app does")
        if (told != latest) Notify.event("update", "info", "Nixup $name is out", "The Updates tab installs it (this phone has $version)", "update:nixup")
        return 0
    }

    /** Installs the newest Nixup, once it is checked; the phone uses it after its next restart. */
    fun install(): Int {
        check()
        val latest = JSONObject(file.readText())
        if (!latest.optBoolean("newer")) return 0
        val json = latest.getJSONObject("latest")
        val name = json.optString("version")
        val url = json.optString("zipUrl")
        if (url.isEmpty()) throw Stop("update.json names no zip (zipUrl)")
        val zip = File(data, "tmp/nixup-$name.zip")
        zip.parentFile?.mkdirs()
        Out.line("Downloading Nixup $name")
        if (url.startsWith("/")) File(url).copyTo(zip, overwrite = true) else Net.fetch(url, zip, -1, null, "update.json", Out.progress("  Nixup $name", -1))
        try {
            verify(zip, json.optLong("versionCode"))
            val (status, said) = installCommand(zip)
            said.lineSequence().filter { it.isNotBlank() }.forEach { Out.line("  $it") }
            if (status != 0) throw Stop("the root solution did not install it (exit $status)", 1)
        } finally {
            zip.delete()
        }
        // Not offered again: the module set up for the next restart is this one now.
        file.writeText(JSONObject(file.readText()).put("newer", false).put("installed", name).toString())
        Out.line("✔ Nixup $name is installed: it is used from the next restart of the phone")
        Notify.event("update", "ok", "Nixup $name is installed", "Restart the phone to use it", "update:nixup")
        return 0
    }

    /** Nixup's module, newer than this one, with its app signed by the key of the app installed. */
    private fun verify(zip: File, promised: Long) {
        ZipFile(zip).use { archive ->
            val inside = archive.getEntry("module.prop")?.let { entry -> archive.getInputStream(entry).bufferedReader().readLines() }
                ?.associate { it.substringBefore('=') to it.substringAfter('=', "") }
                ?: throw Stop("the download is not a Magisk module (it has no module.prop)")
            if (inside["id"] != prop["id"]) throw Stop("the download is another module (${inside["id"]}), not ${prop["id"]}")
            val newCode = inside["versionCode"]?.toLongOrNull() ?: 0L
            if (newCode <= code) throw Stop("the download is not newer than the Nixup in use (${inside["version"]})")
            if (promised > 0 && newCode != promised) throw Stop("the download is version ${inside["versionCode"]}, not the $promised update.json names")
            val app = File(data, "tmp/nixup-update-app.apk")
            try {
                val entry = archive.getEntry("app.apk") ?: throw Stop("the download has no app.apk")
                archive.getInputStream(entry).use { input -> app.outputStream().use { input.copyTo(it) } }
                val theirs = Device.archive(app) ?: throw Stop("the download's app cannot be read here, so it cannot be checked: install it from the Magisk app")
                val ours = Device.installed(APP)?.signers.orEmpty()
                if (theirs.id != APP) throw Stop("the download's app is ${theirs.id}, not $APP")
                if (ours.isEmpty()) throw Stop("the app installed cannot be read here, so the download cannot be checked against it: install it from the Magisk app")
                if (theirs.signers.intersect(ours).isEmpty()) {
                    throw Stop("the download's app is signed with another key than the app installed (a build of your own?): install it from the Magisk app if you trust it")
                }
            } finally {
                app.delete()
            }
        }
        Out.line("✔ Checked: Nixup's module, newer, its app signed with the key of the app installed")
    }

    /** The root solution's command that installs a module zip: Magisk's, KernelSU's or APatch's. */
    private fun installCommand(zip: File): Pair<Int, String> = when {
        File("/data/adb/ksud").canExecute() -> Device.run("/data/adb/ksud", "module", "install", zip.path)
        File("/data/adb/apd").canExecute() -> Device.run("/data/adb/apd", "module", "install", zip.path)
        else -> Device.run("magisk", "--install-module", zip.path)
    }

    /** A file at [where]: an https address (or, for a test, a path on the phone). */
    private fun read(where: String, limit: Int): ByteArray =
        if (where.startsWith("/")) File(where).readBytes() else Net.get(where, limit)

    companion object {
        private const val APP = "dev.nixup.manager"
    }
}
