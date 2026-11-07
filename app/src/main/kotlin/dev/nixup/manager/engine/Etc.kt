package dev.nixup.manager.engine

import android.system.Os
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.TimeZone

/**
 * Nixup's /etc for the Linux programs it runs (Nix, and the services' programs from Nix packages), which look there
 * for DNS servers, trusted certificates, hosts and users, where Android has none: the DNS servers of the networks in
 * use (or networking.nameservers), Android's trusted certificates, localhost (and networking.hosts), the users of
 * users.users and the services' own ([Ids]), the phone's time zone ([writeTimeZone]), and the config's own files
 * (environment.etc). They run in a mount namespace of their own with it over /etc (see Units).
 *
 * The users' shell there (users.users, root's too) is /etc/nixup/login ([LOGIN]): the profile's shell
 * (users.defaultUserShell, else bash), with environment.systemPackages on PATH, started the same way for an ssh session
 * (services.openssh), the app's terminal and nixup-shell. What every session starts with ([SET_ENVIRONMENT]: PATH, a
 * user's own packages first, the time zone, environment.extraInit) /etc/profile and /etc/zshenv give a user's own shell
 * too (users.users.<name>.shell). With NixOS's shells' environment (nix/shells.nix) its /etc/set-environment and
 * shells' files are the config's, and Nixup's own then only adds what the phone has ([writeSystem]: /run/current-system,
 * Android's commands, the phone's name, the users' /run/user). Nix's commands are in /etc/nixup/bin ([writeNixCommands]).
 */
