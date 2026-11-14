package dev.nixup.manager.engine

import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.time.Instant
import java.time.ZoneId
import kotlin.system.exitProcess

/**
 * The module's engine, run as root by its scripts through app_process, from the app's APK:
 *   CLASSPATH=app.apk app_process /system/bin dev.nixup.manager.engine.Main <command>
 * Commands:
 *   index [--force] <file>        brings the indexes of the repositories in <file> (android.repos) up to date
 *                                 (once a day, or now with --force), and when it is a profile, the releases of its
 *                                 apps from releases (android.packages.obtainium) and what Google Play has of its
 *                                 apps from there (android.packages.playstore) too
 *   apply <profile> [--dry-run] [--force] [--secrets=<folder>]
 *                                 makes the apps, their settings, the debloat, the system settings, the secrets and the
 *                                 services match the profile (with --dry-run: only plans and checks; with --force, the
 *                                 releases are asked for again even when new enough; --secrets: the folder of the
 *                                 secrets a PC sent with it, nixup-rebuild's)
 *   paths <profile> [--derivations | --kept]
 *                                 prints the store paths the profile needs (its android.storePaths), one a line, for
 *                                 the module to fetch from cache.nixos.org before applying it; with --derivations, how
 *                                 each comes (android.derivations: a derivation's outputs, which the small files the
 *                                 config makes are built from on the phone, or a path the config added); with --kept,
 *                                 the paths the phone keeps but fetches from nowhere (android.nix.kept: the flakes of
 *                                 Nix's registry, the config's nixpkgs among them, which a build brings)
 *   builders <profile>            prints the folder of the ssh Nix reaches the profile's build machines with, then its
 *                                 machines file (nix.buildMachines, with nix.distributedBuilds); nothing without them
 *   secrets <file> <folder> [--store=<root>]
 *                                 puts the secrets a build on the phone gives (<file>: each one's file or text) in
 *                                 <folder>, readable by root alone, as a PC sends them (--store: Nixup's store, for
 *                                 the files of a flake's copy)
 *   dns                           prints the DNS servers of the network in use, as resolv.conf lines
 *   dns-update                    writes them to Nixup's /etc/resolv.conf when they changed (the services' network
 *                                 watch runs it when the phone's network changes; see Units)
 *   etc                           writes Nixup's /etc for the Linux programs it runs (Nix, tailscaled): DNS servers,
 *                                 trusted certificates, a root user (see Etc); and the services' view of the phone
 *   services                      at boot: sets adb's port and trusted computers again (android.adb), links Nix's
 *                                 commands to the module where it is now (see Etc), and starts the services the last
 *                                 build left (its systemd.services units)
 *   support                       says which settings that need something of the phone (a feature, a newer Android,
 *                                 LineageOS) this phone can have
 *   restore                       brings back the apps it removed, gives back every app and system setting the module
 *                                 changed (adb's too), stops the services, and removes the apps it installed
 *   updates                       lists the updates of the config's apps (and writes updates.json for the app)
 *   update [--auto] [apps...]     installs them, all or the ones named (with --auto: only for the repositories whose
 *                                 android.settings.repo.<repo>.autoupdatePackages is true)
 *   self-update [--install]       looks for a newer Nixup (the module's update.json), or installs it once it is
 *                                 checked (Nixup's module, newer, its app signed with the installed app's key)
 *   playstore-login               signs the phone in to Google Play with an account: reads its email address and the
 *                                 oauth_token of Google's sign-in page, one a line (the app's Google Play account)
 *   playstore-logout              signs it out again: Google Play's apps then come with no account
 *   playstore <apps...> [--files] what Google Play has of the apps for this phone (version, size, libraries) and what a
 *                                 build would do with each; with --files, where their files are now
 *   backup [--auto] [--parts=<parts>] [apps or folders...]
 *                                 backs up the last build's apps and folders (android.backup), all or the ones named,
 *                                 which may be any installed app or folder (with --auto: the ones that are due, as the
 *                                 app's job asks every 15 minutes; --parts: what to take of each app, of apk, data,
 *                                 deviceData, storage, media, obb, accounts, instead of its settings)
 *   backups [app or folder] [--json]
 *                                 lists the backups
 *   backup-status [--json]        each app and folder backed up: when last, and whether (and why) it is due
 *   backup-restore <app> [snapshot] [--parts=<parts>]
 *                                 brings an app's data back from its newest backup (or the one named), over what it
 *                                 has (--parts: the parts named, apk among them); one not installed gets its APK
 *                                 first, from that backup or another of its backups (none has it: app not found)
 *   backup-restore <folder> [snapshot] [--to=<folder>]
 *                                 brings a folder's files back, into it or into a new folder
 *   backup-parts <app> <snapshot> what one backup of the app has (--json)
 *   accounts-add <app>            adds the accounts it reads (JSON) through Android's account service, with their sync
 *                                 settings: what a restore runs as the app itself, which alone may add its accounts
 *   accounts-sync <app>           prints the sync settings of the accounts it reads (JSON): what a backup runs as the
 *                                 app, which Android shows them to (see Accounts)
 *   restic <arguments...>         restic itself, with the backups' repository and password
 *   units [--json]                each service of the last build (its systemd.services units), and how it is, and its
 *                                 timers (systemd.timers) with their next times
 *   unit <start|stop|restart> <name>
 *                                 starts, stops or restarts one, until the next build or restart
 *   timer-next <timer>            prints the next time a timer of the last build starts its unit (epoch seconds;
 *                                 nothing when it has none left until the next boot), for its loop (see Timers)
 *   timer-fire <timer>            starts a timer's unit, as its loop does at its time (or runs its job: build.sh's
 *                                 upgrade, ..., when the phone's conditions for it hold)
 *   path-fire <path unit>         starts a path unit's unit, as its loop does when its paths say so (see Paths)
 *   calendar <expression> [--count=<n>] [--after=<epoch seconds>]
 *                                 the next times (5) of a calendar event (OnCalendar, startAt) after now (or the
 *                                 time given), as systemd-analyze calendar shows them, or what is wrong with it
 *   audio-out [--format=<rate:bits:channels>] [--latency-file=<file>]
 *                                 plays raw sound from its input with Android's audio, as music (services.snapclient's
 *                                 snapclient writes it), and writes the time Android's audio takes (ms) to the file
 *   caldav-accounts               adds, sets or removes the calendar and contacts accounts it reads (JSON) through
 *                                 Android's account service: what a build runs as Nixup's app, which alone may
 *                                 (services.caldav; see Caldav)
 * Exit status: 0 done, 2 stopped before changing anything, 1 stopped partway.
 */
