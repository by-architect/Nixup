package dev.andronix.manager.engine

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Makes the apps on the phone match the profile's android.packages.fdroid, in nixos-rebuild's order: first the
 * plan and every check, and when one fails nothing changes; then the apps, downloaded one by one, each installed
 * as soon as it is downloaded and checked while the next one downloads.
 *
 * The apps it installed (not the ones that were on the phone before) are listed in fdroid/owned: those it removes
 * again, keeping their data, when they leave the config or the module is removed (restore.sh).
 */
class Apply(private val repo: FDroid, private val data: File) {
    private val ownedFile = File(data, "fdroid/owned")
    private val cache = File(data, "cache")

    private class Step(val app: FDroid.App, val version: FDroid.Version, val installed: Device.Installed?)

    private sealed class Ready {
        class Apk(val number: Int, val step: Step, val file: File) : Ready()
        class Failed(val error: String) : Ready()
        object Done : Ready()
    }

    fun run(profile: File, dryRun: Boolean): Int {
        // Two builds at once (the app and Magisk's Action button) would install over each other.
        val lock = RandomAccessFile(File(data, "build.lock"), "rw").channel.tryLock()
            ?: throw Stop("another build is running: wait for it, then build again")
        try {
            return apply(profile, dryRun)
        } finally {
            lock.release()
        }
    }

    private fun apply(profile: File, dryRun: Boolean): Int {
        val wanted = JSONObject(profile.readText()).getJSONObject("android")
            .optJSONObject("packages")?.optJSONArray("fdroid")?.strings() ?: emptyList()
        // The ones removed by hand since the last build are no longer the module's.
        val owned = readOwned().filter { Device.versionCode(it) != null }

        val steps = ArrayList<Step>()
        val upToDate = ArrayList<String>()
        val problems = ArrayList<String>()
        for (id in wanted) {
            val app = repo.find(id)
            if (app == null) {
                problems += "$id is not in F-Droid (its index from ${repo.date}; if the app is new there, build with an update of the index)"
                continue
            }
            val installed = Device.installed(id)
            when (val choice = choose(app, installed)) {
                is Choice.Problem -> problems += choice.text
                is Choice.Pick -> when {
                    installed == null -> steps += Step(app, choice.version, null)
                    installed.versionCode < choice.version.code -> steps += Step(app, choice.version, installed)
                    else -> upToDate += "$id ${installed.versionName}"
                }
            }
        }
        val removals = owned.filter { it !in wanted }
        val toInstall = steps.filter { it.installed == null }
        val toUpdate = steps.filter { it.installed != null }

        Out.line("F-Droid apps in the config: ${wanted.size}")
        if (toInstall.isNotEmpty()) {
            Out.line("To install (${toInstall.size}, ${Out.size(toInstall.sumOf { it.version.size })} to download):")
            toInstall.forEach { Out.line("  ${it.app.id}  ${it.version.name}  ${Out.size(it.version.size)}  (${it.app.name})") }
        }
        if (toUpdate.isNotEmpty()) {
            Out.line("To update (${toUpdate.size}, ${Out.size(toUpdate.sumOf { it.version.size })} to download):")
            toUpdate.forEach { Out.line("  ${it.app.id}  ${it.installed!!.versionName} -> ${it.version.name}  ${Out.size(it.version.size)}") }
        }
        if (removals.isNotEmpty()) {
            Out.line("To remove (${removals.size}, they left the config; their data is kept):")
            removals.forEach { Out.line("  $it") }
        }
        if (upToDate.isNotEmpty()) Out.line("Up to date (${upToDate.size}): ${upToDate.joinToString(", ")}")
        if (problems.isNotEmpty()) {
            Out.line("✘ Cannot build:")
            problems.forEach { Out.line("  - $it") }
            return 2
        }
        if (steps.isEmpty() && removals.isEmpty()) {
            Out.line("Apps: nothing to change")
            if (!dryRun) writeResolved(wanted)
            return 0
        }

        // Each APK is deleted once it is installed, so at most two wait in the cache (one downloading, one installing);
        // an installed app takes about twice its APK's size (the APK and its compiled code).
        val download = steps.sumOf { it.version.size }
        val biggest = steps.maxOfOrNull { it.version.size } ?: 0
        val needed = download * 2 + biggest * 2 + RESERVE
        val free = Device.freeBytes(data)
        if (free < needed) {
            Out.line("✘ Not enough free space: about ${Out.size(needed)} is needed (${Out.size(download)} to download), ${Out.size(free)} is free")
            return 2
        }
        if (steps.isNotEmpty()) Out.line("Space: about ${Out.size(needed)} needed, ${Out.size(free)} free ✔")
        if (dryRun) return 0

        val status = fetchAndInstall(steps)
        if (status != 0) return status
        for (id in removals) {
            if (Device.uninstallKeepingData(id)) {
                writeOwned(readOwned() - id)
                Out.line("✔ Removed $id (its data is kept: installing it again brings it back)")
            } else {
                Out.line("✘ Could not remove $id")
                return 1
            }
        }
        writeResolved(wanted)
        return 0
    }