object Etc {
    /**
     * Writes it, with the shell of [android] (a profile's android part; the profile in place when null); a phone
     * without a network keeps the DNS servers it had.
     */
    fun write(data: File, android: JSONObject? = null): File {
        val etc = File(data, "etc")
        closeModes(data, etc)
        val certs = File(etc, "ssl/certs").apply { mkdirs() }
        val pems = listOf("/apex/com.android.conscrypt/cacerts", "/system/etc/security/cacerts")
            .flatMap { File(it).listFiles().orEmpty().sortedBy { file -> file.name } }
        writeWhole(File(certs, "ca-certificates.crt"), pems.joinToString("") { runCatching { it.readText() }.getOrDefault("").let { text -> if (text.endsWith("\n")) text else "$text\n" } })
        val home = File(data, "home").apply { mkdirs() }
        val profile = android ?: current(data)
        // The view's users and groups (users.users, users.groups), or root and sshd's alone; an app's user with that
        // app's uid on this phone, and left out while the app is not there.
        val users = profile?.optJSONObject("users")
        val uids = Ids(data, users?.string("passwd").orEmpty(), users?.string("group").orEmpty())
        val passwd = users?.string("passwd")?.let { uids.fill(it) }
            ?: "root:x:0:0:root:${home.path}:/etc/nixup/login\nsshd:x:74:74:sshd privilege separation:/var/empty:/system/bin/false\n"
        writeWhole(File(etc, "passwd"), passwd)
        writeWhole(File(etc, "group"), users?.string("group")?.let { uids.fill(it) } ?: "root:x:0:\nsshd:x:74:\n")
        uids.keep()
        writeRuntimeDirs(data, passwd)
        // The home of service users and NixOS's empty folder.
        File(data, "var/empty").mkdirs()
        // /home of the view: normal users' homes.
        File(data, "users").mkdirs()
        users?.optJSONArray("homes")?.let { homes ->
            for (i in 0 until homes.length()) {
                val each = homes.getJSONObject(i)
                val uid = uids.number(each.optString("uid")) ?: continue
                val gid = uids.number(each.optString("gid")) ?: uid
                val path = each.getString("path")
                val dir = outside(data, path)
                if (!dir.isDirectory) {
                    // (An app's own folder cannot be made before the phone is first unlocked: it is left until then.)
                    if (!dir.mkdirs()) continue
                    runCatching { Os.chown(dir.path, uid, gid) }
                    runCatching { Os.chmod(dir.path, (each.string("mode") ?: "700").toInt(8)) }
                } else if (path.startsWith("/home/") && runCatching { Os.stat(dir.path).st_uid }.getOrNull() != uid) {
                    // A home of Nixup's whose app was installed again, with another uid: what is in it becomes the new one's.
                    // (Not into a folder a link names: the user may have one to anywhere.)
                    dir.walkTopDown().onEnter { it == dir || !Files.isSymbolicLink(it.toPath()) }.forEach { runCatching { Os.lchown(it.path, uid, gid) } }
                }
            }
        }
        // networking.hosts and networking.extraHosts, and the DNS servers: networking.nameservers, else the network's.
        val network = profile?.optJSONObject("network")
        // The phone's own name too (its kernel's, as programs look themselves up, and networking.hostName), as NixOS's
        // /etc/hosts has it.
        val deviceName = profile?.optJSONObject("system")?.optJSONObject("settings")?.optJSONObject("global")?.string("device_name")
        val names = listOfNotNull(runCatching { Os.uname().nodename }.getOrNull(), deviceName?.replace(Regex("[^A-Za-z0-9._-]"), "-"))
            .filter { it.isNotBlank() && it != "localhost" }.distinct()
        writeHosts(data, etc, "127.0.0.1 localhost\n::1 localhost ip6-localhost\n" +
            (if (names.isNotEmpty()) "127.0.0.2 ${names.joinToString(" ")}\n" else "") + (network?.string("hosts") ?: ""),
            network?.strings("hostFiles").orEmpty())
        writeDns(etc, network)
        // The proxy's variables (networking.proxy), for the phone's own Nix (module/common.sh's nix_env reads them).
        val proxy = File(etc, "nixup/proxy")
        val proxyVars = network?.optJSONObject("proxy")
        if (proxyVars == null || proxyVars.length() == 0) proxy.delete()
        else {
            proxy.parentFile?.mkdirs()
            writeWhole(proxy, proxyVars.keys().asSequence().filter { it.matches(Regex("[a-z_]+")) }
                .joinToString("") { "export $it=${quote(proxyVars.getString(it))}\n" })
        }
        // The config's own files (environment.etc), and the phone's time zone unless they have one.
        writeFiles(data, etc, profile?.optJSONArray("etc"))
        writeTimeZone(etc, profile?.optJSONArray("etc"))
        // A machine id, as systemd makes one at the first start (systemd-creds, D-Bus and others read it), kept from
        // then on; environment.etc."machine-id" gives one of its own.
        val machineId = File(etc, "machine-id")
        if (!machineId.isFile && !Files.isSymbolicLink(machineId.toPath())) {
            machineId.writeText(java.util.UUID.randomUUID().toString().replace("-", "") + "\n")
        }
        writeLogin(etc, home, profile?.optJSONObject("shell"))
        writeSystem(data, profile?.optJSONObject("shell"))
        // Android's own commands' folders, which NixOS's /etc/set-environment puts on PATH after the Nix ones
        // (nix/shells.nix).
        writeWhole(File(etc, "nixup/path"), androidPath().joinToString(":") + "\n")
        writeNixCommands(etc, profile?.optJSONObject("nix"))
        // What every session runs first (environment.extraInit).
        writeWhole(File(etc, "nixup/environment"), profile?.string("extraInit") ?: "")
        writeVersion(etc, profile?.optJSONObject("version"))
        // The app's terminal: its font and colors (terminal.font, terminal.colors), read by the app when it opens.
        val terminal = File(etc, "nixup/terminal.json")
        val look = profile?.optJSONObject("terminal")
        if (look == null || (look.string("font") == null && (look.optJSONObject("colors")?.length() ?: 0) == 0)) terminal.delete()
        else terminal.writeText(look.toString())
        // The shells' prompt, with the phone's name (its device name, networking.hostName).
        val name = profile?.optJSONObject("system")?.optJSONObject("settings")?.optJSONObject("global")?.string("device_name")
            ?: Device.run("settings", "get", "global", "device_name").second.trim().takeIf { it.isNotEmpty() && it != "null" } ?: "phone"
        val hostName = name.replace(Regex("[^A-Za-z0-9._-]"), "-")
        // For NixOS's prompt (NIXUP_HOSTNAME, nix/shells.nix), as the kernel's name is Android's own.
        writeWhole(File(etc, "nixup/hostname"), "$hostName\n")
        // Nixup's own shells' files, unless environment.etc has them (NixOS's programs.bash and programs.zsh write theirs:
        // nix/shells.nix); bashrc for every interactive bash (nix shell's too).
        val own = profile?.optJSONArray("etc")?.let { entries -> (0 until entries.length()).map { entries.getJSONObject(it).getString("target").trim('/') } }.orEmpty()
        if ("profile" !in own) writeWhole(File(etc, "profile"), PROFILE)
        if ("zshenv" !in own) writeWhole(File(etc, "zshenv"), ZSHENV)
        if ("bashrc" !in own) writeWhole(File(etc, "bashrc"), BASHRC.replace("@NAME@", hostName))
        return etc
    }

    /**
     * /etc/nixup/version: the system's version and the config's revision ([version], a profile's version part:
     * system.nixos.version, label, ..., system.configurationRevision; nix/version.nix), as "name=value" lines, which
     * nixos-version tells and each generation keeps (module/common.sh). None for a profile without it.
     */
    private fun writeVersion(etc: File, version: JSONObject?) {
        val file = File(etc, "nixup/version")
        if (version == null) {
            file.delete()
            return
        }
        val names = listOf("version", "release", "codeName", "revision", "label", "configurationRevision")
        file.writeText(names.joinToString("") { name -> "$name=${version.string(name).orEmpty().replace("\n", " ")}\n" })
    }

