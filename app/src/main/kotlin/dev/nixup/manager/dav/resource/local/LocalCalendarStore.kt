/*
 * From DAVx⁵ (davx5-ose 4.5.21, https://github.com/bitfireAT/davx5-ose), adapted for Nixup's calendar and contacts
 * sync. Copyright © All Contributors of DAVx⁵ (synctools/AUTHORS). GNU General Public License 3.0 (synctools/LICENSE).
 */

package dev.nixup.manager.dav.resource.local

import dev.nixup.manager.dav.Dav
import dev.nixup.manager.dav.accounts.AccountRepository
import dev.nixup.manager.dav.db.DavServiceRepository
import dev.nixup.manager.dav.settings.AccountSettings
import android.accounts.Account
import android.content.ContentProviderClient
import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract
import android.provider.CalendarContract.Attendees
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Reminders
import androidx.core.content.contentValuesOf
import dev.nixup.manager.dav.accounts.AccountId
import dev.nixup.manager.dav.accounts.AndroidAccountManager
import dev.nixup.manager.dav.db.Collection
import dev.nixup.manager.dav.util.DavUtils.extractCollectionName
import at.bitfire.synctools.storage.LocalStorageClient
import at.bitfire.synctools.storage.calendar.AndroidCalendarProvider
import at.bitfire.synctools.storage.calendar.EventsContract.asSyncAdapter
import java.time.ZoneId
import java.util.logging.Level
import java.util.logging.Logger

