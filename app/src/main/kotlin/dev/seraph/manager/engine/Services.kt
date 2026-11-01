package dev.seraph.manager.engine

import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/**
 * The services of the profile (android.services): programs root keeps running, as NixOS's services. For now
 * Tailscale (see [Tailscale]). Like the settings, they are planned and checked first, and changed after; at boot they
 * start again ([start]), and when the module goes they stop and take what they kept with them ([restoreAll]).
 */
class Services(data: File) {
    private val tailscale = Tailscale(File(data, "services/tailscale"), data)
    private var action: (() -> Int)? = null

    /** Whether the plan changes anything. */
    val changes get() = action != null

    fun plan(services: JSONObject?, problems: MutableList<String>) {
        action = tailscale.plan(services?.optJSONObject("tailscale"), problems)
    }

    /** Carries the plan out; the number of services that did not come out as the config says. */
    fun apply(): Int = action?.invoke() ?: 0

    /** At boot: starts the services the last build left running. */
    fun start(): Int = tailscale.start()

    /** The module is being removed: every service stops, and what it kept goes. */
    fun restoreAll(): Int = tailscale.remove()
}

/**
 * Tailscale, run by root: Tailscale's own static Linux build (android.services.tailscale.package), downloaded and
 * checked against its SHA-256, kept in [dir] with tailscaled's state. tailscaled runs in userspace mode (it takes no
 * VPN slot and changes no routes), in a mount namespace with Seraph's /etc (the network's DNS servers and Android's
 * trusted certificates, see [Etc]), under a shell loop that starts it again when it stops. `tailscale up --reset`
 * gives it the config's settings, so what the config leaves out goes back to Tailscale's default.
 */
class Tailscale(private val dir: File, private val data: File) {
    private val spec = File(dir, "spec.json")
    private val socket = File(dir, "tailscaled.sock")
    private val state = File(dir, "state")
    private val log = File(dir, "tailscaled.log")
    private val supervisor = File(dir, "supervisor.pid")

    fun plan(wanted: JSONObject?, problems: MutableList<String>): (() -> Int)? {
        val had = readSpec()
        if (wanted == null) {
            if (had == null && !dir.exists()) return null
            Out.line("Tailscale: to log out and stop (it left the config)")
            return { remove() }
        }
        val pkg = wanted.getJSONObject("package")
        val version = pkg.getString("version")
        val tarballs = pkg.getJSONObject("tarballs")
        val arch = Device.abis.mapNotNull { ARCHES[it] }.firstOrNull { tarballs.has(it) }
        if (arch == null) {
            problems += "android.services.tailscale: its package has no build for this phone's processor (${Device.abis.joinToString(", ")})"
            return null
        }
        wanted.string("authKeyFile")?.let { key ->
            if (!File(key).isFile) problems += "android.services.tailscale.authKeyFile: there is no $key on the phone"
        }
        val running = running()
        if (had != null && had.toString() == wanted.toString() && running) {
            report(binaries(version), done = false)
            return null
        }
        val what = when {
            had == null -> "to start"
            had.toString() == wanted.toString() -> "to start again (it was not running)"
            else -> "to start again with the config's changes"
        }
        val download = if (installed(version)) "" else ", downloading it"
        Out.line("Tailscale $version: $what$download")
        return { change(wanted, arch) }
    }

    private fun change(wanted: JSONObject, arch: String): Int = try {
        val bin = fetch(wanted.getJSONObject("package"), arch)
        val had = readSpec()
        if (had == null || daemonPart(had) != daemonPart(wanted) || !running()) {
            stopDaemon()
            startDaemon(bin, wanted)
        }
        spec.writeText(wanted.toString())
        up(bin, wanted)
        dropOtherVersions(bin.name)
        report(bin, done = true)
    } catch (e: Exception) {
        Out.line("✘ Tailscale: ${e.message}")
        1
    }

    /** At boot: tailscaled again, as the last build left it (it remembers its login and settings). */
    fun start(): Int {
        val had = readSpec() ?: return 0
        if (running()) return 0
        val bin = binaries(had.getJSONObject("package").getString("version"))
        if (!installed(bin.name)) {
            Out.line("✘ Tailscale: its files are gone; build again")
            return 1
        }
        return try {
            startDaemon(bin, had)
            Out.line("✔ Tailscale started")
            0
        } catch (e: Exception) {
            Out.line("✘ Tailscale: ${e.message}")
            1
        }
    }

