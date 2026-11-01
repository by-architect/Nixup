package dev.seraph.manager.engine

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.util.regex.PatternSyntaxException

/**
 * Apps from their developers' own releases, the way Obtainium gets them: android.packages.obtainium, each with the
 * address of its repository on GitHub, GitLab or a Forgejo/Gitea server (like Codeberg). The releases are asked of
 * the server's web API and kept in repos/obtainium/<id>.json with their ETag, so asking again when nothing changed
 * costs nothing (GitHub answers 60 requests an hour without an account). A build asks again once they are a day old,
 * an update check always.
 *
 * Nothing signs a list of releases the way an F-Droid repository signs its index. The APK is trusted for its https
 * address, the SHA-256 GitHub lists for it, its package name (it must be the config's) and its signing key: the one
 * pinned with signer, else the installed app's, else the first one seen (Android then holds the app to it).
 *
 * When Obtainium is on the phone, the apps are put in its own list too, so it shows them and can update them
 * (see [tellObtainium]).
 */
class Releases(data: File) {
    private val dir = File(data, "repos/$NAME")

    /** The files the module put in Obtainium's list, one path a line: only these are ever taken out again. */
    private val toldFile = File(dir, "told")

    /** One of the config's apps from releases: an entry of android.packages.obtainium. */
    class App(val id: String, val url: String, val apkFilter: String?, val signer: String?, val prerelease: Boolean)

    class Asset(val name: String, val url: String, val size: Long, val sha256: String?) {
        /** What tells this APK from another: its checksum, else its address and size. */
        val key: String get() = sha256 ?: "$url $size"
    }

    class Release(val tag: String, val prerelease: Boolean, val assets: List<Asset>)

    sealed class Plan {
        class Install(val step: Installer.Step) : Plan()
        class Current(val version: String) : Plan()
        class Problem(val text: String) : Plan()
    }

    private sealed class Choice {
        class Pick(val release: Release, val asset: Asset) : Choice()
        class Problem(val text: String) : Choice()
    }

    /** Where a repository is: its server, and its path there (owner/name; on GitLab, groups may come in between). */
    private class Place(val host: String, val segments: List<String>) {
        val owner: String get() = segments[0]
        val name: String get() = segments[1].removeSuffix(".git")
        val gitlabPath: String get() = segments.joinToString("/").removeSuffix(".git")
    }

    private fun place(url: String): Place {
        val uri = URI(url)
        val host = uri.host?.lowercase()?.removePrefix("www.") ?: throw IOException("$url has no server in it")
        // GitLab's pages of a project are under /-/ (…/project/-/releases).
        val segments = uri.path.orEmpty().split('/').filter { it.isNotEmpty() }.takeWhile { it != "-" }
        if (segments.size < 2) throw IOException("$url is not the address of a repository (like https://github.com/<owner>/<repo>)")
        return Place(host, segments)
    }

    /** The web APIs that may list a repository's releases, with their kind: GitHub's; else Forgejo/Gitea's, then GitLab's. */
    private fun apis(place: Place): List<Pair<String, String>> {
        val github = "https://api.github.com/repos/${place.owner}/${place.name}/releases?per_page=$COUNT"
        val gitea = "https://${place.host}/api/v1/repos/${place.owner}/${place.name}/releases?limit=$COUNT"
        val gitlab = "https://${place.host}/api/v4/projects/${URLEncoder.encode(place.gitlabPath, "UTF-8")}/releases?per_page=$COUNT"
        return when (place.host) {
            "github.com" -> listOf(GITHUB to github)
            "gitlab.com" -> listOf(GITLAB to gitlab)
            else -> listOf(GITEA to gitea, GITLAB to gitlab)
        }
    }

    private fun file(app: App) = File(dir, "${app.id}.json")

    /** What is here of the app's releases, when they are from the address in the config. */
    private fun saved(app: App): JSONObject? = file(app).takeIf { it.isFile }
        ?.let { runCatching { JSONObject(it.readText()) }.getOrNull() }
        ?.takeIf { it.optString("url") == app.url }

