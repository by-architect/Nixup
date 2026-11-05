package dev.seraph.manager.engine

import android.system.Os
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.concurrent.TimeUnit

/**
 * The config's services: its systemd.services units (Seraph's own services, and NixOS's service modules, make them; see
 * nix/units.nix), run as systemd runs them on NixOS. Each runs in Seraph's view of the phone ([VIEW]): the phone as it
 * is, with /nix (Seraph's store, where the build fetched the units' packages), Seraph's /etc (see [Etc]), /var and /run
 * (the units' state, in /data/adb/seraph/var and /data/adb/seraph/run) and a /tmp of its own; as root, or as its user
 * (serviceConfig.User, DynamicUser), through setpriv, with its folders its own ([prepareDirectories]). A simple unit
 * runs under a shell loop ([SUPERVISOR]) that starts it again as its Restart says; a oneshot runs to its end, and what
 * it says is shown. Like the settings, they are planned and checked first ([plan]) and changed after ([apply]): the
 * units that left the config or changed are stopped, then the ones to start start, in order (after, before, wants,
 * requires). At boot ([start]) the units the last build left start again; when the module goes ([removeAll]) they all
 * stop. A unit's state (/var/lib) stays when it leaves the config, as on NixOS; the module's removal takes it.
 */
class Units(private val data: File) {
    private val dir = File(data, "units")
    private val view = File(dir, "view.sh")
    private val background = File(dir, "background.sh")
    private val varDir = File(data, "var")
    private val runDir = File(data, "run")
    /** The services of Seraph before units (its own Tailscale and Syncthing code), moved over at the next build. */
    private val legacy = File(data, "services")

    private var wanted: Map<String, JSONObject> = emptyMap()
    /** The units startAll could not start, the last time. */
    private var broken: List<String> = emptyList()
    /** At boot, and while a unit is tried again after it (SERAPH_RETRY): a failure is kept, not told, until the last try. */
    private var quietFailures = System.getenv("SERAPH_RETRY") == "1"
    private var toStop: List<String> = emptyList()
    private var toStart: List<String> = emptyList()
    private var leaving: List<String> = emptyList()
    /** Changed units that start so only with their next start (restartIfChanged = false): their files, written now. */
    private var toRefresh: List<String> = emptyList()

    /** Whether the plan changes anything. */
    val changes get() = toStop.isNotEmpty() || toStart.isNotEmpty() || leaving.isNotEmpty() || toRefresh.isNotEmpty() || legacy.exists()

    /** [secrets]: the files of the secrets the build changes; a unit that names one runs again, as with a changed unit. */
    fun plan(units: JSONObject?, problems: MutableList<String>, secrets: List<String> = emptyList()) {
        wanted = units?.let { json -> json.keys().asSequence().associateWith { json.getJSONObject(it) } } ?: emptyMap()
        val had = applied()
        val active = active(wanted)
        for ((name, unit) in wanted) {
            val env = environment(name, unit)
            for (step in steps(unit)) {
                val command = step.optString("command").ifEmpty { null } ?: continue
                runCatching { parse(name, unit, command).words(env) }.onFailure { problems += "systemd.services.$name: ${it.message}" }
            }
        }
        leaving = had.keys.filter { it !in wanted }
        // Changed: its unit, or the scripts the engine makes of it (as NixOS restarts a unit whose unit file changed).
        // Or a secret file it reads (a new auth key): the unit is the same, what it reads is not.
        // Or Seraph's view itself (a newer Seraph's, with another /home, ...): a unit running in the one before starts
        // again in the new one (a oneshot that ran is left).
        val viewChanged = view.isFile && runCatching { view.readText() }.getOrNull() != viewText()
        val edited = wanted.keys.filter { name ->
            had[name]?.let { it.toString() != wanted.getValue(name).toString() || !written(name, wanted.getValue(name)) } == true ||
                (had[name] != null && reads(wanted.getValue(name), secrets)) ||
                (viewChanged && had[name] != null && !oneshot(wanted.getValue(name)) && running(name))
        }
        // One that says so (restartIfChanged = false) keeps running as it is: its next start is the new one.
        toRefresh = edited.filter { name -> !wanted.getValue(name).optBoolean("restartIfChanged", true) && running(name) }
        val changed = edited - toRefresh.toSet()
        val start = mutableListOf<String>()
        for (name in active) {
            val unit = wanted.getValue(name)
            when {
                had[name] == null || name in changed -> start += name
                name in toRefresh -> {}
                oneshot(unit) -> if (status(name) != OK && !waiting(name)) start += name
                !running(name) && !waiting(name) -> start += name
            }
        }
        // A oneshot runs again after a unit it wants (or requires) starts, as its settings may be that unit's.
        do {
            val more = active.filter { name -> name !in start && oneshot(wanted.getValue(name)) && needs(wanted.getValue(name)).any { it in start } }
            start += more
        } while (more.isNotEmpty())
        toStart = order(start, wanted)
        // A oneshot that stays active after its run (RemainAfterExit) is stopped too, by its ExecStop.
        toStop = order((had.keys.filter { it in wanted && it !in active } + changed)
            .filter { supervisorPid(it) != null || remains(it, had[it]) }.distinct(), had).reversed()
        report(had, active, changed)
    }

    /** Whether [unit] names one of [files] (JSON writes / as \/). */
    private fun reads(unit: JSONObject, files: List<String>): Boolean {
        if (files.isEmpty()) return false
        val text = unit.toString().replace("\\/", "/")
        return files.any { text.contains(it) }
    }

    private fun report(had: Map<String, JSONObject>, active: Set<String>, changed: List<String>) {
        if (wanted.isEmpty() && had.isEmpty() && !legacy.exists()) return
        Out.line("Services:")
        if (legacy.exists()) Out.line("  the ones of the earlier Seraph: to move over to units (Tailscale keeps its login)")
        for (name in (wanted.keys + had.keys).sorted()) {
            val unit = wanted[name] ?: had.getValue(name)
            val once = oneshot(unit)
            val what = when {
                name in leaving -> if (supervisorPid(name) != null) "to stop (it left the config)" else "to remove (it left the config)"
                name !in active -> if (supervisorPid(name) != null) "to stop (nothing wants it now)" else "not started (nothing wants it)"
                name in toStart && had[name] == null -> if (once) "to run" else "to start"
                name in toStart && name in changed -> if (once) "to run again (changed)" else "to start again (changed)"
                name in toStart -> if (once) "to run again" else if (supervisorPid(name) != null) "to start again (it keeps stopping)" else "to start (it was not running)"
                name in toRefresh -> "changed: the change comes with its next start (restartIfChanged = false)"
                waiting(name) -> "waiting for its storage (${mounts(unit).firstOrNull { !onStorage(it) } ?: "it is there now"})"
                once && status(name) == SKIPPED -> "not run (its condition is not met)"
                once -> "done"
                else -> "running"
            }
            Out.line("  $name: $what")
        }
    }

    /** Carries the plan out, with the shell of [android] (the profile's android part) as root's; the number of units that did not start. */
    fun apply(android: JSONObject? = null): Int {
        moveLegacy()
        if (toStart.isEmpty() && toStop.isEmpty() && leaving.isEmpty() && toRefresh.isEmpty()) return 0
        writeView()
        Etc.write(data, android)
        Tmpfiles.apply(data, android?.optJSONArray("tmpfiles"))
        toStop.forEach { stop(it) }
        for (name in leaving) {
            stop(name)
            File(dir, name).deleteRecursively()
            Out.line("✔ $name: removed (it left the config)")
        }
        for (name in toRefresh) {
            val unit = wanted.getValue(name)
            val unitDir = File(dir, name)
            writeScripts(name, unit, unitDir)
            File(unitDir, "spec.json").writeText(unit.toString())
            Out.line("✔ $name: changed; the change comes with its next start")
        }
        return startAll(toStart, wanted)
    }

    /** At boot: the units the last build left, started again in order. */
    fun start(): Int {
        if (legacy.exists()) Out.line("✘ The services of the earlier Seraph start again with the next build, as units")
        val had = applied()
        if (had.isEmpty()) return 0
        // /run is empty after a restart, as on Linux.
        runDir.deleteRecursively()
        writeView()
        Etc.write(data)
        Tmpfiles.apply(data, current()?.optJSONArray("tmpfiles"))
        val names = order(active(had), had)
        quietFailures = true
        val count = startAll(names, had)
        // In the journal (the Logs tab); one that did not start is tried again, and told about if it never does.
        Notify.event(
            "service", if (count == 0) "ok" else "warn",
            if (count == 0) "Services started at boot" else "$count of ${names.size} services did not start at boot: tried again in a while",
            if (count == 0) names.joinToString(", ") else broken.joinToString(", "), quiet = true,
        )
        broken.forEach { retryLater(it) }
        return count
    }

    /**
     * A unit that did not start at boot, tried again after 30 seconds, a minute and a half and 4 minutes: what it needs
     * may come only after the boot (an SD card that mounts later, the network), as systemd's units wait for theirs.
     * Told about when the last try fails too.
     */
    private fun retryLater(name: String) {
        val module = Notify.module ?: return
        val script = "for wait in 30 90 240; do sleep \$wait; sh \"\$1/units.sh\" boot start \"\$2\" > /dev/null 2>&1 && exit 0; done; " +
            "sh \"\$1/notify.sh\" service error \"\$2 did not start\" \"It failed at boot and 3 times after; its log: /data/adb/seraph/units/\$2/log\" \"service:\$2\""
        runCatching {
            ProcessBuilder(busybox(), "setsid", "sh", "-c", script, "seraph-retry", module.path, name)
                .redirectInput(File("/dev/null")).redirectOutput(File("/dev/null")).redirectError(File("/dev/null"))
                .apply { environment()["SERAPH_RETRY"] = "1"; environment().remove("SERAPH_RUN") }.start()
        }
    }

    /** The module is being removed: every unit stops, and what they kept goes with the module's data. */
    fun removeAll(): Int {
        moveLegacy()
        val had = applied()
        order(had.keys, had).reversed().forEach { stop(it) }
        dir.deleteRecursively()
        runDir.deleteRecursively()
        if (had.isNotEmpty()) Out.line("✔ Services: stopped")
        return 0
    }