    private sealed class Choice {
        class Pick(val version: FDroid.Version) : Choice()
        class Problem(val text: String) : Choice()
    }

    /**
     * The version F-Droid itself would suggest: the newest stable one this phone can run (Android version, ABIs),
     * signed with the key of the app already installed, else with the app's preferred key.
     */
    private fun choose(app: FDroid.App, installed: Device.Installed?): Choice {
        val runs = app.versions.filter { v ->
            !v.beta && v.minSdk <= Device.sdk && (v.maxSdk == 0 || Device.sdk <= v.maxSdk) &&
                (v.abis.isEmpty() || v.abis.any { it in Device.abis })
        }
        if (runs.isEmpty()) {
            return Choice.Problem("no version of ${app.id} runs on this phone (Android API ${Device.sdk}, ${Device.abis.joinToString("/")})")
        }
        val signed = when {
            installed != null && installed.signers.isNotEmpty() -> runs.filter { v -> v.signers.any { it in installed.signers } }
            app.preferredSigner != null -> runs.filter { app.preferredSigner in it.signers }.ifEmpty { runs }
            else -> runs
        }
        if (signed.isEmpty()) {
            return Choice.Problem(
                "${app.id} is installed with another signing key than F-Droid's builds, so they cannot update it: " +
                    "uninstall it first (its data goes with it), or take it out of the config",
            )
        }
        return Choice.Pick(signed.maxBy { it.code })
    }

    /** Downloads one APK after another while the ones already downloaded are installed, in the config's order. */
    private fun fetchAndInstall(steps: List<Step>): Int {
        if (steps.isEmpty()) return 0
        cache.mkdirs()
        val ready = LinkedBlockingQueue<Ready>()
        val stop = AtomicBoolean(false)
        val total = steps.size
        val downloader = Thread {
            try {
                for ((i, step) in steps.withIndex()) {
                    if (stop.get()) return@Thread
                    val label = "[${i + 1}/$total]"
                    val file = File(cache, "${step.version.sha256}.apk")
                    if (!file.isFile) {
                        Out.line("$label Downloading ${step.app.id} ${step.version.name} (${Out.size(step.version.size)})")
                        Net.fetch(repo.address + step.version.file, file, step.version.size, step.version.sha256, Out.progress(label, step.version.size))
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

        var done = 0
        while (true) {
            when (val item = ready.take()) {
                is Ready.Apk -> {
                    val error = install(item, total)
                    item.file.delete()
                    if (error != null) {
                        stop.set(true)
                        Out.line("✘ $error")
                        return stoppedPartway(done, total)
                    }
                    done++
                }
                is Ready.Failed -> {
                    Out.line("✘ ${item.error}")
                    return stoppedPartway(done, total)
                }
                Ready.Done -> return 0
            }
        }
    }

    private fun stoppedPartway(done: Int, total: Int): Int {
        Out.line("Stopped after $done of $total apps; building again carries on from there (a broken-off download resumes).")
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

    private fun readOwned(): List<String> = if (ownedFile.isFile) ownedFile.readLines().filter { it.isNotBlank() } else emptyList()

    private fun writeOwned(ids: List<String>) {
        ownedFile.parentFile?.mkdirs()
        ownedFile.writeText(ids.distinct().joinToString("") { "$it\n" })
    }

    /** The versions the config's apps are at now, for the generation build.sh keeps. */
    private fun writeResolved(wanted: List<String>) {
        val apps = JSONArray()
        for (id in wanted) {
            val installed = Device.installed(id) ?: continue
            apps.put(JSONObject().put("id", id).put("versionCode", installed.versionCode).put("versionName", installed.versionName))
        }
        File(data, "fdroid/resolved.json").writeText(apps.toString())
    }

    private fun JSONArray.strings() = (0 until length()).map { getString(it) }

    companion object {
        /** Kept free besides the apps, so the phone itself never runs out of space. */
        const val RESERVE = 256L shl 20
    }
}
