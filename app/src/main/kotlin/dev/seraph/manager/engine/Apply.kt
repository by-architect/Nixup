package dev.seraph.manager.engine

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Makes the phone match the profile: its apps (android.packages: a list for each repository, and the apps from
 * releases), their settings (android.apps), the apps that came with the phone to remove (android.apps.<app>.debloat) and the
 * phone's own settings (android.system), the secrets (android.secrets) and the services (android.units, from the config's systemd.services), in nixos-rebuild's order: first the plan and every check, and when one fails
 * nothing changes; then the secrets (see Secrets: [incoming] holds the ones a PC sent), the apps (see Installer), the settings of apps (see AppSettings), the debloat (see Debloat),
 * the system settings (see SystemSettings), the services (see Units), and the apps that left the config removed, their data kept.
 */
class Apply(private val repos: Map<String, FDroid>, private val data: File, private val incoming: File? = null) {
    private val installer = Installer(data)
    private val releases = Releases(data)

    /** With [force], the releases of the apps from releases are asked for again even when they are new enough. */
    fun run(profile: File, dryRun: Boolean, force: Boolean): Int = Installer.locked(data) { apply(profile, dryRun, force) }

    private fun apply(profile: File, dryRun: Boolean, force: Boolean): Int {
        // Until the first unlock Android keeps the apps' own settings (languages, services, data) out of reach, and they
        // would read as unset: changed back for nothing, and remembered wrong as what they were.
        if (!Device.unlocked()) {
            throw Stop("the phone was not unlocked since it started, and until then Android keeps the apps' settings out of reach: unlock it once, then build")
        }
        val android = JSONObject(profile.readText()).getJSONObject("android")
        val packages = android.optJSONObject("packages") ?: JSONObject()
        val apps = android.optJSONObject("apps") ?: JSONObject()
        // The apps that came with the phone to remove (debloat), and the settings of the others.
        val debloated = apps.keys().asSequence().filter { apps.getJSONObject(it).optBoolean("debloat") }.toList()
        val appSettings = JSONObject(apps.toString()).apply { debloated.forEach { remove(it) } }
        val fromReleases = Releases.apps(android)
        // Each app of the config with where it comes from: a repository, or its releases (obtainium).
        val wanted = packages.keys().asSequence().filter { it != Releases.NAME }
            .flatMap { repo -> packages.getJSONArray(repo).strings().map { it to repo } }.toList() +
            fromReleases.map { it.id to Releases.NAME }
        // The ones removed by hand since the last build are no longer the module's.
        val owned = installer.readOwned().filter { Device.versionCode(it) != null }

        val steps = ArrayList<Installer.Step>()
        val upToDate = ArrayList<String>()
        val problems = ArrayList<String>()
        for ((id, name) in wanted) {
            if (name == Releases.NAME) continue
            val repo = repos[name]
            if (repo == null) {
                problems += "android.packages.$name: there is no repository named $name"
                continue
            }
            val app = repo.find(id)
            if (app == null) {
                problems += "$id is not in $name (its index from ${repo.date}; if the app is new there, build with an update of the index)"
                continue
            }
            val installed = Device.installed(id)
            when (val choice = repo.suggested(app, installed)) {
                is FDroid.Choice.Problem -> problems += choice.text
                is FDroid.Choice.Pick -> when {
                    installed == null || installed.versionCode < choice.version.code -> steps += repo.step(app, choice.version, installed)
                    else -> upToDate += "$id ${installed.versionName}"
                }
            }
        }
        for (app in fromReleases) {
            try {
                releases.refresh(app, force)
            } catch (e: Exception) {
                problems += "${app.id}: ${e.message}"
                continue
            }
            when (val plan = releases.plan(app, Device.installed(app.id))) {
                is Releases.Plan.Install -> steps += plan.step
                is Releases.Plan.Current -> upToDate += "${app.id} ${plan.version}"
                is Releases.Plan.Problem -> problems += plan.text
            }
        }
        // The apps that update others go first: those after them get them as their installer of record.
        steps.sortBy { it.id !in Device.FDROID + Device.OBTAINIUM }
        val ids = wanted.map { it.first }
        val removals = owned.filter { it !in ids }
        val toInstall = steps.filter { it.installed == null }
        val toUpdate = steps.filter { it.installed != null }

        val sources = wanted.map { it.second }.distinct()
        Out.line("Apps in the config: ${wanted.size}" + if (sources.size > 1) " (from ${sources.joinToString(", ")})" else "")
        if (toInstall.isNotEmpty()) {
            Out.line("To install (${toInstall.size}, ${download(toInstall)} to download):")
            toInstall.forEach { Out.line("  ${it.id}  ${it.version}  ${Out.size(it.size)}  (${it.name}, from ${it.repo})") }
        }
        if (toUpdate.isNotEmpty()) {
            Out.line("To update (${toUpdate.size}, ${download(toUpdate)} to download):")
            toUpdate.forEach {
                // A release's version code is in its APK: one not newer than the app installed is left once it is here.
                val check = if (it.code == null) ", if its APK is newer" else ""
                Out.line("  ${it.id}  ${it.installed!!.versionName} -> ${it.version}  ${Out.size(it.size)}  (from ${it.repo}$check)")
            }
        }
        if (removals.isNotEmpty()) {
            Out.line("To remove (${removals.size}, they left the config; their data is kept):")
            removals.forEach { Out.line("  $it") }
        }
        if (upToDate.isNotEmpty()) Out.line("Up to date (${upToDate.size}): ${upToDate.joinToString(", ")}")
        val debloat = Debloat(data)
        debloat.plan(debloated, homeApp(apps), problems)
        // An app being removed (left the config, or debloated) gets no settings.
        val leaving = removals.toSet() + debloat.removing
        val settings = AppSettings(data)
        settings.plan(appSettings, toInstall.map { it.id }.toSet(), leaving, problems)
        val system = SystemSettings(data)
        system.plan(android.optJSONObject("system"), toInstall.map { it.id }.toSet(), problems)
        val secrets = Secrets(data, incoming)
        secrets.plan(android.optJSONArray("secrets"), problems)
        val units = Units(data)
        units.plan(android.optJSONObject("units"), problems)
        val adb = Adb(data)
        adb.plan(android.optJSONObject("adb"), problems)
        if (problems.isNotEmpty()) {
            Out.line("✘ Cannot build:")
            problems.forEach { Out.line("  - $it") }
            return 2
        }
        if (steps.isEmpty() && removals.isEmpty() && !settings.changes && !debloat.changes && !system.changes && !secrets.changes && !units.changes && !adb.changes) {
            Out.line("Nothing to change")
            if (!dryRun) {
                // Root's shell follows environment.systemPackages, which changes no unit.
                units.prepare(android)
                finish(profile, ids, fromReleases)
            }
            return 0
        }
        if (!installer.roomFor(steps)) return 2
        if (dryRun) return 0

        // The secrets first: what reads them (a service) may start below.
        if (secrets.apply() != 0) return 1
        val status = installer.fetchAndInstall(steps)
        if (status != 0) return status
        var failed = settings.apply(appSettings, leaving)
        // An app that came with the phone keeps its data while removed: it gets its settings from before back first.
        for (id in debloat.removing) failed += settings.restoreApp(id)
        failed += debloat.apply()
        failed += system.apply()
        failed += adb.apply()
        units.prepare(android)
        failed += units.apply(android)
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
        finish(profile, ids, fromReleases)
        adb.restartLater()
        if (failed > 0) {
            Out.line("$failed settings did not take (see above); building again tries them again.")
            return 1
        }
        return 0
    }

