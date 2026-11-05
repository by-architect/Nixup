package dev.seraph.manager.engine

import android.system.Os
import org.json.JSONObject
import java.io.File

/**
 * Seraph's /etc for the Linux programs it runs (Nix, and the services' programs from Nix packages), which look there
 * for DNS servers, trusted certificates, hosts and users, where Android has none: the DNS servers of the networks in
 * use, Android's trusted certificates, localhost, a root user, and sshd's user for its unprivileged part. They run in
 * a mount namespace of their own with it over /etc (see Units).
 *
 * The users' shell there (users.users, root's too) is /etc/seraph/login ([LOGIN]): the profile's shell, with
 * environment.systemPackages on PATH, started the same way for an ssh session (services.openssh), the app's terminal
 * and seraph-shell.
 */
object Etc {
    /**
     * Writes it, with the shell of [android] (a profile's android part; the profile in place when null); a phone
     * without a network keeps the DNS servers it had.
     */
    fun write(data: File, android: JSONObject? = null): File {
        val etc = File(data, "etc")
        val certs = File(etc, "ssl/certs").apply { mkdirs() }
        val pems = listOf("/apex/com.android.conscrypt/cacerts", "/system/etc/security/cacerts")
            .flatMap { File(it).listFiles().orEmpty().sortedBy { file -> file.name } }
        File(certs, "ca-certificates.crt").writeText(pems.joinToString("") { runCatching { it.readText() }.getOrDefault("").let { text -> if (text.endsWith("\n")) text else "$text\n" } })
        val home = File(data, "home").apply { mkdirs() }
        val profile = android ?: current(data)
        // The view's users and groups (users.users, users.groups), or root and sshd's alone; an app's user with that
        // app's uid on this phone, and left out while the app is not there.
        val users = profile?.optJSONObject("users")
        val uids = AppUids()
        File(etc, "passwd").writeText(users?.string("passwd")?.let { uids.fill(it) }
            ?: "root:x:0:0:root:${home.path}:/etc/seraph/login\nsshd:x:74:74:sshd privilege separation:/var/empty:/system/bin/false\n")
        File(etc, "group").writeText(users?.string("group")?.let { uids.fill(it) } ?: "root:x:0:\nsshd:x:74:\n")
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
                    runCatching { Os.chmod(dir.path, "700".toInt(8)) }
                } else if (path.startsWith("/home/") && runCatching { Os.stat(dir.path).st_uid }.getOrNull() != uid) {
                    // A home of Seraph's whose app was installed again, with another uid: what is in it becomes the new one's.
                    dir.walkTopDown().forEach { runCatching { Os.lchown(it.path, uid, gid) } }
                }
            }
        }
        File(etc, "hosts").writeText("127.0.0.1 localhost\n::1 localhost ip6-localhost\n")
        val dns = Device.dnsServers()
        if (dns.isNotEmpty()) File(etc, "resolv.conf").writeText(dns.joinToString("") { "nameserver $it\n" })
        writeLogin(etc, home, profile?.optJSONObject("shell"))
        // What every session runs first (environment.extraInit).
        File(etc, "seraph/environment").writeText(profile?.string("extraInit") ?: "")
        // The app's terminal: its font and colors (terminal.font, terminal.colors), read by the app when it opens.
        val terminal = File(etc, "seraph/terminal.json")
        val look = profile?.optJSONObject("terminal")
        if (look == null || (look.string("font") == null && (look.optJSONObject("colors")?.length() ?: 0) == 0)) terminal.delete()
        else terminal.writeText(look.toString())
        // A login shell's prompt, with the phone's name (its device name, networking.hostName).
        val name = profile?.optJSONObject("system")?.optJSONObject("settings")?.optJSONObject("global")?.string("device_name")
            ?: Device.run("settings", "get", "global", "device_name").second.trim().takeIf { it.isNotEmpty() && it != "null" } ?: "phone"
        File(etc, "profile").writeText(PROFILE.replace("@NAME@", name.replace(Regex("[^A-Za-z0-9._-]"), "-")))
        return etc
    }

    /**
     * A path of Seraph's view as it is outside of it, where the engine runs: /home, /etc, /var and /run there are
     * Seraph's own folders (Units' view.sh), and /nix its store.
     */
    fun outside(data: File, path: String): File {
        for ((inView, own) in listOf("/home" to "users", "/etc" to "etc", "/var" to "var", "/run" to "run", "/nix" to "nix/nix")) {
            if (path == inView || path.startsWith("$inView/")) return File(File(data, own), path.removePrefix(inView).trimStart('/'))
        }
        return File(path)
    }

    /** "@app:<package>@" (users.users.<name>.androidApp): that app's uid on this phone, looked up once each. */
    private class AppUids {
        private val found = mutableMapOf<String, Int?>()
        private fun uid(app: String) = found.getOrPut(app) { Device.uid(app) }

        /** [text] (/etc/passwd or /etc/group) with the apps' uids, without the lines of an app that is not there. */
        fun fill(text: String): String = text.lineSequence().filter { it.isNotEmpty() }.mapNotNull { line ->
            var missing = false
            val filled = APP.replace(line) { match -> uid(match.groupValues[1])?.toString() ?: run { missing = true; match.value } }
            filled.takeIf { !missing }
        }.joinToString("") { "$it\n" }

        /** A uid or gid as the profile has it: a number, or an app's ("@app:<package>@"); null when that app is not there. */
        fun number(text: String): Int? = APP.matchEntire(text)?.let { uid(it.groupValues[1]) } ?: text.toIntOrNull()
    }

    /** The android part of the profile in place, if there is one. */
    private fun current(data: File): JSONObject? =
        runCatching { JSONObject(File(data, "profile.json").readText()).optJSONObject("android") }.getOrNull()

    /** /etc/seraph/login, for [shell] (the profile's: its PATH and bash), or Android's sh when the config has no Nix packages. */
    private fun writeLogin(etc: File, home: File, shell: JSONObject?) {
        val dir = File(etc, "seraph").apply { mkdirs() }
        val login = File(dir, "login")
        val fresh = File(dir, ".login.new")
        fresh.writeText(
            LOGIN.replace("@PATH@", quote(path(shell?.string("path") ?: "/system/bin:/system/xbin")))
                .replace("@HOME@", quote(home.path))
                .replace("@SHELL@", quote(shell?.string("bash") ?: "/system/bin/sh")),
        )
        Os.chmod(fresh.path, "755".toInt(8))
        fresh.renameTo(login)
    }

    private fun quote(text: String) = "'" + text.replace("'", "'\\''") + "'"

    private val APP = Regex("@app:([A-Za-z0-9._]+)@")

    /**
     * The profile's PATH, then the rest of Android's own (the engine's, as Android starts programs: /product/bin,
     * /system/xbin, the APEXes, ...), where a root solution may keep su.
     */
    private fun path(profile: String): String {
        val dirs = profile.split(':')
        val android = ANDROID_PATH + (System.getenv("PATH") ?: "").split(':')
        return (dirs + android.filter { it.isNotEmpty() && it !in dirs }.distinct()).joinToString(":")
    }

    /** Android's own PATH, as its init sets it (a boot script's may be shorter). */
    private val ANDROID_PATH = listOf(
        "/product/bin", "/apex/com.android.runtime/bin", "/apex/com.android.art/bin", "/system_ext/bin", "/system/bin",
        "/system/xbin", "/odm/bin", "/vendor/bin", "/vendor/xbin",
    )

    /** What bash reads in a login shell: a prompt with the phone's name, and ~/.bashrc, as distributions have it. */
    private val PROFILE = """
        # Seraph's /etc/profile, for login shells in its view (written by the engine: Etc.kt).
        if [ -n "${'$'}BASH_VERSION" ] && [ -n "${'$'}PS1" ]; then
          PS1='\[\e[1;32m\]\u@@NAME@\[\e[0m\]:\[\e[1;34m\]\w\[\e[0m\]\${'$'} '
          [ -r "${'$'}HOME/.bashrc" ] && . "${'$'}HOME/.bashrc"
        fi
    """.trimIndent() + "\n"

    /**
     * The users' shell in the view: for a login (an ssh session, the terminal) a login shell in its home, else the
     * command it is given (`login -c 'ls'`, as sshd runs a command or SFTP).
     */
    private val LOGIN = """
        #!/system/bin/sh
        # The users' shell in Seraph's view of the phone, for ssh sessions, the app's terminal and seraph-shell: the
        # profile's (environment.systemPackages on PATH, then Android's commands). Written by the engine (Etc.kt).
        export PATH=@PATH@ SHELL=/etc/seraph/login
        # root's home; another user's is the one sshd gave it, from /etc/passwd.
        [ "${'$'}(/system/bin/id -u)" = 0 ] && export HOME=@HOME@
        export LANG="${'$'}{LANG:-C.UTF-8}" TERM="${'$'}{TERM:-xterm-256color}"
        # Android's time zone, as its offset from UTC (POSIX: "<+0300>-03:00"): glibc does not read Android's zones.
        if [ -z "${'$'}TZ" ]; then
          off=${'$'}(/system/bin/date +%z)
          case ${'$'}off in
            +[0-9][0-9][0-9][0-9]) export TZ="<${'$'}off>-${'$'}{off:1:2}:${'$'}{off:3:2}" ;;
            -[0-9][0-9][0-9][0-9]) export TZ="<${'$'}off>+${'$'}{off:1:2}:${'$'}{off:3:2}" ;;
          esac
        fi
        # environment.extraInit.
        [ -r /etc/seraph/environment ] && . /etc/seraph/environment
        [ ${'$'}# -gt 0 ] && exec @SHELL@ "${'$'}@"
        cd "${'$'}HOME" 2> /dev/null
        exec @SHELL@ -l
    """.trimIndent() + "\n"
}

internal fun JSONObject.string(key: String): String? = if (isNull(key)) null else optString(key).ifEmpty { null }

internal fun JSONObject.ints(key: String): List<Int> = optJSONArray(key)?.let { array -> (0 until array.length()).map { array.getInt(it) } } ?: emptyList()

internal fun JSONObject.strings(key: String): List<String> = optJSONArray(key)?.let { array -> (0 until array.length()).map { array.getString(it) } } ?: emptyList()