    private fun save(app: App, json: JSONObject) {
        dir.mkdirs()
        val fresh = File(dir, "${app.id}.json.new")
        fresh.writeText(json.toString())
        if (!fresh.renameTo(file(app))) throw IOException("cannot write ${file(app).path}")
    }

    /** Brings what is here of [app]'s releases up to date: at most once a day, or now when [force]d. */
    fun refresh(app: App, force: Boolean) {
        val old = saved(app)
        val now = System.currentTimeMillis()
        if (old != null && !force && now - old.getLong("checked") < FDroid.MAX_AGE) return
        val place = place(app.url)
        var why = "no list of releases there"
        for ((kind, api) in apis(place).filter { old == null || it.first == old.getString("kind") }) {
            val answer = try {
                Net.api(api, old?.text("etag"))
            } catch (e: IOException) {
                why = e.message ?: e.toString()
                break
            }
            when (answer.status) {
                304 -> if (old != null) {
                    save(app, old.put("checked", now))
                    Out.line("$NAME: ${app.id}: no new release (newest: ${newest(app, releases(old))})")
                    return
                }
                200 -> {
                    // GitLab links the APKs uploaded to a project by its number, asked for once (when a release has one).
                    var project = old?.optLong("project", 0)?.takeIf { it > 0 }
                    val releases = try {
                        parse(kind, answer.body, place) { project ?: gitlabProject(place).also { project = it } }
                    } catch (e: JSONException) {
                        // A server of another kind, answering with a page of its own.
                        why = "${place.host} did not answer with a list of releases"
                        continue
                    } catch (e: IOException) {
                        why = e.message ?: e.toString()
                        break
                    }
                    // The version codes of APKs seen before, while they are still listed.
                    val keys = releases.flatMap { release -> release.assets.map { it.key } }.toSet()
                    val codes = JSONObject()
                    old?.optJSONObject("codes")?.let { known -> known.keys().forEach { if (it in keys) codes.put(it, known.getLong(it)) } }
                    val json = JSONObject().put("url", app.url).put("kind", kind).put("checked", now)
                        .put("releases", toJson(releases)).put("codes", codes)
                    answer.header("ETag")?.let { json.put("etag", it) }
                    project?.let { json.put("project", it) }
                    save(app, json)
                    Out.line("$NAME: ${app.id}: newest release ${newest(app, releases)} (${releases.size} looked at)")
                    return
                }
                404 -> {
                    why = "${place.host} has no such repository, or no releases (HTTP 404)"
                    continue
                }
                403, 429 -> {
                    val reset = answer.header("X-RateLimit-Reset")?.toLongOrNull()?.let { " until ${Out.date(it * 1000)}" } ?: ""
                    why = if (answer.status == 429 || answer.header("X-RateLimit-Remaining") == "0") {
                        "${place.host} answers only so many requests an hour, and they are used up$reset"
                    } else {
                        "HTTP ${answer.status}"
                    }
                }
                else -> why = "HTTP ${answer.status}"
            }
            break
        }
        if (old != null) {
            Out.line("$NAME: ${app.id}: cannot look for newer releases ($why); using those from ${Out.date(old.getLong("checked"))}")
            return
        }
        throw IOException("cannot get the releases of ${app.url} ($why): it must be a repository on GitHub, GitLab, or a Forgejo/Gitea server like Codeberg")
    }

    private fun newest(app: App, releases: List<Release>) = releases.firstOrNull { app.prerelease || !it.prerelease }?.tag ?: "none"

    /** The number of a GitLab project, which the addresses of the files uploaded to it have. */
    private fun gitlabProject(place: Place): Long {
        val answer = Net.api("https://${place.host}/api/v4/projects/${URLEncoder.encode(place.gitlabPath, "UTF-8")}")
        if (answer.status != 200) throw IOException("cannot get the project ${place.gitlabPath} on ${place.host} (HTTP ${answer.status})")
        return JSONObject(answer.body).getLong("id")
    }

