/*
 * From DAVx⁵ (davx5-ose 4.5.21, https://github.com/bitfireAT/davx5-ose), adapted for Nixup's calendar and contacts
 * sync. Copyright © All Contributors of DAVx⁵ (synctools/AUTHORS). GNU General Public License 3.0 (synctools/LICENSE).
 */

package dev.nixup.manager.dav.resource.local

import dev.nixup.manager.dav.Dav
import dev.nixup.manager.dav.accounts.AccountRepository
import dev.nixup.manager.dav.settings.AccountSettings
import android.accounts.Account
import android.content.ContentProviderClient
import android.content.ContentValues
import android.content.Context
import androidx.core.content.contentValuesOf
import dev.nixup.manager.dav.accounts.AccountId
import dev.nixup.manager.dav.accounts.AndroidAccountManager
import dev.nixup.manager.dav.db.Collection
import dev.nixup.manager.dav.util.DavUtils.extractCollectionName
import at.bitfire.synctools.storage.LocalStorageClient
import at.bitfire.synctools.storage.TaskProvider
import at.bitfire.synctools.storage.tasks.DmfsTaskList
import at.bitfire.synctools.storage.tasks.DmfsTaskListProvider
import org.dmfs.tasks.contract.TaskContract.TaskListColumns
import org.dmfs.tasks.contract.TaskContract.TaskLists
import org.dmfs.tasks.contract.TaskContract.Tasks
import java.util.logging.Level
import java.util.logging.Logger

class LocalTaskListStore(
    private val providerName: TaskProvider.ProviderName,
    val context: Context
): LocalDataStore<LocalTaskList> {

    // (What DAVx⁵ has Hilt inject: Nixup's own, see Dav.)
    val accountRepository = AccountRepository(context)
    val androidAccountManager = AndroidAccountManager(context)
    val logger: Logger get() = Dav.logger

    private val serviceDao get() = Dav.store.serviceRepository

    override val authority: String
        get() = providerName.authority

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

    override suspend fun create(client: LocalStorageClient, fromCollection: Collection): LocalTaskList {
        val service = serviceDao.get(fromCollection.serviceId)
            ?: throw IllegalArgumentException("Couldn't fetch DB service from collection")
        val accountId = accountRepository.getAccountIdFromName(service.accountName)
        val account = androidAccountManager.getAndroidAccount(accountId)

        logger.info("Adding local task list: $fromCollection")
        val dmfsTaskList = create(account, client, providerName, fromCollection)
        return LocalTaskList(dmfsTaskList)
    }

    private fun create(
        account: Account,
        client: LocalStorageClient,
        providerName: TaskProvider.ProviderName,
        fromCollection: Collection
    ): DmfsTaskList {
        // If the collection doesn't have a color, use a default color.
        val collectionWithColor = if (fromCollection.color != null)
            fromCollection
        else
            fromCollection.copy(color = Dav.DEFAULT_COLOR)

        val values = valuesFromCollectionInfo(
            info = collectionWithColor,
            withColor = true
        ).apply {
            put(TaskLists.OWNER, account.name)
            put(TaskLists.SYNC_ENABLED, 1)
            put(TaskLists.VISIBLE, 1)
        }
        val dmfsTaskListProvider = DmfsTaskListProvider(account, client, providerName)
        return dmfsTaskListProvider.createAndGetTaskList(values)
    }

    private fun valuesFromCollectionInfo(info: Collection, withColor: Boolean): ContentValues {
        val values = ContentValues(3)
        values.put(TaskLists._SYNC_ID, info.id.toString())
        values.put(TaskLists.LIST_NAME,
            if (info.displayName.isNullOrBlank()) extractCollectionName(info.url) else info.displayName)

        if (withColor && info.color != null)
            values.put(TaskLists.LIST_COLOR, info.color)

        if (info.privWriteContent && !info.forceReadOnly)
            values.put(TaskListColumns.ACCESS_LEVEL, TaskListColumns.ACCESS_LEVEL_OWNER)
        else
            values.put(TaskListColumns.ACCESS_LEVEL, TaskListColumns.ACCESS_LEVEL_READ)

        return values
    }

    override fun getAll(accountId: AccountId, client: LocalStorageClient): List<LocalTaskList> {
        val account = androidAccountManager.getAndroidAccount(accountId)
        return DmfsTaskListProvider(account, client, providerName).findTaskLists()
            .map { taskList -> LocalTaskList(taskList) }
    }

    override fun getByDbCollectionId(
        accountId: AccountId,
        client: LocalStorageClient,
        dbCollectionId: Long
    ): LocalTaskList? {
        val account = androidAccountManager.getAndroidAccount(accountId)
        return DmfsTaskListProvider(account, client, providerName)
            .findFirstTaskList("${TaskLists._SYNC_ID}=?", arrayOf(dbCollectionId.toString()))
            ?.let { taskList -> LocalTaskList(taskList) }
    }

    override fun update(
        accountId: AccountId,
        client: LocalStorageClient,
        localCollection: LocalTaskList,
        fromCollection: Collection
    ) {
        logger.log(Level.FINE, "Updating local task list {0}: {1}", arrayOf(fromCollection.url, fromCollection))
        val values = valuesFromCollectionInfo(
            info = fromCollection,
            withColor = useManagedCalendarColors(accountId)
        )
        localCollection.dmfsTaskList.update(values)
    }

    private fun useManagedCalendarColors(accountId: AccountId): Boolean {
        val accountSettings = AccountSettings(context, accountId)
        return accountSettings.getManageCalendarColors()
    }

    override fun updateAccount(oldAccount: Account, newAccount: Account, client: LocalStorageClient?) {
        if (client == null)
            return
        val values = contentValuesOf(Tasks.ACCOUNT_NAME to newAccount.name)
        val uri = Tasks.getContentUri(providerName.authority)
        client.update(uri, values, "${Tasks.ACCOUNT_NAME}=?", arrayOf(oldAccount.name))
    }

    override fun delete(localCollection: LocalTaskList) {
        localCollection.dmfsTaskList.delete()
    }

}