object Main {
    private val data = File("/data/adb/nixup")

    @JvmStatic
    fun main(args: Array<String>) {
        // What the engine and the units it starts make is root's (or the unit's user's) to write, as with systemd's
        // default UMask, 0022: Magisk starts the module's scripts with umask 0, which they would keep.
        android.system.Os.umask("022".toInt(8))
        val status = try {
            moveOldFiles()
            val files = args.drop(1).filter { !it.startsWith("--") }
            when (args.firstOrNull()) {
                "index" -> index(File(files.firstOrNull() ?: usage()), force = "--force" in args)
                "apply" -> {
                    val profile = File(files.firstOrNull() ?: usage())
                    val incoming = args.firstOrNull { it.startsWith("--secrets=") }?.let { File(it.removePrefix("--secrets=")) }
                    Apply(FDroid.of(JSONObject(profile.readText()), data), data, incoming)
                        .run(profile, dryRun = "--dry-run" in args, force = "--force" in args)
                }
                "restore" -> Apply(emptyMap(), data).restore()
                "support" -> SystemSettings(data).report().let { 0 }
                "dns-update" -> Etc.writeDns(data).let { changed -> if (changed) Out.line("✔ DNS servers: ${File(data, "etc/resolv.conf").readText().lines().filter { it.isNotBlank() }.joinToString(", ") { it.removePrefix("nameserver ") }}"); 0 }
                "dns" -> Device.dnsServers().ifEmpty { throw Stop("no network with DNS servers: is the phone online?") }
                    .forEach { println("nameserver $it") }.let { 0 }
                "etc" -> Etc.write(data).let { etc ->
                    // And the view, for what runs a Nix program without a build (git.sh's git).
                    runCatching { Units(data).writeView() }
                    if (!File(etc, "resolv.conf").isFile) throw Stop("no network with DNS servers: is the phone online?")
                    0
                }
                "audio-out" -> AudioOut.play(option(args, "format") ?: "48000:16:2", option(args, "latency-file")?.let { File(it) }, System.`in`)
                // (Run by a build, as Nixup's app: see Caldav.)
                "caldav-accounts" -> Caldav.asApp()
                "services" -> {
                    // adb listens on no port after a restart: the profile's again, before the units start.
                    val android = runCatching { JSONObject(File(data, "profile.json").readText()).optJSONObject("android") }.getOrNull()
                    runCatching { Adb(data).boot(android?.optJSONObject("adb")) }.onFailure { Out.line("✘ adb: ${it.message}") }
                    // The system settings a ROM put back at its start (the profile's again), before the units start.
                    runCatching { SystemSettings(data).boot(android?.optJSONObject("system")) }.onFailure { Out.line("✘ System settings: ${it.message}") }
                    // A module installed since the last start is where Magisk moved it now.
                    runCatching { Etc.writeNixCommands(File(data, "etc"), android?.optJSONObject("nix")) }
                    Units(data).start()
                }
                "secrets" -> Secrets.stage(
                    File(files.getOrNull(0) ?: usage()), File(files.getOrNull(1) ?: usage()),
                    args.firstOrNull { it.startsWith("--store=") }?.let { File(it.removePrefix("--store=")) },
                )
                "paths" -> JSONObject(File(files.firstOrNull() ?: usage()).readText()).getJSONObject("android")
                    .let { android ->
                        when {
                            "--kept" in args -> android.optJSONObject("nix")?.optJSONArray("kept")
                            "--derivations" in args -> android.optJSONArray("derivations")
                            else -> android.optJSONArray("storePaths")
                        }
                    }
                    ?.let { paths -> (0 until paths.length()).forEach { println(paths.getString(it)) } }.let { 0 }
                // nix.buildMachines (with nix.distributedBuilds): the folder of the ssh Nix reaches them with, then Nix's
                // machines file; nothing when the profile has none.
                "builders" -> JSONObject(File(files.firstOrNull() ?: usage()).readText()).getJSONObject("android")
                    .optJSONObject("builders")?.let { builders ->
                        println(builders.getString("ssh"))
                        print(builders.getString("machines"))
                    }.let { 0 }
                "updates" -> Updates(data).check()
                "update" -> Updates(data).update(files, auto = "--auto" in args)
                "self-update" -> SelfUpdate(data).let { if ("--install" in args) it.install() else it.check() }
                "playstore-login" -> PlayStore.login(data, System.`in`)
                "playstore-logout" -> PlayStore.logout(data)
                "playstore" -> PlayStore.show(data, files, files = "--files" in args)
                "backup" -> backups().run(files, auto = "--auto" in args, parts = option(args, "parts")?.split(',')?.filter { it.isNotBlank() })
                "backups" -> if ("--json" in args) json { Backup.ofLastBuild(data)?.listJson(files.firstOrNull()) } else backups().list(files.firstOrNull())
                "backup-status" -> if ("--json" in args) json { Backup.ofLastBuild(data)?.statusJson() } else backups().status()
                "backup-restore" -> {
                    val what = files.firstOrNull() ?: usage()
                    if (what.startsWith("/")) backups().restoreFolder(what, files.getOrNull(1), option(args, "to"))
                    else backups().restoreNow(what, files.getOrNull(1), option(args, "parts")?.split(',')?.filter { it.isNotBlank() })
                }
                "backup-parts" -> json { backups().partsJson(files.getOrNull(0) ?: usage(), files.getOrNull(1) ?: usage()) }
                // (Run by a restore, as the app itself: see Accounts.)
                "accounts-add" -> Accounts.addAsApp(files.firstOrNull() ?: usage())
                "accounts-sync" -> Accounts.syncAsApp()
                "restic" -> backups().passThrough(args.drop(1))
                "units" -> Units(data).list().let { list ->
                    if ("--json" in args) json { list }
                    else {
                        val units = list.getJSONArray("units")
                        if (units.length() == 0) Out.line("No services: the last build has none (services.*, systemd.services)")
                        for (i in 0 until units.length()) units.getJSONObject(i).let { Out.line("${it.getString("state").padEnd(10)} ${it.getString("name")}  ${it.optString("description")}") }
                        val timers = list.getJSONArray("timers")
                        for (i in 0 until timers.length()) timers.getJSONObject(i).let { timer ->
                            val next = timer.optLong("next").takeIf { it > 0 }?.let { "next ${Out.date(it)}" }
                                ?: if (timer.optBoolean("done")) "no time left until the next boot" else if (timer.optBoolean("running")) "waiting" else "not running"
                            val last = timer.optLong("last").takeIf { it > 0 }?.let { ", last ${Out.date(it)}" }.orEmpty()
                            Out.line("${"timer".padEnd(10)} ${timer.getString("name")}  starts ${timer.getString("unit")}: $next$last")
                        }
                        val paths = list.getJSONArray("paths")
                        for (i in 0 until paths.length()) paths.getJSONObject(i).let { path ->
                            val watched = (0 until path.getJSONArray("paths").length()).joinToString(", ") { path.getJSONArray("paths").getString(it) }
                            val last = path.optLong("last").takeIf { it > 0 }?.let { ", last ${Out.date(it)}" }.orEmpty()
                            Out.line("${"path".padEnd(10)} ${path.getString("name")}  starts ${path.getString("unit")}: ${if (path.optBoolean("running")) "watching" else "not running"} $watched$last")
                        }
                        0
                    }
                }
                "unit" -> Installer.locked(data) { Units(data).control(files.getOrNull(0) ?: usage(), files.getOrNull(1) ?: usage()) }
                "timer-next" -> Timers(data).next(files.firstOrNull() ?: usage())?.let { println(it) }.let { 0 }
                "timer-fire" -> Units(data).fire(files.firstOrNull() ?: usage())
                "path-fire" -> Units(data).firePath(files.firstOrNull() ?: usage())
                "calendar" -> calendar(
                    args.drop(1).filter { !it.startsWith("--") }.joinToString(" ").ifBlank { usage() },
                    option(args, "count")?.toIntOrNull() ?: 5, option(args, "after")?.toLongOrNull() ?: (System.currentTimeMillis() / 1000),
                )
                else -> usage()
            }
        } catch (e: Stop) {
            Out.line("✘ ${e.message}")
            e.status
        } catch (e: SecurityException) {
            Out.line("✘ ${e.message}")
            2
        } catch (e: Exception) {
            Out.line("✘ ${e.javaClass.simpleName}: ${e.message}")
            1
        }
        Notify.flush()
        System.out.flush()
        // That it ran at all, for the script that started it (common.sh's engine): app_process exits 0 also when
        // Android's runtime could not start, having run nothing.
        System.getenv("NIXUP_ENGINE_STATUS")?.let { file -> runCatching { File(file).writeText("$status\n") } }
        // The package manager's system context leaves threads behind that would keep the process alive.
        exitProcess(status)
    }

