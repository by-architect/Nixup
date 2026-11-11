/*
 * From DAVx⁵ (davx5-ose 4.5.21, https://github.com/bitfireAT/davx5-ose), adapted for Nixup's calendar and contacts
 * sync. Copyright © All Contributors of DAVx⁵ (synctools/AUTHORS). GNU General Public License 3.0 (synctools/LICENSE).
 */

package dev.nixup.manager.dav.resource.local

import android.accounts.Account
import android.accounts.AccountManager
import dev.nixup.manager.dav.Dav
import dev.nixup.manager.dav.accounts.AccountId
import at.bitfire.synctools.util.setAndVerifyUserData

/**
 * Deals with reading and writing user data of an address book account.
 */
class AddressBookAccountProperties(
    private val accountManager: AccountManager
) {
    private val mainAccountType = Dav.ACCOUNT_TYPE
    private val addressBookAccountType = Dav.ACCOUNT_TYPE_ADDRESS_BOOK


    /**
     * Get [AccountId] of the app account that the given address book account belongs to.
     */
    fun getAppAccount(account: Account): AccountId? {
        requireAddressBookAccount(account)

        val ownerName = accountManager.getUserData(account, USER_DATA_ACCOUNT_NAME)
        val ownerType = accountManager.getUserData(account, USER_DATA_ACCOUNT_TYPE)

        return if (ownerName != null && ownerType == mainAccountType) {
            Account(ownerName, ownerType)
        } else {
            null
        }
    }

    /**
     * Set [AccountId] of the app account that the given address book account belongs to.
     */
    fun setAppAccount(account: Account, owner: AccountId) {
        requireAddressBookAccount(account)

        accountManager.setAndVerifyUserData(account, USER_DATA_ACCOUNT_NAME, owner.name)
        accountManager.setAndVerifyUserData(account, USER_DATA_ACCOUNT_TYPE, owner.type)
    }

    /**
     * Get the collection ID for the given address book account.
     */
    fun getCollectionId(account: Account): Long? {
        requireAddressBookAccount(account)

        return accountManager.getUserData(account, USER_DATA_COLLECTION_ID)?.toLongOrNull()
    }

    /**
     * Set the collection ID for the given address book account.
     */
    fun setCollectionId(account: Account, collectionId: Long?) {
        requireAddressBookAccount(account)

        accountManager.setAndVerifyUserData(account, USER_DATA_COLLECTION_ID, collectionId?.toString())
    }

    private fun requireAddressBookAccount(account: Account) {
        require(account.type == addressBookAccountType) { "Account.type needs to be $addressBookAccountType" }
    }


    companion object {
        private const val USER_DATA_ACCOUNT_NAME = "account_name"
        private const val USER_DATA_ACCOUNT_TYPE = "account_type"

        /**
         * ID of the corresponding database [dev.nixup.manager.dav.db.Collection].
         *
         * User data of the address book account (Long).
         */
        private const val USER_DATA_COLLECTION_ID = "collection_id"
    }
}
