package dev.nixup.manager.engine

import android.system.Os
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * adb as the config says (android.adb, see nix/adb.nix): the TCP port adbd listens on (service.adb.tcp.port, as
 * `adb tcpip` sets it: it lasts until a restart, so [boot] sets it again), and the computers it lets in without asking,
 * whose keys go into Android's own list of them (/data/misc/adb/adb_keys, which adbd reads at each connection). What
 * was there before Nixup changed it is kept in original/adb.json: the port, and the keys Nixup added (a key the phone
 * trusted already stays when it leaves the config). Taken out of the config, or when the module goes ([restore]), it
 * comes back. Like the settings, it is planned and checked first ([plan]), and changed after ([apply]); adbd starts
 * again for a new port once the build is done ([restartLater]), as that cuts a build that came over adb.
 */
class Adb(data: File) {
    private val journalFile = File(data, "original/adb.json")
    private val journal: JSONObject = if (journalFile.isFile) JSONObject(journalFile.readText()) else JSONObject()

    /** The port to set ("" or "-1": none), when it changes; and whether that gives the one from before back. */
    private var port: String? = null
    private var givingBack = false
    private var adding: List<String> = emptyList()
    private var removing: List<String> = emptyList()

    /** Whether the plan changes anything. */
    val changes get() = port != null || adding.isNotEmpty() || removing.isNotEmpty()

    /** Whether adbd must start again to listen as planned: [restartLater], once the build is done. */
    var restart = false
        private set

    fun plan(adb: JSONObject?, problems: MutableList<String>) {
        val warnings = ArrayList<String>()
        val wantedPort = adb?.takeIf { !it.isNull("port") }?.optInt("port")
        val wanted = keys(adb, warnings)
        val trusted = trusted().map(::body).toSet()
        adding = wanted.filter { body(it) !in trusted }
        // Only what Nixup added goes when it leaves the config.
        val wantedBodies = wanted.map(::body).toSet()
        removing = added().filter { it !in wantedBodies && it in trusted }
        val now = off(Device.property(PORT))
        givingBack = wantedPort == null && journal.has("port")
        port = when {
            wantedPort != null -> wantedPort.toString().takeIf { now != it || (adbdRunning() && !listening(wantedPort)) }
            givingBack -> off(journal.getString("port")).takeIf { now != it } ?: run {
                // Already as it was (a restart cleared it): nothing to give back.
                forgetPort()
                null
            }
            else -> null
        }
        val lines = ArrayList<String>()
        when {
            port != null && !givingBack -> lines += "over the network on port $wantedPort: to set (adb starts again after the build)"
            port != null -> lines += "over the network: back as before (${port!!.ifEmpty { "off" }}; adb starts again after the build)"
            wantedPort != null -> lines += "over the network on port $wantedPort"
        }
        if (adding.isNotEmpty()) lines += "to trust: ${adding.joinToString(", ") { name(it) }}"
        if (removing.isNotEmpty()) lines += "to forget (they left the config): ${removing.size} computer(s)"
        if (wanted.isNotEmpty() && adding.isEmpty()) lines += "trusts the ${wanted.size} computer(s) of the config"
        if (lines.isNotEmpty()) {
            Out.line("adb:")
            lines.forEach { Out.line("  $it") }
        }
        warnings.forEach { Out.line("⚠ $it") }
    }

    /** Carries the plan out; the number of changes that did not take. */
    fun apply(): Int {
        var failed = 0
        if (adding.isNotEmpty() || removing.isNotEmpty()) {
            val removed = removing.toSet()
            if (writeTrusted(trusted().filter { body(it) !in removed } + adding)) {
                adding.forEach { Out.line("✔ adb: trusts ${name(it)}") }
                if (removing.isNotEmpty()) Out.line("✔ adb: forgot the ${removing.size} computer(s) it trusted for the config")
                journal.put("keys", JSONArray((added() + adding.map(::body)).filter { it !in removed }.distinct()))
                save()
            } else {
                Out.line("✘ adb: could not write its trusted computers ($KEYS)")
                failed++
            }
        }
        val value = port ?: return failed
        if (!givingBack && !journal.has("port")) {
            journal.put("port", Device.property(PORT))
            save()
        }
        if (Device.run("setprop", PORT, value.ifEmpty { "-1" }).first != 0) {
            Out.line("✘ adb: could not set $PORT to $value")
            return failed + 1
        }
        if (givingBack) forgetPort()
        restart = adbdRunning()
        Out.line("✔ adb: ${if (value.isEmpty() || value == "-1") "not over the network" else "over the network on port $value"}")
        return failed
    }