    /**
     * Each repository's index, one after another, then the releases of the apps from releases and what Google Play has
     * of its apps (a profile has them; the repositories a build evaluates first do not); one that cannot be had does not
     * keep the others from coming.
     */
    private fun index(file: File, force: Boolean): Int {
        val json = JSONObject(file.readText())
        val android = json.optJSONObject("android")
        var failed = 0
        for (repo in FDroid.of(json, data).values) {
            try {
                repo.refresh(force)
            } catch (e: Exception) {
                Out.line("✘ ${repo.name}: ${e.message}")
                failed++
            }
        }
        val releases = Releases(data)
        for (app in android?.let { Releases.apps(it) } ?: emptyList()) {
            try {
                releases.refresh(app, force)
            } catch (e: Exception) {
                Out.line("✘ ${Releases.NAME}: ${app.id}: ${e.message}")
                failed++
            }
        }
        try {
            android?.let { PlayStore(data, it).refresh(PlayStore.apps(it), force) }
        } catch (e: Exception) {
            Out.line("✘ ${PlayStore.NAME}: ${e.message}")
            failed++
        }
        return if (failed == 0) 0 else 1
    }

    /**
     * The files of a module from before custom repositories: f-droid.org's index in fdroid/ goes to repos/fdroid/
     * (marked as fetched from f-droid.org with its key), and the list of apps the module installed and their
     * versions are kept for all repositories, next to it.
     */
    private fun moveOldFiles() {
        // Nixup was called Seraph, and Andronix before that; its module's install takes their data over, and so does
        // this, should that not have run. Seraph's folder stays as a link, as the install leaves it: configs name files in it.
        val seraph = File("/data/adb/seraph")
        if (!data.exists() && seraph.isDirectory && !Files.isSymbolicLink(seraph.toPath()) && seraph.renameTo(data)) {
            runCatching { Files.createSymbolicLink(seraph.toPath(), Paths.get(data.name)) }
        }
        val andronix = File("/data/adb/andronix")
        if (!data.exists() && andronix.isDirectory) andronix.renameTo(data)
        val old = File(data, "fdroid")
        if (!old.isDirectory) return
        File(old, "owned").takeIf { it.isFile }?.renameTo(File(data, "owned"))
        File(old, "resolved.json").takeIf { it.isFile }?.renameTo(File(data, "resolved.json"))
        File(old, "state.json").takeIf { it.isFile }?.let { state ->
            state.writeText(JSONObject(state.readText()).put("url", FDroid.OFFICIAL_URL).put("fingerprint", FDroid.OFFICIAL_FINGERPRINT).toString())
        }
        val repos = File(data, "repos").apply { mkdirs() }
        if (!old.renameTo(File(repos, "fdroid"))) old.deleteRecursively()
    }

