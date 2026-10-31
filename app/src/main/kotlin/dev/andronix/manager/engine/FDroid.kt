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
 * The f-droid.org repository. Its index is trusted down a chain that starts at a pinned key: entry.jar is signed
 * with f-droid.org's key, entry.json in it gives index-v2.json's SHA-256, and the index gives each APK's SHA-256
 * and signing key. The index is kept as one small file per app (packages/<name>.json) and as the tree of package
 * names the Nix config sees as pkgs.fdroid (tree.json); the 70 MB index itself is not kept.
 */
class FDroid(private val dir: File) {
    class Version(
        val file: String, val sha256: String, val size: Long, val code: Long, val name: String,
        val minSdk: Int, val maxSdk: Int, val abis: List<String>, val signers: List<String>, val beta: Boolean,
    )

    class App(val id: String, val name: String, val preferredSigner: String?, val versions: List<Version>)

    private val stateFile = File(dir, "state.json")
    private val packages = File(dir, "packages")
    private val tree = File(dir, "tree.json")

    private val state: JSONObject? get() = if (stateFile.isFile) JSONObject(stateFile.readText()) else null

    /** Where the APKs are: the address the index gives for the repository. */
    val address: String get() = state?.optString("address")?.ifEmpty { null } ?: REPO

    /** When the index was made, for messages. */
    val date: String get() = state?.let { Out.date(it.getLong("timestamp")) } ?: "never"

    /** Brings the index up to date: asks f-droid.org for a newer one at most once a day (or always, [force]d). */
    fun refresh(force: Boolean): Int {
        dir.mkdirs()
        val old = state?.takeIf { packages.isDirectory && tree.isFile }
        val now = System.currentTimeMillis()
        if (old != null && !force && now - old.getLong("checked") < MAX_AGE) {
            Out.line("F-Droid index from $date (${old.getInt("count")} apps; looked for a newer one ${hours(now - old.getLong("checked"))} ago)")
            return 0
        }
        val entry = try {
            entry()
        } catch (e: IOException) {
            if (old == null) throw Stop("cannot get the F-Droid index (${e.message}): connect to the internet and build again")
            Out.line("F-Droid index: cannot look for a newer one (${e.message}); using the one from $date")
            return 0
        }
        val index = entry.getJSONObject("index")
        val timestamp = entry.getLong("timestamp")
        if (old != null && timestamp < old.getLong("timestamp")) {
            throw Stop("f-droid.org sent an index older than the one here (${Out.date(timestamp)}): refused, the one from $date stays")
        }
        if (old != null && index.getString("sha256") == old.getString("sha256")) {
            stateFile.writeText(old.put("checked", now).toString())
            Out.line("F-Droid index from $date is the newest (${old.getInt("count")} apps)")
            return 0
        }
        val size = index.getLong("size")
        // The index, its unpacked files and the old ones next to them.
        if (Device.freeBytes(dir) < size * 2) {
            throw Stop("not enough free space for the F-Droid index: it needs ${Out.size(size * 2)}, ${Out.size(Device.freeBytes(dir))} is free")
        }
        Out.line("F-Droid index: downloading the one from ${Out.date(timestamp)} (${Out.size(size)}, ${index.getInt("numPackages")} apps)")
        val file = File(dir, "index-v2.json")
        Net.fetch(REPO + index.getString("name"), file, size, index.getString("sha256"), Out.progress("   index", size))
        Out.line("F-Droid index: checked; reading it")
        val (count, address) = unpack(file)
        file.delete()
        stateFile.writeText(
            JSONObject().put("sha256", index.getString("sha256")).put("timestamp", timestamp)
                .put("count", count).put("checked", now).put("address", address).toString(),
        )
        Out.line("F-Droid index from ${Out.date(timestamp)}: $count apps")
        return 0
    }

    /** The app with this package name, from the index here, or null when F-Droid does not have it. */
    fun find(id: String): App? {
        val file = File(packages, "$id.json")
        if (!PACKAGE_NAME.matches(id) || !file.isFile) return null
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
                "${app.id} is installed with another signing key than F-Droid's builds, so they cannot update it: " +
                    "uninstall it first (its data goes with it), or take it out of the config",
            )
        }
        return Choice.Pick(signed.maxBy { it.code })
    }

    /** entry.json, from entry.jar once its signature is checked against f-droid.org's pinned key. */
    private fun entry(): JSONObject {
        val jar = File(dir, "entry.jar")
        jar.writeBytes(Net.get("$REPO/entry.jar"))
        JarFile(jar, true).use { file ->
            val entry = file.getJarEntry("entry.json") ?: throw SecurityException("entry.jar has no entry.json")
            // A signed jar's entry is checked while it is read; then its certificates are known.
            val bytes = file.getInputStream(entry).use { it.readBytes() }
            val certificates = entry.certificates ?: throw SecurityException("entry.json in entry.jar is not signed")
            if (certificates.none { Net.sha256(it.encoded) == FINGERPRINT }) {
                throw SecurityException("entry.jar is not signed with f-droid.org's key")
            }
            return JSONObject(String(bytes, Charsets.UTF_8))
        }
    }

    /** Reads the index once, as a stream, into packages/<name>.json and tree.json; returns how many apps, and the repository's address. */
    private fun unpack(index: File): Pair<Int, String> {
        val fresh = File(dir, "packages.new").apply { deleteRecursively(); mkdirs() }
        val names = Node()
        var address = REPO
        var count = 0
        JsonReader(index.bufferedReader()).use { r ->
            r.beginObject()
            while (r.hasNext()) when (r.nextName()) {
                "repo" -> obj(r) { key -> if (key == "address") address = string(r) ?: address else r.skipValue() }
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
        JsonWriter(freshTree.bufferedWriter()).use { names.write(it) }
        val old = File(dir, "packages.old").apply { deleteRecursively() }
        if (packages.exists()) packages.renameTo(old)
        if (!fresh.renameTo(packages) || !freshTree.renameTo(tree)) throw IOException("cannot put the new index in place")
        old.deleteRecursively()
        return count to address
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

        fun write(w: JsonWriter) {
            w.beginObject()
            id?.let { w.name("_id").value(it) }
            for ((part, child) in children) {
                w.name(part)
                child.write(w)
            }
            w.endObject()
        }
    }

    private fun hours(millis: Long) = if (millis < 3_600_000) "${millis / 60_000} min" else "${millis / 3_600_000} h"

    companion object {
        const val REPO = "https://f-droid.org/repo"

        /** SHA-256 of the certificate f-droid.org signs its repository with (entry.jar). */
        const val FINGERPRINT = "43238d512c1e5eb2d6569f4a3afbf5523418b82e0a3ed1552770abb9a9c9ccab"

        /** How long the index here counts as new enough to build with, before f-droid.org is asked again. */
        const val MAX_AGE = 24L * 3600 * 1000
    }
}