    /**
     * Starts adbd again a few seconds from now, apart from this process (whose adb connection, when the build came over
     * adb, goes with it), so the build ends, and says so, first.
     */
    fun restartLater() {
        if (!restart) return
        Out.line("adb starts again in $DELAY seconds, to listen as the config says: a computer connected over adb connects again then")
        ProcessBuilder("/system/bin/sh", "-c", "setsid sh -c 'sleep $DELAY; setprop ctl.restart adbd' < /dev/null > /dev/null 2>&1 &")
            .start().waitFor()
    }

    /** At boot: the profile's port and trusted computers again (a restart leaves adb on no port), adbd started again for the port. */
    fun boot(adb: JSONObject?) {
        adb ?: return
        val warnings = ArrayList<String>()
        val wantedPort = adb.takeIf { !it.isNull("port") }?.optInt("port")
        val trusted = trusted().map(::body).toSet()
        adding = keys(adb, warnings).filter { body(it) !in trusted }
        removing = emptyList()
        givingBack = false
        port = wantedPort?.toString()?.takeIf { it != off(Device.property(PORT)) || !listening(wantedPort) }
        warnings.forEach { Out.line("⚠ $it") }
        if (apply() == 0 && restart) Device.run("setprop", "ctl.restart", "adbd")
    }

    /** The module is being removed: the computers it trusted for the config forgotten, and the port as before. */
    fun restore(): Int {
        var failed = 0
        val mine = added().toSet()
        if (mine.isNotEmpty()) {
            if (writeTrusted(trusted().filter { body(it) !in mine })) Out.line("✔ adb: forgot the ${mine.size} computer(s) it trusted for the config")
            else failed++
        }
        if (journal.has("port")) {
            // From adb's next start: the restart that ends the removal.
            Device.run("setprop", PORT, journal.getString("port").ifEmpty { "-1" })
            Out.line("✔ adb: its port as before")
        }
        journalFile.delete()
        return failed
    }

    private fun forgetPort() {
        if (journal.remove("port") != null) save()
    }

    /** The profile's keys, and the ones in its files on the phone (one that is not there is told, and left out). */
    private fun keys(adb: JSONObject?, warnings: MutableList<String>): List<String> {
        adb ?: return emptyList()
        val keys = adb.strings("keys").toMutableList()
        for (path in adb.strings("keyFiles")) {
            val file = File(path)
            if (!file.isFile) {
                warnings += "android.adb.trustedKeyFiles: there is no $path on the phone, so its keys are left out"
                continue
            }
            keys += file.readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        }
        return keys.distinctBy(::body)
    }

    /** The keys adb trusts now, a line each. */
    private fun trusted(): List<String> =
        runCatching { File(KEYS).readLines() }.getOrDefault(emptyList()).map { it.trim() }.filter { it.isNotEmpty() }

    /** Writes the list over the one there (which keeps its owner, mode and SELinux label), or makes one as Android does. */
    private fun writeTrusted(keys: List<String>): Boolean = try {
        val file = File(KEYS)
        val existed = file.isFile
        file.writeText(keys.joinToString("") { "$it\n" })
        if (!existed) {
            Os.chown(file.path, 1000, 2000)
            Os.chmod(file.path, "640".toInt(8))
            Device.run("restorecon", file.path)
        }
        true
    } catch (e: Exception) {
        false
    }

    private fun added(): List<String> =
        journal.optJSONArray("keys")?.let { keys -> (0 until keys.length()).map { keys.getString(it) } } ?: emptyList()

    private fun save() {
        journalFile.parentFile?.mkdirs()
        val fresh = File(journalFile.path + ".new")
        fresh.writeText(journal.toString(1))
        fresh.renameTo(journalFile)
    }

    private fun adbdRunning() = Device.property("init.svc.adbd") == "running"

    /** Whether a socket listens on TCP [port] (adbd's, over IPv4 or IPv6). */
    private fun listening(port: Int): Boolean {
        val hex = "%04X".format(port)
        return listOf("/proc/net/tcp6", "/proc/net/tcp").any { table ->
            runCatching { File(table).readLines() }.getOrDefault(emptyList()).drop(1).any { line ->
                val fields = line.trim().split(Regex("\\s+"))
                fields.size > 3 && fields[1].substringAfterLast(':') == hex && fields[3] == "0A"
            }
        }
    }

    companion object {
        private const val KEYS = "/data/misc/adb/adb_keys"
        private const val PORT = "service.adb.tcp.port"
        private const val DELAY = 3

        /** A port as adbd takes it: none ("", 0, -1, not a number) as "". */
        private fun off(port: String) = port.trim().takeIf { (it.toIntOrNull() ?: 0) > 0 } ?: ""

        /** A key's own part (its base64), without the computer's name after it. */
        private fun body(key: String) = key.trim().substringBefore(' ')

        /** The computer's name a key carries ("neo@laptop"), or the start of the key. */
        private fun name(key: String) = key.trim().substringAfter(' ', "").ifEmpty { body(key).take(12) + "…" }
    }
}
