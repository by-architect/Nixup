package dev.andronix.manager.engine

import android.util.JsonReader
import android.util.JsonToken
import android.util.JsonWriter
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.TreeMap
import java.util.jar.JarFile

/**
 * An F-Droid repository: f-droid.org, or one of the config's android.repos. Its index is trusted down a chain that
 * starts at the key pinned in the config: entry.jar is signed with that key, entry.json in it gives index-v2.json's
 * SHA-256, and the index gives each APK's SHA-256 and signing key. The index is kept in repos/<name>/ as one small
 * file per app (packages/<id>.json) and as the tree of package names the Nix config sees as pkgs.<name> (tree.json);
 * the index itself (70 MB for f-droid.org) is not kept.
 */
class FDroid(private val dir: File, val name: String, val url: String, private val fingerprint: String) {
    class Version(
        val file: String, val sha256: String, val size: Long, val code: Long, val name: String,
        val minSdk: Int, val maxSdk: Int, val abis: List<String>, val signers: List<String>, val beta: Boolean,
    )

    class App(val id: String, val name: String, val preferredSigner: String?, val versions: List<Version>)

    private val stateFile = File(dir, "state.json")
    private val packages = File(dir, "packages")
    private val tree = File(dir, "tree.json")

    /** What is known of the index here, when it was fetched from this address with this key: a change in the config makes it be fetched again. */
    private val state: JSONObject?
        get() = (if (stateFile.isFile) JSONObject(stateFile.readText()) else null)
            ?.takeIf { it.optString("url") == url && it.optString("fingerprint") == fingerprint }

    /** Where the APKs are: the repository's address from the config, the one its key is pinned for. */
    val address: String get() = url

    /** When the index was made, for messages. */
    val date: String get() = state?.let { Out.date(it.getLong("timestamp")) } ?: "never"

    /** Brings the index up to date: asks the repository for a newer one at most once a day (or always, [force]d). */
    fun refresh(force: Boolean): Int {
        dir.mkdirs()
        val old = state?.takeIf { packages.isDirectory && tree.isFile }
        val now = System.currentTimeMillis()
        if (old != null && !force && now - old.getLong("checked") < MAX_AGE) {
            Out.line("$name index from $date (${old.getInt("count")} apps; looked for a newer one ${hours(now - old.getLong("checked"))} ago)")
            return 0
        }
        val entry = try {
            entry()
        } catch (e: IOException) {
            if (old == null) throw Stop("cannot get the index of $name (${e.message}): connect to the internet and build again")
            Out.line("$name index: cannot look for a newer one (${e.message}); using the one from $date")
            return 0
        }
        val index = entry.getJSONObject("index")
        val timestamp = entry.getLong("timestamp")
        if (old != null && timestamp < old.getLong("timestamp")) {
            throw Stop("$name sent an index older than the one here (${Out.date(timestamp)}): refused, the one from $date stays")
        }
        if (old != null && index.getString("sha256") == old.getString("sha256")) {
            stateFile.writeText(old.put("checked", now).toString())
            Out.line("$name index from $date is the newest (${old.getInt("count")} apps)")
            return 0
        }
        val size = index.getLong("size")
        // The index, its unpacked files and the old ones next to them.
        if (Device.freeBytes(dir) < size * 2) {
            throw Stop("not enough free space for the index of $name: it needs ${Out.size(size * 2)}, ${Out.size(Device.freeBytes(dir))} is free")
        }
        Out.line("$name index: downloading the one from ${Out.date(timestamp)} (${Out.size(size)}, ${index.getInt("numPackages")} apps)")
        val file = File(dir, "index-v2.json")
        Net.fetch(url + index.getString("name"), file, size, index.getString("sha256"), "the signed entry.jar of $name", Out.progress("   index", size))
        Out.line("$name index: checked; reading it")
        val count = unpack(file)
        file.delete()
        stateFile.writeText(
            JSONObject().put("sha256", index.getString("sha256")).put("timestamp", timestamp)
                .put("count", count).put("checked", now).put("url", url).put("fingerprint", fingerprint).toString(),
        )
        Out.line("$name index from ${Out.date(timestamp)}: $count apps")
        return 0
    }