    /**
     * Nix's commands (nix, nix-shell, nix-env, ...: [nix]'s commands, a profile's nix part) in /etc/nixup/bin, which the
     * shells have on PATH and the units run (nix/nix.nix): nix is the module's own Nix, and each other command a link to
     * it, which it acts as. The module's folder is in /data/adb, which only root may enter, so nix is that file itself
     * under a second name (a hard link; a copy when the module is on another file system), which every user may run.
     * The installed module's (it stays where it is from one start to the next, and each start takes it again), else
     * the one this engine runs from (a module installed, the phone not started again yet). None with nix.enable = false.
     */
    fun writeNixCommands(etc: File, nix: JSONObject?) {
        val dir = File(etc, "nixup/bin")
        val commands = nix?.strings("commands").orEmpty().filter { it.matches(Regex("[A-Za-z0-9._-]+")) }
        val binary = listOf(File("/data/adb/modules/nixup/bin/nix"), Notify.module?.let { File(it, "bin/nix") })
            .firstOrNull { it != null && it.isFile && it.canExecute() }
        if (commands.isEmpty() || binary == null) {
            // (Links each, and nix's own name: deleted as they are, never what they lead to.)
            dir.listFiles().orEmpty().forEach { it.delete() }
            dir.delete()
            return
        }
        dir.mkdirs()
        runCatching { Os.chmod(dir.path, "755".toInt(8)) }
        // nix: again when the module's is another file (a new module), as a hard link the same file, as a copy its size
        // and time.
        val nixFile = File(dir, "nix")
        val wanted = Os.stat(binary.path)
        val have = runCatching { Os.lstat(nixFile.path) }.getOrNull()
        val same = have != null && android.system.OsConstants.S_ISREG(have.st_mode) &&
            ((have.st_dev == wanted.st_dev && have.st_ino == wanted.st_ino) || (have.st_size == wanted.st_size && have.st_mtime == wanted.st_mtime))
        if (!same) {
            val next = File(dir, ".nix.new")
            next.delete()
            runCatching { Os.link(binary.path, next.path) }.getOrElse {
                binary.copyTo(next, overwrite = true)
                next.setLastModified(binary.lastModified())
                Os.chmod(next.path, "755".toInt(8))
            }
            if (!next.renameTo(nixFile)) next.delete()
        }
        for (command in commands) {
            if (command == "nix") continue
            val link = File(dir, command)
            if (runCatching { Os.readlink(link.path) }.getOrNull() == "nix") continue
            val next = File(dir, ".$command.new")
            next.delete()
            Os.symlink("nix", next.path)
            if (!next.renameTo(link)) next.delete()
        }
        dir.listFiles().orEmpty().filter { it.name !in commands && it.name != "nix" }.forEach { it.delete() }
    }

    /**
     * /etc/hosts: [head] (localhost, the phone's names, networking.hosts and extraHosts), then the lines of each file of
     * networking.hostFiles ([files]: Nix store paths the build fetched; stevenblack's blocklist is some 4 MB, so it is
     * copied, not read whole). Whole or not at all, for a program that reads it now; a file not there is told and left.
     */
    private fun writeHosts(data: File, etc: File, head: String, files: List<String>) {
        val file = File(etc, "hosts")
        val next = File(etc, ".hosts.new")
        next.outputStream().buffered().use { out ->
            out.write(head.toByteArray())
            for (path in files) {
                val source = outside(data, path)
                if (!source.isFile) { Out.line("✘ /etc/hosts: $path (networking.hostFiles) is not there"); continue }
                out.write("\n# $path (networking.hostFiles)\n".toByteArray())
                source.inputStream().use { it.copyTo(out) }
                out.write("\n".toByteArray())
            }
        }
        if (!next.renameTo(file)) { next.delete(); file.writeText(head) }
    }

    /**
     * Nixup's folders and its /etc as a Nixup started by Magisk left them before 2026-11-07 (Magisk runs the module's
     * scripts with umask 0): writable by every user of the view, who could then change what root reads and runs
     * (/etc/passwd, /etc/nixup/login). Root's own ones lose that; a shared folder (sticky, like a tmp) keeps it, and
     * environment.etc's files get their own mode again after this.
     */
    private fun closeModes(data: File, etc: File) {
        val others = "022".toInt(8)
        fun close(file: File) = runCatching {
            val stat = Os.lstat(file.path)
            val mode = stat.st_mode and "7777".toInt(8)
            if (stat.st_uid != 0 || android.system.OsConstants.S_ISLNK(stat.st_mode) || mode and others == 0) return@runCatching
            if (android.system.OsConstants.S_ISDIR(stat.st_mode) && mode and "1000".toInt(8) != 0) return@runCatching
            Os.chmod(file.path, mode and others.inv())
        }
        close(data)
        data.listFiles().orEmpty().forEach { close(it) }
        etc.walkTopDown().onEnter { !Files.isSymbolicLink(it.toPath()) }.forEach { close(it) }
    }

