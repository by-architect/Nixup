package dev.nixup.manager.engine

import android.accounts.Account
import android.content.ContentResolver
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.provider.CalendarContract
import android.provider.ContactsContract
import dev.nixup.manager.dav.Dav
import dev.nixup.manager.dav.ProductIds
import dev.nixup.manager.dav.network.HttpClientBuilder
import dev.nixup.manager.dav.servicedetection.DavResourceFinder
import dev.nixup.manager.dav.settings.Credentials
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.net.URI

/**
 * The calendars and contacts sync (services.caldav, nix/caldav.nix), set at each build after the app settings: each
 * CalDAV/CardDAV account of the config is an Android account of Nixup's own app, which syncs it itself with DAVx⁵'s
 * sync (the app's dav package): the server's calendars into Android's calendar, its address books into the contacts,
 * its task lists into the tasks app. No DAVx⁵ app is used.
 *  - An account of the config that is not on the phone is added, named as DAVx⁵'s login names one (the email address the
 *    server has for the user, else the user name) unless the config names it; the server is asked first, so a refused
 *    password or an address without CalDAV and CardDAV stops it with a reason. Its settings (the config's, as JSON) are in
 *    its user data, its password its password, and its first sync is asked for.
 *  - One there gets the config's settings and password again when they changed, and a sync.
 *  - One that left the config (or the whole of services.caldav) is removed, and Android removes its calendars, contacts
 *    and tasks with it (they are the server's). Changes made on the phone and not synced yet would go with them: they are
 *    synced first, the build waiting for it; an account whose server cannot take them stays, for a later build.
 * Only Nixup's app may add, set and remove the accounts of its type (Android checks the caller's uid and signing key,
 * which root has not), so that part runs as the app (see AppProcess and [asApp]).
 */
class Caldav(private val data: File) {

    /** An account of the config ([name]: its attribute), and its settings for the app (JSON). */
    private class Wanted(
        val name: String, val accountName: String?, val url: String, val userName: String, val passwordFile: String?,
        val config: JSONObject,
    )

    /** One of Nixup's accounts on the phone, with what the build gave it when they could be read ([known]). */
    private class Stored(
        val name: String, val password: String?, val nixupName: String?, val config: JSONObject?, val known: Boolean,
    )

    /** What the build does with an account of the config: add it (none [found]), or set its [changes]. */
    private class Planned(val wanted: Wanted, val found: Stored?, val changes: List<String>)

    private var spec: JSONObject? = null
    private var planned: List<Planned> = emptyList()
    private var removing: List<Stored> = emptyList()
    /** The tasks app the tasks sync with (its provider's authority), if one is installed. */
    private var tasksAuthority: String? = null
    private var tasksPermissions: List<String> = emptyList()

    /** Whether the plan changes anything. */
    val changes: Boolean get() = removing.isNotEmpty() || tasksPermissions.isNotEmpty() || planned.any { it.found == null || it.changes.isNotEmpty() }

