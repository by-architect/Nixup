package dev.andronix.manager.engine

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Makes the apps on the phone match the profile's android.packages.fdroid, in nixos-rebuild's order: first the
 * plan and every check, and when one fails nothing changes; then the apps (see Installer), the settings of apps
 * (android.apps, see AppSettings), and the apps that left the config removed, their data kept.
 */
class Apply(private val repo: FDroid, private val data: File) {
    private val installer = Installer(repo, data)

    fun run(profile: File, dryRun: Boolean): Int = Installer.locked(data) { apply(profile, dryRun) }

    private fun apply(profile: File, dryRun: Boolean): Int {
        val android = JSONObject(profile.readText()).getJSONObject("android")
        val wanted = android.optJSONObject("packages")?.optJSONArray("fdroid")?.strings() ?: emptyList()
        val apps = android.optJSONObject("apps") ?: JSONObject()
        // The ones removed by hand since the last build are no longer the module's.
        val owned = installer.readOwned().filter { Device.versionCode(it) != null }

        val steps = ArrayList<Installer.Step>()
        val upToDate = ArrayList<String>()
        val problems = ArrayList<String>()
        for (id in wanted) {
            val app = repo.find(id)
            if (app == null) {
                problems += "$id is not in F-Droid (its index from ${repo.date}; if the app is new there, build with an update of the index)"
                continue
            }
            val installed = Device.installed(id)
            when (val choice = repo.suggested(app, installed)) {
                is FDroid.Choice.Problem -> problems += choice.text
                is FDroid.Choice.Pick -> when {
                    installed == null -> steps += Installer.Step(app, choice.version, null)
                    installed.versionCode < choice.version.code -> steps += Installer.Step(app, choice.version, installed)
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
        val settings = AppSettings(data)
        settings.plan(apps, toInstall.map { it.app.id }.toSet(), removals.toSet(), problems)
        if (problems.isNotEmpty()) {
            Out.line("✘ Cannot build:")
            problems.forEach { Out.line("  - $it") }
            return 2
        }
        if (steps.isEmpty() && removals.isEmpty() && !settings.changes) {
            Out.line("Apps: nothing to change")
            if (!dryRun) finish(wanted)
            return 0
        }
        if (!installer.roomFor(steps)) return 2
        if (dryRun) return 0

        val status = installer.fetchAndInstall(steps)
        if (status != 0) return status
        val failed = settings.apply(apps, removals.toSet())
        for (id in removals) {
            if (Device.uninstallKeepingData(id)) {
                installer.writeOwned(installer.readOwned() - id)
                Out.line("✔ Removed $id (its data is kept: installing it again brings it back)")
            } else {
                Out.line("✘ Could not remove $id")
                return 1
            }
        }
        settings.forgetRemoved(removals)
        finish(wanted)
        if (failed > 0) {
            Out.line("$failed app settings did not take (see above); building again tries them again.")
            return 1
        }
        return 0
    }

    /** The versions for the generation, and the updates list (the build may have installed some of them). */
    private fun finish(wanted: List<String>) {
        installer.writeResolved(wanted)
        Updates(repo, data).list(wanted, quiet = true)
    }

    /** Gives back every app setting the module changed and removes the apps it installed, keeping their data: the module is being removed. */
    fun restore(): Int {
        val failed = AppSettings(data).restoreAll()
        for (id in installer.readOwned()) {
            if (Device.uninstallKeepingData(id)) Out.line("✔ Removed $id (its data is kept)") else Out.line("✘ Could not remove $id")
        }
        installer.forgetOwned()
        return if (failed > 0) 1 else 0
    }

    private fun JSONArray.strings() = (0 until length()).map { getString(it) }
}
