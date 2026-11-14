package dev.nixup.manager.dav.db

import android.accounts.Account
import dev.nixup.manager.dav.accounts.AccountId
import dev.nixup.manager.dav.settings.AccountSettings
import dev.nixup.manager.dav.sync.SyncDataType
import io.ktor.http.Url
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * What the servers have, for the sync: each account's services (CalDAV, CardDAV) with their principals, home sets and
 * collections, which DAVx⁵ keeps in its database (Room). Nixup keeps them in one JSON file in the app's folder ([file]),
 * read once and written whole at each change; only the app's process uses it (the build sets the accounts, not this).
 * The repositories below are DAVx⁵'s, with the methods its sync calls.
 */
class DavStore(private val file: File) {

    private var nextId = 1L
    private val services = ArrayList<Service>()
    private val homeSets = ArrayList<HomeSet>()
    private val collections = ArrayList<Collection>()
    /** When each collection's data type was last synced (DAVx⁵'s sync stats), by "collectionId/dataType". */
    private val syncStats = HashMap<String, Long>()
    /** When each service's collections were last looked up on the server. */
    private val refreshed = HashMap<Long, Long>()
    /** Where each account's services were found from (its address and user name then), by account name. */
    private val origins = HashMap<String, String>()
    /** What each account's last syncs were made with (its settings that change what is synced), by account name. */
    private val marks = HashMap<String, MutableMap<String, String>>()

    init {
        load()
    }

    val serviceRepository = DavServiceRepository(this)
    val homeSetRepository = DavHomeSetRepository(this)
    val collectionRepository = DavCollectionRepository(this)
    val syncStatsRepository = DavSyncStatsRepository(this)

    // --- what the repositories use (each one locked on the store)

    @Synchronized
    internal fun <T> read(block: () -> T): T = block()

    @Synchronized
    internal fun <T> write(block: () -> T): T {
        val result = block()
        save()
        return result
    }

    internal fun newId() = nextId++

    internal val allServices: MutableList<Service> get() = services
    internal val allHomeSets: MutableList<HomeSet> get() = homeSets
    internal val allCollections: MutableList<Collection> get() = collections
    internal val allSyncStats: MutableMap<String, Long> get() = syncStats
    internal val allRefreshed: MutableMap<Long, Long> get() = refreshed

    /** When [serviceId]'s collections were last looked up on the server (0: never). */
    fun lastRefresh(serviceId: Long): Long = read { refreshed[serviceId] ?: 0L }

    fun setRefreshed(serviceId: Long, time: Long = System.currentTimeMillis()) = write { refreshed[serviceId] = time }

    /** Where [accountName]'s services were found from (see [origins]), or null: they were not looked for yet. */
    fun origin(accountName: String): String? = read { origins[accountName] }

    fun setOrigin(accountName: String, origin: String?) = write { if (origin != null) origins[accountName] = origin else origins.remove(accountName) }

    /** What [accountName]'s last sync of [key] (a data type) was made with, or null: none was. */
    fun mark(accountName: String, key: String): String? = read { marks[accountName]?.get(key) }

    fun setMark(accountName: String, key: String, value: String) = write { marks.getOrPut(accountName) { HashMap() }[key] = value }

    /** Forgets the services (and what is theirs) of the accounts that are gone. */
    fun cleanUp(accounts: Iterable<String>) = write {
        val names = accounts.toSet()
        val gone = services.filter { it.accountName !in names }.map { it.id }.toSet()
        if (gone.isNotEmpty()) removeServices(gone)
        origins.keys.removeAll { it !in names }
        marks.keys.removeAll { it !in names }
    }

    internal fun removeServices(ids: Set<Long>) {
        val goneCollections = collections.filter { it.serviceId in ids }.map { it.id }.toSet()
        services.removeAll { it.id in ids }
        homeSets.removeAll { it.serviceId in ids }
        collections.removeAll { it.serviceId in ids }
        syncStats.keys.removeAll { key -> key.substringBefore('/').toLongOrNull() in goneCollections }
        ids.forEach { refreshed.remove(it) }
    }

    // --- the file

