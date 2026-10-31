package dev.andronix.manager.engine

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Updates of the config's apps (android.packages of each repository), outside of a build. list finds the ones with
 * a newer version (the one a build would install) and writes them to updates.json for the app's Updates tab; update
 * installs them, all or the ones named, with notifications; update --auto is what the phone runs every 6 hours: it
 * installs those of the repositories whose android.settings.<repo>.autoupdatePackages is true, and only tells about
 * the others in a notification. The config is the last build's profile.
 */
class Updates(private val data: File, private val profile: File = File(data, "profile.json")) {
    private val installer = Installer(data)
    private val file = File(data, "updates.json")

    private val android: JSONObject? by lazy { if (profile.isFile) JSONObject(profile.readText()).optJSONObject("android") else null }
    private val repos: Map<String, FDroid> by lazy { if (profile.isFile) FDroid.of(JSONObject(profile.readText()), data) else emptyMap() }

    /** Each repository's apps in the config. */
    private fun configApps(): Map<String, List<String>> {
        val packages = android?.optJSONObject("packages") ?: return emptyMap()
        return packages.keys().asSequence().associateWith { repo ->
            packages.getJSONArray(repo).let { a -> (0 until a.length()).map { a.getString(it) } }
        }
    }

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
                val pick = repo.suggested(app, installed) as? FDroid.Choice.Pick ?: continue
                if (pick.version.code > installed.versionCode) steps += Installer.Step(repo, app, pick.version, installed)
            }
        }
        write(steps)
        if (!quiet) {
            if (steps.isEmpty()) {
                Out.line("No updates: the config's ${apps.values.sumOf { it.size }} apps are up to date")
            } else {
                Out.line("Updates (${steps.size}, ${Out.size(steps.sumOf { it.version.size })} to download):")
                steps.forEach {
                    Out.line("  ${it.app.id}  ${it.installed!!.versionName} -> ${it.version.name}  ${Out.size(it.version.size)}  (${it.app.name}, from ${it.repo.name})")
                }
            }
        }
        return steps
    }

    /** Installs the updates of the apps named, or all of them; with [auto], those of the repositories that update by themselves. */
    fun update(ids: List<String>, auto: Boolean): Int = Installer.locked(data) {
        val steps = list()
        if (auto) {
            val told = steps.filter { !autoUpdate(it.repo.name) }
            if (told.isNotEmpty()) {
                Notify.available(
                    if (told.size == 1) "1 update is available" else "${told.size} updates are available",
                    told.joinToString(", ") { "${it.app.name} ${it.version.name}" },
                )
                Out.line("Only told, not installed (their repository's autoupdatePackages is false): ${told.joinToString(", ") { it.app.id }}")
            }
        }
        for (id in ids) if (steps.none { it.app.id == id }) Out.line("$id has no update (or is not one of the config's apps)")
        val selected = when {
            auto -> steps.filter { autoUpdate(it.repo.name) }
            ids.isEmpty() -> steps
            else -> steps.filter { it.app.id in ids }
        }
        if (selected.isEmpty()) {
            Out.line("Nothing to update")
            return@locked 0
        }
        if (!installer.roomFor(selected)) return@locked 2
        val status = installer.fetchAndInstall(selected)
        installer.writeResolved(configApps().values.flatten())
        list(quiet = true)
        status
    }

    /** For the app: when it was checked, which repositories update by themselves, and the updates. */
    private fun write(steps: List<Installer.Step>) {
        val updates = JSONArray()
        for (step in steps) {
            updates.put(
                JSONObject().put("id", step.app.id).put("name", step.app.name).put("repo", step.repo.name)
                    .put("from", step.installed?.versionName ?: "").put("to", step.version.name).put("size", step.version.size),
            )
        }
        val auto = JSONObject()
        repos.keys.forEach { auto.put(it, autoUpdate(it)) }
        val json = JSONObject().put("checked", System.currentTimeMillis()).put("autoUpdate", auto).put("updates", updates)
        val fresh = File(file.path + ".new")
        fresh.writeText(json.toString())
        fresh.renameTo(file)
    }
}
