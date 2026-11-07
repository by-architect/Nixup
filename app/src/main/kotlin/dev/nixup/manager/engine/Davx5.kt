package dev.nixup.manager.engine

import android.accounts.Account
import android.content.ContentResolver
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.os.Bundle
import android.os.IBinder
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.system.Os
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory

/**
 * DAVx⁵'s accounts, from the profile's android.davx5 (services.davx5, nix/davx5.nix), set at each build after the apps.
 * DAVx⁵ is an Android app: it syncs a CalDAV/CardDAV server's calendars and address books into Android's own Calendar
 * and Contacts, and Android lets only DAVx⁵ add its accounts. So:
 *  - an account of the config that is not on the phone gets DAVx⁵'s own login screen, opened already filled in (the
 *    server, the user name, the password): a tap on Login, then on Finish, adds it, and DAVx⁵ finds its
 *    calendars and address books itself, the ones syncCollections says selected for syncing (DAVx⁵'s own setting
 *    "preselect collections", which the build sets);
 *  - or, with automatic, it is added with no taps: the engine asks the server what DAVx⁵'s login and its first look
 *    at the collections would ask, writes the answers into DAVx⁵'s database while DAVx⁵ is stopped, adds the account as
 *    DAVx⁵ (the engine run with its uid: see AppProcess) with its settings' version before the update in which DAVx⁵
 *    plans an account's syncs, so that DAVx⁵ plans them itself, and starts its first sync. That database is DAVx⁵'s
 *    own and changes with its versions: only the one Nixup knows is written ([SCHEMA]), as DAVx⁵ would remove all of its
 *    accounts on finding one it cannot read; with another, the login screen opens instead;
 *  - an account there gets the config's user name and password, as DAVx⁵'s account settings change them (Android's
 *    account, through DAVx⁵'s uid). One that left the config stays in DAVx⁵, as do the accounts added by hand.
 * The setting the build changes is given back when services.davx5 leaves the config, and when the module is removed
 * (original/davx5.json).
 */
class Davx5(private val data: File) {
    private val journalFile = File(data, "original/davx5.json")

    /**
     * An account of the config: [name] is its attribute's, [accountName] its name in DAVx⁵ (and Android), or null for
     * the one DAVx⁵'s login gives it.
     */
    private class Wanted(
        val name: String, val accountName: String?, val url: String, val userName: String,
        val passwordFile: String?, val automatic: Boolean,
    )

    /** One of DAVx⁵'s accounts on the phone, with its user name and password when they could be read ([known]). */
    private class Stored(val name: String, val userName: String?, val password: String?, val known: Boolean)

    /** What the build does with an account: add it (none [found]), or set its user name or password. */
    private class Planned(val wanted: Wanted, val found: Stored?, val userName: Boolean, val password: Boolean)

    private var spec: JSONObject? = null
    private var planned: List<Planned> = emptyList()
    /** "preselect collections" to write, when it is not as the config says. */
    private var preselect: Int? = null
    private var giveBack = false

    /** Whether the plan changes anything. */
    val changes: Boolean get() = giveBack || preselect != null || planned.any { it.found == null || it.userName || it.password }

    /**
     * Plans the profile's android.davx5 ([spec]; null without services.davx5): DAVx⁵ must be installed, or be
     * [installing] in this build. [changingSecrets]: the secrets' files this build changes (a password among them).
     */
    fun plan(spec: JSONObject?, installing: Set<String>, changingSecrets: List<String>, problems: MutableList<String>) {
        this.spec = spec
        val installed = Device.versionCode(APP) != null
        if (spec == null) {
            giveBack = journalFile.isFile && installed
            if (giveBack) Out.line("DAVx⁵: its \"preselect collections\" back as before (services.davx5 left the config)")
            return
        }
        if (!installed && APP !in installing) {
            problems += "services.davx5: DAVx⁵ ($APP) is not on the phone, and this build does not install it: list it in " +
                "android.packages, or set services.davx5.install (\"fdroid\")"
            return
        }
        val wanted = wanted(spec)
        val want = spec.optInt("preselect", PRESELECT_ALL)
        if (!installed || (preference() ?: PRESELECT_NONE) != want) preselect = want
        val stored = if (installed) stored() else emptyList()
        val servers = if (installed) servers() else emptyMap()
        // The names the config gives are those accounts' alone.
        val named = wanted.mapNotNull { it.accountName }.toSet()
        val taken = HashSet<String>()
        planned = wanted.map { w ->
            // By the name the config gives it; else, or under another name (changed at its login), by its user name on
            // its server (DAVx⁵'s login names an account after the email address the server has for the user).
            val host = w.url.toHttpUrlOrNull()?.host
            val found = w.accountName?.let { name -> stored.firstOrNull { it.name == name } }
                ?: stored.firstOrNull { s ->
                    s.name !in taken && (s.name == w.accountName || s.name !in named) && s.userName == w.userName &&
                        host != null && servers[s.name].orEmpty().any { sameServer(it, host) }
                }
            found?.let { taken += it.name }
            if (found == null) {
                Planned(w, null, userName = false, password = false)
            } else {
                Planned(
                    w, found,
                    userName = found.known && found.userName != w.userName,
                    password = found.known && w.passwordFile != null && (w.passwordFile in changingSecrets || secret(w.passwordFile) != found.password),
                )
            }
        }

        val lines = ArrayList<String>()
        preselect?.let { lines += "  \"preselect collections\": ${describe(it)}" }
        val databaseProblem = if (installed) databaseProblem(header = true) else null
        for (p in planned) {
            val w = p.wanted
            val label = label(w, p.found)
            lines += when {
                p.found == null && w.automatic && databaseProblem == null -> "  $label: to add, with no taps (from ${display(w.url)})"
                p.found == null && w.automatic -> "  $label: to add: its login screen opens on the phone, filled in ($databaseProblem, so not with no taps)"
                p.found == null -> "  $label: to add: its login screen opens on the phone, filled in (${display(w.url)})"
                p.userName || p.password -> "  $label: its ${listOfNotNull("user name".takeIf { p.userName }, "password".takeIf { p.password }).joinToString(" and ")}"
                else -> "  $label: as the config says"
            }
        }
        if (lines.isNotEmpty()) {
            Out.line("DAVx⁵:")
            lines.forEach { Out.line(it) }
        }
    }

