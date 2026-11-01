package dev.andronix.manager.engine

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Installing apps, from F-Droid repositories and from releases, for builds and updates alike: the space check, then
 * the APKs downloaded one by one, each installed as soon as it is downloaded and checked while the next one
 * downloads, with notifications.
 *
 * The apps it installed (not the ones that were on the phone before), from anywhere, are listed in owned: a build
 * removes them again, keeping their data, when they leave the config, and so does the module's removal (restore).
 */
class Installer(private val data: File) {
    private val ownedFile = File(data, "owned")
    private val cache = File(data, "cache")

    /**
     * One app to install or update, and what its APK must be. An F-Droid index tells everything beforehand; a release
     * tells where its APK is (and on GitHub its checksum), so its version code ([code], when known from before) is
     * read from the APK once it is here ([fromRelease]): one that is not newer than the app installed is left, and
     * [learn] remembers its version code.
     */
    class Step(
        /** Where it comes from: a repository's name, or obtainium. */
        val repo: String,
        val id: String,
        /** The app's name, for messages and notifications. */
        val name: String,
        /** The version, for messages: F-Droid's version name, or the release's tag. */
        val version: String,
        val code: Long?,
        /** The APK's size, below 0 when it is not known. */
        val size: Long,
        val url: String,
        val sha256: String?,
        /** Where the checksum comes from, for messages. */
        val listedBy: String,
        /** The keys the APK may be signed with; null for any (Android itself still holds an update to the installed app's key). */
        val keys: Set<String>?,
        /** Where those keys come from, for messages ("the key …"), with what to do when the APK has another. */
        val keysFrom: String,
        val installed: Device.Installed?,
        /** The apps that can update it later, the first one on the phone becoming its installer of record. */
        val installers: List<String>,
        val fromRelease: Boolean = false,
        val learn: (Long) -> Unit = {},
    )

    private sealed class Ready {
        class Apk(val number: Int, val step: Step, val file: File) : Ready()
        class Failed(val error: String) : Ready()
        object Done : Ready()
    }

    private sealed class Outcome {
        object Installed : Outcome()
        /** A release that turned out not to be newer than the app installed. */
        object Left : Outcome()
        class Failed(val error: String) : Outcome()
    }

    /** Whether there is room for the steps: each APK is deleted once installed, so at most two wait in the cache, and an installed app takes about twice its APK's size (the APK and its compiled code). */
    fun roomFor(steps: List<Step>): Boolean {
        if (steps.isEmpty()) return true
        val download = steps.sumOf { it.room }
        val needed = download * 2 + (steps.maxOfOrNull { it.room } ?: 0) * 2 + RESERVE
        val free = Device.freeBytes(data)
        val unknown = steps.count { it.size < 0 }
        val guess = if (unknown > 0) "${apps(unknown)} of unknown size counted as ${Out.size(UNKNOWN_SIZE)} each" else null
        if (free < needed) {
            Out.line("✘ Not enough free space: about ${Out.size(needed)} is needed (${Out.size(download)} to download${guess?.let { "; $it" } ?: ""}), ${Out.size(free)} is free")
            return false
        }
        Out.line("Space: about ${Out.size(needed)} needed, ${Out.size(free)} free ✔" + (guess?.let { " ($it)" } ?: ""))
        return true
    }

