package dev.nixup.manager.engine

import android.system.Os
import org.json.JSONArray
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/**
 * systemd.tmpfiles (tmpfiles.d(5), nix/tmpfiles.nix): the folders, files and links the services need, made by each build
 * and each start of the phone before the units start, as systemd-tmpfiles makes them at boot. In Nixup's view only: its
 * /var, /run, /etc and /home ([Etc.outside]); /tmp is each unit's own, so a rule for it is left (the build refused any
 * other). A rule's age (cleaning old files) is left be, and so are attributes, ACLs and devices, which a service's
 * folders do not need on a phone.
 */
object Tmpfiles {
    /** Carries [rules] out (a profile's "tmpfiles"); how many could not be. */
    fun apply(data: File, rules: JSONArray?): Int {
        if (rules == null || rules.length() == 0) return 0
        val accounts = Etc.accounts(data)
        var failed = 0
        for (i in 0 until rules.length()) {
            val line = rules.getString(i)
            runCatching { rule(data, accounts, line) }.onFailure {
                // A rule marked "-" may fail.
                if (!line.trimStart().substringBefore(' ').contains('-')) {
                    failed++
                    Out.line("✘ systemd.tmpfiles: $line: ${it.message}")
                }
            }
        }
        return failed
    }

    private fun rule(data: File, accounts: Etc.Accounts, line: String) {
        val fields = split(line)
        if (fields.size < 2) return
        val type = fields[0]
        val path = specifiers(fields[1])
        val letter = type[0]
        val plus = type.contains('+')
        // Only folders of Nixup's own (the build refused others); /tmp is each unit's own.
        if (path == "/tmp" || path.startsWith("/tmp/")) return
        if (listOf("/var", "/run", "/etc", "/home").none { path == it || path.startsWith("$it/") }) return
        val file = Etc.outside(data, path)
        val mode = fields.getOrNull(2)?.takeIf { it != "-" }?.trimStart('~', ':')?.toIntOrNull(8)
        val uid = fields.getOrNull(3)?.takeIf { it != "-" }?.trimStart(':')?.let { accounts.users[it]?.uid ?: it.toIntOrNull() ?: throw IllegalArgumentException("no user $it in Nixup's /etc/passwd") }
        val gid = fields.getOrNull(4)?.takeIf { it != "-" }?.trimStart(':')?.let { accounts.groups[it] ?: it.toIntOrNull() ?: throw IllegalArgumentException("no group $it in Nixup's /etc/group") }
        val argument = fields.getOrNull(6)?.let { if (it == "-") null else it }
        val p = file.toPath()
        when (letter) {
            'd', 'D', 'v', 'q', 'Q' -> {
                file.mkdirs()
                own(p, mode ?: "755".toInt(8), uid, gid)
            }
            'e' -> if (Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) own(p, mode, uid, gid)
            'f', 'F' -> {
                file.parentFile?.mkdirs()
                if (letter == 'F' || plus || !exists(p)) file.writeText(argument?.let { unescape(it) } ?: "")
                own(p, mode ?: "644".toInt(8), uid, gid)
            }
            'w' -> if (exists(p)) {
                if (plus) file.appendText(unescape(argument ?: "")) else file.writeText(unescape(argument ?: ""))
            }
            'L' -> {
                file.parentFile?.mkdirs()
                if (plus && (exists(p) || Files.isSymbolicLink(p))) remove(p)
                if (!exists(p) && !Files.isSymbolicLink(p)) Os.symlink(argument ?: throw IllegalArgumentException("a link needs its target"), file.path)
                if (uid != null || gid != null) Os.lchown(file.path, uid ?: 0, gid ?: 0)
            }
            'z' -> if (exists(p)) own(p, mode, uid, gid)
            'Z' -> if (exists(p)) Files.walk(p).use { all -> all.forEach { own(it, mode, uid, gid) } }
            'r' -> if (exists(p) || Files.isSymbolicLink(p)) {
                if (!Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS) || (file.list()?.isEmpty() == true)) Files.delete(p)
            }
            'R' -> if (exists(p) || Files.isSymbolicLink(p)) remove(p)
            'C' -> if (!exists(p) && argument != null) {
                val from = Etc.outside(data, argument)
                if (from.isDirectory) from.copyRecursively(file) else { file.parentFile?.mkdirs(); from.copyTo(file) }
                own(p, mode, uid, gid)
            }
            'p' -> if (!exists(p)) {
                file.parentFile?.mkdirs()
                Os.mkfifo(file.path, mode ?: "644".toInt(8))
                own(p, null, uid, gid)
            }
            // Exclusions from cleaning, attributes, ACLs and devices: nothing to do on the phone.
            else -> {}
        }
    }

    private fun exists(p: Path) = Files.exists(p, LinkOption.NOFOLLOW_LINKS)

    /** Its mode and owner, when given (a link's owner only). */
    private fun own(p: Path, mode: Int?, uid: Int?, gid: Int?) {
        val path = p.toString()
        if (Files.isSymbolicLink(p)) {
            if (uid != null || gid != null) Os.lchown(path, uid ?: Os.lstat(path).st_uid, gid ?: Os.lstat(path).st_gid)
            return
        }
        if (mode != null) Os.chmod(path, mode)
        if (uid != null || gid != null) {
            val stat = Os.stat(path)
            Os.chown(path, uid ?: stat.st_uid, gid ?: stat.st_gid)
        }
    }

    /** Removes [p] and what is in it, a link without what it leads to. */
    private fun remove(p: Path) {
        if (Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) Files.list(p).use { all -> all.forEach { remove(it) } }
        Files.deleteIfExists(p)
    }

    /** The words of a rule: quoted ones whole, and its argument (the 7th) the rest of the line. */
    private fun split(line: String): List<String> {
        val words = mutableListOf<String>()
        var i = 0
        val s = line.trim()
        while (i < s.length && words.size < 6) {
            while (i < s.length && s[i].isWhitespace()) i++
            if (i >= s.length) break
            val quote = s[i].takeIf { it == '"' || it == '\'' }
            val word = StringBuilder()
            if (quote != null) {
                i++
                while (i < s.length && s[i] != quote) word.append(s[i++])
                i++
            } else {
                while (i < s.length && !s[i].isWhitespace()) word.append(s[i++])
            }
            words += word.toString()
        }
        while (i < s.length && s[i].isWhitespace()) i++
        if (i < s.length) words += s.substring(i)
        return words
    }

    /** systemd's specifiers a path may have, as the view has them. */
    private fun specifiers(path: String): String = Regex("%(.)").replace(path) { match ->
        when (match.groupValues[1]) {
            "t" -> "/run"
            "S" -> "/var/lib"
            "C" -> "/var/cache"
            "L" -> "/var/log"
            "T", "V" -> "/tmp"
            "E" -> "/etc"
            "%" -> "%"
            else -> match.value
        }
    }

    /** An argument's C escapes (\n, \t, \\, \xNN). */
    private fun unescape(text: String): String = Regex("""\\(x[0-9a-fA-F]{2}|.)""").replace(text) { match ->
        val code = match.groupValues[1]
        when {
            code.startsWith("x") && code.length == 3 -> code.drop(1).toInt(16).toChar().toString()
            code == "n" -> "\n"
            code == "t" -> "\t"
            else -> code
        }
    }
}