    /**
     * Logs out (so the phone leaves the tailnet; tailscaled is started for it when it is not running), stops
     * tailscaled and removes everything it kept.
     */
    fun remove(): Int {
        if (!dir.exists()) return 0
        var loggedOut = false
        readSpec()?.let { had ->
            val bin = binaries(had.getJSONObject("package").getString("version"))
            if (installed(bin.name)) {
                runCatching {
                    if (!running()) startDaemon(bin, had)
                    loggedOut = cli(bin, listOf("logout"), 30).first == 0
                }
            }
        }
        stopDaemon()
        dir.deleteRecursively()
        dir.parentFile?.delete()
        Out.line("✔ Tailscale: ${if (loggedOut) "logged out, " else ""}stopped, and its files removed")
        return 0
    }

    // --- tailscaled.

    /** The settings that need tailscaled started again; the others only need `tailscale up`. */
    private fun daemonPart(spec: JSONObject) =
        listOf(spec.getJSONObject("package").getString("version"), spec.opt("port"), spec.optJSONArray("daemonFlags")?.toString()).toString()

    private fun startDaemon(bin: File, spec: JSONObject) {
        val busybox = BUSYBOXES.firstOrNull { File(it).canExecute() }
            ?: throw Stop("there is no busybox (Magisk's or KernelSU's) to give tailscaled its own /etc", 1)
        val etc = Etc.write(data)
        state.mkdirs()
        socket.delete()
        val daemon = listOf(
            File(bin, "tailscaled").path, "--tun=userspace-networking", "--statedir=${state.path}", "--socket=${socket.path}",
            "--port=${spec.optInt("port", 41641)}",
        ) + spec.strings("daemonFlags")
        val devNull = File("/dev/null")
        ProcessBuilder(listOf(busybox, "setsid", "sh", "-c", SUPERVISOR, MARK, busybox, etc.path, log.path, supervisor.path) + daemon)
            .directory(dir)
            .redirectInput(devNull).redirectOutput(devNull).redirectError(devNull)
            .start()
        // Up once the CLI gets an answer from it.
        repeat(75) {
            Thread.sleep(200)
            if (socket.exists() && cli(bin, listOf("status", "--json"), 5).second.contains("\"BackendState\"")) return
        }
        throw Stop("tailscaled did not start (its log: ${log.path})", 1)
    }

    private fun stopDaemon() {
        supervisorPid()?.let { Device.run("kill", "-TERM", "$it") }
        var tries = 0
        while ((daemonPids().isNotEmpty() || supervisorPid() != null) && tries++ < 50) Thread.sleep(200)
        (daemonPids() + listOfNotNull(supervisorPid())).forEach { Device.run("kill", "-KILL", "$it") }
        supervisor.delete()
        socket.delete()
    }

    private fun running() = supervisorPid() != null && daemonPids().isNotEmpty()

    /** The loop that keeps tailscaled running, while it does. */
    private fun supervisorPid(): Int? {
        val pid = runCatching { supervisor.readText().trim().toInt() }.getOrNull() ?: return null
        return pid.takeIf { cmdline(it).contains(MARK) }
    }

    /** The tailscaled processes of this folder (one, unless something went wrong). */
    private fun daemonPids(): List<Int> = File("/proc").listFiles().orEmpty().mapNotNull { it.name.toIntOrNull() }
        .filter { pid -> cmdline(pid).let { it.startsWith(dir.path + "/") && it.contains("/tailscaled") } }

    private fun cmdline(pid: Int) = runCatching { File("/proc/$pid/cmdline").readText().replace('\u0000', ' ') }.getOrDefault("")

    // --- The tailscale command.

    private fun up(bin: File, spec: JSONObject) {
        val host = spec.string("hostname") ?: hostname(Device.settings("global")["device_name"] ?: "android")
        val args = listOf("up", "--reset", "--timeout=20s", "--hostname=$host") +
            listOfNotNull(spec.string("authKeyFile")?.let { "--auth-key=file:$it" }) + spec.strings("upFlags")
        val (status, said) = cli(bin, args, 45)
        // Without an auth key it waits for the login, and gives up after the timeout: the link is in status.
        if (status != 0 && !said.contains("login.tailscale.com") && !said.contains("timeout waiting")) {
            throw Stop("tailscale up: ${said.trim().lines().lastOrNull { it.isNotBlank() } ?: "exit $status"}", 1)
        }
    }

