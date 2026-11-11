/*
 * Adapted from DAVx⁵ (davx5-ose 4.5.21, GNU GPL 3.0): its AccountAuthenticatorService and
 * AddressBookAuthenticatorService.
 */

package dev.nixup.manager.dav.sync.account

import android.accounts.AbstractAccountAuthenticator
import android.accounts.Account
import android.accounts.AccountAuthenticatorResponse
import android.accounts.AccountManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Bundle

/**
 * Account authenticator for Nixup's CalDAV/CardDAV accounts. They come from the config (services.caldav): Android's
 * "Add account" gets an error that says so.
 */
class AccountAuthenticatorService: Service() {

    private lateinit var accountAuthenticator: AccountAuthenticator

    override fun onCreate() {
        accountAuthenticator = AccountAuthenticator(this)
    }

    override fun onBind(intent: Intent?) =
        accountAuthenticator.iBinder.takeIf { intent?.action == AccountManager.ACTION_AUTHENTICATOR_INTENT }

}

/** The same, for the accounts of the address books (one for each address book that syncs). */
class AddressBookAuthenticatorService: Service() {

    private lateinit var accountAuthenticator: AccountAuthenticator

    override fun onCreate() {
        accountAuthenticator = AccountAuthenticator(this)
    }

    override fun onBind(intent: Intent?) =
        accountAuthenticator.iBinder.takeIf { intent?.action == AccountManager.ACTION_AUTHENTICATOR_INTENT }

}

private class AccountAuthenticator(
    val context: Context
): AbstractAccountAuthenticator(context) {

    override fun addAccount(response: AccountAuthenticatorResponse?, accountType: String?, authTokenType: String?, requiredFeatures: Array<String>?, options: Bundle?) =
        Bundle().apply {
            putParcelable(AccountManager.KEY_ACCOUNT_AUTHENTICATOR_RESPONSE, response)
            putInt(AccountManager.KEY_ERROR_CODE, AccountManager.ERROR_CODE_UNSUPPORTED_OPERATION)
            putString(AccountManager.KEY_ERROR_MESSAGE, "Nixup's calendar and contacts accounts come from the config: services.caldav.accounts")
        }

    override fun editProperties(response: AccountAuthenticatorResponse?, accountType: String?) = null
    override fun getAuthTokenLabel(p0: String?) = null
    override fun confirmCredentials(p0: AccountAuthenticatorResponse?, p1: Account?, p2: Bundle?) = null
    override fun updateCredentials(p0: AccountAuthenticatorResponse?, p1: Account?, p2: String?, p3: Bundle?) = null
    override fun getAuthToken(p0: AccountAuthenticatorResponse?, p1: Account?, p2: String?, p3: Bundle?) = null
    override fun hasFeatures(p0: AccountAuthenticatorResponse?, p1: Account?, p2: Array<out String>?) = null

}
