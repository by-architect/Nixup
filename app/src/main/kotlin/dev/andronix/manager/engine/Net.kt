package dev.andronix.manager.engine

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

object Net {
    /**
     * Downloads [url] into [to], picking up a download that broke off (from its .part file), and checks its size
     * and SHA-256 before it takes [to]'s name: a file there has always been checked.
     */
    fun fetch(url: String, to: File, size: Long, sha256: String, progress: (Long) -> Unit = {}) {
        val part = File(to.path + ".part")
        var tries = 0
        while (true) {
            try {
                download(url, part, size, progress)
                break
            } catch (e: IOException) {
                if (++tries >= 3) throw IOException("cannot download $url (${e.message})", e)
                Out.line("    retrying: ${e.message}")
                Thread.sleep(3000L * tries)
            }
        }
        if (part.length() != size) {
            part.delete()
            throw IOException("$url came with ${part.length()} bytes, not $size")
        }
        if (sha256(part) != sha256) {
            part.delete()
            throw SecurityException("$url does not match the checksum in F-Droid's signed index")
        }
        if (!part.renameTo(to)) throw IOException("cannot rename ${part.path}")
    }

    private fun download(url: String, part: File, size: Long, progress: (Long) -> Unit) {
        var have = if (part.isFile) part.length() else 0L
        if (have > size) {
            part.delete()
            have = 0
        }
        if (have == size) return
        val connection = open(url)
        if (have > 0) connection.setRequestProperty("Range", "bytes=$have-")
        try {
            when (connection.responseCode) {
                HttpURLConnection.HTTP_PARTIAL -> {}
                HttpURLConnection.HTTP_OK -> have = 0
                else -> throw IOException("HTTP ${connection.responseCode}")
            }
            FileOutputStream(part, have > 0).use { out ->
                connection.inputStream.use { input ->
                    val buffer = ByteArray(1 shl 16)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        have += n
                        progress(have)
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    /** A small file whose checksum is not known beforehand (it is checked some other way), at most [limit] bytes. */
    fun get(url: String, limit: Int = 1 shl 20): ByteArray {
        val connection = open(url)
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) throw IOException("HTTP ${connection.responseCode} for $url")
            val bytes = connection.inputStream.use { it.readNBytesUpTo(limit + 1) }
            if (bytes.size > limit) throw IOException("$url is bigger than $limit bytes")
            return bytes
        } finally {
            connection.disconnect()
        }
    }

    private fun open(url: String) = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 20_000
        readTimeout = 60_000
        setRequestProperty("User-Agent", "Andronix")
    }

    private fun InputStream.readNBytesUpTo(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(1 shl 14)
        while (out.size() < limit) {
            val n = read(buffer, 0, minOf(buffer.size, limit - out.size()))
            if (n < 0) break
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    fun sha256(file: File): String = file.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(1 shl 16)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            digest.update(buffer, 0, n)
        }
        hex(digest.digest())
    }

    fun sha256(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
}
