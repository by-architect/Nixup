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
 * and whether it is due, and every backup in its repository. By hand, any app (the parts picked: its APK, its data,
 * ...) or folder backed up now, and an app's APK and data, or a folder, brought back from any of its backups. All
 * through the module's backup.sh, as root.
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

    /** The config's apps and folders with their backups, then the backups of others (made by hand, or before they left the config). */
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
        if (apps.isNotEmpty()) list.addView(Ui.heading(activity, activity.getString(R.string.backups_apps)))
        for (app in apps) {
            val id = app.getString("id")
            list.addView(appRow(id, appStatus(app), all.filter { it.app == id }))
        }
        if (folders.isNotEmpty()) list.addView(Ui.heading(activity, activity.getString(R.string.backups_folders)))
        for (folder in folders) {
            val path = folder.getString("path")
            list.addView(folderRow(path, lastLine(folder.optLong("last"), null), all.filter { it.folder == path }))
        }
        val otherApps = all.mapNotNull { it.app }.distinct().filter { id -> apps.none { it.getString("id") == id } }
        val otherFolders = all.mapNotNull { it.folder }.distinct().filter { path -> folders.none { it.getString("path") == path } }
        if (otherApps.isNotEmpty() || otherFolders.isNotEmpty()) list.addView(Ui.heading(activity, activity.getString(R.string.backups_other)))
        for (id in otherApps.sortedBy { label(it).lowercase() }) list.addView(appRow(id, null, all.filter { it.app == id }))
        for (path in otherFolders.sorted()) list.addView(folderRow(path, null, all.filter { it.folder == path }))
        setRunning(runner.running)
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

    private fun appRow(id: String, line: String?, mine: List<Snapshot>): View {
        val installed = installed(id)
        val title = label(id)
        val subtitle = listOfNotNull(
            id.takeIf { it != title },
            activity.getString(R.string.not_installed).takeIf { installed == null },
            line,
            countLine(mine),
        ).joinToString("\n")
        val backUp = Ui.button(activity, activity.getString(R.string.backup)) { backUp(listOf(id), null) }
        val restore = Ui.button(activity, activity.getString(R.string.restore)) { restoreApp(id) }
        rowButtons += listOf(backUp to (installed != null), restore to mine.isNotEmpty())
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

    private fun showApps(apps: List<Pair<String, String>>) {
        if (apps.isEmpty()) {
            toast(activity.getString(R.string.no_apps))
            return
        }
        val checked = BooleanArray(apps.size)
        AlertDialog.Builder(activity)
            .setTitle(R.string.pick_apps)
            .setMultiChoiceItems(apps.map { (id, name) -> "$name\n$id" }.toTypedArray(), checked) { _, i, on -> checked[i] = on }
            .setPositiveButton(R.string.ok) { _, _ ->
                val ids = apps.filterIndexed { i, _ -> checked[i] }.map { it.first }
                if (ids.isNotEmpty()) pickParts(ids)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
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
            .setItems(items.toTypedArray()) { _, i -> partsOf(id, mine[i]) { parts -> restoreParts(id, mine[i], parts, installed) } }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** The parts a backup has: its tags say; for one made before they said, restic is asked. */
    private fun partsOf(id: String, snapshot: Snapshot, then: (List<String>) -> Unit) {
        snapshot.parts?.let { return then(it) }
        Toast.makeText(activity, R.string.reading, Toast.LENGTH_SHORT).show()
        runner.read(Root.MODULE + "sh \$d/backup.sh app parts ${ConfigSource.quote(id)} ${ConfigSource.quote(snapshot.id)} --json 2>&1") { _, said ->
            val parts = Runner.json(said)?.optJSONArray("parts")?.let { strings(it) }
            if (parts == null) toast(Runner.lastLine(said)) else then(parts)
        }
    }

    /** What to bring back: each part the backup has; its APK picked to start with when the app is missing or older. */
    private fun restoreParts(id: String, snapshot: Snapshot, parts: List<String>, installed: Long?) {
        val has = PARTS.filter { it in parts }
        if (has.isEmpty()) return toast(activity.getString(R.string.parts_unknown))
        val checked = BooleanArray(has.size) { i ->
            has[i] != "apk" || installed == null || (snapshot.versionCode != null && snapshot.versionCode > installed)
        }
        AlertDialog.Builder(activity)
            .setTitle(R.string.restore_parts)
            .setMultiChoiceItems(has.map { partName(it) }.toTypedArray(), checked) { _, i, on -> checked[i] = on }
            .setPositiveButton(R.string.restore) { _, _ ->
                val chosen = has.filterIndexed { i, _ -> checked[i] }
                if (chosen.isNotEmpty()) confirmRestore(id, snapshot, chosen)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmRestore(id: String, snapshot: Snapshot, parts: List<String>) {
        val data = parts.filter { it != "apk" }
        val message = listOfNotNull(
            activity.getString(R.string.restore_confirm_apk, version(snapshot)).takeIf { "apk" in parts },
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