    /**
     * Each unit the last build left, and how it is, as systemctl says: a simple one running, restarting (its loop
     * waits to start it again) or stopped; a oneshot done, failed, or not run (nothing wants it, or its condition is not
     * met). Also since when (its start, or its run), and its log.
     */
    fun list(): JSONObject {
        val had = applied()
        val active = active(had)
        val units = JSONArray()
        for (name in had.keys.sorted()) {
            val unit = had.getValue(name)
            val once = oneshot(unit)
            val state = when {
                waiting(name) -> "waiting"
                once -> when (status(name)) { OK -> "done"; SKIPPED -> "skipped"; "" -> "not run"; WAITING -> "waiting"; else -> "failed" }
                running(name) -> "running"
                supervisorPid(name) != null -> "restarting"
                File(dir, "$name/skipped").isFile -> "skipped"
                else -> "stopped"
            }
            val since = File(dir, if (once) "$name/status" else if (state == "waiting") "$name/waiting" else "$name/main.pid").lastModified()
            units.put(
                JSONObject().put("name", name).put("description", unit.optString("description"))
                    .put("type", if (once) "oneshot" else "simple").put("state", state).put("wanted", name in active)
                    .put("since", since).put("log", File(dir, "$name/log").path),
            )
        }
        return JSONObject().put("units", units)
    }

    /**
     * systemctl's start, stop and restart of one unit of the last build, by hand: until the next build or restart,
     * which start the units as the config says. A oneshot starts by running again; it has nothing to stop.
     */
    fun control(action: String, name: String): Int {
        val had = applied()
        val unit = had[name] ?: throw Stop("there is no service $name in the last build (${had.keys.sorted().joinToString(", ").ifEmpty { "it has none" }})")
        val once = oneshot(unit)
        return when (action) {
            "stop" -> {
                when {
                    once && remains(name, unit) -> {
                        stop(name)
                        Out.line("✔ $name: stopped (until the next build or restart)")
                    }
                    once -> Out.line("$name runs once, to its end: there is nothing to stop")
                    supervisorPid(name) == null -> Out.line("$name is not running")
                    else -> {
                        stop(name)
                        Out.line("✔ $name: stopped (until the next build or restart)")
                        Notify.event("service", "info", "Stopped $name", "By hand, until the next build or restart", "service:$name", quiet = true)
                    }
                }
                0
            }
            "start", "restart" -> {
                if (action == "start" && !once && running(name)) {
                    Out.line("$name is running already")
                    return 0
                }
                // As at boot: the view, and Seraph's /etc with the network's DNS servers of now.
                writeView()
                Etc.write(data)
                Tmpfiles.apply(data, current()?.optJSONArray("tmpfiles"))
                val ok = start(name, unit)
                val done = if (once) "Ran $name" else if (action == "restart") "Restarted $name" else "Started $name"
                if (ok) Notify.event("service", "info", done, when {
                    System.getenv("SERAPH_WAITED") == "1" -> "Its storage is there now"
                    System.getenv("SERAPH_RETRY") == "1" -> "Tried again: it did not start at boot"
                    else -> "By hand"
                }, "service:$name", quiet = true)
                if (ok) 0 else 1
            }
            "retry" -> {
                retryLater(name)
                Out.line("$name: tried again after 30 seconds, a minute and a half and 4 minutes, until it starts")
                0
            }
            else -> throw Stop("no such thing to do with a service: $action (start, stop, restart, retry)")
        }
    }

    private fun startAll(names: List<String>, specs: Map<String, JSONObject>): Int {
        val failed = mutableSetOf<String>()
        for (name in names) {
            val unit = specs.getValue(name)
            val missing = unit.strings("requires").filter { it in failed }
            val ok = if (missing.isNotEmpty()) {
                Out.line("✘ $name: not started, as ${missing.joinToString(", ")} did not start")
                false
            } else {
                try {
                    start(name, unit)
                } catch (e: Exception) {
                    Out.line("✘ $name: ${e.message}")
                    false
                }
            }
            if (!ok) failed += name
        }
        broken = failed.toList()
        return failed.size
    }

    // --- One unit.

    private fun start(name: String, unit: JSONObject): Boolean {
        val unitDir = File(dir, name).apply { mkdirs() }
        if (supervisorPid(name) != null || remains(name, unit)) stop(name)
        // Started anew: a failure told before is the past, and so is a condition not met.
        File(unitDir, "failing").delete()
        File(unitDir, "skipped").delete()
        val accounts = Etc.accounts(data)
        val account = unit.string("user")?.let { user ->
            accounts.users[user] ?: run {
                Out.line("✘ $name: its user $user is not in Seraph's /etc/passwd (nix/users.nix makes it)")
                return false
            }
        }
        val gid = unit.string("group")?.let { accounts.groups[it] } ?: account?.gid
        prepareDirectories(name, unit, account?.uid, gid)
        writeScripts(name, unit, unitDir)
        File(unitDir, "spec.json").writeText(unit.toString())
        // The storage it needs (unitConfig.RequiresMountsFor), one path a line, for its loop.
        val mounts = mounts(unit)
        File(unitDir, "mounts").let { if (mounts.isEmpty()) it.delete() else it.writeText(mounts.joinToString("") { path -> "$path\n" }) }
        return if (oneshot(unit)) runOnce(name, unitDir, unit) else launch(name, unit, unitDir)
    }

    /**
     * Its folders, as systemd makes them (StateDirectory in /var/lib, CacheDirectory in /var/cache, LogsDirectory in
     * /var/log, RuntimeDirectory in /run, ConfigurationDirectory in /etc), with their modes (0755 unless *Mode says),
     * owned by its user, and what is in them too when they were someone else's. RuntimeDirectory starts empty. A
     * DynamicUser's state, cache and logs are also there as /var/lib/private/<name>, where systemd keeps them (and some of
     * NixOS's modules look).
     */
    private fun prepareDirectories(name: String, unit: JSONObject, uid: Int?, gid: Int?) {
        val modes = unit.optJSONObject("directoryModes")
        for ((kind, key, base) in directories()) {
            val mode = modes?.string(kind)?.toIntOrNull(8) ?: "755".toInt(8)
            for (relative in unit.strings(key)) {
                val path = File(base, relative)
                if (kind == "Runtime") remove(path)
                path.mkdirs()
                runCatching {
                    if (uid != null) {
                        val stat = Os.lstat(path.path)
                        if (stat.st_uid != uid || stat.st_gid != (gid ?: uid)) {
                            Files.walk(path.toPath()).use { all -> all.forEach { Os.lchown(it.toString(), uid, gid ?: uid) } }
                        }
                    }
                    Os.chmod(path.path, mode)
                }.onFailure { Out.line("✘ $name: its folder ${path.path}: ${it.message}") }
                if (unit.optBoolean("dynamic") && kind in listOf("State", "Cache", "Logs")) privateLink(base, relative)
            }
        }
    }

    /** systemd's /var/lib/private/<name> (and the cache's and logs'), as a link to the folder itself. */
    private fun privateLink(base: File, relative: String) {
        val private = File(base, "private").apply { mkdirs() }
        runCatching { Os.chmod(private.path, "711".toInt(8)) }
        val link = File(private, relative)
        if (link.exists() || Files.isSymbolicLink(link.toPath())) return
        link.parentFile?.mkdirs()
        // Relative, so it leads there in the view and outside of it alike.
        runCatching { Os.symlink("../".repeat(relative.count { it == '/' } + 1) + relative, link.path) }
    }

    /** The kinds of a unit's folders: its key in serviceConfig (a profile's list) and where they are. */
    private fun directories() = listOf(
        Triple("State", "stateDirectories", File(varDir, "lib")),
        Triple("Cache", "cacheDirectories", File(varDir, "cache")),
        Triple("Logs", "logsDirectories", File(varDir, "log")),
        Triple("Runtime", "runtimeDirectories", runDir),
        Triple("Configuration", "configurationDirectories", File(data, "etc")),
    )

    /** A simple (or forking) unit, under its loop; told about at once if it stops at once (a wrong command, a missing file). */
    private fun launch(name: String, unit: JSONObject, unitDir: File): Boolean {
        val busybox = busybox()
        val log = File(unitDir, "log")
        val devNull = File("/dev/null")
        File(unitDir, "run.conf").writeText(runConf(unit, unitDir))
        File(unitDir, "ready").delete()
        if (!background.isFile) writeView()
        // Below the apps on the screen (see BACKGROUND), its loop and so all it starts.
        ProcessBuilder(
            busybox, "sh", background.path, busybox, nice(unit),
            busybox, "setsid", "sh", "-c", SUPERVISOR, MARK + name, busybox, view.path, log.path, unitDir.path, name,
        ).directory(unitDir).redirectInput(devNull).redirectOutput(devNull).redirectError(devNull).apply { unitEnvironment(environment()) }.start()
        // A forking one has started once its start has ended and its service is found.
        if (unit.optString("type") == "forking") {
            val until = System.currentTimeMillis() + 1000L * timeout(unit, "timeoutStart", 90).coerceAtMost(120)
            while (System.currentTimeMillis() < until && !File(unitDir, "ready").isFile && supervisorPid(name) != null) Thread.sleep(300)
        }
        Thread.sleep(1500)
        val main = pid(File(unitDir, "main.pid"))
        if (running(name) && main != null && alive(main)) {
            Out.line("✔ $name: running")
            return true
        }
        if (waiting(name)) {
            Out.line("◌ $name: waiting for ${mounts(unit).firstOrNull { !onStorage(it) } ?: "its storage"} (${STORAGE_WHY}); it starts once that is there")
            return true
        }
        if (File(unitDir, "skipped").isFile) {
            Out.line("◌ $name: not started: ${runCatching { File(unitDir, "skipped").readText().trim() }.getOrNull()?.ifEmpty { null } ?: "its condition is not met"}")
            return true
        }
        Out.line("✘ $name: it stopped at once; the end of its log (${log.path}):")
        val end = tail(log)
        end.forEach { Out.line("    $it") }
        if (notifies(unit)) Notify.event("service", "error", "$name did not start", end.lastOrNull { it.isNotBlank() }.orEmpty(), "service:$name", quiet = quietFailures)
        return false
    }

