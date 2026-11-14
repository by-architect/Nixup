/*
 * Adapted from DAVx⁵ (davx5-ose 4.5.21, GNU GPL 3.0): its AccountId, AndroidAccountManager, AccountRepository and
 * InvalidAccountException, for Nixup's accounts, which are Android's accounts alone.
 */

package dev.nixup.manager.dav.accounts

import android.accounts.Account
import android.accounts.AccountManager
import android.content.Context
import dev.nixup.manager.dav.Dav
import java.util.logging.Logger

/**
 * An account of the sync: DAVx⁵ keeps its own ids for accounts (and moves to its database from Android's); Nixup's are
 * Android's accounts of [Dav.ACCOUNT_TYPE] themselves.
 */
typealias AccountId = Account

/** The account (or its settings) is not there (any more): removed by a build while it synced. */
class InvalidAccountException(account: Account) : Exception("Invalid account: $account")

/** Android's accounts behind the sync's own (the same, here). */
class AndroidAccountManager(private val context: Context) {

    private val logger: Logger get() = Dav.logger

    fun getAndroidAccount(accountId: AccountId): Account = accountId

    fun getAccountId(account: Account): AccountId = account

    /**
     * Lets [packageName] (a tasks app) see [accountId]: it shows the account's task lists only then (Android 8 and later
     * hide an account from other apps).
     */
    fun ensureAccountVisibility(accountId: AccountId, packageName: String) {
        val manager = AccountManager.get(context)
        try {
            if (manager.getAccountVisibility(accountId, packageName) != AccountManager.VISIBILITY_VISIBLE)
                manager.setAccountVisibility(accountId, packageName, AccountManager.VISIBILITY_VISIBLE)
        } catch (e: Exception) {
            logger.warning("Couldn't make $accountId visible to $packageName: $e")
        }
    }

}

/** The main accounts of the sync. */
class AccountRepository(private val context: Context) {

    fun getAll(): Array<Account> = AccountManager.get(context).getAccountsByType(Dav.ACCOUNT_TYPE)

    fun exists(name: String): Boolean = getAll().any { it.name == name }

    fun getAccountIdFromName(name: String): AccountId = Account(name, Dav.ACCOUNT_TYPE)

    fun getAccountName(accountId: AccountId): String = accountId.name

}
