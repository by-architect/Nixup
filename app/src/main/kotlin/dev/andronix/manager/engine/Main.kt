package dev.andronix.manager.engine

import java.io.File
import kotlin.system.exitProcess

/**
 * The module's engine, run as root by its build.sh through app_process, from the app's APK:
 *   CLASSPATH=app.apk app_process /system/bin dev.andronix.manager.engine.Main <command>
 * Commands:
 *   index [--force]               brings the F-Droid index up to date (once a day, or now with --force)
 *   apply <profile> [--dry-run]   makes the apps and their settings match the profile (with --dry-run: only plans and checks)
 *   restore                       gives back every app setting the module changed and removes the apps it installed
 *   updates                       lists the updates of the config's apps (and writes updates.json for the app)
 *   update [--auto] [apps...]     installs them, all or the ones named (with --auto: only when the config says so)
 * Exit status: 0 done, 2 stopped before changing anything, 1 stopped partway.
 */
object Main {
    private val data = File("/data/adb/andronix")

    @JvmStatic
    fun main(args: Array<String>) {
        val status = try {
            val repo = FDroid(File(data, "fdroid"))
            when (args.firstOrNull()) {
                "index" -> repo.refresh(force = "--force" in args)
                "apply" -> Apply(repo, data).run(File(args.getOrNull(1) ?: usage()), dryRun = "--dry-run" in args)
                "restore" -> Apply(repo, data).restore()
                "updates" -> Updates(repo, data).list().let { 0 }
                "update" -> Updates(repo, data).update(args.drop(1).filter { !it.startsWith("--") }, auto = "--auto" in args)
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

    private fun usage(): Nothing =
        throw Stop("usage: index [--force] | apply <profile.json> [--dry-run] | restore | updates | update [--auto] [apps...]")
}