    /** The app with this package name, from the index here, or null when the repository does not have it. */
    fun find(id: String): App? {
        val file = File(packages, "$id.json")
        if (!PACKAGE_NAME.matches(id) || state == null || !file.isFile) return null
        val json = JSONObject(file.readText())
        val versions = json.getJSONArray("versions")
        return App(
            id, json.getString("name"), json.optString("preferredSigner").ifEmpty { null },
            (0 until versions.length()).map { i ->
                val v = versions.getJSONObject(i)
                Version(
                    v.getString("file"), v.getString("sha256"), v.getLong("size"), v.getLong("code"), v.getString("name"),
                    v.getInt("minSdk"), v.getInt("maxSdk"), strings(v, "abis"), strings(v, "signers"), v.getBoolean("beta"),
                )
            },
        )
    }

    private fun strings(json: JSONObject, key: String) = json.getJSONArray(key).let { a -> (0 until a.length()).map { a.getString(it) } }

    sealed class Choice {
        class Pick(val version: Version) : Choice()
        class Problem(val text: String) : Choice()
    }

    /**
     * The version F-Droid itself would suggest: the newest stable one this phone can run (Android version, ABIs),
     * signed with the key of the app already installed, else with the app's preferred key.
     */
    fun suggested(app: App, installed: Device.Installed?): Choice {
        val runs = app.versions.filter { v ->
            !v.beta && v.minSdk <= Device.sdk && (v.maxSdk == 0 || Device.sdk <= v.maxSdk) &&
                (v.abis.isEmpty() || v.abis.any { it in Device.abis })
        }
        if (runs.isEmpty()) {
            return Choice.Problem("no version of ${app.id} runs on this phone (Android API ${Device.sdk}, ${Device.abis.joinToString("/")})")
        }
        val signed = when {
            installed != null && installed.signers.isNotEmpty() -> runs.filter { v -> v.signers.any { it in installed.signers } }
            app.preferredSigner != null -> runs.filter { app.preferredSigner in it.signers }.ifEmpty { runs }
            else -> runs
        }
        if (signed.isEmpty()) {
            return Choice.Problem(
                "${app.id} is installed with another signing key than the builds in $name, so they cannot update it: " +
                    "uninstall it first (its data goes with it), or take it out of the config",
            )
        }
        return Choice.Pick(signed.maxBy { it.code })
    }

    /** Installing or updating [app] to [version]: its APK's checksum and keys are those in the signed index. */
    fun step(app: App, version: Version, installed: Device.Installed?) = Installer.Step(
        repo = name, id = app.id, name = app.name, version = version.name, code = version.code, size = version.size,
        url = address + version.file, sha256 = version.sha256, listedBy = "the signed index of $name",
        keys = version.signers.toSet(), keysFrom = "listed for it in the signed index of $name",
        installed = installed, installers = Device.FDROID,
    )

    /** entry.json, from entry.jar once its signature is checked against the repository's pinned key. */
    private fun entry(): JSONObject {
        val jar = File(dir, "entry.jar")
        jar.writeBytes(Net.get("$url/entry.jar"))
        JarFile(jar, true).use { file ->
            val entry = file.getJarEntry("entry.json") ?: throw SecurityException("entry.jar has no entry.json")
            // A signed jar's entry is checked while it is read; then its certificates are known.
            val bytes = file.getInputStream(entry).use { it.readBytes() }
            val certificates = entry.certificates ?: throw SecurityException("entry.json in entry.jar is not signed")
            if (certificates.none { Net.sha256(it.encoded) == fingerprint }) {
                throw SecurityException("the index of $name is not signed with the key pinned in android.repos.$name")
            }
            return JSONObject(String(bytes, Charsets.UTF_8))
        }
    }

