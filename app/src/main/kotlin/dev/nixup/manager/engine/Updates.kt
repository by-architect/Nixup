package dev.nixup.manager.engine

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Updates of the config's apps (android.packages of each repository, the apps from releases and those from Google
 * Play), outside of a build. list finds the ones with a newer version (the one a build would install) and writes them
 * to updates.json for the app's Updates tab; update installs them, all or the ones named, with notifications; update
 * --auto is the phone's own check: it installs those of the repositories (and obtainium, playstore) whose
 * android.settings.repo.<repo>.autoupdatePackages is true, and only tells about the others in a notification. The config
 * is the last build's profile; the indexes, releases and what Google Play said are those here (update.sh brings them
 * up to date first).
 *
 * The phone's own check (update.sh auto) comes every 6 hours (the app's job) and as soon as the phone joins a network it
 * may use (the network watch: [watch], [joined]), and runs when [due] says so: on such a network (Wi-Fi only, unless
 * android.updates.onlyOnUnmetered is false), with the battery not low, and not within an hour of the last one. So a
 * check that came while the phone was offline, or on mobile data, runs once it is back, not at the next 6 hours.
 */
class Updates(private val data: File, private val profile: File = File(data, "profile.json")) {
    private val installer = Installer(data)
    private val releases = Releases(data)
    private val file = File(data, "updates.json")

    /** When the phone's own check last ran (epoch seconds), which [due] keeps. */
    private val autoFile = File(data, "update-auto")

    /** Whether the phone was on a network the check may use at the network watch's last look, with the boot it was in. */
    private val networkFile = File(data, "update-network")

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

    /** Whether the config keeps the phone's own check to unmetered networks (Wi-Fi): android.updates.onlyOnUnmetered. */
    private val onlyOnUnmetered: Boolean get() = android?.optJSONObject("updates")?.optBoolean("onlyOnUnmetered", true) ?: true

    /** Whether the phone's own check may use the networks the phone is [online] by. */
    private fun usable(online: Device.Online?) = online == Device.Online.UNMETERED || (online == Device.Online.METERED && !onlyOnUnmetered)

    /**
     * Whether the phone's own check runs now, which update.sh asks first: once the phone was unlocked since it started
     * (the apps' data is out of reach until then, as resume.sh waits for it), on a network it may use, with the battery
     * not low, and not within an hour of the last one (from now on, this one). 0 when it runs, else [WAITS], having said
     * why. Where the network cannot be told here, it runs on any, as it did before it looked.
     */
    fun due(): Int {
        val online = Device.online()
        val now = System.currentTimeMillis() / 1000
        val last = runCatching { autoFile.readText().trim().toLong() }.getOrNull()
        val why = when {
            !Device.unlocked() -> "the phone was not unlocked since it started; it checks once it is"
            online == Device.Online.OFFLINE -> "the phone is offline; it checks once the phone is online"
            online != null && !usable(online) ->
                "the phone is on metered networks only (mobile data), and android.updates.onlyOnUnmetered keeps it to Wi-Fi; it checks once the phone joins one"
            !Device.charging() && (Device.batteryLevel() ?: 100) < LOW_BATTERY -> "the battery is low"
            last != null && now - last in 0 until GAP -> "the last one was ${(now - last) / 60} minutes ago (one an hour at most)"
            else -> null
        }
        if (why != null) {
            Out.line("The automatic update check waits: $why")
            return WAITS
        }
        if (online == null) Out.line("(The phone's network cannot be told here: the automatic update check runs on any)")
        autoFile.writeText("$now\n")
        return 0
    }

    /**
     * The network watch's look (see [watch]), after the phone's networks changed: when the phone has just joined a
     * network the check may use, the phone's own check starts at once (update.sh network auto), which [due] lets run
     * unless one ran in the last hour.
     */
    fun joined(): Int {
        if (!profile.isFile || !justJoined()) return 0
        val module = Notify.module ?: throw Stop("the module's folder is not known (the engine runs from it), so the update check cannot start", 1)
        val launcher = runCatching { listOf(Units.busybox(), "setsid") }.getOrDefault(listOf("/system/bin/setsid"))
        return runCatching {
            ProcessBuilder(launcher + listOf("sh", File(module, "update.sh").path, "network", "auto"))
                .directory(module).redirectInput(File("/dev/null")).redirectOutput(File("/dev/null")).redirectError(File("/dev/null"))
                .apply { listOf("NIXUP_RUN", "NIXUP_KIND", "NIXUP_QUIET", "NIXUP_SOURCE", "NIXUP_PROFILE").forEach { environment().remove(it) } }
                .start()
            Out.line("The phone joined a network the update check may use: it checks now")
            0
        }.getOrElse { throw Stop("the update check could not start: ${it.message}", 1) }
    }

