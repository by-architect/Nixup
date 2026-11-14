/*
 * From DAVx⁵ (davx5-ose 4.5.21, https://github.com/bitfireAT/davx5-ose), adapted for Nixup's calendar and contacts
 * sync. Copyright © All Contributors of DAVx⁵ (synctools/AUTHORS). GNU General Public License 3.0 (synctools/LICENSE).
 */

package dev.nixup.manager.dav.sync

import dev.nixup.manager.dav.Dav
import dev.nixup.manager.dav.accounts.AccountId
import dev.nixup.manager.dav.db.Collection
import dev.nixup.manager.dav.db.Service
import dev.nixup.manager.dav.resource.local.LocalTaskList
import dev.nixup.manager.dav.resource.local.LocalTaskListStore
import dev.nixup.manager.dav.resource.remote.CalDavCollection
import dev.nixup.manager.dav.resource.remote.CalendarQueryFilter
import at.bitfire.synctools.storage.LocalStorageClient
import at.bitfire.synctools.storage.TaskProvider
import java.util.logging.Level

/**
 * Sync logic for tasks in CalDAV collections ({@code VTODO}).
 */
class TaskSyncer(
    accountId: AccountId,
    val providerName: TaskProvider.ProviderName,
    resync: ResyncType?,
    syncResult: SyncResult,
    settings: SyncSettings,
) : Syncer<LocalTaskListStore, LocalTaskList>(accountId, resync, syncResult, settings) {

    override val dataStore = LocalTaskListStore(providerName, Dav.context)

    override val serviceType: String
        get() = Service.TYPE_CALDAV


    override fun prepare(client: LocalStorageClient): Boolean {
        // Don't sync if task provider is too old
        try {
            TaskProvider.checkVersion(context, providerName)
        } catch (e: TaskProvider.ProviderTooOldException) {
            TasksAppManager(context).notifyProviderTooOld(e, accountId)
            syncResult.hardError = true
            return false // Don't sync
        }

        // make sure account can be seen by task provider
        androidAccountManager.ensureAccountVisibility(accountId, providerName.packageName)

        return true
    }

    override fun getDbSyncCollections(serviceId: Long): List<Collection> =
        collectionRepository.getSyncTaskLists(serviceId)

    override suspend fun syncCollection(
        client: LocalStorageClient,
        localCollection: LocalTaskList,
        remoteCollectionInfo: Collection
    ) {
        logger.log(
            Level.INFO,
            "Synchronizing task list {0} with database collection ID: {1}",
            arrayOf(localCollection.dmfsTaskList.id, localCollection.dbCollectionId)
        )

        val syncManager = TasksSyncManager(
            accountId = accountId,
            httpClient = httpClient,
            syncResult = syncResult,
            localCollection = localCollection,
            collectionInfo = remoteCollectionInfo,
            remoteCollection = CalDavCollection(
                httpClient = httpClient,
                url = remoteCollectionInfo.url,
                filter = CalendarQueryFilter(components = listOf("VTODO"))
            ),
            resync = resync,
            settings = settings
        )
        syncManager.performSync()
    }

}