    /** The releases a web API listed, newest first, each with its APKs only. */
    private fun parse(kind: String, body: String, place: Place, gitlabProject: () -> Long): List<Release> {
        val list = JSONArray(body)
        val releases = ArrayList<Release>()
        for (i in 0 until list.length()) {
            val release = list.getJSONObject(i)
            val tag = release.text("tag_name") ?: continue
            val assets = ArrayList<Asset>()
            if (kind == GITLAB) {
                val links = release.optJSONObject("assets")?.optJSONArray("links") ?: JSONArray()
                for (j in 0 until links.length()) {
                    val link = links.getJSONObject(j)
                    val url = link.text("direct_asset_url") ?: link.text("url") ?: continue
                    val name = url.substringAfterLast('/').takeIf { it.endsWith(".apk", ignoreCase = true) } ?: link.text("name") ?: continue
                    assets += Asset(name, url, -1, null)
                }
                // Many releases link the APKs they uploaded to the project in their notes.
                UPLOAD.findAll(release.text("description") ?: "").forEach { link ->
                    val path = link.groupValues[1]
                    assets += Asset(path.substringAfterLast('/'), "https://${place.host}/-/project/${gitlabProject()}$path", -1, null)
                }
                releases += Release(tag, release.optBoolean("upcoming_release"), assets.filter { it.name.endsWith(".apk", ignoreCase = true) })
            } else {
                if (release.optBoolean("draft")) continue
                val files = release.optJSONArray("assets") ?: JSONArray()
                for (j in 0 until files.length()) {
                    val asset = files.getJSONObject(j)
                    val name = asset.text("name") ?: continue
                    val url = asset.text("browser_download_url") ?: continue
                    if (!name.endsWith(".apk", ignoreCase = true)) continue
                    val sha256 = asset.text("digest")?.takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:")?.lowercase()
                    assets += Asset(name, url, asset.optLong("size", -1), sha256)
                }
                releases += Release(tag, release.optBoolean("prerelease"), assets)
            }
        }
        return releases
    }

    private fun toJson(releases: List<Release>) = JSONArray().also { list ->
        for (release in releases) {
            val assets = JSONArray()
            for (asset in release.assets) {
                val json = JSONObject().put("name", asset.name).put("url", asset.url).put("size", asset.size)
                asset.sha256?.let { json.put("sha256", it) }
                assets.put(json)
            }
            list.put(JSONObject().put("tag", release.tag).put("prerelease", release.prerelease).put("assets", assets))
        }
    }

    private fun releases(saved: JSONObject): List<Release> {
        val list = saved.optJSONArray("releases") ?: return emptyList()
        return (0 until list.length()).map { i ->
            val release = list.getJSONObject(i)
            val assets = release.getJSONArray("assets")
            Release(
                release.getString("tag"), release.optBoolean("prerelease"),
                (0 until assets.length()).map { j ->
                    val asset = assets.getJSONObject(j)
                    Asset(asset.getString("name"), asset.getString("url"), asset.optLong("size", -1), asset.text("sha256"))
                },
            )
        }
    }

