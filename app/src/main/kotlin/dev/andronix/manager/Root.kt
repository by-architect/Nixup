package dev.andronix.manager

import java.io.IOException

/** Commands run as root through Magisk's su: the module's scripts. */
object Root {
    /** Picks the newest module folder ($d): the update waiting for a restart, else the running module. */
    const val MODULE = "d=/data/adb/modules_update/andronix; [ -f \$d/build.sh ] || d=/data/adb/modules/andronix; "

    /** Runs [command] as root, passing each line it says to [line]; its exit status, or null when the phone has no su. */
    fun run(command: String, line: (String) -> Unit = {}): Int? = try {
        val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        process.outputStream.close()
        process.inputStream.bufferedReader().forEachLine(line)
        process.waitFor()
    } catch (e: IOException) {
        null
    }
}