    /** Applies the plan; returns how many things did not take. */
    fun apply(): Int {
        val spec = spec ?: return if (giveBack) restore() else 0
        val uid = Device.uid(APP)
        if (uid == null) {
            Out.line("✘ DAVx⁵: it is not on the phone, so its accounts are not set")
            return 1
        }
        var failed = 0
        var stopped = false
        var started = false
        this.preselect?.let { value ->
            stopped = stop()
            if (writePreference(value, journal = true)) Out.line("✔ DAVx⁵: \"preselect collections\": ${describe(value)}")
            else { Out.line("✘ DAVx⁵: \"preselect collections\" could not be set"); failed++ }
        }

        val preselect = spec.optInt("preselect", PRESELECT_ALL)
        val missing = planned.filter { it.found == null }
        val toLogin = ArrayList(missing.filter { !it.wanted.automatic })
        // With no taps: what the server has, then DAVx⁵ stopped and that written into its database; where Nixup cannot
        // write it, the login screen.
        val automatic = missing.filter { it.wanted.automatic }
        databaseProblem(header = true)?.takeIf { automatic.isNotEmpty() }?.let { problem ->
            Out.line("⚠ DAVx⁵: $problem, so ${automatic.joinToString(", ") { label(it.wanted, null) }} get its login screen instead")
            toLogin.addAll(0, automatic)
        }
        val discovered = LinkedHashMap<Planned, List<Service>>()
        val passwords = HashMap<Planned, String>()
        for (p in automatic.filter { it !in toLogin }) {
            val label = label(p.wanted, null)
            val password = p.wanted.passwordFile?.let { secret(it) }
            if (password == null) {
                Out.line("✘ DAVx⁵: $label: its passwordFile cannot be read (${p.wanted.passwordFile})")
                failed++
                continue
            }
            val base = p.wanted.url.toHttpUrlOrNull()?.newBuilder()?.username("")?.password("")?.build()
            if (base == null) {
                Out.line("✘ DAVx⁵: $label: ${p.wanted.url} is not an http:// or https:// address")
                failed++
                continue
            }
            try {
                val services = discover(base, p.wanted.userName, password)
                if (services.isEmpty()) {
                    Out.line("✘ DAVx⁵: $label: ${display(p.wanted.url)} has no calendars nor address books for ${p.wanted.userName} (CalDAV and CardDAV found nothing)")
                    failed++
                    continue
                }
                discovered[p] = services
                passwords[p] = password
            } catch (e: IOException) {
                Out.line("✘ DAVx⁵: $label: ${e.message}")
                failed++
            }
        }
        val addNames = discovered.mapValues { (p, services) ->
            p.wanted.accountName ?: services.firstNotNullOfOrNull { it.emails.firstOrNull() } ?: p.wanted.userName
        }
        val written = ArrayList<Planned>()
        if (discovered.isNotEmpty()) {
            Device.run("am", "force-stop", APP)
            stopped = true
            try {
                val problem = writeDatabase(discovered.mapKeys { addNames.getValue(it.key) }, preselect)
                if (problem != null) {
                    Out.line("⚠ DAVx⁵: $problem, so ${discovered.keys.joinToString(", ") { label(it.wanted, null) }} get its login screen instead")
                    toLogin.addAll(0, discovered.keys)
                } else {
                    written += discovered.keys
                }
            } catch (e: Exception) {
                Out.line("✘ DAVx⁵: its database could not be written (${e.javaClass.simpleName}: ${e.message})")
                failed += discovered.size
            }
            // (Force-stopped: Android runs nothing of DAVx⁵'s, the syncs asked for below among it, until it is let off.)
            unstop()
        }

        // The accounts themselves, as DAVx⁵: the user names and passwords of those there, and the ones written above.
        val toSet = planned.filter { it.found != null && (it.userName || it.password || !it.found.known) }
        if (toSet.isNotEmpty() || written.isNotEmpty()) {
            val input = JSONArray()
            for (p in toSet) {
                val item = JSONObject().put("name", p.found!!.name).put("userName", p.wanted.userName)
                p.wanted.passwordFile?.let { secret(it) }?.let { item.put("password", it) }
                input.put(item)
            }
            for (p in written) {
                // With its first sync: DAVx⁵ then updates its settings from their version, which plans its syncs.
                input.put(
                    JSONObject().put("name", addNames.getValue(p)).put("add", true).put("password", passwords[p])
                        .put("userData", JSONObject().put(KEY_SETTINGS_VERSION, SETTINGS_VERSION).put(KEY_USER_NAME, p.wanted.userName))
                        .put("sync", JSONArray().put(CalendarContract.AUTHORITY)),
                )
            }
            val results = asDavx5(uid, input)
            for (p in toSet) {
                val result = results[p.found!!.name]
                val label = label(p.wanted, p.found)
                when (result?.optString("status")) {
                    "set" -> result.optJSONArray("changed")?.takeIf { it.length() > 0 }?.let { changed ->
                        Out.line("✔ DAVx⁵: $label: its ${(0 until changed.length()).joinToString(" and ") { changed.getString(it) }} as the config says")
                    }
                    "missing" -> { Out.line("✘ DAVx⁵: $label: gone from the phone since the plan"); failed++ }
                    else -> { Out.line("✘ DAVx⁵: $label: its user name and password could not be set (${result?.optString("error") ?: "no answer"})"); failed++ }
                }
            }
            val added = ArrayList<Planned>()
            for (p in written) {
                val result = results[addNames.getValue(p)]
                if (result?.optString("status") == "added") {
                    added += p
                } else {
                    Out.line("✘ DAVx⁵: ${label(p.wanted, null)}: Android did not add it (${result?.optString("error") ?: "no answer"})")
                    failed++
                }
            }
            // What was written for those Android did not add goes again (DAVx⁵ would remove it too, in time).
            (written - added.toSet()).takeIf { it.isNotEmpty() }?.let { gone -> runCatching { removeServices(gone.map { addNames.getValue(it) }) } }
            for (p in added) {
                val synced = results[addNames.getValue(p)]?.optInt("synced") == 1
                if (synced) started = true
                Out.line("✔ DAVx⁵: ${p.wanted.name}: added as \"${addNames.getValue(p)}\", with no taps: ${counts(services = discovered.getValue(p), preselect = preselect)}" +
                    if (synced) "; its first sync started" else "; its first sync could not be asked for (DAVx⁵ syncs it at its next start)")
            }
        }

        // The rest get DAVx⁵'s login screen, one at a time (the next build opens the next one's).
        toLogin.firstOrNull()?.let { p ->
            val w = p.wanted
            val label = label(w, null)
            val password = w.passwordFile?.let { secret(it) }
            val command = mutableListOf(
                "am", "start", "--user", "0", "-a", "android.intent.action.VIEW", "-d", display(w.url), "-n", LOGIN,
                "--es", "username", w.userName,
            )
            if (password != null) command += listOf("--es", "password", password)
            val (status, said) = Device.run(*command.toTypedArray())
            if (status == 0 && !said.contains("Error")) {
                started = true
                Out.line("⚠ DAVx⁵: $label: its login screen is open on the phone, filled in: tap Login, then Finish" +
                    (w.accountName?.let { " (named \"$it\")" } ?: ""))
                Notify.event(
                    "build", "warn", "DAVx⁵: tap Login, then Finish",
                    "Its login screen is open, filled in for $label (${display(w.url)})", key = "davx5",
                )
            } else {
                Out.line("✘ DAVx⁵: $label: its login screen could not be opened (${said.trim().lines().lastOrNull().orEmpty()})")
                failed++
            }
        }
        toLogin.drop(1).forEach { Out.line("  DAVx⁵: ${label(it.wanted, null)}: its login screen opens at the next build (one at a time)") }

        if (stopped && !started) wake(uid)
        return failed
    }

    /** Gives "preselect collections" back as it was before Nixup set it: services.davx5 left the config, or the module goes. */
    fun restore(): Int {
        if (!journalFile.isFile) return 0
        if (Device.versionCode(APP) == null) {
            journalFile.delete()
            return 0
        }
        val original = runCatching { JSONObject(journalFile.readText()) }.getOrNull()
            ?.let { if (it.isNull("preselect")) null else it.optInt("preselect") }
        val stopped = stop()
        val done = writePreference(original, journal = false)
        if (stopped) Device.uid(APP)?.let { wake(it) }
        if (!done) {
            Out.line("✘ DAVx⁵: \"preselect collections\" could not go back as before")
            return 1
        }
        journalFile.delete()
        Out.line("✔ DAVx⁵: \"preselect collections\" back as before")
        return 0
    }

    private fun wanted(spec: JSONObject): List<Wanted> {
        val list = spec.optJSONArray("accounts") ?: JSONArray()
        return (0 until list.length()).map { i ->
            val a = list.getJSONObject(i)
            Wanted(
                a.getString("name"), if (a.isNull("accountName")) null else a.getString("accountName"), a.getString("url"), a.getString("userName"),
                if (a.isNull("passwordFile")) null else a.getString("passwordFile"), a.optBoolean("automatic"),
            )
        }
    }

    /** An account as the build names it: by its attribute, with its name in DAVx⁵ when that is another. */
    private fun label(wanted: Wanted, found: Stored?): String =
        if (found != null && found.name != wanted.name) "${wanted.name} (DAVx⁵'s \"${found.name}\")" else wanted.name

    /** Whether two hosts are the same server's: the same, or one a subdomain of the other (dav.example.org, example.org). */
    private fun sameServer(a: String, b: String) = a == b || a.endsWith(".$b") || b.endsWith(".$a")

    private fun describe(preselect: Int) = when (preselect) {
        PRESELECT_ALL -> "all collections selected for syncing when DAVx⁵ finds them"
        PRESELECT_PERSONAL -> "the personal collections selected for syncing when DAVx⁵ finds them"
        else -> "none selected for syncing when DAVx⁵ finds them"
    }

    /** An address as it may be shown: without a user name and password in it. */
    private fun display(url: String): String =
        url.toHttpUrlOrNull()?.newBuilder()?.username("")?.password("")?.build()?.toString() ?: url

