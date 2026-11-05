package dev.seraph.manager

import android.app.AlertDialog
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The Backups tab: the last build's backups (android.backup): the config's apps and folders, when each was backed up
 * and whether it is due, and every backup in its repository; its search box keeps the rows with what is typed in
 * them (an app's name or package, a folder, "due", "not installed"). By hand, any app (the parts picked: its APK, its
 * data, ...) or folder backed up now, and an app's APK and data, or a folder, brought back from any of its backups.
 * All through the module's backup.sh, as root.
 */
class BackupsPane(private val activity: MainActivity, root: View, private val runner: Runner) {
    private val status: TextView = root.findViewById(R.id.backups_status)
    private val list: LinearLayout = root.findViewById(R.id.backups_list)
    private val output: TextView = root.findViewById(R.id.backups_output)
    private val scroll: ScrollView = root.findViewById(R.id.backups_scroll)
    private val buttons: List<Button>
    /** The rows' buttons, each with whether it can do anything (an app not installed cannot be backed up); they wait while a script runs. */
    private val rowButtons = mutableListOf<Pair<Button, Boolean>>()
    /** The last build's backups (backup.sh status), when it has them. */
    private var info: JSONObject? = null
    /** The backups in the repository; null until they are read. */
    private var snapshots: List<Snapshot>? = null
    private var snapshotsProblem: String? = null
    /** What the search box has: the rows shown have it. */
    private var query = ""

    class Snapshot(
        val id: String, val time: Long, val app: String?, val versionCode: Long?, val versionName: String?,
        val parts: List<String>?, val folder: String?,
    )

    init {
        buttons = listOf(
            root.findViewById<Button>(R.id.backup_all).apply { setOnClickListener { backUp(emptyList(), null) } },
            root.findViewById<Button>(R.id.backup_pick).apply { setOnClickListener { pick() } },
            root.findViewById<Button>(R.id.backups_refresh).apply { setOnClickListener { load() } },
        )
        Ui.onSearch(root.findViewById(R.id.backups_search)) {
            query = it
            if (info != null) show()
        }
    }

    /** What the tab shows, read again: the last build's backups, then the repository's. */
    fun load() {
        status.setText(R.string.reading)
        runner.read(Root.MODULE + "sh \$d/backup.sh app status --json 2>&1") { exit, said ->
            val json = Runner.json(said)
            info = json?.takeIf { it.optBoolean("configured") }
            snapshots = null
            snapshotsProblem = null
            when {
                exit == null -> empty(activity.getString(R.string.no_root))
                json == null -> empty(Runner.lastLine(said))
                info == null -> empty(activity.getString(R.string.backups_none))
                else -> {
                    show()
                    readSnapshots()
                }
            }
        }
    }

    private fun empty(why: String) {
        status.text = why
        list.removeAllViews()
        rowButtons.clear()
        setRunning(runner.running)
    }

    private fun readSnapshots() {
        runner.read(Root.MODULE + "sh \$d/backup.sh app list --json 2>&1") { _, said ->
            val array = Runner.json(said)?.optJSONArray("snapshots")
            if (array == null) {
                snapshotsProblem = Runner.lastLine(said).ifEmpty { "?" }
            } else {
                snapshots = objects(array).map { s ->
                    Snapshot(
                        s.getString("id"), s.optLong("time"), s.optString("app").ifEmpty { null },
                        if (s.has("versionCode")) s.optLong("versionCode") else null, s.optString("versionName").ifEmpty { null },
                        s.optJSONArray("parts")?.let { strings(it) }, s.optString("folder").ifEmpty { null },
                    )
                }
            }
            if (info != null) show()
        }
    }

    /** A row of the list: an app (its package name) or a folder (its path), and what it says under its title. */
    private class Item(val app: String?, val folder: String?, val line: String?, val mine: List<Snapshot>)

