package dev.seraph.manager.engine

import android.system.Os
import android.system.OsConstants
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.time.OffsetDateTime

/**
 * Backups of apps and folders into a restic repository (android.backup, nix/backup.nix), run by restic from Nix's
 * store in Seraph's view of the phone (see Units), as root.
 *
 * An app's backup is one snapshot of /backup/<app>, where its parts are bound for the moment of the backup: apk (the
 * folder of its APKs), data (/data/user/0/<app>), deviceData (/data/user_de/0/<app>), storage, media and obb (its
 * folders in Android/ of the shared storage); caches are left out. So each app's snapshots are one group of restic's
 * (host and path), which restic's forget keeps as android.backup.keep says, and each snapshot is tagged with the app
 * and the version code it was made of. While its files are read the app's processes are stopped (SIGSTOP, then
 * SIGCONT), so the copy is whole; when it was running, a first pass reads it unstopped and the stopped pass only reads
 * what changed since, so that takes a moment.
 *
 * A build restores the data of the apps it installs from the newest snapshot of a version not newer than the one
 * installed, never over data the app already has (see [planRestore]), and installs from their snapshots the apps
 * backed up with their APK that are not on the phone (autoRestore).
 */
class Backup private constructor(private val data: File, private val cfg: JSONObject) {
    class Settings(val apk: Boolean, val data: Boolean, val include: List<String>, val `when`: String, val autoRestore: Boolean)