    /**
     * The APK to install: from the newest release (not a pre-release, unless the app takes them) that has an APK
     * matching apkFilter; of its APKs, the one for this phone.
     */
    private fun choose(app: App, releases: List<Release>): Choice {
        val filter = try {
            app.apkFilter?.let { Regex(it) }
        } catch (e: PatternSyntaxException) {
            return Choice.Problem("${app.id}: apkFilter is not a regular expression (${e.description} at ${e.index})")
        }
        val allowed = releases.filter { app.prerelease || !it.prerelease }
        if (allowed.isEmpty()) {
            return Choice.Problem(
                if (releases.isEmpty()) "${app.id}: ${app.url} has no releases with an APK"
                else "${app.id}: ${app.url} has only pre-releases with an APK (prerelease = true takes them)",
            )
        }
        for (release in allowed) {
            val apks = release.assets.filter { filter == null || filter.containsMatchIn(it.name) }
            if (apks.isEmpty()) continue
            val fit = fitting(apks)
            return when {
                fit.isEmpty() -> Choice.Problem("${app.id}: no APK of ${release.tag} is for this phone (${Device.abis.joinToString("/")}): ${apks.joinToString(", ") { it.name }}")
                fit.size > 1 -> Choice.Problem("${app.id}: ${release.tag} has several APKs for this phone (${fit.joinToString(", ") { it.name }}): set apkFilter to a regular expression that picks one")
                else -> Choice.Pick(release, fit.single())
            }
        }
        return Choice.Problem("${app.id}: no release of ${app.url} has an APK matching apkFilter \"${app.apkFilter}\" (${allowed.size} looked at)")
    }

    /** The APKs for this phone: the ones named for its first ABI; else those named for none (they have all); else for its others. */
    private fun fitting(apks: List<Asset>): List<Asset> {
        val ranked = apks.mapNotNull { apk ->
            val named = ABIS.filter { (_, words) -> words.containsMatchIn(apk.name.lowercase()) }.map { it.first }
            val rank = if (named.isEmpty()) 1 else named.map { Device.abis.indexOf(it) }.filter { it >= 0 }.minOrNull()?.let { it * 2 }
            rank?.let { apk to it }
        }
        val best = ranked.minOfOrNull { it.second } ?: return emptyList()
        return ranked.filter { it.second == best }.map { it.first }
    }

    /** What a build does for [app]: install or update it, nothing (it is up to date), or a problem. Uses the releases here only. */
    fun plan(app: App, installed: Device.Installed?): Plan {
        val saved = saved(app) ?: return Plan.Problem("${app.id}: its releases are not here (could they not be asked for?)")
        val pick = when (val choice = choose(app, releases(saved))) {
            is Choice.Problem -> return Plan.Problem(choice.text)
            is Choice.Pick -> choice
        }
        val known = saved.optJSONObject("codes")?.optLong(pick.asset.key, -1)?.takeIf { it >= 0 }
        if (installed != null) {
            if (app.signer != null && installed.signers.isNotEmpty() && app.signer !in installed.signers) {
                return Plan.Problem("${app.id} is installed with another signing key than the one in its signer: uninstall it first (its data goes with it), or fix signer")
            }
            if (known != null && known <= installed.versionCode) return Plan.Current(installed.versionName)
            // Before its APK was ever downloaded: a release named for the version installed (v1.2.3 for 1.2.3) is that version.
            if (known == null && sameVersion(installed.versionName, pick.release.tag)) return Plan.Current(installed.versionName)
        }
        val place = place(app.url)
        val pinned = app.signer != null
        return Plan.Install(
            Installer.Step(
                repo = NAME, id = app.id, name = Device.label(app.id) ?: place.name, version = pick.release.tag, code = known,
                size = pick.asset.size, url = pick.asset.url, sha256 = pick.asset.sha256, listedBy = "the release on ${place.host}",
                keys = if (pinned) setOf(app.signer!!) else installed?.signers?.takeIf { it.isNotEmpty() },
                keysFrom = if (pinned) {
                    "pinned in its signer (an APK with another key may not be the developer's; or is the pin wrong?)"
                } else {
                    "of the app installed: Android takes an update only with the same key, so uninstall it first (its data goes with it), or take it out of the config"
                },
                installed = installed, installers = Device.OBTAINIUM, fromRelease = true,
                learn = { code -> learn(app, pick.asset.key, code) },
            ),
        )
    }

    private fun sameVersion(versionName: String, tag: String): Boolean {
        val name = versionName.trim()
        val version = tag.trim().let { if (it.length > 1 && (it[0] == 'v' || it[0] == 'V') && it[1].isDigit()) it.substring(1) else it }
        return name.isNotEmpty() && (name.equals(tag.trim(), ignoreCase = true) || name.equals(version, ignoreCase = true))
    }