    /** What tailscaled says it is doing; 0 when it is running or waiting for its login. */
    private fun report(bin: File, done: Boolean): Int {
        val status = cli(bin, listOf("status", "--json"), 10).second
        val json = runCatching { JSONObject(status.substring(status.indexOf('{'))) }.getOrNull()
        val self = json?.optJSONObject("Self")
        val name = self?.optString("DNSName")?.removeSuffix(".")?.ifEmpty { null } ?: self?.optString("HostName")
        val ip = self?.optJSONArray("TailscaleIPs")?.optString(0)
        val mark = if (done) "✔ " else ""
        return when (val backend = json?.optString("BackendState")) {
            "Running" -> 0.also { Out.line("${mark}Tailscale: running as $name ($ip)") }
            "NeedsLogin" -> 0.also {
                val url = json.optString("AuthURL").ifEmpty { null }
                Out.line("! Tailscale: waiting for its login: " + (url?.let { "open $it (on any device) and log in" } ?: "build again for the link"))
            }
            "Starting" -> 0.also { Out.line("${mark}Tailscale: connecting") }
            else -> 1.also { Out.line("✘ Tailscale: ${backend ?: "no answer from tailscaled"} (its log: ${log.path})") }
        }
    }

    private fun cli(bin: File, args: List<String>, seconds: Long): Pair<Int, String> {
        val process = ProcessBuilder(listOf(File(bin, "tailscale").path, "--socket=${socket.path}") + args)
            .redirectErrorStream(true).redirectInput(File("/dev/null")).start()
        val said = StringBuilder()
        val reader = Thread { runCatching { process.inputStream.bufferedReader().forEachLine { said.appendLine(it) } } }.apply { start() }
        if (!process.waitFor(seconds, TimeUnit.SECONDS)) process.destroyForcibly()
        reader.join(2000)
        return (if (process.isAlive) -1 else process.exitValue()) to said.toString()
    }

    // --- The package.

    private fun binaries(version: String) = File(dir, version)

    private fun installed(version: String) = binaries(version).let { File(it, "tailscale").canExecute() && File(it, "tailscaled").canExecute() }

    /** Tailscale's build for this phone, downloaded and checked, unpacked: tailscale and tailscaled. */
    private fun fetch(pkg: JSONObject, arch: String): File {
        val version = pkg.getString("version")
        val bin = binaries(version)
        if (installed(version)) return bin
        val tarball = pkg.getJSONObject("tarballs").getJSONObject(arch)
        val tgz = File(data, "tmp/tailscale-$version-$arch.tgz")
        Out.line("Downloading Tailscale $version ($arch)")
        Net.fetch(tarball.getString("url"), tgz, -1, tarball.getString("sha256"), "android.services.tailscale.package")
        val unpacking = File(dir, "$version.new").apply { deleteRecursively(); mkdirs() }
        GZIPInputStream(tgz.inputStream().buffered()).use { untar(it, unpacking, setOf("tailscale", "tailscaled")) }
        tgz.delete()
        for (name in listOf("tailscale", "tailscaled")) {
            val file = File(unpacking, name)
            if (!file.isFile) throw Stop("the Tailscale package has no $name", 1)
            file.setExecutable(true, false)
            file.setReadable(true, false)
        }
        bin.deleteRecursively()
        if (!unpacking.renameTo(bin)) throw Stop("cannot rename ${unpacking.path}", 1)
        return bin
    }

    private fun dropOtherVersions(keep: String) {
        dir.listFiles().orEmpty().filter { it.isDirectory && it.name != keep && it.name != state.name }.forEach { it.deleteRecursively() }
    }

    private fun readSpec(): JSONObject? = runCatching { JSONObject(spec.readText()) }.getOrNull()