    /** The secret's text, without the line break a file ends with; null when it cannot be read. */
    private fun secret(path: String): String? = try {
        File(path).takeIf { it.isFile }?.readText()?.trimEnd('\n', '\r')
    } catch (e: IOException) {
        null
    }

    /** How many calendars and address books [services] have, and how many of them are synced with [preselect]. */
    private fun counts(services: List<Service>, preselect: Int): String {
        fun n(count: Int, what: String) = if (count == 1) "1 $what" else "$count ${what}s"
        val all = services.flatMap { it.collections }
        val calendars = all.filter { it.type != TYPE_ADDRESSBOOK }
        val books = all.filter { it.type == TYPE_ADDRESSBOOK }
        return "${n(calendars.count { it.selected(preselect) }, "calendar")} and ${n(books.count { it.selected(preselect) }, "address book")} to sync" +
            " (of ${calendars.size} and ${books.size} found)"
    }

    // --- DAVx⁵'s accounts in Android's account databases (read as root), and as DAVx⁵ itself.

    /**
     * DAVx⁵'s accounts, from Android's credential-encrypted account database (there after the first unlock): each
     * one's name, user name and password; from dumpsys, by their names alone, where that database cannot be read.
     */
    private fun stored(): List<Stored> = try {
        openAccounts().use { db ->
            db.rawQuery("SELECT _id, name, password FROM accounts WHERE type = ?", arrayOf(ACCOUNT_TYPE)).use { rows ->
                buildList {
                    while (rows.moveToNext()) {
                        val userName = db.rawQuery("SELECT value FROM extras WHERE accounts_id = ? AND key = ?", arrayOf(rows.getLong(0).toString(), KEY_USER_NAME))
                            .use { if (it.moveToFirst() && !it.isNull(0)) it.getString(0) else null }
                        add(Stored(rows.getString(1), userName, if (rows.isNull(2)) null else rows.getString(2), known = true))
                    }
                }
            }
        }
    } catch (e: Exception) {
        Regex("""Account \{name=(.+?), type=([^}]+)\}""").findAll(Device.run("dumpsys", "account").second)
            .filter { it.groupValues[2] == ACCOUNT_TYPE }.map { it.groupValues[1] }.distinct()
            .map { Stored(it, null, null, known = false) }.toList()
    }

    /**
     * The servers (their hosts) of DAVx⁵'s accounts, by account name: its services' principals and home sets, from a
     * copy of its database (DAVx⁵ may be using it, and SQLite would make the files it lacks as root); empty when it
     * cannot be read.
     */
    private fun servers(): Map<String, Set<String>> {
        if (!databaseFile.isFile) return emptyMap()
        val copy = File(data, "tmp/davx5-read")
        return try {
            copy.deleteRecursively()
            copy.mkdirs()
            for (suffix in listOf("", "-wal")) File(databaseFile.path + suffix).takeIf { it.isFile }?.copyTo(File(copy, databaseFile.name + suffix))
            sqliteDefaults()
            SQLiteDatabase.openDatabase(File(copy, databaseFile.name).path, null, SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS).use { db ->
                val servers = HashMap<String, MutableSet<String>>()
                val query = "SELECT s.accountName, s.principal FROM service s UNION ALL SELECT s.accountName, h.url FROM homeset h JOIN service s ON h.serviceId = s.id"
                db.rawQuery(query, null).use { rows ->
                    while (rows.moveToNext()) {
                        if (rows.isNull(0) || rows.isNull(1)) continue
                        val host = rows.getString(1).toHttpUrlOrNull()?.host ?: continue
                        servers.getOrPut(rows.getString(0)) { HashSet() } += host
                    }
                }
                servers
            }
        } catch (e: Exception) {
            emptyMap()
        } finally {
            copy.deleteRecursively()
        }
    }

    private fun openAccounts(): SQLiteDatabase {
        sqliteDefaults()
        return SQLiteDatabase.openDatabase(ACCOUNTS_CE, null, SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS)
    }

    /** Has the engine, run as DAVx⁵, set or add the accounts of [input]; each one's outcome by its name (empty when it could not run). */
    private fun asDavx5(uid: Int, input: JSONArray): Map<String, JSONObject> {
        val results = HashMap<String, JSONObject>()
        try {
            AppProcess.run(uid, listOf("davx5-accounts"), input.toString()) { line ->
                val json = if (line.startsWith("{")) runCatching { JSONObject(line) }.getOrNull() else null
                if (json != null) results[json.optString("name")] = json
                else if (line.isNotBlank()) Out.line("  $line")
            }
        } catch (e: Exception) {
            Out.line("✘ DAVx⁵: its accounts cannot be set as DAVx⁵ (${e.message})")
        }
        return results
    }

    /** Whether DAVx⁵ has a process running. */
    private fun running() = Device.run("pidof", APP).let { (status, said) -> status == 0 && said.isNotBlank() }

    /**
     * Ends DAVx⁵, so that its files can be written (it keeps them in memory, and would write them back): ended in the
     * background if it can be, else force-stopped. True when it was force-stopped: Android then runs none of its jobs
     * until it starts again (see [wake]).
     */
    private fun stop(): Boolean {
        if (!running()) return false
        Device.killInBackground(APP)
        if (!running()) return false
        Device.run("am", "force-stop", APP)
        return true
    }

