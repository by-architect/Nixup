package dev.nixup.manager.engine

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Updates of the config's apps (android.packages of each repository, the apps from releases and those from Google
 * Play), outside of a build. list finds the ones with a newer version (the one a build would install) and writes them
 * to updates.json for the app's Updates tab; update installs them, all or the ones named, with notifications; update
 * --auto is what the phone runs every 6 hours: it installs those of the repositories (and obtainium, playstore) whose
 * android.settings.repo.<repo>.autoupdatePackages is true, and only tells about the others in a notification. The config
 * is the last build's profile; the indexes, releases and what Google Play said are those here (update.sh brings them
 * up to date first).
 */
class Updates(private val data: File, private val profile: File = File(data, "profile.json")) {
    private val installer = Installer(data)
    private val releases = Releases(data)
    private val file = File(data, "updates.json")

    private val android: JSONObject? by lazy { if (profile.isFile) JSONObject(profile.readText()).optJSONObject("android") else null }
    private val repos: Map<String, FDroid> by lazy { if (profile.isFile) FDroid.of(JSONObject(profile.readText()), data) else emptyMap() }
    private val fromReleases: List<Releases.App> by lazy { android?.let { Releases.apps(it) } ?: emptyList() }
    private val fromPlay: List<String> by lazy { android?.let { PlayStore.apps(it) } ?: emptyList() }
    private val play: PlayStore by lazy { PlayStore(data, android) }

    /** Each repository's apps in the config. */
    private fun configApps(): Map<String, List<String>> {
        val packages = android?.optJSONObject("packages") ?: return emptyMap()
        return packages.keys().asSequence().filter { it != Releases.NAME }.associateWith { repo ->
            packages.getJSONArray(repo).let { a -> (0 until a.length()).map { a.getString(it) } }
        }
    }

    private fun allIds() = configApps().values.flatten() + fromReleases.map { it.id }

    private fun autoUpdate(repo: String): Boolean =
        android?.optJSONObject("settings")?.optJSONObject(repo)?.optBoolean("autoupdatePackages") ?: false

    /** The apps of the config that have a newer version, written to updates.json. */
    fun list(quiet: Boolean = false): List<Installer.Step> {
        val steps = ArrayList<Installer.Step>()
        val apps = configApps()
        for ((name, ids) in apps) {
            val repo = repos[name] ?: continue
            for (id in ids) {
                // Not installed: the next build installs it.
                val installed = Device.installed(id) ?: continue
                val app = repo.find(id) ?: continue
                // (One installed with another key is a build's to reinstall, not an update.)
                val pick = repo.suggested(app, installed) as? FDroid.Choice.Pick ?: continue
                if (pick.version.code > installed.versionCode) steps += repo.step(app, pick.version, installed)
            }
        }
        for (app in fromReleases) {
            val installed = Device.installed(app.id) ?: continue
            when (val plan = releases.plan(app, installed)) {
                // (One installed with another key is a build's to reinstall: it says so first.)
                is Releases.Plan.Install -> if (!plan.step.reinstall) steps += plan.step else if (!quiet) Out.line("${app.id}: installed with another key, so a build reinstalls it")
                is Releases.Plan.Problem -> if (!quiet) Out.line("${app.id}: ${plan.text.removePrefix("${app.id}: ")}")
                is Releases.Plan.Current -> {}
            }
        }
        for (id in fromPlay) {
            val installed = Device.installed(id) ?: continue
            when (val plan = play.plan(id, installed)) {
                is PlayStore.Plan.Install -> if (plan.steps.none { it.reinstall }) steps += plan.steps else if (!quiet) Out.line("$id: installed with another key, so a build reinstalls it")
                is PlayStore.Plan.Problem -> if (!quiet) Out.line("$id: ${plan.text.removePrefix("$id: ")}")
                is PlayStore.Plan.Current -> {}
            }
        }
        write(steps)
        if (!quiet) {
            if (steps.isEmpty()) {
                Out.line("No updates: the config's ${allIds().size} apps are up to date")
            } else {
                Out.line("Updates (${steps.size}, ${Out.size(steps.filter { it.size >= 0 }.sumOf { it.size })} to download):")
                steps.forEach {
                    val check = if (it.code == null) ", if its APK is newer" else ""
                    Out.line("  ${it.id}  ${it.installed!!.versionName} -> ${it.version}  ${Out.size(it.size)}  (${it.name}, from ${it.repo}$check)")
                }
            }
        }
        return steps
    }

    /** The updates there are, as [list] finds them, told to the app (the Updates tab's check). */
    fun check(): Int {
        val steps = list()
        if (steps.isEmpty()) Notify.event("update", "ok", "Apps up to date", "The config's ${allIds().size} apps were checked")
        else Notify.event("update", "info", if (steps.size == 1) "1 update is available" else "${steps.size} updates are available", steps.joinToString(", ") { "${it.name} ${it.version}" })
        return 0
    }

    /** Installs the updates of the apps named, or all of them; with [auto], those of the repositories that update by themselves. */
    fun update(ids: List<String>, auto: Boolean): Int = Installer.locked(data) {
        val steps = list()
        if (auto) {
            val told = steps.filter { !autoUpdate(it.repo) }
            if (told.isNotEmpty()) {
                Notify.event(
                    "update", "info",
                    if (told.size == 1) "1 update is available" else "${told.size} updates are available",
                    told.joinToString(", ") { "${it.name} ${it.version}" },
                )
                Out.line("Only told, not installed (their repository's autoupdatePackages is false): ${told.joinToString(", ") { it.id }}")
            } else if (steps.none { autoUpdate(it.repo) }) {
                Notify.event("update", "ok", "Apps up to date", "The config's ${allIds().size} apps were checked", quiet = true)
            }
        }
        for (id in ids) if (steps.none { it.id == id }) Out.line("$id has no update (or is not one of the config's apps)")
        val selected = when {
            auto -> steps.filter { autoUpdate(it.repo) }
            ids.isEmpty() -> steps
            // With the libraries the apps named need (Google Play's apps can need some).
            else -> ids.flatMap { play.libraries(it) }.toSet().let { needed -> steps.filter { it.id in ids || (it.library && it.id in needed) } }
        }
        if (selected.isEmpty()) {
            Out.line("Nothing to update")
            return@locked 0
        }
        if (!installer.roomFor(selected)) return@locked 2
        val status = installer.fetchAndInstall(selected)
        installer.writeResolved(allIds())
        list(quiet = true)
        status
    }

    /** For the app: when it was checked, which repositories update by themselves, and the updates. */
    private fun write(steps: List<Installer.Step>) {
        val updates = JSONArray()
        for (step in steps) {
            updates.put(
                JSONObject().put("id", step.id).put("name", step.name).put("repo", step.repo)
                    .put("from", step.installed?.versionName ?: "").put("to", step.version).put("size", step.size),
            )
        }
        val auto = JSONObject()
        repos.keys.forEach { auto.put(it, autoUpdate(it)) }
        if (fromReleases.isNotEmpty()) auto.put(Releases.NAME, autoUpdate(Releases.NAME))
        if (fromPlay.isNotEmpty()) auto.put(PlayStore.NAME, autoUpdate(PlayStore.NAME))
        val json = JSONObject().put("checked", System.currentTimeMillis()).put("autoUpdate", auto).put("updates", updates)
        val fresh = File(file.path + ".new")
        fresh.writeText(json.toString())
        fresh.renameTo(file)
    }
}