    private val Step.room: Long get() = if (size >= 0) size else UNKNOWN_SIZE

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
                    // An APK checked against its checksum before can wait in the cache; one without a checksum is fetched again.
                    val file = File(cache, "${step.sha256 ?: Net.sha256(step.url.toByteArray())}.apk")
                    if (step.sha256 == null) file.delete()
                    if (!file.isFile) {
                        Out.line("$label Downloading ${step.id} ${step.version} (${Out.size(step.size)})")
                        Notify.progress(title, "Downloading ${step.name} ${step.version} (${Out.size(step.size)})", i, total)
                        Net.fetch(step.url, file, step.size, step.sha256, step.listedBy, Out.progress(label, step.size))
                    }
                    Out.line(
                        "$label ✔ Downloaded ${step.id}; " +
                            if (step.sha256 != null) "its checksum matches ${step.listedBy}" else "nothing lists its checksum: its package and key are checked",
                    )
                    ready.put(Ready.Apk(i + 1, step, file))
                }
                ready.put(Ready.Done)
            } catch (e: Exception) {
                ready.put(Ready.Failed(e.message ?: e.toString()))
            }
        }
        downloader.isDaemon = true
        downloader.start()

        val installed = ArrayList<String>()
        val updated = ArrayList<String>()
        while (true) {
            when (val item = ready.take()) {
                is Ready.Apk -> {
                    Notify.progress(title, "Installing ${item.step.name} ${item.step.version}", item.number - 1, total)
                    val outcome = install(item, total)
                    item.file.delete()
                    when (outcome) {
                        is Outcome.Failed -> {
                            stop.set(true)
                            Out.line("✘ ${outcome.error}")
                            return stoppedPartway(installed.size + updated.size, total, outcome.error)
                        }
                        Outcome.Installed -> (if (item.step.installed == null) installed else updated) += "${item.step.name} ${item.step.version}"
                        Outcome.Left -> {}
                    }
                }
                is Ready.Failed -> {
                    Out.line("✘ ${item.error}")
                    return stoppedPartway(installed.size + updated.size, total, item.error)
                }
                Ready.Done -> {
                    val result = when {
                        installed.isEmpty() && updated.isEmpty() -> "No newer versions"
                        installed.isEmpty() -> "Updated ${apps(updated.size)}"
                        updated.isEmpty() -> "Installed ${apps(installed.size)}"
                        else -> "Installed ${apps(installed.size)}, updated ${apps(updated.size)}"
                    }
                    val text = (installed + updated).joinToString(", ").ifEmpty { "The new releases were not newer than the apps installed" }
                    Notify.done(result, text, failed = false)
                    return 0
                }
            }
        }
    }

    private fun stoppedPartway(finished: Int, total: Int, error: String): Int {
        Out.line("Stopped after $finished of $total apps; again carries on from there (a broken-off download resumes).")
        Notify.done("Stopped after $finished of ${apps(total)}", error, failed = true)
        return 1
    }

    private fun install(item: Ready.Apk, total: Int): Outcome {
        val step = item.step
        val label = "[${item.number}/$total]"
        val apk = Device.archive(item.file)
        if (apk == null) {
            // An F-Droid APK is known from the signed index (pm still holds an update to the installed app's key); a release's is not.
            if (step.fromRelease) return Outcome.Failed("cannot read the downloaded APK of ${step.id} here, so it cannot be checked: not installed")
        } else {
            if (apk.id != step.id) return Outcome.Failed("the APK from ${step.url} is ${apk.id}, not ${step.id}: fix the id or the address in the config")
            if (step.keys != null && apk.signers.none { it in step.keys }) {
                return Outcome.Failed("the downloaded ${step.id} is signed with ${apk.signers.joinToString(", ")}, not with the key ${step.keysFrom}")
            }
            if (step.fromRelease) {
                step.learn(apk.versionCode)
                val now = step.installed
                if (now != null && apk.versionCode <= now.versionCode) {
                    Out.line("$label ${step.id}: the APK of ${step.version} is version ${apk.versionName} (code ${apk.versionCode}), not newer than the one installed (${now.versionName}, code ${now.versionCode}): left as it is")
                    return Outcome.Left
                }
                if (step.keys == null) {
                    Out.line("$label ${step.id} is signed with ${apk.signers.joinToString(", ")}; signer = \"…\" in the config refuses any other key")
                }
            }
        }
        val updater = Device.firstInstalled(step.installers)
        Out.line("$label Installing ${step.id} ${step.version}" + (updater?.let { " (later updates by $it)" } ?: ""))
        Device.install(item.file, updater)?.let { return Outcome.Failed("pm could not install ${step.id}: $it") }
        val wanted = apk?.versionCode ?: step.code ?: return Outcome.Failed("the version code of ${step.id} is not known")
        val now = Device.versionCode(step.id)
        if (now != wanted) return Outcome.Failed("${step.id} is at version code $now after installing, not $wanted")
        if (step.installed == null) writeOwned(readOwned() + step.id)
        Out.line("$label ✔ ${if (step.installed == null) "Installed" else "Updated"} ${step.id} ${step.version}")
        return Outcome.Installed
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

        /** What an APK of unknown size (a GitLab release's) counts as in the space check. */
        const val UNKNOWN_SIZE = 100L shl 20

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
