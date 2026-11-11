/*
 * From DAVx⁵ (davx5-ose 4.5.21, https://github.com/bitfireAT/davx5-ose), adapted for Nixup's calendar and contacts
 * sync. Copyright © All Contributors of DAVx⁵ (synctools/AUTHORS). GNU General Public License 3.0 (synctools/LICENSE).
 */

package dev.nixup.manager.dav.sync

import dev.nixup.manager.dav.Dav
import dev.nixup.manager.dav.accounts.AccountId
import dev.nixup.manager.dav.db.Collection
import dev.nixup.manager.dav.db.Service
import dev.nixup.manager.dav.resource.local.LocalCalendar
import dev.nixup.manager.dav.resource.local.LocalCalendarStore
import dev.nixup.manager.dav.resource.remote.CalDavCollection
import dev.nixup.manager.dav.resource.remote.CalendarQueryFilter
import at.bitfire.synctools.storage.LocalStorageClient
import at.bitfire.synctools.storage.calendar.AndroidCalendarProvider
import net.fortuna.ical4j.model.Component
import java.time.ZonedDateTime
import java.util.logging.Level

/**
 * Sync logic for calendars
 */
class CalendarSyncer(
    accountId: AccountId,
    resync: ResyncType?,
    syncResult: SyncResult,
    settings: SyncSettings,
    calendarStore: LocalCalendarStore = LocalCalendarStore(Dav.context)
) : Syncer<LocalCalendarStore, LocalCalendar>(accountId, resync, syncResult, settings) {

    override val dataStore = calendarStore

    override val serviceType: String
        get() = Service.TYPE_CALDAV


    override fun prepare(client: LocalStorageClient): Boolean {
        // Update colors
        val account = androidAccountManager.getAndroidAccount(accountId)
        val calendarProvider = AndroidCalendarProvider(account, client)
        if (settings.eventColors)
            calendarProvider.provideCss3ColorIndices()
        else
            calendarProvider.removeColorIndices()
        return true
    }

    override fun getDbSyncCollections(serviceId: Long): List<Collection> =
        collectionRepository.getSyncCalendars(serviceId)

    override suspend fun syncCollection(
        client: LocalStorageClient,
        localCollection: LocalCalendar,
        remoteCollectionInfo: Collection
    ) {
        logger.log(
            Level.INFO,
            "Synchronizing calendar #{0}, DB Collection ID: {1}, URL: {2}",
            arrayOf<Any?>(
                localCollection.androidCalendar.id,
                localCollection.dbCollectionId,
                localCollection.androidCalendar.name
            )
        )

        // calculate time range limit
        val limitStart = settings.timeRangePastDays?.let { pastDays ->
            ZonedDateTime.now().minusDays(pastDays.toLong()).toInstant()
        }

        val syncManager = CalendarSyncManager(
            accountId = accountId,
            httpClient = httpClient,
            syncResult = syncResult,
            localCollection = localCollection,
            collectionInfo = remoteCollectionInfo,
            remoteCollection = CalDavCollection(
                httpClient = httpClient,
                url = remoteCollectionInfo.url,
                filter = CalendarQueryFilter(components = listOf(Component.VEVENT), timeRangeStart = limitStart)
            ),
            resync = resync,
            settings = settings
        )
        syncManager.performSync()
    }

}