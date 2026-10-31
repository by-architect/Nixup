package dev.andronix.manager.engine

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Installing apps from F-Droid repositories, for builds and updates alike: the space check, then the APKs downloaded
 * one by one, each installed as soon as it is downloaded and checked while the next one downloads, with notifications.
 *
 * The apps it installed (not the ones that were on the phone before), from any repository, are listed in owned: a
 * build removes them again, keeping their data, when they leave the config, and so does the module's removal (restore).
 */
class Installer(private val data: File) {
    private val ownedFile = File(data, "owned")
    private val cache = File(data, "cache")

    class Step(val repo: FDroid, val app: FDroid.App, val version: FDroid.Version, val installed: Device.Installed?)

    private sealed class Ready {
        class Apk(val number: Int, val step: Step, val file: File) : Ready()
        class Failed(val error: String) : Ready()
        object Done : Ready()
    }

    /** Whether there is room for the steps: each APK is deleted once installed, so at most two wait in the cache, and an installed app takes about twice its APK's size (the APK and its compiled code). */
    fun roomFor(steps: List<Step>): Boolean {
        if (steps.isEmpty()) return true
        val download = steps.sumOf { it.version.size }
        val needed = download * 2 + (steps.maxOfOrNull { it.version.size } ?: 0) * 2 + RESERVE
        val free = Device.freeBytes(data)
        if (free < needed) {
            Out.line("✘ Not enough free space: about ${Out.size(needed)} is needed (${Out.size(download)} to download), ${Out.size(free)} is free")
            return false
        }
        Out.line("Space: about ${Out.size(needed)} needed, ${Out.size(free)} free ✔")
        return true
    }

    /** Downloads one APK after another while the ones already downloaded are installed, in order; notifications say how it goes. */
    fun fetchAndInstall(steps: List<Step>): Int {
        if (steps.isEmpty()) return 0
        cache.mkdirs()
        val total = steps.size
        val installs = steps.count { it.installed == null }
        val title = when {
            installs == 0 -> "Updating ${apps(total)}"
            installs == total -> "Installing ${apps(total)}"
            else -> "Installing ${apps(installs)}, updating ${apps(total - installs)}"
        }
        val ready = LinkedBlockingQueue<Ready>()
        val stop = AtomicBoolean(false)
        val downloader = Thread {
            try {
                for ((i, step) in steps.withIndex()) {
                    if (stop.get()) return@Thread
                    val label = "[${i + 1}/$total]"
                    val file = File(cache, "${step.version.sha256}.apk")
                    if (!file.isFile) {
                        Out.line("$label Downloading ${step.app.id} ${step.version.name} (${Out.size(step.version.size)})")
                        Notify.progress(title, "Downloading ${step.app.name} ${step.version.name} (${Out.size(step.version.size)})", i, total)
                        Net.fetch(step.repo.address + step.version.file, file, step.version.size, step.version.sha256, Out.progress(label, step.version.size))
                    }
                    Out.line("$label ✔ Downloaded ${step.app.id}; its checksum matches the signed index")
                    ready.put(Ready.Apk(i + 1, step, file))
                }
                ready.put(Ready.Done)
            } catch (e: Exception) {
                ready.put(Ready.Failed(e.message ?: e.toString()))
            }
        }
        downloader.isDaemon = true
        downloader.start()

        val finished = ArrayList<String>()
        while (true) {
            when (val item = ready.take()) {
                is Ready.Apk -> {
                    Notify.progress(title, "Installing ${item.step.app.name} ${item.step.version.name}", item.number - 1, total)
                    val error = install(item, total)
                    item.file.delete()
                    if (error != null) {
                        stop.set(true)
                        Out.line("✘ $error")
                        return stoppedPartway(finished, total, error)
                    }
                    finished += "${item.step.app.name} ${item.step.version.name}"
                }
                is Ready.Failed -> {
                    Out.line("✘ ${item.error}")
                    return stoppedPartway(finished, total, item.error)
                }
                Ready.Done -> {
                    val verb = if (installs == 0) "Updated" else if (installs == total) "Installed" else "Installed and updated"
                    Notify.done("$verb ${apps(total)}", finished.joinToString(", "), failed = false)
                    return 0
                }
            }
        }
    }

    private fun stoppedPartway(finished: List<String>, total: Int, error: String): Int {
        Out.line("Stopped after ${finished.size} of $total apps; again carries on from there (a broken-off download resumes).")
        Notify.done("Stopped after ${finished.size} of ${apps(total)}", error, failed = true)
        return 1
    }

    private fun install(item: Ready.Apk, total: Int): String? {
        val step = item.step
        val label = "[${item.number}/$total]"
        Device.signers(item.file)?.let { signers ->
            if (signers.none { it in step.version.signers }) return "the downloaded ${step.app.id} is not signed with the key F-Droid lists for it"
        }
        val updater = Device.updater()
        Out.line("$label Installing ${step.app.id} ${step.version.name}" + (updater?.let { " (later updates by $it)" } ?: ""))
        Device.install(item.file, updater)?.let { return "pm could not install ${step.app.id}: $it" }
        val now = Device.versionCode(step.app.id)
        if (now != step.version.code) return "${step.app.id} is at version code $now after installing, not ${step.version.code}"
        if (step.installed == null) writeOwned(readOwned() + step.app.id)
        Out.line("$label ✔ ${if (step.installed == null) "Installed" else "Updated"} ${step.app.id} ${step.version.name}")
        return null
    }

    fun readOwned(): List<String> = if (ownedFile.isFile) ownedFile.readLines().filter { it.isNotBlank() } else emptyList()

    fun writeOwned(ids: List<String>) {
        ownedFile.parentFile?.mkdirs()
        ownedFile.writeText(ids.distinct().joinToString("") { "$it\n" })
    }

    fun forgetOwned() = ownedFile.delete()

    /** The versions the config's apps are at now, for the generation the scripts keep. */
    fun writeResolved(wanted: Collection<String>) {
        val apps = JSONArray()
        for (id in wanted) {
            val installed = Device.installed(id) ?: continue
            apps.put(JSONObject().put("id", id).put("versionCode", installed.versionCode).put("versionName", installed.versionName))
        }
        File(data, "resolved.json").writeText(apps.toString())
    }

    private fun apps(n: Int) = if (n == 1) "1 app" else "$n apps"

    companion object {
        /** Kept free besides the apps, so the phone itself never runs out of space. */
        const val RESERVE = 256L shl 20

        /** Runs [work] holding the build lock: two builds or updates at once would install over each other. */
        fun <T> locked(data: File, work: () -> T): T {
            val lock = RandomAccessFile(File(data, "build.lock"), "rw").channel.tryLock()
                ?: throw Stop("another build or update is running: wait for it, then try again")
            try {
                return work()
            } finally {
                lock.release()
            }
        }
    }
}