class LocalCalendarStore(
    private val context: Context
): LocalDataStore<LocalCalendar> {

    // (What DAVx⁵ has Hilt inject: Nixup's own, see Dav.)
    private val accountRepository = AccountRepository(context)
    private val androidAccountManager = AndroidAccountManager(context)
    private val logger: Logger get() = Dav.logger
    private val serviceRepository: DavServiceRepository get() = Dav.store.serviceRepository

    override val authority: String
        get() = CalendarContract.AUTHORITY

    override fun acquireLocalStorageClient(throwOnMissingPermissions: Boolean): LocalStorageClient? {
        return acquireContentProviderClient(throwOnMissingPermissions)?.let { LocalStorageClient(it) }
    }

    private fun acquireContentProviderClient(throwOnMissingPermissions: Boolean): ContentProviderClient? {
        return try {
            context.contentResolver.acquireContentProviderClient(authority)
        } catch (e: SecurityException) {
            if (throwOnMissingPermissions) {
                throw e
            } else {
                null
            }
        }
    }

    override suspend fun create(client: LocalStorageClient, fromCollection: Collection): LocalCalendar {
        val service = serviceRepository.get(fromCollection.serviceId)
            ?: throw IllegalArgumentException("Couldn't fetch DB service from collection")
        val accountId = accountRepository.getAccountIdFromName(service.accountName)
        val account = androidAccountManager.getAndroidAccount(accountId)

        // If the collection doesn't have a color, use a default color.
        val collectionWithColor =
            if (fromCollection.color != null)
                fromCollection
            else
                fromCollection.copy(color = Dav.DEFAULT_COLOR)

        val values = valuesFromCollectionInfo(
            info = collectionWithColor,
            withColor = true
        ).apply {
            // ACCOUNT_NAME and ACCOUNT_TYPE are required (see docs)! If it's missing, other apps will crash.
            put(Calendars.ACCOUNT_NAME, account.name)
            put(Calendars.ACCOUNT_TYPE, account.type)

            // Email address for scheduling. Used by the calendar provider to determine whether the
            // user is ORGANIZER/ATTENDEE for a certain event.
            put(Calendars.OWNER_ACCOUNT, account.name)

            // flag as visible & syncable at creation, might be changed by user at any time
            put(Calendars.VISIBLE, 1)
            put(Calendars.SYNC_EVENTS, 1)
        }

        logger.info("Adding local calendar: $values")
        val provider = AndroidCalendarProvider(account, client)
        return LocalCalendar(provider.createAndGetCalendar(values))
    }

    override fun getAll(accountId: AccountId, client: LocalStorageClient): List<LocalCalendar> {
        val account = androidAccountManager.getAndroidAccount(accountId)
        return AndroidCalendarProvider(account, client)
            .findCalendars("${Calendars.SYNC_EVENTS}!=0", null)
            .map { androidCalendar -> LocalCalendar(androidCalendar) }
    }

    override fun getByDbCollectionId(
        accountId: AccountId,
        client: LocalStorageClient,
        dbCollectionId: Long
    ): LocalCalendar? {
        val account = androidAccountManager.getAndroidAccount(accountId)
        return AndroidCalendarProvider(account, client)
            .findFirstCalendar("${Calendars._SYNC_ID}=?", arrayOf(dbCollectionId.toString()))
            ?.let { androidCalendar -> LocalCalendar(androidCalendar) }
    }

    override fun update(
        accountId: AccountId,
        client: LocalStorageClient,
        localCollection: LocalCalendar,
        fromCollection: Collection
    ) {
        val values = valuesFromCollectionInfo(
            info = fromCollection,
            withColor = useManagedCalendarColors(accountId)
        )

        logger.log(Level.FINE, "Updating local calendar {0}: {1}", arrayOf(fromCollection.url, values))
        val androidCalendar = localCollection.androidCalendar
        val provider = AndroidCalendarProvider(androidCalendar.account, client)
        provider.updateCalendar(androidCalendar.id, values)
    }

    private fun useManagedCalendarColors(accountId: AccountId): Boolean {
        val accountSettings = AccountSettings(context, accountId)
        return accountSettings.getManageCalendarColors()
    }

    private fun valuesFromCollectionInfo(info: Collection, withColor: Boolean): ContentValues {
        val values = contentValuesOf(
            Calendars._SYNC_ID to info.id,
            Calendars.CALENDAR_DISPLAY_NAME to
                    if (info.displayName.isNullOrBlank()) extractCollectionName(info.url) else info.displayName,

            Calendars.ALLOWED_AVAILABILITY to arrayOf(
                Events.AVAILABILITY_BUSY,
                Events.AVAILABILITY_FREE
            ).joinToString(",") { it.toString() },

            Calendars.ALLOWED_ATTENDEE_TYPES to arrayOf(
                Attendees.TYPE_NONE,
                Attendees.TYPE_OPTIONAL,
                Attendees.TYPE_REQUIRED,
                Attendees.TYPE_RESOURCE
            ).joinToString(",") { it.toString() },

            Calendars.ALLOWED_REMINDERS to arrayOf(
                Reminders.METHOD_DEFAULT,
                Reminders.METHOD_ALERT,
                Reminders.METHOD_EMAIL
            ).joinToString(",") { it.toString() },
        )

        // By default, set all new calendars as non-primary. Right now we do not support actually fetching the primary calendar.
        // Google Calendar uses this field to determine whether to read the calendar name together with the account name in TalkBack:
        //   if primary, the calendar name is excluded, and as such, all calendars in the account sound the same.
        values.put(Calendars.IS_PRIMARY, 0)

        if (withColor && info.color != null)
            values.put(Calendars.CALENDAR_COLOR, info.color)

        if (info.privWriteContent && !info.forceReadOnly) {
            values.put(Calendars.CALENDAR_ACCESS_LEVEL, Calendars.CAL_ACCESS_OWNER)
            values.put(Calendars.CAN_MODIFY_TIME_ZONE, 1)
            values.put(Calendars.CAN_ORGANIZER_RESPOND, 1)
        } else
            values.put(Calendars.CALENDAR_ACCESS_LEVEL, Calendars.CAL_ACCESS_READ)

        info.timezoneId?.let { tzId ->
            val validSystemTzId = try {
                ZoneId.of(tzId)
                true
            } catch (_: Exception) {
                false
            }
            values.put(Calendars.CALENDAR_TIME_ZONE, tzId.takeIf { validSystemTzId })
        }

        return values
    }

    override fun updateAccount(oldAccount: Account, newAccount: Account, client: LocalStorageClient?) {
        if (client == null)
            return
        val values = contentValuesOf(
            // Account name to be changed
            Calendars.ACCOUNT_NAME to newAccount.name,
            // Owner account of this calendar to be changed. Used by the calendar
            // provider to determine whether the user is ORGANIZER/ATTENDEE (usually an email address) for a certain event.
            Calendars.OWNER_ACCOUNT to newAccount.name
        )
        val uri = Calendars.CONTENT_URI.asSyncAdapter(oldAccount)
        client.update(uri, values, "${Calendars.ACCOUNT_NAME}=?", arrayOf(oldAccount.name))
    }

    override fun delete(localCollection: LocalCalendar) {
        logger.info("Deleting local calendar: $localCollection")
        localCollection.androidCalendar.delete()
    }

}