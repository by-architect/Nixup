/*
 * From DAVx⁵ (davx5-ose 4.5.21, https://github.com/bitfireAT/davx5-ose), adapted for Nixup's calendar and contacts
 * sync. Copyright © All Contributors of DAVx⁵ (synctools/AUTHORS). GNU General Public License 3.0 (synctools/LICENSE).
 */

package dev.nixup.manager.dav.sync

import android.accounts.AccountManager
import android.provider.ContactsContract
import dev.nixup.manager.dav.Dav
import dev.nixup.manager.dav.accounts.AccountId
import dev.nixup.manager.dav.db.Collection
import dev.nixup.manager.dav.db.Service
import dev.nixup.manager.dav.resource.local.LocalAddressBook
import dev.nixup.manager.dav.resource.local.LocalAddressBookStore
import dev.nixup.manager.dav.resource.remote.CardDavCollection
import at.bitfire.synctools.storage.LocalStorageClient
import at.bitfire.synctools.storage.contacts.AddressContract.asSyncAdapter
import at.bitfire.synctools.util.setAndVerifyUserData
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.util.logging.Level

/**
 * Sync logic for address books
 */
class AddressBookSyncer(
    accountId: AccountId,
    resync: ResyncType?,
    val syncFrameworkUpload: Boolean,
    syncResult: SyncResult,
    settings: SyncSettings,
    addressBookStore: LocalAddressBookStore = LocalAddressBookStore(Dav.context),
    private val ioDispatcher: CoroutineDispatcher = Dav.ioDispatcher
) : Syncer<LocalAddressBookStore, LocalAddressBook>(accountId, resync, syncResult, settings) {

    override val dataStore = addressBookStore

    override val serviceType: String
        get() = Service.TYPE_CARDDAV


    override fun getDbSyncCollections(serviceId: Long): List<Collection> =
        collectionRepository.getByServiceAndSync(serviceId)

    override suspend fun syncCollection(
        client: LocalStorageClient,
        localCollection: LocalAddressBook,
        remoteCollectionInfo: Collection
    ) {
        logger.log(Level.INFO, "Synchronizing address book: {0}", arrayOf(localCollection.addressBookAccount.name))
        syncAddressBook(
            accountId = accountId,
            addressBook = localCollection,
            httpClient = httpClient,
            client = client,
            syncResult = syncResult,
            collection = remoteCollectionInfo
        )
    }

    /**
     * Synchronizes an address book.
     *
     * Sync errors of the address book contents are handled (logged, notified, recorded in [syncResult])
     * by [SyncManager] itself. Everything it re-throws – as well as exceptions of
     * [handleGroupMethodChange] – is passed on to [invoke], which classifies it.
     *
     * @param addressBook       local address book
     * @param httpClient        HTTP client to use for network requests
     * @param client            [LocalStorageClient] to access android contacts
     * @param syncResult        stores hard and soft sync errors
     * @param collection        the database collection associated with this address book
     */
    private suspend fun syncAddressBook(
        accountId: AccountId,
        addressBook: LocalAddressBook,
        httpClient: HttpClient,
        client: LocalStorageClient,
        syncResult: SyncResult,
        collection: Collection
    ) {
        handleGroupMethodChange(addressBook, client)

        val syncManager = ContactsSyncManager(
            accountId = accountId,
            httpClient = httpClient,
            syncResult = syncResult,
            client = client,
            localCollection = addressBook,
            collectionInfo = collection,
            remoteCollection = CardDavCollection(httpClient, collection.url),
            resync = resync,
            syncFrameworkUpload = syncFrameworkUpload,
            settings = settings
        )
        syncManager.performSync()

        logger.info("Contacts sync complete")
    }

    private suspend fun handleGroupMethodChange(
        addressBook: LocalAddressBook,
        client: LocalStorageClient
    ) {
        withContext(ioDispatcher) {
            handleGroupMethodChangeBlocking(addressBook, client)
        }
    }

    private fun handleGroupMethodChangeBlocking(
        addressBook: LocalAddressBook,
        client: LocalStorageClient
    ) {
        val groupMethod = settings.groupMethod.name

        val accountManager = AccountManager.get(context)
        accountManager.getUserData(addressBook.addressBookAccount, PREVIOUS_GROUP_METHOD)?.let { previousGroupMethod ->
            if (previousGroupMethod != groupMethod) {
                logger.info("Group method changed, deleting all local contacts/groups")

                // delete all local contacts and groups so that they will be downloaded again
                client.delete(
                    ContactsContract.RawContacts.CONTENT_URI.asSyncAdapter(addressBook.addressBookAccount),
                    null,
                    null
                )
                client.delete(
                    ContactsContract.Groups.CONTENT_URI.asSyncAdapter(addressBook.addressBookAccount),
                    null,
                    null
                )

                // reset sync state
                addressBook.syncState = null
            }
        }
        accountManager.setAndVerifyUserData(addressBook.addressBookAccount, PREVIOUS_GROUP_METHOD, groupMethod)
    }


    companion object {

        const val PREVIOUS_GROUP_METHOD = "previous_group_method"

    }

}