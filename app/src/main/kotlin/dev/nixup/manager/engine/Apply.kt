package dev.nixup.manager.engine

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Makes the phone match the profile: its apps (android.packages: a list for each repository, the apps from releases,
 * and those from Google Play), their settings (android.apps), the apps that came with the phone to remove (android.apps.<app>.debloat) and the
 * phone's own settings (android.system), the secrets (android.secrets), DAVx⁵'s accounts (android.davx5) and the services (android.units, from the config's systemd.services), in nixos-rebuild's order: first the plan and every check, and when one fails
 * nothing changes (the config's own system.preSwitchChecks among them); then the secrets (see Secrets: [incoming] holds the ones a PC sent), the apps (see Installer), the settings of apps (see AppSettings), the debloat (see Debloat),
 * the system settings (see SystemSettings), DAVx⁵'s accounts (see Davx5), the activation scripts (see Activation), the services (see Units), and the apps that left the config removed, their data kept.
 * With android.strict the apps on no list go too (hidden), last, and the ones it hid that come back go first, before the installs (see Strict).
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
        val fromPlay = PlayStore.apps(android)
        // Each app of the config with where it comes from: a repository, its releases (obtainium), or Google Play
        // (playstore, whose list is of package names too).
        val wanted = packages.keys().asSequence().filter { it != Releases.NAME }
            .flatMap { repo -> packages.getJSONArray(repo).strings().map { it to repo } }.toList() +
            fromReleases.map { it.id to Releases.NAME }
        // The ones removed by hand since the last build are no longer the module's.
        val owned = installer.readOwned().filter { Device.versionCode(it) != null }
        // android.strict: the apps on no list go, but for keep's and those the config needs otherwise: a user's app
        // (users.users.<name>.androidApp) and DAVx⁵ for services.davx5.
        val appsOfUsers = android.optJSONObject("users")?.optJSONArray("apps")?.let { list -> (0 until list.length()).map { list.getJSONObject(it).getString("app") } }.orEmpty()
        val strict = Strict(data, android.optJSONObject("strict"), (appsOfUsers + listOfNotNull(Davx5.APP.takeIf { android.optJSONObject("davx5") != null })).toSet())

        val steps = ArrayList<Installer.Step>()
        val upToDate = ArrayList<String>()
        val problems = ArrayList<String>()
        for ((id, name) in wanted) {
            if (name == Releases.NAME || name == PlayStore.NAME) continue
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
            // (One strict hid comes back first: its update is planned from what it is.)
            val installed = Device.installed(id) ?: strict.hidden(id)
            when (val choice = repo.suggested(app, installed)) {
                is FDroid.Choice.Problem -> problems += choice.text
                is FDroid.Choice.Pick -> when {
                    installed == null || installed.versionCode < choice.version.code -> steps += repo.step(app, choice.version, installed)
                    else -> upToDate += "$id ${installed.versionName}"
                }
                // Installed with another key than every build there: reinstalled as theirs, its data kept, when it can be.
                is FDroid.Choice.Reinstall -> if (installer.reinstall.check(id, installed!!, choice.version.code, name, problems)) {
                    steps += repo.step(app, choice.version, installed, reinstall = true)
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
            when (val plan = releases.plan(app, Device.installed(app.id) ?: strict.hidden(app.id))) {
                is Releases.Plan.Install -> if (!plan.step.reinstall || installer.reinstall.check(app.id, plan.step.installed!!, plan.step.code, Releases.NAME, problems)) {
                    steps += plan.step
                }
                is Releases.Plan.Current -> upToDate += "${app.id} ${plan.version}"
                is Releases.Plan.Problem -> problems += plan.text
            }
        }
        if (fromPlay.isNotEmpty()) {
            val play = PlayStore(data, android, Secrets(data, incoming)::staged)
            // The account first: without one Google takes, no app of the list can come.
            val signIn = play.signInProblem()
            if (signIn != null) {
                problems += signIn
                Notify.event("build", "error", "Sign in to Google Play", "Nixup app: ⋮ → Google Play account. $signIn")
            } else try {
                play.refresh(fromPlay, force)
                for (id in fromPlay) {
                    when (val plan = play.plan(id, Device.installed(id) ?: strict.hidden(id))) {
                        is PlayStore.Plan.Install -> {
                            // (The app is the last of its steps, after the libraries it needs.)
                            val app = plan.steps.last()
                            if (!app.reinstall || installer.reinstall.check(id, app.installed!!, app.code, PlayStore.NAME, problems)) steps += plan.steps
                        }
                        is PlayStore.Plan.Current -> upToDate += "$id ${plan.version}"
                        is PlayStore.Plan.Problem -> problems += plan.text
                    }
                }
            } catch (e: Exception) {
                problems += "${PlayStore.NAME}: ${e.message}"
            }
        }
        // The apps that update others go first: those after them get them as their installer of record (a library
        // stays before the app that needs it: the sort keeps their order).
        steps.sortBy { it.id !in Device.FDROID + Device.OBTAINIUM + Device.PLAYSTORE }
        val ids = wanted.map { it.first }
        // (One strict keeps stays, if the module installed it too.)
        val removals = owned.filter { it !in ids && it !in strict.keep.orEmpty() }
        val toInstall = steps.filter { it.installed == null }
        val toUpdate = steps.filter { it.installed != null && !it.reinstall }
        val toReinstall = steps.filter { it.reinstall }

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
        if (toReinstall.isNotEmpty()) {
            Out.line(
                "To reinstall (${toReinstall.size}, ${download(toReinstall)} to download; installed with another key than their source's builds: " +
                    "each comes back as theirs with its data, but for what it locked in Android's keystore, so it may ask to sign in again):",
            )
            toReinstall.forEach { Out.line("  ${it.id}  ${it.installed!!.versionName} (another key) -> ${it.version}  ${Out.size(it.size)}  (from ${it.repo})") }
        }
        if (removals.isNotEmpty()) {
            Out.line("To remove (${removals.size}, they left the config; their data is kept):")
            removals.forEach { Out.line("  $it") }
        }
        if (upToDate.isNotEmpty()) Out.line("Up to date (${upToDate.size}): ${upToDate.joinToString(", ")}")
        val debloat = Debloat(data)
        debloat.plan(debloated, homeApp(apps), problems)
        val keyboardApp = android.optJSONObject("system")?.optString("keyboard").orEmpty().substringBefore('/').takeIf { it.isNotEmpty() && it != "null" }
        strict.plan(ids.toSet(), removals.toSet(), homeApp(apps), keyboardApp, problems)
        // An app being removed (left the config, debloated, or on no list with strict) gets no settings.
        val leaving = removals.toSet() + debloat.removing + strict.removing
        // The apps strict brings back are installed by this build too.
        val installing = toInstall.map { it.id }.toSet() + strict.bringingBack
        val settings = AppSettings(data)
        settings.plan(appSettings, installing, leaving, problems)
        val system = SystemSettings(data)
        system.plan(android.optJSONObject("system"), installing, problems)
        val secrets = Secrets(data, incoming)
        secrets.plan(android.optJSONArray("secrets"), problems)
        // (Its passwords are secrets: those changing are told to it.)
        val davx5 = Davx5(data)
        davx5.plan(android.optJSONObject("davx5"), installing - leaving, secrets.changing, problems)
        val units = Units(data)
        units.plan(android.optJSONObject("units"), problems, secrets.changing, android.optJSONObject("timers"), android.optJSONObject("paths"))
        val adb = Adb(data)
        adb.plan(android.optJSONObject("adb"), problems)
        // The users of apps (users.users.<name>.androidApp) take their app's uid: the app is here, or this build installs it.
        android.optJSONObject("users")?.optJSONArray("apps")?.let { list ->
            val coming = installing - leaving
            for (i in 0 until list.length()) {
                val user = list.getJSONObject(i)
                val app = user.getString("app")
                if (app in leaving || (app !in coming && Device.uid(app) == null)) {
                    problems += "users.users.${user.getString("name")}.androidApp: $app is not on the phone (nor installed by this build)"
                }
            }
        }
        // The data of the apps it installs, and the apps backed up with their APK that are not here, from backups (with
        // strict, only those it keeps: the rest would go again).
        val backup = Backup.of(data, android)
        backup?.planRestore(toInstall.associate { it.id to it.code }, ids.toSet() + debloated, strict.keep, strict.bringingBack)
        // The config's own checks (system.preSwitchChecks), before anything changes: one that fails stops the build.
        val activation = Activation(data)
        activation.check(android, profile, dryRun, problems)
        if (problems.isNotEmpty()) {
            Out.line("✘ Cannot build:")
            problems.forEach { Out.line("  - $it") }
            return 2
        }
        // What changes, in one line, for what the build tells the app at its end (switch_to in common.sh).
        val parts = listOfNotNull(
            "app settings".takeIf { settings.changes }, "apps that came with the phone".takeIf { debloat.changes },
            "system settings".takeIf { system.changes }, "secrets".takeIf { secrets.changes }, "services".takeIf { units.changes },
            "adb".takeIf { adb.changes }, "data from backups".takeIf { backup?.changes == true }, "DAVx⁵ accounts".takeIf { davx5.changes },
        )
        writeSummary(dryRun, toInstall.size, toUpdate.size, toReinstall.size, removals.size + strict.removing.size, strict.bringingBack.size, parts)
        if (steps.isEmpty() && removals.isEmpty() && !strict.changes && !settings.changes && !debloat.changes && !system.changes && !secrets.changes && !units.changes && !adb.changes && backup?.changes != true && !davx5.changes) {
            Out.line("Nothing to change")
            if (dryRun) {
                activation.apply(android, dryRun = true)
                return 0
            }
            // Root's shell follows environment.systemPackages, which changes no unit.
            units.prepare(android)
            // The activation scripts run with every build, as on NixOS.
            val scriptsFailed = activation.apply(android, dryRun = false)
            finish(profile, ids, fromReleases)
            return if (scriptsFailed > 0) APPLIED_WITH_FAILURES else 0
        }
        if (!installer.roomFor(steps)) return 2
        if (dryRun) {
            // (A dry run's activation scripts may fail: it tells, and changes nothing either way.)
            activation.apply(android, dryRun = true)
            return 0
        }

        // The secrets first: what reads them (a service) may start below.
        if (secrets.apply() != 0) return 1
        // What the installs go by (an app verifier's checks), before them.
        var failed = system.applyBeforeInstalls()
        // The apps strict hid that come back, as they were: before the installs, as a list that has one now may update it.
        failed += strict.bringBack()
        val status = installer.fetchAndInstall(steps)
        if (status != 0) return status
        // Their data before their settings and before anything starts them.
        failed += backup?.applyRestore() ?: 0
        failed += settings.apply(appSettings, leaving)
        // An app that came with the phone keeps its data while removed: it gets its settings from before back first.
        for (id in debloat.removing) failed += settings.restoreApp(id)
        failed += debloat.apply()
        failed += system.apply()
        failed += adb.apply()
        // With DAVx⁵ installed, its battery setting made and the secrets in place.
        failed += davx5.apply()
        units.prepare(android)
        // The activation scripts (and the undo of those that left), with the users, /etc and tmpfiles' folders in place,
        // before the services start or start again, as on NixOS.
        val scriptsFailed = activation.apply(android, dryRun = false)
        val unitsFailed = units.apply(android)
        // (One Android does not remove, a device admin, stays on the list: the next build tries again, and the rest of
        // this one is the phone's all the same.)
        val removed = removals.filter { id ->
            if (Device.uninstallKeepingData(id)) {
                installer.writeOwned(installer.readOwned() - id)
                Out.line("✔ Removed $id (its data is kept: installing it again brings it back)")
                true
            } else {
                Out.line("✘ Could not remove $id")
                false
            }
        }
        settings.forgetRemoved(removed)
        // The apps on no list (strict), last, when what takes over from them (a home app, a keyboard) is in place: each
        // gets its settings from before back first, as a debloated app does, since it may come back.
        for (id in strict.removing) failed += settings.restoreApp(id)
        val notRemoved = removals.size - removed.size + strict.remove()
        finish(profile, ids, fromReleases)
        adb.restartLater()
        if (failed > 0 || unitsFailed > 0 || notRemoved > 0) {
            val what = listOfNotNull(
                failed.takeIf { it > 0 }?.let { if (it == 1) "1 setting did not take" else "$it settings did not take" },
                unitsFailed.takeIf { it > 0 }?.let { if (it == 1) "1 service failed" else "$it services failed" },
                notRemoved.takeIf { it > 0 }?.let { if (it == 1) "1 app could not be removed" else "$it apps could not be removed" },
            )
            Out.line("${what.joinToString(" and ")} (see above); building again tries again.")
            return APPLIED_WITH_FAILURES
        }
        // (The rest is done: an activation script that failed is told above, as NixOS's switch tells it.)
        return if (scriptsFailed > 0) APPLIED_WITH_FAILURES else 0
    }

    /** The summary of a build's changes, in $DATA/tmp/summary: "2 apps installed, 1 updated, system settings". */
    private fun writeSummary(dryRun: Boolean, installs: Int, updates: Int, reinstalls: Int, removals: Int, comebacks: Int, parts: List<String>) {
        val apps = { n: Int -> if (n == 1) "1 app" else "$n apps" }
        val counts = if (dryRun) {
            listOfNotNull(installs.takeIf { it > 0 }?.let { "${apps(it)} to install" }, updates.takeIf { it > 0 }?.let { "${apps(it)} to update" }, reinstalls.takeIf { it > 0 }?.let { "${apps(it)} to reinstall" }, removals.takeIf { it > 0 }?.let { "${apps(it)} to remove" }, comebacks.takeIf { it > 0 }?.let { "${apps(it)} to bring back" })
        } else {
            listOfNotNull(installs.takeIf { it > 0 }?.let { "${apps(it)} installed" }, updates.takeIf { it > 0 }?.let { "${apps(it)} updated" }, reinstalls.takeIf { it > 0 }?.let { "${apps(it)} reinstalled" }, removals.takeIf { it > 0 }?.let { "${apps(it)} removed" }, comebacks.takeIf { it > 0 }?.let { "${apps(it)} brought back" })
        }
        val all = counts + parts
        val text = when {
            all.isEmpty() -> "Nothing to change"
            dryRun -> "It would change: " + all.joinToString(", ")
            else -> "Changed: " + all.joinToString(", ")
        }
        runCatching { File(data, "tmp").apply { mkdirs() }.resolve("summary").writeText(text + "\n") }
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
     * Brings back the apps it removed (strict's and debloat's), gives back every app setting and
     * system setting the module changed, takes the apps it put in Obtainium's list out again and removes the apps it
     * installed, keeping their data: the module is being removed.
     */
    fun restore(): Int {
        var failed = Strict(data).restoreAll()
        failed += Debloat(data).restoreAll()
        failed += AppSettings(data).restoreAll()
        failed += SystemSettings(data).restoreAll()
        failed += Davx5(data).restore()
        // The activation scripts' undos, in Nixup's view, before the services and the view go.
        failed += Activation(data).undoAll()
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

    companion object {
        /**
         * The engine's status for a build applied whole, in which a setting, a service or an activation script failed:
         * it is the phone's build all the same (switch_to in common.sh), as NixOS's switch tells of units that failed.
         */
        const val APPLIED_WITH_FAILURES = 3
    }
}
