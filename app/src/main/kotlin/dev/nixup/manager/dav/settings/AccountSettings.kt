/*
 * Adapted from DAVx⁵ (davx5-ose 4.5.21, GNU GPL 3.0): its AccountSettings and Credentials, read from what Nixup's
 * build puts in an account (DAVx⁵'s own are set in its app).
 */

package dev.nixup.manager.dav.settings

import android.accounts.Account
import android.accounts.AccountManager
import android.content.Context
import at.bitfire.synctools.vcard.GroupMethod
import dev.nixup.manager.dav.Dav
import dev.nixup.manager.dav.accounts.InvalidAccountException
import dev.nixup.manager.dav.sync.SyncSettings
import org.json.JSONArray
import org.json.JSONObject

/** The user name and password an account logs in with (Basic or Digest authentication). */
class Credentials(
    val username: String?,
    val password: String?
) {
    override fun toString() = "Credentials(username=$username, password=${if (password != null) "*****" else null})"
}

/**
 * The settings of a main account ([account]), as the build sets them from the config (services.caldav.accounts.<name>
 * and services.caldav): JSON in its user data ([Dav.KEY_CONFIG]), its password as its password. The defaults are
 * DAVx⁵'s.
 *
 * @throws InvalidAccountException when the account is not there (any more)
 */
class AccountSettings(context: Context, val account: Account) {

    private val manager = AccountManager.get(context)

    private val config: JSONObject = run {
        if (manager.getAccountsByType(account.type).none { it == account })
            throw InvalidAccountException(account)
        manager.getUserData(account, Dav.KEY_CONFIG)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()
    }

    /** When the build added the account (see [Dav.KEY_ADDED]). */
    val added: String? = manager.getUserData(account, Dav.KEY_ADDED)

    /** The server's address the config gives (the server itself, or the user's calendars or address books). */
    val baseUrl: String? get() = config.optString("url").ifEmpty { null }

    fun credentials() = Credentials(
        username = config.optString("userName").ifEmpty { null },
        password = manager.getPassword(account)
    )

    /**
     * Which collections are synced as they are found: "all" (the user's and those shared with them), "personal" (those
     * in the user's own home sets) or "none" (only [collections]).
     */
    val syncCollections: String get() = config.optString("syncCollections", "all")

    /**
     * The collections to sync, by their name (as the server shows it) or address: those alone, whatever
     * [syncCollections] says; null: as it says.
     */
    val collections: List<String>? get() = config.optJSONArray("collections")?.strings()

    /** Collections never synced, as [collections] names them (DAVx⁵'s default: Nextcloud's "Recently contacted"). */
    val excluded: List<String> get() = config.optJSONArray("exclude")?.strings() ?: emptyList()

    /** How often each data type syncs, in seconds; null: only when asked for (a build, a calendar app). */
    fun getSyncInterval(): Long? = when {
        !config.has("syncInterval") -> DEFAULT_SYNC_INTERVAL
        config.isNull("syncInterval") -> null
        else -> config.optLong("syncInterval") * 60
    }

    fun getSyncWifiOnly(): Boolean = config.optBoolean("wifiOnly", false)

    /** How far back events are synced, in days; null: all of them. */
    fun getTimeRangePastDays(): Int? = when {
        !config.has("pastDays") -> DEFAULT_TIME_RANGE_PAST_DAYS
        config.isNull("pastDays") -> null
        else -> config.optInt("pastDays", DEFAULT_TIME_RANGE_PAST_DAYS).takeIf { it >= 0 }
    }

    /** Minutes before an event without reminders that one is added; null: none is. */
    fun getDefaultAlarm(): Int? = if (config.has("defaultAlarm") && !config.isNull("defaultAlarm")) config.optInt("defaultAlarm") else null

    fun getManageCalendarColors(): Boolean = config.optBoolean("manageCalendarColors", true)

    fun getEventColors(): Boolean = config.optBoolean("eventColors", false)

    fun getGroupMethod(): GroupMethod = runCatching { GroupMethod.valueOf(config.optString("contactGroupMethod")) }.getOrNull()
        ?: GroupMethod.GROUP_VCARDS

    /** The tasks app whose tasks are synced (its package), or null: the one that is installed (see TasksAppManager). */
    val tasksApp: String? get() = config.optString("tasksApp").ifEmpty { null }

    /** This sync run's settings. */
    fun syncSettings() = SyncSettings(
        syncWifiOnly = getSyncWifiOnly(),
        syncWifiOnlySSIDs = null,
        ignoreVpns = false,
        timeRangePastDays = getTimeRangePastDays(),
        defaultAlarm = getDefaultAlarm(),
        eventColors = getEventColors(),
        groupMethod = getGroupMethod()
    )

    private fun JSONArray.strings() = (0 until length()).map { getString(it) }

    companion object {
        /** DAVx⁵'s: 4 hours. */
        const val DEFAULT_SYNC_INTERVAL = 4 * 3600L

        const val DEFAULT_TIME_RANGE_PAST_DAYS = 90
    }

}
