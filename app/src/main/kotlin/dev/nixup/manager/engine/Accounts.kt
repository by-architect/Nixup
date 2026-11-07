package dev.nixup.manager.engine

import android.accounts.Account
import android.content.ContentResolver
import android.database.sqlite.SQLiteDatabase
import android.os.Bundle
import android.os.IBinder
import android.system.Os
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/**
 * An app's accounts in Android's account settings: those of the account types its own authenticators add (DAVx⁵'s
 * CalDAV/CardDAV accounts and their address books, a mail app's, ...), backed up with its data (the "accounts" part).
 * Android keeps them in its own databases, not in the app's folder, and removes them with the app: an app whose data
 * came back without them has nothing to sync with.
 *
 * They are read as root from those databases (accounts_de.db, and accounts_ce.db, there after the first unlock): each
 * one's name, type, password, user data, auth tokens and which apps see it; and from Android's sync settings, which of
 * the sync adapters of its type run for it (syncable, automatically, periodic syncs). On restore, those not on the
 * phone are made again as the app itself: Android lets only an account type's own app add its accounts (it checks the
 * caller's signing key, which root has not), so a copy of the engine runs with the app's uid for that, reading them
 * from its input. Their sync settings are set as root after.
 */
object Accounts {
    private const val DE = "/data/system_de/0/accounts_de.db"
    private const val CE = "/data/system_ce/0/accounts_ce.db"

    // (Android's regular expressions want every brace escaped, even in a character class.)
    private val authenticator = Regex("""AuthenticatorDescription \{type=([^\}]+)\}, ComponentInfo\{([^/\}]+)/""")

    /** The account types each app's authenticators add, as Android's account service lists them now. */
    private fun allTypes(): Map<String, Set<String>> = authenticator.findAll(Device.run("dumpsys", "account").second)
        .groupBy({ it.groupValues[2] }, { it.groupValues[1] }).mapValues { it.value.toSet() }

    /** The account types [id]'s authenticators add (asked again each time: an app just installed adds them now). */
    fun types(id: String): Set<String> = allTypes()[id] ?: emptySet()

    /** [allTypes] once for all of a backup's apps. */
    private val typesNow: Map<String, Set<String>> by lazy { allTypes() }

    /**
     * [id]'s accounts, for its backup: an empty array when it has none (or adds none), null when Android's account
     * databases cannot be read here.
     */
    fun read(id: String): JSONArray? {
        val types = typesNow[id] ?: emptySet()
        if (types.isEmpty()) return JSONArray()
        return try {
            open(CE).use { ce ->
                open(DE).use { de ->
                    val accounts = JSONArray()
                    val marks = types.joinToString(",") { "?" }
                    ce.rawQuery("SELECT _id, name, type, password FROM accounts WHERE type IN ($marks) ORDER BY _id", types.toTypedArray()).use { rows ->
                        while (rows.moveToNext()) {
                            val ceId = rows.getLong(0).toString()
                            val name = rows.getString(1)
                            val type = rows.getString(2)
                            val account = JSONObject().put("name", name).put("type", type)
                            if (!rows.isNull(3)) account.put("password", rows.getString(3))
                            account.put("userData", pairs(ce, "SELECT key, value FROM extras WHERE accounts_id = ?", ceId))
                            account.put("authTokens", pairs(ce, "SELECT type, authtoken FROM authtokens WHERE accounts_id = ?", ceId))
                            // The device-encrypted database numbers its accounts as the other does; by name and type, to be sure.
                            de.rawQuery("SELECT _id FROM accounts WHERE name = ? AND type = ?", arrayOf(name, type)).use { found ->
                                if (found.moveToFirst()) {
                                    account.put("visibility", pairs(de, "SELECT _package, value FROM visibility WHERE accounts_id = ?", found.getLong(0).toString()))
                                }
                            }
                            account.put("sync", syncSettings(Account(name, type)))
                            accounts.put(account)
                        }
                    }
                    accounts
                }
            }
        } catch (e: Exception) {
            Out.line("  $id's accounts cannot be read here (${e.javaClass.simpleName}: ${e.message}): backed up without them")
            null
        }
    }