    /**
     * Lets DAVx⁵ off its stopped state, which a force-stop leaves it in: Android runs none of its jobs and syncs until
     * it starts again (Android's package manager, as root).
     */
    private fun unstop() {
        runCatching {
            val packages = Class.forName("android.app.ActivityThread").getMethod("getPackageManager").invoke(null)!!
            packages.javaClass.getMethod("setPackageStoppedState", String::class.java, Boolean::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                .invoke(packages, APP, false, 0)
        }.onFailure { Out.line("  (DAVx⁵ could not be let off its stopped state: ${(it as? InvocationTargetException)?.targetException?.message ?: it.message})") }
    }

    /**
     * Starts DAVx⁵ again after a force-stop: let off its stopped state, a sync of each of its accounts (asked for as
     * DAVx⁵: Android takes a sync of an account only from an app that may see it) has Android start it, and it plans
     * its syncs again (WorkManager takes them up after a force-stop).
     */
    private fun wake(uid: Int) {
        unstop()
        val input = JSONArray()
        Regex("""Account \{name=(.+?), type=([^}]+)\}""").findAll(Device.run("dumpsys", "account").second)
            .map { it.groupValues[1] to it.groupValues[2] }.distinct().forEach { (name, type) ->
                val authority = when (type) {
                    ACCOUNT_TYPE -> CalendarContract.AUTHORITY
                    ADDRESS_BOOK_TYPE -> ContactsContract.AUTHORITY
                    else -> return@forEach
                }
                input.put(JSONObject().put("name", name).put("type", type).put("sync", JSONArray().put(authority)))
            }
        if (input.length() > 0) asDavx5(uid, input)
    }

    // --- DAVx⁵'s setting "preselect collections" (its shared preferences).

    private val prefsFile = File(APP_DATA, "shared_prefs/${APP}_preferences.xml")

    /** The preferences' text as DAVx⁵ reads it (a backup Android left from a write that broke off takes the file's place). */
    private fun prefsText(): String? = listOf(File(prefsFile.path + ".bak"), prefsFile).firstOrNull { it.isFile }?.readText()

    /** "preselect collections" now; null when it is not set (DAVx⁵ selects none then). */
    private fun preference(): Int? = prefsText()?.let { PRESELECT_ENTRY.find(it)?.groupValues?.get(1)?.toIntOrNull() }

    /**
     * Writes "preselect collections" ([value]; null: not set) into DAVx⁵'s preferences, which DAVx⁵ must not be running
     * to keep (see [stop]); with [journal], what it was is kept first, for [restore].
     */
    private fun writePreference(value: Int?, journal: Boolean): Boolean = try {
        if (journal && !journalFile.isFile) {
            journalFile.parentFile?.mkdirs()
            journalFile.writeText(JSONObject().put("preselect", preference() ?: JSONObject.NULL).toString())
        }
        val uid = Device.uid(APP) ?: throw IllegalStateException("DAVx⁵ is not installed")
        val label = appLabel()
        val dir = prefsFile.parentFile!!
        if (!dir.isDirectory) {
            dir.mkdirs()
            own(dir, uid, label, "771")
        }
        val text = prefsText() ?: "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n</map>\n"
        val entry = value?.let { "<int name=\"preselect_collections\" value=\"$it\" />" }
        val updated = when {
            PRESELECT_ENTRY.containsMatchIn(text) ->
                if (entry != null) PRESELECT_ENTRY.replace(text, entry) else PRESELECT_LINE.replace(text, "")
            entry == null -> text
            text.contains("<map />") -> text.replace("<map />", "<map>\n    $entry\n</map>")
            else -> text.replace("</map>", "    $entry\n</map>")
        }
        val partial = File(dir, ".${prefsFile.name}.nixup")
        partial.writeText(updated)
        own(partial, uid, label)
        if (!partial.renameTo(prefsFile)) throw IOException("could not put it in $prefsFile")
        File(prefsFile.path + ".bak").delete()
        true
    } catch (e: Exception) {
        Out.line("  (DAVx⁵'s preferences: ${e.javaClass.simpleName}: ${e.message})")
        false
    }

    /** The SELinux label of DAVx⁵'s data folder (with the app's categories), which its files take too. */
    private fun appLabel(): String = Device.run("stat", "-c", "%C", APP_DATA.path).second.trim()

    /** Makes [file], written here as root, DAVx⁵'s own, as if it had made it: its uid and group, [mode], its folder's label. */
    private fun own(file: File, uid: Int, label: String, mode: String = "660") {
        Os.chown(file.path, uid, uid)
        Os.chmod(file.path, mode.toInt(8))
        if (label.startsWith("u:")) Device.run("chcon", label, file.path)
    }

    /** The modes of the database's files now (services.db, -wal, -shm, -journal), to keep them as they were. */
    private fun databaseModes(): Map<String, Int> = databaseFile.parentFile?.listFiles { file -> file.name.startsWith(databaseFile.name) }
        ?.associate { it.name to (Os.stat(it.path).st_mode and "777".toInt(8)) }.orEmpty()

    /**
     * Makes the database's files DAVx⁵'s again after SQLite wrote them as root: each one's mode as it was ([modes]), or
     * for one SQLite made, DAVx⁵'s (the database rw-rw----, its WAL and the like rw-------).
     */
    private fun ownDatabase(uid: Int, label: String, modes: Map<String, Int>) {
        databaseFile.parentFile?.listFiles { file -> file.name.startsWith(databaseFile.name) }?.forEach { file ->
            val mode = modes[file.name] ?: if (file.name == databaseFile.name) "660".toInt(8) else "600".toInt(8)
            own(file, uid, label, Integer.toOctalString(mode))
        }
    }

    // --- DAVx⁵'s database (Room's: services.db), written for the accounts added with no taps.

    private val databaseFile = File(APP_DATA, "databases/services.db")

    /**
     * Why DAVx⁵'s database cannot be written here (null: it can): not of the version Nixup knows, or, where there is
     * none yet, a DAVx⁵ older than that version, which would take the one Nixup made for a newer one and drop it with all
     * of its accounts. With [header], from the file's header alone (the plan: nothing is opened).
     */
    private fun databaseProblem(header: Boolean): String? {
        if (!databaseFile.exists()) {
            val code = Device.versionCode(APP) ?: return null
            return if (code < MIN_VERSION_CODE) "DAVx⁵ ${Device.installed(APP)?.versionName} is older than 4.5.17, whose database Nixup writes" else null
        }
        if (!header) return null
        // SQLite's header: the user version (Room's version of the schema) at bytes 60 to 63.
        val version = try {
            databaseFile.inputStream().use { input ->
                val bytes = ByteArray(64)
                if (input.read(bytes) < 64) return null
                ((bytes[60].toInt() and 0xff) shl 24) or ((bytes[61].toInt() and 0xff) shl 16) or ((bytes[62].toInt() and 0xff) shl 8) or (bytes[63].toInt() and 0xff)
            }
        } catch (e: IOException) {
            return null
        }
        return if (version != SCHEMA_VERSION) "DAVx⁵'s database is of version $version, which Nixup does not write (it writes version $SCHEMA_VERSION, DAVx⁵ 4.5.17 to 4.5.21's)" else null
    }

    private fun openDatabase(create: Boolean): SQLiteDatabase {
        sqliteDefaults()
        var flags = SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS or SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING
        if (create) flags = flags or SQLiteDatabase.CREATE_IF_NECESSARY
        return SQLiteDatabase.openDatabase(databaseFile.path, null, flags)
    }

    /**
     * Writes each account's services (by its name) into DAVx⁵'s database, as its login and its first look at the
     * collections do: the services with their principals, the home sets, and the collections, selected for syncing as
     * [preselect] says. DAVx⁵ must be stopped. Returns why it was not written (see [databaseProblem]), or null when it was.
     */
    private fun writeDatabase(accounts: Map<String, List<Service>>, preselect: Int): String? {
        databaseProblem(header = false)?.let { return it }
        val uid = Device.uid(APP) ?: return "DAVx⁵ is not installed"
        val label = appLabel()
        val dir = databaseFile.parentFile!!
        val fresh = !databaseFile.exists()
        if (fresh && !dir.isDirectory) {
            dir.mkdirs()
            own(dir, uid, label, "771")
        }
        val modes = databaseModes()
        try {
            openDatabase(create = fresh).use { db ->
                if (fresh) {
                    // As Room makes it at DAVx⁵'s first start.
                    db.beginTransaction()
                    try {
                        SCHEMA.forEach { db.execSQL(it) }
                        db.version = SCHEMA_VERSION
                        db.setTransactionSuccessful()
                    } finally {
                        db.endTransaction()
                    }
                } else {
                    val identity = runCatching {
                        db.rawQuery("SELECT identity_hash FROM room_master_table WHERE id = 42", null).use { if (it.moveToFirst()) it.getString(0) else null }
                    }.getOrNull()
                    if (db.version != SCHEMA_VERSION || identity != SCHEMA_IDENTITY) {
                        return "DAVx⁵'s database is of version ${db.version}${if (db.version == SCHEMA_VERSION) " but not the one Nixup knows" else ""}, which Nixup does not write"
                    }
                }
                // (As Room has them: a service that goes takes its home sets and collections with it.)
                db.setForeignKeyConstraintsEnabled(true)
                db.beginTransaction()
                try {
                    for ((accountName, services) in accounts) {
                        // What is left of an account of that name that is gone (DAVx⁵ removes it in time) makes way.
                        db.delete("service", "accountName = ?", arrayOf(accountName))
                        for (service in services) insertService(db, accountName, service, preselect)
                    }
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
                db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
            }
        } finally {
            // Its files are DAVx⁵'s again (SQLite made those it lacked as root).
            ownDatabase(uid, label, modes)
        }
        return null
    }

    private fun insertService(db: SQLiteDatabase, accountName: String, service: Service, preselect: Int) {
        val serviceId = db.insertOrThrow("service", null, ContentValues().apply {
            put("accountName", accountName)
            put("type", service.type)
            put("principal", service.principal?.toString())
        })
        val homeSetIds = HashMap<HttpUrl, Long>()
        for (homeSet in service.homeSets) {
            homeSetIds[homeSet.url] = db.insertOrThrow("homeset", null, ContentValues().apply {
                put("serviceId", serviceId)
                put("personal", 1)
                put("url", homeSet.url.toString())
                put("privBind", if (homeSet.privBind) 1 else 0)
                put("displayName", homeSet.displayName)
            })
        }
        for (collection in service.collections) {
            db.insertOrThrow("collection", null, ContentValues().apply {
                put("serviceId", serviceId)
                put("homeSetId", collection.homeSet?.let { homeSetIds[it] })
                put("type", collection.type)
                put("url", collection.url.toString())
                put("privWriteContent", if (collection.privWriteContent) 1 else 0)
                put("privUnbind", if (collection.privUnbind) 1 else 0)
                put("forceReadOnly", 0)
                put("displayName", collection.displayName)
                put("description", collection.description)
                put("color", collection.color)
                put("timezoneId", collection.timezoneId)
                put("supportsVEVENT", collection.supportsVEVENT?.let { if (it) 1 else 0 })
                put("supportsVTODO", collection.supportsVTODO?.let { if (it) 1 else 0 })
                put("supportsVJOURNAL", collection.supportsVJOURNAL?.let { if (it) 1 else 0 })
                put("source", collection.source)
                put("sync", if (collection.selected(preselect)) 1 else 0)
                put("supportsWebPush", 0)
            })
        }
    }

    /** Takes the services written for [names] out again (Android did not add those accounts). */
    private fun removeServices(names: List<String>) {
        val uid = Device.uid(APP) ?: return
        val label = appLabel()
        val modes = databaseModes()
        try {
            openDatabase(create = false).use { db ->
                db.setForeignKeyConstraintsEnabled(true)
                for (name in names) db.delete("service", "accountName = ?", arrayOf(name))
                db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
            }
        } finally {
            ownDatabase(uid, label, modes)
        }
    }

    // --- What the server has: DAVx⁵'s service discovery, and its first look at the home sets' collections.

    /** A home set: where the server keeps the user's calendars or address books. */
    private class HomeSet(val url: HttpUrl) {
        var displayName: String? = null
        var privBind = true
    }

    /** A calendar, a calendar subscription (webcal) or an address book, as DAVx⁵'s database keeps it. */
    private class DavCollection(
        val url: HttpUrl, val type: String, val homeSet: HttpUrl?, val privWriteContent: Boolean, val privUnbind: Boolean,
        val displayName: String?, val description: String?, val color: Int?, val timezoneId: String?,
        val supportsVEVENT: Boolean?, val supportsVTODO: Boolean?, val supportsVJOURNAL: Boolean?, val source: String?,
    ) {
        /** Whether DAVx⁵ would select it for syncing on finding it, with "preselect collections" at [preselect]. */
        fun selected(preselect: Int): Boolean {
            val excluded = url.toString().contains(PRESELECT_EXCLUDED)
            return when (preselect) {
                PRESELECT_ALL -> !excluded
                // (Every home set found here is the user's own: a collection outside of them is not.)
                PRESELECT_PERSONAL -> homeSet != null && !excluded
                else -> false
            }
        }

    }

    /** What the server has of one service (CalDAV or CardDAV) for the user, and the user's email addresses it tells (CalDAV's). */
    private class Service(
        val type: String, val principal: HttpUrl?, val homeSets: List<HomeSet>, val collections: List<DavCollection>,
        val emails: List<String> = emptyList(),
    )

    /** CalDAV and CardDAV of [base]'s server for the user, as DAVx⁵ finds them (none: neither is there). */
    private fun discover(base: HttpUrl, userName: String, password: String): List<Service> {
        val dav = Dav(userName, password)
        return listOfNotNull(discover(dav, base, TYPE_CARDDAV), discover(dav, base, TYPE_CALDAV))
    }

    private fun discover(dav: Dav, base: HttpUrl, type: String): Service? {
        val calendars = type == TYPE_CALDAV
        val homeSetName = if (calendars) Name(CALDAV, "calendar-home-set") else Name(CARDDAV, "addressbook-home-set")
        var principal: HttpUrl? = null
        val homeSets = LinkedHashMap<HttpUrl, HomeSet>()
        val collections = LinkedHashMap<HttpUrl, DavCollection>()

        // The address given: a collection itself, a home set, or a resource that names the user's principal.
        val baseProperties = listOf(
            Name(DAV, "resourcetype"), Name(DAV, "displayname"), Name(DAV, "current-user-principal"),
            Name(DAV, "current-user-privilege-set"), homeSetName,
        ) + collectionProperties(type)
        try {
            for (response in dav.propfind(base, 0, baseProperties)) {
                response.hrefs(Name(DAV, "current-user-principal")).firstOrNull()?.let { principal = it }
                if (Name(DAV, "principal") in response.resourceTypes()) principal = response.url
                for (href in response.hrefs(homeSetName)) homeSets.getOrPut(href.withSlash()) { HomeSet(href.withSlash()) }
                collection(response, type, null)?.let { collections[it.url] = it }
            }
        } catch (e: Dav.Fatal) {
            throw e
        } catch (e: IOException) {
            // (As DAVx⁵: the well-known address may still say where it is.)
        }
        principal?.let { if (!dav.provides(it, type)) principal = null }
        // Else the well-known address (/.well-known/caldav, /.well-known/carddav), with its redirects.
        if (principal == null) {
            try {
                val wellKnown = base.resolve("/.well-known/${if (calendars) "caldav" else "carddav"}")!!
                principal = dav.propfind(wellKnown, 0, listOf(Name(DAV, "current-user-principal")))
                    .firstNotNullOfOrNull { it.hrefs(Name(DAV, "current-user-principal")).firstOrNull() }
                    ?.takeIf { dav.provides(it, type) }
            } catch (e: Dav.Fatal) {
                throw e
            } catch (e: IOException) {
                // none there
            }
        }
        // The principal's home sets (the user's own, as DAVx⁵ takes those of the current user's principal).
        principal?.let { url ->
            try {
                for (response in dav.propfind(url, 0, listOf(Name(DAV, "displayname"), Name(DAV, "resourcetype"), homeSetName))) {
                    for (href in response.hrefs(homeSetName)) homeSets.getOrPut(href.withSlash()) { HomeSet(href.withSlash()) }
                }
            } catch (e: Dav.Fatal) {
                throw e
            } catch (e: IOException) {
                // a principal without them
            }
        }
        // Each home set's collections.
        for (homeSet in homeSets.values.toList()) {
            try {
                for (response in dav.propfind(homeSet.url, 1, listOf(Name(DAV, "displayname"), Name(DAV, "current-user-privilege-set")) + collectionProperties(type))) {
                    if (response.url.withSlash() == homeSet.url) {
                        homeSet.displayName = response.text(Name(DAV, "displayname"))
                        homeSet.privBind = response.privileges()?.bind != false
                    }
                    collection(response, type, homeSet.url)?.let { collections[it.url] = it }
                }
            } catch (e: Dav.Fatal) {
                throw e
            } catch (e: Dav.Failed) {
                // One the user cannot reach goes, as DAVx⁵ removes it.
                if (e.code in listOf(403, 404, 410)) homeSets.remove(homeSet.url)
            } catch (e: IOException) {
                // (An answer that is no WebDAV: its collections are not known.)
            }
        }
        // The user's email addresses (calendar-user-address-set), after which DAVx⁵'s login names an account.
        val emails = ArrayList<String>()
        if (calendars) principal?.let { url ->
            try {
                for (response in dav.propfind(url, 0, listOf(Name(CALDAV, "calendar-user-address-set")))) {
                    response.properties[Name(CALDAV, "calendar-user-address-set")]?.children(DAV, "href")
                        ?.mapNotNull { it.textContent?.trim() }?.filter { it.startsWith("mailto:", ignoreCase = true) }
                        ?.forEach { emails += it.substring(7) }
                }
            } catch (e: Dav.Fatal) {
                throw e
            } catch (e: IOException) {
                // none told
            }
        }
        return if (principal != null || homeSets.isNotEmpty() || collections.isNotEmpty())
            Service(type, principal, homeSets.values.toList(), collections.values.toList(), emails)
        else null
    }

    /** The properties DAVx⁵ asks of a collection. */
    private fun collectionProperties(type: String): List<Name> = listOf(
        Name(DAV, "current-user-privilege-set"), Name(DAV, "displayname"), Name(DAV, "owner"), Name(DAV, "resourcetype"),
    ) + if (type == TYPE_CARDDAV) listOf(Name(CARDDAV, "addressbook-description"))
    else listOf(
        Name(ICAL, "calendar-color"), Name(CALDAV, "calendar-description"), Name(CALDAV, "calendar-timezone"),
        Name(CALDAV, "calendar-timezone-id"), Name(CALDAV, "supported-calendar-component-set"), Name(CALSERVER, "source"),
    )

    /** The collection [response] is, as DAVx⁵'s Collection.fromDavResponse makes it; null when it is none [type]'s service syncs. */
    private fun collection(response: Dav.Response, type: String, homeSet: HttpUrl?): DavCollection? {
        val types = response.resourceTypes()
        val kind = when {
            Name(CARDDAV, "addressbook") in types -> TYPE_ADDRESSBOOK
            Name(CALDAV, "calendar") in types -> TYPE_CALENDAR
            Name(CALSERVER, "subscribed") in types -> TYPE_WEBCAL
            else -> return null
        }
        val privileges = response.privileges()
        var description: String? = null
        var color: Int? = null
        var timezoneId: String? = null
        var events: Boolean? = null
        var tasks: Boolean? = null
        var journals: Boolean? = null
        var source: String? = null
        if (kind == TYPE_ADDRESSBOOK) {
            description = response.text(Name(CARDDAV, "addressbook-description"))
        } else {
            description = response.text(Name(CALDAV, "calendar-description"))
            color = response.text(Name(ICAL, "calendar-color"))?.let { parseColor(it) }
            timezoneId = response.text(Name(CALDAV, "calendar-timezone-id"))
                ?: response.text(Name(CALDAV, "calendar-timezone"))?.let { Regex("""(?m)^TZID[^:]*:(.+)$""").find(it)?.groupValues?.get(1)?.trim() }
            if (kind == TYPE_CALENDAR) {
                events = true
                tasks = true
                journals = true
                response.properties[Name(CALDAV, "supported-calendar-component-set")]?.let { set ->
                    val components = set.children(CALDAV, "comp").map { it.getAttribute("name").uppercase() }.toSet()
                    events = "VEVENT" in components
                    tasks = "VTODO" in components
                    journals = "VJOURNAL" in components
                }
            } else {
                source = response.properties[Name(CALSERVER, "source")]?.child(DAV, "href")?.textContent?.trim()
                    ?.replace(Regex("^webcal://"), "http://")?.replace(Regex("^webcals://"), "https://")
                    ?.toHttpUrlOrNull()?.toString()
                events = true
            }
        }
        val collection = DavCollection(
            url = response.url.withSlash(), type = kind, homeSet = homeSet,
            privWriteContent = privileges?.writeContent ?: true, privUnbind = privileges?.unbind ?: true,
            displayName = response.text(Name(DAV, "displayname")), description = description, color = color,
            timezoneId = timezoneId, supportsVEVENT = events, supportsVTODO = tasks, supportsVJOURNAL = journals,
            source = source,
        )
        // Usable for the service: its own kind, or a subscription with its source.
        val usable = (type == TYPE_CARDDAV && kind == TYPE_ADDRESSBOOK) || (type == TYPE_CALDAV && kind != TYPE_ADDRESSBOOK) ||
            (kind == TYPE_WEBCAL && source != null)
        return collection.takeIf { usable }
    }

    /** A calendar's color as Android takes it (ARGB), from #RRGGBB or #RRGGBBAA, as DAVx⁵ reads it. */
    private fun parseColor(text: String): Int? {
        val match = Regex("""#?(\p{XDigit}{6})(\p{XDigit}{2})?""").find(text) ?: return null
        val rgb = match.groupValues[1].toInt(16)
        val alpha = match.groupValues[2].takeIf { it.isNotEmpty() }?.toInt(16) ?: 0xff
        return (alpha shl 24) or rgb
    }

    /** A WebDAV property's name. */
    private data class Name(val namespace: String, val local: String)

    /** WebDAV requests to an account's server, with its user name and password (Basic authentication). */
    private class Dav(userName: String, password: String) {
        private val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(false).followSslRedirects(false).build()
        private val authorization = Credentials.basic(userName, password, Charsets.UTF_8)

        /** The server answered with an error ([code]). */
        class Failed(val code: Int, message: String) : IOException(message)

        /** What keeps every other request from going too: the looking stops at it. */
        open class Fatal(message: String, cause: Throwable? = null) : IOException(message, cause)

        /** The server refused the user name or password. */
        class Refused(url: HttpUrl) : Fatal("the server refused the user name or password (HTTP 401 at ${url.host})")

        /** The server cannot be reached (or did not answer in time). */
        class Unreachable(message: String, cause: Throwable) : Fatal(message, cause)

        /** One response of a multistatus: its address, and its properties that were found (status 2xx). */
        class Response(val url: HttpUrl, val requested: HttpUrl, val properties: Map<Name, Element>) {
            fun text(name: Name): String? = properties[name]?.textContent?.trim()?.ifEmpty { null }

            /** The addresses in a property of hrefs (current-user-principal, calendar-home-set, ...). */
            fun hrefs(name: Name): List<HttpUrl> = properties[name]?.children(DAV, "href")
                ?.mapNotNull { href -> href.textContent?.trim()?.ifEmpty { null }?.let { requested.resolve(it) } }.orEmpty()

            fun resourceTypes(): Set<Name> = properties[Name(DAV, "resourcetype")]?.elements()
                ?.map { Name(it.namespaceURI.orEmpty(), it.localName.orEmpty()) }?.toSet().orEmpty()

            class Privileges(val writeContent: Boolean, val bind: Boolean, val unbind: Boolean)

            /** current-user-privilege-set, as DAVx⁵ reads it; null when the server did not give it. */
            fun privileges(): Privileges? {
                val set = properties[Name(DAV, "current-user-privilege-set")] ?: return null
                var write = false
                var bind = false
                var unbind = false
                for (privilege in set.children(DAV, "privilege")) for (granted in privilege.elements()) {
                    if (granted.namespaceURI != DAV) continue
                    when (granted.localName) {
                        "all", "write" -> { write = true; bind = true; unbind = true }
                        "write-content" -> write = true
                        "bind" -> bind = true
                        "unbind" -> unbind = true
                    }
                }
                return Privileges(write, bind, unbind)
            }
        }

        private class Answer(val url: HttpUrl, val code: Int, val dav: String?, val body: String)

        /** [method] to [url], following its redirects as WebDAV clients do (the method and body kept). */
        private fun send(method: String, url: HttpUrl, depth: Int?, body: String?): Answer {
            var at = url
            repeat(6) {
                val request = Request.Builder().url(at)
                    .header("User-Agent", "Nixup")
                    .header("Authorization", authorization)
                    .apply { if (depth != null) header("Depth", depth.toString()) }
                    .method(method, body?.toRequestBody(XML))
                    .build()
                val (answer, location) = try {
                    http.newCall(request).execute().use { response ->
                        Answer(at, response.code, response.header("DAV"), response.body.string()) to response.header("Location")
                    }
                } catch (e: IOException) {
                    throw Unreachable("${at.host} cannot be reached (${e.javaClass.simpleName}: ${e.message})", e)
                }
                if (answer.code in REDIRECTS && location != null) {
                    at = at.resolve(location) ?: throw IOException("$at sends to $location, which is no address")
                    return@repeat
                }
                if (answer.code == 401) throw Refused(at)
                return answer
            }
            throw IOException("$url: too many redirects")
        }

        /** PROPFIND [url] for [properties] at [depth] (0: itself, 1: and what is in it). */
        fun propfind(url: HttpUrl, depth: Int, properties: List<Name>): List<Response> {
            val answer = send("PROPFIND", url, depth, propfindBody(properties))
            if (answer.code != 207) throw Failed(answer.code, "PROPFIND ${answer.url}: HTTP ${answer.code}")
            return multistatus(answer.url, answer.body)
        }

        /** Whether [url] has [type]'s service, by its DAV header (calendar-access, addressbook), as DAVx⁵ asks. */
        fun provides(url: HttpUrl, type: String): Boolean = try {
            val dav = send("OPTIONS", url, null, null).dav.orEmpty()
            if (type == TYPE_CALDAV) dav.contains("calendar-access") else dav.contains("addressbook")
        } catch (e: Fatal) {
            throw e
        } catch (e: IOException) {
            false
        }

        private fun propfindBody(properties: List<Name>): String = buildString {
            append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<d:propfind")
            for ((namespace, prefix) in PREFIXES) append(" xmlns:$prefix=\"$namespace\"")
            append("><d:prop>")
            for (name in properties.distinct()) append("<${PREFIXES.getValue(name.namespace)}:${name.local}/>")
            append("</d:prop></d:propfind>")
        }

        private fun multistatus(requested: HttpUrl, body: String): List<Response> {
            val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            // (A server's answer is data: no document type, no entities from elsewhere, where the parser knows them.)
            runCatching { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { factory.isExpandEntityReferences = false }
            val document = try {
                factory.newDocumentBuilder().parse(ByteArrayInputStream(body.toByteArray()))
            } catch (e: Exception) {
                throw IOException("PROPFIND $requested: not a WebDAV answer (${e.message})")
            }
            val responses = ArrayList<Response>()
            for (response in document.documentElement.children(DAV, "response")) {
                val url = response.child(DAV, "href")?.textContent?.trim()?.let { requested.resolve(it) } ?: continue
                if (!ok(response.child(DAV, "status")?.textContent)) continue
                val properties = HashMap<Name, Element>()
                for (propstat in response.children(DAV, "propstat")) {
                    if (!ok(propstat.child(DAV, "status")?.textContent)) continue
                    val prop = propstat.child(DAV, "prop") ?: continue
                    for (property in prop.elements()) properties[Name(property.namespaceURI.orEmpty(), property.localName.orEmpty())] = property
                }
                responses += Response(url, requested, properties)
            }
            return responses
        }

        /** Whether a status line (HTTP/1.1 200 OK) is a success; none is. */
        private fun ok(status: String?): Boolean =
            status == null || Regex("""\s(\d{3})(\s|$)""").find(status)?.groupValues?.get(1)?.toIntOrNull()?.let { it in 200..299 } != false

        companion object {
            private val XML = "application/xml; charset=utf-8".toMediaType()
            private val REDIRECTS = setOf(301, 302, 303, 307, 308)
            private val PREFIXES = mapOf(DAV to "d", CALDAV to "c", CARDDAV to "card", ICAL to "i", CALSERVER to "cs")
        }
    }

    /** Android's account service, through its binder (no context here), called as the app [packageName] (its uid's). */
    private class AccountService(private val packageName: String) {
        private val service: Any = run {
            val binder = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java).invoke(null, "account") as IBinder?
                ?: throw IllegalStateException("Android's account service is not there")
            Class.forName("android.accounts.IAccountManager\$Stub").getMethod("asInterface", IBinder::class.java).invoke(null, binder)
                ?: throw IllegalStateException("Android's account service cannot be reached")
        }

        private fun method(name: String, vararg types: Class<*>): Method? =
            service.javaClass.methods.firstOrNull { it.name == name && it.parameterTypes.contentEquals(types) }

        private fun needed(name: String, vararg types: Class<*>): Method =
            method(name, *types) ?: throw IllegalStateException("this Android's account service has no $name that Nixup knows")

        fun names(type: String): List<String> =
            (needed("getAccountsAsUser", String::class.java, Int::class.javaPrimitiveType!!, String::class.java).invoke(service, type, 0, packageName) as Array<*>?)
                ?.filterIsInstance<Account>()?.map { it.name }.orEmpty()

        fun password(account: Account) = needed("getPassword", Account::class.java).invoke(service, account) as String?

        fun setPassword(account: Account, password: String) {
            needed("setPassword", Account::class.java, String::class.java).invoke(service, account, password)
        }

        fun userData(account: Account, key: String) = needed("getUserData", Account::class.java, String::class.java).invoke(service, account, key) as String?

        fun setUserData(account: Account, key: String, value: String) {
            needed("setUserData", Account::class.java, String::class.java, String::class.java).invoke(service, account, key, value)
        }

        /** Adds [account] with [password] and [userData], as DAVx⁵'s login does; false when Android does not. */
        fun add(account: Account, password: String?, userData: JSONObject): Boolean {
            val extras = Bundle().apply { for (key in userData.keys()) putString(key, userData.getString(key)) }
            // Android 12 and later name the caller's package too.
            val added = method("addAccountExplicitly", Account::class.java, String::class.java, Bundle::class.java, String::class.java)
                ?.invoke(service, account, password, extras, packageName)
                ?: needed("addAccountExplicitly", Account::class.java, String::class.java, Bundle::class.java).invoke(service, account, password, extras)
            if (added != true) return false
            // Android loses the user data an account is added with at times: set again, as DAVx⁵ does.
            for (key in userData.keys()) setUserData(account, key, userData.getString(key))
            return true
        }
    }

    companion object {
        const val APP = "at.bitfire.davdroid"
        private const val ACCOUNT_TYPE = "bitfire.at.davdroid"
        private const val ADDRESS_BOOK_TYPE = "at.bitfire.davdroid.address_book"
        private const val LOGIN = "$APP/.ui.setup.LoginActivity"
        private val APP_DATA = File("/data/data/$APP")
        private const val ACCOUNTS_CE = "/data/system_ce/0/accounts_ce.db"

        // DAVx⁵'s account settings (Android's user data of its accounts).
        private const val KEY_SETTINGS_VERSION = "version"
        private const val KEY_USER_NAME = "user_name"
        /**
         * The settings' version an account is added with: before 19, whose update plans the account's syncs (as its login
         * does), so DAVx⁵ runs it, and those after (20, 21), at the account's first sync. DAVx⁵ 4.5.17 to 4.5.21 are at 21.
         */
        private const val SETTINGS_VERSION = "18"

        // "preselect collections": which collections DAVx⁵ selects for syncing when it finds them. (DAVx⁵'s
        // "personal" is never matched by DAVx⁵ 4.5 itself: it asks for a collection's home set before setting it.)
        const val PRESELECT_NONE = 0
        const val PRESELECT_ALL = 1
        const val PRESELECT_PERSONAL = 2
        /** The collections it never selects by itself (its default: Nextcloud's "Recently contacted"). */
        private const val PRESELECT_EXCLUDED = "/z-app-generated--contactsinteraction--recent/"
        private val PRESELECT_ENTRY = Regex("""<int\s+name="preselect_collections"\s+value="(-?\d+)"\s*/>""")
        private val PRESELECT_LINE = Regex("""\n?[ \t]*<int\s+name="preselect_collections"\s+value="-?\d+"\s*/>""")

        // Its services and collections, as its database names them.
        private const val TYPE_CALDAV = "caldav"
        private const val TYPE_CARDDAV = "carddav"
        private const val TYPE_ADDRESSBOOK = "ADDRESS_BOOK"
        private const val TYPE_CALENDAR = "CALENDAR"
        private const val TYPE_WEBCAL = "WEBCAL"

        private const val DAV = "DAV:"
        private const val CALDAV = "urn:ietf:params:xml:ns:caldav"
        private const val CARDDAV = "urn:ietf:params:xml:ns:carddav"
        private const val ICAL = "http://apple.com/ns/ical/"
        private const val CALSERVER = "http://calendarserver.org/ns/"

        /**
         * The database Nixup writes: Room's version 20 of DAVx⁵'s (4.5.17 to 4.5.21; 4.5.22's betas are at 21), its
         * identity, and the statements Room makes it with (core/schemas/at.bitfire.davdroid.db.AppDatabase/20.json).
         */
        private const val SCHEMA_VERSION = 20
        private const val SCHEMA_IDENTITY = "41a7f609a1e8b35b5933f413c60ac992"
        /** DAVx⁵ 4.5.17, the first at version 20: an older one would drop a database of 20, and its accounts with it. */
        private const val MIN_VERSION_CODE = 405170000L
        private val SCHEMA = listOf(
            "CREATE TABLE IF NOT EXISTS `account_setting` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `accountId` INTEGER NOT NULL, `key` TEXT NOT NULL, `value` TEXT, `sensitiveValue` TEXT, FOREIGN KEY(`accountId`) REFERENCES `account`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_account_setting_accountId_key` ON `account_setting` (`accountId`, `key`)",
            "CREATE TABLE IF NOT EXISTS `service` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `accountName` TEXT NOT NULL, `type` TEXT NOT NULL, `principal` TEXT)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_service_accountName_type` ON `service` (`accountName`, `type`)",
            "CREATE TABLE IF NOT EXISTS `account` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_account_name` ON `account` (`name`)",
            "CREATE TABLE IF NOT EXISTS `homeset` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `serviceId` INTEGER NOT NULL, `personal` INTEGER NOT NULL, `url` TEXT NOT NULL, `privBind` INTEGER NOT NULL, `displayName` TEXT, FOREIGN KEY(`serviceId`) REFERENCES `service`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_homeset_serviceId_url` ON `homeset` (`serviceId`, `url`)",
            "CREATE TABLE IF NOT EXISTS `collection` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `serviceId` INTEGER NOT NULL, `homeSetId` INTEGER, `ownerId` INTEGER, `type` TEXT NOT NULL, `url` TEXT NOT NULL, `privWriteContent` INTEGER NOT NULL, `privUnbind` INTEGER NOT NULL, `forceReadOnly` INTEGER NOT NULL, `displayName` TEXT, `description` TEXT, `color` INTEGER, `timezoneId` TEXT, `supportsVEVENT` INTEGER, `supportsVTODO` INTEGER, `supportsVJOURNAL` INTEGER, `source` TEXT, `sync` INTEGER NOT NULL, `pushTopic` TEXT, `supportsWebPush` INTEGER NOT NULL DEFAULT 0, `pushVapidKey` TEXT, `pushSubscription` TEXT, `pushRegisteredEndpoint` TEXT, `pushSubscriptionExpires` INTEGER, `pushSubscriptionCreated` INTEGER, FOREIGN KEY(`serviceId`) REFERENCES `service`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`homeSetId`) REFERENCES `homeset`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL , FOREIGN KEY(`ownerId`) REFERENCES `principal`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL )",
            "CREATE INDEX IF NOT EXISTS `index_collection_serviceId_type` ON `collection` (`serviceId`, `type`)",
            "CREATE INDEX IF NOT EXISTS `index_collection_homeSetId_type` ON `collection` (`homeSetId`, `type`)",
            "CREATE INDEX IF NOT EXISTS `index_collection_ownerId_type` ON `collection` (`ownerId`, `type`)",
            "CREATE INDEX IF NOT EXISTS `index_collection_pushTopic_type` ON `collection` (`pushTopic`, `type`)",
            "CREATE INDEX IF NOT EXISTS `index_collection_url` ON `collection` (`url`)",
            "CREATE TABLE IF NOT EXISTS `principal` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `serviceId` INTEGER NOT NULL, `url` TEXT NOT NULL, `displayName` TEXT, FOREIGN KEY(`serviceId`) REFERENCES `service`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_principal_serviceId_url` ON `principal` (`serviceId`, `url`)",
            "CREATE TABLE IF NOT EXISTS `syncstats` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `collectionId` INTEGER NOT NULL, `dataType` TEXT NOT NULL, `lastSync` INTEGER NOT NULL, FOREIGN KEY(`collectionId`) REFERENCES `collection`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_syncstats_collectionId_dataType` ON `syncstats` (`collectionId`, `dataType`)",
            "CREATE TABLE IF NOT EXISTS `webdav_document` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `mountId` INTEGER NOT NULL, `parentId` INTEGER, `name` TEXT NOT NULL, `isDirectory` INTEGER NOT NULL, `displayName` TEXT, `mimeType` TEXT, `eTag` TEXT, `lastModified` INTEGER, `size` INTEGER, `mayBind` INTEGER, `mayUnbind` INTEGER, `mayWriteContent` INTEGER, `quotaAvailable` INTEGER, `quotaUsed` INTEGER, FOREIGN KEY(`mountId`) REFERENCES `webdav_mount`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`parentId`) REFERENCES `webdav_document`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_webdav_document_mountId_parentId_name` ON `webdav_document` (`mountId`, `parentId`, `name`)",
            "CREATE INDEX IF NOT EXISTS `index_webdav_document_parentId` ON `webdav_document` (`parentId`)",
            "CREATE TABLE IF NOT EXISTS `webdav_mount` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `url` TEXT NOT NULL)",
            "CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)",
            "INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '41a7f609a1e8b35b5933f413c60ac992')",
        )

        /**
         * Asks Android for a sync of [account]'s [authority] now, in the engine run as DAVx⁵: Android's sync service
         * takes it from an app that may see the account (and drops root's), and starts DAVx⁵ for it.
         */
        private fun requestSync(account: Account, authority: String) {
            val extras = Bundle().apply {
                putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
                putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
            }
            val binder = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java).invoke(null, "content") as IBinder?
            val service = binder?.let { Class.forName("android.content.IContentService\$Stub").getMethod("asInterface", IBinder::class.java).invoke(null, it) }
            // Android 12 and later name the caller's package too (this process has none of its own).
            val named = service?.javaClass?.methods?.firstOrNull {
                it.name == "requestSync" && it.parameterTypes.contentEquals(arrayOf(Account::class.java, String::class.java, Bundle::class.java, String::class.java))
            }
            if (named != null) named.invoke(service, account, authority, extras, APP)
            else ContentResolver.requestSync(account, authority, extras)
        }

        /**
         * Android's SQLite first reads a setting of its own through the process's application, which an engine with a
         * system context (see Device) has but the settings provider refuses: its default, set first.
         */
        private fun sqliteDefaults() {
            runCatching { Class.forName("android.database.sqlite.SQLiteCompatibilityWalFlags").getMethod("init", String::class.java).invoke(null, null) }
        }

        private fun HttpUrl.withSlash(): HttpUrl = if (encodedPath.endsWith("/")) this else newBuilder().addPathSegment("").build()

        private fun Element.elements(): List<Element> = (0 until childNodes.length).mapNotNull { childNodes.item(it) as? Element }
        private fun Element.children(namespace: String, name: String) = elements().filter { it.namespaceURI == namespace && it.localName == name }
        private fun Element.child(namespace: String, name: String) = children(namespace, name).firstOrNull()

        /**
         * In the engine run as DAVx⁵ (davx5-accounts, see AppProcess): through Android's account service, which takes
         * DAVx⁵'s own uid for DAVx⁵'s accounts, sets the user name and password of each account it reads (a JSON array:
         * name, userName, password), or adds it (with "add": its password and user data, as DAVx⁵'s login adds one).
         * Writes a line of JSON for each: its name, and its status (set, with what changed; added; missing; failed, with
         * the error). Exit status: how many failed.
         */
        fun asApp(): Int {
            val list = JSONArray(System.`in`.bufferedReader().readText())
            val accounts = AccountService(APP)
            var failed = 0
            for (i in 0 until list.length()) {
                val item = list.getJSONObject(i)
                val name = item.getString("name")
                val result = JSONObject().put("name", name)
                try {
                    val account = Account(name, item.optString("type").ifEmpty { ACCOUNT_TYPE })
                    val password = if (item.has("password")) item.getString("password") else null
                    when {
                        item.optBoolean("add") -> {
                            if (accounts.add(account, password, item.optJSONObject("userData") ?: JSONObject())) result.put("status", "added")
                            else {
                                result.put("status", "failed").put("error", "one of that name is there already?")
                                failed++
                            }
                        }
                        name !in accounts.names(account.type) -> result.put("status", "missing")
                        else -> {
                            val changed = JSONArray()
                            val userName = item.optString("userName").ifEmpty { null }
                            if (userName != null && accounts.userData(account, KEY_USER_NAME) != userName) {
                                accounts.setUserData(account, KEY_USER_NAME, userName)
                                changed.put("user name")
                            }
                            if (password != null && accounts.password(account) != password) {
                                accounts.setPassword(account, password)
                                changed.put("password")
                            }
                            result.put("status", "set").put("changed", changed)
                        }
                    }
                    // The syncs asked for, now that it is there: as DAVx⁵, which may see its accounts.
                    val syncs = item.optJSONArray("sync")
                    if (syncs != null && result.optString("status") in listOf("added", "set")) {
                        for (j in 0 until syncs.length()) requestSync(account, syncs.getString(j))
                        result.put("synced", 1)
                    }
                } catch (e: Exception) {
                    val cause = (e as? InvocationTargetException)?.targetException ?: e
                    result.put("status", "failed").put("error", "${cause.javaClass.simpleName}: ${cause.message}")
                    failed++
                }
                println(result.toString())
            }
            System.out.flush()
            return failed
        }
    }
}
