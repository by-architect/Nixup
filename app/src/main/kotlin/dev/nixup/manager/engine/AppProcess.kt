package dev.nixup.manager.engine

import android.system.Os
import java.io.File

/**
 * The engine run as an app's own uid (with its group alone), for what Android lets only that app do: add the accounts
 * of its account types and change their passwords (Android checks the caller's uid and signing key, which root has
 * not; see Davx5). Busybox's setuidgid takes Android's name for the uid; the engine's APK is copied where that uid can
 * read it first (Nixup's own folder is root's alone). What it is to do comes on its input, so passwords stay off
 * command lines and files.
 */
object AppProcess {
    private val BUSYBOX = listOf("/data/adb/magisk/busybox", "/data/adb/ksu/bin/busybox", "/data/adb/ap/bin/busybox")

    /** Android's name for an app's uid of the phone's owner (u0_a123 for 10123), or null for any other uid. */
    fun user(uid: Int): String? = if (uid in 10000..19999) "u0_a${uid - 10000}" else null

    /**
     * Runs the engine's [command] (Main's) as [uid] with [input]; each line it writes goes to [line]. Returns its exit
     * status; throws IllegalStateException when it cannot be run at all.
     */
    fun run(uid: Int, command: List<String>, input: String, line: (String) -> Unit): Int {
        val user = user(uid) ?: throw IllegalStateException("uid $uid is not an app's of the phone's owner")
        val busybox = BUSYBOX.firstOrNull { File(it).canExecute() }
            ?: throw IllegalStateException("there is no busybox (Magisk's, KernelSU's or APatch's) to run it as the app with")
        val classpath = System.getenv("CLASSPATH")?.split(':')?.firstOrNull { File(it).isFile }
            ?: throw IllegalStateException("the engine's APK is not known here (CLASSPATH)")
        val copy = File("/data/local/tmp/nixup-app-${Os.getpid()}.apk")
        try {
            File(classpath).copyTo(copy, overwrite = true)
            Os.chmod(copy.path, "644".toInt(8))
            val process = ProcessBuilder(listOf(busybox, "setuidgid", user, "/system/bin/app_process", "/system/bin", "dev.nixup.manager.engine.Main") + command)
                .directory(File("/")).redirectErrorStream(true)
                .apply {
                    environment()["CLASSPATH"] = copy.path
                    // (Its status is not the build's.)
                    environment().remove("NIXUP_ENGINE_STATUS")
                }.start()
            process.outputStream.use { it.write(input.toByteArray()) }
            process.inputStream.bufferedReader().forEachLine(line)
            return process.waitFor()
        } finally {
            copy.delete()
        }
    }
}