    /** What the loop of a unit does (see SUPERVISOR), as shell variables it reads. */
    private fun runConf(unit: JSONObject, unitDir: File): String {
        val optional = { file: String -> File(unitDir, file).takeIf { it.isFile }?.path.orEmpty() }
        // Its loop tells the app when it stops by itself, and when it runs again (unless its notifications are off).
        val notify = Notify.module?.takeIf { notifies(unit) }?.let { File(it, "notify.sh").path }.orEmpty()
        val values = linkedMapOf(
            "restart" to unit.optString("restart", "no"),
            "delay" to seconds(unit.optString("restartSec", "0.1")),
            "start" to File(unitDir, "start.sh").path,
            "post" to optional("post.sh"),
            "stop" to optional("stop.sh"),
            "stoppost" to optional("stoppost.sh"),
            "notify" to notify,
            "success" to unit.ints("successExitStatus").joinToString(" "),
            "force" to unit.ints("restartForceExitStatus").joinToString(" "),
            "prevent" to unit.ints("restartPreventExitStatus").joinToString(" "),
            "type" to unit.optString("type", "simple"),
            // Its PIDFile as it is outside the view, where the loop runs.
            "pidfile" to (unit.string("pidFile")?.let { Etc.outside(data, it).path } ?: ""),
            "killmode" to unit.optString("killMode", "control-group"),
            "killsignal" to signal(unit.optString("killSignal", "SIGTERM")),
            "sigkill" to if (unit.optBoolean("sendSigkill", true)) "1" else "",
            "timeout" to timeout(unit, "timeoutStop", 10).coerceAtMost(300).toString(),
            "timeout_start" to timeout(unit, "timeoutStart", 90).coerceAtMost(600).toString(),
        )
        return "# The loop's settings for this unit (written by the engine; see Units.kt).\n" +
            values.entries.joinToString("") { (key, value) -> "$key=${quote(value)}\n" }
    }

    /** A time span of [unit] (TimeoutStopSec, ...) in whole seconds; [default] when it has none, and for "infinity". */
    private fun timeout(unit: JSONObject, key: String, default: Int): Int {
        val text = unit.string(key) ?: return default
        if (text == "infinity" || text == "0") return default * 10
        return seconds(text).toDouble().toInt().coerceAtLeast(1)
    }

    /**
     * What a unit's processes get besides their own environment: the module's folder (SERAPH_MODULE: its engine, for
     * services.snapclient's sound) and its notify.sh (SERAPH_NOTIFY: `sh "$SERAPH_NOTIFY" <kind> <level> <title>
     * [text]` tells the app something, as a notification). Not the run that started it: its own events are its own.
     */
    private fun unitEnvironment(env: MutableMap<String, String>) {
        listOf("SERAPH_RUN", "SERAPH_KIND", "SERAPH_BY", "SERAPH_QUIET").forEach { env.remove(it) }
        Notify.module?.let {
            env["SERAPH_MODULE"] = it.path
            env["SERAPH_NOTIFY"] = File(it, "notify.sh").path
        }
    }

    private fun notifies(unit: JSONObject) = unit.optBoolean("notifications", true)

    /** Its nice (serviceConfig.Nice; 10, below the apps, by default). */
    private fun nice(unit: JSONObject) = unit.optInt("nice", 10).coerceIn(-20, 19).toString()

    /**
     * A oneshot, to its end (or its TimeoutStartSec; 10 minutes by default); what it says is shown (and kept in its log).
     * One whose storage is not there yet runs once it is, from a waiter of its own: as a unit waiting, not a failure. One
     * whose condition is not met has not run, which is no failure either.
     */
    private fun runOnce(name: String, unitDir: File, unit: JSONObject): Boolean {
        val log = File(unitDir, "log")
        mounts(unit).firstOrNull { !onStorage(it) }?.let { missing ->
            File(unitDir, "status").writeText(WAITING)
            if (!waiting(name)) waitThenRun(name, unitDir)
            log.appendText("${Out.date(System.currentTimeMillis())} $name: waiting for $missing ($STORAGE_WHY)\n")
            Out.line("◌ $name: waiting for $missing ($STORAGE_WHY); it runs once that is there")
            return true
        }
        val seconds = timeout(unit, "timeoutStart", ONESHOT_MINUTES * 60).toLong()
        val (done, code) = inView(name, unit, File(unitDir, "start.sh"), log, seconds, show = true)
        val skipped = File(unitDir, "skipped").isFile
        val ok = done && (code == 0 || code in unit.ints("successExitStatus"))
        File(unitDir, "status").writeText(if (skipped) SKIPPED else if (ok) OK else "failed")
        // ExecStopPost, as the unit is done (one that stays active, RemainAfterExit, runs it when it stops).
        if (!unit.optBoolean("remainAfterExit")) File(unitDir, "stoppost.sh").takeIf { it.isFile }?.let { inView(name, unit, it, log, 600) }
        when {
            skipped -> Out.line("◌ $name: not run: ${runCatching { File(unitDir, "skipped").readText().trim() }.getOrNull()?.ifEmpty { null } ?: "its condition is not met"}")
            ok -> Out.line("✔ $name: done")
            else -> {
                val why = if (done) "failed ($code)" else if (seconds % 60 == 0L) "did not end in ${seconds / 60} minutes" else "did not end in $seconds seconds"
                Out.line("✘ $name: $why; its log: ${log.path}")
                if (notifies(unit)) Notify.event("service", "error", "$name $why", unit.optString("description"), "service:$name", quiet = quietFailures)
            }
        }
        return ok || skipped
    }

    /**
     * Runs [script] of [name] in the view, below the apps, its output in [log] (and shown, with [show]); whether it ended
     * within [seconds], and its exit status.
     */
    private fun inView(name: String, unit: JSONObject, script: File, log: File, seconds: Long, show: Boolean = false): Pair<Boolean, Int> {
        if (!background.isFile) writeView()
        val process = ProcessBuilder(busybox(), "sh", background.path, busybox(), nice(unit), busybox(), "sh", view.path, script.path)
            .directory(script.parentFile).redirectErrorStream(true).redirectInput(File("/dev/null")).apply { unitEnvironment(environment()) }.start()
        val reader = Thread {
            runCatching {
                log.appendText("${Out.date(System.currentTimeMillis())} $name\n")
                process.inputStream.bufferedReader().forEachLine { line ->
                    if (show) Out.line(line)
                    log.appendText(line + "\n")
                }
            }
        }.apply { start() }
        val done = process.waitFor(seconds, TimeUnit.SECONDS)
        if (!done) process.destroyForcibly()
        reader.join(2000)
        return done to (if (done) process.exitValue() else -1)
    }

    /** Runs the oneshot [name] (units.sh start) once its storage (unitDir/mounts) is all there, from a process of its own. */
    private fun waitThenRun(name: String, unitDir: File) {
        val module = Notify.module ?: return
        val script = ON_STORAGE + "\n" + """
            echo ${'$'}${'$'} > "${'$'}2/waiter.pid"
            until all_there "${'$'}2/mounts"; do sleep 5; done
            rm -f "${'$'}2/waiter.pid"
            SERAPH_WAITED=1 exec sh "${'$'}1/units.sh" boot start "${'$'}3"
        """.trimIndent()
        runCatching {
            ProcessBuilder(busybox(), "setsid", "sh", "-c", script, "seraph-wait:$name", module.path, unitDir.path, name)
                .redirectInput(File("/dev/null")).redirectOutput(File("/dev/null")).redirectError(File("/dev/null"))
                .apply { environment()["b"] = busybox(); environment().remove("SERAPH_RUN") }.start()
        }
    }

    /**
     * Stops a unit: a simple one's loop ends it as its KillMode, KillSignal and TimeoutStopSec say, after its ExecStop
     * and before its ExecStopPost; a oneshot that stays active (RemainAfterExit) runs its ExecStop and ExecStopPost. Its
     * runtime folders go, as systemd's do (unless RuntimeDirectoryPreserve says yes).
     */
    private fun stop(name: String) {
        val unitDir = File(dir, name)
        val spec = runCatching { JSONObject(File(unitDir, "spec.json").readText()) }.getOrNull()
        // A oneshot's waiter for its storage, too.
        pid(File(unitDir, "waiter.pid"))?.takeIf { cmdline(it).contains("seraph-wait:$name") }?.let { Device.run("kill", "-TERM", "$it") }
        File(unitDir, "waiter.pid").delete()
        if (spec != null && remains(name, spec)) {
            val log = File(unitDir, "log")
            File(unitDir, "stop.sh").takeIf { it.isFile }?.let { inView(name, spec, it, log, 600) }
            File(unitDir, "stoppost.sh").takeIf { it.isFile }?.let { inView(name, spec, it, log, 600) }
            File(unitDir, "status").delete()
        }
        val supervisor = supervisorPid(name)
        if (supervisor != null) {
            Device.run("kill", "-TERM", "$supervisor")
            // Its loop waits for its ExecStop, its processes (TimeoutStopSec) and its ExecStopPost.
            val seconds = 10 + (spec?.let { timeout(it, "timeoutStop", 10).coerceAtMost(300) } ?: 10)
            var tries = 0
            while (supervisorPid(name) != null && tries++ < seconds * 5) Thread.sleep(200)
            supervisorPid(name)?.let { Device.run("kill", "-KILL", "$it") }
        }
        pid(File(unitDir, "main.pid"))?.takeIf { alive(it) }?.let { Device.run("kill", "-KILL", "$it") }
        File(unitDir, "supervisor.pid").delete()
        File(unitDir, "main.pid").delete()
        if (spec?.optString("runtimeDirectoryPreserve") !in listOf("yes", "true", "1", "restart")) {
            spec?.strings("runtimeDirectories")?.forEach { remove(File(runDir, it)) }
        }
    }

    /** Whether [unit] is a oneshot that ran and stays active (RemainAfterExit), with something to stop it. */
    private fun remains(name: String, unit: JSONObject?): Boolean =
        unit != null && oneshot(unit) && unit.optBoolean("remainAfterExit") && status(name) == OK &&
            (File(dir, "$name/stop.sh").isFile || File(dir, "$name/stoppost.sh").isFile)