    class Snapshot(val id: String, val shortId: String, val time: Long, val tags: List<String>, val paths: List<String>) {
        val app: String? get() = tags.firstOrNull { it.startsWith("app:") }?.removePrefix("app:")
        val versionCode: Long? get() = tags.firstOrNull { it.startsWith("versionCode:") }?.removePrefix("versionCode:")?.toLongOrNull()
        val warm: Boolean get() = WARM in tags
        val date: String get() = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.ROOT).format(java.util.Date(time))
    }

    val apps: Map<String, Settings> = cfg.optJSONObject("apps")?.let { json ->
        json.keys().asSequence().associateWith { id ->
            val app = json.getJSONObject(id)
            Settings(
                app.optBoolean("apk", true), app.optBoolean("data", true),
                app.optJSONArray("include")?.strings() ?: listOf("data", "deviceData", "storage"),
                app.optString("when", "auto"), app.optBoolean("autoRestore", true),
            )
        }
    } ?: emptyMap()
    private val folders = cfg.optJSONArray("folders")?.strings() ?: emptyList()
    private val repository = cfg.getString("repository")
    private val host = cfg.optString("host").takeIf { it.isNotEmpty() && it != "null" } ?: Device.settings("global")["device_name"] ?: "phone"
    private val schedule = cfg.optLong("schedule", 86400) * 1000
    private val quietTime = cfg.optLong("quietTime", 600) * 1000

    private val dir = File(data, "backup")
    private val stateFile = File(dir, "state.json")
    private val frozenFile = File(dir, "frozen")
    private val tmp = File(data, "tmp")
    private val view = File(data, "units/view.sh")

    // --- Running restic.

    /** Where root finds a folder of the shared storage: /storage/emulated/<user> is /data/media/<user>, an SD card's /storage/<card> is /mnt/media_rw/<card>. */
    private fun real(path: String): String {
        Regex("^/storage/emulated/(\\d+)(/.*)?$").find(path)?.let { return "/data/media/${it.groupValues[1]}${it.groupValues[2]}" }
        Regex("^/storage/([^/]+)(/.*)?$").find(path)?.let { m ->
            val card = "/mnt/media_rw/${m.groupValues[1]}"
            if (m.groupValues[1] != "self" && File(card).isDirectory) return card + m.groupValues[2]
        }
        return path
    }

    private val repo: String get() = if (repository.startsWith("/")) real(repository) else repository

    /**
     * Runs [body] (shell lines, after the environment restic needs) in Seraph's view; what restic prints on standard
     * output is returned, and what it says on standard error goes to [say] line by line.
     */
    private fun inView(body: String, say: (String) -> Unit = { Out.line("  $it") }): Pair<Int, String> {
        if (!view.isFile) Units(data).writeView()
        tmp.mkdirs()
        val script = File(tmp, "backup-${Os.getpid()}-${System.nanoTime()}.sh")
        val env = mapOf(
            "PATH" to cfg.getString("path"),
            "RESTIC_REPOSITORY" to repo,
            "RESTIC_PASSWORD_FILE" to cfg.getString("passwordFile"),
            "RESTIC_CACHE_DIR" to "/var/cache/restic",
            "HOME" to "/var/lib/seraph-backup",
            "TMPDIR" to "${tmp.path}/restic",
            "RESTIC_PROGRESS_FPS" to "0.2",
        )
        script.writeText(buildString {
            append("set -e\n")
            for ((name, value) in env) append("export $name=${quote(value)}\n")
            append("mkdir -p \"\$TMPDIR\" \"\$HOME\"\n")
            cfg.optString("environmentFile").takeIf { it.isNotEmpty() && it != "null" }?.let { append("set -a; . ${quote(it)}; set +a\n") }
            append("b=${quote(BUSYBOX)}\n")
            append(body)
            append("\n")
        })
        try {
            val process = ProcessBuilder(BUSYBOX, "sh", view.path, script.path).start()
            process.outputStream.close()
            val errors = Thread { process.errorStream.bufferedReader().forEachLine { line -> if (line.isNotBlank()) say(line) } }.apply { start() }
            val out = process.inputStream.bufferedReader().readText()
            val status = process.waitFor()
            errors.join()
            return status to out
        } finally {
            script.delete()
        }
    }

    private fun restic(vararg args: String, say: (String) -> Unit = { Out.line("  $it") }): Pair<Int, String> =
        inView("exec restic " + args.joinToString(" ") { quote(it) }, say)

    /** Makes the repository, when there is none yet and initialize is on; false when it cannot be had. */
    private fun ready(): Boolean {
        val (status, _) = restic("cat", "config", say = {})
        if (status == 0) return true
        if (status != NO_REPOSITORY) {
            Out.line("✘ Backups: the repository $repository cannot be opened (restic exit $status); is it there, and the password right?")
            restic("cat", "config")
            return false
        }
        if (!cfg.optBoolean("initialize", true)) {
            Out.line("✘ Backups: there is no repository at $repository (android.backup.initialize = false): make it with seraph-backup restic init")
            return false
        }
        if (repo.startsWith("/")) File(repo).parentFile?.mkdirs()
        val (made, _) = restic("init")
        if (made != 0) {
            Out.line("✘ Backups: could not make the repository at $repository")
            return false
        }
        Out.line("✔ Backups: made the repository at $repository (keep its password off the phone too)")
        handOverRepository()
        return true
    }

    /** A repository on the shared storage is media_rw's, as the files apps put there are, so file managers and sync apps can copy it. */
    private fun handOverRepository() {
        if (repo.startsWith("/data/media/")) Device.run("chown", "-R", "1023:1023", repo)
    }

    fun snapshots(app: String? = null): List<Snapshot> {
        val args = mutableListOf("snapshots", "--json", "--host", host)
        if (app != null) args += listOf("--tag", "app:$app")
        val (status, out) = restic(*args.toTypedArray())
        if (status != 0) throw Stop("the backups at $repository cannot be read (restic exit $status)", 1)
        val json = JSONArray(out.trim().ifEmpty { "[]" })
        return (0 until json.length()).map { json.getJSONObject(it) }.map { s ->
            Snapshot(
                s.getString("id"), s.optString("short_id", s.getString("id").take(8)),
                OffsetDateTime.parse(s.getString("time")).toInstant().toEpochMilli(),
                s.optJSONArray("tags")?.strings() ?: emptyList(), s.optJSONArray("paths")?.strings() ?: emptyList(),
            )
        }.sortedBy { it.time }
    }

    // --- An app's parts.

    private fun partPath(part: String, id: String): String = when (part) {
        "data" -> "/data/user/0/$id"
        "deviceData" -> "/data/user_de/0/$id"
        "storage" -> "/data/media/0/Android/data/$id"
        "media" -> "/data/media/0/Android/media/$id"
        "obb" -> "/data/media/0/Android/obb/$id"
        else -> throw IllegalArgumentException(part)
    }

    /** The folder of the app's APKs, when it was installed (not one that came with the phone, in /system). */
    private fun apkDir(id: String): String? = Device.run("pm", "path", "--user", "0", id).second.lineSequence()
        .firstOrNull { it.startsWith("package:") }?.removePrefix("package:")?.trim()
        ?.let { File(it).parentFile?.path }?.takeIf { it.startsWith("/data/app/") }

    private fun binds(id: String, parts: Map<String, String>): String = parts.entries.joinToString("\n") { (part, path) ->
        "mkdir -p ${quote("/backup/$id/$part")} ${quote(path)}\n\"\$b\" mount --bind ${quote(path)} ${quote("/backup/$id/$part")}"
    }

    private fun excludes(id: String) = listOf("apk/oat", "apk/lib", "data/cache", "data/code_cache", "deviceData/cache", "deviceData/code_cache", "storage/cache")
        .flatMap { listOf("--exclude", "/backup/$id/$it") }

    // --- Stopping an app's processes for the moment of a copy.

    private fun pidsOf(uid: Int): List<Int> = File("/proc").listFiles().orEmpty().mapNotNull { it.name.toIntOrNull() }.filter { pid ->
        runCatching { File("/proc/$pid/status").useLines { lines -> lines.first { it.startsWith("Uid:") } }.split(Regex("\\s+"))[1].toInt() == uid }
            .getOrDefault(false)
    }

    private fun <T> stopped(pids: List<Int>, work: () -> T): T {
        if (pids.isEmpty()) return work()
        dir.mkdirs()
        frozenFile.writeText(pids.joinToString("\n"))
        pids.forEach { runCatching { Os.kill(it, OsConstants.SIGSTOP) } }
        try {
            return work()
        } finally {
            pids.forEach { runCatching { Os.kill(it, OsConstants.SIGCONT) } }
            frozenFile.delete()
        }
    }

    /** Lets go of processes an earlier run stopped and could not let go of (it was killed in between). */
    private fun thawLeftovers() {
        if (!frozenFile.isFile) return
        frozenFile.readLines().mapNotNull { it.trim().toIntOrNull() }.forEach { runCatching { Os.kill(it, OsConstants.SIGCONT) } }
        frozenFile.delete()
    }

    // --- Backing up.

    private class Result(val snapshot: String?, val added: Long, val skipped: Boolean)

    /** One restic backup of [paths]; null when it failed. */
    private fun backup(setup: String, paths: List<String>, tags: List<String>, extra: List<String>): Result? {
        val args = listOf("backup", "--json", "--host", host, "--skip-if-unchanged") + tags.flatMap { listOf("--tag", it) } + extra + paths
        val (status, out) = inView(setup + "\ncd /\nexec restic " + args.joinToString(" ") { quote(it) }) { line ->
            // restic's errors come as JSON lines too.
            val message = runCatching { JSONObject(line) }.getOrNull()?.let { it.optJSONObject("error")?.optString("message") ?: it.optString("message") } ?: line
            Out.line("  $message")
        }
        // 3: some files could not be read, and the snapshot has the others.
        if (status != 0 && status != 3) return null
        val summary = out.lineSequence().mapNotNull { runCatching { JSONObject(it) }.getOrNull() }
            .lastOrNull { it.optString("message_type") == "summary" } ?: return null
        val snapshot = summary.optString("snapshot_id").takeIf { it.isNotEmpty() }
        return Result(snapshot, summary.optLong("data_added"), snapshot == null)
    }

    private fun backupApp(id: String, settings: Settings): Boolean {
        val installed = Device.installed(id)
        if (installed == null) {
            Out.line("  $id is not installed: passed over")
            return true
        }
        val parts = LinkedHashMap<String, String>()
        if (settings.apk) apkDir(id)?.let { parts["apk"] = it }
        if (settings.data) for (part in settings.include) partPath(part, id).takeIf { File(it).isDirectory }?.let { parts[part] = it }
        if (parts.isEmpty()) {
            Out.line("  $id has nothing to back up")
            return true
        }
        val setup = binds(id, parts)
        val tags = listOf(APP_TAG, "app:$id", "versionCode:${installed.versionCode}")
        val pids = Device.uid(id)?.takeIf { it >= 10000 }?.let { pidsOf(it) } ?: emptyList()
        // A running app is read once as it is, so the stopped pass only reads what changed in between.
        val warm = if (pids.isNotEmpty()) backup(setup, listOf("/backup/$id"), tags + WARM, excludes(id)) else null
        val result = stopped(pids) { backup(setup, listOf("/backup/$id"), tags, excludes(id)) }
        warm?.snapshot?.let { restic("forget", it, say = {}) }
        if (result == null) {
            Out.line("✘ Could not back up $id")
            return false
        }
        state().apply {
            put(id, JSONObject().put("time", System.currentTimeMillis()).put("versionCode", installed.versionCode))
        }.let { saveState() }
        Out.line(
            if (result.skipped) "✔ $id: nothing changed since its last backup"
            else "✔ Backed up $id ${installed.versionName} (${Out.size(result.added)} new)",
        )
        return true
    }

    private fun backupFolder(folder: String): Boolean {
        val path = real(folder)
        if (!File(path).isDirectory) {
            Out.line("  $folder is not there: passed over")
            return true
        }
        val result = backup("", listOf(path), listOf(FOLDER_TAG), emptyList())
        if (result == null) {
            Out.line("✘ Could not back up $folder")
            return false
        }
        folderState().put(folder, System.currentTimeMillis())
        saveState()
        Out.line(if (result.skipped) "✔ $folder: nothing changed since its last backup" else "✔ Backed up $folder (${Out.size(result.added)} new)")
        return true
    }

    /**
     * Backs up the apps and folders named ([only]; none: all of the config's), or with [auto] those that are due:
     * an "auto" app once it was left in the background for quietTime (and at least once a schedule), a "schedule"
     * one once a schedule, any but a "manual" one after it was updated, and the folders once a schedule.
     */
    fun run(only: List<String>, auto: Boolean): Int = locked {
        if (!Device.unlocked()) throw Stop("the phone was not unlocked since it started: the apps' data is out of reach until then")
        thawLeftovers()
        if (auto && cfg.optBoolean("onlyWhileCharging") && !Device.charging()) {
            Out.line("Backups: only while charging, and the phone is not")
            return@locked 0
        }
        val unknown = only.filter { it !in apps && it !in folders }
        if (unknown.isNotEmpty()) throw Stop("not backed up by the config: ${unknown.joinToString(", ")} (android.backup.apps, android.apps.<app>.backup, android.backup.folders)")
        val dueApps = when {
            only.isNotEmpty() -> apps.filterKeys { it in only }
            auto -> dueApps()
            else -> apps
        }
        val dueFolders = when {
            only.isNotEmpty() -> folders.filter { it in only }
            auto -> folders.filter { System.currentTimeMillis() - folderState().optLong(it) >= schedule }
            else -> folders
        }
        if (dueApps.isEmpty() && dueFolders.isEmpty()) {
            if (!auto) Out.line("Backups: nothing to back up")
            return@locked 0
        }
        if (!ready()) {
            if (auto) Notify.done("Backups failed", "The repository $repository cannot be opened", failed = true)
            return@locked 1
        }
        Out.line("Backing up ${listOf(count(dueApps.size, "app"), count(dueFolders.size, "folder")).filter { !it.startsWith("0 ") }.joinToString(" and ")} into $repository")
        val failed = dueApps.count { (id, settings) -> !backupApp(id, settings) } + dueFolders.count { !backupFolder(it) }
        forget()
        handOverRepository()
        if (failed > 0 && auto) Notify.done("Backups failed", "${count(failed, "backup")} could not be made (see seraph-backup log)", failed = true)
        if (failed > 0) 1 else 0
    }

    private fun dueApps(): Map<String, Settings> {
        val usage = Device.usage(System.currentTimeMillis() - 7 * DAY)
        return apps.filter { (id, settings) -> why(id, settings, usage).first }
    }

    /** Whether [id] is due for a backup by itself, and why (or why not). */
    private fun why(id: String, settings: Settings, usage: Map<String, Device.Use>?): Pair<Boolean, String> {
        val now = System.currentTimeMillis()
        if (settings.`when` == "manual") return false to "manual: only with seraph-backup now"
        val installed = Device.versionCode(id) ?: return false to "not installed"
        val last = state().optJSONObject(id)
        val lastTime = last?.optLong("time") ?: 0L
        val use = usage?.get(id)
        return when {
            use?.foreground == true -> false to "on the screen now"
            last == null -> true to "never backed up"
            last.optLong("versionCode") != installed -> true to "updated since its last backup"
            now - lastTime >= schedule -> true to "its schedule came round"
            settings.`when` != "auto" -> false to "next on its schedule"
            now - lastTime < MIN_GAP -> false to "backed up less than an hour ago"
            use == null || use.leftAt <= lastTime -> false to "not used since its last backup"
            now - use.leftAt < quietTime -> false to "used ${ago(use.leftAt)}: waits for quietTime"
            else -> true to "used ${ago(use.leftAt)}"
        }
    }

    private fun ago(time: Long): String {
        val minutes = (System.currentTimeMillis() - time) / 60_000
        return when {
            minutes < 60 -> "$minutes min ago"
            minutes < 48 * 60 -> "${minutes / 60} h ago"
            else -> "${minutes / 1440} days ago"
        }
    }

    /** seraph-backup status: each app and folder, when it was backed up last and whether it is due. */
    fun status(): Int {
        val usage = Device.usage(System.currentTimeMillis() - 7 * DAY)
        Out.line("Repository: $repository")
        Out.line(usage?.let { "Usage stats: ${it.size} apps used in the last 7 days" } ?: "Android's usage stats cannot be read here: \"auto\" apps go by their schedule")
        for ((id, settings) in apps) {
            val last = state().optJSONObject(id)?.optLong("time")?.takeIf { it > 0 }
            val (due, reason) = why(id, settings, usage)
            Out.line("${if (due) "due " else "    "} $id (${settings.`when`}): last ${last?.let { ago(it) } ?: "never"}; $reason")
        }
        for (folder in folders) {
            val last = folderState().optLong(folder).takeIf { it > 0 }
            val due = last == null || System.currentTimeMillis() - last >= schedule
            Out.line("${if (due) "due " else "    "} $folder: last ${last?.let { ago(it) } ?: "never"}")
        }
        return 0
    }

    /** The backups android.backup.keep does not keep, of every app and folder; and once a week the space they took. */
    private fun forget() {
        val keep = cfg.optJSONObject("keep") ?: JSONObject()
        val args = mutableListOf("forget", "--host", host)
        for (name in listOf("last", "hourly", "daily", "weekly", "monthly", "yearly")) {
            val n = keep.optInt(name)
            if (n > 0) args += listOf("--keep-$name", "$n")
        }
        if (args.size == 3) return
        restic(*args.toTypedArray(), say = {})
        val pruned = folderState().optLong(PRUNED)
        if (System.currentTimeMillis() - pruned >= 7 * DAY) {
            Out.line("Freeing the space of the backups that went")
            if (restic("prune", say = {}).first == 0) {
                folderState().put(PRUNED, System.currentTimeMillis())
                saveState()
            }
        }
    }

    // --- Restoring.

    /** The newest snapshot of [id] of a version not newer than [versionCode] (any, when null). */
    private fun newest(all: List<Snapshot>, id: String, versionCode: Long?): Snapshot? = all.lastOrNull { s ->
        s.app == id && !s.warm && (versionCode == null || (s.versionCode ?: Long.MAX_VALUE) <= versionCode)
    }

    private var known: List<Snapshot>? = null
    private var toRestore: Map<String, Snapshot> = emptyMap()
    private var toInstall: Map<String, Snapshot> = emptyMap()

    /** Whether a build has anything to restore. */
    val changes get() = toRestore.isNotEmpty() || toInstall.isNotEmpty()

    /**
     * What a build restores: the data of the apps it installs ([installing]: each one's version code, when known),
     * and the apps backed up with their APK that are neither on the phone nor in the config's lists ([listed]), from
     * their backups. A repository that cannot be read keeps nothing from building: the apps come without their data.
     */
    fun planRestore(installing: Map<String, Long?>, listed: Set<String>) {
        val candidates = apps.filter { it.value.autoRestore }
        val fresh = candidates.filterKeys { it in installing }
        val missing = candidates.filter { (id, settings) -> id !in listed && id !in installing && settings.apk && Device.versionCode(id) == null }
        if (fresh.isEmpty() && missing.isEmpty()) return
        val all = try {
            snapshots()
        } catch (e: Exception) {
            Out.line("Backups: ${e.message}; the apps it installs come without their data")
            return
        }
        known = all
        toRestore = fresh.keys.mapNotNull { id -> newest(all, id, installing[id])?.let { id to it } }.toMap()
        toInstall = missing.keys.mapNotNull { id -> newest(all, id, null)?.let { id to it } }.toMap()
        if (toInstall.isNotEmpty()) {
            Out.line("To install from backups (${toInstall.size}: on no list of android.packages, not on the phone):")
            toInstall.forEach { (id, s) -> Out.line("  $id  from ${s.date}") }
        }
        if (toRestore.isNotEmpty()) {
            Out.line("To restore the data of (${toRestore.size}, once installed, unless they have data):")
            toRestore.forEach { (id, s) -> Out.line("  $id  from ${s.date}") }
        }
    }

    /** After the installs: the apps from backups, then the data of each app installed (see [planRestore]). */
    fun applyRestore(): Int = locked {
        var failed = 0
        for ((id, snapshot) in toInstall) {
            if (!installFrom(id, snapshot)) {
                failed++
                continue
            }
            if (!restoreData(id, snapshot, overwrite = false)) failed++
        }
        for ((id, snapshot) in toRestore) {
            if (Device.versionCode(id) == null) continue
            if (!restoreData(id, snapshot, overwrite = false)) failed++
        }
        failed
    }

    /** An app's data has nothing but what Android makes at its install (cache and code_cache, empty). */
    private fun hasData(id: String): Boolean = listOf("data", "deviceData").any { part ->
        File(partPath(part, id)).listFiles().orEmpty().any { it.name !in listOf("cache", "code_cache") || it.list().orEmpty().isNotEmpty() }
    }

    /**
     * Brings [id]'s data back from [snapshot]: with [overwrite] over what it has (seraph-backup restore), else only
     * when it has none yet. The app is stopped first, and what comes back is made its own (its uid, and the groups and
     * SELinux labels of the folders it goes into).
     */
    fun restoreData(id: String, snapshot: Snapshot, overwrite: Boolean): Boolean {
        val settings = apps[id]
        val uid = Device.uid(id) ?: run {
            Out.line("✘ $id is not installed: its data cannot come back")
            return false
        }
        if (!overwrite && hasData(id)) {
            Out.line("  $id has data already: left as it is (seraph-backup restore $id brings the backup's over it)")
            return true
        }
        val parts = (settings?.include ?: listOf("data", "deviceData", "storage", "media", "obb")).associateWith { partPath(it, id) }
        Device.run("am", "force-stop", "--user", "0", id)
        if (overwrite) {
            // What the backup has instead; caches stay (they are not in it).
            for (path in parts.values) File(path).listFiles().orEmpty().filter { it.name !in listOf("cache", "code_cache") }.forEach { it.deleteRecursively() }
        }
        val args = listOf("restore", "${snapshot.id}:/backup/$id", "--target", "/backup/$id") + parts.keys.flatMap { listOf("--include", "/$it") }
        // How Android made each folder for this install: restic brings back the backed-up folder's owner and label too.
        val made = parts.mapValues { (part, path) -> madeAs(part, path) }
        val (status, _) = inView(binds(id, parts) + "\ncd /\nexec restic " + args.joinToString(" ") { quote(it) })
        for ((part, path) in parts) {
            val folder = made.getValue(part)
            // A part the backup did not have leaves no empty folder behind.
            if (!folder.existed && File(path).list().isNullOrEmpty()) File(path).delete() else handOverData(path, uid, folder)
        }
        if (status != 0) {
            Out.line("✘ Could not restore the data of $id (restic exit $status)")
            return false
        }
        Out.line("✔ Restored the data of $id from ${snapshot.date}")
        return true
    }

    /** How a part's folder is to be: whether it was there, and the group, mode and SELinux label it gets. */
    private class Made(val existed: Boolean, val gid: Int?, val mode: String, val label: String, val own: Boolean)

    /**
     * Its own folders (data, deviceData) as Android made them at the install: their group, mode and label; a folder of
     * the shared storage as the one above it has them (Android/data: ext_data_rw, Android/obb: ext_obb_rw, Android/media),
     * with its uid, as apps get them there.
     */
    private fun madeAs(part: String, path: String): Made {
        val folder = File(path)
        val own = part == "data" || part == "deviceData"
        val like = if (own || folder.isDirectory) folder else folder.parentFile!!
        val existed = folder.isDirectory
        if (!like.isDirectory) return Made(existed, null, "2770", "", own)
        val stat = Os.stat(like.path)
        val mode = if (existed) Integer.toOctalString(stat.st_mode and "7777".toInt(8)) else "2770"
        return Made(existed, stat.st_gid, mode, Device.run("stat", "-c", "%C", like.path).second.trim(), own)
    }

    /**
     * Makes the files restored in [path] the app's ([uid]), with the folder's group, mode and label as Android made it
     * ([made]); its own folders' caches (left out of backups, made by the install) stay as they are.
     */
    private fun handOverData(path: String, uid: Int, made: Made) {
        val folder = File(path)
        if (!folder.isDirectory) return
        val gid = made.gid ?: uid
        val label = made.label.takeIf { it.startsWith("u:") }
        Device.run("chown", "$uid:$gid", path)
        Device.run("chmod", made.mode, path)
        label?.let { Device.run("chcon", it, path) }
        for (entry in folder.listFiles().orEmpty().filter { !made.own || it.name !in listOf("cache", "code_cache") }) {
            Device.run("chown", "-R", "$uid:$gid", entry.path)
            label?.let { Device.run("chcon", "-R", it, entry.path) }
        }
    }

    /** Installs [id] from the APKs in [snapshot] (its base APK and splits). */
    private fun installFrom(id: String, snapshot: Snapshot): Boolean {
        val into = File(tmp, "restore-$id")
        into.deleteRecursively()
        into.mkdirs()
        try {
            val (status, _) = inView("exec restic restore ${quote("${snapshot.id}:/backup/$id/apk")} --target ${quote(into.path)} --include '/*.apk'")
            val apks = into.listFiles().orEmpty().filter { it.name.endsWith(".apk") }.sortedBy { it.name != "base.apk" }
            if (status != 0 || apks.isEmpty()) {
                Out.line("✘ The backup of $id from ${snapshot.date} has no APK to install it from")
                return false
            }
            Device.installAll(apks)?.let {
                Out.line("✘ pm could not install $id from its backup: $it")
                return false
            }
            // Not one of a list of android.packages: a build does not remove it as having left the config.
            Installer(data).run { writeOwned(readOwned() - id) }
            Out.line("✔ Installed $id from its backup of ${snapshot.date}")
            return true
        } finally {
            into.deleteRecursively()
        }
    }

    /** seraph-backup restore: [id]'s data from its newest backup (or [snapshotId]) over what it has. */
    fun restoreNow(id: String, snapshotId: String?): Int = locked {
        if (Device.versionCode(id) == null) throw Stop("$id is not installed: install it first (a build installs it from its backup when its APK is there)")
        val installed = Device.versionCode(id)
        val all = snapshots(id)
        val snapshot = if (snapshotId != null) all.firstOrNull { it.id.startsWith(snapshotId) && !it.warm } ?: throw Stop("$id has no backup $snapshotId (seraph-backup list $id)")
        else newest(all, id, installed) ?: throw Stop("$id has no backup of a version not newer than the one installed (seraph-backup list $id)")
        if (snapshot.versionCode != null && installed != null && snapshot.versionCode!! > installed) {
            throw Stop("that backup is of a newer version of $id (${snapshot.versionCode}) than the one installed ($installed): update the app first")
        }
        if (restoreData(id, snapshot, overwrite = true)) 0 else 1
    }

    /** seraph-backup list: the backups, of one app or folder or all. */
    fun list(what: String?): Int {
        val all = snapshots().filter { !it.warm }
        val shown = if (what == null) all else all.filter { it.app == what || real(what) in it.paths }
        if (shown.isEmpty()) {
            Out.line("No backups${what?.let { " of $it" } ?: ""} in $repository")
            return 0
        }
        for (s in shown) {
            val name = s.app?.let { "$it (version ${s.versionCode ?: "?"})" } ?: s.paths.joinToString(", ")
            Out.line("${s.shortId}  ${s.date}  $name")
        }
        return 0
    }

    /** seraph-backup restic: restic itself, with the repository and its password. */
    fun passThrough(args: List<String>): Int {
        val process = ProcessBuilder(BUSYBOX, "sh", view.path, writePassThrough(args).path).inheritIO().start()
        return process.waitFor()
    }

    private fun writePassThrough(args: List<String>): File {
        if (!view.isFile) Units(data).writeView()
        tmp.mkdirs()
        return File(tmp, "backup-restic-${Os.getpid()}.sh").apply {
            writeText(buildString {
                append("export PATH=${quote(cfg.getString("path"))} RESTIC_REPOSITORY=${quote(repo)} RESTIC_PASSWORD_FILE=${quote(cfg.getString("passwordFile"))} RESTIC_CACHE_DIR=/var/cache/restic HOME=/var/lib/seraph-backup TMPDIR=${quote("${tmp.path}/restic")}\n")
                append("mkdir -p \"\$TMPDIR\" \"\$HOME\"\n")
                cfg.optString("environmentFile").takeIf { it.isNotEmpty() && it != "null" }?.let { append("set -a; . ${quote(it)}; set +a\n") }
                append("rm -f ${quote(path)}\n")
                append("exec restic " + args.joinToString(" ") { quote(it) } + "\n")
            })
        }
    }

    // --- What was backed up when.

    private var stateJson: JSONObject? = null

    private fun loadState(): JSONObject = stateJson ?: (runCatching { JSONObject(stateFile.readText()) }.getOrNull() ?: JSONObject()).also { stateJson = it }

    private fun state(): JSONObject = loadState().optJSONObject("apps") ?: JSONObject().also { loadState().put("apps", it) }

    private fun folderState(): JSONObject = loadState().optJSONObject("folders") ?: JSONObject().also { loadState().put("folders", it) }

    private fun saveState() {
        dir.mkdirs()
        stateFile.writeText(loadState().toString())
    }

    /** One backup or restore at a time; a build waits for a backup running. */
    private fun <T> locked(work: () -> T): T {
        dir.mkdirs()
        val channel = RandomAccessFile(File(dir, "lock"), "rw").channel
        val lock = channel.tryLock() ?: run {
            Out.line("Waiting for the backup that is running")
            channel.lock()
        }
        try {
            return work()
        } finally {
            lock.release()
            channel.close()
        }
    }

    private fun count(n: Int, what: String) = if (n == 1) "1 $what" else "$n ${what}s"

    companion object {
        private const val APP_TAG = "seraph-app"
        private const val FOLDER_TAG = "seraph-folder"
        private const val WARM = "seraph-warm"
        private const val PRUNED = "#pruned"
        /** restic's exit status when there is no repository. */
        private const val NO_REPOSITORY = 10
        private const val DAY = 86_400_000L
        /** An "auto" app is backed up once an hour at most. */
        private const val MIN_GAP = 3_600_000L
        private val BUSYBOX = listOf("/data/adb/magisk/busybox", "/data/adb/ksu/bin/busybox", "/data/adb/ap/bin/busybox")
            .firstOrNull { File(it).canExecute() } ?: "/data/adb/magisk/busybox"

        /** The backups of [android] (a profile's android part), or null when it has none. */
        fun of(data: File, android: JSONObject?): Backup? =
            android?.optJSONObject("backup")?.let { Backup(data, it) }

        /** The backups of the last build. */
        fun ofLastBuild(data: File): Backup? =
            of(data, runCatching { JSONObject(File(data, "profile.json").readText()).optJSONObject("android") }.getOrNull())

        private fun quote(text: String) = "'" + text.replace("'", "'\\''") + "'"

        private fun JSONArray.strings() = (0 until length()).map { getString(it) }
    }
}