    /** Remembers an APK's version code once it is known, so the next build knows it without downloading it. */
    private fun learn(app: App, key: String, code: Long) {
        val saved = saved(app) ?: return
        val codes = saved.optJSONObject("codes") ?: JSONObject().also { saved.put("codes", it) }
        save(app, saved.put("codes", codes.put(key, code)))
    }

    // --- Obtainium's own list of apps.

    private fun readTold(): Set<String> = if (toldFile.isFile) toldFile.readLines().filter { it.isNotBlank() }.toSet() else emptySet()

    private fun writeTold(paths: Set<String>) {
        if (paths.isEmpty()) {
            toldFile.delete()
            return
        }
        dir.mkdirs()
        toldFile.writeText(paths.sorted().joinToString("") { "$it\n" })
    }

    /**
     * Puts the config's apps from releases that are installed in Obtainium's own list, when Obtainium is on the phone
     * and was opened once (it makes its storage then): it reads its list, one file an app, each time it comes to the
     * front. An app already in its list is left as it is. The files the module put there are taken out again when
     * their apps leave the config ([apps] empty: all of them, for the module's removal).
     */
    fun tellObtainium(apps: List<App>) {
        val told = readTold().toMutableSet()
        val before = told.toSet()
        val obtainium = Device.firstInstalled(Device.OBTAINIUM)
        val storage = obtainium?.let { File("/data/media/0/Android/data/$it") }
        val folder = storage?.let { File(it, "files/app_data") }
        val wanted = HashSet<String>()
        if (obtainium != null && storage != null && folder != null && apps.isNotEmpty()) {
            if (!folder.isDirectory) {
                Out.line("Obtainium: its list does not have the apps yet: open it once (it makes its storage then), then build again")
            } else {
                for (app in apps) {
                    val file = File(folder, "${app.id}.json")
                    wanted += file.path
                    // There already: put there by the module, or by the user (theirs is left alone, and stays theirs).
                    if (file.exists()) continue
                    val installed = Device.installed(app.id) ?: continue
                    if (place(app.url).host !in OBTAINIUM_HOSTS) {
                        Out.line("Obtainium: ${app.id} is not put in its list: of the servers here, it knows only ${OBTAINIUM_HOSTS.joinToString(", ")} by themselves")
                        continue
                    }
                    if (write(app, installed, obtainium, file, reference(storage, told))) {
                        told += file.path
                        Out.line("Obtainium: ${app.id} put in its list")
                    } else {
                        Out.line("Obtainium: could not put ${app.id} in its list")
                    }
                }
            }
        }
        var removed = false
        for (path in told.filter { it !in wanted }) {
            val file = File(path)
            if (file.exists()) {
                if (file.delete()) {
                    removed = true
                    Out.line("Obtainium: ${file.name.removeSuffix(".json")} taken out of its list")
                } else {
                    Out.line("Obtainium: could not take ${file.name.removeSuffix(".json")} out of its list")
                    continue
                }
            }
            told -= path
        }
        // Obtainium keeps its list in memory while it runs: in the background, it is ended, and reads it again when opened.
        if (removed && obtainium != null) Device.killInBackground(obtainium)
        if (told != before) writeTold(told)
    }

    /** A file Obtainium made itself in its storage, to give the module's files the same owner and SELinux label. */
    private fun reference(storage: File, told: Set<String>): File? =
        storage.walkTopDown().maxDepth(4).firstOrNull { it.isFile && it.path !in told && !it.name.endsWith(TEMP) }

