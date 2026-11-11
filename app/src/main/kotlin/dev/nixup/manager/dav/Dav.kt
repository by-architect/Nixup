package dev.nixup.manager.dav

import android.content.Context
import dev.nixup.manager.dav.db.DavStore
import dev.nixup.manager.dav.network.HttpClientBuilder
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import java.io.File
import java.util.logging.Logger

/**
 * Nixup's CalDAV/CardDAV sync (services.caldav, nix/caldav.nix): DAVx⁵'s sync (davx5-ose 4.5.21, GNU GPL 3.0), run by
 * Nixup's app itself for the accounts of the config, with no DAVx⁵ app. The code under this package is DAVx⁵'s, kept
 * as close to it as it goes (the same files, classes and logic, so that DAVx⁵'s updates can be taken over); what
 * DAVx⁵ has Hilt hand over (its database, settings, HTTP client, WorkManager) is this object and the classes next to
 * it, made for Nixup:
 *  - the accounts are Android's, of Nixup's own account type: the build adds them (as the app, see the engine's
 *    Caldav), with the server's address, the user name and the config's settings in their user data, and the password
 *    as their password. There is no login screen, nor any setting in the app: each build sets them as the config says;
 *  - what the server has (its CalDAV and CardDAV services, home sets and collections) is in [DavStore], a file in the
 *    app's folder, in place of DAVx⁵'s database;
 *  - Android's sync framework runs the syncs (its sync adapters, periodic syncs and syncs on changes), each in the
 *    sync adapter's own thread, as DAVx⁵ did before WorkManager (see SyncAdapterServices).
 */
object Dav {
    /** The main accounts (one for each account of the config), and the accounts of their address books. */
    const val ACCOUNT_TYPE = "dev.nixup.manager.dav"
    const val ACCOUNT_TYPE_ADDRESS_BOOK = "dev.nixup.manager.dav.address_book"

    /** A main account's settings from the config (JSON, see AccountSettings), and the config's name for it. */
    const val KEY_CONFIG = "nixup_config"
    const val KEY_NAME = "nixup_name"
    /** When the build added it: one removed and added again (by the same name) is another to the sync. */
    const val KEY_ADDED = "nixup_added"

    /** A sync's extra (from a build): the collections are looked up on the server first, whenever they were last. */
    const val EXTRA_REFRESH = "nixup_refresh"

    /** The calendars' color where the server gives none (DAVx⁵'s green). */
    const val DEFAULT_COLOR = 0xFF8BC34A.toInt()

    @Volatile
    private var appContext: Context? = null

    /** The app's context: set by what runs the sync (the sync adapters), before anything here is used. */
    val context: Context get() = appContext ?: throw IllegalStateException("Dav.init was not called")

    fun init(context: Context) {
        if (appContext == null) appContext = context.applicationContext
    }

    val logger: Logger = Logger.getLogger("dev.nixup.manager.dav")

    val ioDispatcher: CoroutineDispatcher = Dispatchers.IO

    /** How many multigets run at once (DAVx⁵'s SyncModule). */
    val syncMultigetSemaphore = Semaphore(Runtime.getRuntime().availableProcessors().coerceIn(2, 8))

    val store: DavStore by lazy { DavStore(File(context.filesDir, "dav/store.json")) }

    val productIds: ProductIds by lazy { ProductIds(appContext) }

    fun httpClientBuilder() = HttpClientBuilder(productIds, logger)
}