    /**
     * The config's files in /etc (environment.etc: a profile's "etc"): its text, a link to its source (in Nix's store,
     * as the view has it) or with a mode a copy of it, owned by its user and group. The ones a build before wrote that
     * this profile has not (listed in .nixup-files) go.
     */
    private fun writeFiles(data: File, etc: File, entries: JSONArray?) {
        val list = File(etc, ".nixup-files")
        // (Only names in /etc, as written below: what else is in the list goes nowhere.)
        val before = runCatching { list.readLines().filter { it.isNotBlank() && !it.startsWith("/") && it.split('/').none { part -> part == ".." || part == "." } } }
            .getOrDefault(emptyList())
        val now = mutableListOf<String>()
        val accounts by lazy { accounts(data) }
        for (i in 0 until (entries?.length() ?: 0)) {
            val entry = entries!!.getJSONObject(i)
            val target = entry.getString("target").trim('/')
            if (target.isEmpty() || target.split('/').any { it == ".." || it == "." }) continue
            val file = File(etc, target)
            now += target
            runCatching {
                file.parentFile?.mkdirs()
                val mode = entry.optString("mode", "symlink")
                // (An empty text is a file of its own too: string() would make it none.)
                val text = if (entry.has("text") && !entry.isNull("text")) entry.getString("text") else null
                val source = entry.string("source")
                // What is there goes first: a link is made anew, and a file of another kind replaced.
                if (Files.isSymbolicLink(file.toPath()) || file.isFile) file.delete()
                when {
                    mode == "symlink" || mode == "direct-symlink" ->
                        if (text != null) file.writeText(text) else Os.symlink(source!!, file.path)
                    else -> {
                        if (text != null) file.writeText(text) else outside(data, source!!).copyTo(file, overwrite = true)
                        Os.chmod(file.path, mode.toInt(8))
                        val uid = owner(entry.optString("user"), accounts.users.mapValues { it.value.uid }) ?: 0
                        val gid = owner(entry.optString("group"), accounts.groups) ?: 0
                        Os.chown(file.path, uid, gid)
                    }
                }
            }.onFailure { Out.line("✘ /etc/$target: ${it.message}") }
        }
        for (gone in before - now.toSet()) {
            val file = File(etc, gone)
            if (Files.isSymbolicLink(file.toPath()) || file.isFile) file.delete()
            // Its folders too, when nothing else is in them.
            var parent = file.parentFile
            while (parent != null && parent != etc && parent.list()?.isEmpty() == true) {
                parent.delete()
                parent = parent.parentFile
            }
        }
        if (now.isEmpty()) list.delete() else list.writeText(now.joinToString("") { "$it\n" })
    }

    /**
     * The phone's time zone as Linux programs read it, unless environment.etc gives one: /etc/localtime (its rules, a
     * TZif file, out of Android's time zone data) and /etc/timezone (its name). With it, programs that clear their
     * environment (cron, for its jobs) keep the phone's time, and so does a clock change of the zone (summer time).
     */
    private fun writeTimeZone(etc: File, entries: JSONArray?) {
        val own = (0 until (entries?.length() ?: 0)).map { entries!!.getJSONObject(it).getString("target").trim('/') }
        val zone = Device.timeZone().ifEmpty { TimeZone.getDefault().id }
        val zoneinfo = "zoneinfo" !in own && writeZoneinfo(File(etc, "zoneinfo"))
        if ("localtime" !in own) {
            val file = File(etc, "localtime")
            val rules = zoneRules(zone)
            // As NixOS has it: a link to its zone in /etc/zoneinfo, by which programs know its name (.NET, Java).
            val link = "zoneinfo/$zone"
            val linked = zoneinfo && zone.isNotEmpty() && File(etc, link).isFile
            when {
                linked -> if (!Files.isSymbolicLink(file.toPath()) || runCatching { Os.readlink(file.path) }.getOrNull() != link) {
                    val next = File(etc, ".localtime.new")
                    next.delete()
                    Os.symlink(link, next.path)
                    if (!next.renameTo(file)) next.delete()
                }
                // (Not found: the services' TZ, the zone's offset from UTC, stays theirs; see Units.)
                rules == null -> if (Files.isSymbolicLink(file.toPath()) || file.isFile) file.delete()
                Files.isSymbolicLink(file.toPath()) || !file.isFile || !file.readBytes().contentEquals(rules) -> {
                    // Whole or not at all, for a program that reads it now.
                    val next = File(etc, ".localtime.new")
                    next.writeBytes(rules)
                    Os.chmod(next.path, "644".toInt(8))
                    if (Files.isSymbolicLink(file.toPath())) file.delete()
                    if (!next.renameTo(file)) next.delete()
                }
            }
        }
        if ("timezone" !in own && zone.isNotEmpty()) {
            val file = File(etc, "timezone")
            if (runCatching { file.readText() }.getOrNull() != "$zone\n") file.writeText("$zone\n")
        }
    }