    /** Obtainium's file for the app: what its own "add app" makes, with the config's settings (it fills in the rest). */
    private fun write(app: App, installed: Device.Installed, obtainium: String, file: File, like: File?): Boolean {
        val place = place(app.url)
        val pick = saved(app)?.let { choose(app, releases(it)) as? Choice.Pick }
        val settings = JSONObject().put("includePrereleases", app.prerelease).put("autoApkFilterByArch", true)
        app.apkFilter?.let { settings.put("apkFilterRegEx", it) }
        app.signer?.let { key -> settings.put("allowedSigningCertHashes", key.chunked(2).joinToString(":").uppercase()) }
        val json = JSONObject()
            .put("id", app.id)
            .put("url", if (place.host == "gitlab.com") "https://gitlab.com/${place.gitlabPath}" else "https://${place.host}/${place.owner}/${place.name}")
            .put("author", place.owner)
            .put("name", Device.label(app.id) ?: place.name)
            // Until Obtainium looks for a newer release itself, the newest it knows is the one installed.
            .put("installedVersion", installed.versionName)
            .put("latestVersion", installed.versionName)
            .put("preferredApkIndex", 0)
            .put("additionalSettings", settings.toString())
            .put("categories", JSONArray())
            .put("overrideSource", JSONObject.NULL)
        pick?.let { json.put("apkUrls", JSONArray().put(JSONArray().put(it.asset.name).put(it.asset.url)).toString()) }
        // Written next to it under a name Obtainium does not read, then renamed: it never sees half a file.
        val temp = File(file.parentFile, ".${app.id}.json$TEMP")
        return try {
            temp.writeText(json.toString())
            Device.handOver(temp, obtainium, like) && temp.renameTo(file)
        } catch (e: Exception) {
            false
        } finally {
            temp.delete()
        }
    }

    companion object {
        /** The name of this source in the config: android.packages.obtainium, android.settings.repo.obtainium. */
        const val NAME = "obtainium"

        /** How many releases are asked for: the newest with an APK for the phone is usually among the first. */
        private const val COUNT = 15

        private const val GITHUB = "github"
        private const val GITLAB = "gitlab"
        private const val GITEA = "gitea"
        private const val TEMP = ".seraph"

        /** The servers Obtainium knows by themselves (others it needs told which kind they are). */
        private val OBTAINIUM_HOSTS = listOf("github.com", "gitlab.com", "codeberg.org")

        /** An APK uploaded to a GitLab project, linked in a release's notes. */
        private val UPLOAD = Regex("""\]\((/uploads/[0-9a-f]+/[^)\s]+\.apk)\)""", RegexOption.IGNORE_CASE)

        /** The ABIs an APK's name may say it is for, in the words developers use. */
        private val ABIS = listOf(
            "arm64-v8a" to "arm64[-_]?v8a?|aarch64|armv8a?",
            "armeabi-v7a" to "armeabi[-_]?v7a|armeabi|armv7a?|arm32|armhf|arm",
            "x86_64" to "x86[-_]64|x64|amd64",
            "x86" to "x86(?![-_]?64)|i[3-6]86",
        ).map { (abi, words) -> abi to Regex("(?<![a-z0-9])(?:$words)(?![a-z0-9])") }

        /** The config's apps from releases (android.packages.obtainium), as the Nix config checked them. */
        fun apps(android: JSONObject): List<App> {
            val list = android.optJSONObject("packages")?.optJSONArray(NAME) ?: return emptyList()
            return (0 until list.length()).map { i ->
                val app = list.getJSONObject(i)
                val id = app.getString("id")
                val url = app.getString("url").trimEnd('/')
                if (!PACKAGE_NAME.matches(id) || !url.startsWith("https://")) {
                    throw Stop("android.packages.$NAME: each app needs its package name as id, and the https:// address of its repository as url")
                }
                App(id, url, app.text("apkFilter"), app.text("signer")?.lowercase()?.replace(":", ""), app.optBoolean("prerelease"))
            }
        }

        /** A string value, or null when it is missing or JSON's null (which optString would give as "null"). */
        private fun JSONObject.text(key: String): String? = if (isNull(key)) null else optString(key).ifEmpty { null }
    }
}
