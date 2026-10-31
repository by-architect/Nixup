package dev.andronix.manager.engine

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Updates of the config's F-Droid apps, outside of a build. list finds the ones with a newer version (the one a
 * build would install) and writes them to updates.json for the app's Updates tab; update installs them, all or the
 * ones named, with notifications; update --auto is what the phone runs every 6 hours: it installs them when the
 * config's android.settings.fdroid.autoupdatePackages is true, and else only tells in a notification.
 */
class Updates(private val repo: FDroid, private val data: File) {
    private val installer = Installer(repo, data)
    private val profile = File(data, "profile.json")
    private val file = File(data, "updates.json")

    /** The apps of the config (from the last build) that have a newer version, written to updates.json. */
    fun list(wanted: List<String> = configApps(), quiet: Boolean = false): List<Installer.Step> {
        val steps = ArrayList<Installer.Step>()
        for (id in wanted) {
            // Not installed: the next build installs it.
            val installed = Device.installed(id) ?: continue
            val app = repo.find(id) ?: continue
            val pick = repo.suggested(app, installed) as? FDroid.Choice.Pick ?: continue
            if (pick.version.code > installed.versionCode) steps += Installer.Step(app, pick.version, installed)
        }
        write(steps)
        if (!quiet) {
            if (steps.isEmpty()) {
                Out.line("No updates: the config's ${wanted.size} F-Droid apps are up to date (index from ${repo.date})")
            } else {
                Out.line("Updates (${steps.size}, ${Out.size(steps.sumOf { it.version.size })} to download):")
                steps.forEach { Out.line("  ${it.app.id}  ${it.installed!!.versionName} -> ${it.version.name}  ${Out.size(it.version.size)}  (${it.app.name})") }
            }
        }
        return steps
    }

    /** Installs the updates of the apps named, or all of them; with [auto], only when the config says so. */
    fun update(ids: List<String>, auto: Boolean): Int = Installer.locked(data) {
        val steps = list()
        if (auto && !autoUpdate()) {
            if (steps.isNotEmpty()) {
                Notify.available(
                    if (steps.size == 1) "1 update is available" else "${steps.size} updates are available",
                    steps.joinToString(", ") { "${it.app.name} ${it.version.name}" },
                )
            }
            Out.line("android.settings.fdroid.autoupdatePackages is false: the updates are only told, not installed")
            return@locked 0
        }
        for (id in ids) if (steps.none { it.app.id == id }) Out.line("$id has no update (or is not one of the config's F-Droid apps)")
        val selected = if (ids.isEmpty()) steps else steps.filter { it.app.id in ids }
        if (selected.isEmpty()) {
            Out.line("Nothing to update")
            return@locked 0
        }
        if (!installer.roomFor(selected)) return@locked 2
        val status = installer.fetchAndInstall(selected)
        installer.writeResolved(configApps())
        list(quiet = true)
        status
    }

    private fun android(): JSONObject? = if (profile.isFile) JSONObject(profile.readText()).optJSONObject("android") else null

    private fun configApps(): List<String> =
        android()?.optJSONObject("packages")?.optJSONArray("fdroid")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()

    private fun autoUpdate(): Boolean =
        android()?.optJSONObject("settings")?.optJSONObject("fdroid")?.optBoolean("autoupdatePackages") ?: false

    /** For the app: when it was checked, against which index, whether the config updates by itself, and the updates. */
    private fun write(steps: List<Installer.Step>) {
        val updates = JSONArray()
        for (step in steps) {
            updates.put(
                JSONObject().put("id", step.app.id).put("name", step.app.name).put("repo", "fdroid")
                    .put("from", step.installed?.versionName ?: "").put("to", step.version.name).put("size", step.version.size),
            )
        }
        val json = JSONObject().put("checked", System.currentTimeMillis()).put("index", repo.date)
            .put("autoUpdate", JSONObject().put("fdroid", autoUpdate())).put("updates", updates)
        val fresh = File(file.path + ".new")
        fresh.writeText(json.toString())
        fresh.renameTo(file)
    }
}