    /**
     * Plans the profile's android.caldav ([spec]; null without services.caldav). [changingSecrets]: the secrets' files
     * this build changes (a password among them); [installing]: the apps it installs (a tasks app among them).
     */
    fun plan(spec: JSONObject?, changingSecrets: List<String>, installing: Set<String>, problems: MutableList<String>) {
        this.spec = spec
        val stored = stored()
        val lines = ArrayList<String>()
        if (spec != null) {
            if (Dav.ACCOUNT_TYPE !in Accounts.types(APP)) {
                problems += "services.caldav: Nixup's app on the phone has no calendar and contacts sync (an app older than this " +
                    "module?): install the module again, or restart the phone"
                return
            }
            // The tasks app the tasks sync with (one installed, or by this build): in each account's settings, so that one
            // installed later sets their syncs at that build.
            tasksAuthority = tasksProvider(spec.optJSONObject("settings")?.optString("tasksApp")?.ifEmpty { null }, installing)
            val wanted = wanted(spec)
            wanted.forEach { it.config.put("tasksAuthority", tasksAuthority ?: JSONObject.NULL) }
            val taken = HashSet<String>()
            planned = wanted.map { w ->
                // By the config's name for it; else, one of that name the build has not named yet.
                val found = stored.firstOrNull { it.nixupName == w.name }
                    ?: w.accountName?.let { name -> stored.firstOrNull { it.name == name && it.nixupName == null } }
                // Named otherwise now: it goes, and one of the new name comes.
                val renamed = found != null && w.accountName != null && found.name != w.accountName
                val keep = found?.takeIf { !renamed && it.name !in taken }
                keep?.let { taken += it.name }
                val changes = if (keep == null) emptyList() else buildList {
                    if (!keep.known) add("settings")
                    else {
                        if (keep.nixupName != w.name || keep.config == null || !same(keep.config, w.config)) add("settings")
                        if (w.passwordFile != null && (w.passwordFile in changingSecrets || secret(w.passwordFile) != keep.password)) add("password")
                    }
                }
                Planned(w, keep, changes)
            }
            for (p in planned) {
                val w = p.wanted
                lines += when {
                    p.found == null -> "  ${w.name}: to add${w.accountName?.let { " as \"$it\"" } ?: ""} (${display(w.url)})"
                    p.changes.isNotEmpty() -> "  ${label(w, p.found)}: its ${p.changes.joinToString(" and ")}"
                    else -> "  ${label(w, p.found)}: as the config says"
                }
            }
            val packageInfo = if (tasksAuthority != null) Device.run("dumpsys", "package", APP).second else ""
            tasksPermissions = TASKS.filter { it.authority == tasksAuthority }.flatMap { it.permissions }
                .filter { !packageInfo.contains("$it: granted=true") }
            if (tasksPermissions.isNotEmpty())
                lines += "  Tasks: Nixup's app may use ${TASKS.first { it.authority == tasksAuthority }.label}'s tasks"
        }
        val kept = planned.mapNotNull { it.found?.name }.toSet()
        removing = stored.filter { it.name !in kept }
        for (s in removing) {
            lines += "  ${label(s)}: to remove" +
                if (spec == null) " (services.caldav left the config)" else " (it left services.caldav.accounts)"
        }
        // DAVx⁵ syncing the same servers would show each calendar and address book twice.
        if (spec != null && planned.isNotEmpty() && Device.versionCode(DAVX5) != null && davx5Accounts() > 0)
            lines += "  ⚠ DAVx⁵ is on the phone with accounts of its own: their calendars and contacts show beside Nixup's " +
                "(twice, for the same servers). Nixup syncs without it now: take DAVx⁵ out of android.packages, or its accounts out of it"
        if (lines.isNotEmpty()) {
            Out.line("Calendars and contacts (services.caldav):")
            lines.forEach { Out.line(it) }
        }
    }

    /** The accounts that are to go but stay this build (their changes are not on the server yet). */
    private var staying: List<Stored> = emptyList()

    /**
     * Removes the accounts that left the config, before the app settings (which take Nixup's calendar and contacts
     * permissions back with services.caldav). One with changes made on the phone that are not on its server yet syncs
     * first, as its next sync would, and the build waits for it (Nixup's app has the permissions for the time of it); one
     * whose sync does not take them stays, for a later build. Returns how many things did not take.
     */
    fun applyRemovals(): Int {
        if (removing.isEmpty()) return 0
        val uid = Device.uid(APP)
        if (uid == null) {
            Out.line("✘ Calendars and contacts: Nixup's app is not on the phone")
            return 1
        }
        var failed = 0
        val books = addressBooks()
        val unsynced = removing.filter { unsynced(it, books[it.name].orEmpty()) > 0 }
        if (unsynced.isNotEmpty()) {
            Out.line("  Calendars and contacts: syncing the changes made on the phone of ${unsynced.joinToString(", ") { label(it) }} first")
            flush(uid, unsynced)
        }
        val input = JSONArray()
        val gone = ArrayList<Stored>()
        val staying = ArrayList<Stored>()
        for (s in removing) {
            val pending = if (s in unsynced) unsynced(s, books[s.name].orEmpty()) else 0
            if (pending > 0) {
                Out.line("⚠ Calendars and contacts: ${label(s)}: ${count(pending, "change")} made on the phone ${if (pending == 1) "is" else "are"} not on its server yet " +
                    "(it cannot be reached?): the account stays until a later build syncs ${if (pending == 1) "it" else "them"} (Android's account settings can remove it, and ${if (pending == 1) "it" else "them"} with it)")
                staying += s
            } else {
                input.put(JSONObject().put("op", "remove").put("name", s.name))
                gone += s
            }
        }
        this.staying = staying
        val results = if (input.length() > 0) asApp(uid, input) else emptyMap()
        for (s in gone) {
            val result = results[s.name]
            when (result?.optString("status")) {
                "removed", "missing" -> Out.line("✔ Calendars and contacts: ${label(s)} removed, with its calendars and contacts on the phone (the server keeps them)")
                else -> { Out.line("✘ Calendars and contacts: ${label(s)} could not be removed (${result?.optString("error") ?: "no answer"})"); failed++ }
            }
        }
        // Without services.caldav, Nixup's app needs no tasks app's tasks either (the calendar and contacts are the app
        // settings').
        if (spec == null && staying.isEmpty()) {
            val packageInfo = Device.run("dumpsys", "package", APP).second
            for (permission in TASKS.flatMap { it.permissions }.filter { packageInfo.contains("$it: granted=true") })
                Device.run("pm", "revoke", "--user", "0", APP, permission)
        }
        return failed
    }

    /**
     * What account [name] has on the phone after its first sync: its calendars (and events), address books (and contacts)
     * and task lists (and tasks), as Android's providers have them.
     */
    private fun firstSync(name: String): String {
        fun rows(uri: String, where: String): Int {
            val (status, said) = Device.run("content", "query", "--uri", uri, "--projection", "_id", "--where", where)
            return if (status == 0) said.lines().count { it.startsWith("Row:") } else 0
        }
        fun quoted(text: String) = "'" + text.replace("'", "''") + "'"
        val account = "account_name=${quoted(name)} AND account_type=${quoted(Dav.ACCOUNT_TYPE)}"
        val parts = ArrayList<String>()
        val calendars = rows("content://com.android.calendar/calendars", account)
        if (calendars > 0) parts += "${count(calendars, "calendar")} (${count(rows("content://com.android.calendar/events", "$account AND deleted=0"), "event")})"
        val books = addressBooks()[name].orEmpty()
        if (books.isNotEmpty()) parts += "${count(books.size, "address book")} (${count(rows("content://com.android.contacts/raw_contacts",
            "deleted=0 AND account_type=${quoted(Dav.ACCOUNT_TYPE_ADDRESS_BOOK)} AND account_name IN (${books.joinToString(",") { quoted(it) }})"), "contact")})"
        TASKS.firstOrNull { it.authority == tasksAuthority && it.authority != JTX && Device.versionCode(it.app) != null }?.let { tasks ->
            val lists = rows("content://${tasks.authority}/tasklists", account)
            if (lists > 0) parts += "${count(lists, "task list")} (${count(rows("content://${tasks.authority}/tasks", account), "task")}, in ${tasks.label})"
        }
        return if (parts.isEmpty()) "its first sync started (nothing synced yet: it goes on in the background)"
        else "synced: " + parts.joinToString(", ")
    }

    /** An account on the phone as the build names it: by the config's name for it, with its own when that is another. */
    private fun label(s: Stored) = s.nixupName?.let { if (it != s.name) "$it (\"${s.name}\")" else it } ?: "\"${s.name}\""

    /**
     * Syncs [accounts]' changes made on the phone to their servers now: asks Nixup's app for their syncs, has Android run
     * them at once, and waits for them (2 minutes at the most). The calendar, contacts and tasks permissions they need
     * that the app has not (any more) are given for the time of it.
     */
    private fun flush(uid: Int, accounts: List<Stored>) {
        val packageInfo = Device.run("dumpsys", "package", APP).second
        val needed = listOf("android.permission.READ_CALENDAR", "android.permission.WRITE_CALENDAR", "android.permission.READ_CONTACTS", "android.permission.WRITE_CONTACTS") +
            TASKS.filter { Device.versionCode(it.app) != null }.flatMap { it.permissions }
        val given = needed.filter { !packageInfo.contains("$it: granted=true") && Device.run("pm", "grant", "--user", "0", APP, it).first == 0 }
        try {
            val authorities = JSONArray(listOf(CalendarContract.AUTHORITY) + TASKS.filter { Device.versionCode(it.app) != null }.map { it.authority })
            asApp(uid, JSONArray(accounts.map { JSONObject().put("op", "sync").put("name", it.name).put("authorities", authorities).put("books", true) }))
            runSyncs(120_000)
        } finally {
            for (permission in given) Device.run("pm", "revoke", "--user", "0", APP, permission)
        }
    }

    /**
     * Has Android run the syncs of Nixup's accounts that wait (it may hold them for minutes) at once, and waits until none
     * runs nor waits, [timeout] ms at the most. (Android's sync jobs are the system's own, which cmd jobscheduler runs.)
     */
    private fun runSyncs(timeout: Long) {
        val start = System.currentTimeMillis()
        val deadline = start + timeout
        val forced = HashSet<String>()
        var waiting = emptySet<String>()
        do {
            Thread.sleep(1500)
            val content = Device.run("dumpsys", "content").second
            val active = content.substringAfter("Active Syncs:", "").substringBefore("Pending Syncs:")
            // (One that runs is listed as waiting too: run again, it would be stopped.)
            val running = Regex("""JobId=(\d+)""").findAll(active).map { it.groupValues[1] }.toSet()
            val pending = content.substringAfter("Pending Syncs:", "").substringBefore("Periodic Syncs:").lines()
                .filter { it.contains(Dav.ACCOUNT_TYPE) && !it.contains("PERIODIC") }
                .mapNotNull { Regex("""^JobId=(\d+)""").find(it.trim())?.groupValues?.get(1) }
            // (Android takes a moment to queue the syncs asked for, longer while the phone starts.)
            if (pending.isEmpty() && !active.contains(Dav.ACCOUNT_TYPE) && System.currentTimeMillis() - start > 6_000) return
            // (One Android has just put there it may be starting itself: those waiting since the last look.)
            for (id in pending.filter { it in waiting } - forced - running) {
                Device.run("cmd", "jobscheduler", "run", "-f", "android", id)
                forced += id
            }
            waiting = pending.toSet()
        } while (System.currentTimeMillis() < deadline)
    }

    /** Applies the plan (but its removals: [applyRemovals]); returns how many things did not take. */
    fun apply(): Int {
        if (spec == null) return 0
        val uid = Device.uid(APP)
        if (uid == null) {
            Out.line("✘ Calendars and contacts: Nixup's app is not on the phone")
            return 1
        }
        var failed = 0

        // The tasks app's permissions, before its first sync (Android's own, calendar and contacts, are the app settings').
        for (permission in tasksPermissions) {
            if (Device.run("pm", "grant", "--user", "0", APP, permission).first != 0) {
                Out.line("✘ Calendars and contacts: Nixup's app could not be given $permission")
                failed++
            }
        }

        val input = JSONArray()
        val names = HashMap<Planned, String>()
        val found = HashMap<Planned, Found?>()
        val taken = HashSet(staying.map { it.name } + planned.mapNotNull { it.found?.name })
        for (p in planned) {
            val w = p.wanted
            if (p.found != null && p.changes.isEmpty()) continue
            val password = w.passwordFile?.let { secret(it) }
            if (w.passwordFile != null && password == null) {
                Out.line("✘ Calendars and contacts: ${w.name}: its passwordFile cannot be read (${w.passwordFile})")
                failed++
                continue
            }
            // What the server has for the user, as DAVx⁵'s login asks: a password it refuses, or an address with neither
            // CalDAV nor CardDAV, stops it here (a server that cannot be reached does not: the phone may be offline).
            if (p.found == null || "password" in p.changes || p.found.config?.optString("url") != w.url || p.found.config?.optString("userName") != w.userName) {
                val result = find(w, password)
                found[p] = result
                if (result != null && result.problem != null) {
                    Out.line("✘ Calendars and contacts: ${w.name}: ${result.problem}${if (p.found == null) ": not added" else ": its settings are set all the same"}")
                    failed++
                    if (p.found == null) continue
                }
            }
            val name = p.found?.name ?: (w.accountName ?: found[p]?.email ?: w.userName).let { base ->
                // (Another account of the config may have that name.)
                if (base in taken) "$base (${w.name})" else base
            }
            taken += name
            names[p] = name
            input.put(
                JSONObject().put("op", if (p.found == null) "add" else "update").put("name", name).put("nixupName", w.name)
                    .put("config", w.config.toString()).put("password", password ?: JSONObject.NULL)
                    .put("interval", interval(w.config) ?: JSONObject.NULL).put("tasks", tasksAuthority ?: JSONObject.NULL)
                    .put("authorities", authorities()),
            )
        }
        if (input.length() == 0) return failed

        val results = asApp(uid, input)
        // The syncs asked for, now: Android may hold them for minutes. An account added is waited for (its first sync makes
        // its address books' accounts, whose syncs come after it), for the build to say what it brought.
        val added = names.filter { (_, name) -> results[name]?.optString("status") == "added" }.values.toSet()
        if (added.isNotEmpty()) runSyncs(45_000)
        else if (results.values.any { it.optString("status") == "set" }) runSyncs(4_000)
        for ((p, name) in names) {
            val w = p.wanted
            val result = results[name]
            val label = if (name != w.name) "${w.name} (\"$name\")" else w.name
            when (result?.optString("status")) {
                "added" -> Out.line("✔ Calendars and contacts: $label added (${found[p]?.services ?: display(w.url)}); ${firstSync(name)}")
                "set" -> Out.line("✔ Calendars and contacts: $label: its ${p.changes.joinToString(" and ")} as the config says; a sync started")
                "missing" -> { Out.line("✘ Calendars and contacts: $label: gone from the phone since the plan"); failed++ }
                else -> { Out.line("✘ Calendars and contacts: $label: ${result?.optString("error") ?: "Nixup's app gave no answer"}"); failed++ }
            }
        }
        return failed
    }

    private fun wanted(spec: JSONObject): List<Wanted> {
        val settings = spec.optJSONObject("settings") ?: JSONObject()
        val list = spec.optJSONArray("accounts") ?: JSONArray()
        return (0 until list.length()).map { i ->
            val a = list.getJSONObject(i)
            // The app's settings of the account: the config's for all of them, and its own.
            val config = JSONObject(settings.toString())
                .put("url", a.getString("url")).put("userName", a.getString("userName"))
                .put("collections", a.optJSONArray("collections") ?: JSONObject.NULL)
                .put("exclude", a.optJSONArray("exclude") ?: JSONArray())
            Wanted(
                a.getString("name"), a.optStr("accountName"), a.getString("url"), a.getString("userName"),
                a.optStr("passwordFile"), config,
            )
        }
    }

    private fun JSONObject.optStr(key: String): String? = if (isNull(key)) null else optString(key).ifEmpty { null }

    /** Whether two JSON values are the same (objects whatever the order of their keys). */
    private fun same(a: Any?, b: Any?): Boolean = when {
        a is JSONObject && b is JSONObject -> a.length() == b.length() && a.keys().asSequence().all { b.has(it) && same(a.opt(it), b.opt(it)) }
        a is JSONArray && b is JSONArray -> a.length() == b.length() && (0 until a.length()).all { same(a.opt(it), b.opt(it)) }
        a is Number && b is Number -> a.toDouble() == b.toDouble()
        else -> a == b || (a == null && b == JSONObject.NULL) || (a == JSONObject.NULL && b == null)
    }

    private fun label(wanted: Wanted, found: Stored?): String =
        if (found != null && found.name != wanted.name) "${wanted.name} (\"${found.name}\")" else wanted.name

    /** How often the account syncs (seconds), as the config says; null: only when asked. */
    private fun interval(config: JSONObject): Long? = if (config.isNull("syncInterval")) null else config.optLong("syncInterval", 240) * 60

    /** The authorities of the main accounts' sync adapters. */
    private fun authorities() = JSONArray(listOf(CalendarContract.AUTHORITY) + TASKS.map { it.authority })

    /** An address as it may be shown: without a user name and password in it. */
    private fun display(url: String): String = runCatching {
        val uri = URI(url)
        URI(uri.scheme, null, uri.host, uri.port, uri.path, uri.query, uri.fragment).toString()
    }.getOrDefault(url)

    /** The secret's text, without the line break a file ends with; null when it cannot be read. */
    private fun secret(path: String): String? = try {
        File(path).takeIf { it.isFile }?.readText()?.trimEnd('\n', '\r')
    } catch (e: IOException) {
        null
    }

    private fun count(n: Int, what: String) = if (n == 1) "1 $what" else "$n ${what}s"

    // --- What the server has, as DAVx⁵'s login finds it (its DavResourceFinder, in the engine).

    /** What the server answered: the services found, the user's email address (the account's name), or what is wrong. */
    private class Found(val services: String?, val email: String?, val problem: String?)

    /** Asks [wanted]'s server for its services; null when it cannot be reached (nothing is known then). */
    private fun find(wanted: Wanted, password: String?): Found? = try {
        runBlocking {
            HttpClientBuilder(ProductIds(null), Dav.logger).authenticate(null, Credentials(wanted.userName, password)).build().use { client ->
                val configuration = DavResourceFinder(URI(wanted.url), Credentials(wanted.userName, password), client, Dav.logger).findInitialConfiguration()
                val services = listOfNotNull("CalDAV".takeIf { configuration.calDAV != null }, "CardDAV".takeIf { configuration.cardDAV != null })
                when {
                    services.isNotEmpty() -> Found(services.joinToString(" and ") + " found", configuration.calDAV?.emails?.firstOrNull(), null)
                    configuration.encountered401 -> Found(null, null, "${display(wanted.url)} refused the user name or password")
                    else -> Found(null, null, "${display(wanted.url)} has no CalDAV nor CardDAV service for ${wanted.userName} (or cannot be reached)")
                }
            }
        }
    } catch (e: Exception) {
        null
    }

    // --- The accounts on the phone, read as root from Android's account databases.

    /** Nixup's accounts, from Android's credential-encrypted account database; from dumpsys, by name, where it cannot be read. */
    private fun stored(): List<Stored> = try {
        open(ACCOUNTS_CE).use { db ->
            db.rawQuery("SELECT _id, name, password FROM accounts WHERE type = ?", arrayOf(Dav.ACCOUNT_TYPE)).use { rows ->
                buildList {
                    while (rows.moveToNext()) {
                        val extras = extras(db, rows.getLong(0))
                        add(Stored(
                            rows.getString(1), if (rows.isNull(2)) null else rows.getString(2), extras[Dav.KEY_NAME],
                            extras[Dav.KEY_CONFIG]?.let { runCatching { JSONObject(it) }.getOrNull() }, known = true,
                        ))
                    }
                }
            }
        }
    } catch (e: Exception) {
        accountNames(Dav.ACCOUNT_TYPE).map { Stored(it, null, null, null, known = false) }
    }

    /** The address books' accounts of each of Nixup's accounts (by its name). */
    private fun addressBooks(): Map<String, List<String>> = try {
        open(ACCOUNTS_CE).use { db ->
            db.rawQuery("SELECT _id, name FROM accounts WHERE type = ?", arrayOf(Dav.ACCOUNT_TYPE_ADDRESS_BOOK)).use { rows ->
                val books = HashMap<String, MutableList<String>>()
                while (rows.moveToNext()) {
                    val owner = extras(db, rows.getLong(0))["account_name"] ?: continue
                    books.getOrPut(owner) { ArrayList() } += rows.getString(1)
                }
                books
            }
        }
    } catch (e: Exception) {
        emptyMap()
    }

    private fun extras(db: SQLiteDatabase, id: Long): Map<String, String> =
        db.rawQuery("SELECT key, value FROM extras WHERE accounts_id = ?", arrayOf(id.toString())).use { rows ->
            buildMap { while (rows.moveToNext()) if (!rows.isNull(0) && !rows.isNull(1)) put(rows.getString(0), rows.getString(1)) }
        }

    private fun accountNames(type: String): List<String> =
        Regex("""Account \{name=(.+?), type=([^\}]+)\}""").findAll(Device.run("dumpsys", "account").second)
            .filter { it.groupValues[2] == type }.map { it.groupValues[1] }.distinct().toList()

    private fun davx5Accounts(): Int = accountNames(DAVX5_TYPE).size

    private fun open(path: String): SQLiteDatabase {
        // (Android's SQLite reads a setting through the process's application first: its default, see Accounts.)
        runCatching { Class.forName("android.database.sqlite.SQLiteCompatibilityWalFlags").getMethod("init", String::class.java).invoke(null, null) }
        return SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS)
    }

    /**
     * How many changes made on the phone (events, contacts, tasks: added, changed, deleted) an account ([stored], with
     * its address books [books]) has not uploaded yet, as Android's providers have them marked (dirty).
     */
    private fun unsynced(stored: Stored, books: List<String>): Int {
        fun rows(uri: String, where: String): Int {
            val (status, said) = Device.run("content", "query", "--uri", uri, "--projection", "_id", "--where", where)
            return if (status == 0) said.lines().count { it.startsWith("Row:") } else 0
        }
        fun syncAdapter(uri: String, account: String, type: String) = Uri.parse(uri).buildUpon()
            .appendQueryParameter("caller_is_syncadapter", "true").appendQueryParameter("account_name", account)
            .appendQueryParameter("account_type", type).build().toString()
        fun quoted(text: String) = "'" + text.replace("'", "''") + "'"
        var n = rows(syncAdapter("content://com.android.calendar/events", stored.name, Dav.ACCOUNT_TYPE), "dirty=1")
        if (books.isNotEmpty())
            n += rows("content://com.android.contacts/raw_contacts",
                "dirty=1 AND account_type=${quoted(Dav.ACCOUNT_TYPE_ADDRESS_BOOK)} AND account_name IN (${books.joinToString(",") { quoted(it) }})")
        for (tasks in TASKS.filter { it.authority != JTX && Device.versionCode(it.app) != null })
            n += rows(syncAdapter("content://${tasks.authority}/tasks", stored.name, Dav.ACCOUNT_TYPE), "_dirty=1")
        return n
    }

    /** The tasks app's provider the tasks sync with: the config's ([wanted], a package), else the first installed. */
    private fun tasksProvider(wanted: String?, installing: Set<String>): String? {
        val candidates = if (wanted != null) TASKS.filter { it.app == wanted || it.authority == wanted } else TASKS
        return candidates.firstOrNull { it.app in installing || Device.versionCode(it.app) != null }?.authority
    }

    // --- As the app (see AppProcess).

    /** Has the engine, run as Nixup's app ([uid]), carry out [input]; each account's outcome by its name. */
    private fun asApp(uid: Int, input: JSONArray): Map<String, JSONObject> {
        val results = HashMap<String, JSONObject>()
        try {
            AppProcess.run(uid, listOf("caldav-accounts"), input.toString()) { line ->
                val json = if (line.startsWith("{")) runCatching { JSONObject(line) }.getOrNull() else null
                if (json != null) results[json.optString("name")] = json
                else if (line.isNotBlank()) Out.line("  $line")
            }
        } catch (e: Exception) {
            Out.line("✘ Calendars and contacts: the accounts cannot be set as Nixup's app (${e.message})")
        }
        return results
    }

    /** A tasks app the tasks can sync with: its package, its provider's authority, its permissions. */
    private class Tasks(val label: String, val app: String, val authority: String, val permissions: List<String>)

    companion object {
        const val APP = "dev.nixup.manager"
        private const val ACCOUNTS_CE = "/data/system_ce/0/accounts_ce.db"
        private const val DAVX5 = "at.bitfire.davdroid"
        private const val DAVX5_TYPE = "bitfire.at.davdroid"
        private const val JTX = "at.techbee.jtx.provider"

        /** In the order the sync takes them without a choice in the config (see the app's TasksAppManager). */
        private val TASKS = listOf(
            Tasks("Tasks.org", "org.tasks", "org.tasks.opentasks", listOf("org.tasks.permission.READ_TASKS", "org.tasks.permission.WRITE_TASKS")),
            Tasks("OpenTasks", "org.dmfs.tasks", "org.dmfs.tasks", listOf("org.dmfs.permission.READ_TASKS", "org.dmfs.permission.WRITE_TASKS")),
            Tasks("jtx Board", "at.techbee.jtx", JTX, listOf("at.techbee.jtx.permission.READ", "at.techbee.jtx.permission.WRITE")),
        )

        /**
         * In the engine run as Nixup's app (caldav-accounts, see AppProcess): through Android's account and sync
         * services, which take the app's own uid for its accounts, carries out each action it reads (a JSON array):
         *  - add (name, nixupName, config, password, interval, tasks): adds the account with them, sets its syncs
         *    (calendar; the tasks app's; every [interval] seconds and on changes, or only when asked: null) and asks for
         *    its first sync;
         *  - update (the same): sets them on the account there, and asks for a sync;
         *  - remove (name): removes the account and its address books' accounts;
         *  - sync (name, authorities, books): asks for a sync of each (and of its address books' contacts, with books).
         * Writes a line of JSON for each: its name and its status (added, set, removed, synced, missing, failed with
         * the error). Exit status: how many failed.
         */
        fun asApp(): Int {
            val list = JSONArray(System.`in`.bufferedReader().readText())
            val accounts = AccountService(APP)
            var failed = 0
            for (i in 0 until list.length()) {
                val item = list.getJSONObject(i)
                val name = item.getString("name")
                val account = Account(name, Dav.ACCOUNT_TYPE)
                val result = JSONObject().put("name", name)
                try {
                    val present = name in accounts.names(Dav.ACCOUNT_TYPE)
                    when (item.getString("op")) {
                        "remove" -> {
                            // Its address books first: theirs are the contacts.
                            for (book in accounts.names(Dav.ACCOUNT_TYPE_ADDRESS_BOOK)) {
                                val bookAccount = Account(book, Dav.ACCOUNT_TYPE_ADDRESS_BOOK)
                                if (accounts.userData(bookAccount, "account_name") == name) accounts.remove(bookAccount)
                            }
                            result.put("status", if (!present) "missing" else if (accounts.remove(account)) "removed" else "failed")
                        }
                        "sync" -> {
                            if (!present) result.put("status", "missing")
                            else {
                                val authorities = item.optJSONArray("authorities") ?: JSONArray()
                                for (j in 0 until authorities.length()) requestSync(account, authorities.getString(j), refresh = false)
                                // (Its address books' contacts sync as their own accounts.)
                                if (item.optBoolean("books"))
                                    for (book in booksOf(accounts, name)) requestSync(book, ContactsContract.AUTHORITY, refresh = false)
                                result.put("status", "synced")
                            }
                        }
                        "add", "update" -> {
                            val adding = item.getString("op") == "add"
                            val password = if (item.isNull("password")) null else item.getString("password")
                            val userData = JSONObject().put(Dav.KEY_NAME, item.getString("nixupName")).put(Dav.KEY_CONFIG, item.getString("config"))
                            if (adding) userData.put(Dav.KEY_ADDED, System.currentTimeMillis().toString())
                            when {
                                adding && present -> result.put("status", "failed").put("error", "an account of that name is on the phone already")
                                !adding && !present -> result.put("status", "missing")
                                adding && !accounts.add(account, password, userData) ->
                                    result.put("status", "failed").put("error", "Android did not add it")
                                else -> {
                                    if (!adding) {
                                        for (key in userData.keys()) accounts.setUserData(account, key, userData.getString(key))
                                        if (password != null && accounts.password(account) != password) accounts.setPassword(account, password)
                                    }
                                    val interval = if (item.isNull("interval")) null else item.getLong("interval")
                                    val tasks = if (item.isNull("tasks")) null else item.getString("tasks")
                                    setSyncs(accounts, account, interval, tasks)
                                    requestSync(account, CalendarContract.AUTHORITY, refresh = true)
                                    tasks?.let { requestSync(account, it, refresh = true) }
                                    // Its address books sync again with the settings (their accounts come with its first sync).
                                    for (book in booksOf(accounts, name)) requestSync(book, ContactsContract.AUTHORITY, refresh = false)
                                    result.put("status", if (adding) "added" else "set")
                                }
                            }
                        }
                        else -> result.put("status", "failed").put("error", "unknown action")
                    }
                } catch (e: Exception) {
                    val cause = (e as? InvocationTargetException)?.targetException ?: e
                    result.put("status", "failed").put("error", "${cause.javaClass.simpleName}: ${cause.message}")
                }
                if (result.optString("status") == "failed") failed++
                println(result.toString())
            }
            System.out.flush()
            return failed
        }

        private fun booksOf(accounts: AccountService, owner: String): List<Account> =
            accounts.names(Dav.ACCOUNT_TYPE_ADDRESS_BOOK).map { Account(it, Dav.ACCOUNT_TYPE_ADDRESS_BOOK) }
                .filter { accounts.userData(it, "account_name") == owner }

        /**
         * The account's syncs, as DAVx⁵'s AutomaticSyncManager sets them: its calendars and the tasks app's tasks (the
         * other tasks apps not), on changes and every [interval] seconds (only when asked: null), and its address books'
         * contacts the same way (the app sets those of the address books it adds itself).
         */
        private fun setSyncs(accounts: AccountService, account: Account, interval: Long?, tasks: String?) {
            fun set(target: Account, authority: String, on: Boolean) {
                ContentResolver.setIsSyncable(target, authority, if (on) 1 else 0)
                ContentResolver.setSyncAutomatically(target, authority, on && interval != null)
                // (Android adds one of a day when syncs on changes are turned on: the config's alone.)
                for (sync in ContentResolver.getPeriodicSyncs(target, authority)) ContentResolver.removePeriodicSync(target, authority, sync.extras)
                if (on && interval != null) ContentResolver.addPeriodicSync(target, authority, Bundle.EMPTY, interval)
            }
            set(account, CalendarContract.AUTHORITY, true)
            for (provider in TASKS) set(account, provider.authority, provider.authority == tasks)
            for (book in booksOf(accounts, account.name)) set(book, ContactsContract.AUTHORITY, true)
        }

        /**
         * Asks Android for a sync of [account]'s [authority] now, as Nixup's app (Android takes a sync of an account only
         * from an app that may see it, and drops root's); with [refresh], the app looks up the collections first.
         */
        private fun requestSync(account: Account, authority: String, refresh: Boolean) {
            val extras = Bundle().apply {
                putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
                putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
                if (refresh) putBoolean(Dav.EXTRA_REFRESH, true)
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

        fun remove(account: Account): Boolean =
            needed("removeAccountExplicitly", Account::class.java).invoke(service, account) as Boolean? == true

        /** Adds [account] with [password] and [userData]; false when Android does not. */
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
}