    private fun load() {
        val json = try {
            file.takeIf { it.isFile }?.readText()?.let { JSONObject(it) }
        } catch (e: Exception) {
            null
        } ?: return
        nextId = json.optLong("nextId", 1)
        json.optJSONArray("services")?.objects()?.forEach { o ->
            services += Service(o.getLong("id"), o.getString("accountName"), o.getString("type"), o.optUrl("principal"))
        }
        json.optJSONArray("homeSets")?.objects()?.forEach { o ->
            o.optUrl("url")?.let { url ->
                homeSets += HomeSet(o.getLong("id"), o.getLong("serviceId"), o.optBoolean("personal"), url, o.optBoolean("privBind", true), o.optStr("displayName"))
            }
        }
        json.optJSONArray("collections")?.objects()?.forEach { o ->
            val url = o.optUrl("url") ?: return@forEach
            collections += Collection(
                id = o.getLong("id"),
                serviceId = o.getLong("serviceId"),
                homeSetId = o.optLongOrNull("homeSetId"),
                type = o.getString("type"),
                url = url,
                privWriteContent = o.optBoolean("privWriteContent", true),
                privUnbind = o.optBoolean("privUnbind", true),
                forceReadOnly = o.optBoolean("forceReadOnly", false),
                displayName = o.optStr("displayName"),
                description = o.optStr("description"),
                color = o.optLongOrNull("color")?.toInt(),
                timezoneId = o.optStr("timezoneId"),
                supportsVEVENT = o.optBooleanOrNull("supportsVEVENT"),
                supportsVTODO = o.optBooleanOrNull("supportsVTODO"),
                supportsVJOURNAL = o.optBooleanOrNull("supportsVJOURNAL"),
                source = o.optUrl("source"),
                sync = o.optBoolean("sync", false)
            )
        }
        json.optJSONObject("syncStats")?.let { stats -> stats.keys().forEach { syncStats[it] = stats.getLong(it) } }
        json.optJSONObject("refreshed")?.let { times -> times.keys().forEach { key -> key.toLongOrNull()?.let { refreshed[it] = times.getLong(key) } } }
        json.optJSONObject("origins")?.let { all -> all.keys().forEach { origins[it] = all.getString(it) } }
        json.optJSONObject("marks")?.let { all ->
            all.keys().forEach { account ->
                val keys = all.getJSONObject(account)
                marks[account] = keys.keys().asSequence().associateWith { keys.getString(it) }.toMutableMap()
            }
        }
    }

    private fun save() {
        val json = JSONObject().put("nextId", nextId)
        json.put("services", JSONArray(services.map { s ->
            JSONObject().put("id", s.id).put("accountName", s.accountName).put("type", s.type).put("principal", s.principal?.toString())
        }))
        json.put("homeSets", JSONArray(homeSets.map { h ->
            JSONObject().put("id", h.id).put("serviceId", h.serviceId).put("personal", h.personal).put("url", h.url.toString())
                .put("privBind", h.privBind).put("displayName", h.displayName)
        }))
        json.put("collections", JSONArray(collections.map { c ->
            JSONObject().put("id", c.id).put("serviceId", c.serviceId).put("homeSetId", c.homeSetId).put("type", c.type)
                .put("url", c.url.toString()).put("privWriteContent", c.privWriteContent).put("privUnbind", c.privUnbind)
                .put("forceReadOnly", c.forceReadOnly).put("displayName", c.displayName).put("description", c.description)
                .put("color", c.color).put("timezoneId", c.timezoneId).put("supportsVEVENT", c.supportsVEVENT)
                .put("supportsVTODO", c.supportsVTODO).put("supportsVJOURNAL", c.supportsVJOURNAL)
                .put("source", c.source?.toString()).put("sync", c.sync)
        }))
        json.put("syncStats", JSONObject(syncStats as Map<*, *>))
        json.put("refreshed", JSONObject(refreshed.mapKeys { it.key.toString() } as Map<*, *>))
        json.put("origins", JSONObject(origins as Map<*, *>))
        json.put("marks", JSONObject(marks.mapValues { JSONObject(it.value as Map<*, *>) } as Map<*, *>))
        file.parentFile?.mkdirs()
        val partial = File(file.path + ".new")
        partial.writeText(json.toString())
        if (!partial.renameTo(file)) throw IllegalStateException("could not write $file")
    }