    /**
     * The config's apps and folders with their backups, then the backups of others (made by hand, or before they left
     * the config); with a search, the rows that have it, under the headings that still have rows.
     */
    private fun show() {
        val info = info ?: return
        status.text = listOfNotNull(
            activity.getString(R.string.backups_repository, info.optString("repository")),
            activity.getString(R.string.backups_locked).takeIf { !info.optBoolean("unlocked", true) },
            info.optString("waiting").takeIf { it.isNotEmpty() }?.let { activity.getString(R.string.backups_waiting, it) },
            when {
                snapshotsProblem != null -> activity.getString(R.string.backups_unreadable, snapshotsProblem)
                snapshots == null -> activity.getString(R.string.reading_backups)
                else -> null
            },
        ).joinToString("\n")
        list.removeAllViews()
        rowButtons.clear()
        val all = snapshots.orEmpty()
        val apps = objects(info.optJSONArray("apps"))
        val folders = objects(info.optJSONArray("folders"))
        if (apps.isEmpty() && folders.isEmpty()) list.addView(Ui.note(activity, activity.getString(R.string.backups_nothing)))
        val otherApps = all.mapNotNull { it.app }.distinct().filter { id -> apps.none { it.getString("id") == id } }
        val otherFolders = all.mapNotNull { it.folder }.distinct().filter { path -> folders.none { it.getString("path") == path } }
        val sections = listOf(
            R.string.backups_apps to apps.map { app -> app.getString("id").let { id -> Item(id, null, appStatus(app), all.filter { it.app == id }) } },
            R.string.backups_folders to folders.map { folder ->
                folder.getString("path").let { path -> Item(null, path, lastLine(folder.optLong("last"), null), all.filter { it.folder == path }) }
            },
            R.string.backups_other to otherApps.sortedBy { label(it).lowercase() }.map { id -> Item(id, null, null, all.filter { it.app == id }) } +
                otherFolders.sorted().map { path -> Item(null, path, null, all.filter { it.folder == path }) },
        )
        var shown = 0
        for ((heading, items) in sections) {
            val found = items.filter { matches(it) }
            if (found.isEmpty()) continue
            list.addView(Ui.heading(activity, activity.getString(heading)))
            for (item in found) list.addView(if (item.app != null) appRow(item.app, item.line, item.mine) else folderRow(item.folder!!, item.line, item.mine))
            shown += found.size
        }
        if (shown == 0 && query.isNotEmpty() && (apps.isNotEmpty() || folders.isNotEmpty() || all.isNotEmpty())) list.addView(Ui.noMatch(activity, query))
        setRunning(runner.running)
    }

    /** Whether [item] has what the search box has: in its title or in what it says under it. */
    private fun matches(item: Item): Boolean = query.isEmpty() || if (item.app != null) {
        Ui.matches(query, *appTexts(item.app, item.line, item.mine).toList().toTypedArray())
    } else {
        Ui.matches(query, item.folder, item.line, countLine(item.mine))
    }

    private fun appStatus(app: JSONObject): String {
        val reason = app.optString("reason")
        val due = if (app.optBoolean("due")) activity.getString(R.string.backup_due, reason) else activity.getString(R.string.backup_not_due, reason)
        return lastLine(app.optLong("last"), app.optString("when")) + " · " + due
    }

    private fun lastLine(last: Long, `when`: String?): String =
        listOfNotNull(
            if (last > 0) activity.getString(R.string.backed_up_last, Ui.ago(last)) else activity.getString(R.string.backed_up_never),
            `when`?.ifEmpty { null },
        ).joinToString(" · ")

    /** How many backups there are, and from when the newest is. */
    private fun countLine(mine: List<Snapshot>): String? = when {
        snapshots == null -> null
        mine.isEmpty() -> activity.getString(R.string.no_backups_yet)
        else -> activity.getString(
            R.string.backups_count_newest, activity.resources.getQuantityString(R.plurals.backup_count, mine.size, mine.size), Ui.date(mine.maxOf { it.time }),
        )
    }

    /** An app's row's title (its name) and what it says under it: its package, whether it is installed, its backups. */
    private fun appTexts(id: String, line: String?, mine: List<Snapshot>): Pair<String, String> {
        val title = label(id)
        return title to listOfNotNull(
            id.takeIf { it != title },
            activity.getString(R.string.not_installed).takeIf { installed(id) == null },
            line,
            countLine(mine),
        ).joinToString("\n")
    }

    private fun appRow(id: String, line: String?, mine: List<Snapshot>): View {
        val (title, subtitle) = appTexts(id, line, mine)
        val backUp = Ui.button(activity, activity.getString(R.string.backup)) { backUp(listOf(id), null) }
        val restore = Ui.button(activity, activity.getString(R.string.restore)) { restoreApp(id) }
        rowButtons += listOf(backUp to (installed(id) != null), restore to mine.isNotEmpty())
        return Ui.row(activity, title, subtitle, listOf(backUp, restore))
    }