    companion object {
        /** Android's processor kinds, and Tailscale's names for them. */
        private val ARCHES = mapOf("arm64-v8a" to "arm64", "armeabi-v7a" to "arm", "x86_64" to "amd64", "x86" to "386")

        val BUSYBOXES = listOf("/data/adb/magisk/busybox", "/data/adb/ksu/bin/busybox", "/data/adb/ap/bin/busybox")

        /** The supervisor's name ($0), how its process is known again. */
        private const val MARK = "seraph-tailscale"

        /**
         * Runs tailscaled (the arguments after the first four) in a mount namespace whose /etc is Seraph's, and starts
         * it again 10 s after it stops; a TERM stops both. Its log is set aside past 1 MB.
         */
        private val SUPERVISOR = """
            b=${'$'}1 etc=${'$'}2 log=${'$'}3
            echo ${'$'}${'$'} > "${'$'}4"
            shift 4
            child=
            trap 'kill ${'$'}child 2> /dev/null; exit 0' TERM INT
            while :; do
              [ -f "${'$'}log" ] && [ "${'$'}(stat -c %s "${'$'}log")" -gt 1048576 ] && mv -f "${'$'}log" "${'$'}log.old"
              "${'$'}b" unshare -m --propagation private sh -c \
                'b=${'$'}0 etc=${'$'}1; shift; "${'$'}b" mount --make-rprivate / && "${'$'}b" mount --bind "${'$'}etc" "${'$'}(readlink -f /etc)" && exec "${'$'}@"' \
                "${'$'}b" "${'$'}etc" "${'$'}@" >> "${'$'}log" 2>&1 < /dev/null &
              child=${'$'}!
              wait ${'$'}child
              echo "${'$'}(date '+%F %T') tailscaled stopped (${'$'}?); starting it again in 10 s" >> "${'$'}log"
              sleep 10 &
              wait ${'$'}!
            done
        """.trimIndent()

        /** A name as the tailnet takes it: lower case letters, digits and -. */
        fun hostname(name: String) = name.lowercase().replace(Regex("[^a-z0-9-]+"), "-").trim('-').ifEmpty { "android" }

        /** The regular files named [names] in a tar stream, into [into] (by their names without folders). */
        private fun untar(input: InputStream, into: File, names: Set<String>) {
            val header = ByteArray(512)
            while (readFully(input, header) == 512 && header.any { it != 0.toByte() }) {
                val name = String(header, 0, 100, Charsets.UTF_8).substringBefore('\u0000')
                val size = String(header, 124, 12, Charsets.US_ASCII).trim { it <= ' ' || it == '\u0000' }.ifEmpty { "0" }.toLong(8)
                val type = header[156].toInt().toChar()
                val base = name.substringAfterLast('/')
                if ((type == '0' || type == '\u0000') && base in names) {
                    File(into, base).outputStream().use { out -> copy(input, out, size) }
                } else {
                    skip(input, size)
                }
                skip(input, (512 - size % 512) % 512)
            }
        }

        private fun readFully(input: InputStream, buffer: ByteArray): Int {
            var have = 0
            while (have < buffer.size) {
                val n = input.read(buffer, have, buffer.size - have)
                if (n < 0) break
                have += n
            }
            return have
        }

        private fun copy(input: InputStream, out: java.io.OutputStream, size: Long) {
            val buffer = ByteArray(1 shl 16)
            var left = size
            while (left > 0) {
                val n = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                if (n < 0) throw java.io.IOException("the package ends too soon")
                out.write(buffer, 0, n)
                left -= n
            }
        }

        private fun skip(input: InputStream, size: Long) {
            var left = size
            val buffer = ByteArray(1 shl 16)
            while (left > 0) {
                val n = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                if (n < 0) return
                left -= n
            }
        }
    }
}

/**
 * Seraph's /etc for the Linux programs it runs (Nix, tailscaled), which look there for DNS servers, trusted
 * certificates and users, where Android has none: the DNS servers of the networks in use, Android's trusted
 * certificates and a root user. They run in a mount namespace of their own with it over /etc.
 */
object Etc {
    /** Writes it; a phone without a network keeps the DNS servers it had. */
    fun write(data: File): File {
        val etc = File(data, "etc")
        val certs = File(etc, "ssl/certs").apply { mkdirs() }
        val pems = listOf("/apex/com.android.conscrypt/cacerts", "/system/etc/security/cacerts")
            .flatMap { File(it).listFiles().orEmpty().sortedBy { file -> file.name } }
        File(certs, "ca-certificates.crt").writeText(pems.joinToString("") { runCatching { it.readText() }.getOrDefault("").let { text -> if (text.endsWith("\n")) text else "$text\n" } })
        File(etc, "passwd").writeText("root:x:0:0:root:${data.path}/home:/system/bin/sh\n")
        File(etc, "group").writeText("root:x:0:\n")
        val dns = Device.dnsServers()
        if (dns.isNotEmpty()) File(etc, "resolv.conf").writeText(dns.joinToString("") { "nameserver $it\n" })
        return etc
    }
}

private fun JSONObject.string(key: String): String? = if (isNull(key)) null else optString(key).ifEmpty { null }

private fun JSONObject.strings(key: String): List<String> = optJSONArray(key)?.let { array -> (0 until array.length()).map { array.getString(it) } } ?: emptyList()