    /** Removes [file] and what is in it, a link without what it leads to. */
    private fun remove(file: File) {
        val path = file.toPath()
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) file.listFiles().orEmpty().forEach { remove(it) }
        runCatching { Files.deleteIfExists(path) }
    }

    private fun writeScripts(name: String, unit: JSONObject, unitDir: File) {
        unitDir.listFiles().orEmpty().filter { it.name.endsWith(".sh") }.forEach { it.delete() }
        for ((file, text) in scripts(name, unit, unitDir)) File(unitDir, file).writeText(text)
    }

    /** Whether its scripts on the phone are the ones the engine makes of [unit] now. */
    private fun written(name: String, unit: JSONObject): Boolean {
        val unitDir = File(dir, name)
        val wantedFiles = scripts(name, unit, unitDir)
        val there = unitDir.listFiles().orEmpty().filter { it.name.endsWith(".sh") }.map { it.name }.toSet()
        return there == wantedFiles.keys && wantedFiles.all { (file, text) -> runCatching { File(unitDir, file).readText() == text }.getOrDefault(false) }
    }

    /**
     * Its scripts, by file name: start.sh (its conditions, ExecCondition, ExecStartPre, then ExecStart; a oneshot's
     * ExecStartPost too), post.sh, stop.sh (ExecStop) and stoppost.sh (ExecStopPost). Each begins as root, in the view
     * ([head]); its steps run as its user (through setpriv), but for those marked + or ! (and with PermissionsStartOnly,
     * all but its ExecStart), which run as root, as systemd runs them.
     */
    private fun scripts(name: String, unit: JSONObject, unitDir: File): Map<String, String> {
        val files = linkedMapOf<String, String>()
        val env = environment(name, unit)
        val shell = unit.optString("shell")
        val user = unit.string("user")
        val rootSteps = unit.optBoolean("rootSteps")
        var scripts = 0
        fun line(step: JSONObject, how: String, root: Boolean = false): String {
            val (ignore, wrapped) = if (step.has("script")) {
                val asUser = user != null && !root
                val file = "script-${scripts++}.sh"
                files[file] = step.getString("script")
                val args = step.string("args")?.let { parse(name, unit, "/ $it", arguments = true).shell(env, runtime(unit)).drop(1) }.orEmpty()
                // A user cannot read Seraph's folders: its script comes open, as its fd 3.
                false to if (asUser) "${setpriv(unit)} -- " + (listOf(quote(shell), "'-e'", "/dev/fd/3") + args).joinToString(" ") + " 3< ${quote(File(unitDir, file).path)}"
                else (listOf(quote(shell), "'-e'", quote(File(unitDir, file).path)) + args).joinToString(" ")
            } else {
                val parsed = parse(name, unit, step.getString("command"))
                val words = parsed.shell(env, runtime(unit)).joinToString(" ")
                parsed.ignore to if (user != null && !root && !parsed.privileged) "${setpriv(unit)} -- $words" else words
            }
            return when {
                how == "exec" -> "exec $wrapped"
                how == "condition" -> "$wrapped || { c=${'$'}?; if [ ${'$'}c -ge 1 ] && [ ${'$'}c -le 254 ]; then echo \"$name: its ExecCondition said no (${'$'}c)\" > ${quote(File(unitDir, "skipped").path)}; exit 0; fi; exit ${'$'}c; }"
                ignore || how == "stop" -> "$wrapped || true"
                else -> "$wrapped || exit \$?"
            }
        }
        val pre = unit.objects("pre").map { line(it, "run", rootSteps) }
        val condition = unit.objects("condition").map { line(it, "condition", rootSteps) }
        val start = unit.objects("start")
        val post = unit.objects("post").map { line(it, "run", rootSteps) }
        val stop = unit.objects("stop").map { line(it, "stop", rootSteps) }
        val stopPost = unit.objects("stopPost").map { line(it, "stop", rootSteps) }
        val checks = checks(name, unit, unitDir)
        if (oneshot(unit)) {
            files["start.sh"] = head(name, unit, env) + checks + (condition + pre + start.map { line(it, "run") } + post).joinToString("") { "$it\n" }
        } else {
            // Its view's mount namespace, by which its loop knows its processes (KillMode).
            val mark = "/system/bin/readlink /proc/self/ns/mnt > ${quote(File(unitDir, "ns").path)}\n"
            files["start.sh"] = head(name, unit, env) + checks + mark + (condition + pre + line(start.single(), "exec")).joinToString("") { "$it\n" }
            if (post.isNotEmpty()) files["post.sh"] = head(name, unit, env) + post.joinToString("") { "$it\n" }
        }
        if (stop.isNotEmpty()) files["stop.sh"] = head(name, unit, env) + stop.joinToString("") { "$it\n" }
        if (stopPost.isNotEmpty()) files["stoppost.sh"] = head(name, unit, env) + stopPost.joinToString("") { "$it\n" }
        return files
    }

    /** setpriv's words for running a step as its user: its uid and gid, its groups, its ambient capabilities. */
    private fun setpriv(unit: JSONObject): String {
        val user = unit.getString("user")
        val words = mutableListOf(quote(unit.getString("setpriv")), quote("--reuid=$user"))
        // Its Group, else its user's own group (as /etc/passwd has it: looked up by the head).
        words += unit.string("group")?.let { quote("--regid=$it") } ?: "\"--regid=${'$'}seraph_gid\""
        words += "'--init-groups'"
        val caps = unit.strings("ambientCapabilities").filter { !it.startsWith("~") }
            .map { "+" + it.lowercase().removePrefix("cap_") }
        if (caps.isNotEmpty()) words += listOf(quote("--inh-caps=${caps.joinToString(",")}"), quote("--ambient-caps=${caps.joinToString(",")}"))
        if (unit.optBoolean("noNewPrivileges")) words += "'--no-new-privs'"
        return words.joinToString(" ")
    }

    /** Whether its commands take variables only known when it runs (its EnvironmentFile's, its user's HOME, ...). */
    private fun runtime(unit: JSONObject) = unit.has("environmentFiles") || unit.has("user")

    /**
     * What each of its scripts does first, as root in the view: its environment, its user (looked up in Seraph's
     * /etc/passwd: HOME, USER, ...), its WorkingDirectory, UMask, Limit*, OOMScoreAdjust, BindPaths, EnvironmentFile,
     * UnsetEnvironment and credentials (LoadCredential, SetCredential: in a folder of its own only it may read, in its
     * view's memory).
     */
    private fun head(name: String, unit: JSONObject, env: Map<String, String>): String = buildString {
        append("# $name, as Seraph runs it (the config's systemd.services.$name): ${unit.optString("description")}\n")
        if (env.isNotEmpty()) append("export ").append(env.entries.joinToString(" ") { (key, value) -> "$key=${quote(value)}" }).append('\n')
        val user = unit.string("user")
        if (user != null) {
            append("while IFS=: read -r seraph_name seraph_x seraph_uid seraph_gid seraph_gecos seraph_home seraph_shell; do [ \"${'$'}seraph_name\" = ${quote(user)} ] && break; done < /etc/passwd\n")
            append("[ \"${'$'}seraph_name\" = ${quote(user)} ] || { echo \"$name: its user $user is not in Seraph's /etc/passwd\" >&2; exit 1; }\n")
            append("export USER=${quote(user)} LOGNAME=${quote(user)} HOME=\"${'$'}seraph_home\" SHELL=\"${'$'}seraph_shell\"\n")
            unit.string("group")?.let { group ->
                append("while IFS=: read -r seraph_name seraph_x seraph_gid seraph_members; do [ \"${'$'}seraph_name\" = ${quote(group)} ] && break; done < /etc/group\n")
                append("[ \"${'$'}seraph_name\" = ${quote(group)} ] || { echo \"$name: its group $group is not in Seraph's /etc/group\" >&2; exit 1; }\n")
            }
        }
        val workingDirectory = unit.string("workingDirectory")
        val optional = workingDirectory?.startsWith("-") == true
        val directory = workingDirectory?.removePrefix("-")
        val cd = when (directory) {
            null -> quote("/")
            "~" -> if (user != null) "\"${'$'}HOME\"" else quote(File(data, "home").path)
            else -> quote(directory)
        }
        append(if (optional) "cd $cd 2> /dev/null || cd /\n" else "cd $cd || exit 1\n")
        if (runtime(unit)) append("set -f\n")
        // Busybox's mount (Android's toybox cannot remount a bind read-only).
        if (unit.has("binds") || unit.has("credentials")) append("seraph_b=${quote(busybox())}\n")
        unit.optJSONArray("binds")?.let { binds ->
            for (i in 0 until binds.length()) {
                val bind = binds.getJSONObject(i)
                val from = quote(bind.getString("from"))
                val to = quote(bind.getString("to"))
                val missing = if (bind.optBoolean("optional")) ":" else "{ echo \"$name: ${bind.getString("from")} is not there (BindPaths)\" >&2; exit 1; }"
                append("if [ -e $from ]; then if [ -d $from ]; then /system/bin/mkdir -p $to; else /system/bin/mkdir -p ${quote(File(bind.getString("to")).parent ?: "/")}; [ -e $to ] || : > $to; fi; ")
                append("\"${'$'}seraph_b\" mount --bind $from $to || exit 1")
                if (bind.optBoolean("readOnly")) append("; \"${'$'}seraph_b\" mount -o remount,bind,ro $to || exit 1")
                append("; else $missing; fi\n")
            }
        }
        unit.optJSONArray("environmentFiles")?.let { files ->
            append(ENVIRONMENT_FILE)
            for (i in 0 until files.length()) {
                val file = files.getJSONObject(i)
                append("seraph_environment ${quote(file.getString("path"))} ${if (file.optBoolean("optional")) "optional" else "needed"} || exit 1\n")
            }
        }
        unit.strings("unsetEnvironment").map { it.substringBefore('=') }.filter { NAME.matches(it) }.takeIf { it.isNotEmpty() }
            ?.let { append("unset ${it.joinToString(" ")}\n") }
        unit.optJSONArray("credentials")?.let { credentials ->
            val folder = quote(credentialsDirectory(name))
            append("/system/bin/mkdir -p $folder && /system/bin/chmod 0755 /run/credentials && \"${'$'}seraph_b\" mount -t tmpfs -o mode=0700,nosuid,nodev,noexec,size=4m seraph-credentials $folder || exit 1\n")
            for (i in 0 until credentials.length()) {
                val credential = credentials.getJSONObject(i)
                val id = credential.getString("id")
                val into = quote("${credentialsDirectory(name)}/$id")
                when {
                    credential.has("text") -> append("printf '%s' ${quote(credential.getString("text"))} > $into || exit 1\n")
                    credential.string("path") != null -> {
                        val from = quote(credential.getString("path"))
                        append("if [ -d $from ]; then ( set +f; for f in $from/*; do [ -f \"${'$'}f\" ] && /system/bin/cp \"${'$'}f\" ${quote("${credentialsDirectory(name)}/${id}_")}\"${'$'}{f##*/}\"; done ); ")
                        append("else /system/bin/cp $from $into || { echo \"$name: its credential $id (${credential.getString("path")}) could not be read\" >&2; exit 1; }; fi\n")
                    }
                    // An id alone: the file of that name in a credentials store, as systemd looks for it.
                    else -> append("for f in /etc/credstore/${quote(id)} /run/credstore/${quote(id)}; do [ -f \"${'$'}f\" ] && /system/bin/cp \"${'$'}f\" $into && break; done; [ -f $into ] || { echo \"$name: no credential $id in /etc/credstore or /run/credstore\" >&2; exit 1; }\n")
                }
            }
            append("/system/bin/chmod -R 0400 $folder && /system/bin/chmod 0500 $folder || exit 1\n")
            // Its user's (and its Group's, which the lookup above left in seraph_gid).
            if (user != null) append("/system/bin/chown -R \"${'$'}seraph_uid:${'$'}seraph_gid\" $folder || exit 1\n")
        }
        // What it runs with: after the root part above, so its umask is not that part's.
        unit.string("umask")?.let { append("umask ${quote(it)}\n") }
        unit.optJSONObject("limits")?.let { limits -> limits.keys().asSequence().sorted().forEach { key -> append(ulimit(key, limits.getString(key))) } }
        if (unit.has("oomScoreAdjust")) append("echo ${unit.optInt("oomScoreAdjust")} > /proc/self/oom_score_adj\n")
    }

    /** Its conditions (unitConfig's Condition and Assert keys), as shell tests: one not met ends it, as not run. */
    private fun checks(name: String, unit: JSONObject, unitDir: File): String {
        val list = unit.optJSONArray("checks") ?: return ""
        val skipped = quote(File(unitDir, "skipped").path)
        return buildString {
            var triggered = false
            for (i in 0 until list.length()) {
                val check = list.getJSONObject(i)
                val key = check.getString("key")
                var value = check.getString("value")
                val trigger = value.startsWith("|").also { if (it) value = value.drop(1) }
                val negate = value.startsWith("!").also { if (it) value = value.drop(1) }
                val kind = key.removePrefix("Condition").removePrefix("Assert")
                val path = quote(value)
                val test = when (kind) {
                    "PathExists" -> "[ -e $path ]"
                    "PathExistsGlob" -> "( set +f; for f in $value; do [ -e \"${'$'}f\" ] && exit 0; done; exit 1 )"
                    "PathIsDirectory" -> "[ -d $path ]"
                    "PathIsSymbolicLink" -> "[ -L $path ]"
                    "PathIsMountPoint" -> "/system/bin/mountpoint -q $path"
                    "PathIsReadWrite" -> "[ -w $path ]"
                    "DirectoryNotEmpty" -> "[ -d $path ] && [ -n \"${'$'}(/system/bin/ls -A $path)\" ]"
                    "FileNotEmpty" -> "[ -s $path ]"
                    "FileIsExecutable" -> "[ -f $path ] && [ -x $path ]"
                    // A phone runs on no machine of another's, on arm64.
                    "Virtualization" -> if (value in listOf("no", "false", "0")) "true" else "false"
                    "Architecture" -> if (value == "arm64") "true" else "false"
                    "KernelCommandLine" -> "/system/bin/grep -qw -- $path /proc/cmdline"
                    else -> "true"
                }
                val met = if (negate) "! { $test; }" else "{ $test; }"
                val said = "$key=${check.getString("value")}"
                when {
                    key.startsWith("Assert") -> append("$met || { echo \"$name: $said failed\" >&2; exit 1; }\n")
                    trigger -> {
                        if (!triggered) append("seraph_any=\n")
                        triggered = true
                        append("$met && seraph_any=1\n")
                    }
                    else -> append("$met || { echo \"$said is not met\" > $skipped; echo \"$name: $said is not met: not started\"; exit 0; }\n")
                }
            }
            if (triggered) append("[ -n \"${'$'}seraph_any\" ] || { echo \"none of its conditions is met\" > $skipped; echo \"$name: none of its conditions is met: not started\"; exit 0; }\n")
        }
    }

    /**
     * Its environment, with systemd's STATE_DIRECTORY, RUNTIME_DIRECTORY, CACHE_DIRECTORY, LOGS_DIRECTORY,
     * CONFIGURATION_DIRECTORY and CREDENTIALS_DIRECTORY.
     */
    private fun environment(name: String, unit: JSONObject): Map<String, String> {
        val env = linkedMapOf<String, String>()
        unit.optJSONObject("environment")?.let { json -> json.keys().forEach { env[it] = json.getString(it) } }
        for ((variable, key, base) in listOf(
            Triple("STATE_DIRECTORY", "stateDirectories", "/var/lib"), Triple("RUNTIME_DIRECTORY", "runtimeDirectories", "/run"),
            Triple("CACHE_DIRECTORY", "cacheDirectories", "/var/cache"), Triple("LOGS_DIRECTORY", "logsDirectories", "/var/log"),
            Triple("CONFIGURATION_DIRECTORY", "configurationDirectories", "/etc"),
        )) {
            unit.strings(key).takeIf { it.isNotEmpty() }?.let { env[variable] = it.joinToString(":") { dir -> "$base/$dir" } }
        }
        if (unit.has("credentials")) env["CREDENTIALS_DIRECTORY"] = credentialsDirectory(name)
        return env
    }

    private fun credentialsDirectory(name: String) = "/run/credentials/$name.service"

    /** A Limit* as the shell's ulimit sets it ("soft:hard" both); one it has not (RTTIME), left. */
    private fun ulimit(key: String, value: String): String {
        val (flag, unit) = LIMITS[key] ?: return ""
        fun amount(text: String): String {
            if (text == "infinity") return "unlimited"
            if (unit == 1L) return text.toLongOrNull()?.toString() ?: text
            val match = Regex("([0-9]+)([KMGTP]?)").matchEntire(text.trim()) ?: return text
            val factor = when (match.groupValues[2]) { "K" -> 1L shl 10; "M" -> 1L shl 20; "G" -> 1L shl 30; "T" -> 1L shl 40; "P" -> 1L shl 50; else -> 1L }
            return (match.groupValues[1].toLong() * factor / unit).toString()
        }
        val soft = amount(value.substringBefore(':'))
        val hard = amount(value.substringAfter(':', value.substringBefore(':')))
        return "ulimit -H $flag $hard 2> /dev/null; ulimit -S $flag $soft 2> /dev/null\n"
    }

    // --- What is there.

    /** The units the last build left, by name. */
    private fun applied(): Map<String, JSONObject> = dir.listFiles().orEmpty().filter { it.isDirectory }.mapNotNull { unitDir ->
        runCatching { unitDir.name to JSONObject(File(unitDir, "spec.json").readText()) }.getOrNull()
    }.toMap()

    /** The android part of the profile in place, if there is one. */
    private fun current(): JSONObject? = runCatching { JSONObject(File(data, "profile.json").readText()).optJSONObject("android") }.getOrNull()

    /** Whether a simple unit runs: its loop, and its program (a loop between failed starts is not running). */
    private fun running(name: String) = supervisorPid(name) != null && pid(File(dir, "$name/main.pid"))?.let { alive(it) } == true

    private fun status(name: String) = runCatching { File(dir, "$name/status").readText().trim() }.getOrDefault("")

    /** Whether it waits for its storage (unitConfig.RequiresMountsFor): a simple unit's loop, or a oneshot's waiter. */
    private fun waiting(name: String): Boolean {
        val unitDir = File(dir, name)
        return if (File(unitDir, "waiting").isFile) supervisorPid(name) != null
        else status(name) == WAITING && pid(File(unitDir, "waiter.pid"))?.let { cmdline(it).contains("seraph-wait:$name") } == true
    }

    /** The phone's storage it needs (unitConfig.RequiresMountsFor). */
    private fun mounts(unit: JSONObject) = unit.strings("mounts")

    /**
     * Whether [path] is on the phone's storage now: the nearest part of it that is there is not RAM, as the bare folder
     * an SD card (or the storage Android mounts once the phone is first unlocked) is mounted on is.
     */
    private fun onStorage(path: String): Boolean {
        var there = File(path)
        while (!there.exists()) there = there.parentFile ?: return false
        val type = Device.run("stat", "-f", "-c", "%T", there.path)
        return type.first == 0 && type.second.trim().let { it.isNotEmpty() && it != "tmpfs" }
    }

    /** Its loop's process, while it runs. */
    private fun supervisorPid(name: String): Int? = pid(File(dir, "$name/supervisor.pid"))?.takeIf { cmdline(it).contains(MARK + name) }

    private fun pid(file: File): Int? = runCatching { file.readText().trim().toInt() }.getOrNull()

    private fun alive(pid: Int) = File("/proc/$pid").exists()

    private fun cmdline(pid: Int) = runCatching { File("/proc/$pid/cmdline").readText().replace('\u0000', ' ') }.getOrDefault("")

    private fun tail(file: File) = runCatching { file.readLines().takeLast(8) }.getOrDefault(emptyList())

    /** The units to run: those wanted by multi-user.target, and those they want or require, and so on. */
    private fun active(specs: Map<String, JSONObject>): Set<String> {
        val active = specs.filterValues { it.optBoolean("wanted") }.keys.toMutableSet()
        do {
            val more = active.flatMap { needs(specs.getValue(it)) }.filter { it in specs && it !in active }
            active += more
        } while (more.isNotEmpty())
        return active
    }

    private fun needs(unit: JSONObject) = (unit.strings("wants") + unit.strings("requires")).distinct()

    /** [names] in the order they start: each after the ones it is after, and before the ones it is before. */
    private fun order(names: Collection<String>, specs: Map<String, JSONObject>): List<String> {
        val set = names.toSet()
        val first = set.associateWith { mutableSetOf<String>() }
        for (name in set) {
            val unit = specs[name] ?: continue
            unit.strings("after").filter { it in set }.forEach { first.getValue(name) += it }
            unit.strings("before").filter { it in set }.forEach { first.getValue(it) += name }
        }
        val done = linkedSetOf<String>()
        val visiting = mutableSetOf<String>()
        fun visit(name: String) {
            // A cycle is broken where it closes, as systemd breaks one.
            if (name in done || !visiting.add(name)) return
            first.getValue(name).sorted().forEach { visit(it) }
            visiting -= name
            done += name
        }
        set.sorted().forEach { visit(it) }
        return done.toList()
    }

    // --- The view and the loop.

    private fun busybox() = BUSYBOXES.firstOrNull { File(it).canExecute() }
        ?: throw Stop("there is no busybox (Magisk's or KernelSU's) to run the services with", 1)

    /** The view alone (see VIEW), for what runs in it without a build (backups). */
    private fun viewText() = VIEW.replace("@BUSYBOX@", busybox()).replace("@DATA@", data.path)

    fun writeView() {
        dir.mkdirs()
        view.writeText(viewText())
        background.writeText(BACKGROUND)
    }

    /**
     * The view and Seraph's /etc, with the shell of [android] (the profile's android part) as root's, for seraph-shell,
     * the app's terminal and ssh sessions, and the folders of systemd.tmpfiles; written by each build, also one that
     * starts no unit.
     */
    fun prepare(android: JSONObject?) {
        writeView()
        Etc.write(data, android)
        Tmpfiles.apply(data, android?.optJSONArray("tmpfiles"))
    }

    /** The services Seraph ran before units: stopped, Tailscale's and Syncthing's state moved to theirs, and gone. */
    private fun moveLegacy() {
        if (!legacy.isDirectory) return
        for (service in legacy.listFiles().orEmpty().filter { it.isDirectory }) {
            pid(File(service, "supervisor.pid"))?.let { Device.run("kill", "-TERM", "$it") }
            Thread.sleep(1000)
            File("/proc").listFiles().orEmpty().mapNotNull { it.name.toIntOrNull() }
                .filter { cmdline(it).startsWith(service.path + "/") }
                .forEach { Device.run("kill", "-KILL", "$it") }
        }
        for ((from, to) in listOf("tailscale/state" to "lib/tailscale", "syncthing/home" to "lib/syncthing")) {
            val old = File(legacy, from)
            val new = File(varDir, to)
            if (old.isDirectory && !new.exists()) {
                new.parentFile?.mkdirs()
                old.renameTo(new)
            }
        }
        legacy.deleteRecursively()
        Out.line("✔ Services: the earlier Seraph's moved over to units")
    }

    /** A command line of a unit, as systemd takes it, with what it needs to become shell words (see [parse]). */
    private class Command(val ignore: Boolean, val privileged: Boolean, private val words: List<List<Part>>) {
        /** Its words, with its variables' values from [env]; one with none, empty. */
        fun words(env: Map<String, String>): List<String> = words.flatMap { parts ->
            val whole = parts.singleOrNull() as? Part.Variable
            if (whole != null && whole.split) (env[whole.name] ?: "").split(Regex("\\s+")).filter { it.isNotEmpty() }
            else listOf(parts.joinToString("") { part -> when (part) { is Part.Text -> part.text; is Part.Variable -> env[part.name] ?: "" } })
        }

        /**
         * Its words for the shell: as [words], quoted; with [runtime], a variable [env] has not got is left to the shell,
         * which knows it when the unit runs (from its EnvironmentFile, its user's HOME), as systemd does.
         */
        fun shell(env: Map<String, String>, runtime: Boolean): List<String> = words.flatMap { parts ->
            val whole = (parts.singleOrNull() as? Part.Variable)?.takeIf { it.split }
            val unknown = runtime && parts.any { it is Part.Variable && it.name !in env }
            when {
                !unknown && whole != null -> (env[whole.name] ?: "").split(Regex("\\s+")).filter { it.isNotEmpty() }.map { quote(it) }
                !unknown -> listOf(quote(parts.joinToString("") { part -> when (part) { is Part.Text -> part.text; is Part.Variable -> env[part.name] ?: "" } }))
                // $NAME alone splits into words (its script has globs off: set -f).
                whole != null -> listOf("${'$'}{${whole.name}}")
                else -> listOf("\"" + parts.joinToString("") { part ->
                    when (part) {
                        is Part.Text -> inQuotes(part.text)
                        is Part.Variable -> env[part.name]?.let { inQuotes(it) } ?: "${'$'}{${part.name}}"
                    }
                } + "\"")
            }
        }

        /** [text] as it stays itself in the shell's double quotes. */
        private fun inQuotes(text: String) = text.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$").replace("`", "\\`")
    }

    private sealed class Part {
        class Text(val text: String) : Part()
        /** $NAME as a word of its own ([split]: its value's words), or ${NAME}, or $NAME in a word. */
        class Variable(val name: String, val split: Boolean) : Part()
    }

    /** [line] of unit [name] as systemd takes it (see the companion's [parse]), with its specifiers. */
    private fun parse(name: String, unit: JSONObject, line: String, arguments: Boolean = false): Command =
        parse(line, arguments) { letter -> specifier(name, unit, letter) }

    /** systemd's specifiers a command may have, and their values in the view. */
    private fun specifier(name: String, unit: JSONObject, letter: Char): String {
        val user = unit.string("user")
        val instance = name.substringAfter('@', "")
        return when (letter) {
            '%' -> "%"
            'n' -> "$name.service"
            'N' -> name
            'p' -> name.substringBefore('@')
            'P' -> name.substringBefore('@')
            'i', 'I' -> instance
            'j', 'J' -> name.substringBefore('@').substringAfterLast('-')
            't' -> "/run"
            'S' -> "/var/lib"
            'C' -> "/var/cache"
            'L' -> "/var/log"
            'E' -> "/etc"
            'T', 'V' -> "/tmp"
            'd' -> credentialsDirectory(name)
            'u' -> user ?: "root"
            'g' -> unit.string("group") ?: user ?: "root"
            'h' -> if (user != null) "${'$'}{HOME}" else File(data, "home").path
            'U' -> if (user != null) "${'$'}{seraph_uid}" else "0"
            'G' -> if (user != null) "${'$'}{seraph_gid}" else "0"
            'a' -> "arm64"
            'v' -> System.getProperty("os.version").orEmpty()
            else -> throw IllegalArgumentException("its command has %$letter, a systemd specifier not on the phone (%n, %N, %p, %i, %I, %t, %S, %C, %L, %E, %T, %d, %u, %g, %h, %U, %G, %a, %v and %% are)")
        }
    }

    companion object {
        private const val OK = "ok"
        private const val SKIPPED = "skipped"
        private const val WAITING = "waiting"
        private const val STORAGE_WHY = "the phone's storage: an SD card, or what Android mounts once the phone is first unlocked"

        /** systemd's Limit* keys as the phone's shell (mksh) sets them: its flag, and the bytes of its unit (1: a count). */
        private val LIMITS = mapOf(
            "CPU" to ("-t" to 1L), "FSIZE" to ("-f" to 512L), "DATA" to ("-d" to 1024L), "STACK" to ("-s" to 1024L),
            "CORE" to ("-c" to 512L), "RSS" to ("-m" to 1024L), "NOFILE" to ("-n" to 1L), "AS" to ("-v" to 1024L),
            "NPROC" to ("-p" to 1L), "MEMLOCK" to ("-l" to 1024L), "LOCKS" to ("-x" to 1L), "SIGPENDING" to ("-i" to 1L),
            "MSGQUEUE" to ("-q" to 1L), "NICE" to ("-e" to 1L), "RTPRIO" to ("-r" to 1L),
        )

        private val NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")

        /**
         * EnvironmentFile, as systemd reads one: NAME=value lines (a value in quotes, or not; "export " before it taken),
         * without blank lines and comments (# or ;). A file marked optional (- in the unit) may be missing.
         */
        private val ENVIRONMENT_FILE = """
            seraph_environment() {
              [ -r "${'$'}1" ] || { [ "${'$'}2" = optional ] && return 0; echo "${'$'}1: there is no such file (EnvironmentFile)" >&2; return 1; }
              while IFS= read -r seraph_line || [ -n "${'$'}seraph_line" ]; do
                while :; do case ${'$'}seraph_line in ' '* | '	'*) seraph_line=${'$'}{seraph_line#?} ;; *) break ;; esac; done
                case ${'$'}seraph_line in '' | '#'* | ';'*) continue ;; esac
                seraph_line=${'$'}{seraph_line#export }
                case ${'$'}seraph_line in [A-Za-z_]*=*) ;; *) continue ;; esac
                seraph_key=${'$'}{seraph_line%%=*}
                seraph_value=${'$'}{seraph_line#*=}
                case ${'$'}seraph_key in *[!A-Za-z0-9_]*) continue ;; esac
                case ${'$'}seraph_value in
                  \"*\") seraph_value=${'$'}{seraph_value#\"}; seraph_value=${'$'}{seraph_value%\"} ;;
                  \'*\') seraph_value=${'$'}{seraph_value#\'}; seraph_value=${'$'}{seraph_value%\'} ;;
                esac
                export "${'$'}seraph_key=${'$'}seraph_value"
              done < "${'$'}1"
            }
        """.trimIndent() + "\n"

        /**
         * Shell functions on the phone's storage, with busybox as ${'$'}b: on_storage ROOT PATH (the nearest part of PATH
         * that is there, under ROOT ("": the phone's own), is not RAM) and all_there FILE (each path in FILE is).
         */
        private val ON_STORAGE = """
            on_storage() {
              d=${'$'}2
              while [ "${'$'}d" != / ] && [ ! -e "${'$'}1${'$'}d" ]; do d=${'$'}{d%/*}; [ -n "${'$'}d" ] || d=/; done
              t=${'$'}("${'$'}b" stat -f -c %T "${'$'}1${'$'}d" 2> /dev/null) && [ -n "${'$'}t" ] && [ "${'$'}t" != tmpfs ]
            }
            all_there() { [ -f "${'$'}1" ] || return 0; while read -r p; do [ -z "${'$'}p" ] || on_storage "" "${'$'}p" || return 1; done < "${'$'}1"; return 0; }
        """.trimIndent()
        private const val ONESHOT_MINUTES = 10
        private const val MARK = "seraph-unit:"
        private val BUSYBOXES = listOf("/data/adb/magisk/busybox", "/data/adb/ksu/bin/busybox", "/data/adb/ap/bin/busybox")

        /**
         * Runs a unit's script ($1) as root in Seraph's view of the phone: a root of its own, in a mount namespace of
         * its own, with Android's own folders as they are, /nix (Seraph's store), Seraph's /etc, /var and /run (the
         * units' state, which stays), /home (the normal users' homes, Seraph's users folder) and a /tmp of its own,
         * and Android's time zone as TZ (its offset from UTC, "<+0300>-03:00": glibc does not read Android's zones).
         * Each step execs the next, so the script's own process is the one started.
         */
        private val VIEW = """
            # Seraph's view of the phone, for its services (written by the engine; see Units.kt).
            exec @BUSYBOX@ unshare -m --propagation private sh -c '
              b=${'$'}0 data=${'$'}1 script=${'$'}2
              set -e
              "${'$'}b" mount --make-rprivate /
              v=/dev/seraph-view-${'$'}${'$'}
              mkdir -p "${'$'}v"
              "${'$'}b" mount -t tmpfs -o mode=755 seraph "${'$'}v"
              for e in /*; do
                n=${'$'}{e#/}
                case ${'$'}n in nix | etc | var | run | home | tmp) continue ;; esac
                if [ -L "${'$'}e" ]; then ln -s "${'$'}(readlink "${'$'}e")" "${'$'}v/${'$'}n"
                elif [ -d "${'$'}e" ]; then mkdir -p "${'$'}v/${'$'}n"; "${'$'}b" mount --rbind "${'$'}e" "${'$'}v/${'$'}n"
                elif [ -f "${'$'}e" ]; then : > "${'$'}v/${'$'}n"; "${'$'}b" mount --bind "${'$'}e" "${'$'}v/${'$'}n"
                fi
              done
              # /dev holds the view itself: its copy inside the view goes.
              "${'$'}b" umount -l "${'$'}v${'$'}v" 2> /dev/null || true
              for d in nix:${'$'}data/nix/nix etc:${'$'}data/etc var:${'$'}data/var run:${'$'}data/run home:${'$'}data/users; do
                mkdir -p "${'$'}v/${'$'}{d%%:*}" "${'$'}{d#*:}"
                "${'$'}b" mount --bind "${'$'}{d#*:}" "${'$'}v/${'$'}{d%%:*}"
              done
              mkdir -p "${'$'}v/tmp"
              "${'$'}b" mount -t tmpfs -o mode=1777 tmp "${'$'}v/tmp"
              if [ -z "${'$'}TZ" ]; then
                off=${'$'}(/system/bin/date +%z 2> /dev/null)
                case ${'$'}off in
                  [+-][0-9][0-9][0-9][0-9])
                    hm=${'$'}{off#?}
                    case ${'$'}off in +*) sign=- ;; *) sign=+ ;; esac
                    export TZ="<${'$'}off>${'$'}sign${'$'}{hm%??}:${'$'}{hm#??}" ;;
                esac
              fi
              exec "${'$'}b" chroot "${'$'}v" /system/bin/sh "${'$'}script"
            ' @BUSYBOX@ @DATA@ "${'$'}1"
        """.trimIndent() + "\n"

        /**
         * Runs a command below the apps on the screen: in Android's system-background cpuset (its background cores),
         * at the nice given (absolute: busybox's renice without -n), with the lowest share of the storage's time of
         * the normal ones. What it starts keeps all three. Arguments: busybox, nice, then the command.
         */
        private val BACKGROUND = """
            # Runs a command below the apps on the screen (written by the engine; see Units.kt).
            b=${'$'}1 nice=${'$'}2
            shift 2
            for c in /dev/cpuset/system-background/cgroup.procs /dev/cpuset/system-background/tasks; do
              [ -w "${'$'}c" ] && echo ${'$'}${'$'} > "${'$'}c" 2> /dev/null && break
            done
            "${'$'}b" renice "${'$'}nice" -p ${'$'}${'$'} > /dev/null 2>&1
            /system/bin/ionice -c 2 -n 7 -p ${'$'}${'$'} > /dev/null 2>&1
            exec "${'$'}@"
        """.trimIndent() + "\n"

        /**
         * Keeps a simple (or forking) unit running: its start.sh in the view, its post.sh once it has started, and again
         * as its Restart says (after RestartSec), with its ExecStopPost (stoppost.sh) after each run; a TERM runs its
         * stop.sh (ExecStop), ends it as its KillMode, KillSignal and TimeoutStopSec say (control-group: all of its
         * processes, which its view's mount namespace tells, as systemd's cgroup does), then runs its stoppost.sh. A
         * forking unit's service is the process its PIDFile names, else the one left in its view after its start ends.
         * Its log is set aside past 1 MB. With notify.sh, it tells the app when the unit stops by itself (once, until it
         * has run again for a minute) and when it runs again.
         * With unitConfig.RequiresMountsFor (the unit folder's mounts) it waits for that storage before each start, and
         * while it runs stops it when the storage goes away and starts it again in a fresh view when it came after its
         * start: neither is a failure. A run whose condition is not met (its start.sh leaves "skipped") ends the loop, as
         * no failure. A clean end is exit status 0, SIGHUP, SIGINT, SIGTERM or SIGPIPE, or one of SuccessExitStatus;
         * RestartPreventExitStatus's codes keep it stopped, RestartForceExitStatus's start it again.
         * Arguments: busybox, view.sh, log, unit folder, name; the rest is in the unit folder's run.conf.
         */
        private val SUPERVISOR = """
            b=${'$'}1 view=${'$'}2 log=${'$'}3 unit=${'$'}4 name=${'$'}5
            . "${'$'}unit/run.conf"
            echo ${'$'}${'$'} > "${'$'}unit/supervisor.pid"
            child= watcher= main=
            tell() { [ -n "${'$'}notify" ] && "${'$'}b" sh "${'$'}notify" service "${'$'}@" "service:${'$'}name" < /dev/null > /dev/null 2>&1; }
            inview() { "${'$'}b" sh "${'$'}view" "${'$'}1" >> "${'$'}log" 2>&1 < /dev/null; }
            # Its processes: those in the mount namespace of the view its start made.
            procs() { [ -f "${'$'}unit/ns" ] || return 0; ns=${'$'}(cat "${'$'}unit/ns"); for p in /proc/[0-9]*; do [ "${'$'}(readlink "${'$'}p/ns/mnt" 2> /dev/null)" = "${'$'}ns" ] && echo "${'$'}{p#/proc/}"; done; return 0; }
            alive() { { [ -n "${'$'}main" ] && kill -0 "${'$'}main" 2> /dev/null; } || { [ "${'$'}killmode" = control-group ] && [ -n "${'$'}(procs)" ]; }; }
            # Ends it as KillMode says, with KillSignal; what still runs after TimeoutStopSec gets SIGKILL (unless SendSIGKILL is no).
            end() {
              case ${'$'}killmode in
                none) ;;
                process | mixed) [ -n "${'$'}main" ] && kill -"${'$'}killsignal" "${'$'}main" 2> /dev/null ;;
                *) [ -n "${'$'}main" ] && kill -"${'$'}killsignal" "${'$'}main" 2> /dev/null; for p in ${'$'}(procs); do kill -"${'$'}killsignal" "${'$'}p" 2> /dev/null; done ;;
              esac
              i=0
              while [ "${'$'}killmode" != none ] && alive && [ ${'$'}i -lt ${'$'}((timeout * 5)) ]; do sleep 0.2; i=${'$'}((i + 1)); done
              if [ -n "${'$'}sigkill" ] && [ "${'$'}killmode" != none ]; then
                [ -n "${'$'}main" ] && kill -9 "${'$'}main" 2> /dev/null
                [ "${'$'}killmode" = process ] || for p in ${'$'}(procs); do kill -9 "${'$'}p" 2> /dev/null; done
              fi
              return 0
            }
            stopping() {
              [ -n "${'$'}watcher" ] && kill ${'$'}watcher 2> /dev/null
              [ -n "${'$'}stop" ] && [ -n "${'$'}main" ] && inview "${'$'}stop"
              [ -n "${'$'}main" ] && end
              [ -n "${'$'}child" ] && wait ${'$'}child 2> /dev/null
              [ -n "${'$'}stoppost" ] && inview "${'$'}stoppost"
              rm -f "${'$'}unit/supervisor.pid" "${'$'}unit/main.pid" "${'$'}unit/waiting" "${'$'}unit/fresh" "${'$'}unit/ns" "${'$'}unit/ready"
              exit 0
            }
            trap stopping TERM INT
            @ON_STORAGE@
            # The first of its storage (unitConfig.RequiresMountsFor) the phone has not got now; the first the phone has got and
            # the view of process ${'$'}1 has not (mounted after it started, which a view does not take).
            missing() { [ -f "${'$'}unit/mounts" ] || return 1; while read -r p; do [ -z "${'$'}p" ] || on_storage "" "${'$'}p" || { echo "${'$'}p"; return 0; }; done < "${'$'}unit/mounts"; return 1; }
            stale() { [ -f "${'$'}unit/mounts" ] || return 1; while read -r p; do [ -n "${'$'}p" ] && on_storage "" "${'$'}p" && ! on_storage "/proc/${'$'}1/root" "${'$'}p" && { echo "${'$'}p"; return 0; }; done < "${'$'}unit/mounts"; return 1; }
            has() { case " ${'$'}2 " in *" ${'$'}1 "*) return 0 ;; esac; return 1; }
            while :; do
              [ -f "${'$'}log" ] && [ "${'$'}(stat -c %s "${'$'}log")" -gt 1048576 ] && mv -f "${'$'}log" "${'$'}log.old"
              # Its storage first: it waits for it, which is no failure.
              if p=${'$'}(missing); then
                echo "${'$'}(date '+%F %T') ${'$'}name: waiting for ${'$'}p (the phone's storage: an SD card, or what Android mounts once the phone is first unlocked)" >> "${'$'}log"
                : > "${'$'}unit/waiting"
                while missing > /dev/null; do sleep 5 & wait ${'$'}!; done
                rm -f "${'$'}unit/waiting"
              fi
              echo "${'$'}(date '+%F %T') ${'$'}name: starting" >> "${'$'}log"
              rm -f "${'$'}unit/skipped" "${'$'}unit/ns"
              "${'$'}b" sh "${'$'}view" "${'$'}start" >> "${'$'}log" 2>&1 < /dev/null &
              child=${'$'}!
              main=${'$'}child
              echo ${'$'}child > "${'$'}unit/main.pid"
              code=
              if [ "${'$'}type" = forking ]; then
                # Its start forks the service and ends: the service is the process its PIDFile names, else the one left in
                # its view.
                wait ${'$'}child
                code=${'$'}?
                child= main=
                if [ ${'$'}code -eq 0 ] && [ ! -f "${'$'}unit/skipped" ]; then
                  i=0
                  while [ -z "${'$'}main" ] && [ ${'$'}i -lt ${'$'}((timeout_start * 5)) ]; do
                    if [ -n "${'$'}pidfile" ]; then
                      main=${'$'}(tr -dc 0-9 < "${'$'}pidfile" 2> /dev/null)
                      [ -n "${'$'}main" ] && ! kill -0 "${'$'}main" 2> /dev/null && main=
                    else
                      main=${'$'}(procs | head -n 1)
                    fi
                    [ -n "${'$'}main" ] || { sleep 0.2; i=${'$'}((i + 1)); }
                  done
                  if [ -n "${'$'}main" ]; then echo ${'$'}main > "${'$'}unit/main.pid"; code=; else echo "${'$'}(date '+%F %T') ${'$'}name: its service was not found after its start (PIDFile: ${'$'}{pidfile:-none})" >> "${'$'}log"; code=1; fi
                fi
                : > "${'$'}unit/ready"
              fi
              if [ -z "${'$'}code" ]; then
                if [ -f "${'$'}unit/failing" ]; then
                  ( sleep 60; kill -0 ${'$'}main 2> /dev/null && rm "${'$'}unit/failing" 2> /dev/null && tell ok "${'$'}name runs again" "It had stopped by itself" ) < /dev/null > /dev/null 2>&1 &
                fi
                [ -n "${'$'}post" ] && [ ! -f "${'$'}unit/skipped" ] && inview "${'$'}post"
                # While it runs, its storage is looked at every 15 s: one that went away stops it (to wait for it again), one
                # that came after its start starts it again in a fresh view.
                rm -f "${'$'}unit/fresh"
                if [ -f "${'$'}unit/mounts" ]; then
                  (
                    while sleep 15; do
                      kill -0 ${'$'}main 2> /dev/null || exit 0
                      if p=${'$'}(missing); then why="${'$'}p went away: waiting for it again"
                      elif p=${'$'}(stale ${'$'}main); then why="${'$'}p was mounted after it started: starting it again in a fresh view"
                      else continue; fi
                      echo "${'$'}why" > "${'$'}unit/fresh"
                      [ -n "${'$'}stop" ] && "${'$'}b" sh "${'$'}view" "${'$'}stop" >> "${'$'}log" 2>&1 < /dev/null
                      kill ${'$'}main 2> /dev/null
                      exit 0
                    done
                  ) < /dev/null > /dev/null 2>&1 &
                  watcher=${'$'}!
                fi
                if [ -n "${'$'}child" ]; then
                  wait ${'$'}child
                  code=${'$'}?
                else
                  # A forking unit's service is not this loop's child: it is watched, and its end is a failure (its status
                  # is its parent's to know).
                  while kill -0 "${'$'}main" 2> /dev/null; do sleep 1 & wait ${'$'}!; done
                  code=1
                fi
                [ -n "${'$'}watcher" ] && kill ${'$'}watcher 2> /dev/null
              fi
              # What is left of it ends with it (KillMode control-group), as systemd ends a service's processes.
              [ "${'$'}killmode" = control-group ] && [ -n "${'$'}(procs)" ] && { main=; end; }
              child= watcher= main=
              rm -f "${'$'}unit/main.pid"
              [ -n "${'$'}stoppost" ] && inview "${'$'}stoppost"
              if [ -f "${'$'}unit/fresh" ]; then
                # Stopped for its storage: no failure, and it starts again (or waits) whatever Restart says.
                echo "${'$'}(date '+%F %T') ${'$'}name: ${'$'}(cat "${'$'}unit/fresh")" >> "${'$'}log"
                rm -f "${'$'}unit/fresh"
                continue
              fi
              if [ -f "${'$'}unit/skipped" ]; then
                echo "${'$'}(date '+%F %T') ${'$'}name: not started: ${'$'}(cat "${'$'}unit/skipped")" >> "${'$'}log"
                break
              fi
              echo "${'$'}(date '+%F %T') ${'$'}name: stopped (${'$'}code)" >> "${'$'}log"
              # A clean end: 0, SIGHUP, SIGINT, SIGPIPE or SIGTERM, or SuccessExitStatus; a signal else is abnormal.
              clean=
              case ${'$'}code in 0 | 129 | 130 | 141 | 143) clean=1 ;; esac
              has ${'$'}code "${'$'}success" && clean=1
              abnormal=
              [ -z "${'$'}clean" ] && [ ${'$'}code -gt 128 ] && abnormal=1
              again=
              case ${'$'}restart in
                always) again=1 ;;
                on-success) [ -n "${'$'}clean" ] && again=1 ;;
                on-failure) [ -z "${'$'}clean" ] && again=1 ;;
                on-abnormal | on-abort) [ -n "${'$'}abnormal" ] && again=1 ;;
              esac
              has ${'$'}code "${'$'}prevent" && again=
              has ${'$'}code "${'$'}force" && again=1
              # Told once: a failure, or a unit kept running (always) that stopped by itself.
              if { [ -z "${'$'}clean" ] || { [ "${'$'}restart" = always ] && ! has ${'$'}code "${'$'}success"; }; } && ! has ${'$'}code "${'$'}force" && [ ! -f "${'$'}unit/failing" ]; then
                : > "${'$'}unit/failing"
                if [ -n "${'$'}again" ]; then tell error "${'$'}name stopped" "Exit status ${'$'}code; it starts again in ${'$'}{delay%.000} s"
                else tell error "${'$'}name stopped" "Exit status ${'$'}code"; fi
              fi
              [ -n "${'$'}again" ] || break
              sleep "${'$'}delay" &
              wait ${'$'}!
            done
            rm -f "${'$'}unit/supervisor.pid" "${'$'}unit/main.pid" "${'$'}unit/waiting" "${'$'}unit/ns"
        """.trimIndent().replace("@ON_STORAGE@", ON_STORAGE)

        /** A signal's name or number (KillSignal: "SIGTERM", "TERM", 15) as kill takes it. */
        private fun signal(text: String): String = text.removePrefix("SIG").ifEmpty { "TERM" }

        /**
         * A command line as systemd takes it: its prefixes (- leaves a failure be; + ! run it as root, as units of a
         * user run their privileged steps; !! needs nothing, as the phone has ambient capabilities; : leaves $ as it is;
         * @ is not on the phone), specifiers (%n, ..., from [specifier]), quotes and backslashes, and $NAME / ${'$'}{NAME}
         * ($NAME alone splits into words, as systemd does; $$ is $). With [arguments], its first word may be anything.
         */
        private fun parse(line: String, arguments: Boolean, specifier: (Char) -> String): Command {
            var rest = line.trimStart()
            var ignore = false
            var expand = true
            var privileged = false
            while (rest.isNotEmpty() && rest[0] in "-+!:@") {
                when (rest[0]) {
                    '-' -> ignore = true
                    ':' -> expand = false
                    '+' -> privileged = true
                    '!' -> if (rest.startsWith("!!")) rest = rest.substring(1) else privileged = true
                    '@' -> throw IllegalArgumentException("its command starts with @ (another name for the program), which is not on the phone")
                }
                rest = rest.substring(1)
            }
            val specified = buildString {
                var i = 0
                while (i < rest.length) {
                    if (rest[i] == '%' && i + 1 < rest.length) {
                        append(specifier(rest[i + 1]))
                        i += 2
                    } else {
                        append(rest[i++])
                    }
                }
            }
            val words = mutableListOf<List<Part>>()
            var i = 0
            val s = specified
            while (i < s.length) {
                while (i < s.length && s[i].isWhitespace()) i++
                if (i >= s.length) break
                // $NAME as a word of its own is its value's words.
                val whole = if (expand) WHOLE_VARIABLE.at(s, i) else null
                if (whole != null) {
                    words += listOf(Part.Variable(whole.groupValues[1], split = true))
                    i = whole.range.last + 1
                    continue
                }
                val parts = mutableListOf<Part>()
                val text = StringBuilder()
                var quote: Char? = null
                while (i < s.length && (quote != null || !s[i].isWhitespace())) {
                    val c = s[i]
                    when {
                        quote == null && (c == '"' || c == '\'') -> quote = c
                        quote != null && c == quote -> quote = null
                        c == '\\' && i + 1 < s.length && quote != '\'' -> {
                            i++
                            text.append(
                                when (s[i]) {
                                    'n' -> '\n'
                                    't' -> '\t'
                                    else -> s[i]
                                },
                            )
                        }
                        expand && c == '$' && quote != '\'' && i + 1 < s.length && s[i + 1] == '$' -> {
                            text.append('$')
                            i++
                        }
                        expand && c == '$' && quote != '\'' -> {
                            val match = VARIABLE.at(s, i)
                            if (match != null) {
                                if (text.isNotEmpty()) parts += Part.Text(text.toString()).also { text.clear() }
                                parts += Part.Variable(match.groupValues[1].ifEmpty { match.groupValues[2] }, split = false)
                                i = match.range.last
                            } else {
                                text.append(c)
                            }
                        }
                        else -> text.append(c)
                    }
                    i++
                }
                if (quote != null) throw IllegalArgumentException("its command has a quote that does not end: $line")
                if (text.isNotEmpty() || parts.isEmpty()) parts += Part.Text(text.toString())
                words += parts
            }
            if (words.isEmpty()) throw IllegalArgumentException("its command is empty")
            val first = words[0].singleOrNull() as? Part.Text
            if (!arguments && first != null && !first.text.startsWith("/")) {
                throw IllegalArgumentException("its command is not a full path, as systemd needs: ${first.text}")
            }
            return Command(ignore, privileged, words)
        }

        /** A systemd time span (RestartSec: "10", "5s", "100ms", "1min 30s") in seconds, as sleep takes it. */
        fun seconds(span: String): String {
            val units = mapOf("us" to 0.000001, "ms" to 0.001, "s" to 1.0, "sec" to 1.0, "m" to 60.0, "min" to 60.0, "h" to 3600.0, "hr" to 3600.0)
            var total = 0.0
            for (part in Regex("([0-9.]+)\\s*([a-z]*)").findAll(span)) {
                total += (part.groupValues[1].toDoubleOrNull() ?: 0.0) * (units[part.groupValues[2].ifEmpty { "s" }] ?: 1.0)
            }
            return String.format(java.util.Locale.ROOT, "%.3f", total.coerceAtLeast(0.1))
        }

        private fun quote(text: String) = "'" + text.replace("'", "'\\''") + "'"

        private val WHOLE_VARIABLE = Regex("\\$([A-Za-z_][A-Za-z0-9_]*)(?=\\s|$)")
        private val VARIABLE = Regex("\\$\\{([A-Za-z_][A-Za-z0-9_]*)\\}|\\$([A-Za-z_][A-Za-z0-9_]*)")

        /** A match of this pattern that starts right at [index] of [text], if there is one. */
        private fun Regex.at(text: String, index: Int) = find(text, index)?.takeIf { it.range.first == index }

        private fun oneshot(unit: JSONObject) = unit.optString("type") == "oneshot"

        private fun steps(unit: JSONObject) = listOf("condition", "pre", "start", "post", "stop", "stopPost").flatMap { unit.objects(it) }

        private fun JSONObject.objects(key: String): List<JSONObject> = optJSONArray(key)?.let { array -> (0 until array.length()).map { array.getJSONObject(it) } } ?: emptyList()
    }
}
