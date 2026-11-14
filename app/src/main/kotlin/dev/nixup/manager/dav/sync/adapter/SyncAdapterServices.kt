/*
 * Adapted from DAVx⁵ (davx5-ose 4.5.21, GNU GPL 3.0): its SyncAdapterServices, SyncAdapterImpl and BaseSyncWorker.
 * DAVx⁵ hands each sync request of Android's sync framework to WorkManager; Nixup runs the sync in the sync
 * adapter's own thread, as DAVx⁵ did before, and has the sync framework retry and schedule (see [SyncRunner]).
 */

package dev.nixup.manager.dav.sync.adapter

import android.accounts.Account
import android.accounts.AccountManager
import android.app.Service
import android.content.AbstractThreadedSyncAdapter
import android.content.ContentProviderClient
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.SyncResult as FrameworkSyncResult
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.provider.CalendarContract
import android.provider.ContactsContract
import at.bitfire.dav4jvm.ktor.exception.UnauthorizedException
import at.bitfire.synctools.storage.TaskProvider
import dev.nixup.manager.dav.Dav
import dev.nixup.manager.dav.accounts.AccountRepository
import dev.nixup.manager.dav.accounts.InvalidAccountException
import dev.nixup.manager.dav.db.Service as DavService
import dev.nixup.manager.dav.resource.local.AddressBookAccountProperties
import dev.nixup.manager.dav.servicedetection.RefreshCollections
import dev.nixup.manager.dav.settings.AccountSettings
import dev.nixup.manager.dav.sync.AddressBookSyncer
import dev.nixup.manager.dav.sync.CalendarSyncer
import dev.nixup.manager.dav.sync.JtxSyncer
import dev.nixup.manager.dav.sync.ResyncType
import dev.nixup.manager.dav.sync.SyncDataType
import dev.nixup.manager.dav.sync.SyncNotificationManager
import dev.nixup.manager.dav.sync.SyncResult
import dev.nixup.manager.dav.sync.TaskSyncer
import dev.nixup.manager.dav.sync.TasksAppManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.util.Collections
import java.util.logging.Level

abstract class SyncAdapterService: Service() {

    // create syncAdapter on demand and cache it
    private val syncAdapter by lazy { SyncAdapterImpl(applicationContext) }

    override fun onBind(intent: Intent?) = syncAdapter.syncAdapterBinder

}

// exported sync adapter services; we need a separate class for each authority
class CalendarsSyncAdapterService: SyncAdapterService()
class ContactsSyncAdapterService: SyncAdapterService()
class JtxSyncAdapterService: SyncAdapterService()
class OpenTasksSyncAdapterService: SyncAdapterService()
class TasksOrgSyncAdapterService: SyncAdapterService()

/**
 * Entry point for the Sync Adapter Framework: runs the sync asked for (by a periodic sync, a change in the calendar or
 * contacts, a calendar app's refresh, or a build), in its thread, and tells the framework how it went.
 */
class SyncAdapterImpl(context: Context) : AbstractThreadedSyncAdapter(context, /* autoInitialize = */ true) {

    override fun onPerformSync(
        accountOrAddressBookAccount: Account,
        extras: Bundle,
        authority: String,
        provider: ContentProviderClient,
        syncResult: FrameworkSyncResult
    ) {
        try {
            /* When the sync framework wants to interrupt the sync, it calls onSyncCanceled, which
            interrupts the thread in the default implementation. runBlocking cancels its
            CoroutineScope when the thread is interrupted. So we can just rely on that. */
            runBlocking {
                SyncRunner(context).sync(accountOrAddressBookAccount, extras, authority, syncResult)
            }
        } catch (e: Throwable) {
            Dav.logger.log(Level.WARNING, "Sync of $accountOrAddressBookAccount/$authority ended", e)
        } finally {
            /* Sync framework bug: ContentResolver.isSyncPending() can get stuck returning true forever
            after a sync, starting with Android 14: https://issuetracker.google.com/issues/320542002.
            DAVx⁵ clears the "pending" flag by cancelling the sync. */
            if (Build.VERSION.SDK_INT >= 34)
                ContentResolver.cancelSync(accountOrAddressBookAccount, authority)
        }
    }

    override fun onSecurityException(account: Account, extras: Bundle, authority: String, syncResult: FrameworkSyncResult) {
        Dav.logger.warning("Security exception for $account/$authority")
    }

}

/**
 * One sync of an account and an authority: what DAVx⁵'s SyncAdapterImpl and BaseSyncWorker do, with Nixup's lookup of
 * the collections ([RefreshCollections]) first.
 */
class SyncRunner(private val context: Context) {

    private val logger get() = Dav.logger

    init {
        Dav.init(context)
    }

    suspend fun sync(accountOrAddressBookAccount: Account, extras: Bundle, authority: String, frameworkResult: FrameworkSyncResult) {
        val upload = extras.containsKey(ContentResolver.SYNC_EXTRAS_UPLOAD)
        val manual = extras.getBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, false)
        val forceRefresh = extras.getBoolean(Dav.EXTRA_REFRESH, false)
        logger.info("Sync request via sync framework for $accountOrAddressBookAccount $authority (upload=$upload, manual=$manual)")

