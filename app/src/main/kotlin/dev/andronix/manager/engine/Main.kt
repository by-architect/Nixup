package dev.andronix.manager.engine

import org.json.JSONObject
import java.io.File
import kotlin.system.exitProcess

/**
 * The module's engine, run as root by its scripts through app_process, from the app's APK:
 *   CLASSPATH=app.apk app_process /system/bin dev.andronix.manager.engine.Main <command>
 * Commands:
 *   index [--force] <file>        brings the indexes of the repositories in <file> (android.repos) up to date
 *                                 (once a day, or now with --force), and when it is a profile, the releases of its
 *                                 apps from releases (android.packages.obtainium) too
 *   apply <profile> [--dry-run] [--force]
 *                                 makes the apps and their settings match the profile (with --dry-run: only plans and
 *                                 checks; with --force, the releases are asked for again even when new enough)
 *   restore                       gives back every app setting the module changed and removes the apps it installed
 *   updates                       lists the updates of the config's apps (and writes updates.json for the app)
 *   update [--auto] [apps...]     installs them, all or the ones named (with --auto: only for the repositories whose
 *                                 android.settings.<repo>.autoupdatePackages is true)
 * Exit status: 0 done, 2 stopped before changing anything, 1 stopped partway.
 */
object Main {
    private val data = File("/data/adb/andronix")

    @JvmStatic
    fun main(args: Array<String>) {
        val status = try {
            moveOldFiles()
            val files = args.drop(1).filter { !it.startsWith("--") }
            when (args.firstOrNull()) {
                "index" -> index(File(files.firstOrNull() ?: usage()), force = "--force" in args)
                "apply" -> {
                    val profile = File(files.firstOrNull() ?: usage())
                    Apply(FDroid.of(JSONObject(profile.readText()), data), data).run(profile, dryRun = "--dry-run" in args, force = "--force" in args)
                }
                "restore" -> Apply(emptyMap(), data).restore()
                "updates" -> Updates(data).list().let { 0 }
                "update" -> Updates(data).update(files, auto = "--auto" in args)
                else -> usage()
            }
        } catch (e: Stop) {
            Out.line("✘ ${e.message}")
            e.status
        } catch (e: SecurityException) {
            Out.line("✘ ${e.message}")
            2
        } catch (e: Exception) {
            Out.line("✘ ${e.javaClass.simpleName}: ${e.message}")
            1
        }
        Notify.flush()
        System.out.flush()
        // The package manager's system context leaves threads behind that would keep the process alive.
        exitProcess(status)
    }

    /**
     * Each repository's index, one after another, then the releases of the apps from releases (a profile has them;
     * the repositories a build evaluates first do not); one that cannot be had does not keep the others from coming.
     */
    private fun index(file: File, force: Boolean): Int {
        val json = JSONObject(file.readText())
        var failed = 0
        for (repo in FDroid.of(json, data).values) {
            try {
                repo.refresh(force)
            } catch (e: Exception) {
                Out.line("✘ ${repo.name}: ${e.message}")
                failed++
            }
        }
        val releases = Releases(data)
        for (app in json.optJSONObject("android")?.let { Releases.apps(it) } ?: emptyList()) {
            try {
                releases.refresh(app, force)
            } catch (e: Exception) {
                Out.line("✘ ${Releases.NAME}: ${app.id}: ${e.message}")
                failed++
            }
        }
        return if (failed == 0) 0 else 1
    }

    /**
     * The files of a module from before custom repositories: f-droid.org's index in fdroid/ goes to repos/fdroid/
     * (marked as fetched from f-droid.org with its key), and the list of apps the module installed and their
     * versions are kept for all repositories, next to it.
     */
    private fun moveOldFiles() {
        val old = File(data, "fdroid")
        if (!old.isDirectory) return
        File(old, "owned").takeIf { it.isFile }?.renameTo(File(data, "owned"))
        File(old, "resolved.json").takeIf { it.isFile }?.renameTo(File(data, "resolved.json"))
        File(old, "state.json").takeIf { it.isFile }?.let { state ->
            state.writeText(JSONObject(state.readText()).put("url", FDroid.OFFICIAL_URL).put("fingerprint", FDroid.OFFICIAL_FINGERPRINT).toString())
        }
        val repos = File(data, "repos").apply { mkdirs() }
        if (!old.renameTo(File(repos, "fdroid"))) old.deleteRecursively()
    }

    private fun usage(): Nothing =
        throw Stop("usage: index [--force] <file> | apply <profile.json> [--dry-run] [--force] | restore | updates | update [--auto] [apps...]")
}