    private fun JSONArray.objects() = (0 until length()).mapNotNull { optJSONObject(it) }
    private fun JSONObject.optStr(key: String): String? = if (isNull(key)) null else optString(key).ifEmpty { null }
    private fun JSONObject.optUrl(key: String): Url? = optStr(key)?.let { runCatching { Url(it) }.getOrNull() }
    private fun JSONObject.optLongOrNull(key: String): Long? = if (has(key) && !isNull(key)) optLong(key) else null
    private fun JSONObject.optBooleanOrNull(key: String): Boolean? = if (has(key) && !isNull(key)) optBoolean(key) else null

}

/** DAVx⁵'s services: one of each type (CalDAV, CardDAV) at most for an account. */
class DavServiceRepository(private val store: DavStore) {

    fun get(id: Long): Service? = store.read { store.allServices.firstOrNull { it.id == id } }

    fun getBlocking(id: Long): Service? = get(id)

    fun getByAccountIdAndType(accountId: AccountId, @ServiceType type: String): Service? =
        store.read { store.allServices.firstOrNull { it.accountName == accountId.name && it.type == type } }

    fun getByAccount(accountName: String): List<Service> =
        store.read { store.allServices.filter { it.accountName == accountName } }

    /** The service of [type] of [account], with [principal] (null keeps the one known); made when there is none. */
    fun insertOrUpdate(account: Account, @ServiceType type: String, principal: Url?): Service = store.write {
        val index = store.allServices.indexOfFirst { it.accountName == account.name && it.type == type }
        if (index >= 0) {
            val updated = store.allServices[index].let { it.copy(principal = principal ?: it.principal) }
            store.allServices[index] = updated
            updated
        } else
            Service(store.newId(), account.name, type, principal).also { store.allServices += it }
    }

    /** Takes [account]'s service of [type] out, with its home sets and collections (the server has none any more). */
    fun deleteByAccountAndType(account: Account, @ServiceType type: String) = store.write {
        val ids = store.allServices.filter { it.accountName == account.name && it.type == type }.map { it.id }.toSet()
        if (ids.isNotEmpty()) store.removeServices(ids)
    }

}

class DavHomeSetRepository(private val store: DavStore) {

    fun getByService(serviceId: Long): List<HomeSet> = store.read { store.allHomeSets.filter { it.serviceId == serviceId } }

    /** Saves [homeSet] by its service and address (its id is the one there, if any); returns its id. */
    fun insertOrUpdateByUrl(homeSet: HomeSet): Long = store.write {
        val index = store.allHomeSets.indexOfFirst { it.serviceId == homeSet.serviceId && it.url == homeSet.url }
        if (index >= 0) {
            val id = store.allHomeSets[index].id
            store.allHomeSets[index] = homeSet.copy(id = id)
            id
        } else {
            val id = store.newId()
            store.allHomeSets += homeSet.copy(id = id)
            id
        }
    }

    fun delete(homeSet: HomeSet) = store.write {
        store.allHomeSets.removeAll { it.id == homeSet.id }
        // (Its collections stay, without a home set, as Room's SET NULL leaves them.)
        store.allCollections.replaceAll { if (it.homeSetId == homeSet.id) it.copy(homeSetId = null) else it }
    }

}

class DavCollectionRepository(private val store: DavStore) {

    fun get(id: Long): Collection? = store.read { store.allCollections.firstOrNull { it.id == id } }

    fun getAsync(id: Long): Collection? = get(id)

    fun getByService(serviceId: Long): List<Collection> = store.read { store.allCollections.filter { it.serviceId == serviceId } }

    fun getByServiceAndHomeset(serviceId: Long, homeSetId: Long?): List<Collection> =
        store.read { store.allCollections.filter { it.serviceId == serviceId && it.homeSetId == homeSetId } }

    /** The address books of a CardDAV service that are synced. */
    fun getByServiceAndSync(serviceId: Long): List<Collection> =
        store.read { store.allCollections.filter { it.serviceId == serviceId && it.sync } }