    /** Android's time zone data, its newest copy first (the time zone updates' module, then the system's). */
    private val TZDATA = listOf("/apex/com.android.tzdata/etc/tz/tzdata", "/system/usr/share/zoneinfo/tzdata")

    /**
     * Each zone of Android's time zone data [file] (bionic's tzdata: one file with every zone: a header, an index of
     * 52-byte entries, then the zones), as its name and its rules (a TZif file, as /usr/share/zoneinfo has them), to
     * [take], until it says it has what it wants (true). Whether the file was read.
     */
    private fun zones(file: File, take: (String, () -> ByteArray) -> Boolean): Boolean = runCatching {
        RandomAccessFile(file, "r").use { tzdata ->
            val magic = ByteArray(12).also { tzdata.readFully(it) }
            if (!String(magic, Charsets.US_ASCII).startsWith("tzdata")) return@use false
            val index = tzdata.readInt()
            val start = tzdata.readInt()
            val name = ByteArray(40)
            for (i in 0 until (start - index) / 52) {
                tzdata.seek(index.toLong() + 52L * i)
                tzdata.readFully(name)
                val offset = tzdata.readInt()
                val length = tzdata.readInt()
                val rules = { tzdata.seek(start.toLong() + offset); ByteArray(length).also { tzdata.readFully(it) } }
                if (take(String(name, Charsets.US_ASCII).trimEnd('\u0000'), rules)) break
            }
            true
        }
    }.getOrDefault(false)

    /** The rules of time zone [zone] (a TZif file), out of Android's time zone data. */
    fun zoneRules(zone: String): ByteArray? {
        for (path in TZDATA) {
            val file = File(path).takeIf { it.isFile } ?: continue
            var found: ByteArray? = null
            zones(file) { name, rules -> (name == zone).also { if (it) found = rules() } }
            if (found != null) return found
        }
        return null
    }

    /**
     * /etc/zoneinfo, as NixOS's (TZDIR): every zone of Android's time zone data, for programs that look a zone up by
     * its name (TZ=Europe/Berlin, .NET's and Java's own lookups). Written again only when Android's data changes; whether
     * it is there.
     */
    private fun writeZoneinfo(dir: File): Boolean {
        val file = TZDATA.map { File(it) }.firstOrNull { it.isFile } ?: return false
        val version = File(dir, ".nixup-tzdata")
        val stamp = "${file.path} ${file.length()} ${file.lastModified()}\n"
        if (runCatching { version.readText() }.getOrNull() == stamp) return true
        val written = zones(file) { name, rules ->
            if (name.isNotEmpty() && name.split('/').none { it == ".." || it == "." || it.isEmpty() }) {
                val zoneFile = File(dir, name)
                zoneFile.parentFile?.mkdirs()
                zoneFile.writeBytes(rules())
            }
            false
        }
        if (written) version.writeText(stamp)
        return written
    }

    /**
     * Nixup's /etc/resolv.conf: networking.nameservers, else the DNS servers of the phone's network now (one without a
     * network keeps the ones it had); whether it changed. Its services read it again when it does (glibc, Go).
     */
    fun writeDns(data: File): Boolean = writeDns(File(data, "etc"), current(data)?.optJSONObject("network"))

    private fun writeDns(etc: File, network: JSONObject?): Boolean {
        val dns = network?.strings("nameservers")?.takeIf { it.isNotEmpty() } ?: Device.dnsServers()
        if (dns.isEmpty()) return false
        val text = dns.joinToString("") { "nameserver $it\n" }
        val file = File(etc, "resolv.conf")
        if (runCatching { file.readText() }.getOrNull() == text) return false
        file.writeText(text)
        return true
    }

    /** "+<number>", or a name of [names]. */
    private fun owner(text: String, names: Map<String, Int>): Int? =
        if (text.startsWith("+")) text.drop(1).toIntOrNull() else names[text] ?: text.toIntOrNull()

    /** A user of the view's /etc/passwd: its uid, gid and home. */
    class Account(val uid: Int, val gid: Int, val home: String)

    /** The view's users and groups, as the last build wrote /etc/passwd and /etc/group, by name. */
    class Accounts(val users: Map<String, Account>, val groups: Map<String, Int>)

    fun accounts(data: File): Accounts {
        val etc = File(data, "etc")
        val users = runCatching { File(etc, "passwd").readLines() }.getOrDefault(emptyList()).mapNotNull { line ->
            val f = line.split(':')
            if (f.size < 7) null else f[2].toIntOrNull()?.let { uid -> f[0] to Account(uid, f[3].toIntOrNull() ?: uid, f[5]) }
        }.toMap()
        val groups = runCatching { File(etc, "group").readLines() }.getOrDefault(emptyList()).mapNotNull { line ->
            val f = line.split(':')
            if (f.size < 3) null else f[2].toIntOrNull()?.let { f[0] to it }
        }.toMap()
        return Accounts(users, groups)
    }

