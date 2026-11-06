package dev.nixup.manager.engine

import android.system.Os
import org.json.JSONArray
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.LocalDate

/**
 * The view's /etc/shadow, /etc/subuid and /etc/subgid (nix/users.nix), as NixOS's update-users-groups.pl writes them
 * with users.mutableUsers false: each user's password and the day it expires, and its subordinate ids.
 */
object Shadow {
    /**
     * /etc/shadow for the users of [passwd] (the view's /etc/passwd, written already), with the passwords of [specs] (a
     * profile's users.shadow: name, and file, password, hash, expires as it has them). A user it does not name has
     * none: "*", which takes no password (NixOS's "!" would make sshd, which runs without PAM, refuse its keys too).
     * Root alone may read it (sshd reads it as root).
     */
    fun write(data: File, etc: File, passwd: String, specs: JSONArray?) {
        val byName = (0 until (specs?.length() ?: 0)).map { specs!!.getJSONObject(it) }.associateBy { it.getString("name") }
        val before = runCatching { File(etc, "shadow").readLines() }.getOrDefault(emptyList())
            .mapNotNull { line -> line.split(':').takeIf { it.size >= 2 }?.let { it[0] to it[1] } }.toMap()
        val lines = passwd.lineSequence().filter { it.isNotEmpty() }.mapNotNull { it.split(':').firstOrNull() }.distinct().map { name ->
            val spec = byName[name]
            val hash = spec?.let { password(data, name, it, before[name]) } ?: "*"
            val expires = spec?.string("expires")?.let { day -> runCatching { LocalDate.parse(day).toEpochDay().toString() }.getOrNull() }.orEmpty()
            // As NixOS writes it: the last change on day 1 (0 would ask for a new password at once), no ageing.
            "$name:$hash:1:::::$expires:"
        }.joinToString("") { "$it\n" }
        val file = File(etc, "shadow")
        if (runCatching { file.readText() }.getOrNull() == lines) return
        // Whole or not at all, and never readable by others, not even for a moment.
        val next = File(etc, ".shadow.new")
        next.delete()
        next.writeText("")
        Os.chmod(next.path, "600".toInt(8))
        next.writeText(lines)
        if (!next.renameTo(file)) next.delete()
    }

    /**
     * The hash of user [name] from [spec], as NixOS takes it: its file's line when the file is there, else its password
     * (hashed here; one that [before], its hash so far, already is stays as it was), else its hash; null: none.
     */
    private fun password(data: File, name: String, spec: org.json.JSONObject, before: String?): String? {
        spec.string("file")?.let { path ->
            // (A path in the view, /etc/..., /var/..., is where the view has it.)
            val line = runCatching { Etc.outside(data, path).readLines().firstOrNull()?.trim() }.getOrNull()
            if (line != null && ':' !in line) return line
            Out.line("! users.users.$name.hashedPasswordFile: $path is not on the phone (or not one line): it keeps the other options' password")
        }
        if (spec.has("password") && !spec.isNull("password")) {
            val password = spec.getString("password")
            if (before != null && matches(password, before)) return before
            return sha512Crypt(password, salt())
        }
        return if (spec.has("hash") && !spec.isNull("hash")) spec.getString("hash") else null
    }

    /**
     * /etc/subuid and /etc/subgid from [specs] (a profile's users.subIds: name, uids and gids as ranges, and auto):
     * a user with auto gets 65536 of its own from 100000 on, given once and kept in subids.json, as NixOS keeps
     * /var/lib/nixos/auto-subuid-map.
     */
    fun writeSubIds(data: File, etc: File, specs: JSONArray?) {
        val users = (0 until (specs?.length() ?: 0)).map { specs!!.getJSONObject(it) }
        val keptFile = File(data, "subids.json")
        val kept = runCatching { org.json.JSONObject(keptFile.readText()) }.getOrDefault(org.json.JSONObject())
        var changed = false
        val uids = StringBuilder()
        val gids = StringBuilder()
        for (user in users) {
            val name = user.getString("name")
            fun ranges(key: String, out: StringBuilder) {
                val list = user.optJSONArray(key) ?: return
                for (i in 0 until list.length()) list.getJSONObject(i).let { out.append("$name:${it.getLong("start")}:${it.getLong("count")}\n") }
            }
            ranges("uids", uids)
            ranges("gids", gids)
            if (user.optBoolean("auto")) {
                val start = if (kept.has(name)) kept.getLong(name) else {
                    val taken = kept.keys().asSequence().map { kept.getLong(it) }.toSet()
                    generateSequence(100000L) { it + 65536 }.first { it !in taken }.also { kept.put(name, it); changed = true }
                }
                uids.append("$name:$start:65536\n")
                gids.append("$name:$start:65536\n")
            }
        }
        if (changed) runCatching { keptFile.writeText(kept.toString(2)) }
        for ((file, text) in listOf(File(etc, "subuid") to uids.toString(), File(etc, "subgid") to gids.toString())) {
            if (text.isEmpty()) file.delete() else if (runCatching { file.readText() }.getOrNull() != text) file.writeText(text)
        }
    }