    /** The app the config makes the home app (android.apps.<app>.defaultFor = [ "home" ]), if any. */
    private fun homeApp(apps: JSONObject): String? = apps.keys().asSequence().firstOrNull { app ->
        apps.getJSONObject(app).optJSONArray("defaultFor")?.let { roles -> (0 until roles.length()).any { roles.getString(it) == "home" } } == true
    }

    /** The versions for the generation, the updates list (the build may have installed some of them), and Obtainium's list. */
    private fun finish(profile: File, ids: List<String>, fromReleases: List<Releases.App>) {
        installer.writeResolved(ids)
        Updates(data, profile).list(quiet = true)
        releases.tellObtainium(fromReleases)
    }

    private fun download(steps: List<Installer.Step>) =
        Out.size(steps.filter { it.size >= 0 }.sumOf { it.size }) + if (steps.any { it.size < 0 }) " and some of unknown size" else ""

    /**
     * Brings back the apps it removed (debloat), gives back every app setting and system setting the module changed,
     * takes the apps it put in Obtainium's list out again and removes the apps it installed, keeping their data: the
     * module is being removed.
     */
    fun restore(): Int {
        var failed = Debloat(data).restoreAll()
        failed += AppSettings(data).restoreAll()
        failed += SystemSettings(data).restoreAll()
        failed += Units(data).removeAll()
        failed += Adb(data).restore()
        releases.tellObtainium(emptyList())
        for (id in installer.readOwned()) {
            if (Device.uninstallKeepingData(id)) Out.line("✔ Removed $id (its data is kept)") else Out.line("✘ Could not remove $id")
        }
        installer.forgetOwned()
        return if (failed > 0) 1 else 0
    }

    private fun JSONArray.strings() = (0 until length()).map { getString(it) }
}