    private fun folderRow(path: String, line: String?, mine: List<Snapshot>): View {
        val backUp = Ui.button(activity, activity.getString(R.string.backup)) { backUp(listOf(path), null) }
        val restore = Ui.button(activity, activity.getString(R.string.restore)) { restoreFolder(path) }
        rowButtons += listOf(backUp to true, restore to mine.isNotEmpty())
        return Ui.row(activity, path, listOfNotNull(line, countLine(mine)).joinToString("\n"), listOf(backUp, restore))
    }

    fun setRunning(on: Boolean) {
        buttons.forEach { it.isEnabled = !on }
        rowButtons.forEach { (button, can) -> button.isEnabled = can && !on }
    }

    // --- Backing up by hand.

    /** Backs up [names] (none: all of the config's), each app with [parts] (null: its settings, or android.backup.defaults). */
    private fun backUp(names: List<String>, parts: List<String>?) {
        val args = listOfNotNull(parts?.let { "--parts=" + it.joinToString(",") }) + names
        runner.run(
            Root.MODULE + "sh \$d/backup.sh app now${args.joinToString("") { " " + ConfigSource.quote(it) }} 2>&1",
            activity.getString(R.string.backing_up), output, scroll,
        ) { load() }
    }

    /** Back up…: apps, picked from the installed ones, with the parts picked; or a folder, from the system's folder picker. */
    private fun pick() {
        AlertDialog.Builder(activity)
            .setTitle(R.string.pick_what)
            .setItems(arrayOf(activity.getString(R.string.pick_apps_item), activity.getString(R.string.pick_folder_item))) { _, which ->
                if (which == 0) pickApps() else activity.pickFolder()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun pickApps() {
        runner.background {
            val pm = activity.packageManager
            // The apps installed on the phone, and those that came with it but were updated: the ones with an APK of their own.
            val apps = pm.getInstalledApplications(0)
                .filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 || it.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0 }
                .filter { it.packageName != activity.packageName }
                .map { it.packageName to it.loadLabel(pm).toString() }
                .sortedBy { it.second.lowercase() }
            activity.runOnUiThread { if (!activity.isDestroyed) showApps(apps) }
        }
    }

    /** The apps to back up, picked from [apps] (package name, name), found by name or package with the search box. */
    private fun showApps(apps: List<Pair<String, String>>) {
        if (apps.isEmpty()) {
            toast(activity.getString(R.string.no_apps))
            return
        }
        Ui.pickMany(
            activity, activity.getString(R.string.pick_apps), activity.getString(R.string.search_apps),
            apps.map { (id, name) -> id to "$name\n$id" }, activity.getString(R.string.ok),
        ) { ids -> if (ids.isNotEmpty()) pickParts(ids) }
    }

    /** What to take of the apps picked, as android.backup.defaults says to start with. */
    private fun pickParts(ids: List<String>) {
        val defaults = info?.optJSONArray("defaults")?.let { strings(it) } ?: listOf("apk", "data", "deviceData", "storage")
        val checked = BooleanArray(PARTS.size) { PARTS[it] in defaults }
        AlertDialog.Builder(activity)
            .setTitle(R.string.pick_parts)
            .setMultiChoiceItems(PARTS.map { partName(it) }.toTypedArray(), checked) { _, i, on -> checked[i] = on }
            .setPositiveButton(R.string.backup) { _, _ ->
                val parts = PARTS.filterIndexed { i, _ -> checked[i] }
                if (parts.isNotEmpty()) backUp(ids, parts)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** A folder from the system's folder picker (see MainActivity.pickFolder). */
    fun folderPicked(uri: Uri) {
        val path = ConfigSource.treePath(uri) ?: return toast(activity.getString(R.string.not_a_folder))
        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.backup_folder_title, path))
            .setMessage(R.string.backup_folder_message)
            .setPositiveButton(R.string.backup) { _, _ -> backUp(listOf(path), null) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // --- Restoring.

    /** An app's backups, newest first, to pick one from; then what to bring back of it. */
    private fun restoreApp(id: String) {
        val mine = snapshots.orEmpty().filter { it.app == id }.sortedByDescending { it.time }
        if (mine.isEmpty()) return
        val installed = installed(id)?.first
        // A backup of a newer version than the one installed brings its data back only with its APK.
        val items = mine.map { s ->
            activity.getString(R.string.snapshot_item, Ui.date(s.time), version(s)) +
                (if (installed != null && s.versionCode != null && s.versionCode > installed) activity.getString(R.string.snapshot_newer) else "")
        }
        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.restore_which, label(id)))
            .setItems(items.toTypedArray()) { _, i -> partsOf(id, mine[i], installed) { parts, apk -> restoreParts(id, mine[i], parts, apk, installed) } }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * What a backup can bring back: its parts, and the backup the app's APK would come from (its own, else another of
     * the app's, as the engine picks it). Its tags say both when it has the APK, or when the app needs none (installed,
     * at a version that reads the backup's data); else the engine is asked, which reads backups made before the tags too.
     */
    private fun partsOf(id: String, snapshot: Snapshot, installed: Long?, then: (List<String>, Snapshot?) -> Unit) {
        val tagged = snapshot.parts
        val needsApk = installed == null || (snapshot.versionCode != null && snapshot.versionCode > installed)
        if (tagged != null && ("apk" in tagged || !needsApk)) return then(tagged, snapshot.takeIf { "apk" in tagged })
        Toast.makeText(activity, R.string.reading, Toast.LENGTH_SHORT).show()
        runner.read(Root.MODULE + "sh \$d/backup.sh app parts ${ConfigSource.quote(id)} ${ConfigSource.quote(snapshot.id)} --json 2>&1") { _, said ->
            val json = Runner.json(said)
            val parts = json?.optJSONArray("parts")?.let { strings(it) } ?: return@read toast(Runner.lastLine(said))
            // (A module from before it said where the APK comes from says the parts alone: the backup's own APK, if any.)
            val apk = json.optJSONObject("apk")?.let { a ->
                snapshots.orEmpty().firstOrNull { it.id == a.optString("id") } ?: Snapshot(
                    a.optString("id"), a.optLong("time"), id, if (a.has("versionCode")) a.optLong("versionCode") else null,
                    a.optString("versionName").ifEmpty { null }, null, null,
                )
            } ?: snapshot.takeIf { "apk" in parts }
            then(parts, apk)
        }
    }

    /**
     * What to bring back: the parts the backup has, and its APK when a backup has it ([apk]: this one, or another);
     * the APK picked to start with when the app is missing or older. An app that is not installed comes back with its
     * APK first, so that one cannot be left out; when no backup has its APK, it is not found.
     */
    private fun restoreParts(id: String, snapshot: Snapshot, parts: List<String>, apk: Snapshot?, installed: Long?) {
        if (installed == null && apk == null) return notFound(id)
        val has = PARTS.filter { (it == "apk" && apk != null) || (it != "apk" && it in parts) }
        if (has.isEmpty()) return toast(activity.getString(R.string.parts_unknown))
        val names = has.map { part ->
            if (part == "apk" && apk != null && apk.id != snapshot.id) activity.getString(R.string.part_apk_from, Ui.date(apk.time)) else partName(part)
        }
        val needed = { i: Int -> has[i] == "apk" && installed == null }
        val checked = BooleanArray(has.size) { i ->
            has[i] != "apk" || installed == null || (snapshot.versionCode != null && snapshot.versionCode > installed)
        }
        AlertDialog.Builder(activity)
            .setTitle(R.string.restore_parts)
            .setMultiChoiceItems(names.toTypedArray(), checked) { dialog, i, on ->
                if (!on && needed(i)) {
                    // The dialog marks the array itself before telling: both get it back.
                    checked[i] = true
                    (dialog as AlertDialog).listView.setItemChecked(i, true)
                    toast(activity.getString(R.string.restore_apk_needed))
                } else {
                    checked[i] = on
                }
            }
            .setPositiveButton(R.string.restore) { _, _ ->
                val chosen = has.filterIndexed { i, _ -> checked[i] }
                if (chosen.isNotEmpty()) confirmRestore(id, snapshot, chosen, apk)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** An app that is not installed and whose backups have no APK: it cannot come back until it is installed. */
    private fun notFound(id: String) {
        AlertDialog.Builder(activity)
            .setTitle(R.string.restore_not_found_title)
            .setMessage(activity.getString(R.string.restore_not_found_message, label(id)))
            .setPositiveButton(R.string.close, null)
            .show()
    }

    private fun confirmRestore(id: String, snapshot: Snapshot, parts: List<String>, apk: Snapshot?) {
        val data = parts.filter { it != "apk" }
        val apkLine = apk?.takeIf { "apk" in parts }?.let {
            if (it.id == snapshot.id) activity.getString(R.string.restore_confirm_apk, version(it))
            else activity.getString(R.string.restore_confirm_apk_from, version(it), Ui.date(it.time))
        }
        val message = listOfNotNull(
            apkLine,
            activity.getString(R.string.restore_confirm_data, data.joinToString(", ") { shortPartName(it) }).takeIf { data.isNotEmpty() },
        ).joinToString("\n\n")
        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.restore_confirm_title, label(id), Ui.date(snapshot.time)))
            .setMessage(message)
            .setPositiveButton(R.string.restore) { _, _ ->
                val args = listOf(id, snapshot.id, "--parts=" + parts.joinToString(",")).joinToString(" ") { ConfigSource.quote(it) }
                runner.run(Root.MODULE + "sh \$d/backup.sh app restore $args 2>&1", activity.getString(R.string.restoring), output, scroll) { load() }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** A folder's backups, newest first; then whether into the folder itself or into a new one next to it. */
    private fun restoreFolder(folder: String) {
        val mine = snapshots.orEmpty().filter { it.folder == folder }.sortedByDescending { it.time }
        if (mine.isEmpty()) return
        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.restore_which, folder))
            .setItems(mine.map { Ui.date(it.time) }.toTypedArray()) { _, i ->
                val snapshot = mine[i]
                val stamp = SimpleDateFormat("yyyy-MM-dd HHmm", Locale.ROOT).format(Date())
                val fresh = folder.trimEnd('/') + " " + activity.getString(R.string.restored_suffix, stamp)
                AlertDialog.Builder(activity)
                    .setTitle(activity.getString(R.string.restore_confirm_title, folder, Ui.date(snapshot.time)))
                    .setItems(arrayOf(activity.getString(R.string.restore_folder_into, folder), activity.getString(R.string.restore_folder_new, fresh))) { _, where ->
                        val args = listOfNotNull(folder, snapshot.id, if (where == 1) "--to=$fresh" else null).joinToString(" ") { ConfigSource.quote(it) }
                        runner.run(Root.MODULE + "sh \$d/backup.sh app restore $args 2>&1", activity.getString(R.string.restoring), output, scroll) { load() }
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // --- Names.

    /** The installed app's version code and name, or null when it is not installed. */
    private fun installed(id: String): Pair<Long, String>? = try {
        activity.packageManager.getPackageInfo(id, 0).let { it.longVersionCode to (it.versionName ?: it.longVersionCode.toString()) }
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    /** The app's name, as the launcher shows it; its package name when it is not installed. */
    private fun label(id: String): String = try {
        activity.packageManager.getApplicationInfo(id, 0).loadLabel(activity.packageManager).toString()
    } catch (e: PackageManager.NameNotFoundException) {
        id
    }

    private fun version(snapshot: Snapshot): String = activity.getString(
        R.string.snapshot_version,
        listOfNotNull(snapshot.versionName, snapshot.versionCode?.let { if (snapshot.versionName != null) "($it)" else "$it" }).joinToString(" ")
            .ifEmpty { activity.getString(R.string.unknown) },
    )

    private fun partName(part: String) = activity.getString(
        when (part) {
            "apk" -> R.string.part_apk
            "data" -> R.string.part_data
            "deviceData" -> R.string.part_deviceData
            "storage" -> R.string.part_storage
            "media" -> R.string.part_media
            else -> R.string.part_obb
        },
    )

    private fun shortPartName(part: String) = activity.getString(
        when (part) {
            "data" -> R.string.part_short_data
            "deviceData" -> R.string.part_short_deviceData
            "storage" -> R.string.part_short_storage
            "media" -> R.string.part_short_media
            else -> R.string.part_short_obb
        },
    )

    private fun toast(text: String) = Toast.makeText(activity, text, Toast.LENGTH_LONG).show()

    private fun objects(array: JSONArray?): List<JSONObject> = array?.let { (0 until it.length()).map { i -> it.getJSONObject(i) } } ?: emptyList()

    private fun strings(array: JSONArray): List<String> = (0 until array.length()).map { array.getString(it) }

    companion object {
        /** An app's parts, as backup.sh names them: its APKs, then its data. */
        private val PARTS = listOf("apk", "data", "deviceData", "storage", "media", "obb")
    }
}
