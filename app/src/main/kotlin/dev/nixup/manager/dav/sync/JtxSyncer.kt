/*
 * From DAVx⁵ (davx5-ose 4.5.21, https://github.com/bitfireAT/davx5-ose), adapted for Nixup's calendar and contacts
 * sync. Copyright © All Contributors of DAVx⁵ (synctools/AUTHORS). GNU General Public License 3.0 (synctools/LICENSE).
 */

package dev.nixup.manager.dav.sync

import dev.nixup.manager.dav.Dav
import dev.nixup.manager.dav.accounts.AccountId
import dev.nixup.manager.dav.db.Collection
import dev.nixup.manager.dav.db.Service
import dev.nixup.manager.dav.resource.local.LocalJtxCollection
import dev.nixup.manager.dav.resource.local.LocalJtxCollectionStore
import dev.nixup.manager.dav.resource.remote.CalDavCollection
import dev.nixup.manager.dav.resource.remote.CalendarQueryFilter
import at.bitfire.synctools.storage.LocalStorageClient
import at.bitfire.synctools.storage.TaskProvider

/**
 * Sync logic for jtx board
 */
class JtxSyncer(
    accountId: AccountId,
    resync: ResyncType?,
    syncResult: SyncResult,
    settings: SyncSettings,
    localJtxCollectionStore: LocalJtxCollectionStore = LocalJtxCollectionStore(Dav.context)
) : Syncer<LocalJtxCollectionStore, LocalJtxCollection>(accountId, resync, syncResult, settings) {

    override val dataStore = localJtxCollectionStore

    override val serviceType: String
        get() = Service.TYPE_CALDAV


    override fun prepare(client: LocalStorageClient): Boolean {
        // check whether jtx Board is new enough
        try {
            TaskProvider.checkVersion(context, TaskProvider.ProviderName.JtxBoard)
        } catch (e: TaskProvider.ProviderTooOldException) {
            TasksAppManager(context).notifyProviderTooOld(e, accountId)
            syncResult.hardError = true
            return false // Don't sync
        }

        // make sure account can be seen by jtx board
        androidAccountManager.ensureAccountVisibility(accountId, TaskProvider.ProviderName.JtxBoard.packageName)

        return true
    }

    override fun getDbSyncCollections(serviceId: Long): List<Collection> =
        collectionRepository.getSyncJtxCollections(serviceId)

    override suspend fun syncCollection(
        client: LocalStorageClient,
        localCollection: LocalJtxCollection,
        remoteCollectionInfo: Collection
    ) {
        logger.info("Synchronizing jtx collection $localCollection")

        val syncManager = JtxSyncManager(
            accountId = accountId,
            httpClient = httpClient,
            syncResult = syncResult,
            localCollection = localCollection,
            collectionInfo = remoteCollectionInfo,
            remoteCollection = CalDavCollection(
                httpClient = httpClient,
                url = remoteCollectionInfo.url,
                filter = CalendarQueryFilter(components = buildList {
                    if (localCollection.supportsVTODO) add("VTODO")
                    if (localCollection.supportsVJOURNAL) add("VJOURNAL")
                })
            ),
            resync = resync,
            settings = settings
        )
        syncManager.performSync()
    }

}