    private fun open(path: String): SQLiteDatabase {
        // Android's SQLite first reads a setting of its own through the process's application, which an engine with a
        // system context (see Device) has but the settings provider refuses (SecurityException): its default, set first.
        runCatching { Class.forName("android.database.sqlite.SQLiteCompatibilityWalFlags").getMethod("init", String::class.java).invoke(null, null) }
        return SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS)
    }

    /** The rows of a two-column [query] as an object (a null value left out). */
    private fun pairs(db: SQLiteDatabase, query: String, vararg args: String): JSONObject {
        val json = JSONObject()
        db.rawQuery(query, args).use { rows ->
            while (rows.moveToNext()) {
                if (!rows.isNull(0) && !rows.isNull(1)) json.put(rows.getString(0), rows.getString(1))
            }
        }
        return json
    }

    /** Which sync adapters of the account's type run for it, automatically, and how often: Android's sync settings. */
    private fun syncSettings(account: Account): JSONArray {
        val settings = JSONArray()
        for (adapter in ContentResolver.getSyncAdapterTypes().filter { it.accountType == account.type }) {
            val authority = adapter.authority
            val periodic = JSONArray()
            for (sync in ContentResolver.getPeriodicSyncs(account, authority)) {
                periodic.put(JSONObject().put("period", sync.period).put("extras", bundleJson(sync.extras)))
            }
            settings.put(
                JSONObject().put("authority", authority).put("syncable", ContentResolver.getIsSyncable(account, authority))
                    .put("automatically", ContentResolver.getSyncAutomatically(account, authority)).put("periodic", periodic),
            )
        }
        return settings
    }

    /** A sync's extras: each value with its type, as a Bundle has them (strings, numbers, true/false). */
    private fun bundleJson(bundle: Bundle?): JSONObject {
        val json = JSONObject()
        bundle ?: return json
        for (key in bundle.keySet()) {
            @Suppress("DEPRECATION")
            when (val value = bundle.get(key)) {
                is String -> json.put(key, JSONArray().put("string").put(value))
                is Int -> json.put(key, JSONArray().put("int").put(value))
                is Long -> json.put(key, JSONArray().put("long").put(value))
                is Boolean -> json.put(key, JSONArray().put("boolean").put(value))
                is Double -> json.put(key, JSONArray().put("double").put(value))
                is Float -> json.put(key, JSONArray().put("float").put(value.toDouble()))
            }
        }
        return json
    }

    private fun bundleOf(json: JSONObject?): Bundle {
        val bundle = Bundle()
        json ?: return bundle
        for (key in json.keys()) {
            val value = json.optJSONArray(key) ?: continue
            when (value.optString(0)) {
                "string" -> bundle.putString(key, value.optString(1))
                "int" -> bundle.putInt(key, value.optInt(1))
                "long" -> bundle.putLong(key, value.optLong(1))
                "boolean" -> bundle.putBoolean(key, value.optBoolean(1))
                "double" -> bundle.putDouble(key, value.optDouble(1))
                "float" -> bundle.putFloat(key, value.optDouble(1).toFloat())
            }
        }
        return bundle
    }

    /** The accounts on the phone now, as "type/name". */
    private fun present(): Set<String> = try {
        open(DE).use { de ->
            de.rawQuery("SELECT type, name FROM accounts", null).use { rows ->
                buildSet { while (rows.moveToNext()) add(rows.getString(0) + "/" + rows.getString(1)) }
            }
        }
    } catch (e: Exception) {
        emptySet()
    }

    private fun key(account: JSONObject) = account.optString("type") + "/" + account.optString("name")

    private fun label(account: JSONObject) = "${account.optString("name")} (${account.optString("type")})"

    /** What a restore did: how many accounts came back, and how many could not. */
    class Restored(val back: Int, val lost: Int)

    /**
     * Brings [accounts] of [id] (its uid: [uid]) back: those not on the phone are made again, as the app, with their
     * passwords, user data, auth tokens and the apps that see them, then their sync settings set; one there already
     * stays as it is.
     */
    fun restore(id: String, uid: Int, accounts: JSONArray): Restored {
        val all = (0 until accounts.length()).map { accounts.getJSONObject(it) }
        if (all.isEmpty()) return Restored(0, 0)
        val before = present()
        val there = all.filter { key(it) in before }
        val missing = all.filter { key(it) !in before }
        there.forEach { Out.line("  Account ${label(it)}: on the phone already, left as it is") }
        if (missing.isEmpty()) return Restored(0, 0)
        // An app's types only: its authenticators must be installed (Android adds no account of a type no app adds).
        // Android registers them shortly after an install: a few seconds for them, an app just installed from its backup.
        var types = types(id)
        for (wait in 1..10) {
            if (missing.all { it.optString("type") in types }) break
            Thread.sleep(1000)
            types = types(id)
        }
        val unknown = missing.filter { it.optString("type") !in types }
        unknown.forEach { Out.line("  ✘ Account ${label(it)}: $id adds no accounts of this type (any more): not brought back") }
        val wanted = missing - unknown.toSet()
        if (wanted.isEmpty()) return Restored(0, unknown.size)
        val user = appUser(uid)
        if (user == null) {
            Out.line("  ✘ $id's accounts: its uid ($uid) is not an app's of the phone's owner: they cannot be added as it")
            return Restored(0, missing.size)
        }
        val status = asApp(id, user, JSONArray(wanted))
        val now = present()
        val back = wanted.filter { key(it) in now }
        back.forEach { applySync(it) }
        val lost = missing.size - back.size
        // (All back, but the app's engine said something went wrong: an account's tokens or the apps that see it.)
        return Restored(back.size, if (lost == 0 && status != 0) 1 else lost)
    }

    /** Android's name for an app's uid of the phone's owner (u0_a123 for 10123), which busybox's setuidgid takes. */
    private fun appUser(uid: Int): String? = if (uid in 10000..19999) "u0_a${uid - 10000}" else null

    /**
     * Runs the engine as [user] (the app's uid, with its group alone) to add [accounts], which it reads from its input
     * (passwords stay off command lines and files); its lines are told here. The engine's APK is copied where the app's
     * uid can read it first (Nixup's own folder is root's alone). Returns its exit status.
     */
    private fun asApp(id: String, user: String, accounts: JSONArray): Int {
        val classpath = System.getenv("CLASSPATH")?.split(':')?.firstOrNull { File(it).isFile }
        if (classpath == null) {
            Out.line("  ✘ $id's accounts: the engine's APK is not known here (CLASSPATH)")
            return 1
        }
        val copy = File("/data/local/tmp/nixup-accounts-${Os.getpid()}.apk")
        return try {
            File(classpath).copyTo(copy, overwrite = true)
            Os.chmod(copy.path, "644".toInt(8))
            val process = ProcessBuilder(BUSYBOX, "setuidgid", user, "/system/bin/app_process", "/system/bin", "dev.nixup.manager.engine.Main", "accounts-add", id)
                .directory(File("/")).redirectErrorStream(true)
                .apply {
                    environment()["CLASSPATH"] = copy.path
                    environment().remove("NIXUP_ENGINE_STATUS")
                }.start()
            process.outputStream.use { it.write(accounts.toString().toByteArray()) }
            process.inputStream.bufferedReader().forEachLine { if (it.isNotBlank()) Out.line(it) }
            process.waitFor()
        } catch (e: Exception) {
            Out.line("  ✘ $id's accounts: could not run as the app (${e.javaClass.simpleName}: ${e.message})")
            1
        } finally {
            copy.delete()
        }
    }

    /** An account's sync settings as backed up, set as root (it may change them for any app). */
    private fun applySync(account: JSONObject) {
        val it = Account(account.getString("name"), account.getString("type"))
        val settings = account.optJSONArray("sync") ?: return
        var set = 0
        for (i in 0 until settings.length()) {
            val sync = settings.getJSONObject(i)
            val authority = sync.optString("authority").ifEmpty { continue }
            try {
                // Unknown (-1) and waiting for the user's consent (3) are Android's to settle.
                when (val syncable = sync.optInt("syncable", -1)) {
                    0, 1 -> ContentResolver.setIsSyncable(it, authority, syncable)
                }
                ContentResolver.setSyncAutomatically(it, authority, sync.optBoolean("automatically"))
                // The periodic syncs as they were: Android adds one of a day for an adapter newly turned on.
                for (existing in ContentResolver.getPeriodicSyncs(it, authority)) ContentResolver.removePeriodicSync(it, authority, existing.extras)
                val periodic = sync.optJSONArray("periodic") ?: JSONArray()
                for (j in 0 until periodic.length()) {
                    val p = periodic.getJSONObject(j)
                    ContentResolver.addPeriodicSync(it, authority, bundleOf(p.optJSONObject("extras")), p.optLong("period"))
                }
                set++
            } catch (e: Exception) {
                Out.line("  Account ${label(account)}: its sync settings for $authority could not be set (${e.message})")
            }
        }
        if (set > 0) Out.line("  Account ${label(account)}: sync settings as they were ($set of its sync adapters)")
    }

    /**
     * In the engine run as the app ([id]): adds the accounts it reads from its input through Android's account
     * service, which takes them from the app's own uid (an account type's app adds its accounts); each one's user data
     * goes in with it and is set again after (as DAVx⁵ does: Android has lost them at the add), then its auth tokens
     * and which apps see it. Exit status: the number that could not be added.
     */
    fun addAsApp(id: String): Int {
        val accounts = JSONArray(System.`in`.bufferedReader().readText())
        val binder = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java).invoke(null, "account") as IBinder?
            ?: throw Stop("Android's account service is not there", 1)
        val service = Class.forName("android.accounts.IAccountManager\$Stub").getMethod("asInterface", IBinder::class.java).invoke(null, binder)
            ?: throw Stop("Android's account service cannot be reached", 1)
        fun method(name: String, vararg types: Class<*>): Method? =
            service.javaClass.methods.firstOrNull { it.name == name && it.parameterTypes.contentEquals(types) }
        // Android 12 and later name the caller's package too.
        val add4 = method("addAccountExplicitly", Account::class.java, String::class.java, Bundle::class.java, String::class.java)
        val add3 = method("addAccountExplicitly", Account::class.java, String::class.java, Bundle::class.java)
        val setUserData = method("setUserData", Account::class.java, String::class.java, String::class.java)
        val setAuthToken = method("setAuthToken", Account::class.java, String::class.java, String::class.java)
        val setVisibility = method("setAccountVisibility", Account::class.java, String::class.java, Int::class.javaPrimitiveType!!)
        if (add4 == null && add3 == null) throw Stop("this Android's account service has no addAccountExplicitly that Nixup knows", 1)
        var failed = 0
        for (i in 0 until accounts.length()) {
            val json = accounts.getJSONObject(i)
            val account = Account(json.getString("name"), json.getString("type"))
            val userData = json.optJSONObject("userData") ?: JSONObject()
            val extras = Bundle().apply { for (key in userData.keys()) putString(key, userData.optString(key)) }
            val password = if (json.has("password")) json.getString("password") else null
            try {
                val added = (if (add4 != null) add4.invoke(service, account, password, extras, id) else add3!!.invoke(service, account, password, extras)) as Boolean
                if (!added) {
                    Out.line("  ✘ Account ${label(json)}: Android did not add it (one of that name is there?)")
                    failed++
                    continue
                }
                for (key in userData.keys()) setUserData?.invoke(service, account, key, userData.optString(key))
                val tokens = json.optJSONObject("authTokens") ?: JSONObject()
                for (type in tokens.keys()) setAuthToken?.invoke(service, account, type, tokens.optString(type))
                val visibility = json.optJSONObject("visibility") ?: JSONObject()
                for (pkg in visibility.keys()) {
                    // An app that is not on the phone any more is passed over by Android.
                    runCatching { setVisibility?.invoke(service, account, pkg, visibility.optInt(pkg)) }
                }
                Out.line("  ✔ Account ${label(json)}: back, with ${if (password != null) "its password, " else ""}${count(userData.length(), "setting")} and ${count(tokens.length(), "token")}")
            } catch (e: InvocationTargetException) {
                Out.line("  ✘ Account ${label(json)}: ${e.targetException.javaClass.simpleName}: ${e.targetException.message}")
                failed++
            }
        }
        return failed
    }

    private fun count(n: Int, what: String) = if (n == 1) "1 $what" else "$n ${what}s"

    private val BUSYBOX = listOf("/data/adb/magisk/busybox", "/data/adb/ksu/bin/busybox", "/data/adb/ap/bin/busybox")
        .firstOrNull { File(it).canExecute() } ?: "/data/adb/magisk/busybox"
}