    /**
     * A path of Nixup's view as it is outside of it, where the engine runs: /home, /etc, /var and /run there are
     * Nixup's own folders (Units' view.sh), and /nix its store.
     */
    fun outside(data: File, path: String): File {
        for ((inView, own) in listOf("/home" to "users", "/etc" to "etc", "/var" to "var", "/run" to "run", "/nix" to "nix/nix")) {
            if (path == inView || path.startsWith("$inView/")) return File(File(data, own), path.removePrefix(inView).trimStart('/'))
        }
        return File(path)
    }

    /**
     * The uids and gids the profile's /etc/passwd and /etc/group leave to the phone: "@app:<package>@"
     * (users.users.<name>.androidApp: that app's uid on this phone, looked up once each), and "@id:u:<name>@" and
     * "@id:g:<name>@", a user or group with no number of its own (a service's): one from 999 down to 400, as NixOS gives
     * its system users, the same for a user and its group when it can be, given once and kept in ids.json (as NixOS
     * keeps them in /var/lib/nixos), so a service's files stay its own from one build to the next. Android lets uids
     * below 10000 use the network, and gives none from 1 to 999 to anything of its own.
     */
    private class Ids(data: File, passwd: String, group: String) {
        private val found = mutableMapOf<String, Int?>()
        private fun uid(app: String) = found.getOrPut(app) { Device.uid(app) }
        private val file = File(data, "ids.json")
        private val kept: JSONObject = runCatching { JSONObject(file.readText()) }.getOrDefault(JSONObject())
        private var changed = false
        // The numbers the config gives itself, which no one else may get.
        private val usedUids = numbers(passwd)
        private val usedGids = numbers(group)

        private fun numbers(text: String) = text.lineSequence().mapNotNull { it.split(':').getOrNull(2)?.toIntOrNull() }.toSet()

        private fun taken(kind: Char): Set<Int> = (if (kind == 'u') usedUids else usedGids) +
            kept.keys().asSequence().filter { it.startsWith("$kind:") }.map { kept.getInt(it) }.toSet()

        /** The number of user ([kind] u) or group (g) [name]: the one it was given, else a new one. */
        private fun id(kind: Char, name: String): Int? {
            val key = "$kind:$name"
            if (kept.has(key)) return kept.getInt(key)
            val other = if (kind == 'u') 'g' else 'u'
            val mine = taken(kind)
            val theirs = taken(other)
            // The number its user's (or group's) namesake has, else one free for both.
            val pair = kept.optInt("$other:$name", -1).takeIf { it in RANGE && it !in mine }
            val number = pair ?: RANGE.reversed().firstOrNull { it !in mine && it !in theirs } ?: RANGE.reversed().firstOrNull { it !in mine }
                ?: return null
            kept.put(key, number)
            changed = true
            return number
        }

        /** [text] (/etc/passwd or /etc/group) with the numbers filled in, without the lines of an app that is not there. */
        fun fill(text: String): String = text.lineSequence().filter { it.isNotEmpty() }.mapNotNull { line ->
            var missing = false
            val filled = PLACEHOLDER.replace(line) { match -> resolve(match.value)?.toString() ?: run { missing = true; match.value } }
            filled.takeIf { !missing }
        }.joinToString("") { "$it\n" }

        private fun resolve(text: String): Int? {
            APP.matchEntire(text)?.let { return uid(it.groupValues[1]) }
            ID.matchEntire(text)?.let { return id(it.groupValues[1][0], it.groupValues[2]) }
            return text.toIntOrNull()
        }

        /** A uid or gid as the profile has it: a number, an app's, or a service user's; null when it cannot be had. */
        fun number(text: String): Int? = resolve(text)

        /** Keeps the numbers given. */
        fun keep() {
            if (changed) runCatching { file.writeText(kept.toString(2)) }
        }

        companion object {
            private val RANGE = 400..999
            private val ID = Regex("@id:([ug]):([A-Za-z0-9._-]+)@")
            private val PLACEHOLDER = Regex("@app:[A-Za-z0-9._]+@|@id:[ug]:[A-Za-z0-9._-]+@")
        }
    }

    /**
     * /run/current-system/sw, as on NixOS: system.path ([shell]'s system: environment.systemPackages with the shells'
     * packages), whose bin folder NixOS's /etc/set-environment puts on PATH (nix/shells.nix); gone when the profile has
     * none. [data]'s run is the view's /run.
     */
    private fun writeSystem(data: File, shell: JSONObject?) {
        val system = shell?.string("system")
        link(File(data, "run/current-system/sw"), system)
        if (system == null) File(data, "run/current-system").delete()
    }

