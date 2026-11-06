package dev.nixup.manager

import android.util.Base64
import java.io.IOException

/** Commands run as root through Magisk's su: the module's scripts. */
object Root {
    /** Picks the newest module folder ($d): the update waiting for a restart, else the running module. */
    const val MODULE = "d=/data/adb/modules_update/nixup; [ -f \$d/build.sh ] || d=/data/adb/modules/nixup; "

    /** The module's engine, from its copy of the app (what the module's scripts run), followed by its command. */
    const val ENGINE = MODULE + "CLASSPATH=\$d/app.apk /system/bin/app_process /system/bin dev.nixup.manager.engine.Main "

    /**
     * Runs [command] as root, passing each line it says to [line]; its exit status, or null when the phone has no su.
     * [input] is what it reads (a token, kept out of the command, which other processes can see).
     */
    fun run(command: String, input: String? = null, line: (String) -> Unit = {}): Int? = try {
        val process = ProcessBuilder("su", "-c", global(command)).redirectErrorStream(true).start()
        process.outputStream.use { if (input != null) it.write(input.toByteArray()) }
        process.inputStream.bufferedReader().forEachLine(line)
        process.waitFor()
    } catch (e: IOException) {
        null
    }

    /** What [command] writes, as it is (a file's bytes); null when the phone has no su, or it fails. */
    fun bytes(command: String): ByteArray? = try {
        val process = ProcessBuilder("su", "-c", global("$command 2>/dev/null")).start()
        process.outputStream.close()
        val out = process.inputStream.readBytes()
        if (process.waitFor() == 0) out else null
    } catch (e: IOException) {
        null
    }

    /**
     * [command], run in the whole phone's view (init's mount namespace), as adb's root shell runs: the root shell an
     * app starts sees the phone as the app does, and Android hides the other apps' data folders from an app (a backup
     * made there would miss them). Where nsenter cannot enter it, it runs as it is. The command goes in as base64,
     * which no quoting can break.
     */
    private fun global(command: String): String {
        val encoded = Base64.encodeToString(command.toByteArray(), Base64.NO_WRAP)
        return "c=\$(echo $encoded | base64 -d); " +
            "if [ \"\$(readlink /proc/1/ns/mnt)\" != \"\$(readlink /proc/self/ns/mnt)\" ]; then " +
            "for n in '/data/adb/magisk/busybox nsenter' '/data/adb/ksu/bin/busybox nsenter' '/data/adb/ap/bin/busybox nsenter' /system/bin/nsenter; do " +
            "\$n -t 1 -m -- true 2>/dev/null && exec \$n -t 1 -m -- /system/bin/sh -c \"\$c\"; done; fi; " +
            "exec /system/bin/sh -c \"\$c\""
    }
}