        val dataType = try {
            SyncDataType.fromAuthority(authority)
        } catch (e: IllegalArgumentException) {
            logger.warning("Not syncing unknown authority $authority")
            return
        }

        // The accounts that are gone take what is theirs with them (DAVx⁵'s AccountsCleanupWorker).
        cleanUp()

        // If we should sync an address book account: find the main account
        val account = mainAccount(accountOrAddressBookAccount) ?: return

        val settings = try {
            AccountSettings(context, account)
        } catch (e: InvalidAccountException) {
            logger.log(Level.WARNING, "Account doesn't exist anymore", e)
            return
        }

        if (manual)
            logger.info("Manual sync, skipping network checks")
        else {
            if (!internetAvailable()) {
                logger.info("Sync started without Internet connection. Aborting.")
                return
            }
            if (settings.getSyncWifiOnly() && !wifiAvailable()) {
                logger.info("WiFi conditions not met. Won't run sync.")
                return
            }
        }

        // One sync of a data type of an account at a time.
        val syncTag = "sync-$dataType ${account.type}/${account.name}"
        if (!runningSyncs.add(syncTag)) {
            logger.info("There's already another sync running for $syncTag, skipping")
            return
        }
        val notifications = SyncNotificationManager(account, context)
        try {
            // Nixup: what the server has, looked up as DAVx⁵ does when asked to; and which collections sync.
            val refreshed = try {
                Dav.httpClientBuilder().fromAccount(settings).build().use { httpClient ->
                    RefreshCollections.refresh(account, settings, httpClient, force = forceRefresh || manual)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lookupFailed(e, account, dataType, notifications, frameworkResult)
                if (Dav.store.serviceRepository.getByAccount(account.name).isEmpty())
                    return
                false
            }
            for (service in Dav.store.serviceRepository.getByAccount(account.name))
                Dav.store.collectionRepository.applySelection(service.id, settings)

            // The address books are Android accounts of their own, which must be there before their contacts sync: made
            // (and removed) after each lookup, and when they are not as the synced address books are, whatever is synced now.
            if (dataType != SyncDataType.CONTACTS && (refreshed || !addressBooksAsSynced(account)))
                updateAddressBooks(account, settings)

            val syncResult = SyncResult()
            val syncSettings = settings.syncSettings()
            // A setting that changes what the events are on the phone (how far back, a default reminder, their colors)
            // has them all downloaded again, as DAVx⁵ does when one of its settings changes.
            val eventsMark = "${syncSettings.timeRangePastDays}|${syncSettings.defaultAlarm}|${syncSettings.eventColors}"
            val resync: ResyncType? = if (dataType == SyncDataType.EVENTS && Dav.store.mark(account.name, MARK_EVENTS).let { it != null && it != eventsMark }) {
                logger.info("Events settings changed: downloading all events again")
                ResyncType.RESYNC_ENTRIES
            } else null
            when (dataType) {
                SyncDataType.CONTACTS ->
                    AddressBookSyncer(account, resync, upload, syncResult, syncSettings).use { it() }
                SyncDataType.EVENTS ->
                    CalendarSyncer(account, resync, syncResult, syncSettings).use { it() }
                SyncDataType.TASKS -> {
                    val provider = TasksAppManager(context).currentProvider(settings)
                    if (provider?.authority != authority) {
                        // Another tasks app than the one the tasks sync with: no syncs for it (as DAVx⁵'s
                        // AutomaticSyncManager turns them off).
                        logger.info("$authority is not the tasks app to sync with (${provider?.authority}), turning its syncs off")
                        ContentResolver.setIsSyncable(account, authority, 0)
                        return
                    }
                    when (provider) {
                        TaskProvider.ProviderName.JtxBoard ->
                            JtxSyncer(account, resync, syncResult, syncSettings).use { it() }
                        TaskProvider.ProviderName.OpenTasks, TaskProvider.ProviderName.TasksOrg ->
                            TaskSyncer(account, provider, resync, syncResult, syncSettings).use { it() }
                    }
                }
            }

            if (dataType == SyncDataType.EVENTS && !syncResult.hasError)
                Dav.store.setMark(account.name, MARK_EVENTS, eventsMark)

            // As BaseSyncWorker: a soft error is tried again (the sync framework does, after a while), a hard error not.
            if (syncResult.softError) {
                logger.warning("Soft error while syncing: $syncResult")
                frameworkResult.stats.numIoExceptions++
                if (syncResult.delayUntil > 0)
                    frameworkResult.delayUntil = syncResult.delayUntil
            } else {
                notifications.dismissRetriesFailed(dataType)
                if (syncResult.hardError) {
                    logger.warning("Hard error while syncing: $syncResult")
                    frameworkResult.databaseError = true
                } else
                    logger.info("Sync succeeded: $syncResult")
            }

        } finally {
            runningSyncs -= syncTag
        }
    }