    /** [file] made a link to [target] (whole or not at all), or one there removed when [target] is null. */
    private fun link(file: File, target: String?) {
        if (target == null) {
            if (Files.isSymbolicLink(file.toPath())) file.delete()
            return
        }
        if (runCatching { Os.readlink(file.path) }.getOrNull() == target) return
        val dir = file.parentFile ?: return
        dir.mkdirs()
        val next = File(dir, ".${file.name}.new")
        next.delete()
        Os.symlink(target, next.path)
        if (!next.renameTo(file)) next.delete()
    }

    /**
     * Each user that logs in (a shell in /etc/passwd) has a /run/user/<uid> of its own, as logind makes it on NixOS: its
     * XDG_RUNTIME_DIR (nix/shells.nix), where tmux keeps its socket and ssh-agent its own. [data]'s run is the view's
     * /run.
     */
    private fun writeRuntimeDirs(data: File, passwd: String) {
        for (line in passwd.lineSequence()) {
            val f = line.split(':')
            if (f.size < 7 || f[6].isEmpty() || f[6] == "/system/bin/false") continue
            val uid = f[2].toIntOrNull() ?: continue
            val gid = f[3].toIntOrNull() ?: uid
            val dir = File(data, "run/user/$uid")
            if (!dir.isDirectory && !dir.mkdirs()) continue
            runCatching { Os.chown(dir.path, uid, gid) }
            runCatching { Os.chmod(dir.path, "700".toInt(8)) }
        }
    }

    /** The android part of the profile in place, if there is one. */
    private fun current(data: File): JSONObject? =
        runCatching { JSONObject(File(data, "profile.json").readText()).optJSONObject("android") }.getOrNull()

    /**
     * /etc/nixup/login, for [shell] (the profile's: its PATH and bash), or Android's sh when the config has no Nix
     * packages; and /etc/nixup/set-environment, with that PATH.
     */
    private fun writeLogin(etc: File, home: File, shell: JSONObject?) {
        val dir = File(etc, "nixup").apply { mkdirs() }
        File(dir, "set-environment").writeText(SET_ENVIRONMENT.replace("@PATH@", quote(path(shell?.string("path") ?: "/system/bin:/system/xbin"))))
        val login = File(dir, "login")
        val fresh = File(dir, ".login.new")
        fresh.writeText(
            LOGIN.replace("@HOME@", quote(home.path))
                .replace("@SHELL@", quote(shell?.string("bash") ?: "/system/bin/sh")),
        )
        Os.chmod(fresh.path, "755".toInt(8))
        fresh.renameTo(login)
    }

    private fun quote(text: String) = "'" + text.replace("'", "'\\''") + "'"

    private val APP = Regex("@app:([A-Za-z0-9._]+)@")

    /** The profile's PATH, then the rest of Android's own ([androidPath]). */
    private fun path(profile: String): String {
        val dirs = profile.split(':')
        return (dirs + androidPath().filter { it !in dirs }).joinToString(":")
    }

    /**
     * Android's own PATH (the engine's, as Android starts programs: /product/bin, /system/xbin, the APEXes, ...), where a
     * root solution may keep su.
     */
    private fun androidPath(): List<String> =
        (ANDROID_PATH + (System.getenv("PATH") ?: "").split(':')).filter { it.isNotEmpty() }.distinct()

    /** Android's own PATH, as its init sets it (a boot script's may be shorter). */
    private val ANDROID_PATH = listOf(
        "/product/bin", "/apex/com.android.runtime/bin", "/apex/com.android.art/bin", "/system_ext/bin", "/system/bin",
        "/system/xbin", "/odm/bin", "/vendor/bin", "/vendor/xbin",
    )

    /**
     * What every session in the view starts with, once: the profile's PATH, with the user's own packages first (its
     * profile, /etc/profiles/per-user/<user>: users.users.<name>.packages, Home Manager's) and Android's commands after;
     * the time zone; environment.extraInit. Sourced by /etc/nixup/login, /etc/profile and /etc/zshenv.
     */
    private val SET_ENVIRONMENT = """
        # What every session in Nixup's view starts with, once (written by the engine: Etc.kt): PATH (the user's own
        # packages, then environment.systemPackages, then Android's commands), the time zone, environment.extraInit.
        [ -n "${'$'}{__NIXUP_SET_ENVIRONMENT_DONE:-}" ] && return 0
        export __NIXUP_SET_ENVIRONMENT_DONE=1
        export PATH=@PATH@
        nixup_user=${'$'}{USER:-${'$'}(/system/bin/id -un 2> /dev/null)}
        [ -n "${'$'}nixup_user" ] && [ -d "/etc/profiles/per-user/${'$'}nixup_user/bin" ] && PATH="/etc/profiles/per-user/${'$'}nixup_user/bin:${'$'}PATH"
        unset nixup_user
        export LANG="${'$'}{LANG:-C.UTF-8}" TERM="${'$'}{TERM:-xterm-256color}"
        # Android's time zone is /etc/localtime (its zones in /etc/zoneinfo); without it, its offset from UTC
        # (POSIX: "<+0300>-03:00").
        [ -d /etc/zoneinfo ] && export TZDIR=/etc/zoneinfo
        if [ -z "${'$'}{TZ:-}" ] && [ ! -s /etc/localtime ]; then
          off=${'$'}(/system/bin/date +%z)
          case ${'$'}off in
            +[0-9][0-9][0-9][0-9]) export TZ="<${'$'}off>-${'$'}{off:1:2}:${'$'}{off:3:2}" ;;
            -[0-9][0-9][0-9][0-9]) export TZ="<${'$'}off>+${'$'}{off:1:2}:${'$'}{off:3:2}" ;;
          esac
          unset off
        fi
        # environment.extraInit.
        [ -r /etc/nixup/environment ] && . /etc/nixup/environment
        # NixOS's shells' environment (nix/shells.nix), when the config has it: environment.variables, the profiles'
        # PATH, environment.extraInit (which has the phone's own settings and Android's commands).
        [ -r /etc/set-environment ] && . /etc/set-environment
        return 0
    """.trimIndent() + "\n"