    /** Whether [hash] (a "$6$" one) is [password]'s, made with its own salt and rounds. */
    private fun matches(password: String, hash: String): Boolean {
        val parts = hash.split('$')
        if (parts.size < 4 || parts[1] != "6") return false
        val rounds = parts[2].takeIf { it.startsWith("rounds=") }?.removePrefix("rounds=")?.toIntOrNull()
        val salt = if (rounds != null) parts[3] else parts[2]
        return sha512Crypt(password, salt, rounds) == hash
    }

    private val ITOA64 = "./0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"

    private fun salt(): String {
        val random = SecureRandom()
        return (1..16).map { ITOA64[random.nextInt(64)] }.joinToString("")
    }

    /**
     * crypt(3)'s SHA-512 ("$6$"), as glibc makes it (Ulrich Drepper's specification): what `mkpasswd -m sha-512` gives
     * for [password] with [salt] (at most 16 characters), and [rounds] when they are given ("rounds=" in the hash).
     */
    fun sha512Crypt(password: String, salt: String, rounds: Int? = null): String {
        val key = password.toByteArray(Charsets.UTF_8)
        val saltBytes = salt.take(16).toByteArray(Charsets.UTF_8)
        val count = (rounds ?: 5000).coerceIn(1000, 999_999_999)
        fun sha() = MessageDigest.getInstance("SHA-512")

        val b = sha().run { update(key); update(saltBytes); update(key); digest() }
        val a = sha().run {
            update(key); update(saltBytes)
            var n = key.size
            while (n > 64) { update(b, 0, 64); n -= 64 }
            update(b, 0, n)
            n = key.size
            while (n > 0) { if (n and 1 != 0) update(b) else update(key); n = n shr 1 }
            digest()
        }
        val dp = sha().run { repeat(key.size) { update(key) }; digest() }
        val p = ByteArray(key.size) { dp[it % 64] }
        val ds = sha().run { repeat(16 + (a[0].toInt() and 0xff)) { update(saltBytes) }; digest() }
        val s = ByteArray(saltBytes.size) { ds[it % 64] }

        var c = a
        for (i in 0 until count) {
            c = sha().run {
                if (i and 1 != 0) update(p) else update(c)
                if (i % 3 != 0) update(s)
                if (i % 7 != 0) update(p)
                if (i and 1 != 0) update(c) else update(p)
                digest()
            }
        }

        val out = StringBuilder("$6$")
        if (rounds != null) out.append("rounds=$count$")
        out.append(String(saltBytes, Charsets.UTF_8)).append('$')
        fun b64(b2: Int, b1: Int, b0: Int, n: Int) {
            var w = ((b2 and 0xff) shl 16) or ((b1 and 0xff) shl 8) or (b0 and 0xff)
            repeat(n) { out.append(ITOA64[w and 0x3f]); w = w shr 6 }
        }
        val order = intArrayOf(
            0, 21, 42, 22, 43, 1, 44, 2, 23, 3, 24, 45, 25, 46, 4, 47, 5, 26, 6, 27, 48, 28, 49, 7, 50, 8, 29, 9, 30, 51,
            31, 52, 10, 53, 11, 32, 12, 33, 54, 34, 55, 13, 56, 14, 35, 15, 36, 57, 37, 58, 16, 59, 17, 38, 18, 39, 60,
            40, 61, 19, 62, 20, 41,
        )
        for (i in order.indices step 3) b64(c[order[i]].toInt(), c[order[i + 1]].toInt(), c[order[i + 2]].toInt(), 4)
        b64(0, 0, c[63].toInt(), 2)
        return out.toString()
    }
}
