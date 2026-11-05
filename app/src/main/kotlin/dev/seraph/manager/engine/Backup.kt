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
 * and the version code it was made of. An app whose processes are all idle (cached, as Android keeps an app that
 * left the screen) has them stopped while its files are read (SIGSTOP, then SIGCONT), so the copy is whole; one in
 * use (on the screen, or with a service running) is read as it is, without stopping it: a stopped process that the
 * system calls would hold the system up, and the whole phone with it.
 *
 * Before a backup, what it needs is checked ([preflight]): the repository's storage there (an SD card in) and room
 * on it, and the phone not short of memory or hot; an automatic one that cannot run now waits for the next check.
 *
 * With autoRestore (off unless the config turns it on), a build restores the data of the apps it installs from the
 * newest snapshot of a version not newer than the one installed, never over data the app already has (see
 * [planRestore]), and installs from their snapshots the apps backed up with their APK that are not on the phone; an
 * app this phone backed up and that was removed since stays removed: it comes back only by hand.
 *
 * By hand (seraph-backup, the app's Backups tab), any installed app or folder can be backed up too, with the parts
 * named, and an app's APK and any of its data parts, or a folder, brought back from any of its snapshots. An app that
 * is not on the phone comes back whole: its APK first (from that snapshot, else from another of its snapshots, see
 * [apkBackup]), then its data; with no APK in any of them, it is not found.
 */
class Backup private constructor(
    private val data: File,
    private val cfg: JSONObject,
    /** What was backed up when, and the lock: the module's data, or a test profile's own folder (SERAPH_PROFILE). */
    private val dir: File = File(data, "backup"),
) {
    class Settings(val apk: Boolean, val data: Boolean, val include: List<String>, val `when`: String, val autoRestore: Boolean) {
        /** What a backup with these settings takes: apk, then the data parts. */
        val parts: List<String> get() = (if (apk) listOf("apk") else emptyList()) + (if (data) include else emptyList())
    }

    class Snapshot(val id: String, val shortId: String, val time: Long, val tags: List<String>, val paths: List<String>) {
        val app: String? get() = tags.firstOrNull { it.startsWith("app:") }?.removePrefix("app:")
        val versionCode: Long? get() = tags.firstOrNull { it.startsWith("versionCode:") }?.removePrefix("versionCode:")?.toLongOrNull()
        val versionName: String? get() = tags.firstOrNull { it.startsWith("versionName:") }?.removePrefix("versionName:")
        /** The parts it has (apk, data, ...), as its tags say; null for one made before they were tagged (see [partsOf]). */
        val parts: List<String>? get() = tags.filter { it.startsWith("part:") }.map { it.removePrefix("part:") }.ifEmpty { null }
        val warm: Boolean get() = WARM in tags
        val date: String get() = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.ROOT).format(java.util.Date(time))
    }

    val apps: Map<String, Settings> = cfg.optJSONObject("apps")?.let { json ->
        json.keys().asSequence().associateWith { id -> settingsOf(json.getJSONObject(id)) }
    } ?: emptyMap()
    /** What an app the config does not back up is backed up with, when it is named by hand: android.backup.defaults. */
    private val defaults = settingsOf(cfg.optJSONObject("defaults") ?: JSONObject())
    private val folders = cfg.optJSONArray("folders")?.strings() ?: emptyList()
    private val repository = cfg.getString("repository")
    private val host = cfg.optString("host").takeIf { it.isNotEmpty() && it != "null" } ?: Device.settings("global")["device_name"] ?: "phone"
    private val schedule = cfg.optLong("schedule", 86400) * 1000
    private val quietTime = cfg.optLong("quietTime", 600) * 1000

    private fun settingsOf(app: JSONObject) = Settings(
        app.optBoolean("apk", true), app.optBoolean("data", true),
        app.optJSONArray("include")?.strings() ?: listOf("data", "deviceData", "storage"),
        app.optString("when", "auto"), app.optBoolean("autoRestore", false),
    )

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

    /** A folder as the phone names it ([real] the other way): /data/media/0/DCIM is /storage/emulated/0/DCIM; the config's own name, when it is one of its folders. */
    private fun shown(path: String): String {
        folders.firstOrNull { real(it) == path }?.let { return it }
        Regex("^/data/media/(\\d+)(/.*)?$").find(path)?.let { return "/storage/emulated/${it.groupValues[1]}${it.groupValues[2]}" }
        Regex("^/mnt/media_rw/([^/]+)(/.*)?$").find(path)?.let { return "/storage/${it.groupValues[1]}${it.groupValues[2]}" }
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
            // Less memory at its peak (Go's collector runs twice as often), for the apps of the phone.
            "GOGC" to "50",
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
        if (status == 0) {
            // The locks of a backup a restart (or a kill) broke off, which would hold up forget.
            restic("unlock", say = {})
            return true
        }
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

    /** Whether a process is idle as Android keeps it: cached (oom_score_adj 900 and up), not on the screen or serving. */
    private fun cached(pid: Int) = runCatching { File("/proc/$pid/oom_score_adj").readText().trim().toInt() >= CACHED_ADJ }.getOrDefault(true)

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
        if (settings.data && hidden(id)) {
            Out.line("✘ $id: its data folder is out of sight here (an app's view of the phone, which hides the other apps' data): not backed up")
            return false
        }
        val parts = LinkedHashMap<String, String>()
        if (settings.apk) apkDir(id)?.let { parts["apk"] = it }
        if (settings.data) for (part in settings.include) partPath(part, id).takeIf { File(it).isDirectory }?.let { parts[part] = it }
        if (parts.isEmpty()) {
            Out.line("  $id has nothing to back up")
            return true
        }
        val setup = binds(id, parts)
        // Its version, and the parts it has, so a restore can say what it brings back (a comma would split a tag).
        val tags = listOf(APP_TAG, "app:$id", "versionCode:${installed.versionCode}") +
            listOfNotNull(installed.versionName.trim().ifEmpty { null }?.let { "versionName:" + it.replace(',', '.') }) +
            parts.keys.map { "part:$it" }
        // Stopped while it is read only when all its processes are idle; an app in use is read as it is.
        val pids = Device.uid(id)?.takeIf { it >= 10000 }?.let { pidsOf(it) } ?: emptyList()
        val idle = pids.all { cached(it) }
        if (!idle) Out.line("  $id is in use: read as it is, without stopping it")
        val result = stopped(if (idle) pids else emptyList()) { backup(setup, listOf("/backup/$id"), tags, excludes(id)) }
        if (result == null) {
            Out.line("✘ Could not back up $id")
            return false
        }
        state().apply {
            put(id, JSONObject().put("time", System.currentTimeMillis()).put("versionCode", installed.versionCode).put("size", sizeOf(parts.values)))
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
        folderSizes().put(folder, sizeOf(listOf(path)))
        saveState()
        Out.line(if (result.skipped) "✔ $folder: nothing changed since its last backup" else "✔ Backed up $folder (${Out.size(result.added)} new)")
        return true
    }

    /**
     * Backs up the apps and folders named ([only]; none: all of the config's), or with [auto] those that are due:
     * an "auto" app once it was left in the background for quietTime (and at least once a schedule), a "schedule"
     * one once a schedule, any but a "manual" one after it was updated, and the folders once a schedule. One named
     * by hand need not be the config's: any installed app (with android.backup.defaults) or folder of the phone.
     * [parts] (apk, data, deviceData, storage, media, obb) is what to take of each app instead of its settings.
     */
    fun run(only: List<String>, auto: Boolean, parts: List<String>? = null): Int = locked {
        if (!Device.unlocked()) {
            // The job comes back every 15 minutes: it waits quietly.
            if (auto) {
                Out.line("Backups: waiting for the phone's first unlock since it started (the apps' data is out of reach until then)")
                return@locked 0
            }
            throw Stop("the phone was not unlocked since it started: the apps' data is out of reach until then")
        }
        thawLeftovers()
        if (auto && cfg.optBoolean("onlyWhileCharging") && !Device.charging()) {
            Out.line("Backups: only while charging, and the phone is not")
            return@locked 0
        }
        parts?.let { checkParts(it, PARTS) }
        val named = only.map { if (it.length > 1) it.trimEnd('/') else it }
        val unknown = named.filter { it !in apps && !it.startsWith("/") && Device.versionCode(it) == null }
        if (unknown.isNotEmpty()) throw Stop("neither an installed app nor a folder (a full path): ${unknown.joinToString(", ")}")
        val take = { settings: Settings ->
            if (parts == null) settings
            else Settings("apk" in parts, parts.any { it != "apk" }, parts.filter { it != "apk" }, settings.`when`, settings.autoRestore)
        }
        val dueApps = when {
            named.isNotEmpty() -> named.filter { !it.startsWith("/") }.associateWith { take(apps[it] ?: defaults) }
            auto -> dueApps()
            else -> apps.mapValues { take(it.value) }
        }
        val dueFolders = when {
            named.isNotEmpty() -> named.filter { it.startsWith("/") }
            auto -> folders.filter { System.currentTimeMillis() - folderState().optLong(it) >= schedule }
            else -> folders
        }
        if (dueApps.isEmpty() && dueFolders.isEmpty()) {
            if (!auto) Out.line("Backups: nothing to back up")
            return@locked 0
        }
        // What the backup needs, there before it starts: an automatic one waits for the next check, one by hand says why.
        preflight(dueApps, dueFolders, auto)?.let { why ->
            if (!auto) throw Stop("the backup cannot run now: $why", TEMPORARY)
            waiting(why)
            return@locked 0
        }
        if (loadState().has("waiting")) {
            loadState().remove("waiting")
            saveState()
        }
        // Told to the app: when it starts, and how many succeeded and failed (an automatic one only when something was due).
        val what = listOf(count(dueApps.size, "app"), count(dueFolders.size, "folder")).filter { !it.startsWith("0 ") }.joinToString(" and ")
        val label = if (auto) "Automatic backup" else "Backup"
        Notify.event("backup", "start", "$label: backing up $what", (dueApps.keys + dueFolders).joinToString(", "))
        if (!ready()) {
            Notify.event("backup", "error", "$label failed", "The repository $repository cannot be opened")
            return@locked 1
        }
        Out.line("Backing up $what into $repository")
        val failedApps = dueApps.filter { (id, settings) -> !backupApp(id, settings) }.keys
        val failedFolders = dueFolders.filter { !backupFolder(it) }
        val failed = failedApps.size + failedFolders.size
        val succeeded = dueApps.size + dueFolders.size - failed
        forget()
        handOverRepository()
        when {
            failed == 0 -> Notify.event("backup", "ok", "$label finished: $succeeded succeeded", (dueApps.keys + dueFolders).joinToString(", "))
            else -> Notify.event(
                "backup", if (succeeded == 0) "error" else "warn", "$label finished: $succeeded succeeded, $failed failed",
                "Failed: ${(failedApps + failedFolders).joinToString(", ")} (the Logs tab has why)",
            )
        }
        if (failed > 0) 1 else 0
    }

    /**
     * What keeps a backup of [apps] and [folders] from running now, or null: the repository's storage not there (an SD
     * card taken out) or without room for what is new (what was never backed up, and what grew since its last backup,
     * with some room always kept free); and for an automatic one, the phone short of memory or hot, unless it has been
     * waiting 6 hours already.
     */
    private fun preflight(apps: Map<String, Settings>, folders: List<String>, auto: Boolean): String? {
        if (repository.startsWith("/")) {
            // An SD card's (or USB storage's) repository: its card must be in.
            Regex("^/storage/([^/]+)/").find(repository)?.groupValues?.get(1)?.takeIf { it != "emulated" && it != "self" }?.let { card ->
                if (!File("/mnt/media_rw/$card").isDirectory) return "the storage of the repository ($card, an SD card or USB storage) is not there"
            }
            val there = generateSequence(File(repo)) { it.parentFile }.firstOrNull { it.exists() } ?: return "the repository's folder is not there"
            val stat = runCatching { android.os.StatFs(there.path) }.getOrNull() ?: return null
            val free = stat.availableBytes
            val keep = maxOf(256L shl 20, stat.totalBytes / 50)
            val need = apps.entries.sumOf { (id, settings) -> grown(id, settings) } + folders.sumOf { grownFolder(it) }
            if (free < need + keep) {
                return "the repository's storage has ${Out.size(free)} free; this backup needs about ${Out.size(need)}, and ${Out.size(keep)} stays free"
            }
        }
        if (!auto) return null
        val since = loadState().optJSONObject("waiting")?.optLong("since") ?: 0L
        if (since > 0 && System.currentTimeMillis() - since > 6 * HOUR) return null
        val memory = runCatching {
            File("/proc/meminfo").readLines().associate { line -> line.substringBefore(':') to (line.substringAfter(':').trim().substringBefore(' ').toLongOrNull() ?: 0L) }
        }.getOrNull()
        val total = memory?.get("MemTotal") ?: 0L
        val available = memory?.get("MemAvailable") ?: total
        if (total > 0 && available < total / 20) return "the phone is short of memory (${Out.size(available * 1024)} free)"
        val thermal = Regex("Thermal Status: (\\d+)").find(Device.run("dumpsys", "thermalservice").second)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        if (thermal >= 3) return "the phone is hot (thermal status $thermal)"
        return null
    }

    /** What is new in [id] since its last backup, about: what it has now less what it had then (all of it, the first time). */
    private fun grown(id: String, settings: Settings): Long {
        if (Device.versionCode(id) == null) return 0
        val paths = (if (settings.apk) listOfNotNull(apkDir(id)) else emptyList()) +
            (if (settings.data) settings.include.map { partPath(it, id) }.filter { File(it).isDirectory } else emptyList())
        val now = sizeOf(paths)
        val then = state().optJSONObject(id)?.optLong("size", -1L) ?: -1L
        return if (then < 0) now else maxOf(0L, now - then)
    }

    private fun grownFolder(folder: String): Long {
        val path = real(folder)
        if (!File(path).isDirectory) return 0
        val now = sizeOf(listOf(path))
        val then = folderSizes().optLong(folder, -1L)
        return if (then < 0) now else maxOf(0L, now - then)
    }

    /** The size of [paths] on disk, in bytes. */
    private fun sizeOf(paths: Collection<String>): Long {
        if (paths.isEmpty()) return 0
        return Device.run("du", "-sk", *paths.toTypedArray()).second.lineSequence()
            .sumOf { it.substringBefore('\t').trim().toLongOrNull() ?: 0L } * 1024
    }

    /**
     * An automatic backup waits: kept in the state (the Backups tab says it), and told when the reason is new; a full
     * or missing storage, which needs the user, is told in a notification too (once a day at most).
     */
    private fun waiting(why: String) {
        val now = System.currentTimeMillis()
        val before = loadState().optJSONObject("waiting")
        val storage = why.contains("storage")
        val told = before?.optLong("told") ?: 0L
        val tell = storage && (before?.optString("reason") != why || now - told > DAY)
        loadState().put(
            "waiting",
            JSONObject().put("reason", why).put("since", before?.optLong("since")?.takeIf { it > 0 } ?: now).put("told", if (tell) now else told),
        )
        saveState()
        Out.line("Automatic backup waits: $why")
        if (before?.optString("reason") != why || tell) Notify.event("backup", "warn", "Automatic backup waits", "${why.replaceFirstChar { it.uppercase() }}; it tries again at the next check", quiet = !tell)
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

    /** The same as [status], for the app's Backups tab: and what an app named by hand is backed up with (defaults). */
    fun statusJson(): JSONObject {
        val usage = Device.usage(System.currentTimeMillis() - 7 * DAY)
        val appList = JSONArray()
        for ((id, settings) in apps) {
            val (due, reason) = why(id, settings, usage)
            appList.put(
                JSONObject().put("id", id).put("when", settings.`when`).put("parts", JSONArray(settings.parts))
                    .put("last", state().optJSONObject(id)?.optLong("time") ?: 0L).put("due", due).put("reason", reason),
            )
        }
        val folderList = JSONArray()
        for (folder in folders) {
            val last = folderState().optLong(folder)
            folderList.put(JSONObject().put("path", folder).put("last", last).put("due", System.currentTimeMillis() - last >= schedule))
        }
        return JSONObject().put("configured", true).put("repository", repository).put("host", host)
            .put("defaults", JSONArray(defaults.parts)).put("apps", appList).put("folders", folderList)
            .put("unlocked", Device.unlocked())
            .put("waiting", loadState().optJSONObject("waiting")?.optString("reason").orEmpty())
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
        // An app this phone backed up and that is not on it now was removed: it comes back only by hand.
        val removed = candidates.keys.filter { state().has(it) && Device.versionCode(it) == null }.toSet()
        if (removed.isNotEmpty()) Out.line("Backups: not brought back by themselves, as they were removed since this phone backed them up (the Backups tab restores them): ${removed.joinToString(", ")}")
        val fresh = candidates.filterKeys { it in installing && it !in removed }
        val missing = candidates.filter { (id, settings) -> id !in removed && id !in listed && id !in installing && settings.apk && Device.versionCode(id) == null }
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

    /**
     * Whether the installed [id]'s own data folder, which Android makes at its install, is out of sight: so it is in
     * an app's view of the phone (a root shell an app started), where Android hides the other apps' data folders.
     */
    private fun hidden(id: String): Boolean = !File(partPath("data", id)).isDirectory && Device.uid(id) != null

    /** An app's data has nothing but what Android makes at its install (cache and code_cache, empty). */
    private fun hasData(id: String): Boolean = listOf("data", "deviceData").any { part ->
        File(partPath(part, id)).listFiles().orEmpty().any { it.name !in listOf("cache", "code_cache") || it.list().orEmpty().isNotEmpty() }
    }

    /**
     * Brings [id]'s data back from [snapshot]: with [overwrite] over what it has (seraph-backup restore), else only
     * when it has none yet. The app is stopped first, and what comes back is made its own (its uid, and the groups and
     * SELinux labels of the folders it goes into).
     */
    fun restoreData(id: String, snapshot: Snapshot, overwrite: Boolean, only: List<String>? = null): Boolean {
        val uid = Device.uid(id) ?: run {
            Out.line("✘ $id is not installed: its data cannot come back")
            return false
        }
        if (hidden(id)) {
            Out.line("✘ $id: its data folder is out of sight here (an app's view of the phone, which hides the other apps' data): its data cannot come back")
            return false
        }
        if (!overwrite && hasData(id)) {
            Out.line("  $id has data already: left as it is (seraph-backup restore $id brings the backup's over it)")
            return true
        }
        // [only], else the config's parts of it; of those, the ones the backup has: a part it has not got is not emptied.
        val has = snapshot.parts
        val parts = (only ?: apps[id]?.include ?: DATA_PARTS).filter { it != "apk" && (has == null || it in has) }.associateWith { partPath(it, id) }
        if (parts.isEmpty()) {
            Out.line("  The backup of $id from ${snapshot.date} has none of its data")
            return true
        }
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

    /**
     * seraph-backup restore <app>: from its newest backup with data of a version not newer than the one installed (see
     * [newestWithData]; or [snapshotId]), its data over what it has, and its APK when it is not installed. With
     * [parts], the parts named: apk installs it from the backup (when it is not installed, or an older version is), and
     * each data part replaces what the app has of it. An app that is not installed comes back whole, its APK first,
     * then its data, apk named or not; the APK comes from that backup, else from another of the app's ([apkBackup]),
     * and with none it is not found. Every check comes first: when one fails, nothing has changed.
     */
    fun restoreNow(id: String, snapshotId: String?, parts: List<String>? = null): Int = locked {
        if (!Device.unlocked()) throw Stop("the phone was not unlocked since it started: the apps' data is out of reach until then")
        parts?.let { checkParts(it, PARTS) }
        val installed = Device.versionCode(id)
        val all = snapshots(id).filter { !it.warm }
        val snapshot = if (snapshotId != null) all.firstOrNull { it.id.startsWith(snapshotId) } ?: throw Stop("$id has no backup $snapshotId (seraph-backup list $id)")
        else newestWithData(all, id, installed) ?: throw Stop(
            if (all.isEmpty()) "$id has no backups (seraph-backup list)" else "$id has no backup of a version not newer than the one installed (seraph-backup list $id)",
        )
        val has = partsOf(id, snapshot)
        // Its data alone cannot come back without the app: one not installed gets its APK first.
        val wanted = (parts ?: (apps[id]?.include ?: DATA_PARTS)).let { if (installed == null && "apk" !in it) listOf("apk") + it else it }
        val missing = wanted.filter { it != "apk" && it !in has }
        if (parts != null && missing.isNotEmpty()) {
            throw Stop("the backup of $id from ${snapshot.date} has no ${missing.joinToString(", ")} (it has ${has.joinToString(", ").ifEmpty { "nothing" }})")
        }
        val dataParts = wanted.filter { it != "apk" && it in has }
        val apk = if ("apk" in wanted) apkBackup(id, snapshot, has, all) else null
        if ("apk" in wanted && apk == null) {
            throw Stop(if (installed == null) notFound(id, snapshot, all) else "no backup of $id has its APK${snapshot.versionCode?.let { " (version $it or newer)" } ?: ""} (seraph-backup list $id)")
        }
        if (apk == null && dataParts.isEmpty()) throw Stop("the backup of $id from ${snapshot.date} has none of that (it has ${has.joinToString(", ").ifEmpty { "nothing" }})")
        val backed = snapshot.versionCode
        val apkVersion = apk?.versionCode
        val installs = apk != null && (installed == null || apkVersion == null || apkVersion > installed)
        if (apk != null && installed != null && apkVersion != null && apkVersion < installed) {
            throw Stop("the installed $id (version $installed) is newer than its backup's ($apkVersion), and Android installs no older version over a newer one: uninstall it first, then restore it with its APK and data")
        }
        if (dataParts.isNotEmpty()) {
            if (installed != null && hidden(id)) throw Stop("$id: its data folder is out of sight here (an app's view of the phone, which hides the other apps' data)")
            val after = if (installs) apkVersion else installed
            if (backed != null && after != null && backed > after) {
                throw Stop("that backup is of a newer version of $id ($backed) than the one installed ($after): update the app first, or restore its APK too")
            }
        }
        if (apk != null && !installs) Out.line("  $id is at its backup's version already: its APK is not installed again")
        if (installs && apk !== snapshot) Out.line("  The backup of ${snapshot.date} has no APK of $id: it comes from its backup of ${apk!!.date}")
        if (installs && !installFrom(id, apk!!)) return@locked 1
        if (dataParts.isNotEmpty() && !restoreData(id, snapshot, overwrite = true, only = dataParts)) return@locked 1
        0
    }

    /**
     * The backup [id]'s APK comes from, for the data of [snapshot] (which [has] these parts), of [all] the app's: that
     * one, when it has the APK; else the newest other with the APK of the same version, or else of a newer version (an
     * app reads the data of its own version or an older one, never that of a newer one). Null when none has it. The
     * parts of a backup made before they were tagged are asked of restic, and only as far as needed.
     */
    private fun apkBackup(id: String, snapshot: Snapshot, has: List<String>, all: List<Snapshot>): Snapshot? {
        if ("apk" in has) return snapshot
        val version = snapshot.versionCode
        return all.filter { it.id != snapshot.id && (version == null || (it.versionCode ?: Long.MAX_VALUE) >= version) }
            .sortedWith(compareBy<Snapshot> { if (version != null && it.versionCode == version) 0 else 1 }.thenByDescending { it.time })
            .firstOrNull { hasApk(id, it) }
    }

    private fun hasApk(id: String, snapshot: Snapshot): Boolean = "apk" in knownParts(id, snapshot)

    /** The parts of [snapshot] of [id], as [partsOf] has them; none when it cannot be read. */
    private fun knownParts(id: String, snapshot: Snapshot): List<String> = snapshot.parts ?: runCatching { partsOf(id, snapshot) }.getOrDefault(emptyList())

    /**
     * What a restore by the app's name alone takes, of [all] its backups: the newest of a version not newer than the
     * one installed (of any, when it is not installed) that has some of its data, else the newest of those: a backup
     * of its APK alone, newer than its data's, does not hide its data (the APK comes from wherever it is, see [apkBackup]).
     */
    private fun newestWithData(all: List<Snapshot>, id: String, installed: Long?): Snapshot? {
        val fit = all.filter { it.app == id && (installed == null || (it.versionCode ?: Long.MAX_VALUE) <= installed) }
        return fit.lastOrNull { snapshot -> knownParts(id, snapshot).any { it != "apk" } } ?: fit.lastOrNull()
    }

    /** Why [id], which is not installed, cannot come back with the data of [snapshot]: no backup of it ([all]) has an APK that reads it. */
    private fun notFound(id: String, snapshot: Snapshot, all: List<Snapshot>): String {
        val version = snapshot.versionCode
        val older = if (version == null) null else all.lastOrNull { (it.versionCode ?: Long.MAX_VALUE) < version && hasApk(id, it) }
        return "app not found: $id is not installed, and " +
            (if (older == null) "none of its backups has its APK" else "its backups have only an older APK (version ${older.versionCode}) than this data's ($version)") +
            ": install it first, then restore its data"
    }

    /**
     * seraph-backup restore <folder>: its files from its newest backup (or [snapshotId]) back into it, over the ones
     * there that the backup has (the others stay); or, with [to], into that folder, which is new. On the shared storage
     * they become the storage's own (its owner and SELinux label), and Android's media store is told about them.
     */
    fun restoreFolder(folder: String, snapshotId: String?, to: String?): Int = locked {
        if (!Device.unlocked()) throw Stop("the phone was not unlocked since it started: the shared storage is out of reach until then")
        val source = real(folder.trimEnd('/'))
        val all = snapshots().filter { !it.warm && it.app == null && source in it.paths }
        val snapshot = (if (snapshotId != null) all.firstOrNull { it.id.startsWith(snapshotId) } else all.lastOrNull())
            ?: throw Stop("there is no backup${snapshotId?.let { " $it" } ?: ""} of $folder (seraph-backup list $folder)")
        val target = File(to?.let { real(it.trimEnd('/')) } ?: source)
        if (to != null && !target.path.startsWith("/")) throw Stop("$to is not a full path")
        if (to != null && target.list()?.isNotEmpty() == true) throw Stop("$to is there already, with files in it: name a new folder")
        // The folder it goes into, or the nearest one above it there is, says whose the files become.
        val like = generateSequence(target) { it.parentFile }.first { it.isDirectory }
        val stat = Os.stat(like.path)
        val label = Device.run("stat", "-c", "%C", like.path).second.trim()
        target.mkdirs()
        Out.line("Restoring ${shown(source)} from ${snapshot.date} into ${shown(target.path)}")
        val (status, _) = inView("exec restic restore ${quote("${snapshot.id}:$source")} --target ${quote(target.path)}")
        // An SD card's file system has no owners or labels; the phone's storage has.
        if (target.path.startsWith("/data/media/")) {
            Device.run("chown", "-R", "${stat.st_uid}:${stat.st_gid}", target.path)
            if (label.startsWith("u:")) Device.run("chcon", "-R", label, target.path)
        }
        scan(shown(target.path))
        if (status != 0) {
            Out.line("✘ Could not restore ${shown(source)} (restic exit $status)")
            return@locked 1
        }
        Out.line("✔ Restored ${shown(source)} from ${snapshot.date}" + (to?.let { " into $it" } ?: ""))
        0
    }

    /** Tells Android's media store about the files in [folder] (its /storage path), so galleries and players see them. */
    private fun scan(folder: String) {
        if (!folder.startsWith("/storage/")) return
        val uri = "file://" + java.net.URI(null, null, folder, null).rawPath
        Device.run("am", "broadcast", "-f", "0x01000000", "-a", "android.intent.action.MEDIA_SCANNER_SCAN_FILE", "-d", uri)
    }

    /** The parts [snapshot] of [id] has: as its tags say, or for one made before they were tagged, as restic lists its folder. */
    fun partsOf(id: String, snapshot: Snapshot): List<String> = snapshot.parts ?: run {
        val (status, out) = restic("ls", "--json", snapshot.id, "/backup/$id", say = {})
        if (status != 0) throw Stop("the backup ${snapshot.shortId} of $id cannot be read (restic exit $status)")
        val prefix = "/backup/$id/"
        out.lineSequence().mapNotNull { runCatching { JSONObject(it).optString("path") }.getOrNull() }
            .filter { it.startsWith(prefix) && '/' !in it.removePrefix(prefix) }.map { it.removePrefix(prefix) }
            .filter { it in PARTS }.distinct().sortedBy { PARTS.indexOf(it) }.toList()
    }

    /**
     * The parts of one backup of [id], and the backup its APK would come from (see [apkBackup]; none when no backup has
     * it), for the app's restore.
     */
    fun partsJson(id: String, snapshotId: String): JSONObject {
        val all = snapshots(id).filter { !it.warm }
        val snapshot = all.firstOrNull { it.id.startsWith(snapshotId) } ?: throw Stop("$id has no backup $snapshotId (seraph-backup list $id)")
        val parts = partsOf(id, snapshot)
        val apk = apkBackup(id, snapshot, parts, all)
        return JSONObject().put("parts", JSONArray(parts)).put(
            "apk",
            apk?.let { JSONObject().put("id", it.id).put("time", it.time).put("versionCode", it.versionCode).put("versionName", it.versionName) },
        )
    }

    private fun checkParts(parts: List<String>, allowed: List<String>) {
        val wrong = parts.filter { it !in allowed }
        if (parts.isEmpty() || wrong.isNotEmpty()) throw Stop("parts: ${wrong.joinToString(", ").ifEmpty { "none named" }}: name some of ${allowed.joinToString(", ")}")
    }

    /** seraph-backup list: the backups, of one app or folder or all. */
    fun list(what: String?): Int {
        val found = listed(what)
        if (found.isEmpty()) {
            Out.line("No backups${what?.let { " of $it" } ?: ""} in $repository")
            return 0
        }
        for (s in found) {
            val version = listOfNotNull(s.versionName, s.versionCode?.let { "($it)" }).joinToString(" ").ifEmpty { "?" }
            val name = s.app?.let { "$it, version $version" + (s.parts?.let { parts -> ": ${parts.joinToString(", ")}" } ?: "") }
                ?: s.paths.joinToString(", ") { shown(it) }
            Out.line("${s.shortId}  ${s.date}  $name")
        }
        return 0
    }

    /** The same as [list], for the app's Backups tab. */
    fun listJson(what: String?): JSONObject = JSONObject().put("configured", true).put("repository", repository).put(
        "snapshots",
        JSONArray(listed(what).map { s ->
            JSONObject().put("id", s.id).put("short", s.shortId).put("time", s.time).put("app", s.app)
                .put("versionCode", s.versionCode).put("versionName", s.versionName).put("parts", s.parts?.let { JSONArray(it) })
                .put("folder", if (s.app == null) s.paths.firstOrNull()?.let { shown(it) } else null)
        }),
    )

    private fun listed(what: String?): List<Snapshot> {
        val all = snapshots().filter { !it.warm }
        return if (what == null) all else all.filter { it.app == what || real(what.trimEnd('/')) in it.paths }
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

    private fun folderSizes(): JSONObject = loadState().optJSONObject("folderSizes") ?: JSONObject().also { loadState().put("folderSizes", it) }

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
        /** An app's parts, as a backup has them: its APKs, then its data. */
        private val PARTS = listOf("apk", "data", "deviceData", "storage", "media", "obb")
        private val DATA_PARTS = PARTS - "apk"
        private const val APP_TAG = "seraph-app"
        private const val FOLDER_TAG = "seraph-folder"
        private const val WARM = "seraph-warm"
        /** An app process's oom_score_adj from which Android keeps it cached: idle, off the screen, serving nothing. */
        private const val CACHED_ADJ = 900
        /** The exit status of a backup that cannot run now (its storage, ...), to try again later (EX_TEMPFAIL). */
        const val TEMPORARY = 75
        private const val HOUR = 3_600_000L
        private const val PRUNED = "#pruned"
        /** restic's exit status when there is no repository. */
        private const val NO_REPOSITORY = 10
        private const val DAY = 86_400_000L
        /** An "auto" app is backed up once an hour at most. */
        private const val MIN_GAP = 3_600_000L
        private val BUSYBOX = listOf("/data/adb/magisk/busybox", "/data/adb/ksu/bin/busybox", "/data/adb/ap/bin/busybox")
            .firstOrNull { File(it).canExecute() } ?: "/data/adb/magisk/busybox"

        /** The backups of [android] (a profile's android part), or null when it has none. */
        fun of(data: File, android: JSONObject?): Backup? = android?.optJSONObject("backup")?.let { cfg ->
            // A test profile (SERAPH_PROFILE) keeps its own record, as with ofLastBuild.
            val test = System.getenv("SERAPH_PROFILE")?.takeIf { it.isNotEmpty() }?.let { File(it) }
            if (test != null) Backup(data, cfg, File(test.absoluteFile.parentFile, "backup-state")) else Backup(data, cfg)
        }

        /**
         * The backups of the last build; or, for one command (tests), of the profile SERAPH_PROFILE names, which keeps
         * what it backed up when in a folder next to it, so the phone's own backups go on as they were.
         */
        fun ofLastBuild(data: File): Backup? {
            val test = System.getenv("SERAPH_PROFILE")?.takeIf { it.isNotEmpty() }?.let { File(it) }
            val android = runCatching { JSONObject((test ?: File(data, "profile.json")).readText()).optJSONObject("android") }.getOrNull()
            val cfg = android?.optJSONObject("backup") ?: return null
            return if (test != null) Backup(data, cfg, File(test.absoluteFile.parentFile, "backup-state")) else Backup(data, cfg)
        }

        private fun quote(text: String) = "'" + text.replace("'", "'\\''") + "'"

        private fun JSONArray.strings() = (0 until length()).map { getString(it) }
    }
}
