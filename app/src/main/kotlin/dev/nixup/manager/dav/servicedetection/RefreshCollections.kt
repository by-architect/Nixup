/*
 * Adapted from DAVx⁵ (davx5-ose 4.5.21, GNU GPL 3.0): its login (DavResourceFinder, then the services written as
 * AccountDetailsModel does) and its RefreshCollectionsWorker, which DAVx⁵ runs when the user asks; Nixup runs them
 * itself (see [refresh]).
 */

package dev.nixup.manager.dav.servicedetection

import android.accounts.Account
import dev.nixup.manager.dav.Dav
import dev.nixup.manager.dav.db.HomeSet
import dev.nixup.manager.dav.db.Service
import dev.nixup.manager.dav.settings.AccountSettings
import io.ktor.client.HttpClient
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level

/**
 * What the server has of an account, kept up to date: its services (found from the config's address as DAVx⁵'s login
 * finds them: the address itself, its well-known addresses), their home sets and collections (as DAVx⁵'s "refresh"
 * finds them), and which collections are synced, as the config says (DavCollectionRepository.applySelection).
 */
object RefreshCollections {

    /** How long a lookup holds: the collections are looked up again at the first sync after it. */
    const val REFRESH_INTERVAL = 60 * 60 * 1000L

    /** How long a lookup holds even when one is asked for. */
    const val FORCED_INTERVAL = 60 * 1000L

    private val locks = ConcurrentHashMap<String, Mutex>()

    /** The server found nothing to sync for the account ([message] says what). */
    class NothingFound(message: String) : Exception(message)

    /** The server refused the user name or password while its services were looked for. */
    class LoginRefused(message: String) : Exception(message)

    /**
     * Looks up [account]'s collections on its server when [force]d (unless it did in the last minute), when they were never
     * looked up from its address and user name as the config gives them now, or when [REFRESH_INTERVAL] has passed; else
     * nothing. One lookup at a
     * time for an account (its syncs of calendars, contacts and tasks may run at once). Returns whether it looked.
     *
     * @throws LoginRefused when the server refuses the user name or password while looking for the services (and
     *   UnauthorizedException when it does after)
     * @throws NothingFound when the address has no CalDAV nor CardDAV service for the user
     */
    suspend fun refresh(account: Account, settings: AccountSettings, httpClient: HttpClient, force: Boolean): Boolean =
        locks.getOrPut(account.name) { Mutex() }.withLock {
            val store = Dav.store
            val origin = origin(settings)
            val services = store.serviceRepository.getByAccount(account.name)
            val found = store.origin(account.name) == origin && services.isNotEmpty()
            val age = services.minOfOrNull { System.currentTimeMillis() - store.lastRefresh(it.id) } ?: Long.MAX_VALUE
            val stale = services.any { System.currentTimeMillis() - store.lastRefresh(it.id) > REFRESH_INTERVAL }
            // (The syncs of calendars, contacts and tasks a build asks for start together: one lookup for them.)
            if (found && ((!force && !stale) || age < FORCED_INTERVAL))
                return@withLock false

            if (!found)
                findServices(account, settings, httpClient)

            for (service in store.serviceRepository.getByAccount(account.name))
                refreshService(service, settings, httpClient)
            store.setOrigin(account.name, origin)
            true
        }

    /**
     * The address and user name the services are found from, and the account's adding: when one changes, they are
     * looked for again.
     */
    private fun origin(settings: AccountSettings) = "${settings.baseUrl}|${settings.credentials().username}|${settings.added}"

    /**
     * Finds the account's services from its address, as DAVx⁵'s login does, and writes them in place of those known
     * (from another address), with their principals, home sets, and the collections found at the address itself.
     */
    private suspend fun findServices(account: Account, settings: AccountSettings, httpClient: HttpClient) {
        val logger = Dav.logger
        val base = settings.baseUrl ?: throw NothingFound("the account has no server address")
        logger.info("Looking for the services of ${account.name} at $base")
        val configuration = DavResourceFinder(URI(base), settings.credentials(), httpClient, logger).findInitialConfiguration()
        if (configuration.calDAV == null && configuration.cardDAV == null) {
            if (configuration.encountered401)
                throw LoginRefused("$base refused the user name or password")
            throw NothingFound("$base has no CalDAV nor CardDAV service for the user (no calendars nor address books found)")
        }

        val repository = Dav.store.serviceRepository
        for ((type, info) in listOf(Service.TYPE_CARDDAV to configuration.cardDAV, Service.TYPE_CALDAV to configuration.calDAV)) {
            // (Services found from another address, or one that is not there any more, go with what is theirs.)
            repository.deleteByAccountAndType(account, type)
            if (info == null)
                continue
            val service = repository.insertOrUpdate(account, type, info.principal)
            for (homeSet in info.homeSets)
                Dav.store.homeSetRepository.insertOrUpdateByUrl(HomeSet(0, service.id, true, homeSet))
            for (collection in info.collections.values)
                if (ServiceDetectionUtils.isUsableCollection(service, collection))
                    Dav.store.collectionRepository.insertOrUpdateByUrlRememberSync(collection.copy(serviceId = service.id))
        }
    }

    /** DAVx⁵'s RefreshCollectionsWorker for one service, then the config's choice of the collections to sync. */
    private suspend fun refreshService(service: Service, settings: AccountSettings, httpClient: HttpClient) {
        val logger = Dav.logger
        logger.log(Level.INFO, "Refreshing {0} collections of service #{1}", arrayOf(service.type, service))

        // refresh home set list (from principal url)
        service.principal?.let { principalUrl ->
            logger.fine("Querying principal $principalUrl for home sets")
            ServiceRefresher(service, httpClient).discoverHomesets(principalUrl)
        }

        // refresh home sets and their member collections
        HomeSetRefresher(service, httpClient).refreshHomesetsAndTheirCollections()

        // also refresh collections without a home set
        CollectionsWithoutHomeSetRefresher(service, httpClient).refreshCollectionsWithoutHomeSet()

        // (Nixup) which of them sync, as the config says
        Dav.store.collectionRepository.applySelection(service.id, settings)
        Dav.store.setRefreshed(service.id)
    }

}