    private fun backups(): Backup = Backup.ofLastBuild(data)
        ?: throw Stop("the last build has no backups: set android.backup.repository (and the apps, folders) in the config, then build", 2)

    /** What the app reads: one line of JSON ({"configured":false} when the last build has no backups). */
    private fun json(make: () -> JSONObject?): Int {
        println((make() ?: JSONObject().put("configured", false)).toString())
        return 0
    }

    /** The next [count] times of calendar event [expression] after [start], in the phone's time zone and in UTC, as systemd-analyze shows them. */
    private fun calendar(expression: String, count: Int, start: Long): Int {
        val calendar = try {
            Timers.Calendar.parse(expression)
        } catch (e: IllegalArgumentException) {
            throw Stop("\"$expression\": ${e.message}", 2)
        }
        val zone = ZoneId.systemDefault()
        var after = start
        Out.line("  Original form: $expression")
        repeat(count.coerceIn(1, 1000)) { i ->
            val next = calendar.next(after, zone) ?: return 0.also { if (i == 0) Out.line("    Next elapse: never") }
            val at = Instant.ofEpochSecond(next)
            Out.line("${if (i == 0) "    Next elapse" else "           Iter. #${i + 1}"}: ${at.atZone(zone).toLocalDateTime()} ($zone)")
            Out.line("       (in UTC): ${at.atZone(ZoneId.of("UTC")).toLocalDateTime()} UTC  $next")
            after = next
        }
        return 0
    }

    /** The value of --<name>=<value>, when it is given. */
    private fun option(args: Array<String>, name: String): String? = args.firstOrNull { it.startsWith("--$name=") }?.removePrefix("--$name=")

    private fun usage(): Nothing =
        throw Stop("usage: index [--force] <file> | apply <profile.json> [--dry-run] [--force] [--secrets=<folder>] | paths <profile.json> | builders <profile.json> | secrets <file> <folder> [--store=<root>] | restore | support | dns | etc | services | updates | update [--auto] [apps...] | self-update [--install] | playstore-login | playstore-logout | playstore <apps...> [--files] | backup [--auto] [--parts=<parts>] [apps or folders...] | backups [app or folder] [--json] | backup-status [--json] | backup-restore <app or folder> [snapshot] [--parts=<parts>] [--to=<folder>] | backup-parts <app> <snapshot> | restic <arguments...> | units [--json] | dns-update | unit <start|stop|restart> <name> | timer-next <timer> | timer-fire <timer> | path-fire <path unit> | calendar <expression> [--count=<n>] [--after=<epoch seconds>] | audio-out [--format=<rate:bits:channels>] [--latency-file=<file>]")
}
