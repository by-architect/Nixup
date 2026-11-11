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
import at.bitfire.synctools.storage.jtx.JtxCollectionProvider
import at.techbee.jtx.JtxContract
import at.techbee.jtx.JtxContract.asSyncAdapter
import java.util.logging.Logger

class LocalJtxCollectionStore(
    val context: Context
): LocalDataStore<LocalJtxCollection> {

    // (What DAVx⁵ has Hilt inject: Nixup's own, see Dav.)
    val accountRepository = AccountRepository(context)
    val androidAccountManager = AndroidAccountManager(context)

    private val serviceDao get() = Dav.store.serviceRepository

    override val authority: String
        get() = JtxContract.AUTHORITY

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

    override suspend fun create(client: LocalStorageClient, fromCollection: Collection): LocalJtxCollection {
        val service = serviceDao.get(fromCollection.serviceId)
            ?: throw IllegalArgumentException("Couldn't fetch DB service from collection")
        val accountId = accountRepository.getAccountIdFromName(service.accountName)
        val account = androidAccountManager.getAndroidAccount(accountId)

        // If the collection doesn't have a color, use a default color.
        val collectionWithColor =
            if (fromCollection.color != null)
                fromCollection
            else
                fromCollection.copy(color = Dav.DEFAULT_COLOR)

        val values = valuesFromCollection(
            info = collectionWithColor,
            account = account,
            withColor = true
        )

        val jtxCollection = JtxCollectionProvider(account, client).createAndGetCollection(values)
        return LocalJtxCollection(jtxCollection)
    }

    private fun valuesFromCollection(info: Collection, account: Account, withColor: Boolean): ContentValues {
        // (Nixup keeps no principals of the collections' owners: the account's own, for all of them.)
        val owner = serviceDao.get(info.serviceId)?.principal

        return ContentValues().apply {
            put(JtxContract.JtxCollection.SYNC_ID, info.id)
            put(JtxContract.JtxCollection.URL, info.url.toString())
            put(
                JtxContract.JtxCollection.DISPLAYNAME,
                info.displayName ?: extractCollectionName(info.url)
            )
            put(JtxContract.JtxCollection.DESCRIPTION, info.description)
            if (owner != null)
                put(JtxContract.JtxCollection.OWNER, owner.toString())
            else
                Logger.getGlobal().warning("No collection owner given. Will create jtx collection without owner")
            put(JtxContract.JtxCollection.OWNER_DISPLAYNAME, account.name)
            if (withColor && info.color != null)
                put(JtxContract.JtxCollection.COLOR, info.color)
            put(JtxContract.JtxCollection.SUPPORTSVEVENT, info.supportsVEVENT)
            put(JtxContract.JtxCollection.SUPPORTSVJOURNAL, info.supportsVJOURNAL)
            put(JtxContract.JtxCollection.SUPPORTSVTODO, info.supportsVTODO)
            put(JtxContract.JtxCollection.ACCOUNT_NAME, account.name)
            put(JtxContract.JtxCollection.ACCOUNT_TYPE, account.type)
            put(JtxContract.JtxCollection.READONLY, info.forceReadOnly || !info.privWriteContent)
        }
    }

    override fun getAll(accountId: AccountId, client: LocalStorageClient): List<LocalJtxCollection> {
        val account = androidAccountManager.getAndroidAccount(accountId)
        return JtxCollectionProvider(account, client).findCollections().map { jtxCollection ->
            LocalJtxCollection(jtxCollection)
        }
    }

    override fun getByDbCollectionId(
        accountId: AccountId,
        client: LocalStorageClient,
        dbCollectionId: Long
    ): LocalJtxCollection? {
        val account = androidAccountManager.getAndroidAccount(accountId)
        return JtxCollectionProvider(account, client).findFirstCollection(
            "${JtxContract.JtxCollection.SYNC_ID}=?", arrayOf(dbCollectionId.toString())
        )?.let { jtxCollection -> LocalJtxCollection(jtxCollection) }
    }

    override fun update(
        accountId: AccountId,
        client: LocalStorageClient,
        localCollection: LocalJtxCollection,
        fromCollection: Collection
    ) {
        val values = valuesFromCollection(
            info = fromCollection,
            account = localCollection.jtxCollection.account,
            withColor = useManagedCalendarColors(accountId)
        )
        localCollection.jtxCollection.update(values)
    }

    private fun useManagedCalendarColors(accountId: AccountId): Boolean {
        val accountSettings = AccountSettings(context, accountId)
        return accountSettings.getManageCalendarColors()
    }

    override fun updateAccount(oldAccount: Account, newAccount: Account, client: LocalStorageClient?) {
        if (client == null)
            return
        val values = contentValuesOf(JtxContract.JtxCollection.ACCOUNT_NAME to newAccount.name)
        val uri = JtxContract.JtxCollection.CONTENT_URI.asSyncAdapter(oldAccount)
        client.update(uri, values, "${JtxContract.JtxCollection.ACCOUNT_NAME}=?", arrayOf(oldAccount.name))
    }

    override fun delete(localCollection: LocalJtxCollection) {
        localCollection.jtxCollection.delete()
    }

}