    /** Reads the index once, as a stream, into packages/<id>.json and tree.json; returns how many apps it has. */
    private fun unpack(index: File): Int {
        val fresh = File(dir, "packages.new").apply { deleteRecursively(); mkdirs() }
        val names = Node()
        var count = 0
        JsonReader(index.bufferedReader()).use { r ->
            r.beginObject()
            while (r.hasNext()) when (r.nextName()) {
                "packages" -> obj(r) { id ->
                    val app = readApp(id, r)
                    if (PACKAGE_NAME.matches(id)) {
                        writeApp(File(fresh, "$id.json"), app)
                        names.add(id)
                        count++
                    }
                }
                else -> r.skipValue()
            }
            r.endObject()
        }
        val freshTree = File(dir, "tree.json.new")
        JsonWriter(freshTree.bufferedWriter()).use { names.write(it, name) }
        val old = File(dir, "packages.old").apply { deleteRecursively() }
        if (packages.exists()) packages.renameTo(old)
        if (!fresh.renameTo(packages) || !freshTree.renameTo(tree)) throw IOException("cannot put the new index in place")
        old.deleteRecursively()
        return count
    }

    private fun readApp(id: String, r: JsonReader): App {
        var name = id
        var preferredSigner: String? = null
        val versions = ArrayList<Version>()
        obj(r) { key ->
            when (key) {
                "metadata" -> obj(r) { field ->
                    when (field) {
                        "name" -> name = localized(r) ?: name
                        "preferredSigner" -> preferredSigner = string(r)
                        else -> r.skipValue()
                    }
                }
                "versions" -> obj(r) { readVersion(r)?.let { versions += it } }
                else -> r.skipValue()
            }
        }
        return App(id, name, preferredSigner, versions)
    }

    private fun readVersion(r: JsonReader): Version? {
        var file: String? = null
        var sha256: String? = null
        var size = 0L
        var code = 0L
        var name = ""
        var minSdk = 1
        var maxSdk = 0
        val abis = ArrayList<String>()
        val signers = ArrayList<String>()
        var beta = false
        obj(r) { key ->
            when (key) {
                "file" -> obj(r) { field ->
                    when (field) {
                        "name" -> file = string(r)
                        "sha256" -> sha256 = string(r)
                        "size" -> size = r.nextLong()
                        else -> r.skipValue()
                    }
                }
                "manifest" -> obj(r) { field ->
                    when (field) {
                        "versionCode" -> code = r.nextLong()
                        "versionName" -> name = string(r) ?: ""
                        "usesSdk" -> obj(r) { sdk -> if (sdk == "minSdkVersion") minSdk = r.nextInt() else r.skipValue() }
                        "maxSdkVersion" -> maxSdk = r.nextInt()
                        "nativecode" -> array(r) { string(r)?.let { abis += it } }
                        "signer" -> obj(r) { signer -> if (signer == "sha256") array(r) { string(r)?.let { signers += it } } else r.skipValue() }
                        else -> r.skipValue()
                    }
                }
                // Stable versions have none; the others are in a channel like "Beta".
                "releaseChannels" -> array(r) { r.skipValue(); beta = true }
                else -> r.skipValue()
            }
        }
        val f = file ?: return null
        val s = sha256 ?: return null
        return Version(f, s, size, code, name, minSdk, maxSdk, abis, signers, beta)
    }

