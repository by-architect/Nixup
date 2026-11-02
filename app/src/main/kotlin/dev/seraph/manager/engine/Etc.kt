package dev.seraph.manager.engine

import org.json.JSONObject
import java.io.File

/**
 * Seraph's /etc for the Linux programs it runs (Nix, and the services' programs from Nix packages), which look there
 * for DNS servers, trusted certificates, hosts and users, where Android has none: the DNS servers of the networks in
 * use, Android's trusted certificates, localhost and a root user. They run in a mount namespace of their own with it
 * over /etc (see Units).
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
        File(etc, "hosts").writeText("127.0.0.1 localhost\n::1 localhost ip6-localhost\n")
        val dns = Device.dnsServers()
        if (dns.isNotEmpty()) File(etc, "resolv.conf").writeText(dns.joinToString("") { "nameserver $it\n" })
        return etc
    }
}

internal fun JSONObject.string(key: String): String? = if (isNull(key)) null else optString(key).ifEmpty { null }

internal fun JSONObject.strings(key: String): List<String> = optJSONArray(key)?.let { array -> (0 until array.length()).map { array.getString(it) } } ?: emptyList()
