package dev.seraph.manager.engine

import org.json.JSONObject
import java.io.File
import kotlin.system.exitProcess

/**
 * The module's engine, run as root by its scripts through app_process, from the app's APK:
 *   CLASSPATH=app.apk app_process /system/bin dev.seraph.manager.engine.Main <command>
 * Commands:
 *   index [--force] <file>        brings the indexes of the repositories in <file> (android.repos) up to date
 *                                 (once a day, or now with --force), and when it is a profile, the releases of its
 *                                 apps from releases (android.packages.obtainium) and what Google Play has of its
 *                                 apps from there (android.packages.playstore) too
 *   apply <profile> [--dry-run] [--force] [--secrets=<folder>]
 *                                 makes the apps, their settings, the debloat, the system settings, the secrets and the
 *                                 services match the profile (with --dry-run: only plans and checks; with --force, the
 *                                 releases are asked for again even when new enough; --secrets: the folder of the
 *                                 secrets a PC sent with it, seraph-rebuild's)
 *   paths <profile>               prints the store paths the profile needs (its android.storePaths), one a line, for
 *                                 the module to fetch from cache.nixos.org before applying it
 *   secrets <file> <folder> [--store=<root>]
 *                                 puts the secrets a build on the phone gives (<file>: each one's file or text) in
 *                                 <folder>, readable by root alone, as a PC sends them (--store: Seraph's store, for
 *                                 the files of a flake's copy)
 *   dns                           prints the DNS servers of the network in use, as resolv.conf lines
 *   etc                           writes Seraph's /etc for the Linux programs it runs (Nix, tailscaled): DNS servers,
 *                                 trusted certificates, a root user (see Etc); and the services' view of the phone
 *   services                      at boot: sets adb's port and trusted computers again (android.adb), and starts the
 *                                 services the last build left (its systemd.services units)
 *   support                       says which settings that need something of the phone (a feature, a newer Android,
 *                                 LineageOS) this phone can have
 *   restore                       brings back the apps it removed, gives back every app and system setting the module
 *                                 changed (adb's too), stops the services, and removes the apps it installed
 *   updates                       lists the updates of the config's apps (and writes updates.json for the app)
 *   update [--auto] [apps...]     installs them, all or the ones named (with --auto: only for the repositories whose
 *                                 android.settings.repo.<repo>.autoupdatePackages is true)
 *   self-update [--install]       looks for a newer Seraph (the module's update.json), or installs it once it is
 *                                 checked (Seraph's module, newer, its app signed with the installed app's key)
 *   playstore-login               signs the phone in to Google Play with an account: reads its email address and the
 *                                 oauth_token of Google's sign-in page, one a line (the app's Google Play account)
 *   playstore-logout              signs it out again: Google Play's apps then come with no account
 *   playstore <apps...> [--files] what Google Play has of the apps for this phone (version, size, libraries) and what a
 *                                 build would do with each; with --files, where their files are now
 *   backup [--auto] [--parts=<parts>] [apps or folders...]
 *                                 backs up the last build's apps and folders (android.backup), all or the ones named,
 *                                 which may be any installed app or folder (with --auto: the ones that are due, as the
 *                                 app's job asks every 15 minutes; --parts: what to take of each app, of apk, data,
 *                                 deviceData, storage, media, obb, instead of its settings)
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
 *   restic <arguments...>         restic itself, with the backups' repository and password
 *   units [--json]                each service of the last build (its systemd.services units), and how it is
 *   unit <start|stop|restart> <name>
 *                                 starts, stops or restarts one, until the next build or restart
 *   audio-out [--format=<rate:bits:channels>] [--latency-file=<file>]
 *                                 plays raw sound from its input with Android's audio, as music (services.snapclient's
 *                                 snapclient writes it), and writes the time Android's audio takes (ms) to the file
 * Exit status: 0 done, 2 stopped before changing anything, 1 stopped partway.
 */
object Main {
    private val data = File("/data/adb/seraph")

    @JvmStatic
    fun main(args: Array<String>) {
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
                "dns" -> Device.dnsServers().ifEmpty { throw Stop("no network with DNS servers: is the phone online?") }
                    .forEach { println("nameserver $it") }.let { 0 }
                "etc" -> Etc.write(data).let { etc ->
                    // And the view, for what runs a Nix program without a build (git.sh's git).
                    runCatching { Units(data).writeView() }
                    if (!File(etc, "resolv.conf").isFile) throw Stop("no network with DNS servers: is the phone online?")
                    0
                }
                "audio-out" -> AudioOut.play(option(args, "format") ?: "48000:16:2", option(args, "latency-file")?.let { File(it) }, System.`in`)
                "services" -> {
                    // adb listens on no port after a restart: the profile's again, before the units start.
                    val android = runCatching { JSONObject(File(data, "profile.json").readText()).optJSONObject("android") }.getOrNull()
                    runCatching { Adb(data).boot(android?.optJSONObject("adb")) }.onFailure { Out.line("✘ adb: ${it.message}") }
                    Units(data).start()
                }
                "secrets" -> Secrets.stage(
                    File(files.getOrNull(0) ?: usage()), File(files.getOrNull(1) ?: usage()),
                    args.firstOrNull { it.startsWith("--store=") }?.let { File(it.removePrefix("--store=")) },
                )
                "paths" -> JSONObject(File(files.firstOrNull() ?: usage()).readText()).getJSONObject("android")
                    .optJSONArray("storePaths")?.let { paths -> (0 until paths.length()).forEach { println(paths.getString(it)) } }.let { 0 }
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
                "restic" -> backups().passThrough(args.drop(1))
                "units" -> Units(data).list().let { list ->
                    if ("--json" in args) json { list }
                    else {
                        val units = list.getJSONArray("units")
                        if (units.length() == 0) Out.line("No services: the last build has none (services.*, systemd.services)")
                        for (i in 0 until units.length()) units.getJSONObject(i).let { Out.line("${it.getString("state").padEnd(10)} ${it.getString("name")}  ${it.optString("description")}") }
                        0
                    }
                }
                "unit" -> Installer.locked(data) { Units(data).control(files.getOrNull(0) ?: usage(), files.getOrNull(1) ?: usage()) }
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
        // Seraph was called Andronix; its module's install takes its data over, and so does this, should that not have run.
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

    /** The value of --<name>=<value>, when it is given. */
    private fun option(args: Array<String>, name: String): String? = args.firstOrNull { it.startsWith("--$name=") }?.removePrefix("--$name=")

    private fun usage(): Nothing =
        throw Stop("usage: index [--force] <file> | apply <profile.json> [--dry-run] [--force] [--secrets=<folder>] | paths <profile.json> | secrets <file> <folder> [--store=<root>] | restore | support | dns | etc | services | updates | update [--auto] [apps...] | self-update [--install] | playstore-login | playstore-logout | playstore <apps...> [--files] | backup [--auto] [--parts=<parts>] [apps or folders...] | backups [app or folder] [--json] | backup-status [--json] | backup-restore <app or folder> [snapshot] [--parts=<parts>] [--to=<folder>] | backup-parts <app> <snapshot> | restic <arguments...> | units [--json] | unit <start|stop|restart> <name> | audio-out [--format=<rate:bits:channels>] [--latency-file=<file>]")
}