    /** What bash reads in a login shell: what every session starts with, /etc/bashrc (the prompt) and ~/.bashrc, as distributions have it. */
    private val PROFILE = """
        # Nixup's /etc/profile, for login shells in its view (written by the engine: Etc.kt).
        [ -r /etc/nixup/set-environment ] && . /etc/nixup/set-environment
        if [ -n "${'$'}BASH_VERSION" ] && [ -n "${'$'}PS1" ]; then
          [ -r /etc/bashrc ] && . /etc/bashrc
          [ -r "${'$'}HOME/.bashrc" ] && . "${'$'}HOME/.bashrc"
        fi
    """.trimIndent() + "\n"

    /** What zsh reads first, in any session: what every session starts with, as /etc/profile gives it to bash. */
    private val ZSHENV = """
        # Nixup's /etc/zshenv, for zsh in its view (written by the engine: Etc.kt).
        [ -r /etc/nixup/set-environment ] && emulate sh -c '. /etc/nixup/set-environment'
    """.trimIndent() + "\n"

    /**
     * What every interactive bash reads (nixpkgs' bash reads /etc/bashrc; a login shell through /etc/profile): a prompt
     * with the phone's name, also in a shell another program starts (nix shell's, tmux's).
     */
    private val BASHRC = """
        # Nixup's /etc/bashrc, for interactive bash shells in its view (written by the engine: Etc.kt).
        if [ -n "${'$'}PS1" ]; then
          PS1='\[\e[1;32m\]\u@@NAME@\[\e[0m\]:\[\e[1;34m\]\w\[\e[0m\]\${'$'} '
        fi
    """.trimIndent() + "\n"

    /**
     * The users' shell in the view: for a login (an ssh session, the terminal) a login shell in its home, else the
     * command it is given (`login -c 'ls'`, as sshd runs a command or SFTP).
     */
    private val LOGIN = """
        #!/system/bin/sh
        # The users' shell in Nixup's view of the phone, for ssh sessions, the app's terminal and nixup-shell: the
        # profile's (the user's own packages and environment.systemPackages on PATH, then Android's commands). Written
        # by the engine (Etc.kt).
        # SHELL is the shell itself, as on NixOS: a program that starts one (nix shell, tmux) keeps the PATH it made.
        export SHELL=@SHELL@
        # root's home; another user's is the one sshd gave it, from /etc/passwd. (The uid as the kernel has it: Android's
        # id stops at a user that only Nixup's /etc/passwd has, "bad uid".)
        while read -r key uid rest; do [ "${'$'}key" = Uid: ] && break; done < /proc/self/status
        [ "${'$'}uid" = 0 ] && export HOME=@HOME@
        unset key uid rest
        # What every session starts with (PATH, the time zone, environment.extraInit), anew.
        unset __NIXUP_SET_ENVIRONMENT_DONE __NIXOS_SET_ENVIRONMENT_DONE __ETC_PROFILE_DONE
        . /etc/nixup/set-environment
        # nixup-shell's one command, run as it is, whatever the shell is (fish's -c takes no sh).
        if [ "${'$'}{1:-}" = --nixup-exec ]; then
          shift
          exec "${'$'}@"
        fi
        [ ${'$'}# -gt 0 ] && exec @SHELL@ "${'$'}@"
        cd "${'$'}HOME" 2> /dev/null
        exec @SHELL@ -l
    """.trimIndent() + "\n"
}

internal fun JSONObject.string(key: String): String? = if (isNull(key)) null else optString(key).ifEmpty { null }

internal fun JSONObject.ints(key: String): List<Int> = optJSONArray(key)?.let { array -> (0 until array.length()).map { array.getInt(it) } } ?: emptyList()

internal fun JSONObject.strings(key: String): List<String> = optJSONArray(key)?.let { array -> (0 until array.length()).map { array.getString(it) } } ?: emptyList()