    /** The main account of [account], an address book's; [account] itself for a main account; null: none (any more). */
    private fun mainAccount(account: Account): Account? {
        if (account.type != Dav.ACCOUNT_TYPE_ADDRESS_BOOK)
            return account
        val main = AddressBookAccountProperties(AccountManager.get(context)).getAppAccount(account)
        if (main == null || !AccountRepository(context).exists(main.name)) {
            logger.warning("Address book account $account has no main account (any more), removing it")
            AccountManager.get(context).removeAccountExplicitly(account)
            return null
        }
        return main
    }

    /** Forgets what is known of the accounts that are gone, and removes their address books. */
    private fun cleanUp() {
        val manager = AccountManager.get(context)
        val accounts = manager.getAccountsByType(Dav.ACCOUNT_TYPE).map { it.name }.toSet()
        Dav.store.cleanUp(accounts)
        val properties = AddressBookAccountProperties(manager)
        for (book in manager.getAccountsByType(Dav.ACCOUNT_TYPE_ADDRESS_BOOK)) {
            val main = properties.getAppAccount(book)
            if (main == null || main.name !in accounts) {
                logger.info("Removing address book account $book of a removed account")
                manager.removeAccountExplicitly(book)
            }
        }
    }

    /** Whether [account]'s address books' accounts are those of its synced address books. */
    private fun addressBooksAsSynced(account: Account): Boolean {
        val service = Dav.store.serviceRepository.getByAccountIdAndType(account, DavService.TYPE_CARDDAV)
        val synced = service?.let { Dav.store.collectionRepository.getByServiceAndSync(it.id).map { c -> c.id } }.orEmpty().toSet()
        val manager = AccountManager.get(context)
        val properties = AddressBookAccountProperties(manager)
        val there = manager.getAccountsByType(Dav.ACCOUNT_TYPE_ADDRESS_BOOK)
            .filter { properties.getAppAccount(it) == account }.mapNotNull { properties.getCollectionId(it) }.toSet()
        return synced == there
    }

    /** Makes and removes the address book accounts of [account] as its synced address books are. */
    private suspend fun updateAddressBooks(account: Account, settings: AccountSettings) {
        if (Dav.store.serviceRepository.getByAccountIdAndType(account, DavService.TYPE_CARDDAV) == null)
            return
        try {
            // (Android syncs an account it gets by itself: one asked for besides would stop that one, which it runs again.)
            AddressBookSyncer(account, null, false, SyncResult(), settings.syncSettings()).use { syncer ->
                for (created in syncer.updateCollectionsOnly())
                    logger.info("Made the account of address book ${created.addressBookAccount.name}: Android syncs it")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.log(Level.WARNING, "Couldn't update the address book accounts", e)
        }
    }

    /** Tells the user why the collections could not be looked up (and has the sync framework retry a passing error). */
    private fun lookupFailed(e: Exception, account: Account, dataType: SyncDataType, notifications: SyncNotificationManager, frameworkResult: FrameworkSyncResult) {
        logger.log(Level.WARNING, "Couldn't look up the collections of $account", e)
        when (e) {
            is RefreshCollections.LoginRefused, is UnauthorizedException -> {
                notifications.notifyRetriesFailed(dataType,
                    "The server refused the user name or password (${e.message}). Each build sets them from services.caldav.accounts: check its passwordFile, and build again.")
                frameworkResult.stats.numAuthExceptions++
            }
            is RefreshCollections.NothingFound -> {
                notifications.notifyRetriesFailed(dataType, "Nothing to sync: ${e.message}. Check services.caldav.accounts' url.")
                frameworkResult.databaseError = true
            }
            is IOException ->
                frameworkResult.stats.numIoExceptions++
            else -> {
                notifications.notifyRetriesFailed(dataType, "The calendars and address books could not be looked up: ${e.message ?: e.javaClass.simpleName}")
                frameworkResult.stats.numIoExceptions++
            }
        }
    }

    // network checks, as DAVx⁵'s SyncConditions

    private fun internetAvailable(): Boolean {
        val connectivityManager = context.getSystemService(ConnectivityManager::class.java) ?: return true
        @Suppress("DEPRECATION")
        return connectivityManager.allNetworks.any { network ->
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return@any false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }
    }

    private fun wifiAvailable(): Boolean {
        val connectivityManager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        @Suppress("DEPRECATION")
        return connectivityManager.allNetworks.any { network ->
            connectivityManager.getNetworkCapabilities(network)?.let { capabilities ->
                (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) &&
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            } == true
        }
    }

    companion object {
        /** The store's mark of the settings the events were last synced with. */
        private const val MARK_EVENTS = "events"

        /** The syncs running now, by data type and account. */
        private val runningSyncs = Collections.synchronizedSet(HashSet<String>())

        /** The authorities of the main accounts' sync adapters (the contacts' are the address books'). */
        val MAIN_AUTHORITIES = listOf(CalendarContract.AUTHORITY) + TaskProvider.ProviderName.entries.map { it.authority }
    }

}