    private fun writeApp(file: File, app: App) = JsonWriter(file.bufferedWriter()).use { w ->
        w.beginObject()
        w.name("name").value(app.name)
        app.preferredSigner?.let { w.name("preferredSigner").value(it) }
        w.name("versions").beginArray()
        for (v in app.versions) {
            w.beginObject()
            w.name("file").value(v.file).name("sha256").value(v.sha256).name("size").value(v.size)
            w.name("code").value(v.code).name("name").value(v.name)
            w.name("minSdk").value(v.minSdk.toLong()).name("maxSdk").value(v.maxSdk.toLong())
            w.name("abis").beginArray().apply { v.abis.forEach { value(it) } }.endArray()
            w.name("signers").beginArray().apply { v.signers.forEach { value(it) } }.endArray()
            w.name("beta").value(v.beta)
            w.endObject()
        }
        w.endArray()
        w.endObject()
    }

    private fun localized(r: JsonReader): String? {
        if (r.peek() == JsonToken.STRING) return r.nextString()
        var first: String? = null
        var english: String? = null
        obj(r) { language ->
            val text = string(r)
            if (first == null) first = text
            if (language == "en-US") english = text
        }
        return english ?: first
    }

    private inline fun obj(r: JsonReader, each: (String) -> Unit) {
        if (r.peek() != JsonToken.BEGIN_OBJECT) return r.skipValue()
        r.beginObject()
        while (r.hasNext()) each(r.nextName())
        r.endObject()
    }

    private inline fun array(r: JsonReader, each: () -> Unit) {
        if (r.peek() != JsonToken.BEGIN_ARRAY) return r.skipValue()
        r.beginArray()
        while (r.hasNext()) each()
        r.endArray()
    }

    private fun string(r: JsonReader): String? = if (r.peek() == JsonToken.NULL) r.nextNull().let { null } else r.nextString()

    /** The package names as a tree: org.fdroid.fdroid is org -> fdroid -> fdroid, its node holding the full name in _id. */
    private class Node {
        val children = TreeMap<String, Node>()
        var id: String? = null

        fun add(name: String) {
            var node = this
            for (part in name.split('.')) node = node.children.getOrPut(part) { Node() }
            node.id = name
        }

        fun write(w: JsonWriter, repo: String) {
            w.beginObject()
            id?.let { w.name("_id").value(it).name("_repo").value(repo) }
            for ((part, child) in children) {
                w.name(part)
                child.write(w, repo)
            }
            w.endObject()
        }
    }

    private fun hours(millis: Long) = if (millis < 3_600_000) "${millis / 60_000} min" else "${millis / 3_600_000} h"

    companion object {
        /** f-droid.org, there in every config, and the SHA-256 of the certificate it signs its index with. */
        const val OFFICIAL_URL = "https://f-droid.org/repo"
        const val OFFICIAL_FINGERPRINT = "43238d512c1e5eb2d6569f4a3afbf5523418b82e0a3ed1552770abb9a9c9ccab"

        /** How long the index here counts as new enough to build with, before the repository is asked again. */
        const val MAX_AGE = 24L * 3600 * 1000

        private val NAME = Regex("[a-z][a-zA-Z0-9]*")
        private val KEY = Regex("[0-9a-f]{64}")

        /** The repositories of a profile (android.repos), each with its index in [data]/repos/<name>; f-droid.org for a profile without any. */
        fun of(profile: JSONObject, data: File): Map<String, FDroid> {
            val repos = profile.optJSONObject("android")?.optJSONObject("repos")
                ?: JSONObject().put("fdroid", JSONObject().put("url", OFFICIAL_URL).put("fingerprint", OFFICIAL_FINGERPRINT))
            val found = LinkedHashMap<String, FDroid>()
            for (name in repos.keys()) {
                val repo = repos.getJSONObject(name)
                val url = repo.getString("url").trimEnd('/')
                val key = repo.getString("fingerprint").lowercase()
                if (!NAME.matches(name) || !url.startsWith("https://") || !KEY.matches(key)) {
                    throw Stop("android.repos.$name: the module trusts only an https:// address with its 64-digit fingerprint")
                }
                found[name] = FDroid(File(data, "repos/$name"), name, url, key)
            }
            return found
        }
    }
}
