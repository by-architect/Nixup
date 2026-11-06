package dev.nixup.manager.engine

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** What the engine says: build.sh passes it on to the app, Magisk's Action screen or adb, and keeps it in the log. */
object Out {
    @Synchronized
    fun line(text: String = "") {
        println(text)
        System.out.flush()
    }

    fun size(bytes: Long): String = when {
        bytes < 0 -> "size unknown"
        bytes >= 1L shl 30 -> String.format(Locale.ROOT, "%.1f GiB", bytes / 1073741824.0)
        bytes >= 1L shl 20 -> String.format(Locale.ROOT, "%.1f MiB", bytes / 1048576.0)
        else -> "${(bytes + 1023) / 1024} KiB"
    }

    fun date(millis: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(Date(millis))

    /** Says how far a download of [size] bytes is, at every quarter. */
    fun progress(label: String, size: Long): (Long) -> Unit {
        var next = 25
        return { done ->
            val percent = if (size > 0) (done * 100 / size).toInt() else 0
            while (percent >= next && next < 100) {
                line("$label   $next% of ${size(size)}")
                next += 25
            }
        }
    }
}

/** Stops the engine with a message: status 2 when nothing was changed yet, 1 when it stopped partway. */
class Stop(message: String, val status: Int = 2) : Exception(message)

/** A package name, like org.example.app: what the Nix config checks too. */
val PACKAGE_NAME = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
