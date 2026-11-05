package dev.seraph.manager.engine

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The config's services: its systemd.services units (Seraph's own services are modules that make them; see
 * nix/units.nix), run as systemd runs them on NixOS. Each runs as root in Seraph's view of the phone ([VIEW]): the
 * phone as it is, with /nix (Seraph's store, where the build fetched the units' packages), Seraph's /etc (see [Etc]),
 * /var and /run (the units' state, in /data/adb/seraph/var and /data/adb/seraph/run) and a /tmp of its own. A simple
 * unit runs under a shell loop ([SUPERVISOR]) that starts it again as its Restart says; a oneshot runs to its end, and
 * what it says is shown. Like the settings, they are planned and checked first ([plan]) and changed after ([apply]):
 * the units that left the config or changed are stopped, then the ones to start start, in order (after, before,
 * wants, requires). At boot ([start]) the units the last build left start again; when the module goes ([removeAll])
 * they all stop. A unit's state (/var/lib) stays when it leaves the config, as on NixOS; the module's removal takes it.
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

    /** Whether the plan changes anything. */
    val changes get() = toStop.isNotEmpty() || toStart.isNotEmpty() || leaving.isNotEmpty() || legacy.exists()

    /** [secrets]: the files of the secrets the build changes; a unit that names one runs again, as with a changed unit. */
    fun plan(units: JSONObject?, problems: MutableList<String>, secrets: List<String> = emptyList()) {
        wanted = units?.let { json -> json.keys().asSequence().associateWith { json.getJSONObject(it) } } ?: emptyMap()
        val had = applied()
        val active = active(wanted)
        for ((name, unit) in wanted) {
            val env = environment(name, unit)
            for (step in steps(unit)) {
                val command = step.optString("command").ifEmpty { null } ?: continue
                runCatching { command(name, command, env) }.onFailure { problems += "systemd.services.$name: ${it.message}" }
            }
        }
        leaving = had.keys.filter { it !in wanted }
        // Changed: its unit, or the scripts the engine makes of it (as NixOS restarts a unit whose unit file changed).
        // Or a secret file it reads (a new auth key): the unit is the same, what it reads is not.
        // Or Seraph's view itself (a newer Seraph's, with another /home, ...): a unit running in the one before starts
        // again in the new one (a oneshot that ran is left).
        val viewChanged = view.isFile && runCatching { view.readText() }.getOrNull() != viewText()
        val changed = wanted.keys.filter { name ->
            had[name]?.let { it.toString() != wanted.getValue(name).toString() || !written(name, wanted.getValue(name)) } == true ||
                (had[name] != null && reads(wanted.getValue(name), secrets)) ||
                (viewChanged && had[name] != null && !oneshot(wanted.getValue(name)) && running(name))
        }
        val start = mutableListOf<String>()
        for (name in active) {
            val unit = wanted.getValue(name)
            when {
                had[name] == null || name in changed -> start += name
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
        toStop = order((had.keys.filter { it in wanted && it !in active } + changed).filter { supervisorPid(it) != null }.distinct(), had).reversed()
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
                waiting(name) -> "waiting for its storage (${mounts(unit).firstOrNull { !onStorage(it) } ?: "it is there now"})"
                once -> "done"
                else -> "running"
            }
            Out.line("  $name: $what")
        }
    }

    /** Carries the plan out, with the shell of [android] (the profile's android part) as root's; the number of units that did not start. */
    fun apply(android: JSONObject? = null): Int {
        moveLegacy()
        if (toStart.isEmpty() && toStop.isEmpty() && leaving.isEmpty()) return 0
        writeView()
        Etc.write(data, android)
        toStop.forEach { stop(it) }
        for (name in leaving) {
            stop(name)
            File(dir, name).deleteRecursively()
            Out.line("✔ $name: removed (it left the config)")
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
     * waits to start it again) or stopped; a oneshot done, failed, or not run (nothing wants it). Also since when
     * (its start, or its run), and its log.
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
                once -> when (status(name)) { OK -> "done"; "" -> "not run"; WAITING -> "waiting"; else -> "failed" }
                running(name) -> "running"
                supervisorPid(name) != null -> "restarting"
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
        if (supervisorPid(name) != null) stop(name)
        // Started anew: a failure told before is the past.
        File(unitDir, "failing").delete()
        for (state in unit.strings("stateDirectories")) File(varDir, "lib/$state").mkdirs()
        // Its runtime folders start empty, as systemd makes them.
        for (runtime in unit.strings("runtimeDirectories")) File(runDir, runtime).apply { deleteRecursively(); mkdirs() }
        writeScripts(name, unit, unitDir)
        File(unitDir, "spec.json").writeText(unit.toString())
        // The storage it needs (unitConfig.RequiresMountsFor), one path a line, for its loop.
        val mounts = mounts(unit)
        File(unitDir, "mounts").let { if (mounts.isEmpty()) it.delete() else it.writeText(mounts.joinToString("") { path -> "$path\n" }) }
        return if (oneshot(unit)) runOnce(name, unitDir, unit) else launch(name, unit, unitDir)
    }

    /** A simple unit, under its loop; told about at once if it stops at once (a wrong command, a missing file). */
    private fun launch(name: String, unit: JSONObject, unitDir: File): Boolean {
        val busybox = busybox()
        val log = File(unitDir, "log")
        val devNull = File("/dev/null")
        val optional = { file: String -> File(unitDir, file).takeIf { it.isFile }?.path.orEmpty() }
        // Its loop tells the app when it stops by itself, and when it runs again (unless its notifications are off).
        val notify = Notify.module?.takeIf { notifies(unit) }?.let { File(it, "notify.sh").path }.orEmpty()
        if (!background.isFile) writeView()
        // Below the apps on the screen (see BACKGROUND), its loop and so all it starts.
        ProcessBuilder(
            busybox, "sh", background.path, busybox, nice(unit),
            busybox, "setsid", "sh", "-c", SUPERVISOR, MARK + name, busybox, view.path, log.path, unitDir.path, name,
            unit.optString("restart", "no"), seconds(unit.optString("restartSec", "0.1")),
            File(unitDir, "start.sh").path, optional("post.sh"), optional("stop.sh"), notify,
            unit.ints("successExitStatus").joinToString(" "), unit.ints("restartForceExitStatus").joinToString(" "),
        ).directory(unitDir).redirectInput(devNull).redirectOutput(devNull).redirectError(devNull).apply { unitEnvironment(environment()) }.start()
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
        Out.line("✘ $name: it stopped at once; the end of its log (${log.path}):")
        val end = tail(log)
        end.forEach { Out.line("    $it") }
        if (notifies(unit)) Notify.event("service", "error", "$name did not start", end.lastOrNull { it.isNotBlank() }.orEmpty(), "service:$name", quiet = quietFailures)
        return false
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
     * A oneshot, to its end; what it says is shown (and kept in its log). One whose storage is not there yet runs once it
     * is, from a waiter of its own: as a unit waiting, not a failure.
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
        if (!background.isFile) writeView()
        val process = ProcessBuilder(busybox(), "sh", background.path, busybox(), nice(unit), busybox(), "sh", view.path, File(unitDir, "start.sh").path)
            .directory(unitDir).redirectErrorStream(true).redirectInput(File("/dev/null")).apply { unitEnvironment(environment()) }.start()
        val reader = Thread {
            runCatching {
                log.appendText("${Out.date(System.currentTimeMillis())} $name\n")
                process.inputStream.bufferedReader().forEachLine { line ->
                    Out.line(line)
                    log.appendText(line + "\n")
                }
            }
        }.apply { start() }
        val done = process.waitFor(ONESHOT_MINUTES, TimeUnit.MINUTES)
        if (!done) process.destroyForcibly()
        reader.join(2000)
        val ok = done && process.exitValue() == 0
        File(unitDir, "status").writeText(if (ok) OK else "failed")
        if (ok) {
            Out.line("✔ $name: done")
        } else {
            val why = if (done) "failed (${process.exitValue()})" else "did not end in $ONESHOT_MINUTES minutes"
            Out.line("✘ $name: $why; its log: ${log.path}")
            if (notifies(unit)) Notify.event("service", "error", "$name $why", unit.optString("description"), "service:$name", quiet = quietFailures)
        }
        return ok
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

    /** Stops a simple unit: its ExecStop, then its process; its runtime folders go, as systemd's do. */
    private fun stop(name: String) {
        val unitDir = File(dir, name)
        // A oneshot's waiter for its storage, too.
        pid(File(unitDir, "waiter.pid"))?.takeIf { cmdline(it).contains("seraph-wait:$name") }?.let { Device.run("kill", "-TERM", "$it") }
        File(unitDir, "waiter.pid").delete()
        val supervisor = supervisorPid(name)
        if (supervisor != null) {
            Device.run("kill", "-TERM", "$supervisor")
            var tries = 0
            while (supervisorPid(name) != null && tries++ < 75) Thread.sleep(200)
            supervisorPid(name)?.let { Device.run("kill", "-KILL", "$it") }
        }
        pid(File(unitDir, "main.pid"))?.takeIf { alive(it) }?.let { Device.run("kill", "-KILL", "$it") }
        File(unitDir, "supervisor.pid").delete()
        File(unitDir, "main.pid").delete()
        runCatching { JSONObject(File(unitDir, "spec.json").readText()) }.getOrNull()
            ?.strings("runtimeDirectories")?.forEach { File(runDir, it).deleteRecursively() }
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

    /** Its scripts, by file name: start.sh (ExecStartPre, then ExecStart; a oneshot's ExecStartPost too), post.sh, stop.sh. */
    private fun scripts(name: String, unit: JSONObject, unitDir: File): Map<String, String> {
        val files = linkedMapOf<String, String>()
        val env = environment(name, unit)
        val shell = unit.optString("shell")
        var scripts = 0
        fun line(step: JSONObject, how: String): String {
            val (ignore, words) = if (step.has("script")) {
                val file = "script-${scripts++}.sh"
                files[file] = step.getString("script")
                false to listOf(shell, "-e", File(unitDir, file).path)
            } else {
                command(name, step.getString("command"), env)
            }
            val run = words.joinToString(" ") { quote(it) }
            return when {
                how == "exec" -> "exec $run"
                ignore || how == "stop" -> "$run || true"
                else -> "$run || exit \$?"
            }
        }
        val head = buildString {
            append("# $name, as Seraph runs it (the config's systemd.services.$name): ${unit.optString("description")}\n")
            if (env.isNotEmpty()) append("export ").append(env.entries.joinToString(" ") { (key, value) -> "$key=${quote(value)}" }).append('\n')
            append("cd ${quote(unit.string("workingDirectory") ?: "/")} || exit 1\n")
        }
        val pre = unit.objects("pre").map { line(it, "run") }
        val start = unit.objects("start")
        val post = unit.objects("post").map { line(it, "run") }
        if (oneshot(unit)) {
            files["start.sh"] = head + (pre + start.map { line(it, "run") } + post).joinToString("") { "$it\n" }
        } else {
            files["start.sh"] = head + (pre + line(start.single(), "exec")).joinToString("") { "$it\n" }
            if (post.isNotEmpty()) files["post.sh"] = head + post.joinToString("") { "$it\n" }
        }
        val stop = unit.objects("stop").map { line(it, "stop") }
        if (stop.isNotEmpty()) files["stop.sh"] = head + stop.joinToString("") { "$it\n" }
        return files
    }

    /** Its environment, with systemd's STATE_DIRECTORY and RUNTIME_DIRECTORY. */
    private fun environment(name: String, unit: JSONObject): Map<String, String> {
        val env = linkedMapOf<String, String>()
        unit.optJSONObject("environment")?.let { json -> json.keys().forEach { env[it] = json.getString(it) } }
        unit.strings("stateDirectories").takeIf { it.isNotEmpty() }?.let { env["STATE_DIRECTORY"] = it.joinToString(":") { dir -> "/var/lib/$dir" } }
        unit.strings("runtimeDirectories").takeIf { it.isNotEmpty() }?.let { env["RUNTIME_DIRECTORY"] = it.joinToString(":") { dir -> "/run/$dir" } }
        return env
    }

    // --- What is there.

    /** The units the last build left, by name. */
    private fun applied(): Map<String, JSONObject> = dir.listFiles().orEmpty().filter { it.isDirectory }.mapNotNull { unitDir ->
        runCatching { unitDir.name to JSONObject(File(unitDir, "spec.json").readText()) }.getOrNull()
    }.toMap()

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
     * the app's terminal and ssh sessions; written by each build, also one that starts no unit.
     */
    fun prepare(android: JSONObject?) {
        writeView()
        Etc.write(data, android)
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

    companion object {
        private const val OK = "ok"
        private const val WAITING = "waiting"
        private const val STORAGE_WHY = "the phone's storage: an SD card, or what Android mounts once the phone is first unlocked"

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
        private const val ONESHOT_MINUTES = 10L
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
         * Keeps a simple unit running: its start.sh in the view, its post.sh once it has started, and again as its
         * Restart says (after RestartSec); a TERM runs its stop.sh, then stops it. Its log is set aside past 1 MB.
         * With notify.sh, it tells the app when the unit stops by itself (once, until it has run again for a minute)
         * and when it runs again.
         * With unitConfig.RequiresMountsFor (the unit folder's mounts) it waits for that storage before each start, and
         * while it runs stops it when the storage goes away and starts it again in a fresh view when it came after its
         * start: neither is a failure. SuccessExitStatus's codes are no failure, RestartForceExitStatus's start it again.
         * Arguments: busybox, view.sh, log, unit folder, name, restart, seconds, start.sh, post.sh, stop.sh, notify.sh,
         * SuccessExitStatus's codes, RestartForceExitStatus's codes.
         */
        private val SUPERVISOR = """
            b=${'$'}1 view=${'$'}2 log=${'$'}3 unit=${'$'}4 name=${'$'}5 restart=${'$'}6 delay=${'$'}7 start=${'$'}8 post=${'$'}9 stop=${'$'}{10} notify=${'$'}{11} success=${'$'}{12} force=${'$'}{13}
            echo ${'$'}${'$'} > "${'$'}unit/supervisor.pid"
            child= watcher=
            tell() { [ -n "${'$'}notify" ] && "${'$'}b" sh "${'$'}notify" service "${'$'}@" "service:${'$'}name" < /dev/null > /dev/null 2>&1; }
            stopping() {
              [ -n "${'$'}watcher" ] && kill ${'$'}watcher 2> /dev/null
              [ -n "${'$'}stop" ] && [ -n "${'$'}child" ] && "${'$'}b" sh "${'$'}view" "${'$'}stop" >> "${'$'}log" 2>&1 < /dev/null
              [ -n "${'$'}child" ] && kill ${'$'}child 2> /dev/null && wait ${'$'}child
              rm -f "${'$'}unit/supervisor.pid" "${'$'}unit/main.pid" "${'$'}unit/waiting" "${'$'}unit/fresh"
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
              "${'$'}b" sh "${'$'}view" "${'$'}start" >> "${'$'}log" 2>&1 < /dev/null &
              child=${'$'}!
              echo ${'$'}child > "${'$'}unit/main.pid"
              if [ -f "${'$'}unit/failing" ]; then
                ( sleep 60; kill -0 ${'$'}child 2> /dev/null && rm "${'$'}unit/failing" 2> /dev/null && tell ok "${'$'}name runs again" "It had stopped by itself" ) < /dev/null > /dev/null 2>&1 &
              fi
              [ -n "${'$'}post" ] && "${'$'}b" sh "${'$'}view" "${'$'}post" >> "${'$'}log" 2>&1 < /dev/null
              # While it runs, its storage is looked at every 15 s: one that went away stops it (to wait for it again), one that
              # came after its start starts it again in a fresh view.
              rm -f "${'$'}unit/fresh"
              if [ -f "${'$'}unit/mounts" ]; then
                (
                  while sleep 15; do
                    kill -0 ${'$'}child 2> /dev/null || exit 0
                    if p=${'$'}(missing); then why="${'$'}p went away: waiting for it again"
                    elif p=${'$'}(stale ${'$'}child); then why="${'$'}p was mounted after it started: starting it again in a fresh view"
                    else continue; fi
                    echo "${'$'}why" > "${'$'}unit/fresh"
                    [ -n "${'$'}stop" ] && "${'$'}b" sh "${'$'}view" "${'$'}stop" >> "${'$'}log" 2>&1 < /dev/null
                    kill ${'$'}child 2> /dev/null
                    exit 0
                  done
                ) < /dev/null > /dev/null 2>&1 &
                watcher=${'$'}!
              fi
              wait ${'$'}child
              code=${'$'}?
              [ -n "${'$'}watcher" ] && kill ${'$'}watcher 2> /dev/null
              child= watcher=
              rm -f "${'$'}unit/main.pid"
              if [ -f "${'$'}unit/fresh" ]; then
                # Stopped for its storage: no failure, and it starts again (or waits) whatever Restart says.
                echo "${'$'}(date '+%F %T') ${'$'}name: ${'$'}(cat "${'$'}unit/fresh")" >> "${'$'}log"
                rm -f "${'$'}unit/fresh"
                continue
              fi
              echo "${'$'}(date '+%F %T') ${'$'}name: stopped (${'$'}code)" >> "${'$'}log"
              # SuccessExitStatus: no failure; RestartForceExitStatus: it starts again whatever Restart says (Syncthing's 3: a
              # restart it asked for).
              failed=
              [ ${'$'}code -ne 0 ] && ! has ${'$'}code "${'$'}success" && failed=1
              again=
              case ${'$'}restart in
                always) again=1 ;;
                on-failure) [ -n "${'$'}failed" ] && again=1 ;;
              esac
              has ${'$'}code "${'$'}force" && again=1
              if { [ -n "${'$'}failed" ] || { [ "${'$'}restart" = always ] && ! has ${'$'}code "${'$'}success"; }; } && ! has ${'$'}code "${'$'}force" && [ ! -f "${'$'}unit/failing" ]; then
                : > "${'$'}unit/failing"
                if [ -n "${'$'}again" ]; then tell error "${'$'}name stopped" "Exit status ${'$'}code; it starts again in ${'$'}{delay%.000} s"
                else tell error "${'$'}name stopped" "Exit status ${'$'}code"; fi
              fi
              [ -n "${'$'}again" ] || break
              sleep "${'$'}delay" &
              wait ${'$'}!
            done
            rm -f "${'$'}unit/supervisor.pid" "${'$'}unit/main.pid" "${'$'}unit/waiting"
        """.trimIndent().replace("@ON_STORAGE@", ON_STORAGE)

        /** systemd's specifiers a command may have, and their values in the view. */
        private fun specifier(name: String, letter: Char): String = when (letter) {
            '%' -> "%"
            'n' -> "$name.service"
            'N', 'p' -> name
            't' -> "/run"
            'S' -> "/var/lib"
            'C' -> "/var/cache"
            'L' -> "/var/log"
            'T' -> "/tmp"
            else -> throw IllegalArgumentException("its command has %$letter, a systemd specifier not on the phone (%n, %N, %t, %S, %C, %L, %T and %% are)")
        }

        /**
         * A command line as systemd takes it: its prefixes (- leaves a failure be; + ! !! need nothing more, as units run
         * as root; : leaves $ as it is), specifiers (%n, ...), quotes and backslashes, and $NAME / ${'$'}{NAME} from
         * its environment ($NAME alone splits into words, as systemd does); whether a failure is left be, and its words.
         */
        fun command(name: String, line: String, env: Map<String, String>): Pair<Boolean, List<String>> {
            var rest = line.trimStart()
            var ignore = false
            var expand = true
            while (rest.isNotEmpty() && rest[0] in "-+!:@") {
                when (rest[0]) {
                    '-' -> ignore = true
                    ':' -> expand = false
                    '@' -> throw IllegalArgumentException("its command starts with @ (another name for the program), which is not on the phone")
                }
                rest = rest.substring(1)
            }
            val specified = buildString {
                var i = 0
                while (i < rest.length) {
                    if (rest[i] == '%' && i + 1 < rest.length) {
                        append(specifier(name, rest[i + 1]))
                        i += 2
                    } else {
                        append(rest[i++])
                    }
                }
            }
            val words = mutableListOf<String>()
            var i = 0
            val s = specified
            while (i < s.length) {
                while (i < s.length && s[i].isWhitespace()) i++
                if (i >= s.length) break
                // $NAME as a word of its own is its value's words.
                val whole = if (expand) WHOLE_VARIABLE.at(s, i) else null
                if (whole != null) {
                    words += (env[whole.groupValues[1]] ?: "").split(Regex("\\s+")).filter { it.isNotEmpty() }
                    i = whole.range.last + 1
                    continue
                }
                val word = StringBuilder()
                var quote: Char? = null
                while (i < s.length && (quote != null || !s[i].isWhitespace())) {
                    val c = s[i]
                    when {
                        quote == null && (c == '"' || c == '\'') -> quote = c
                        quote != null && c == quote -> quote = null
                        c == '\\' && i + 1 < s.length && quote != '\'' -> {
                            i++
                            word.append(
                                when (s[i]) {
                                    'n' -> '\n'
                                    't' -> '\t'
                                    else -> s[i]
                                },
                            )
                        }
                        expand && c == '$' && quote != '\'' -> {
                            val match = VARIABLE.at(s, i)
                            if (match != null) {
                                word.append(env[match.groupValues[1].ifEmpty { match.groupValues[2] }] ?: "")
                                i = match.range.last
                            } else {
                                word.append(c)
                            }
                        }
                        else -> word.append(c)
                    }
                    i++
                }
                if (quote != null) throw IllegalArgumentException("its command has a quote that does not end: $line")
                words += word.toString()
            }
            if (words.isEmpty()) throw IllegalArgumentException("its command is empty")
            if (!words[0].startsWith("/")) throw IllegalArgumentException("its command is not a full path, as systemd needs: ${words[0]}")
            return ignore to words
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

        private fun steps(unit: JSONObject) = listOf("pre", "start", "post", "stop").flatMap { unit.objects(it) }

        private fun JSONObject.objects(key: String): List<JSONObject> = optJSONArray(key)?.let { array -> (0 until array.length()).map { array.getJSONObject(it) } } ?: emptyList()
    }
}
