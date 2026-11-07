package dev.nixup.manager.engine

import android.system.Os
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The config's path units (systemd.paths; see nix/units.nix): each starts its unit when its paths say so, as systemd's
 * do: while one is there (PathExists, PathExistsGlob, DirectoryNotEmpty: again once its unit has ended, if it still is,
 * at most every 5 seconds) or when one changes (PathChanged, PathModified: a file's time or size, or what a folder
 * holds). A path unit of the last build runs as a loop of its own ([LOOP]), started by the build and at boot, which
 * looks every 2 seconds (the phone has no inotify for this) and has the engine start its unit ([Units.firePath]).
 * MakeDirectory makes the folders it watches. Its paths' specifiers (%h, %t, %S, ...) are filled in as the unit it
 * starts has them (a user's own path unit's are its user's). Its folder ($DATA/paths/<name>) keeps its spec, the paths
 * as they are outside the view (checks), when it last started its unit (last) and what each start said (log).
 */
class Paths(
    private val data: File,
    /** A path with its specifiers filled in for the unit that its path unit starts (Units.pathOf), or null. */
    private val filled: (unit: String, path: String) -> String? = { _, path -> path },
) {
    private val dir = File(data, "paths")

    private var wanted: Map<String, JSONObject> = emptyMap()
    private var toStop: List<String> = emptyList()
    private var toStart: List<String> = emptyList()
    private var leaving: List<String> = emptyList()
    private var toWrite: List<String> = emptyList()
    private var had: Map<String, JSONObject> = emptyMap()
    private var active: Set<String> = emptySet()

    /** Whether the plan changes anything. */
    val changes get() = toStop.isNotEmpty() || toStart.isNotEmpty() || leaving.isNotEmpty() || toWrite.isNotEmpty()

    /** The units the planned path units start, each with their names. */
    fun unitsStarted(): Map<String, List<String>> = started(wanted)

    /** The units the last build's path units start, each with their names. */
    fun unitsStartedNow(): Map<String, List<String>> = started(applied())

    private fun started(paths: Map<String, JSONObject>) =
        paths.entries.filter { it.value.optBoolean("wanted") }.groupBy({ it.value.optString("unit") }, { it.key })

    /** Plans the path units of a profile ([paths]: its android.paths), for the [units] it has. */
    fun plan(paths: JSONObject?, units: Set<String>, @Suppress("UNUSED_PARAMETER") problems: MutableList<String>) {
        wanted = paths?.let { json -> json.keys().asSequence().associateWith { json.getJSONObject(it) } } ?: emptyMap()
        had = applied()
        leaving = had.keys.filter { it !in wanted }
        active = wanted.filter { (_, path) -> path.optBoolean("wanted") && path.optString("unit") in units }.keys
        val changed = wanted.keys.filter { name -> had[name]?.toString()?.let { it != wanted.getValue(name).toString() } == true }
        toStop = had.keys.filter { (it in leaving || it !in active || it in changed) && loopPid(it) != null }
        toStart = active.filter { it in changed || had[it] == null || loopPid(it) == null }
        toWrite = wanted.keys.filter { it !in active && (had[it] == null || it in changed) }
    }

    /** What the plan does with each path unit, after the services' plan. */
    fun report() {
        if (wanted.isEmpty() && had.isEmpty()) return
        Out.line("Path units:")
        for (name in (wanted.keys + had.keys).sorted()) {
            val path = wanted[name] ?: had.getValue(name)
            val what = when {
                name in leaving -> "to remove (it left the config)"
                name !in active -> if (loopPid(name) != null) "to stop (nothing wants it now)" else "not started (nothing wants it)"
                name in toStart -> "to start"
                else -> "watching"
            }
            Out.line("  $name (starts ${path.optString("unit")}): $what")
        }
    }

    /** Carries the plan out: the path units that changed or left stop, the new and changed ones start. */
    fun apply(): Int {
        toStop.forEach { stop(it) }
        for (name in leaving) {
            deleteTree(File(dir, name))
            Out.line("✔ path unit $name: removed (it left the config)")
        }
        for (name in toWrite) File(dir, name).apply { mkdirs() }.resolve("spec.json").writeText(wanted.getValue(name).toString())
        var failed = 0
        for (name in toStart) {
            val path = wanted.getValue(name)
            File(dir, name).apply { mkdirs() }.resolve("spec.json").writeText(path.toString())
            if (launch(name, path)) Out.line("✔ path unit $name: watching (${path.optJSONArray("checks")?.let { checks(it) }?.joinToString(", ") { "${it.first} ${it.second}" }.orEmpty()})")
            else failed++
        }
        return failed
    }

    /** At boot: the last build's path units start again. */
    fun start(units: Set<String>) {
        for ((name, path) in applied()) if (path.optBoolean("wanted") && path.optString("unit") in units) launch(name, path)
    }

    /** The module is being removed: the path units stop, and their folders go. */
    fun removeAll() {
        applied().keys.forEach { stop(it) }
        deleteTree(dir)
    }

    /** The last build's path units, as the app and units.sh list them. */
    fun list(): JSONArray {
        val list = JSONArray()
        for ((name, path) in applied().toSortedMap()) {
            list.put(
                JSONObject().put("name", name).put("unit", path.optString("unit")).put("description", path.optString("description"))
                    .put("wanted", path.optBoolean("wanted")).put("running", loopPid(name) != null)
                    .put("last", runCatching { File(dir, "$name/last").readText().trim().toLong() * 1000 }.getOrDefault(0L))
                    .put("paths", JSONArray(path.optJSONArray("checks")?.let { checks(it) }.orEmpty().map { "${it.first} ${it.second}" })),
            )
        }
        return list
    }

    /** The last build's path unit [name] (its spec), if there is one. */
    fun spec(name: String): JSONObject? = applied()[name]

    private fun checks(array: JSONArray) = (0 until array.length()).map { array.getJSONObject(it) }.map { it.getString("kind") to it.getString("path") }

    /** Starts the loop of [name] (see [LOOP]), below the apps on the screen; whether it runs. */
    private fun launch(name: String, path: JSONObject): Boolean {
        val module = Notify.module ?: run {
            Out.line("✘ path unit $name: the module's folder is not known (the engine runs from it), so it cannot start")
            return false
        }
        stop(name)
        val folder = File(dir, name).apply { mkdirs() }
        val checks = path.optJSONArray("checks")?.let { checks(it) }.orEmpty().map { (kind, value) ->
            kind to (filled(path.optString("unit"), value) ?: run {
                Out.line("✘ path unit $name: $value: its specifiers cannot be filled in (its unit's user is not known)")
                return false
            })
        }
        // The paths as the loop sees them, outside the view; MakeDirectory's folders made first.
        File(folder, "checks").writeText(checks.joinToString("") { (kind, value) -> "$kind\t${Etc.outside(data, value).path}\n" })
        if (path.optBoolean("makeDirectory")) {
            val mode = path.string("directoryMode")?.toIntOrNull(8) ?: "755".toInt(8)
            for ((kind, value) in checks) {
                if (kind !in listOf("PathExists", "DirectoryNotEmpty", "PathChanged", "PathModified")) continue
                val folderToMake = Etc.outside(data, if (kind == "DirectoryNotEmpty") value else File(value).parent ?: continue)
                if (!folderToMake.isDirectory && folderToMake.mkdirs()) runCatching { Os.chmod(folderToMake.path, mode) }
            }
        }
        val busybox = Units.busybox()
        val background = File(data, "units/background.sh")
        val unitDir = File(data, "units/${path.optString("unit")}")
        val command = listOf(busybox, "setsid", "sh", "-c", LOOP, MARK + name, File(module, "app.apk").path, folder.path, name, unitDir.path)
        return runCatching {
            ProcessBuilder(if (background.isFile) listOf(busybox, "sh", background.path, busybox, "10") + command else command)
                .directory(folder).redirectInput(File("/dev/null")).redirectOutput(File("/dev/null")).redirectError(File("/dev/null"))
                .apply {
                    listOf("NIXUP_RUN", "NIXUP_KIND", "NIXUP_QUIET").forEach { environment().remove(it) }
                    environment()["NIXUP_BY"] = "path"
                }.start()
            var tries = 0
            while (loopPid(name) == null && tries++ < 25) Thread.sleep(200)
            loopPid(name) != null
        }.getOrElse { false }.also { if (!it) Out.line("✘ path unit $name: its loop did not start") }
    }

    private fun stop(name: String) {
        loopPid(name)?.let { Device.run("kill", "-TERM", "$it") }
        File(dir, "$name/loop.pid").delete()
    }

    /** The last build's path units, by name. */
    private fun applied(): Map<String, JSONObject> = dir.listFiles().orEmpty().filter { it.isDirectory }.mapNotNull { folder ->
        runCatching { folder.name to JSONObject(File(folder, "spec.json").readText()) }.getOrNull()
    }.toMap()

    /** Its loop's process, while it runs. */
    private fun loopPid(name: String): Int? = runCatching { File(dir, "$name/loop.pid").readText().trim().toInt() }.getOrNull()
        ?.takeIf { pid -> runCatching { File("/proc/$pid/cmdline").readText().replace('\u0000', ' ') }.getOrDefault("").contains(MARK + name) }

    companion object {
        private const val MARK = "nixup-path:"

        /**
         * A path unit's loop: every 2 seconds, the state of its changing paths (a file's time, size and inode; what a
         * folder holds, with their times and sizes) against the last; and whether one of its paths that must be there
         * is (while its unit is not running, at most every 5 seconds). Either has the engine start its unit
         * (path-fire). Its log is set aside past 256 KB. Arguments: the module's app.apk, the path unit's folder, its
         * name, its unit's folder.
         */
        private val LOOP = """
            apk=${'$'}1 folder=${'$'}2 name=${'$'}3 unitdir=${'$'}4
            echo ${'$'}${'$'} > "${'$'}folder/loop.pid"
            engine() { CLASSPATH="${'$'}apk" /system/bin/app_process /system/bin dev.nixup.manager.engine.Main "${'$'}@"; }
            state() {
              while IFS='	' read -r kind path; do
                case ${'$'}kind in
                  PathChanged | PathModified)
                    if [ -d "${'$'}path" ]; then stat -c '%n %Y %s' "${'$'}path" "${'$'}path"/* "${'$'}path"/.[!.]* 2> /dev/null
                    else stat -c '%n %Y %s %i' "${'$'}path" 2> /dev/null || echo "${'$'}path none"; fi ;;
                esac
              done < "${'$'}folder/checks"
            }
            there() {
              while IFS='	' read -r kind path; do
                case ${'$'}kind in
                  PathExists) [ -e "${'$'}path" ] && return 0 ;;
                  PathExistsGlob) for f in ${'$'}path; do [ -e "${'$'}f" ] && return 0; done ;;
                  DirectoryNotEmpty) [ -n "${'$'}(ls -A "${'$'}path" 2> /dev/null)" ] && return 0 ;;
                esac
              done < "${'$'}folder/checks"
              return 1
            }
            running() { p=${'$'}(cat "${'$'}unitdir/main.pid" 2> /dev/null) && [ -n "${'$'}p" ] && kill -0 "${'$'}p" 2> /dev/null; }
            before=${'$'}(state) last=0
            while :; do
              [ -f "${'$'}folder/log" ] && [ "${'$'}(stat -c %s "${'$'}folder/log")" -gt 262144 ] && mv -f "${'$'}folder/log" "${'$'}folder/log.old"
              now=${'$'}(date +%s) why=
              after=${'$'}(state)
              [ "${'$'}after" != "${'$'}before" ] && why="a path changed"
              before=${'$'}after
              if [ -z "${'$'}why" ] && [ ${'$'}((now - last)) -ge 5 ] && there && ! running; then why="a path is there"; fi
              if [ -n "${'$'}why" ]; then
                last=${'$'}now
                echo "${'$'}now" > "${'$'}folder/last"
                echo "${'$'}(date '+%F %T') ${'$'}name: ${'$'}why" >> "${'$'}folder/log"
                engine path-fire "${'$'}name" >> "${'$'}folder/log" 2>&1 < /dev/null
              fi
              sleep 2 & wait ${'$'}!
            done
        """.trimIndent()
    }
}