    fun getSyncCalendars(serviceId: Long): List<Collection> = store.read {
        store.allCollections.filter { it.serviceId == serviceId && it.sync && it.type == Collection.TYPE_CALENDAR && it.supportsVEVENT != false }
    }

    fun getSyncTaskLists(serviceId: Long): List<Collection> = store.read {
        store.allCollections.filter { it.serviceId == serviceId && it.sync && it.type == Collection.TYPE_CALENDAR && it.supportsVTODO != false }
    }

    fun getSyncJtxCollections(serviceId: Long): List<Collection> = store.read {
        store.allCollections.filter {
            it.serviceId == serviceId && it.sync && it.type == Collection.TYPE_CALENDAR && (it.supportsVTODO != false || it.supportsVJOURNAL != false)
        }
    }

    /**
     * Saves [collection] by its service and address: one found again keeps its id and whether it is synced (which
     * [applySelection] sets from the config after each lookup).
     */
    fun insertOrUpdateByUrlRememberSync(collection: Collection): Long = store.write {
        val index = store.allCollections.indexOfFirst { it.serviceId == collection.serviceId && it.url == collection.url }
        if (index >= 0) {
            val existing = store.allCollections[index]
            store.allCollections[index] = collection.copy(id = existing.id, sync = existing.sync)
            existing.id
        } else {
            val id = store.newId()
            store.allCollections += collection.copy(id = id)
            id
        }
    }

    fun delete(collection: Collection) = store.write {
        store.allCollections.removeAll { it.id == collection.id }
        store.allSyncStats.keys.removeAll { it.substringBefore('/') == collection.id.toString() }
    }

    /**
     * Sets which collections of [serviceId] are synced, as the config says ([settings]): those [AccountSettings.collections]
     * names, else all of them, the personal ones (in the user's own home sets) or none ([AccountSettings.syncCollections]),
     * but never those it excludes (and Nextcloud's "Recently contacted", unless named).
     */
    fun applySelection(serviceId: Long, settings: AccountSettings) = store.write {
        val named = settings.collections
        val excluded = settings.excluded
        val personal = store.allHomeSets.filter { it.serviceId == serviceId && it.personal }.map { it.id }.toSet()
        store.allCollections.replaceAll { collection ->
            if (collection.serviceId != serviceId) return@replaceAll collection
            val sync = when {
                excluded.any { matches(collection, it) } -> false
                named != null -> named.any { matches(collection, it) }
                collection.url.toString().contains(PRESELECT_COLLECTIONS_EXCLUDED) -> false
                settings.syncCollections == "all" -> true
                settings.syncCollections == "personal" -> collection.homeSetId in personal
                else -> false
            }
            if (sync != collection.sync) collection.copy(sync = sync) else collection
        }
    }

    companion object {
        /** DAVx⁵'s default "preselect collections excluded": Nextcloud's "Recently contacted" address book. */
        const val PRESELECT_COLLECTIONS_EXCLUDED = "/z-app-generated--contactsinteraction--recent/"

        /**
         * Whether [collection] is the one [name] names: its name as the server shows it, its address (whole, or its
         * path), or the last part of its path.
         */
        fun matches(collection: Collection, name: String): Boolean {
            val wanted = name.trim()
            if (wanted.isEmpty()) return false
            val url = collection.url.toString()
            val path = collection.url.encodedPath
            val last = collection.url.segments.lastOrNull()
            return collection.displayName?.equals(wanted, ignoreCase = true) == true ||
                url.trimEnd('/') == wanted.trimEnd('/') ||
                path.trimEnd('/') == wanted.trimEnd('/') ||
                last == wanted.trim('/')
        }
    }

}

class DavSyncStatsRepository(private val store: DavStore) {

    fun logSyncTime(collectionId: Long, dataType: SyncDataType, time: Long = System.currentTimeMillis()) =
        store.write { store.allSyncStats["$collectionId/${dataType.name}"] = time }

    fun lastSync(collectionId: Long, dataType: SyncDataType): Long? = store.read { store.allSyncStats["$collectionId/${dataType.name}"] }

}