    /**
     * Whether the phone has just joined a network the check may use: it is on one now, and was on none at the last look
     * of this boot (a boot starts on none, and until the phone's first unlock it counts as on none: see [due]). What it
     * is on now is kept for the next look.
     */
    private fun justJoined(): Boolean {
        val boot = runCatching { File("/proc/sys/kernel/random/boot_id").readText().trim() }.getOrDefault("")
        val now = Device.unlocked() && usable(Device.online())
        val before = runCatching { networkFile.readText().trim().split(' ') }.getOrNull()
            ?.takeIf { it.size == 2 && it[0] == boot }?.get(1) == "1"
        networkFile.writeText("$boot ${if (now) 1 else 0}\n")
        return now && !before
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

    companion object {
        /** [due]'s status when the check does not run now: as the engine's "stopped before changing anything". */
        const val WAITS = 2

        /** The phone's own check runs once an hour at most (seconds): a network that comes and goes does not check each time. */
        private const val GAP = 3600L

        /** As Android's jobs (and system.autoUpgrade's timer) take a low battery, in percent. */
        private const val LOW_BATTERY = 15

        private const val WATCH_MARK = "nixup-update-watch"

        /**
         * Starts the update check's network watch, unless it runs, once there is a build ([profile]; before, there is
         * nothing to update): a loop of its own ([WATCH]) that looks at the phone's networks every 20 seconds, and when
         * they change has the engine see whether the phone has just joined a network the check may use ([joined]), which
         * then checks at once. Started by each build and at [boot]: one started by a build counts the network the phone
         * is on now as one it was on already, and one at boot does not (the phone just started, on none).
         */
        fun watch(data: File, profile: File = File(data, "profile.json"), boot: Boolean = false) {
            val module = Notify.module ?: return
            val pidFile = File(data, "update-watch.pid")
            if (!profile.isFile || watching(pidFile) != null) return
            if (!boot) runCatching { Updates(data, profile).justJoined() }
            runCatching {
                val busybox = Units.busybox()
                val background = File(data, "units/background.sh")
                val command = listOf(busybox, "setsid", "sh", "-c", WATCH, WATCH_MARK, File(module, "app.apk").path, pidFile.path)
                // Below the apps on the screen, as the timers' loops are (see Units' BACKGROUND), with what it starts.
                ProcessBuilder(if (background.isFile) listOf(busybox, "sh", background.path, busybox, "19") + command else command)
                    .redirectInput(File("/dev/null")).redirectOutput(File("/dev/null")).redirectError(File("/dev/null"))
                    .apply { listOf("NIXUP_RUN", "NIXUP_KIND", "NIXUP_QUIET").forEach { environment().remove(it) } }.start()
            }
        }

        /** Stops the network watch: the module is being removed. */
        fun unwatch(data: File) {
            val pidFile = File(data, "update-watch.pid")
            watching(pidFile)?.let { Device.run("kill", "-TERM", "$it") }
            pidFile.delete()
        }

        /** The network watch's loop, while it runs. */
        private fun watching(pidFile: File): Int? = runCatching { pidFile.readText().trim().toInt() }.getOrNull()
            ?.takeIf { pid -> runCatching { File("/proc/$pid/cmdline").readText() }.getOrDefault("").contains(WATCH_MARK) }

        /**
         * The network watch (see [watch]): every 20 seconds, whether the phone was unlocked since it started, and its
         * networks, as the interfaces it reaches the internet by (the one it goes out by, then each with a way out of its
         * own: Wi-Fi's, mobile data's, with a VPN on top or not); when they change, the engine's update-network then and
         * three times more, as Android takes a network it joined as online (and goes out by it) only a little after it is
         * up. Arguments: the module's app.apk, its pid file.
         */
        private val WATCH = """
            apk=${'$'}1 pidfile=${'$'}2
            echo ${'$'}${'$'} > "${'$'}pidfile"
            engine() { CLASSPATH="${'$'}apk" /system/bin/app_process /system/bin dev.nixup.manager.engine.Main "${'$'}@" > /dev/null 2>&1 < /dev/null; }
            state() {
              /system/bin/getprop sys.user.0.ce_available
              /system/bin/ip route get 8.8.8.8 2> /dev/null | sed -n 's/.* dev \([^ ]*\).*/\1/p'
              { /system/bin/ip route show table all; /system/bin/ip -6 route show table all; } 2> /dev/null | sed -n 's/^default .* dev \([^ ]*\).*/\1/p' | sort -u
            }
            last= again=0
            while :; do
              now=${'$'}(state)
              if [ "${'$'}now" != "${'$'}last" ]; then last=${'$'}now again=4; fi
              if [ ${'$'}again -gt 0 ]; then again=${'$'}((again - 1)); engine update-network; fi
              sleep 20 & wait ${'$'}!
            done
        """.trimIndent()
    }
}
